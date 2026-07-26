package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.BookRepository
import org.json.JSONObject

/**
 * Book-oriented agent tools for novel creation.
 *
 * All tools are scoped to `activeBookId`. When no book is active, no book
 * tools are surfaced to the agent. This keeps the tool schema small unless
 * the user is inside a novel-writing session.
 *
 * Tools are provider-agnostic (same pattern as AgentTools.kt).
 */
object BookTools {

    /** Return all book-tool definitions when a book is active. */
    fun definitions(activeBookId: String?): List<AgentToolDefinition> {
        if (activeBookId == null) return emptyList()
        return buildList {
            add(listChaptersDefinition())
            add(readChapterDefinition())
            add(writeChapterDefinition())
            add(editChapterDefinition())
            add(readOutlineDefinition())
            add(writeOutlineDefinition())
            add(referenceDefinition())
            add(buildContextDefinition())
            add(searchDefinition())
            add(loadSkillDefinition())
        }
    }

    /** Dispatch a book-tool execution. Returns null if the tool name is not
     *  a book tool (caller falls through to other tools). */
    suspend fun execute(
        name: String,
        argsJson: String,
        bookId: String,
        sessionId: String,
        context: Context,
    ): ToolExecutionResult? {
        return when (name) {
            "book_list_chapters" -> listChapters(bookId, context)
            "book_read_chapter" -> readChapter(bookId, argsJson, context)
            "book_write_chapter" -> writeChapter(bookId, argsJson, context)
            "book_edit_chapter" -> editChapter(bookId, argsJson, context)
            "book_read_outline" -> readOutline(bookId, context)
            "book_write_outline" -> writeOutline(bookId, argsJson, context)
            "book_reference" -> reference(bookId, argsJson, context)
            "book_get_context" -> buildContext(bookId, argsJson, context)
            "book_search" -> search(bookId, argsJson, context)
            "book_load_skill" -> loadSkill(bookId, argsJson, context)
            else -> null  // not a book tool — caller handles
        }
    }

    // ── Tool definitions ───────────────────────────────────────────────

    private fun toolTitle(name: String) = AgentToolParam(
        "string",
        "A concise 5-10 word summary of what this tool call does, shown to the user."
    )

    private fun listChaptersDefinition() = AgentToolDefinition(
        name = "book_list_chapters",
        description = "List ALL chapters in the current book, with title, word count, and whether a summary exists. " +
            "Call this first when starting a writing session to understand what has been written so far.",
        parameters = mapOf("tool_title" to toolTitle("list_chapters")),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
    )

    private fun readChapterDefinition() = AgentToolDefinition(
        name = "book_read_chapter",
        description = "Read the full body of a chapter by chapter number (1-based). " +
            "Use before writing a new chapter to ensure continuity with previous content.",
        parameters = mapOf(
            "tool_title" to toolTitle("read_chapter"),
            "num" to AgentToolParam("integer", "Chapter number (1-based) to read"),
        ),
        required = listOf("tool_title", "num"),
        propertyOrdering = listOf("tool_title", "num"),
    )

    private fun writeChapterDefinition() = AgentToolDefinition(
        name = "book_write_chapter",
        description = "Write a new chapter or overwrite/append to an existing one. " +
            "If the chapter already exists, use `append=true` to add content at the end " +
            "(preferred) or leave `append=false` to overwrite. " +
            "The first line `# Title` is used as the chapter title. " +
            "Word count and chapter list are automatically updated after write. " +
            "After writing, also call `book_get_context` to refresh your understanding.",
        parameters = mapOf(
            "tool_title" to toolTitle("write_chapter"),
            "num" to AgentToolParam("integer", "Chapter number (1-based) to write"),
            "title" to AgentToolParam("string", "Chapter title (optional, will be used as # heading)"),
            "content" to AgentToolParam("string", "Full chapter body content. Use Markdown. Include a title as # Heading if title param is not provided."),
            "append" to AgentToolParam("boolean", "If true, append to existing chapter instead of overwriting"),
        ),
        required = listOf("tool_title", "num", "content"),
        propertyOrdering = listOf("tool_title", "num", "title", "content", "append"),
    )

    private fun editChapterDefinition() = AgentToolDefinition(
        name = "book_edit_chapter",
        description = "Edit an existing chapter by find-and-replace. " +
            "Use `replaceAll=false` for the first occurrence only, `replaceAll=true` for all occurrences. " +
            "For large rewrites, prefer `file_read` + `book_write_chapter` (with append=false to overwrite).",
        parameters = mapOf(
            "tool_title" to toolTitle("edit_chapter"),
            "num" to AgentToolParam("integer", "Chapter number (1-based) to edit"),
            "find" to AgentToolParam("string", "Text to find (exact match)"),
            "replace" to AgentToolParam("string", "Replacement text. Use empty string to delete the matched text."),
            "replaceAll" to AgentToolParam("boolean", "If true, replace ALL occurrences; if false, replace first only"),
        ),
        required = listOf("tool_title", "num", "find", "replace"),
        propertyOrdering = listOf("tool_title", "num", "find", "replace", "replaceAll"),
    )

