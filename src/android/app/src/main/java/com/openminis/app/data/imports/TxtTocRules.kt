package com.openminis.app.data.imports

import java.util.regex.Pattern

/**
 * Built-in TXT chapter-splitting rules, ported from legado's
 * `assets/defaultData/txtTocRule.json` (the core subset covering the vast
 * majority of Chinese web novels + English books).
 *
 * v0.24 rewrite:
 *  - Fixed the old broken pattern that put `(?!张)` INSIDE a character class
 *    (where it matched literal `(?!张)` chars instead of a lookahead).
 *  - Ported legado's standard rule verbatim (id -2 "目录", with the
 *    disambiguation lookaheads 节(?!课) 集(?![合和]) 部(?![分赛游]) 篇(?!张)).
 *  - Added the frequently-hit legado extras: 回/话 chapters, bracketed
 *    numbers, 晋江 ☆、 style, standalone digit lines, digit-separator titles.
 *  - Added [autoDetect]: score every rule against a text sample and return
 *    the best match — the UI's "智能识别" option and the agent's default.
 *
 * All patterns use MULTILINE matching and are anchored to a whole title line.
 */
data class TxtTocRule(val name: String, val regex: String, val description: String)

object TxtTocRules {

    /** The rule selected by default in the import dialog (used as fallback). */
    val DEFAULT_INDEX = 0

    /** Minimum matches in the sample for [autoDetect] to trust a rule. */
    private const val AUTO_MIN_MATCHES = 3

    val presets: List<TxtTocRule> = listOf(
        // legado id -2 "目录" — the standard rule. Verbatim port including
        // the false-positive guards (节课/集合/部分/篇张...).
        TxtTocRule(
            name = "标准章节（第X章/序章/楔子…）",
            regex = """^[ 　\t]{0,4}(?:序章|楔子|正文(?!完|结)|终章|后记|尾声|番外|第\s{0,4}[\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+?\s{0,4}(?:章|节(?!课)|卷|集(?![合和])|部(?![分赛游])|篇(?!张))).{0,30}$""",
            description = "匹配 第X章/节/卷/集/部/篇 及 序章/楔子/终章/番外 等（legado 标准规则）",
        ),
        // legado "目录(去空白)" — tolerates blanks anywhere inside the marker.
        TxtTocRule(
            name = "标准章节（宽松去空白）",
            regex = """^[ 　\t]{0,4}第\s{0,4}[\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+\s{0,4}[章节卷集部篇回话]\s{0,2}.{0,30}$""",
            description = "「第 X 章」标记内可夹空格，含 回/话",
        ),
        TxtTocRule(
            name = "第X回/话（古典/轻小说）",
            regex = """^[ 　\t]{0,4}第[\d〇零一二两三四五六七八九十百千万]+[回话].{0,30}$""",
            description = "匹配「第X回」「第X话」章回体与轻小说",
        ),
        TxtTocRule(
            name = "数字分隔符标题（1、 2. 3：）",
            regex = """^[ 　\t]{0,4}\d{1,5}[、\.．,，:：\-—].{1,30}$""",
            description = "匹配「1、标题」「2.标题」「3：标题」等数字加分隔符",
        ),
        TxtTocRule(
            name = "括号数字标题（(1) 【2】）",
            regex = """^[ 　\t]{0,4}[(（\[【]\d{1,4}[)）\]】].{0,30}$""",
            description = "匹配「(1) 标题」「【2】标题」等括号编号",
        ),
        TxtTocRule(
            name = "特殊符号标题（☆、★ 晋江常见）",
            regex = """^[ 　\t]{0,4}[☆★✦✧◆■▲●○][、\s]?.{1,30}$""",
            description = "匹配「☆、第1章」等特殊符号开头的标题（晋江等站点导出常见）",
        ),
        TxtTocRule(
            name = "Chapter / Section（英文）",
            regex = """^[ \t]{0,4}(?:[Cc][Hh][Aa][Pp][Tt][Ee][Rr]|[Ss][Ee][Cc][Tt][Ii][Oo][Nn]|[Pp][Aa][Rr][Tt]|[Ee][Pp][Ii][Ss][Oo][Dd][Ee])\s{1,4}[\dIVXLCDMivxlcdm]{1,8}\b.{0,30}$""",
            description = "匹配 Chapter 1 / Section II / Part 3 / Episode 4 等",
        ),
        TxtTocRule(
            name = "独立数字行",
            regex = """^[ 　\t]{0,4}\d{1,5}[ 　\t]{0,4}$""",
            description = "整行只有一个数字（部分导出格式）",
        ),
        TxtTocRule(
            name = "卷标题（第X卷/部/篇）",
            regex = """^[ 　\t]{0,4}第[\d〇零一二两三四五六七八九十百千万]+[卷部篇]\s{0,4}.{0,30}$""",
            description = "只按卷/部/篇切分（超长书按卷读）",
        ),
        TxtTocRule(
            name = "无规则（按字数切分）",
            regex = "",
            description = "不匹配标题，每约 10KB 切一段，标题自动编号",
        ),
    )

    /**
     * Score every preset against [sample] (typically the first ~512KB of the
     * file, decoded) and return the best-matching rule, or null when nothing
     * reaches [AUTO_MIN_MATCHES] (caller should fall back to size-splitting).
     *
     * Ties go to the earlier (more specific / more standard) rule. Mirrors
     * legado's multi-rule auto-pick behaviour in TextFile.analyze.
     */
    fun autoDetect(sample: String): TxtTocRule? {
        var best: TxtTocRule? = null
        var bestCount = 0
        for (rule in presets) {
            if (rule.regex.isBlank()) continue
            val count = runCatching {
                val m = Pattern.compile(rule.regex, Pattern.MULTILINE).matcher(sample)
                var c = 0
                while (m.find() && c < 10_000) c++
                c
            }.getOrDefault(0)
            if (count > bestCount) {
                best = rule
                bestCount = count
            }
        }
        return if (bestCount >= AUTO_MIN_MATCHES) best else null
    }
}
