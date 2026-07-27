package com.openminis.app.data.repository

import android.content.Context
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.BookSourceEntity
import com.openminis.app.data.source.JsonPathEvaluator
import com.openminis.app.sandbox.PRootKernel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Book-source engine (legado-format, JSON-API subset).
 *
 * - Import: parse a legado source JSON (single object / array / remote URL) and
 *   persist it to Room. This is what `book_import_source` drives.
 * - Explore: given a source, fetch one of its `exploreUrl` category lines,
 *   substitute `{{page}}`/`{{genre}}`, then run [JsonPathEvaluator] over the
 *   response with the source's `ruleExplore` to produce [RemoteBook]s.
 * - Cache (on-demand, mirrors legado): clicking a remote book materialises a
 *   LOCAL book folder under /var/minis/books/{id} (so the existing file-based
 *   [BookRepository] + `book_*` agent tools work unchanged). Only the book
 *   *info + TOC* are fetched on click; each *chapter body* is lazily fetched
 *   the first time it is read and then persisted as `chapters/chNNN.md`
 *   ("cached" = the file holds a body). Nothing is bulk-downloaded.
 */
object BookSourceRepository {
    private val client = OkHttpClient.Builder().build()
    private const val BOOKS_DIR = "/var/minis/books"

    private fun dao(context: Context) = AppDatabase.getInstance(context).bookSourceDao()

    // ── Import ──────────────────────────────────────────────────────────

    /** Import from raw text: object, array, or a remote URL. Returns imported entities. */
    suspend fun importFromText(text: String, context: Context): List<BookSourceEntity> = withContext(Dispatchers.IO) {
        val t = text.trim()
        val sources = when {
            t.startsWith("http://") || t.startsWith("https://") -> importFromUrl(t, context)
            t.startsWith("[") -> parseArray(JSONArray(t))
            t.startsWith("{") -> listOfNotNull(parseObject(JSONObject(t)))
            else -> emptyList()
        }
        if (sources.isNotEmpty()) dao(context).insert(*sources.toTypedArray())
        sources
    }

