package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

/**
 * content_search — grep-style full-text search across the agent's sandbox
 * text files. Complements `@`-mention (which only matches file *names*) and
 * `book_search` (which is bound to the active book): this tool searches file
 * *contents* across skills / workspace / shared / memory / books in one call,
 * so the agent can answer "which skill talks about dialogue rules?" or
 * "which cached chapter mentions 沈青澜?" without knowing the filename.
 *
 * Implementation notes:
 *   - Streaming scan, no persistent index. Mobile-scale corpora (a few
 *     thousand small text files) scan in well under a second on modern
 *     devices; an index would add invalidation complexity for no gain.
 *   - Binary files are skipped via a null-byte probe on the first 8 KB
 *     (same heuristic as FileReadTool). Files larger than
 *     [MAX_FILE_BYTES] are skipped entirely to bound worst-case latency.
 *   - Budgets: at most [MAX_FILES_SCANNED] files visited and [DEFAULT_LIMIT]
 *     (capped [MAX_LIMIT]) matches returned; the tail reports whether the
 *     scan was exhaustive so the agent knows to narrow the query.
 */
object ContentSearchTool {
    const val NAME = "content_search"

    private const val MAX_FILE_BYTES = 1_048_576L      // 1 MB per file
    private const val MAX_FILES_SCANNED = 2000
    private const val DEFAULT_LIMIT = 30
    private const val MAX_LIMIT = 100
    private const val CONTEXT_CHARS = 80               // chars of context around a hit
    private const val MAX_OUTPUT_CHARS = 20_000

