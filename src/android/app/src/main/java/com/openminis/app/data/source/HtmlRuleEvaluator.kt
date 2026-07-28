package com.openminis.app.data.source

import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * Evaluates legado's HTML-source rule subset against a parsed [org.jsoup]
 * document/element. This is the counterpart to [JsonPathEvaluator] for the
 * (vast majority of) legado book sources that scrape HTML instead of calling
 * a JSON API.
 *
 * Supported rule grammar (a pragmatic subset of legado's; enough for the
 * common Chinese/English book sites):
 *
 *  - `@css(selector)` / `@css:selector` / bare `selector`
 *    Select element(s) via CSS. Bare selectors are treated as CSS too (legado
 *    allows `ruleExplore.bookList` to be a plain CSS expression).
 *  - `@attr` suffix — appended after a selector to read an attribute instead
 *    of text, e.g. `@css:a@href` (the link's href) or `@css:img@src`.
 *    A rule that is *only* `@href` reads the attribute off the base element.
 *  - `##regex##replacement` — replace every match of `regex` with
 *    `replacement`. `##regex` (no replacement) removes every match. Applied
 *    to the final text (legado "clean up").
 *  - `:regex(…)` — extract via regex; returns the first capturing group if
 *    present, otherwise the whole match. Applied to the base element's text.
 *  - `&&` — concatenate the results of several sub-rules (legado joiner).
 *
 * All methods are wrapped in [runCatching] by callers; here every public
 * function returns null (or an empty list) on malformed input rather than
 * throwing, so one bad rule never blanks an entire book list.
 */
object HtmlRuleEvaluator {

    /** Select the element list matched by a *list* rule (bookList / chapterList). */
    fun selectElements(base: Element, rule: String): List<Element> {
        val c = rule.trim()
        if (c.isEmpty()) return emptyList()
        val (sel, _) = parseCss(c)
        val els: Elements = if (sel.isEmpty()) Elements(base) else runCatching { base.select(sel) }.getOrNull() ?: Elements()
        return els.toList()
    }

    /** Evaluate a *field* rule against [base] and return its plain text. */
    fun evalText(base: Element, rule: String): String? = eval(base, rule, keepHtml = false)

    /** Evaluate a *field* rule against [base] and return its inner HTML
     *  (used for chapter content so `<br>`/`<p>` survive as line breaks). */
    fun evalHtml(base: Element, rule: String): String? = eval(base, rule, keepHtml = true)

    // ── internals ───────────────────────────────────────────────────────

    private fun eval(base: Element, rule: String, keepHtml: Boolean): String? {
        val r = rule.trim()
        if (r.isEmpty()) return null
        val (core, cleanRegex, cleanRepl) = splitClean(r)
        val raw = evalCore(base, core, keepHtml) ?: return null
        return if (cleanRegex != null) raw.replace(Regex(cleanRegex), cleanRepl ?: "") else raw
    }

    /** Split a `left##regex##replacement` clean rule off the front. */
    private fun splitClean(rule: String): Triple<String, String?, String?> {
        val idx = rule.indexOf("##")
        if (idx < 0) return Triple(rule, null, null)
        val left = rule.substring(0, idx)
        val rest = rule.substring(idx + 2)
        val parts = rest.split("##", limit = 2)
        return Triple(left, parts[0], parts.getOrNull(1))
    }

    private fun evalCore(base: Element, core: String, keepHtml: Boolean): String? {
        // `&&` joins several sub-rules' text together.
        if (core.contains("&&")) {
            val joined = core.split("&&").joinToString("") { evalCore(base, it.trim(), keepHtml) ?: "" }
            return joined.takeIf { it.isNotEmpty() }
        }
        val c = core.trim()

        // `:regex(...)` extracts from the base element's own text.
        if (c.startsWith(":")) {
            val (pattern, group) = parseRegexCall(c) ?: return null
            val text = base.text()
            val m = runCatching { Regex(pattern).find(text) }.getOrNull() ?: return null
            return m.groupValues.getOrNull(group) ?: m.value
        }

        // Bare `text` / `ownText` / `html` read the base element itself
        // (legado uses a bare `text` rule on a node to take the node's own
        // text, not its descendants'). Handled before CSS parsing because
        // `base.select("text")` would fail for the literal token.
        if (c == "text" || c == "ownText") {
            return runCatching { base.ownText() }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        if (c == "html") {
            return runCatching { base.html() }.getOrNull()?.takeIf { it.isNotBlank() }
        }

        val (sel, attr) = parseCss(c)
        val target: Element = if (sel.isEmpty()) {
            base
        } else {
            runCatching { base.select(sel) }.getOrNull()?.firstOrNull() ?: return null
        }
        return when {
            attr != null ->
                runCatching { target.attr(attr) }.getOrNull()?.takeIf { it.isNotBlank() }
            keepHtml ->
                runCatching { target.html() }.getOrNull()?.takeIf { it.isNotBlank() }
            else ->
                runCatching { target.text() }.getOrNull()?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * Parse a `@css(...)` / `@css:...` / bare-selector / `@attr` rule into a
     * (selector, attribute) pair. `selector` may be empty when the rule is a
     * bare `@href`-style attribute read on the base element.
     */
    private fun parseCss(c: String): Pair<String, String?> {
        var s = c
        if (s.startsWith("@css")) {
            s = s.substring(4)
            when {
                s.startsWith("(") -> {
                    val end = s.indexOf(")")
                    if (end < 0) return "" to null
                    s = s.substring(1, end)
                }
                s.startsWith(":") -> s = s.substring(1)
            }
        }
        // Optional trailing @attr (e.g. `a@href`, `img@src`). A leading `@` with
        // no selector means "attribute on base" (e.g. `@href`).
        val atIdx = s.lastIndexOf("@")
        val attr = if (atIdx >= 0 && (atIdx == 0 || !s.substring(0, atIdx).contains("@"))) {
            s.substring(atIdx + 1).takeIf { it.isNotBlank() }
        } else {
            null
        }
        val sel = if (attr != null) s.substring(0, atIdx).trim() else s.trim()
        return sel to attr
    }

    /**
     * Parse a `:regex(...)` / `:R(...)` call. [c] still carries the leading
     * `:`. Returns (pattern, groupIndex) where groupIndex is 1 when the body
     * uses capturing parentheses, else 0 (whole match).
     */
    private fun parseRegexCall(c: String): Pair<String, Int>? {
        val body = c.removePrefix(":").removePrefix("regex").removePrefix("R").trim()
        if (!body.startsWith("(")) return null
        val end = body.lastIndexOf(")")
        if (end < 0) return null
        val inner = body.substring(1, end)
        // crude: if the inner uses a capturing group, return group 1.
        val group = if (inner.contains("(") && !inner.contains("\\(")) 1 else 0
        return inner to group
    }
}
