package com.openminis.app.tools

import android.content.Context
import java.io.File
import java.time.Instant
import com.openminis.app.data.AgentPersonas
import com.openminis.app.data.KnowledgeIndexManager
import com.openminis.app.data.WorkRuleManager
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.BookRepository
import com.openminis.app.data.repository.BookSourceRepository
import com.openminis.app.sandbox.PRootKernel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            add(deleteChapterDefinition())
            add(readOutlineDefinition())
            add(writeOutlineDefinition())
            add(referenceDefinition())
            add(buildContextDefinition())
            add(searchDefinition())
            add(loadSkillDefinition())
            add(exportDefinition())
            add(batchEditChapterDefinition())
            add(undoDefinition())
            add(bookSetRuleDefinition())
            add(bookListRulesDefinition())
            add(bookDeleteRuleDefinition())
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
        // A cached source book is a real local book folder, but its chapter
        // bodies live remotely and are fetched lazily — so route the
        // chapter-oriented tools through the source-aware helpers below.
        val isSrc = BookSourceRepository.isSourceBook(bookId, context)
        return when (name) {
            "book_list_chapters" -> if (isSrc) listSourceChapters(bookId, context) else listChapters(bookId, context)
            "book_read_chapter" -> if (isSrc) readSourceChapter(bookId, argsJson, context) else readChapter(bookId, argsJson, context)
            "book_write_chapter" -> if (isSrc) writeSourceChapter(bookId, argsJson, context) else writeChapter(bookId, argsJson, context)
            "book_edit_chapter" -> if (isSrc) editSourceChapter(bookId, argsJson, context) else editChapter(bookId, argsJson, context)
            "book_delete_chapter" -> if (isSrc) deleteSourceChapter(bookId, argsJson, context) else deleteChapter(bookId, argsJson, context)
            "book_read_outline" -> readOutline(bookId, context)
            "book_write_outline" -> writeOutline(bookId, argsJson, context)
            "book_reference" -> if (isSrc) {
                ToolExecutionResult("书源缓存书不支持 reference（角色/世界观/笔记），请用章节读写工具。", true)
            } else {
                reference(bookId, argsJson, context)
            }
            "book_get_context" -> if (isSrc) buildSourceContext(bookId, argsJson, context) else buildContext(bookId, argsJson, context)
            "book_search" -> search(bookId, argsJson, context)
            "book_load_skill" -> loadSkill(bookId, argsJson, context)
            "book_export" -> exportBook(bookId, context)
            "book_batch_edit_chapter" -> batchEditChapter(bookId, argsJson, context)
            "book_undo" -> undoLastBatch(bookId, context)
            "book_set_rule" -> setRule(bookId, argsJson, context)
            "book_list_rules" -> listRules(bookId, context)
            "book_delete_rule" -> deleteRule(bookId, argsJson, context)
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

    private fun deleteChapterDefinition() = AgentToolDefinition(
        name = "book_delete_chapter",
        description = "Delete a chapter by number (1-based). The chapter file and its summary are removed. " +
            "Subsequent chapters are NOT renumbered - chapter numbers are stable identifiers, so deleting " +
            "ch003 leaves a gap rather than shifting ch004->ch003 (which would invalidate outline/summary refs). " +
            "Use sparingly; for a rewrite prefer book_write_chapter with append=false.",
        parameters = mapOf(
            "tool_title" to toolTitle("delete_chapter"),
            "num" to AgentToolParam("integer", "Chapter number (1-based) to delete"),
        ),
        required = listOf("tool_title", "num"),
        propertyOrdering = listOf("tool_title", "num"),
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

    private fun bookSetRuleDefinition() = AgentToolDefinition(
        name = "book_set_rule",
        description = "Add a work rule (用户自定义写作指令) to the current book. Rules are injected into the system prompt so the agent follows them. Max 20 rules, each ≤ 5000 chars.",
        parameters = mapOf(
            "tool_title" to toolTitle("set_rule"),
            "content" to AgentToolParam("string", "The rule text, e.g. '所有对话必须用口语化短句' or '反派不能无故洗白'. Markdown ok."),
        ),
        required = listOf("tool_title", "content"),
        propertyOrdering = listOf("tool_title", "content"),
    )

    private fun bookListRulesDefinition() = AgentToolDefinition(
        name = "book_list_rules",
        description = "List all work rules of the current book (user-defined writing instructions).",
        parameters = mapOf("tool_title" to toolTitle("list_rules")),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
    )

    private fun bookDeleteRuleDefinition() = AgentToolDefinition(
        name = "book_delete_rule",
        description = "Delete a work rule of the current book by its id (obtain ids from book_list_rules).",
        parameters = mapOf(
            "tool_title" to toolTitle("delete_rule"),
            "rule_id" to AgentToolParam("string", "The rule id returned by book_list_rules."),
        ),
        required = listOf("tool_title", "rule_id"),
        propertyOrdering = listOf("tool_title", "rule_id"),
    )

    private fun buildContextDefinition() = AgentToolDefinition(
        name = "book_get_context",
        description = "Build a complete writing context for a target chapter, mirroring lingxi's " +
            "context order: 创作人设 → 知识点索引(仅描述) → 作品信息(梗概/大纲) → 作品规则 → 章节上下文. " +
            "Returns the full assembled block in one response so you can write or revise the chapter " +
            "with all needed context at hand. Call this before writing a new chapter. " +
            "Conversation history is already supplied by the system automatically.",
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

    private fun exportDefinition() = AgentToolDefinition(
        name = "book_export",
        description = "Export the current book into a single merged text file. " +
            "For locally-authored novels all chapters are bundled. " +
            "For a cached source book, only chapters already fetched into the cache are exported " +
            "(open a chapter first to fetch it; uncached chapters are skipped). " +
            "Returns the file path inside the sandbox.",
        parameters = mapOf("tool_title" to toolTitle("export")),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
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

    private fun deleteChapter(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val num = args.optInt("num", 1)
        if (num < 1) return ToolExecutionResult("Error: 'num' must be >= 1", false)
        val deleted = BookRepository.deleteChapter(bookId, num, context)
        return if (deleted) {
            ToolExecutionResult("Deleted chapter $num. Subsequent chapters keep their numbers (no renumbering).", true)
        } else {
            ToolExecutionResult("Chapter $num not found (already absent?).", false)
        }
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
                else {
                    com.openminis.app.data.KnowledgeIndexManager.markLoaded(bookId, type, name)
                    ToolExecutionResult("--- $type/$name ---\n$text", true)
                }
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

    private suspend fun setRule(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val content = args.optString("content", "")
        if (content.isBlank()) return ToolExecutionResult("Error: 'content' is required", false)
        val id = com.openminis.app.data.WorkRuleManager.addRule(bookId, content, context)
            ?: return ToolExecutionResult("Error: rule limit reached (max 20) or content empty", false)
        ToolExecutionResult("Added rule $id. Rules are now injected into the book system prompt.", true)
    }

    private suspend fun listRules(bookId: String, context: Context): ToolExecutionResult {
        com.openminis.app.data.WorkRuleManager.refresh(bookId, context)
        val text = com.openminis.app.data.WorkRuleManager.getRulesText(bookId)
        if (text.isBlank()) return ToolExecutionResult("No work rules yet.", true)
        ToolExecutionResult(text, true)
    }

    private suspend fun deleteRule(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val id = args.optString("rule_id", "")
        if (id.isBlank()) return ToolExecutionResult("Error: 'rule_id' is required", false)
        com.openminis.app.data.WorkRuleManager.deleteRule(bookId, id, context)
        ToolExecutionResult("Deleted rule $id.", true)
    }

    private suspend fun buildContext(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val chapterNum = args.optInt("chapter_num", -1)
        val num = if (chapterNum > 0) chapterNum else {
            val chapters = BookRepository.listChapters(bookId, context)
            if (chapters.isEmpty()) 1 else chapters.last().num + 1
        }
        val text = assembleBookContext(bookId, num, context, isSource = false)
        return ToolExecutionResult(text, true)
    }

    /**
     * [T-lingxi-replication] Stage-3 context assembler. Mirrors lingxi's request
     * context order: persona -> knowledge index -> work info (synopsis/outline/
     * settings) -> work rules -> chapter block.
     *
     * Conversation history is supplied by the chat framework automatically (the
     * message list is part of every request), so it is intentionally NOT
     * duplicated here — re-injecting it would only waste tokens.
     */
    private suspend fun assembleBookContext(
        bookId: String,
        num: Int,
        context: Context,
        isSource: Boolean,
    ): String {
        val sb = StringBuilder()
        val book = BookRepository.loadBook(bookId, context)
        val persona = AgentPersonas.fromId(book?.persona)

        // 1) Persona (agent role)
        sb.appendLine("=== 创作人设 ===")
        sb.appendLine("你当前的创作人设：${persona.name}")
        sb.appendLine(persona.systemPrompt)
        sb.appendLine()

        // 2) Knowledge index (description-only; bodies load on demand)
        if (!isSource) {
            val idx = KnowledgeIndexManager.getIndexText(bookId, context)
            if (idx.isNotBlank()) {
                sb.appendLine(idx)
                sb.appendLine()
            }
        }

        // 3) Work info: synopsis + genre/status + outline (settings fold into the index)
        sb.appendLine("=== 作品信息 ===")
        sb.appendLine("书名：${book?.title ?: "?"}    类型：${book?.genre ?: ""}    状态：${book?.status ?: ""}")
        if (book != null && book.synopsis.isNotBlank()) {
            sb.appendLine("梗概：${book.synopsis}")
        }
        if (!isSource) {
            val outline = BookRepository.readOutline(bookId, context)
            if (outline.isNotBlank()) {
                sb.appendLine()
                sb.appendLine("--- 大纲 ---")
                sb.appendLine(outline)
            }
        }
        sb.appendLine()

        // 4) Work rules (user-defined instructions)
        WorkRuleManager.refresh(bookId, context)
        val rules = WorkRuleManager.getRulesText(bookId)
        if (rules.isNotBlank()) {
            sb.appendLine(rules)
            sb.appendLine()
        }

        // 5) Chapter block (current + recent prior chapters + chapter list)
        sb.appendLine("=== 章节写作上下文（围绕 ch${"%03d".format(num)}）===")
        if (isSource) {
            sb.append(buildSourceChapterBlock(bookId, num, context))
        } else {
            sb.append(BookRepository.buildContext(bookId, num, context = context))
        }

        sb.appendLine()
        sb.appendLine("注：以上为作品与章节上下文。完整对话历史由系统按消息顺序自动提供，无需在此重复。")
        return sb.toString().trimEnd()
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

    // ── Source-book aware helpers (cached remote books) ─────────────────

    private suspend fun listSourceChapters(bookId: String, context: Context): ToolExecutionResult {
        val chapters = BookSourceRepository.listSourceChapters(bookId, context)
        if (chapters.isEmpty()) return ToolExecutionResult("No chapters in this source book.", true)
        val sb = StringBuilder()
        sb.appendLine("Total: ${chapters.size} chapters (cached: ${chapters.count { it.cached }})")
        chapters.forEach { c ->
            val mark = if (c.cached) "" else " [未缓存]"
            sb.appendLine("ch${"%03d".format(c.num)}: ${c.title}$mark")
        }
        return ToolExecutionResult(sb.toString().trimEnd(), true)
    }

    private suspend fun readSourceChapter(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val num = args.optInt("num", 1)
        val content = BookSourceRepository.readSourceChapter(bookId, num, context)
        if (content == null) {
            return ToolExecutionResult("Error: Chapter $num not found or cannot be fetched (check network / source).", false)
        }
        return ToolExecutionResult("--- ch${"%03d".format(num)} ---\n$content", true)
    }

    private suspend fun writeSourceChapter(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val num = args.optInt("num", 1)
        val title = args.optString("title", null)
        val content = args.optString("content", "")
        val append = args.optBoolean("append", false)
        if (content.isBlank()) return ToolExecutionResult("Error: content cannot be empty", false)
        // Fetch the remote body first so we don't silently drop the original
        // when overwriting / appending.
        BookSourceRepository.ensureChapterCached(bookId, num, context)
        val result = BookRepository.writeChapter(bookId, num, title, content, append, context)
        return ToolExecutionResult(result, true)
    }

    private suspend fun editSourceChapter(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val num = args.optInt("num", 1)
        val find = args.optString("find", "")
        val replace = args.optString("replace", "")
        val replaceAll = args.optBoolean("replaceAll", false)
        if (find.isBlank()) return ToolExecutionResult("Error: 'find' cannot be empty", false)
        BookSourceRepository.ensureChapterCached(bookId, num, context)
        val result = BookRepository.editChapter(bookId, num, find, replace, replaceAll, context)
        return ToolExecutionResult(result, !result.startsWith("Error"))
    }

    private suspend fun deleteSourceChapter(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val num = args.optInt("num", 1)
        if (num < 1) return ToolExecutionResult("Error: 'num' must be >= 1", false)
        val deleted = BookRepository.deleteChapter(bookId, num, context)
        return if (deleted) {
            ToolExecutionResult("Deleted chapter $num from cache. Subsequent chapters keep their numbers.", true)
        } else {
            ToolExecutionResult("Chapter $num not found.", false)
        }
    }

    private suspend fun buildSourceContext(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val chapterNum = args.optInt("chapter_num", -1)
        val chapters = BookSourceRepository.listSourceChapters(bookId, context)
        val num = if (chapterNum > 0) chapterNum else (chapters.lastOrNull()?.num ?: 1)
        val text = assembleBookContext(bookId, num, context, isSource = true)
        return ToolExecutionResult(text, true)
    }

    /** Source-book chapter block (bodies live remotely, fetched lazily). */
    private suspend fun buildSourceChapterBlock(bookId: String, num: Int, context: Context): String {
        val sb = StringBuilder()
        val current = BookSourceRepository.readSourceChapter(bookId, num, context) ?: ""
        sb.appendLine("=== Current Chapter (ch${"%03d".format(num)}) ===")
        sb.appendLine(current)
        sb.appendLine()
        val nearStart = (num - 9).coerceAtLeast(1)
        sb.appendLine("=== Previous Chapters (ch${"%03d".format(nearStart)} ~ ch${"%03d".format((num - 1).coerceAtLeast(1))}) ===")
        for (n in nearStart until num) {
            val ch = BookSourceRepository.readSourceChapter(bookId, n, context)
            if (!ch.isNullOrBlank()) {
                sb.appendLine("--- ch${"%03d".format(n)} ---")
                sb.appendLine(ch)
            }
        }
        sb.appendLine()
        sb.appendLine("=== Chapter List (cached chapters only) ===")
        BookSourceRepository.listSourceChapters(bookId, context).forEach { c ->
            sb.appendLine("ch${"%03d".format(c.num)}: ${c.title}${if (c.cached) "" else " [未缓存]"}")
        }
        return sb.toString().trimEnd()
    }

    private suspend fun exportBook(bookId: String, context: Context): ToolExecutionResult = withContext(Dispatchers.IO) {
        val meta = BookRepository.loadBook(bookId, context) ?: return@withContext ToolExecutionResult("Error: book not found", false)
        val isSrc = BookSourceRepository.isSourceBook(bookId, context)
        val chapterNums: List<Pair<Int, Boolean>> = if (isSrc) {
            BookSourceRepository.listSourceChapters(bookId, context).map { it.num to it.cached }
        } else {
            BookRepository.listChapters(bookId, context).map { it.num to true }
        }
        val sb = StringBuilder()
        sb.appendLine("# ${meta.title}")
        sb.appendLine()
        var exported = 0
        var skipped = 0
        for ((num, cached) in chapterNums) {
            if (isSrc && !cached) { skipped++; continue } // export only what's in the cache
            val text = BookRepository.readChapter(bookId, num, context) ?: continue
            // Drop the leading "# Title" heading; the merged file uses its own chapter markers.
            val body = text.lines().dropWhile { it.startsWith("# ") }.drop(1).joinToString("\n").trimStart('\n')
            sb.appendLine("## 第${num}章")
            sb.appendLine(body)
            sb.appendLine()
            exported++
        }
        val hostDir = PRootKernel.resolveSessionHostPath("", BookRepository.booksBasePath(), context)
            ?.let { java.io.File(it, bookId) }
            ?: return@withContext ToolExecutionResult("Error: cannot resolve book dir", false)
        val exportDir = java.io.File(hostDir, "export").also { it.mkdirs() }
        val safe = meta.title.filter { it.isLetterOrDigit() || it in "_-" }.ifBlank { "book" }
        val outFile = java.io.File(exportDir, "$safe.txt")
        outFile.writeText(sb.toString())
        val msg = "Exported $exported chapters to: ${outFile.absolutePath}" +
            if (skipped > 0) "\n($skipped chapters were not cached and skipped — open them first to fetch.)" else ""
        ToolExecutionResult(msg, true)
    }

    // ── Batch edit + undo (v0.24-P2) ──────────────────────────────────────

    private fun batchEditChapterDefinition() = AgentToolDefinition(
        name = "book_batch_edit_chapter",
        description = "Batch find-and-replace across multiple chapters in one transaction. " +
            "A snapshot of the affected chapters is saved to .backup/<timestamp>/ before any change, " +
            "so you can roll back with book_undo. dry_run is forced on by default (preview only) — " +
            "review the preview, then call again with dry_run=false to apply.",
        parameters = mapOf(
            "tool_title" to toolTitle("batch_edit"),
            "chapters" to AgentToolParam("string", "Which chapters: 'all', a comma list like '1,3,5', or a range '2-7'. For source-cache books only cached chapters are touched."),
            "find" to AgentToolParam("string", "Text to find (exact match). Required."),
            "replace" to AgentToolParam("string", "Replacement text. Empty string deletes the matched text."),
            "replaceAll" to AgentToolParam("boolean", "If true, replace ALL occurrences in each chapter; if false, first only"),
            "dry_run" to AgentToolParam("boolean", "Preview only (default true). Set false to actually apply after reviewing."),
        ),
        required = listOf("tool_title", "chapters", "find", "replace"),
        propertyOrdering = listOf("tool_title", "chapters", "find", "replace", "replaceAll", "dry_run"),
    )

    private fun undoDefinition() = AgentToolDefinition(
        name = "book_undo",
        description = "Roll back the most recent book_batch_edit_chapter (or other bulk chapter edit) by restoring the snapshot saved to .backup/. Reverts every chapter changed in that operation.",
        parameters = mapOf("tool_title" to toolTitle("undo")),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
    )

    private suspend fun batchEditChapter(bookId: String, argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val spec = args.optString("chapters", "all")
        val find = args.optString("find", "")
        val replace = args.optString("replace", "")
        val replaceAll = args.optBoolean("replaceAll", false)
        val dryRun = args.optBoolean("dry_run", true) // forced default true
        if (find.isBlank()) return ToolExecutionResult("Error: 'find' cannot be empty", false)
        val isSrc = BookSourceRepository.isSourceBook(bookId, context)
        val nums = parseChapterNums(spec, bookId, context, isSrc)
        if (nums.isEmpty()) return ToolExecutionResult("Error: no chapters matched by '$spec'", false)

        val previews = mutableListOf<String>()
        var totalHits = 0
        for (num in nums) {
            if (isSrc) BookSourceRepository.ensureChapterCached(bookId, num, context)
            val content = BookRepository.readChapter(bookId, num, context) ?: continue
            val count = countOccurrences(content, find, replaceAll)
            if (count > 0) {
                totalHits += count
                previews.add("ch${"%03d".format(num)}: $count 处命中")
            }
        }
        if (dryRun) {
            val sb = StringBuilder()
            sb.appendLine("【预览 dry_run】将对 ${nums.size} 章执行替换，共 $totalHits 处命中：")
            previews.forEach { sb.appendLine("  $it") }
            if (totalHits == 0) sb.appendLine("（没有找到匹配，无需修改）")
            sb.appendLine("确认无误后调用 book_batch_edit_chapter 并设置 dry_run=false 执行。")
            return ToolExecutionResult(sb.toString().trimEnd(), true)
        }
        if (totalHits == 0) return ToolExecutionResult("No matches found in the selected chapters; nothing changed.", true)

        if (!snapshotChapters(bookId, nums, context)) {
            return ToolExecutionResult("Error: failed to create backup snapshot; aborting to avoid data loss.", false)
        }
        var applied = 0
        for (num in nums) {
            if (isSrc) BookSourceRepository.ensureChapterCached(bookId, num, context)
            val r = BookRepository.editChapter(bookId, num, find, replace, replaceAll, context)
            if (!r.startsWith("Error")) applied++
        }
        return ToolExecutionResult("Batch edit applied to $applied/${nums.size} chapters ($totalHits replacements). Snapshot saved — call book_undo to roll back.", true)
    }

    private suspend fun undoLastBatch(bookId: String, context: Context): ToolExecutionResult {
        val hostBase = PRootKernel.resolveSessionHostPath("", BookRepository.booksBasePath(), context)
            ?: return ToolExecutionResult("Error: cannot resolve book dir", false)
        val bookDir = File(hostBase, bookId)
        val backupRoot = File(bookDir, ".backup")
        if (!backupRoot.isDirectory) return ToolExecutionResult("Error: no backup snapshot found for this book.", false)
        val latest = File(backupRoot, "latest.txt").let { if (it.exists()) it.readText().trim() else null }
            ?: backupRoot.listFiles { f -> f.isDirectory }?.maxByOrNull { it.name }?.name
            ?: return ToolExecutionResult("Error: no backup snapshot found.", false)
        val backupChapters = File(File(backupRoot, latest), "chapters")
        if (!backupChapters.isDirectory) return ToolExecutionResult("Error: backup snapshot '$latest' is empty.", false)
        val chaptersDir = File(bookDir, "chapters")
        if (!chaptersDir.isDirectory) return ToolExecutionResult("Error: chapters directory missing.", false)
        var restored = 0
        backupChapters.listFiles { f -> f.extension == "md" }?.forEach { src ->
            val dst = File(chaptersDir, src.name)
            try { src.copyTo(dst, overwrite = true); restored++ } catch (_: Exception) {}
        }
        return ToolExecutionResult("Restored $restored chapters from backup snapshot '$latest'.", true)
    }

    private fun countOccurrences(content: String, find: String, replaceAll: Boolean): Int {
        if (find.isEmpty()) return 0
        var idx = 0
        var count = 0
        while (true) {
            val i = content.indexOf(find, idx)
            if (i < 0) break
            count++
            idx = i + find.length
            if (!replaceAll) break
        }
        return count
    }

    private suspend fun parseChapterNums(spec: String, bookId: String, context: Context, isSrc: Boolean): List<Int> {
        val s = spec.trim()
        if (s == "all") {
            return if (isSrc) {
                BookSourceRepository.listSourceChapters(bookId, context).filter { it.cached }.map { it.num }
            } else {
                BookRepository.listChapters(bookId, context).map { it.num }
            }
        }
        val nums = mutableListOf<Int>()
        s.split(",").forEach { part ->
            val p = part.trim()
            if (p.contains("-")) {
                val (a, b) = p.split("-", limit = 2)
                val lo = a.trim().toIntOrNull()
                val hi = b.trim().toIntOrNull()
                if (lo != null && hi != null) for (n in lo..hi) nums.add(n)
            } else {
                p.toIntOrNull()?.let { nums.add(it) }
            }
        }
        return nums.distinct().sorted()
    }

    private fun snapshotChapters(bookId: String, nums: List<Int>, context: Context): Boolean {
        val hostBase = PRootKernel.resolveSessionHostPath("", BookRepository.booksBasePath(), context) ?: return false
        val bookDir = File(hostBase, bookId)
        val chaptersDir = File(bookDir, "chapters")
        if (!chaptersDir.isDirectory) return false
        val ts = Instant.now().toString().replace(":", "-")
        val backupDir = File(File(bookDir, ".backup").also { it.mkdirs() }, ts)
        val backupChapters = File(backupDir, "chapters").also { it.mkdirs() }
        var ok = true
        for (num in nums) {
            val src = File(chaptersDir, "ch${"%03d".format(num)}.md")
            if (!src.exists()) continue
            try { src.copyTo(File(backupChapters, src.name), overwrite = true) } catch (_: Exception) { ok = false }
        }
        try { File(bookDir, ".backup/latest.txt").writeText(ts) } catch (_: Exception) {}
        return ok
    }
}