    /** Scope name → list of Linux roots to scan. `workspace`/`attachments` are session-scoped. */
    private fun rootsForScope(scope: String, sessionId: String): List<String> = when (scope) {
        "skills" -> listOf("/var/minis/skills")
        "workspace" -> listOf("/var/minis/workspace/$sessionId", "/var/minis/attachments/$sessionId")
        "shared" -> listOf("/var/minis/shared")
        "memory" -> listOf("/var/minis/memory")
        "books" -> listOf("/var/minis/books")
        else -> listOf( // "all"
            "/var/minis/skills",
            "/var/minis/workspace/$sessionId",
            "/var/minis/attachments/$sessionId",
            "/var/minis/shared",
            "/var/minis/memory",
            "/var/minis/books",
        )
    }

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search file CONTENTS (grep-style) across the sandbox text files: skills, " +
            "workspace, shared, memory, and book projects (including cached book-source chapters). " +
            "Use this when you need to find WHERE something is written but don't know the filename — " +
            "e.g. which skill defines a writing rule, or which chapter mentions a character. " +
            "Returns matching lines as 'path:lineNo: excerpt'. For searching inside the currently " +
            "bound book only, book_search also works; content_search covers everything.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "query" to AgentToolParam("string", "Text to search for. Plain substring by default; set regex=true to treat it as a regular expression."),
            "scope" to AgentToolParam("string", "Where to search: 'skills', 'workspace', 'shared', 'memory', 'books', or 'all' (default 'all')."),
            "path" to AgentToolParam("string", "Optional: restrict the search to this Linux directory or file (e.g. /var/minis/books/src_lingya_12). Overrides scope."),
            "regex" to AgentToolParam("boolean", "Treat query as a regular expression (default false)."),
            "case_sensitive" to AgentToolParam("boolean", "Case-sensitive matching (default false)."),
            "limit" to AgentToolParam("integer", "Maximum number of matching lines to return (default $DEFAULT_LIMIT, max $MAX_LIMIT)."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "scope", "path", "regex", "case_sensitive", "limit"),
    )

    data class Hit(val linuxPath: String, val lineNo: Int, val excerpt: String)

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val toolTitle = args.optString("tool_title", NAME)
            val query = args.optString("query", "")
            if (query.isBlank()) {
                return ToolExecutionResult("Error: 'query' is required", false, toolTitle = toolTitle)
            }
            val scope = args.optString("scope", "all")
            val explicitPath = args.optString("path", "")
            val useRegex = args.optBoolean("regex", false)
            val caseSensitive = args.optBoolean("case_sensitive", false)
            val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

            val roots = if (explicitPath.isNotBlank()) listOf(explicitPath)
            else rootsForScope(scope, sessionId)

            val result = search(
                roots = roots,
                sessionId = sessionId,
                context = context,
                query = query,
                useRegex = useRegex,
                caseSensitive = caseSensitive,
                limit = limit,
            ) ?: return ToolExecutionResult(
                "Error: invalid regex pattern: $query", false, toolTitle = toolTitle
            )

            val (hits, filesScanned, truncated) = result
            if (hits.isEmpty()) {
                return ToolExecutionResult(
                    "No matches for \"$query\" (scanned $filesScanned files in scope '$scope').",
                    true, toolTitle = toolTitle
                )
            }
            val sb = StringBuilder()
            sb.append("${hits.size} match(es) for \"$query\":\n")
            for (h in hits) {
                val line = "${h.linuxPath}:${h.lineNo}: ${h.excerpt}\n"
                if (sb.length + line.length > MAX_OUTPUT_CHARS) break
                sb.append(line)
            }
            if (truncated) {
                sb.append("\n[truncated: hit the $limit-match or $MAX_FILES_SCANNED-file budget — narrow the query, scope, or path to see more]")
            }
            ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message}", false)
        }
    }

    /**
     * Core scanning routine, shared with the composer's mention-panel content
     * search (FileMentionIndex.contentMatches). Returns null on a bad regex,
     * otherwise Triple(hits, filesScanned, truncated).
     */
    fun search(
        roots: List<String>,
        sessionId: String,
        context: Context,
        query: String,
        useRegex: Boolean,
        caseSensitive: Boolean,
        limit: Int,
    ): Triple<List<Hit>, Int, Boolean>? {
        val pattern: Regex? = if (useRegex) {
            try {
                if (caseSensitive) Regex(query) else Regex(query, RegexOption.IGNORE_CASE)
            } catch (_: Exception) {
                return null
            }
        } else null
        val needle = if (caseSensitive) query else query.lowercase()

        val hits = mutableListOf<Hit>()
        var filesScanned = 0
        var truncated = false

        outer@ for (root in roots) {
            val hostRoot = PRootKernel.resolveSessionHostPath(sessionId, root, context) ?: continue
            if (!hostRoot.exists()) continue
            val queue = ArrayDeque<Pair<File, String>>() // hostFile → linuxPath
            queue.addLast(hostRoot to root)
            while (queue.isNotEmpty()) {
                val (host, linux) = queue.removeFirst()
                if (host.isDirectory) {
                    if (host.name.startsWith(".") && host != hostRoot) continue
                    if (host.name in SKIP_DIR_NAMES) continue
                    host.listFiles()?.forEach { child ->
                        queue.addLast(child to "$linux/${child.name}")
                    }
                    continue
                }
                if (!host.isFile) continue
                if (host.name.startsWith(".")) continue
                if (host.length() > MAX_FILE_BYTES) continue
                if (filesScanned >= MAX_FILES_SCANNED) { truncated = true; break@outer }
                filesScanned++
                if (isBinary(host)) continue

                try {
                    host.bufferedReader().useLines { lines ->
                        var lineNo = 0
                        for (raw in lines) {
                            lineNo++
                            val hitIndex = if (pattern != null) {
                                pattern.find(raw)?.range?.first ?: -1
                            } else {
                                val hay = if (caseSensitive) raw else raw.lowercase()
                                hay.indexOf(needle)
                            }
                            if (hitIndex < 0) continue
                            hits += Hit(linux, lineNo, excerpt(raw, hitIndex))
                            if (hits.size >= limit) { truncated = true; return@useLines }
                        }
                    }
                } catch (_: Exception) {
                    // unreadable file — skip
                }
                if (hits.size >= limit) break@outer
            }
        }
        return Triple(hits, filesScanned, truncated)
    }

    /** Trim a matched line to ~CONTEXT_CHARS around the hit position. */
    private fun excerpt(line: String, hitIndex: Int): String {
        val trimmed = line.trim()
        if (trimmed.length <= CONTEXT_CHARS * 2) return trimmed
        // Work on the original line to keep hitIndex valid, then trim edges.
        val start = (hitIndex - CONTEXT_CHARS).coerceAtLeast(0)
        val end = (hitIndex + CONTEXT_CHARS).coerceAtMost(line.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < line.length) "…" else ""
        return prefix + line.substring(start, end).trim() + suffix
    }

    private fun isBinary(file: File): Boolean = try {
        file.inputStream().use { input ->
            val buf = ByteArray(minOf(8192L, file.length()).toInt())
            val read = input.read(buf)
            read > 0 && buf.take(read).any { it == 0.toByte() }
        }
    } catch (_: Exception) {
        true
    }

    private val SKIP_DIR_NAMES = setOf(
        ".git", ".svn", ".hg", "node_modules", ".venv", "venv",
        "__pycache__", ".build", ".gradle", ".idea", ".backup",
    )
}
