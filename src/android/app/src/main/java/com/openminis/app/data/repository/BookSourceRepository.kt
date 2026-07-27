package com.openminis.app.data.repository

import android.content.Context
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.BookSourceEntity
import com.openminis.app.data.source.JsonPathEvaluator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Book-source engine (legado-format, JSON-API subset).
 *
 * - Import: parse a legado source JSON (single object / array / remote URL) and
 *   persist it to Room. This is what `book_import_source` drives.
 * - Explore: given a source, fetch one of its `exploreUrl` category lines,
 *   substitute `{{page}}`/`{{genre}}`, then run [JsonPathEvaluator] over the
 *   response with the source's `ruleExplore` to produce [RemoteBook]s.
 *
 * Book-source books are *remote* and intentionally live outside the
 * file-based novel projects in [BookRepository].
 */
object BookSourceRepository {
    private val client = OkHttpClient.Builder().build()

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
        val body = client.newCall(Request.Builder().url(url).build()).execute().use {
            it.body?.string() ?: return@withContext emptyList()
        }
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

        val body = client.newCall(reqBuilder.build()).execute().use {
            it.body?.string() ?: return@withContext emptyList()
        }
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

    // ── Helpers ──────────────────────────────────────────────────────────

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
