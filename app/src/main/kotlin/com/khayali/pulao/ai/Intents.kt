package com.khayali.pulao.ai

/**
 * Plain-language requests that clearly mean one command: "show my holdings" → /holdings,
 * "what IPOs are open" → /ipo, "chart of TCS for 1 year" → /chart TCS 1y. They run exactly like the
 * typed command — instant, no AI model needed. Patterns match the WHOLE short sentence, so a real
 * question ("should I worry about my holdings if rates rise?") still goes to the model, which can run
 * any command itself through its run_command tool. Pure and unit-tested.
 */
object Intents {
    private const val MAX_WORDS = 12

    /** "1 year", "a year", "one year", "5 years", "6 months", "1 week" → "1y", "5y", "6m", "1w". */
    fun period(text: String?): String? {
        val t = text?.lowercase()?.trim() ?: return null
        val n = Regex("""(\d+|a|an|one|two|three|five|six)\s*(week|wk|month|mon|year|yr)s?""").find(t) ?: return null
        val k = when (n.groupValues[1]) { "a", "an", "one" -> 1; "two" -> 2; "three" -> 3; "five" -> 5; "six" -> 6; else -> n.groupValues[1].toInt() }
        return when (n.groupValues[2].take(1)) {
            "w" -> "1w"
            "m" -> when { k <= 1 -> "1m"; k <= 3 -> "3m"; else -> "6m" }
            else -> if (k >= 3) "5y" else "1y"
        }
    }

    private fun day(text: String?): String? = Regex("""\b(today|tomorrow|this week|next week|this month)\b""").find(text?.lowercase().orEmpty())?.value
        ?.let { if (it.endsWith("week")) "week" else if (it == "this month") "month" else it }

    private val NAME = """([A-Za-z@][A-Za-z0-9&.\- ]{0,30}?)"""
    private val STOP = setOf("my", "the", "a", "an", "me", "it", "this", "that", "market", "stock", "stocks", "share", "shares", "today")
    private fun clean(name: String?): String? = name?.trim()?.removePrefix("@")?.removeSuffix("'s")?.trim()
        ?.takeIf { it.isNotBlank() && it.lowercase() !in STOP && it.split(' ').size <= 3 }

    private data class Rule(val re: Regex, val make: (MatchResult) -> String?)
    private fun r(pattern: String, make: (MatchResult) -> String?) = Rule(Regex("^(?:please |can you |could you |show me |show |give me |get me |tell me |what(?:'s| is| are) |i want )*(?:$pattern)$", RegexOption.IGNORE_CASE), make)

    private val RULES = listOf(
        // your account (read-only Kite)
        r("""(?:my )?(?:holdings|portfolio holdings|stocks i (?:own|hold)|what i own)""") { "/holdings" },
        r("""(?:my )?(?:portfolio )?(?:p&l|pnl|profit and loss|profit or loss)""") { "/pnl" },
        r("""(?:my )?(?:open )?positions(?: today)?""") { "/positions" },
        r("""(?:my )?(?:margins?|available margin|funds available|cash available)""") { "/margins" },
        r("""(?:my |today's )?orders(?: today)?""") { "/orders" },
        r("""(?:my )?(?:mutual funds|mf holdings|mfs)""") { "/mf" },
        // calendar, results, IPOs
        r("""(?:the )?(?:market |corporate |results |earnings |dividend )?calendar(?: for| of)?(?: (today|tomorrow|this week|next week|this month))?""") { m -> "/calendar ${day(m.groupValues[1]) ?: "week"}" },
        r("""(?:what(?:'s| is) )?happening in the market (today|tomorrow|this week|next week)""") { m -> "/calendar ${day(m.groupValues[1])}" },
        r("""(?:who (?:reports|announces) results|results (?:due|announced|coming)|which companies report results)(?: (today|tomorrow|this week|next week))?""") { m -> "/results ${day(m.groupValues[1]) ?: "today"}" },
        r("""(?:which |any |the )?(?:upcoming |open |current |latest |new )?ipos?(?: (?:are )?(?:open|opening|coming up|this week|today|now|right now|upcoming))*""") { "/ipo" },
        r("""(?:the )?(?:gmp|grey[- ]market premium)(?: (?:of|for|on) $NAME)?(?: ipo)?""") { m -> clean(m.groupValues[1])?.let { "/gmp $it" } ?: "/gmp" },
        r("""(?:the )?(?:latest |current )?(?:share |stock )?(?:price|quote|ltp) (?:of|for) $NAME""") { m -> clean(m.groupValues[1])?.let { "/price $it" } },
        r("""(?:how much is |what(?:'s| is) the price of )$NAME(?: trading at| trading| now| today)*""") { m -> clean(m.groupValues[1])?.let { "/price $it" } },
        r("""(?:a )?chart (?:of |for )?$NAME(?: (?:for|over) (?:the )?(?:last |past )?(.+))?""") { m -> clean(m.groupValues[1])?.takeIf { !it.contains(' ') }?.let { "/chart ${it.uppercase()} ${period(m.groupValues[2]) ?: ""}".trim() } },
        r("""(?:the )?(?:events|corporate actions|dividends|upcoming events|board meetings) (?:of|for) $NAME""") { m -> clean(m.groupValues[1])?.let { "/events $it" } },
        r("""(?:the )?(?:latest )?(?:news|headlines) (?:on|about|for|of) $NAME""") { m -> clean(m.groupValues[1])?.let { "/news $it" } },
        r("""(?:the )?(?:latest |quarterly )?results (?:of|for) $NAME""") { m -> clean(m.groupValues[1])?.let { "/results $it" } },
        // memory and help
        r("""what do you (?:remember|know) about me""") { "/memory" },
        r("""(?:my )?memory""") { "/memory" },
        r("""remember (?:that )?(.{6,160})""") { m -> "/remember ${m.groupValues[1]}" },
        r("""(?:help|commands|all commands|what can you do|list (?:all )?commands)""") { "/help" },
    )

    /** The command a short plain request stands for, or null to let the AI model handle it. */
    fun match(text: String): String? {
        val t = text.trim().replace(Regex("""\s+"""), " ").trimEnd('?', '.', '!', ' ')
        if (t.isEmpty() || t.startsWith("/") || t.split(' ').size > MAX_WORDS) return null
        for (rule in RULES) rule.re.matchEntire(t)?.let { m -> rule.make(m)?.let { return it.trim() } }
        return null
    }
}
