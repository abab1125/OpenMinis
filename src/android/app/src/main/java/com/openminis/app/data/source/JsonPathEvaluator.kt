package com.openminis.app.data.source

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tiny JSONPath evaluator supporting the legado subset used by book-source
 * rules: `$.a.b.c`, `$.a.b[0]`, `$.a[*]`.
 *
 * OpenMinis does not ship Gson, so this is built on [org.json]. It is enough
 * for JSON-API sources like lingya (ruleExplore/ruleSearch/ruleBookInfo all
 * use simple `$.field` paths). HTML/CSS/JS sources are out of scope for now.
 */
object JsonPathEvaluator {

    /** Return every value matched by [path] under [root] (may be the array itself). */
    fun evalList(root: Any?, path: String): List<Any> {
        val cleaned = path.trim().removePrefix("$").trimStart('.')
        if (cleaned.isEmpty()) return listOfNotNull(root)
        return traverse(root, tokenize(cleaned))
    }

    /** First matched value rendered as a string, or null. */
    fun evalString(root: Any?, path: String): String? {
        return evalList(root, path).firstOrNull()?.let { asString(it) }
    }

    private fun tokenize(expr: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < expr.length) {
            when (expr[i]) {
                '.' -> {
                    i++
                    var j = i
                    while (j < expr.length && expr[j] != '.' && expr[j] != '[') j++
                    tokens.add(expr.substring(i, j))
                    i = j
                }
                '[' -> {
                    val end = expr.indexOf(']', i)
                    if (end < 0) break
                    tokens.add(expr.substring(i, end + 1))
                    i = end + 1
                }
                else -> {
                    var j = i
                    while (j < expr.length && expr[j] != '.' && expr[j] != '[') j++
                    tokens.add(expr.substring(i, j))
                    i = j
                }
            }
        }
        return tokens.filter { it.isNotEmpty() }
    }

    private fun traverse(node: Any?, parts: List<String>): List<Any> {
        var current: Any? = node
        for (idx in parts.indices) {
            val part = parts[idx]
            current = when {
                part.startsWith("[") && part.endsWith("]") -> {
                    val inner = part.substring(1, part.length - 1)
                    if (inner == "*") {
                        val arr = current as? JSONArray ?: return emptyList()
                        return if (idx == parts.lastIndex) {
                            (0 until arr.length()).mapNotNull { arr.get(it) }
                        } else {
                            val rest = parts.drop(idx + 1)
                            val out = mutableListOf<Any>()
                            for (k in 0 until arr.length()) out.addAll(traverse(arr.get(k), rest))
                            out
                        }
                    } else {
                        val n = inner.toIntOrNull() ?: return emptyList()
                        (current as? JSONArray)?.opt(n)
                    }
                }
                else -> (current as? JSONObject)?.opt(part)
            }
        }
        return listOfNotNull(current)
    }

    fun asString(v: Any?): String? = when (v) {
        null -> null
        JSONObject.NULL -> null
        is String -> v
        is Number -> v.toString()
        is Boolean -> v.toString()
        else -> v.toString()
    }
}
