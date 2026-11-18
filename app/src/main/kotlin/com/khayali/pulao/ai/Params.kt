package com.khayali.pulao.ai

/**
 * Guided parameters: what each command needs, in order, with choices to tap. Typing "/sip " asks for the
 * monthly amount, then the return, then the years — each a row of pills — and runs once the last one is
 * picked. Optional parameters offer "Run now". Pure and unit-tested.
 */
object Params {
    data class Slot(val ask: String, val choices: List<String>, val optional: Boolean = false)
    data class Step(val ask: String, val choices: List<String>, val canRun: Boolean, val complete: Boolean)
    const val RUN = "▶ Run now"

    private val POPULAR = listOf("INFY", "TCS", "RELIANCE", "HDFCBANK", "ICICIBANK", "SBIN")

    fun slots(name: String, symbols: List<String>): List<Slot> {
        val syms = (symbols + POPULAR).distinct().take(8)
        val stock = Slot("Pick a stock", syms)
        val n = name.lowercase()
        return when (n) {
            "chart" -> listOf(stock, Slot("Over what period?", Commands.PERIODS, optional = true))
            "compare" -> listOf(stock, Slot("Compare it with", syms), Slot("Over what period?", Commands.PERIODS, optional = true))
            "health" -> listOf(Slot("Pick a stock — or run it for your holdings", syms, optional = true))
            "deals", "watch" -> listOf(Slot("Pick a stock (optional)", syms, optional = true))
            "results" -> listOf(Slot("Which day or company?", listOf("today", "tomorrow", "week") + syms.take(4), optional = true))
            "calendar" -> listOf(Slot("When?", listOf("today", "tomorrow", "week", "month"), optional = true))
            "heatmap" -> listOf(Slot("Which index?", Commands.INDEX_ARGS, optional = true))
            "peers" -> listOf(Slot("Which sector?", Commands.INDEX_ARGS.map { it.removePrefix("NIFTY ") }))
            "movers" -> listOf(Slot("Which list?", listOf("bank", "next50", "fno", "all"), optional = true))
            "indices" -> listOf(Slot("Which kind?", listOf("broad", "sectoral", "thematic", "strategy"), optional = true))
            "sectors" -> listOf(Slot("Ranked over?", listOf("30d", "1y"), optional = true))
            "screen" -> listOf(Slot("Which strategy?", com.khayali.pulao.ai.data.Market.STRATEGIES.map { it.id }))
            "learn" -> listOf(Slot("Which strategy?", com.khayali.pulao.ai.term.Playbook.ENTRIES.map { it.id }, optional = true))
            "screens" -> listOf(Slot("What kind of companies?", listOf("quality low debt", "dividend", "growth", "promoter", "breakout", "magic formula", "low pe"), optional = true))
            "data" -> listOf(Slot("What to download?", listOf("1y", "3y", "5y", "update", "knowledge"), optional = true))
            "web" -> listOf(Slot("What should I search?", listOf("why is nifty down today", "RBI policy this week", "why are FIIs selling")))
            "remember" -> listOf(Slot("What should I remember?", listOf("my goal is 1 crore in 15 years", "I invest for the long term", "I prefer low-risk stocks")))
            "sip" -> listOf(Slot("How much a month?", listOf("5000", "10000", "25000", "50000")), Slot("Expected yearly return?", listOf("10%", "12%", "15%")), Slot("For how long?", listOf("5y", "10y", "15y", "20y")))
            "goal" -> listOf(Slot("Your target?", listOf("50 lakh", "1 crore", "5 crore"), optional = true))
            "fd" -> listOf(Slot("Held for how many years?", listOf("1", "2", "3", "5"), optional = true))
            "move" -> listOf(Slot("If it moves by (%)?", listOf("-20", "-10", "10", "20", "50"), optional = true))
            "gmp" -> listOf(Slot("Which IPO? (type its name)", emptyList()))
            "why", "concall" -> listOf(stock)
            "find" -> listOf(Slot("Describe the stocks", listOf("above 200 day near 52 week high", "oversold above 200 day", "volume spike up 10% in a month", "low volatility golden cross")))
            "alert" -> listOf(stock, Slot("When?", listOf("above", "below", "moves")), Slot("Price or %", listOf("5", "10", "1000", "2000")))
            "harvest" -> listOf(Slot("Gains you've booked this year (optional)", listOf("25000", "50000", "100000", "200000"), optional = true))
            "redflags" -> listOf(Slot("A stock (optional)", syms, optional = true))
            "news" -> listOf(Slot("A stock — or run it for the whole market", syms, optional = true))
            "radar" -> listOf(Slot("All filings, or only your stocks?", listOf("mine"), optional = true))
            "sebi" -> listOf(Slot("Which kind?", listOf("circulars", "orders", "press", "consultation"), optional = true))
            "ipo", "price", "events", "quote" -> if (n == "ipo") emptyList() else listOf(stock)
            else -> {
                if (n in Commands.SYMBOL_COMMANDS) return listOf(stock)
                // other commands: offer their own example as a one-tap choice
                val usage = (Commands.ALL + Commands.TERMINAL).firstOrNull { it.name == n }?.usage ?: return emptyList()
                val example = usage.substringAfter(' ', "").trim()
                if (example.isEmpty()) emptyList() else if (example.matches(Regex("""[A-Z][A-Z0-9&\-]{1,14}"""))) listOf(stock)
                else listOf(Slot("Use the example or type your own", listOf(example)))
            }
        }
    }

    /** What to ask next for the command being typed, or null when [text] isn't a command with a name yet. */
    fun step(text: String, symbols: List<String>): Step? {
        val t = text.trimStart()
        if (!t.startsWith("/") || !t.contains(' ')) return null
        val name = t.removePrefix("/").substringBefore(' ')
        val slots = slots(name, symbols); if (slots.isEmpty()) return Step("", emptyList(), canRun = true, complete = true)
        val rest = t.substringAfter(' ')
        val tokens = rest.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val atBoundary = rest.isEmpty() || rest.endsWith(" ")
        var filled = if (atBoundary) tokens.size else tokens.size - 1
        // a slot whose choices have spaces ("NIFTY BANK", "quality low debt") takes everything typed after it
        val multi = slots.indexOfFirst { s -> s.choices.any { it.contains(' ') } }
        if (multi >= 0 && filled > multi) filled = slots.size
        if (filled >= slots.size) return Step("", emptyList(), canRun = true, complete = true)
        val slot = slots[filled]
        val partial = if (atBoundary) "" else if (filled == multi) rest.trim() else tokens.last()
        val choices = slot.choices.filter { it.startsWith(partial, ignoreCase = true) && !it.equals(partial, true) }.take(8)
        val canRun = slots.drop(filled).all { it.optional }
        return Step(slot.ask, choices, canRun, complete = false)
    }

    /** The text after picking [choice] for the current slot. */
    fun pick(text: String, choice: String): String = Commands.completeArg(text, choice)
}
