package com.openminis.app.ui.chat

// [T-android-split-chat] @-mention picker methods extracted from ChatViewModel
// as top-level extension functions (call syntax unchanged). The 5 mention
// state fields they touch were flipped private->internal. Verbatim logic.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.terminal.MinisUrlMarker
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Inspect the input + caret position; if the caret sits inside an
 * `@<token>` (preceded by start-of-text or whitespace, no whitespace
 * between `@` and caret) open the mention picker and refresh the
 * filter. Otherwise close it. Mirrors iOS
 * [AIChatViewModel.updateMentionMenuState].
 *
 * Accepts both ASCII `@` and full-width `＠` (U+FF20) so CJK IMEs
 * substituting the full-width form still trigger the picker — same
 * convention as slash commands accepting `／`.
 */
internal fun ChatViewModel.updateMentionMenuState(text: String, caret: Int) {
    // The slash and mention pickers are mutually exclusive (iOS does
    // the same). Slash takes priority.
    if (_showSlashMenu.value) {
        if (_showMentionMenu.value) dismissMentionMenu()
        return
    }
    val safeCaret = caret.coerceIn(0, text.length)
    // Walk back from caret to find an `@` that opens the active token.
    var anchor = -1
    var i = safeCaret
    while (i > 0) {
        val prev = i - 1
        val ch = text[prev]
        if (ch.isWhitespace()) break
        if (ch == '@' || ch == '＠') {
            // Require start-of-text or whitespace before `@` so emails
            // ("foo@bar.com") don't pop the menu.
            if (prev == 0 || text[prev - 1].isWhitespace()) {
                anchor = prev
            }
            break
        }
        i = prev
    }
    if (anchor < 0) {
        if (_showMentionMenu.value) dismissMentionMenu()
        return
    }
    val filter = text.substring(anchor + 1, safeCaret)
    _mentionAnchor.value = anchor
    // Content-search results are pinned to the filter they were run against;
    // any filter change invalidates them (T-mention-content-search).
    if (_mentionFilter.value != filter) clearMentionContentSearch()
    _mentionFilter.value = filter
    if (!_showMentionMenu.value) {
        _showMentionMenu.value = true
        // Mirror iOS: pre-select row 0 so a hardware-keyboard Return
        // commits the top match without an extra Down press.
        _mentionSelectedIndex.value = 0
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isNotEmpty()) fileMentionIndex.refreshIfNeeded(sid)
        Log.i(ChatViewModel.TAG, "mention menu open anchor=$anchor filter=\"$filter\"")
    } else {
        // Filter changed while open — clamp the highlight back into range
        // so it never points past the end of a shrunk filtered list.
        // Async: the next combine emission for [mentionEntries] will have
        // the new size; we only need to keep the index sane in the
        // interim. Concretely: if the user types past the only remaining
        // match, the filter narrows and on the next list emission the
        // composable's LaunchedEffect resets us back into bounds.
        val current = _mentionSelectedIndex.value
        if (current < 0) _mentionSelectedIndex.value = 0
    }
}

internal fun ChatViewModel.dismissMentionMenu() {
    if (!_showMentionMenu.value) return
    _showMentionMenu.value = false
    _mentionFilter.value = ""
    _mentionAnchor.value = -1
    _mentionSelectedIndex.value = -1
    clearMentionContentSearch()
}

// ── @-mention auto-injection (T-mention-autoinject, aka P1 of the
// "灵犀娘 thinking-flow" plan) ────────────────────────────────────────────
//
// Before this, `@/var/minis/skills/foo` in a sent message was plain text:
// the model saw a path, had to *choose* to file_read it, costing a turn and
// sometimes skipping it entirely. Now sendMessage resolves each mentioned
// path and injects its content directly into the user turn as
// <mention-context> blocks, so referenced skills/files are live context
// from token one — mirroring the "@知识库引用" behavior in the reference
// screenshots (knowledge base == the existing skill system, per 老板).

/** Matches `@/var/minis/...` tokens (ASCII or full-width @). */
private val MENTION_PATH_REGEX = Regex("[@＠](/var/minis/[^\\s]+)")

/** Per-file char cap (~16 KB): full injection below, truncate + hint above. */
private const val MENTION_FILE_CHAR_CAP = 16_384

/** Total injected chars across all mentions in one message (~48 KB). */
private const val MENTION_TOTAL_CHAR_BUDGET = 49_152

/** Files larger than this are never inlined — the agent is told to read it. */
private const val MENTION_FILE_HARD_SKIP_BYTES = 512 * 1024L

/**
 * Resolve every `@/var/minis/...` mention in [text] and build
 * `<mention-context>` content parts:
 *   - skill directory (has SKILL.md)  → inject the SKILL.md, record skill use
 *   - other directory                 → inject a one-level listing
 *   - text file ≤ cap                 → inject full content
 *   - text file > cap                 → inject truncated head + read-hint
 *   - binary / oversized / missing    → short note (agent can still act)
 * Returns an empty list when the text has no resolvable mentions.
 */
