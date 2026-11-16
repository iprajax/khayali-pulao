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

    /**
     * Replace any ₹ amount the model wrote that doesn't match (±1%) a number the tools actually
     * produced this turn. The visual card already shows the exact figure.
     */
    fun verify(text: String, known: List<Double>): String {
        if (known.isEmpty()) return fix(text)
        return RUPEE.replace(fix(text)) { m ->
            val v = (m.groupValues[1] + m.groupValues[2]).replace(",", "").toDoubleOrNull() ?: return@replace m.value
            if (known.any { k -> k != 0.0 && abs(v - k) / abs(k) <= 0.01 } || v < 100) m.value else "(see card)"
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

    fun fix(text: String): String = RUPEE.replace(text) { m ->
        val digits = m.groupValues[1].replace(",", "")
        if (digits.length > 15) return@replace m.value          // not a sane amount; leave it
        val whole = digits.toLongOrNull() ?: return@replace m.value
        "₹" + Fmt.amount(whole.toDouble()) + m.groupValues[2]
    }
}
