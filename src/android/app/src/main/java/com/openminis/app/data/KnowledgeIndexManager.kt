package com.openminis.app.data

import android.content.Context
import com.openminis.app.data.repository.BookRepository

/**
 * Knowledge index layer — mirrors lingxi's skill-index mechanism.
 *
 * Each book's reference entries (characters / worldview / notes) are exposed to
 * the model as a compact index of `name + description` only. The full body is
 * loaded on demand via [loadContent], which delegates to the existing
 * `book_reference(type, op=read, name)` path. This keeps the system prompt small
 * (index ≈ 1.4k chars, like lingxi's userSkillIndex) while letting the agent pull
 * any entry's full text when it actually needs it.
 *
 * This is intentionally NOT built on `loadSkill` (which is a no-op stub) — the
 * real, populated data source is the per-book `reference` store.
 */
object KnowledgeIndexManager {
    private val TYPES = listOf("characters", "worldview", "notes")

    data class KnowledgeIndexEntry(
        val name: String,
        val description: String,
        val type: String,
    )

    /** Build the full index (name + derived description + type) for a book. */
    fun buildIndex(bookId: String, context: Context): List<KnowledgeIndexEntry> {
        val entries = mutableListOf<KnowledgeIndexEntry>()
        for (type in TYPES) {
            val names = try {
                BookRepository.listReferences(bookId, type, context)
            } catch (_: Exception) {
                emptyList()
            }
            for (name in names) {
                val body = try {
                    BookRepository.readReference(bookId, type, name, context)
                } catch (_: Exception) {
                    null
                } ?: continue
                entries.add(KnowledgeIndexEntry(name, deriveDescription(body), type))
            }
        }
        return entries
    }

    /**
     * Compact, description-only index text injected into the system prompt.
     * Returns "" when the book has no reference entries (so callers can skip it).
     */
    fun getIndexText(bookId: String, context: Context): String {
        val entries = buildIndex(bookId, context)
        if (entries.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine(
            "=== 知识点索引（仅描述；需要正文时用 book_reference(type, op=read, name) 按需加载）===",
        )
        for (e in entries) {
            sb.appendLine("- [${e.type}] ${e.name}: ${e.description}")
        }
        return sb.toString().trimEnd()
    }

    /** Load the full body of a single knowledge entry on demand. */
    fun loadContent(
        bookId: String,
        type: String,
        name: String,
        context: Context,
    ): String? = try {
        BookRepository.readReference(bookId, type, name, context)
    } catch (_: Exception) {
        null
    }

    /**
     * Derive a short description from a reference body: the first non-blank
     * paragraph, whitespace-collapsed and capped at [maxLen] chars. This is what
     * the model sees in the index; the full text stays behind `loadContent`.
     */
    private fun deriveDescription(body: String, maxLen: Int = 120): String {
        val firstParagraph = body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() } ?: ""
        val clean = firstParagraph.replace(Regex("\\s+"), " ").take(maxLen)
        return if (clean.length >= maxLen) "$clean…" else clean
    }
}
