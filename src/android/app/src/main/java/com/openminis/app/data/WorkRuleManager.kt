package com.openminis.app.data

import android.content.Context
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.WorkRuleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-book work rules (lingxi's `agent-instructions`), backed by the Room
 * `work_rules` table (per the replication plan).
 *
 * `buildSystemPrompt()` runs on a thread we don't fully control, so we never
 * touch Room from it. Instead we keep an in-memory cache (bookId → rules text)
 * populated on a background thread via [refresh]; [getRulesText] reads the cache
 * synchronously and is safe on any thread.
 *
 * Rules cap at 20 entries, each ≤ 5000 chars, mirroring lingxi's config.
 */
object WorkRuleManager {
    data class WorkRule(val id: String, val content: String, val sort: Int)

    private const val MAX_RULES = 20
    private const val MAX_LEN = 5000

    private val cache = mutableMapOf<String, String>()

    private fun dao(context: Context) = AppDatabase.getInstance(context).workRuleDao()

    /** Load rules from Room into the cache (call on a background thread). */
    suspend fun refresh(bookId: String, context: Context) = withContext(Dispatchers.IO) {
        val rules = try {
            dao(context).getRules(bookId)
        } catch (_: Exception) {
            emptyList()
        }
        cache[bookId] = buildText(rules)
    }

    /** Synchronous, thread-safe read of the cached rules text. */
    fun getRulesText(bookId: String): String = cache[bookId] ?: ""

    private fun buildText(rules: List<WorkRuleEntity>): String {
        val filtered = rules.filter { it.content.isNotBlank() }
        if (filtered.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("=== 作品规则 ===")
        filtered.forEachIndexed { idx, r -> sb.appendLine("${idx + 1}. ${r.content}") }
        return sb.toString().trimEnd()
    }

    /** Add a rule (background). Returns the new rule id, or null if capped/empty. */
    suspend fun addRule(bookId: String, content: String, context: Context): String? =
        withContext(Dispatchers.IO) {
            val trimmed = content.take(MAX_LEN)
            if (trimmed.isBlank()) return@withContext null
            val existing = dao(context).getRules(bookId)
            if (existing.size >= MAX_RULES) return@withContext null
            val id = "r${System.currentTimeMillis()}"
            dao(context).insert(WorkRuleEntity(id, bookId, trimmed, existing.size))
            refresh(bookId, context)
            id
        }

    /** Delete a rule (background) and refresh the cache. */
    suspend fun deleteRule(bookId: String, id: String, context: Context) =
        withContext(Dispatchers.IO) {
            dao(context).deleteById(id)
            refresh(bookId, context)
        }
}