internal suspend fun ChatViewModel.buildMentionContextParts(
    text: String,
): List<AgentContentPart> = withContext(Dispatchers.IO) {
    val paths = LinkedHashSet<String>()
    MENTION_PATH_REGEX.findAll(text).forEach { m ->
        // Strip trailing punctuation the user may have typed right after the path.
        paths += m.groupValues[1].trimEnd('.', ',', ';', ':', '，', '。', '；', '：', ')', '）', ']', '】')
    }
    if (paths.isEmpty()) return@withContext emptyList()

    val parts = mutableListOf<AgentContentPart>()
    var budget = MENTION_TOTAL_CHAR_BUDGET
    for (path in paths) {
        if (budget <= 0) break
        val host = try {
            com.openminis.app.sandbox.PRootKernel
                .resolveSessionHostPath(activeSessionId, path, context)
        } catch (_: Exception) { null } ?: continue

        var target = host
        var effectivePath = path
        if (host.isDirectory) {
            val skillMd = java.io.File(host, "SKILL.md")
            if (skillMd.isFile) {
                target = skillMd
                effectivePath = "$path/SKILL.md"
            } else {
                val listing = host.listFiles()
                    ?.sortedBy { it.name }
                    ?.take(60)
                    ?.joinToString("\n") { it.name + if (it.isDirectory) "/" else "" }
                    .orEmpty()
                parts += AgentContentPart.Text(
                    "<mention-context path=\"$path\" type=\"directory\">\n$listing\n</mention-context>"
                )
                budget -= listing.length
                continue
            }
        }
        if (!target.isFile) continue
        if (target.length() > MENTION_FILE_HARD_SKIP_BYTES) {
            parts += AgentContentPart.Text(
                "<mention-context path=\"$effectivePath\" note=\"file too large to inline " +
                    "(${target.length()} bytes) — use file_read with offset/lines to read it\"/>"
            )
            continue
        }
        // Binary probe (same heuristic as FileReadTool).
        val binary = try {
            target.inputStream().use { input ->
                val buf = ByteArray(minOf(8192L, target.length()).toInt())
                val read = input.read(buf)
                read > 0 && buf.take(read).any { it == 0.toByte() }
            }
        } catch (_: Exception) { true }
        if (binary) continue

        var content = try { target.readText() } catch (_: Exception) { continue }
        val cap = minOf(MENTION_FILE_CHAR_CAP, budget)
        var truncatedAttr = ""
        if (content.length > cap) {
            content = content.take(cap)
            truncatedAttr = " truncated=\"true\" note=\"use file_read with offset to see the rest\""
        }
        parts += AgentContentPart.Text(
            "<mention-context path=\"$effectivePath\"$truncatedAttr>\n$content\n</mention-context>"
        )
        budget -= content.length

        // Mirror the FileReadTool hook: an @-mentioned skill counts as used.
        runCatching {
            skillRepository?.skillIdFromPath(effectivePath)?.let { sid ->
                skillRepository?.recordSkillUse(sid)
            }
        }
    }
    parts
}

/**
 * Commit a content-search hit into the composer. Same token-replacement
 * semantics as [selectMention] — we wrap the hit's path in a synthetic
 * [FileMentionIndex.Entry] since selectMention only consumes `linuxPath`.
 */
internal fun ChatViewModel.selectMentionContentHit(
    hit: com.openminis.app.tools.ContentSearchTool.Hit,
    currentText: String,
    currentCaret: Int,
): Pair<String, Int> {
    val synthetic = FileMentionIndex.Entry(
        linuxPath = hit.linuxPath,
        scope = FileMentionIndex.Scope.WORKSPACE,
        mountName = null,
        modifiedAt = 0L,
        isDirectory = false,
    )
    return selectMention(synthetic, currentText, currentCaret)
}

/**
 * T-at-filepicker-keyboard: hardware-keyboard navigation helpers. Wraparound
 * matches iOS so Up at row 0 lands at the last row and vice versa.
 */
internal fun ChatViewModel.mentionMenuUp() {
    val count = mentionEntries.value.size
    if (count <= 0) return
    val idx = _mentionSelectedIndex.value
    _mentionSelectedIndex.value = if (idx <= 0) count - 1 else idx - 1
}

internal fun ChatViewModel.mentionMenuDown() {
    val count = mentionEntries.value.size
    if (count <= 0) return
    val idx = _mentionSelectedIndex.value
    _mentionSelectedIndex.value = if (idx >= count - 1) 0 else idx + 1
}

/**
 * Commit the highlighted entry (or the first match) into [currentText],
 * returning the new (text, caret). Returns null when the menu has no
 * matches to commit, so the caller can fall through to its default
 * Return-key handler (e.g. send-on-enter).
 */
internal fun ChatViewModel.executeSelectedMention(
    currentText: String,
    currentCaret: Int,
): Pair<String, Int>? {
    val entries = mentionEntries.value
    if (entries.isEmpty()) return null
    val idx = _mentionSelectedIndex.value.let {
        if (it in entries.indices) it else 0
    }
    return selectMention(entries[idx], currentText, currentCaret)
}

/**
 * Replace the active `@<token>` in [currentText] with `@<linuxPath> ` and
 * return the new text + caret position. The caller writes both back into
 * its TextFieldValue so the cursor lands right after the inserted space
 * — exactly mirroring iOS [AIChatViewModel.selectMention] which sets
 * `inputText` and `pendingCaret` in lockstep.
 *
 * If the menu is not open the call is a no-op and returns the original
 * (text, caret).
 */
internal fun ChatViewModel.selectMention(
    entry: FileMentionIndex.Entry,
    currentText: String,
    currentCaret: Int,
): Pair<String, Int> {
    val anchor = _mentionAnchor.value
    if (anchor < 0 || anchor > currentText.length) {
        dismissMentionMenu()
        return currentText to currentCaret
    }
    // Replacement span: from `@` up to next whitespace (or end).
    var endOffset = anchor + 1
    while (endOffset < currentText.length && !currentText[endOffset].isWhitespace()) {
        endOffset++
    }
    val replacement = "@${entry.linuxPath} "
    val newText = currentText.substring(0, anchor) +
        replacement +
        currentText.substring(endOffset)
    val newCaret = anchor + replacement.length
    // Dismiss before announcing the new text so the consumer's
    // updateMentionMenuState callback (fired on text change) doesn't
    // re-open the menu against the inserted path.
    dismissMentionMenu()
    return newText to newCaret
}