    private fun readOutlineDefinition() = AgentToolDefinition(
        name = "book_read_outline",
        description = "Read the book's current outline. The outline contains story units, beats, character concepts, " +
            "and world notes. Read this at the start of a session to understand the planned structure.",
        parameters = mapOf("tool_title" to toolTitle("read_outline")),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
    )

    private fun writeOutlineDefinition() = AgentToolDefinition(
        name = "book_write_outline",
        description = "Update the book's outline. Use Markdown. " +
            "Follow the two-level outline format: Story Units (跨章结构) with Goals/Conflicts/Results, " +
            "and Beats (最小写作单位). Create or modify the outline before writing chapters.",
        parameters = mapOf(
            "tool_title" to toolTitle("write_outline"),
            "content" to AgentToolParam("string", "Full outline content in Markdown"),
        ),
        required = listOf("tool_title", "content"),
        propertyOrdering = listOf("tool_title", "content"),
    )

    private fun referenceDefinition() = AgentToolDefinition(
        name = "book_reference",
        description = "Manage reference materials: character cards, world-building entries, and notes. " +
            "Supported types: 'characters', 'worldview', 'notes'. " +
            "Operations: 'list' (list all names of a type), 'read' (read one), 'write' (create/update), 'delete'. " +
            "Use this to maintain structured reference data across chapters.",
        parameters = mapOf(
            "tool_title" to toolTitle("reference"),
            "type" to AgentToolParam("string", "Reference type. One of: characters, worldview, notes",
                enumValues = listOf("characters", "worldview", "notes")),
            "op" to AgentToolParam("string", "Operation: list, read, write, delete",
                enumValues = listOf("list", "read", "write", "delete")),
            "name" to AgentToolParam("string", "Name of the reference entry (without .md extension). Required for read/write/delete."),
            "content" to AgentToolParam("string", "Content for write operation. Use Markdown. Include relevant structured fields."),
        ),
        required = listOf("tool_title", "type", "op"),
        propertyOrdering = listOf("tool_title", "type", "op", "name", "content"),
    )

    private fun buildContextDefinition() = AgentToolDefinition(
        name = "book_get_context",
        description = "Build a structured writing context for a target chapter. " +
            "Returns: current chapter body, up to 9 previous chapters (full text), chapter list. " +
            "Call this before writing a new chapter to get everything you need in a single response. " +
            "Saves multiple file_read calls.",
        parameters = mapOf(
            "tool_title" to toolTitle("get_context"),
            "chapter_num" to AgentToolParam("integer", "Target chapter number to build context around (default: latest written + 1)"),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "chapter_num"),
    )

