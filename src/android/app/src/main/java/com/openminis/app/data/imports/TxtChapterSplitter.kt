package com.openminis.app.data.imports

import java.io.InputStream
import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * TXT chapter splitter - ported from legado's `TextFile.analyze`
 * (app/src/main/java/io/legado/app/model/localBook/TextFile.kt:159-389).
 *
 * Splits a plain-text novel into chapters by matching chapter-title lines
 * with a [Pattern.MULTILINE] regex. Designed for large files: reads in fixed
 * 512KB blocks and retreats to the last newline on a block boundary so a
 * title line is never cut in half. Offsets and content lengths are tracked in
 * *bytes* (not chars) because multi-byte encodings (GBK/UTF-8) make
 * char-offset slicing wrong - mirroring legado's correctness fix.
 *
 * Simplifications vs legado (YAGNI for OpenMinis novel-writing):
 *  - No Rhino JS title sanitisation - the matched line is trimmed instead.
 *  - No "long chapter" re-split (>100KB chapters stay whole).
 *  - No preface/volume detection.
 *  - No 25-rule auto-pick - the caller passes a single regex (the UI offers
 *    presets). An empty regex falls back to size-based splitting.
 */
object TxtChapterSplitter {

    /** A split chapter: its title (from the matched line) and full body text. */
    data class Chapter(val title: String, val content: String)

    private const val BUFFER_SIZE = 512_000
    // Size-based fallback chunk when no regex matches (legado: 10KB per chunk).
    private const val MAX_LENGTH_WITH_NO_TOC = 10 * 1024
    // Newline byte used to find a safe block-boundary retreat point.
    private val NEWLINE: Byte = 0x0a

    /**
     * Split [input] into chapters using [regex] under [charset].
     *
     * @param input the TXT file stream (will be consumed; caller closes it).
     * @param regex a MULTILINE regex matching whole chapter-title lines.
     *        Empty string => size-based fallback (every ~10KB, retreating to
     *        a newline), titling chunks "第N章".
     * @param charset text encoding of the file.
     * @return chapters in order; empty only if the stream is empty.
     */
    fun split(input: InputStream, regex: String, charset: Charset = Charsets.UTF_8): List<Chapter> {
        return if (regex.isBlank()) splitBySize(input, charset)
        else splitByRegex(input, regex, charset)
    }

    /** UTF-8 BOM detection (first 3 bytes). */
    private fun hasUtf8Bom(b0: Int, b1: Int, b2: Int): Boolean =
        b0 == 0xEF && b1 == 0xBB && b2 == 0xBF

