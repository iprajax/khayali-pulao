package com.khayali.pulao.ai

/**
 * What to ask next, suggested while the user types a plain question. Action-first: each suggestion is a
 * question that leads to a decision or a next step, and runs one exact command. Ranked by how well it
 * matches the words typed, then by whether what it needs (market history, Kite, web) is already set up.
 * Pure and unit-tested.
 */
object Asks {
    /** [needs]: "market", "knowledge", "kite", "web" or null. */
    data class Ask(val text: String, val command: String, val needs: String? = null, val keywords: String = "")

    val ALL = listOf(
        Ask("What should I do today?", "/brief", "market", "plan action next step today profit make money advice brief morning"),
        Ask("What needs my attention in my holdings?", "/health", "market", "portfolio holdings review risk attention weak falling my stocks"),
        Ask("Which strategy is working right now?", "/strategies", "market", "strategy best working backtest rules compare profit returns"),
        Ask("Which stocks does the best rule pick today?", "/screen sharpe", "market", "picks screen buy candidates ideas stocks today rule"),
        Ask("Any hidden patterns today?", "/anomalies", "market", "unusual hidden pattern volume spike breakout streak anomaly"),
        Ask("Is the market trending up or down?", "/regime", "market", "market trend regime breadth up down bull bear weak strong"),
        Ask("How did the market do today?", "/report", "web", "market today report nifty sensex sectors gainers losers"),
        Ask("Which sectors are moving?", "/sectormovers", "web", "sectors sector rotation moving leaders laggards"),
        Ask("What's coming up this week: results, dividends, splits?", "/calendar", "web", "calendar results dividend events week upcoming agm split bonus"),
        Ask("Which IPOs are open and what's their grey-market premium?", "/ipo", "web", "ipo listing gmp grey market new issue"),
        Ask("Show my holdings and today's P&L", "/holdings", "kite", "holdings portfolio pnl profit loss my stocks today"),
        Ask("How concentrated is my portfolio?", "/xray", "kite", "concentration diversification allocation weight portfolio risk"),
        Ask("Find screens for quality companies with low debt", "/screens quality low debt", "knowledge", "screens quality low debt roce fundamentals filter screener"),
        Ask("Find screens for dividend stocks", "/screens dividend", "knowledge", "dividend yield income screens"),
        Ask("Explain the magic formula", "/learn magic formula", null, "learn explain magic formula value greenblatt"),
        Ask("Which strategies can I learn?", "/learn", null, "learn strategies playbook teach explain list"),
        Ask("How much will a monthly SIP grow?", "/sip 10000 12% 15y", null, "sip invest monthly grow compound future value goal"),
        Ask("How do I reach my goal?", "/goal 1 crore", null, "goal crore target retire plan save"),
        Ask("Download market history", "/data 3y", null, "download data history market nse setup"),
    )

    private fun words(s: String) = Regex("""[a-z0-9&]+""").findAll(s.lowercase()).map { it.value }.toList()

    fun suggest(typed: String, ready: Set<String>, k: Int = 4): List<Ask> {
        val t = words(typed); if (t.isEmpty() || typed.trimStart().startsWith("/")) return emptyList()
        val last = t.last(); val full = t.dropLast(1).filter { it.length > 1 }
        return ALL.mapNotNull { a ->
            // words in the question itself count more than keywords
            val tw = words(a.text); val kw = words(a.keywords)
            fun score(q: String, partial: Boolean): Double = when {
                tw.any { it == q || (partial || q.length > 2) && it.startsWith(q) } -> 1.0
                kw.any { it == q || (partial || q.length > 2) && it.startsWith(q) } -> 0.6
                else -> 0.0 }
            val hits = full.sumOf { score(it, false) } + score(last, true)
            if (hits == 0.0) null else a to hits + (if (a.needs == null || a.needs in ready) 0.3 else 0.0)
        }.sortedByDescending { it.second }.take(k).map { it.first }
    }
}
