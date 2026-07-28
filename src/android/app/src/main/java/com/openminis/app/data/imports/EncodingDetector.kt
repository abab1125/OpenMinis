package com.openminis.app.data.imports

import java.nio.charset.Charset

/**
 * Lightweight text-encoding sniffer for TXT imports.
 *
 * Chinese web novels are overwhelmingly either UTF-8 or GBK/GB18030; reading
 * a GBK file as UTF-8 yields mojibake that no chapter regex can match — the
 * single most common cause of "导入后完全无法解析". Detection strategy
 * (mirrors what legado's EncodingDetect does, without the jchardet dep):
 *
 *  1. BOM sniff (UTF-8 / UTF-16LE / UTF-16BE).
 *  2. Strict UTF-8 validation over the sample — if every multi-byte sequence
 *     is well-formed, it is UTF-8 (GBK text virtually never validates).
 *  3. Otherwise GB18030 (superset of GBK/GB2312, so it decodes all of them).
 */
object EncodingDetector {

    fun detect(sample: ByteArray): Charset {
        if (sample.size >= 3 &&
            sample[0] == 0xEF.toByte() && sample[1] == 0xBB.toByte() && sample[2] == 0xBF.toByte()
        ) return Charsets.UTF_8
        if (sample.size >= 2) {
            if (sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte()) return Charsets.UTF_16LE
            if (sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        }
        return if (looksLikeUtf8(sample)) Charsets.UTF_8 else Charset.forName("GB18030")
    }

    /**
     * Validate [bytes] as UTF-8. The final (possibly truncated) sequence is
     * forgiven since the sample may cut a character in half.
     */
    private fun looksLikeUtf8(bytes: ByteArray): Boolean {
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            val len = when {
                b < 0x80 -> 1
                b in 0xC2..0xDF -> 2
                b in 0xE0..0xEF -> 3
                b in 0xF0..0xF4 -> 4
                else -> return false // continuation byte or invalid leader first
            }
            if (i + len > n) return true // truncated tail — forgive
            for (k in 1 until len) {
                val c = bytes[i + k].toInt() and 0xFF
                if (c < 0x80 || c > 0xBF) return false
            }
            i += len
        }
        return true
    }
}
