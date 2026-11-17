package com.khayali.pulao.ai

/**
 * Chat compaction. The on-device model has a small context, so a long thread is folded into a
 * summary the model can carry: each exchange becomes one line — the question and the first
 * sentence of the answer. Calculator answers start with the tool's exact summary, so the figures
 * that survive are the checked ones. Pure and unit-tested.
 */
object Compact {
    /** Exchanges kept in memory between compactions. */
    const val KEEP = 12
    private const val MAX_CHARS = 900

    fun summary(turns: List<ChatSession.Turn>): String? {
        val real = turns.filter { it.answer.isNotBlank() && !it.answer.startsWith("⚠️") }
        if (real.size < 2) return null
        val lines = real.map { t -> "- Asked: ${t.user.take(90).trim()} → ${firstSentence(t.answer)}" }
        // Newest exchanges matter most: drop from the oldest end until it fits.
        var keep = lines
        while (keep.size > 1 && keep.sumOf { it.length + 1 } > MAX_CHARS) keep = keep.drop(1)
        val dropped = lines.size - keep.size
        return (if (dropped > 0) "($dropped earlier exchanges left out.)\n" else "") + keep.joinToString("\n")
    }

    private fun firstSentence(answer: String): String {
        val clean = answer.replace(Regex("""\s+"""), " ").replace(Regex("""[*_`>#]"""), "").trim()
        // split on sentence ends, but not inside figures like ₹1,450.00 or 12.5%
        val end = Regex("""(?<!\d)[.!?](?=\s|$)|(?<=\d)[.!?](?=\s+[A-Z]|$)""").find(clean)?.range?.last ?: (clean.length - 1)
        return clean.substring(0, minOf(end + 1, clean.length)).take(220)
    }
}