    suspend fun importFromUrl(url: String, context: Context): List<BookSourceEntity> = withContext(Dispatchers.IO) {
        val body = try {
            client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() }
        } catch (_: Exception) {
            null
        } ?: return@withContext emptyList()
        importFromText(body, context)
    }

    private fun parseArray(arr: JSONArray): List<BookSourceEntity> {
        val out = mutableListOf<BookSourceEntity>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseObject(o)?.let { out.add(it) }
        }
        return out
    }

    private fun parseObject(o: JSONObject): BookSourceEntity? {
        val url = o.optString("bookSourceUrl", "")
        if (url.isEmpty()) return null
        fun rule(name: String): String? = o.optJSONObject(name)?.toString()
        return BookSourceEntity(
            bookSourceUrl = url,
            bookSourceName = o.optString("bookSourceName", url),
            bookSourceGroup = o.optString("bookSourceGroup", "").ifEmpty { null },
            enabledExplore = o.optBoolean("enabledExplore", true),
            exploreUrl = o.optString("exploreUrl", ""),
            ruleExploreJson = rule("ruleExplore") ?: "{}",
            ruleSearchJson = rule("ruleSearch"),
            ruleBookInfoJson = rule("ruleBookInfo"),
            ruleTocJson = rule("ruleToc"),
            ruleContentJson = rule("ruleContent"),
            header = o.optString("header", "").ifEmpty { null },
            lastUpdateTime = o.optLong("lastUpdateTime", 0),
        )
    }

    // ── Query ──────────────────────────────────────────────────────────

    suspend fun listSources(context: Context): List<BookSourceEntity> = dao(context).getAll()

    suspend fun deleteSource(url: String, context: Context) = dao(context).deleteByUrl(url)

    /** Category names parsed from a source's multi-line exploreUrl. */
    fun categories(source: BookSourceEntity): List<String> =
        source.exploreUrl.lines().map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("::") }
            .map { it.substringBefore("::") }

    // ── Explore ────────────────────────────────────────────────────────

    /**
     * Fetch one page of books for [source] under category [categoryIndex]
     * (index into [categories]). Returns the parsed books, or empty on error.
     */
    suspend fun exploreBooks(
        source: BookSourceEntity,
        categoryIndex: Int,
        page: Int,
        context: Context,
    ): List<RemoteBook> = withContext(Dispatchers.IO) {
        val lines = source.exploreUrl.lines().map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("::") }
        if (categoryIndex < 0 || categoryIndex >= lines.size) return@withContext emptyList()

        val urlPart = lines[categoryIndex].substringAfter("::")
        val resolved = resolveUrl(source.bookSourceUrl, urlPart)
            .replace("{{page}}", page.toString())
            .replace("{{key}}", "")
            .replace("{{genre}}", "")

        val reqBuilder = Request.Builder().url(resolved)
        parseHeader(source.header)?.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

        val body = try {
            client.newCall(reqBuilder.build()).execute().use { it.body?.string() }
        } catch (_: Exception) {
            null
        } ?: return@withContext emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext emptyList()

        val rule = runCatching { JSONObject(source.ruleExploreJson) }.getOrNull() ?: JSONObject()
        val bookListPath = rule.optString("bookList", "$.data")
        val items = JsonPathEvaluator.evalList(root, bookListPath)
            .mapNotNull { it as? JSONArray }
            .flatMap { arr -> (0 until arr.length()).map { arr.get(it) } }

        items.mapNotNull { it as? JSONObject }.map { obj ->
            RemoteBook(
                name = JsonPathEvaluator.evalString(obj, rule.optString("name", "$.name")) ?: "",
                author = JsonPathEvaluator.evalString(obj, rule.optString("author", "$.author")) ?: "",
                coverUrl = JsonPathEvaluator.evalString(obj, rule.optString("coverUrl", "$.coverUrl")) ?: "",
                bookUrl = JsonPathEvaluator.evalString(obj, rule.optString("bookUrl", "$.bookUrl")) ?: "",
                kind = JsonPathEvaluator.evalString(obj, rule.optString("kind", "$.kind")) ?: "",
                lastChapter = JsonPathEvaluator.evalString(obj, rule.optString("lastChapter", "$.lastChapter")) ?: "",
                intro = JsonPathEvaluator.evalString(obj, rule.optString("intro", "$.intro")) ?: "",
            )
        }
    }

    // ── On-demand cache (legado-style) ──────────────────────────────────

    /**
     * Materialise a local book folder for a remote book and cache its
     * info + TOC. Returns the local bookId (stable for the same source+bookUrl).
     * The chapter bodies are NOT fetched here — they are lazily fetched by
     * [readSourceChapter] when the user/agent actually opens a chapter.
     */
    suspend fun cacheBookInfo(
        source: BookSourceEntity,
        bookUrl: String,
        context: Context,
    ): String? = withContext(Dispatchers.IO) {
        val bookId = sourceBookId(source.bookSourceUrl, bookUrl)
        val tocUrl = resolveUrl(source.bookSourceUrl, bookUrl)
        val infoAndChapters = try {
            val reqBuilder = Request.Builder().url(tocUrl)
            parseHeader(source.header)?.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
            val body = client.newCall(reqBuilder.build()).execute().use { it.body?.string() }
            val root = runCatching { JSONObject(body ?: "") }.getOrNull() ?: return@withContext null
            parseBookInfoAndToc(root, source)
        } catch (_: Exception) {
            null
        } ?: return@withContext null

        val (info, chapters) = infoAndChapters
        BookRepository.createBook(bookId, info.name, "书源", info.intro, context)

        // Enrich book.json with source-cache markers so BookRepository/agent
        // can recognise this as a cached remote book.
        hostFile(bookId, "book.json", context)?.let { f ->
            runCatching {
                val json = JSONObject(f.readText())
                json.put("kind", "source-cache")
                json.put("sourceId", source.bookSourceUrl)
                json.put("sourceBookUrl", source.bookSourceUrl)
                json.put("remoteBookUrl", bookUrl)
                json.put("coverUrl", info.coverUrl)
                json.put("author", info.author)
                json.put("intro", info.intro)
                json.put("tocUrl", tocUrl)
                f.writeText(json.toString(2))
            }
        }

        // Write the TOC manifest (source of truth for remote chapter URLs +
        // cached flags) and lightweight placeholder chapter files so the book
        // shows up in lists immediately.
        val toc = JSONObject().apply {
            put("sourceId", source.bookSourceUrl)
            put("sourceBookUrl", source.bookSourceUrl)
            put("remoteBookUrl", bookUrl)
            put("ruleContentJson", source.ruleContentJson ?: "{}")
            put(
                "chapters",
                JSONArray().apply {
                    chapters.forEachIndexed { i, ch ->
                        put(
                            JSONObject().apply {
                                put("num", i + 1)
                                put("title", ch.title)
                                put("remoteUrl", ch.url)
                                put("cached", false)
                            },
                        )
                    }
                },
            )
        }
        hostFile(bookId, "source_toc.json", context)?.writeText(toc.toString(2))

        chapters.forEachIndexed { i, ch ->
            val f = hostFile(bookId, "chapters/ch${"%03d".format(i + 1)}.md", context) ?: return@forEachIndexed
            f.writeText("# ${ch.title}\n\n")
        }
        bookId
    }

    /** True if [bookId] is a cached remote (source) book. */
    fun isSourceBook(bookId: String, context: Context): Boolean {
        val f = hostFile(bookId, "book.json", context) ?: return false
        if (!f.exists()) return false
        return runCatching { JSONObject(f.readText()).optString("kind", "") == "source-cache" }.getOrDefault(false)
    }

    /** List a cached source book's chapters (with cached flags). */
    fun listSourceChapters(bookId: String, context: Context): List<SourceChapter> {
        val meta = readToc(bookId, context) ?: return emptyList()
        return meta.chapters
    }

    /**
     * Read a chapter of a cached source book. Lazily fetches + caches the body
     * on first access; afterwards the local file is the source of truth (so
     * agent edits persist). Returns the full file text (`# Title` + body) or
     * null if the chapter does not exist / cannot be fetched.
     */
    suspend fun readSourceChapter(
        bookId: String,
        num: Int,
        context: Context,
    ): String? = withContext(Dispatchers.IO) {
        val meta = readToc(bookId, context) ?: return@withContext null
        val ch = meta.chapters.firstOrNull { it.num == num } ?: return@withContext null
        val chFile = hostFile(bookId, "chapters/ch${"%03d".format(num)}.md", context) ?: return@withContext null
        if (ch.cached && chFile.exists()) return@withContext chFile.readText()
        // Fetch and cache on first read.
        val raw = fetchContent(meta.sourceBookUrl, ch.remoteUrl, meta.ruleContentJson) ?: return@withContext null
        chFile.writeText("# ${ch.title}\n\n${htmlToText(raw)}")
        setCachedFlag(bookId, num, true, context)
        chFile.readText()
    }

    /** Ensure a chapter body is present locally (fetch if needed). */
    suspend fun ensureChapterCached(bookId: String, num: Int, context: Context): Boolean = withContext(Dispatchers.IO) {
        val meta = readToc(bookId, context) ?: return@withContext false
        val ch = meta.chapters.firstOrNull { it.num == num } ?: return@withContext false
        if (ch.cached) return@withContext true
        val chFile = hostFile(bookId, "chapters/ch${"%03d".format(num)}.md", context) ?: return@withContext false
        val raw = fetchContent(meta.sourceBookUrl, ch.remoteUrl, meta.ruleContentJson) ?: return@withContext false
        chFile.writeText("# ${ch.title}\n\n${htmlToText(raw)}")
        setCachedFlag(bookId, num, true, context)
        true
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun sourceBookId(sourceUrl: String, bookUrl: String): String {
        val a = kotlin.math.abs(sourceUrl.hashCode()).toString(36)
        val b = kotlin.math.abs(bookUrl.hashCode()).toString(36)
        return "src_${a}_$b"
    }

    private data class BookInfo(
        val name: String,
        val author: String,
        val intro: String,
        val coverUrl: String,
    )

    private data class ChapterRef(val title: String, val url: String)

    private fun parseBookInfoAndToc(root: JSONObject, source: BookSourceEntity): Pair<BookInfo, List<ChapterRef>>? {
        val infoRule = runCatching { JSONObject(source.ruleBookInfoJson ?: "{}") }.getOrNull() ?: JSONObject()
        val tocRule = runCatching { JSONObject(source.ruleTocJson ?: "{}") }.getOrNull() ?: JSONObject()

        val name = JsonPathEvaluator.evalString(root, infoRule.optString("name", "$.data.name")) ?: return null
        val author = JsonPathEvaluator.evalString(root, infoRule.optString("author", "$.data.author")) ?: ""
        val intro = JsonPathEvaluator.evalString(root, infoRule.optString("intro", "$.data.intro")) ?: ""
        val coverUrl = JsonPathEvaluator.evalString(root, infoRule.optString("coverUrl", "$.data.coverUrl")) ?: ""

        val listPath = tocRule.optString("chapterList", "$.data.chapters")
        val arr = JsonPathEvaluator.evalList(root, listPath)
            .mapNotNull { it as? JSONArray }.firstOrNull()
        val chapters = if (arr == null) {
            emptyList()
        } else {
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = JsonPathEvaluator.evalString(obj, tocRule.optString("chapterName", "$.title")) ?: "第${i + 1}章"
                val url = JsonPathEvaluator.evalString(obj, tocRule.optString("chapterUrl", "$.chapterUrl")) ?: return@mapNotNull null
                ChapterRef(title, url)
            }
        }
        return Pair(BookInfo(name, author, intro, coverUrl), chapters)
    }

    private fun fetchContent(sourceBookUrl: String, remoteUrl: String, ruleContentJson: String): String? {
        val url = resolveUrl(sourceBookUrl, remoteUrl)
        val body = try {
            client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() }
        } catch (_: Exception) {
            null
        } ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val rule = runCatching { JSONObject(ruleContentJson) }.getOrNull() ?: JSONObject()
        val content = JsonPathEvaluator.evalString(root, rule.optString("content", "$.data.content")) ?: return null
        // legado ruleContent can declare nextContentUrl for paginated chapters.
        val nextPath = rule.optString("nextContentUrl", "")
        val next = if (nextPath.isNotBlank()) {
            JsonPathEvaluator.evalString(root, nextPath)
        } else {
            null
        }
        return if (!next.isNullOrBlank()) {
            "$content\n${fetchContent(sourceBookUrl, next, ruleContentJson) ?: ""}"
        } else {
            content
        }
    }

    private fun readToc(bookId: String, context: Context): SourceToc? {
        val f = hostFile(bookId, "source_toc.json", context) ?: return null
        if (!f.exists()) return null
        return runCatching {
            val json = JSONObject(f.readText())
            val arr = json.optJSONArray("chapters") ?: JSONArray()
            val chapters = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SourceChapter(
                    num = o.optInt("num"),
                    title = o.optString("title"),
                    remoteUrl = o.optString("remoteUrl"),
                    cached = o.optBoolean("cached"),
                )
            }
            SourceToc(
                sourceId = json.optString("sourceId"),
                sourceBookUrl = json.optString("sourceBookUrl"),
                remoteBookUrl = json.optString("remoteBookUrl"),
                ruleContentJson = json.optString("ruleContentJson", "{}"),
                chapters = chapters,
            )
        }.getOrNull()
    }

    private fun setCachedFlag(bookId: String, num: Int, cached: Boolean, context: Context) {
        val f = hostFile(bookId, "source_toc.json", context) ?: return
        if (!f.exists()) return
        runCatching {
            val json = JSONObject(f.readText())
            val arr = json.optJSONArray("chapters") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optInt("num") == num) {
                    o.put("cached", cached)
                    break
                }
            }
            json.put("chapters", arr)
            f.writeText(json.toString(2))
        }
    }

    /** Strip HTML tags / entities to plain text for `.md` storage. */
    private fun htmlToText(html: String): String {
        var s = html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<p\\s*/?>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]+>"), "")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        return s.trim()
    }

    private fun hostFile(bookId: String, relativePath: String, context: Context): File? =
        PRootKernel.resolveSessionHostPath("", "$BOOKS_DIR/$bookId/$relativePath", context)

    private fun resolveUrl(base: String, rel: String): String {
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        return base.trimEnd('/') + "/" + rel.trimStart('/')
    }

    private fun parseHeader(header: String?): Map<String, String>? {
        if (header.isNullOrBlank()) return null
        return header.lines().mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }.toMap().ifEmpty { null }
    }
}

/** A single book discovered from a remote book source (not yet on local disk). */
data class RemoteBook(
    val name: String,
    val author: String,
    val coverUrl: String,
    val bookUrl: String,
    val kind: String,
    val lastChapter: String,
    val intro: String,
)

/** A cached chapter of a source book: its remote URL plus whether the body is local. */
data class SourceChapter(
    val num: Int,
    val title: String,
    val remoteUrl: String,
    val cached: Boolean,
)

/** Parsed [source_toc.json] manifest for a cached source book. */
private data class SourceToc(
    val sourceId: String,
    val sourceBookUrl: String,
    val remoteBookUrl: String,
    val ruleContentJson: String,
    val chapters: List<SourceChapter>,
)