    private fun splitByRegex(input: InputStream, regex: String, charset: Charset): List<Chapter> {
        val pattern = regex.toPattern(Pattern.MULTILINE)
        val toc = ArrayList<Chapter>()
        val buffer = ByteArray(BUFFER_SIZE)

        // Read 3 bytes to detect BOM. bufferStart is where the next read fills
        // from; leftover bytes from a previous block live at the head.
        var bufferStart = 3
        val first3 = IntArray(3)
        first3[0] = input.read()
        first3[1] = if (first3[0] >= 0) input.read() else -1
        first3[2] = if (first3[1] >= 0) input.read() else -1
        if (first3[0] < 0) return emptyList() // empty file
        if (hasUtf8Bom(first3[0], first3[1], first3[2])) {
            bufferStart = 0 // discard BOM bytes
        } else {
            // Not a BOM - the 3 bytes are real content, place them at the head.
            buffer[0] = first3[0].toByte()
            if (first3[1] >= 0) buffer[1] = first3[1].toByte()
            if (first3[2] >= 0) buffer[2] = first3[2].toByte()
            // bufferStart stays 3 so the next read fills after these bytes.
        }

        // Accumulator for the current chapter's body (everything between the
        // last title and the next). The title line itself is NOT part of the
        // body; legado strips it, and we emit it as Chapter.title instead.
        val bodySb = StringBuilder()
        var pendingTitle: String? = null // title waiting for its body to close

        while (true) {
            val n = input.read(buffer, bufferStart, BUFFER_SIZE - bufferStart)
            if (n <= 0) break
            var end = bufferStart + n
            // Block full? Retreat to last newline so we don't split a title line.
            if (end == BUFFER_SIZE) {
                var cut = -1
                for (i in end - 1 downTo 0) {
                    if (buffer[i] == NEWLINE) { cut = i; break }
                }
                if (cut >= 0) end = cut
            }
            val block = String(buffer, 0, end, charset)
            // Move leftover tail (from `end` to bufferStart+n) to the head.
            val leftover = (bufferStart + n) - end
            if (leftover > 0) {
                System.arraycopy(buffer, end, buffer, 0, leftover)
            }
            bufferStart = leftover

            // Scan matches inside this block, slicing body text between titles.
            // The title line itself is NOT part of any chapter body - it
            // becomes the next chapter's title. Body text between two titles
            // belongs to the chapter opened by the earlier title.
            val matcher = pattern.matcher(block)
            var seekPos = 0
            while (matcher.find()) {
                val matchStart = matcher.start()
                val bodyBetween = block.substring(seekPos, matchStart)
                val titleLine = matcher.group().trim()
                if (titleLine.isEmpty()) {
                    seekPos = matcher.end()
                    continue
                }
                // Whatever body we have so far (leftover from prior blocks +
                // text up to this title) closes the pending chapter.
                bodySb.append(bodyBetween)
                if (pendingTitle != null) {
                    toc.add(Chapter(pendingTitle!!, bodySb.toString().trimEnd()))
                } else if (bodySb.isNotBlank() && toc.isEmpty()) {
                    // Content before the first title -> emit as a preface chapter.
                    toc.add(Chapter("序章", bodySb.toString().trimEnd()))
                }
                bodySb.setLength(0)
                pendingTitle = titleLine
                seekPos = matcher.end()
            }
            // Append the trailing (non-matched) tail of this block to the body
            // of the currently pending chapter.
            if (seekPos < block.length) {
                bodySb.append(block.substring(seekPos))
            }
        }
        // Flush the final pending chapter.
        if (pendingTitle != null) {
            toc.add(Chapter(pendingTitle!!, bodySb.toString().trimEnd()))
        } else if (bodySb.isNotBlank()) {
            // No title matched at all - treat the whole file as one chapter.
            toc.add(Chapter("第1章", bodySb.toString().trimEnd()))
        }
        return toc
    }

    /** Size-based fallback: cut every ~10KB at a newline, title "第N章". */
    private fun splitBySize(input: InputStream, charset: Charset): List<Chapter> {
        val toc = ArrayList<Chapter>()
        val buffer = ByteArray(BUFFER_SIZE)
        var bufferStart = 3
        val first3 = IntArray(3)
        first3[0] = input.read()
        first3[1] = if (first3[0] >= 0) input.read() else -1
        first3[2] = if (first3[1] >= 0) input.read() else -1
        if (first3[0] < 0) return emptyList()
        if (hasUtf8Bom(first3[0], first3[1], first3[2])) {
            bufferStart = 0
        } else {
            buffer[0] = first3[0].toByte()
            if (first3[1] >= 0) buffer[1] = first3[1].toByte()
            if (first3[2] >= 0) buffer[2] = first3[2].toByte()
        }

        val bodySb = StringBuilder()
        var chapterPos = 1
        var bytesThisChapter = 0
        while (true) {
            val n = input.read(buffer, bufferStart, BUFFER_SIZE - bufferStart)
            if (n <= 0) break
            var end = bufferStart + n
            if (end == BUFFER_SIZE) {
                var cut = -1
                for (i in end - 1 downTo 0) {
                    if (buffer[i] == NEWLINE) { cut = i; break }
                }
                if (cut >= 0) end = cut
            }
            val block = String(buffer, 0, end, charset)
            val leftover = (bufferStart + n) - end
            if (leftover > 0) System.arraycopy(buffer, end, buffer, 0, leftover)
            bufferStart = leftover

            bodySb.append(block)
            bytesThisChapter += block.toByteArray(charset).size
            if (bytesThisChapter >= MAX_LENGTH_WITH_NO_TOC) {
                toc.add(Chapter("第${chapterPos}章", bodySb.toString().trimEnd()))
                bodySb.setLength(0)
                bytesThisChapter = 0
                chapterPos++
            }
        }
        if (bodySb.isNotBlank()) {
            toc.add(Chapter("第${chapterPos}章", bodySb.toString().trimEnd()))
        }
        return toc
    }
}
