package com.openminis.app.data.repository

import android.content.Context
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

/**
 * File-based book project repository.
 *
 * Each book is a directory under /var/minis/books/{bookId}/ with:
 *   book.json          — metadata (title, genre, synopsis, status, created/updated timestamps)
 *   outline.md         — two-level outline (story units + beats)
 *   chapters/          — ch001.md, ch002.md, …
 *   characters/        — {charId}.md
 *   worldview/         — {entryId}.md
 *   notes/             — {noteId}.md
 *   summaries/         — ch001.summary.md, …
 *   .skills/           — book-level skills
 *   .git/              — git repo for version control
 *
 * Room is NOT used — "file is the database" keeps things simple and makes
 * git-based version tracking trivial.
 */
object BookRepository {

    private const val TAG = "BookRepository"
    private const val BOOKS_DIR = "/var/minis/books"

    /** Root directory for all book projects (Linux path). */
    fun booksBasePath() = BOOKS_DIR

    fun booksListPath() = BOOKS_DIR

    data class MiniBook(
        val id: String,
        val title: String,
        val genre: String,
        val synopsis: String,
        val status: String,          // drafting, paused, completed, archived
        val totalWords: Int,
        val currentChapter: Int,
        val createdAt: Long,
        val updatedAt: Long,
        val kind: String = "",       // "source-cache" for books pulled from a remote book source
    )

    data class BookChapter(
        val num: Int,
        val title: String,
        val wordCount: Int,
        val hasSummary: Boolean,
    )

    /** List all known books from the filesystem. */
    fun listBooks(context: Context): List<MiniBook> {
        val rootDir = PRootKernel.resolveSessionHostPath("", BOOKS_DIR, context) ?: return emptyList()
        if (!rootDir.isDirectory) return emptyList()
        return (rootDir.listFiles()?.filter { it.isDirectory } ?: emptyList())
            .mapNotNull { f -> loadBook(f.name, context) }
            .sortedByDescending { it.updatedAt }
    }

