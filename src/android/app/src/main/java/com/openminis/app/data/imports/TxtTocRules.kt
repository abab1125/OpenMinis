package com.openminis.app.data.imports

/**
 * Built-in TXT chapter-splitting rules. Ported (and simplified) from legado's
 * `assets/defaultData/txtTocRule.json` - we keep the handful of patterns that
 * cover the vast majority of Chinese web novels, plus an English Chapter/Section
 * rule. Users pick one in the import dialog; the selected [Rule.regex] is passed
 * to [TxtChapterSplitter.split].
 *
 * All patterns use MULTILINE matching and are anchored to a whole title line
 * (legado wraps them with `^...$` semantics via MULTILINE). They intentionally
 * accept up to ~30 trailing chars after the chapter marker so titles like
 * "第十二章 风起云涌" match in full.
 */
data class TxtTocRule(val name: String, val regex: String, val description: String)

object TxtTocRules {

    /** The rule selected by default in the import dialog. */
    val DEFAULT_INDEX = 0

    val presets: List<TxtTocRule> = listOf(
        TxtTocRule(
            name = "中文章节（序章/楔子/第X章）",
            regex = """^[ 　\t]{0,4}(?:序章|楔子|正文|终章|后记|尾声|番外|第[\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+?[章节卷集部篇(?!张)]).{0,30}$""",
            description = "匹配 第X章/节/卷/集/部/篇 及 序章/楔子/终章/番外 等",
        ),
        TxtTocRule(
            name = "中文章节（宽松）",
            regex = """^[ 　\t]{0,4}第[\d〇零一二两三四五六七八九十百千万]+[章节卷集部篇].{0,30}$""",
            description = "只匹配「第X章/节/卷/集/部/篇」，不含序章等",
        ),
        TxtTocRule(
            name = "Chapter / Section（英文）",
            regex = """^\s*(?:Chapter|Section|CHAPTER|SECTION)\s+[\dIVXLCDMivxlcdm]+.*$""",
            description = "匹配 Chapter 1 / Section II 等",
        ),
        TxtTocRule(
            name = "纯数字标题",
            regex = """^[ 　\t]{0,4}(\d{1,5})[、\.．].{0,30}$""",
            description = "匹配「1、」「2.」「3．」开头的数字标题",
        ),
        TxtTocRule(
            name = "无规则（按字数切分）",
            regex = "",
            description = "不匹配标题，每约 10KB 切一段，标题自动编号",
        ),
    )
}