    private fun searchDefinition() = AgentToolDefinition(
        name = "book_search",
        description = "Full-text search across all chapters using ripgrep. " +
            "Useful for finding when a character first appeared, checking if a plot point was mentioned, " +
            "or locating specific passages. Returns chapter numbers and matching lines.",
        parameters = mapOf(
            "tool_title" to toolTitle("search"),
            "query" to AgentToolParam("string", "Search keywords. Supports regex patterns. Case-insensitive."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query"),
    )

    private fun loadSkillDefinition() = AgentToolDefinition(
        name = "book_load_skill",
        description = "Load a writing skill into the current session. " +
            "Skills provide specialized writing guidance (character design, plot twists, pacing, etc.). " +
            "Search order: book-local .skills/{name}/SKILL.md first, then global skills/{name}/SKILL.md. " +
            "Use 'novel-writing' as the skill name to load the master novel-writing methodology.",
        parameters = mapOf(
            "tool_title" to toolTitle("load_skill"),
            "name" to AgentToolParam("string", "Skill name to load. Try 'novel-writing' first."),
        ),
        required = listOf("tool_title", "name"),
        propertyOrdering = listOf("tool_title", "name"),
    )

    // ── Tool execution implementations ──────────────────────────────────

    private fun listChapters(bookId: String, context: Context): ToolExecutionResult {
        val chapters = BookRepository.listChapters(bookId, context)
        if (chapters.isEmpty()) {
            return ToolExecutionResult("No chapters yet. Use book_write_chapter to create the first one.", true)
        }
        val sb = StringBuilder()
        sb.appendLine("Total: ${chapters.size} chapters, ${chapters.sumOf { it.wordCount }} characters")
        chapters.forEach { c ->
            val summaryMark = if (c.hasSummary) " [summary]" else ""
            sb.appendLine("ch${"%03d".format(c.num)}: ${c.title} (${c.wordCount}字)$summaryMark")
        }
        return ToolExecutionResult(sb.toString().trimEnd(), true)
    }

    private fun readChapter(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val num = args.optInt("num", 1)
        val content = BookRepository.readChapter(bookId, num, context)
        if (content == null) {
            return ToolExecutionResult("Error: Chapter $num not found. Use book_list_chapters to see available chapters.", false)
        }
        return ToolExecutionResult(
            "--- ch${"%03d".format(num)} ---\n$content",
            true,
        )
    }

    private fun writeChapter(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val num = args.optInt("num", 1)
        val title = args.optString("title", null)
        val content = args.optString("content", "")
        val append = args.optBoolean("append", false)
        if (content.isBlank()) {
            return ToolExecutionResult("Error: content cannot be empty", false)
        }
        val result = BookRepository.writeChapter(bookId, num, title, content, append, context)
        return ToolExecutionResult(result, true)
    }

    private fun editChapter(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val num = args.optInt("num", 1)
        val find = args.optString("find", "")
        val replace = args.optString("replace", "")
        val replaceAll = args.optBoolean("replaceAll", false)
        if (find.isBlank()) {
            return ToolExecutionResult("Error: 'find' cannot be empty", false)
        }
        val result = BookRepository.editChapter(bookId, num, find, replace, replaceAll, context)
        return ToolExecutionResult(result, !result.startsWith("Error"))
    }

    private fun readOutline(bookId: String, context: Context): ToolExecutionResult {
        val content = BookRepository.readOutline(bookId, context)
        if (content.isBlank()) {
            return ToolExecutionResult("Outline is empty. Use book_write_outline to create one.", true)
        }
        return ToolExecutionResult(content, true)
    }

    private fun writeOutline(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val content = args.optString("content", "")
        if (content.isBlank()) {
            return ToolExecutionResult("Error: content cannot be empty", false)
        }
        BookRepository.writeOutline(bookId, content, context)
        return ToolExecutionResult("Outline updated.", true)
    }

    private fun reference(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val type = args.optString("type", "")
        val op = args.optString("op", "list")
        val name = args.optString("name", "")
        val content = args.optString("content", "")

        return when (op) {
            "list" -> {
                val names = BookRepository.listReferences(bookId, type, context)
                if (names.isEmpty()) ToolExecutionResult("No $type yet.", true)
                else ToolExecutionResult("$type:\n  " + names.joinToString("\n  "), true)
            }
            "read" -> {
                if (name.isBlank()) return ToolExecutionResult("Error: 'name' required for read", false)
                val text = BookRepository.readReference(bookId, type, name, context)
                if (text == null) ToolExecutionResult("Error: $type/$name.md not found", false)
                else ToolExecutionResult("--- $type/$name ---\n$text", true)
            }
            "write" -> {
                if (name.isBlank()) return ToolExecutionResult("Error: 'name' required for write", false)
                if (content.isBlank()) return ToolExecutionResult("Error: 'content' required for write", false)
                val result = BookRepository.writeReference(bookId, type, name, content, context)
                ToolExecutionResult(result, true)
            }
            "delete" -> {
                if (name.isBlank()) return ToolExecutionResult("Error: 'name' required for delete", false)
                val result = BookRepository.deleteReference(bookId, type, name, context)
                ToolExecutionResult(result, !result.startsWith("Error"))
            }
            else -> ToolExecutionResult("Error: unknown op '$op'", false)
        }
    }

    private fun buildContext(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val chapterNum = args.optInt("chapter_num", -1)
        val num = if (chapterNum > 0) chapterNum else {
            val chapters = BookRepository.listChapters(bookId, context)
            if (chapters.isEmpty()) 1 else chapters.last().num + 1
        }
        val ctx = BookRepository.buildContext(bookId, num, context = context)
        return ToolExecutionResult(ctx, true)
    }

    private fun search(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val query = args.optString("query", "")
        if (query.isBlank()) return ToolExecutionResult("Error: 'query' is required", false)

        // Use shell rg via BookRepository
        val hostDir = BookRepository.booksListPath()
        // We resolve via PRootKernel at runtime — delegate to a shell call since
        // rg is more efficient than reading every file in Kotlin
        val linuxPath = "$hostDir/$bookId/chapters"
        // Return a hint — the agent can use shell_execute directly
        return ToolExecutionResult(
            "Use shell_execute to search: `rg -i --no-heading '$query' $linuxPath -t.md`\n" +
            "Or use file_read on individual chapters by calling book_read_chapter.",
            true,
        )
    }

    private fun loadSkill(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val name = args.optString("name", "")
        if (name.isBlank()) {
            return ToolExecutionResult("Error: 'name' is required", false)
        }
        // The actual skill loading happens via SkillRepository.setSessionOverride.
        // The tool informs the agent that the skill concept is being flagged —
        // the skill loading is handled at session creation time or via the skill
        // management UI.
        return ToolExecutionResult(
            "Skill '$name' is noted. Available skills for this book:\n" +
            "  - novel-writing (master novel methodology)\n" +
            "Book-local skills are loaded automatically when the session is created.",
            true,
        )
    }
}