    /** Load book metadata from book.json. Returns null if invalid. */
    fun loadBook(bookId: String, context: Context): MiniBook? {
        val jsonFile = resolveHostFile(bookId, "book.json", context) ?: return null
        if (!jsonFile.exists()) return null
        return try {
            val json = JSONObject(jsonFile.readText())
            val chaptersDir = resolveHostFile(bookId, "chapters", context) ?: return null
            val chapterCount = chaptersDir.listFiles()?.count { it.name.endsWith(".md") } ?: 0
            MiniBook(
                id = bookId,
                title = json.optString("title", bookId),
                genre = json.optString("genre", ""),
                synopsis = json.optString("synopsis", ""),
                status = json.optString("status", "drafting"),
                totalWords = json.optInt("totalWords", 0),
                currentChapter = chapterCount,
                createdAt = json.optLong("createdAt", jsonFile.lastModified()),
                updatedAt = jsonFile.lastModified(),
                kind = json.optString("kind", ""),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Create a new book project directory with default files. */
    fun createBook(bookId: String, title: String, genre: String, synopsis: String, context: Context) {
        val hostRoot = resolveHostDir(bookId, context) ?: return
        hostRoot.mkdirs()
        File(hostRoot, "chapters").mkdirs()
        File(hostRoot, "characters").mkdirs()
        File(hostRoot, "worldview").mkdirs()
        File(hostRoot, "notes").mkdirs()
        File(hostRoot, "summaries").mkdirs()
        File(hostRoot, ".skills").mkdirs()

        val meta = JSONObject().apply {
            put("id", bookId)
            put("title", title)
            put("genre", genre)
            put("synopsis", synopsis)
            put("status", "drafting")
            put("totalWords", 0)
            put("currentChapter", 0)
            put("createdAt", System.currentTimeMillis())
        }
        File(hostRoot, "book.json").writeText(meta.toString(2))

        val outline = """# $title — Outline

## Story Units

### Unit 1: [Title]
- **Goal:** 
- **Conflict:** 
- **Result:** 
- **Beats:**
  1. [Beat 1]

---

## Characters
*(use characters/ for individual character cards)*

## World
*(use worldview/ for individual entries)*

## Notes
*(use notes/ for individual notes)*
"""
        File(hostRoot, "outline.md").writeText(outline)

        // init git repo
        try {
            Runtime.getRuntime().exec(arrayOf("git", "init"), null, hostRoot)
        } catch (_: Exception) {}
    }

    /** Delete a book project (irreversible). */
    fun deleteBook(bookId: String, context: Context) {
        val hostRoot = resolveHostDir(bookId, context) ?: return
        hostRoot.deleteRecursively()
    }

    /**
     * Delete a single chapter file by number (1-based). Does NOT renumber
     * subsequent chapters - chapter numbers are stable identifiers, so
     * deleting ch003 leaves a gap rather than shifting ch004->ch003 (which
     * would silently invalidate any outline / summary references). The
     * summary file (if any) is also removed. Returns true on success.
     */
    fun deleteChapter(bookId: String, num: Int, context: Context): Boolean {
        val f = chapterFile(bookId, num, context) ?: return false
        val existed = f.delete()
        // Drop the matching summary too so listChapters' hasSummary flag stays honest.
        runCatching { summaryFile(bookId, num, context).delete() }
        if (existed) refreshBookMeta(bookId, context)
        return existed
    }

    /**
     * Agent-friendly import: split a TXT file already on the host filesystem
     * (resolved via the book-id host dir, so a path under /var/minis/... works)
     * into chapters under a new book project. Mirrors [importBook] but takes a
     * host [sourcePath] instead of a content:// Uri - the agent can't drive a
     * file picker, so it points at a file the user already placed (e.g. via
     * file_write or a mounted folder).
     *
     * @return the new bookId, or null on failure.
     */
    fun importBookFromPath(
        title: String,
        sourcePath: String,
        regex: String,
        charset: java.nio.charset.Charset,
        context: Context,
        progress: ((written: Int, total: Int) -> Unit)? = null,
    ): String? {
        // Resolve the source through the sandbox mapper (a /var/minis/mounts/*
        // path maps to the real host file via bind mounts); fall back to a
        // raw File for paths that already live on the Android host FS. Without
        // this, sandbox paths are never found and every import fails.
        val src = PRootKernel.resolveSessionHostPath("", sourcePath, context)
            ?.takeIf { it.isFile }
            ?: File(sourcePath).takeIf { it.isFile }
            ?: return null
        val chapters = try {
            src.inputStream().use { input ->
                com.openminis.app.data.imports.TxtChapterSplitter.split(input, regex, charset)
            }
        } catch (_: Exception) {
            return null
        }
        if (chapters.isEmpty()) return null

        val bookId = "import_${System.currentTimeMillis()}"
        createBook(bookId, title, "imported", "Imported from $sourcePath.", context)
        chapters.forEachIndexed { idx, ch ->
            val num = idx + 1
            val f = chapterFile(bookId, num, context) ?: return@forEachIndexed
            f.writeText("# ${ch.title}\n\n${ch.content}")
            progress?.invoke(idx + 1, chapters.size)
        }
        refreshBookMeta(bookId, context)
        return bookId
    }

    /**
     * Import a TXT file as a new book, splitting it into chapters with a
     * regex (ported from legado's TextFile.analyze). The file is read via
     * ContentResolver, split in-memory by [TxtChapterSplitter], then each
     * chapter is written to `chapters/ch{NNN}.md` (first line `# {title}`)
     * under a freshly-created book project. [progress] is invoked with the
     * number of chapters written so far (for UI progress).
     *
     * @return the new bookId, or null on failure (no read access / IO error).
     */
    fun importBook(
        title: String,
        sourceUri: android.net.Uri,
        regex: String,
        charset: java.nio.charset.Charset,
        context: Context,
        progress: ((written: Int, total: Int) -> Unit)? = null,
    ): String? {
        val bookId = "import_${System.currentTimeMillis()}"
        // Read & split the source first - if this fails we don't leave a
        // half-created book directory behind.
        val chapters = try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                com.openminis.app.data.imports.TxtChapterSplitter.split(input, regex, charset)
            } ?: return null
        } catch (_: Exception) {
            return null
        }
        if (chapters.isEmpty()) return null

        // Create the book skeleton (directories, book.json, outline, git).
        createBook(bookId, title, "imported", "Imported from TXT.", context)

        // Write each chapter. Match the existing ch{NNN}.md convention and
        // the "# Title" heading that listChapters / writeChapter expect.
        chapters.forEachIndexed { idx, ch ->
            val num = idx + 1
            val f = chapterFile(bookId, num, context) ?: return@forEachIndexed
            val heading = "# ${ch.title}\n\n"
            f.writeText(heading + ch.content)
            progress?.invoke(idx + 1, chapters.size)
        }
        refreshBookMeta(bookId, context)

        // Commit to git so the imported state is recoverable.
        val hostRoot = resolveHostDir(bookId, context)
        if (hostRoot != null) {
            try {
                Runtime.getRuntime().exec(arrayOf("git", "add", "-A"), null, hostRoot).waitFor()
                Runtime.getRuntime()
                    .exec(arrayOf("git", "commit", "-m", "Import ${chapters.size} chapters"), null, hostRoot)
                    .waitFor()
            } catch (_: Exception) {}
        }
        return bookId
    }

    // ── Chapter operations ─────────────────────────────────────────────

    fun listChapters(bookId: String, context: Context): List<BookChapter> {
        val dir = resolveHostFile(bookId, "chapters", context) ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.endsWith(".md") && it.name.matches(Regex("""ch\d+\.md""")) }
            ?.mapNotNull { f ->
                val num = f.name.removePrefix("ch").removeSuffix(".md").toIntOrNull() ?: return@mapNotNull null
                val text = f.readText()
                val firstLine = text.lines().firstOrNull { it.startsWith("# ") }
                val title = firstLine?.removePrefix("# ")?.trim() ?: "第${num}章"
                BookChapter(num, title, text.length, hasSummary = summaryFile(bookId, num, context).exists())
            }
            ?.sortedBy { it.num }
            ?: emptyList()
    }

