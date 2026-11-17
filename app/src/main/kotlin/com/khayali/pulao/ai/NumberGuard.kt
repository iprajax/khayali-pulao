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
     * Replace any ₹ amount the model wrote that doesn't match (±1%) a number the tools actually
     * produced this turn. The visual card already shows the exact figure.
     */
    fun verify(text: String, known: List<Double>): String {
        if (known.isEmpty()) return fix(text)
        return UNIT.replace(fix(text)) { m ->
            val v = (m.groupValues[1] + m.groupValues[2]).replace(",", "").toDoubleOrNull() ?: return@replace m.value
            // "₹2.5 crore" must be compared as 2,50,00,000, not as 2.5
            val scaled = v * when (m.groupValues[3].trim().lowercase()) { "cr", "crore", "crores" -> 1e7; "l", "lakh", "lakhs", "lac", "lacs" -> 1e5; "k" -> 1e3; else -> 1.0 }
            fun ok(x: Double) = known.any { k -> k != 0.0 && abs(x - k) / abs(k) <= 0.01 }
            if (ok(scaled) || ok(v) && m.groupValues[3].isBlank() || scaled < 100) m.value else "(see card)"
        }
    }

    /**
     * When tools ran, the exact numbers come from their summaries; from the model we keep only
     * up to [maxInsights] sentences that contain no digits (insight, not arithmetic).
     */
    fun compose(modelText: String, toolSummaries: List<String>, maxInsights: Int = 2): String {
        if (toolSummaries.isEmpty()) return modelText
        val insights = modelText.replace("\n", " ").split(Regex("""(?<=[.!?])\s+"""))
            .map { it.trim() }.filter { it.length > 12 && !it.any(Char::isDigit) && !it.contains("(see card)") && !it.contains("card above", true) }
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
