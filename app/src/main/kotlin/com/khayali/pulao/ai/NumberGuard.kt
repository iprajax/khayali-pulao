package com.khayali.pulao.ai

import com.khayali.pulao.Fmt
import kotlin.math.abs

/**
 * Small models sometimes mangle Indian digit grouping when they retype a number
 * ("₹3,10,0,5850"). The digits are usually right; the commas are not. We re-group
 * every ₹ amount the Indian way so what the user reads is always well-formed.
 */
object NumberGuard {
    private val RUPEE = Regex("""₹\s?(\d+(?:,\d+)*)(\.\d+)?""")
    private val UNIT = Regex("""₹\s?(\d+(?:,\d+)*)(\.\d+)?(\s?(?:crores?|cr|lakhs?|lacs?|l|k)\b)?""", RegexOption.IGNORE_CASE)

    /**
     * Drops every sentence with a ₹ amount the model wrote that doesn't match (±1%) a number the tools actually
     * produced this turn — the sentence goes, not just the figure, so the answer never reads "worth (see card)".
     * The card shows the exact figures; if nothing is left, the tools' own summaries speak.
     */
    fun verify(text: String, known: List<Double>): String {
        if (known.isEmpty()) return fix(text)
        fun trusted(m: MatchResult): Boolean {
            val v = (m.groupValues[1] + m.groupValues[2]).replace(",", "").toDoubleOrNull() ?: return true
            // "₹2.5 crore" must be compared as 2,50,00,000, not as 2.5
            val scaled = v * when (m.groupValues[3].trim().lowercase()) { "cr", "crore", "crores" -> 1e7; "l", "lakh", "lakhs", "lac", "lacs" -> 1e5; "k" -> 1e3; else -> 1.0 }
            fun ok(x: Double) = known.any { k -> k != 0.0 && abs(x - k) / abs(k) <= 0.01 }
            return ok(scaled) || ok(v) && m.groupValues[3].isBlank() || scaled < 100
        }
        return fix(text).lines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull line
            val kept = line.split(Regex("""(?<=[.!?])\s+""")).filter { s -> UNIT.findAll(s).all(::trusted) }.joinToString(" ")
            kept.takeIf { it.isNotBlank() && it.trim() !in setOf("-", "*", "•") }
        }.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /**
     * Next-step links the model wrote: repairs "Label](cmd:/x)" missing its "[", keeps links whose command the app
     * actually has, and drops the rest (a made-up command is worse than no suggestion).
     */
    fun tidyLinks(md: String, valid: (String) -> Boolean): String {
        val link = Regex("""]\((cmd:[^)\n]*)\)""")
        val out = StringBuilder(); var i = 0
        for (m in link.findAll(md)) {
            val before = md.substring(i, m.range.first)
            val open = before.lastIndexOf('['); val close = before.lastIndexOf(']')
            val cut = if (open > close) open else maxOf(before.lastIndexOf('\n'), before.lastIndexOf('*'), before.lastIndexOf('•'), before.lastIndexOf("  ") + 1,
                Regex("""[.?!:]\s""").findAll(before).lastOrNull()?.range?.last ?: -1)
            val prefix = before.substring(0, maxOf(cut, 0)).let { if (open > close) it else before.substring(0, cut + 1) }
            val label = before.substring(if (open > close) open + 1 else cut + 1).trim().trim('*', '_', ' ', '▸')
            val cmd = m.groupValues[1].removePrefix("cmd:").trim()
            out.append(prefix)
            if (label.isNotEmpty() && valid(cmd)) out.append(if (prefix.isEmpty() || prefix.last().isWhitespace() || prefix.last() == '*') "" else " ").append("[$label](cmd:$cmd)")
            i = m.range.last + 1
        }
        out.append(md.substring(i))
        return out.toString().lines().map { it.replace(Regex("""\s+[*•]\s*$"""), "") }.filter { it.trim() !in setOf("-", "*", "•") }
            .joinToString("\n").replace(Regex("""[ \t]{2,}"""), " ").trim()
    }

    /**
     * When tools ran, the exact numbers come from their summaries; from the model we keep only
     * up to [maxInsights] sentences that contain no digits (insight, not arithmetic).
     */
    fun compose(modelText: String, toolSummaries: List<String>, maxInsights: Int = 2): String {
        if (toolSummaries.isEmpty()) return modelText
        val insights = modelText.replace("\n", " ").split(Regex("""(?<=[.!?])\s+"""))
            .map { it.trim() }.filter { it.length > 12 && !it.any(Char::isDigit) && !it.contains("see card", true) && !it.contains("card above", true) }
            .take(maxInsights)
        return (toolSummaries.distinct().joinToString("\n\n") + if (insights.isEmpty()) "" else "\n\n" + insights.joinToString(" ")).trim()
    }

    private val NUM = Regex("""\d[\d,]*(?:\.\d+)?""")
    private fun norm(n: String) = n.replace(",", "").trimEnd('.').let { if (it.contains('.')) it.trimEnd('0').trimEnd('.') else it }

    /**
     * Web answers: a sentence survives only if every figure in it literally appears in the
     * source text (snippets + tool summaries). Citation markers like [2] and single digits are
     * ignored. Returns the kept text and whether anything was dropped.
     */
    fun grounded(text: String, corpus: String): Pair<String, Boolean> {
        val known = NUM.findAll(corpus).map { norm(it.value) }.toSet()
        var dropped = false
        val lines = text.lines().map { line ->
            line.split(Regex("""(?<=[.!?])\s+""")).filter { s ->
                val nums = NUM.findAll(s.replace(Regex("""\[\d+]"""), " ")).map { norm(it.value) }.filter { it.length > 1 }.toList()
                (nums.all { it in known }).also { if (!it) dropped = true }
            }.joinToString(" ")
        }
        return lines.filter { it.isNotBlank() && it.trim() !in setOf("-", "*", "•") }.joinToString("\n").trim() to dropped
    }

    fun fix(text: String): String = RUPEE.replace(text) { m ->
        val digits = m.groupValues[1].replace(",", "")
        if (digits.length > 15) return@replace m.value          // not a sane amount; leave it
        val whole = digits.toLongOrNull() ?: return@replace m.value
        "₹" + Fmt.amount(whole.toDouble()) + m.groupValues[2]
    }
}