    fun readChapter(bookId: String, num: Int, context: Context): String? {
        val f = chapterFile(bookId, num, context) ?: return null
        if (!f.exists()) return null
        return f.readText()
    }

    fun writeChapter(bookId: String, num: Int, title: String?, content: String, append: Boolean, context: Context): String {
        val f = chapterFile(bookId, num, context) ?: return "Error: cannot resolve chapter path"
        if (append && f.exists()) {
            f.appendText("\n$content")
        } else {
            val heading = if (title != null) "# $title\n\n" else ""
            f.writeText(heading + content)
        }
        refreshBookMeta(bookId, context)
        return "Written: ch${"%03d".format(num)}.md (${f.length()} bytes)"
    }

    fun editChapter(bookId: String, num: Int, find: String, replace: String, replaceAll: Boolean, context: Context): String {
        val f = chapterFile(bookId, num, context) ?: return "Error: cannot resolve chapter path"
        if (!f.exists()) return "Error: chapter $num not found"
        val text = f.readText()
        val newText = if (replaceAll) text.replace(find, replace) else text.replaceFirst(find, replace)
        if (newText == text) return "No match found"
        f.writeText(newText)
        refreshBookMeta(bookId, context)
        return "Edited: ${if (replaceAll) "replace_all" else "replace"} done (${f.length()} bytes)"
    }

    // ── Outline ────────────────────────────────────────────────────────

    fun readOutline(bookId: String, context: Context): String {
        val f = resolveHostFile(bookId, "outline.md", context) ?: return ""
        return if (f.exists()) f.readText() else ""
    }

    fun writeOutline(bookId: String, content: String, context: Context) {
        val f = resolveHostFile(bookId, "outline.md", context) ?: return
        f.writeText(content)
    }

    // ── Reference (character / worldview / note) ───────────────────────

    private val REFERENCE_TYPES = setOf("characters", "worldview", "notes")

    fun listReferences(bookId: String, type: String, context: Context): List<String> {
        if (type !in REFERENCE_TYPES) return emptyList()
        val dir = resolveHostFile(bookId, type, context) ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.filter { it.name.endsWith(".md") }?.map { it.nameWithoutExtension } ?: emptyList()
    }

    fun readReference(bookId: String, type: String, name: String, context: Context): String? {
        if (type !in REFERENCE_TYPES) return null
        val f = resolveHostFile(bookId, "$type/$name.md", context) ?: return null
        return if (f.exists()) f.readText() else null
    }

    fun writeReference(bookId: String, type: String, name: String, content: String, context: Context): String {
        if (type !in REFERENCE_TYPES) return "Error: unknown reference type '$type'"
        val dir = resolveHostFile(bookId, type, context) ?: return "Error: cannot resolve $type directory"
        dir.mkdirs()
        File(dir, "$name.md").writeText(content)
        return "Written: $type/$name.md"
    }

    fun deleteReference(bookId: String, type: String, name: String, context: Context): String {
        if (type !in REFERENCE_TYPES) return "Error: unknown reference type '$type'"
        val f = resolveHostFile(bookId, "$type/$name.md", context)
        if (f?.delete() == true) return "Deleted: $type/$name.md"
        return "Error: could not delete $type/$name.md"
    }

    // ── Summaries ──────────────────────────────────────────────────────

    fun readSummary(bookId: String, num: Int, context: Context): String? {
        val f = summaryFile(bookId, num, context)
        return if (f.exists()) f.readText() else null
    }

    fun writeSummary(bookId: String, num: Int, content: String, context: Context) {
        val f = summaryFile(bookId, num, context)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    /** Build the 4-layer context string for a chapter. */
    fun buildContext(bookId: String, chapterNum: Int, maxTokens: Int = 30000, context: Context): String {
        val sb = StringBuilder()

        // Layer 1: current chapter
        val current = readChapter(bookId, chapterNum, context) ?: ""
        sb.appendLine("=== Current Chapter (ch${"%03d".format(chapterNum)}) ===")
        sb.appendLine(current.take(maxTokens / 4))
        sb.appendLine()

        // Layer 2: near — up to 9 previous chapters
        val nearStart = (chapterNum - 9).coerceAtLeast(1)
        sb.appendLine("=== Previous Chapters (ch${"%03d".format(nearStart)} ~ ch${"%03d".format((chapterNum - 1).coerceAtLeast(1))}) ===")
        for (n in nearStart until chapterNum) {
            val ch = readChapter(bookId, n, context)
            if (ch != null) {
                sb.appendLine("--- ch${"%03d".format(n)} ---")
                sb.appendLine(ch.take(maxTokens / 6))
            }
        }
        sb.appendLine()

        // Layer 3: chapter list
        val chapters = listChapters(bookId, context)
        sb.appendLine("=== Chapter List (${chapters.size} chapters) ===")
        chapters.forEach { c ->
            sb.appendLine("ch${"%03d".format(c.num)}: ${c.title} (${c.wordCount}字)")
        }

        val result = sb.toString()
        return if (result.length > maxTokens) result.take(maxTokens) + "\n... (truncated)" else result
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /** Resolve a linux path under a book to the host filesystem path. */
    private fun resolveHostFile(bookId: String, relativePath: String, context: Context): File? {
        val linuxPath = "$BOOKS_DIR/$bookId/$relativePath"
        return PRootKernel.resolveSessionHostPath("", linuxPath, context)
    }

    private fun resolveHostDir(bookId: String, context: Context): File? {
        val linuxPath = "$BOOKS_DIR/$bookId"
        return PRootKernel.resolveSessionHostPath("", linuxPath, context)
    }

    private fun chapterFile(bookId: String, num: Int, context: Context): File? {
        return resolveHostFile(bookId, "chapters/ch${"%03d".format(num)}.md", context)
    }

    private fun summaryFile(bookId: String, num: Int, context: Context): File {
        val hostRoot = resolveHostDir(bookId, context) ?: run {
            return File("/dev/null")
        }
        return File(File(hostRoot, "summaries"), "ch${"%03d".format(num)}.summary.md")
    }

    /** Refresh word count and timestamp in book.json. */
    private fun refreshBookMeta(bookId: String, context: Context) {
        val jsonFile = resolveHostFile(bookId, "book.json", context) ?: return
        if (!jsonFile.exists()) return
        try {
            val json = JSONObject(jsonFile.readText())
            val chaptersDir = resolveHostFile(bookId, "chapters", context)
            var totalWords = 0
            chaptersDir?.listFiles()?.filter { it.name.endsWith(".md") }?.forEach { totalWords += it.length().toInt() }
            json.put("totalWords", totalWords)
            json.put("updatedAt", System.currentTimeMillis())
            jsonFile.writeText(json.toString(2))
        } catch (_: Exception) {}
    }
}
