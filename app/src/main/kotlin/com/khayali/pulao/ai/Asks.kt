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
        Ask("Take the market's pulse", "/pulse", "market", "pulse market today overview snapshot how is the market"),
        Ask("What's the market mood — fear or greed?", "/mood", "market", "mood sentiment fear greed index"),
        Ask("What's in the news today?", "/news", "web", "news headlines today latest stories"),
        Ask("Any new filings about my stocks?", "/radar mine", "web", "filings announcements disclosures results orders exchange"),
        Ask("What's new from SEBI?", "/sebi", "web", "sebi regulator circular order rules"),
        Ask("Are any of my stocks on a surveillance list?", "/redflags", "web", "red flags surveillance asm gsm esm risk flagged"),
        Ask("How healthy is my portfolio?", "/healthscore", "market", "portfolio health score diversification risk concentration rate"),
        Ask("Which holdings could I sell to save tax?", "/harvest", null, "tax loss harvesting save tax losses gains"),
        Ask("Are FIIs long or short?", "/positioning", "web", "fii dii positioning long short futures options derivatives"),
        Ask("Find stocks near a 52-week high above their 200-day", "/find above 200 day near 52 week high", "market", "find screen filter stocks scanner near high breakout"),
        Ask("Set a price alert", "/alert", null, "alert notify price above below remind"),
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

    /** A task for the empty chat: what it does, in words, and what tapping it runs (a plan, a command or a fix). */
    data class Starter(val icon: String, val text: String, val run: String)

    /** Task-driven starters for an empty chat, personalised with the user's own stocks when known. */
    fun starters(holdings: List<String>, watched: List<String>, kite: Boolean): List<Starter> {
        val focus = (holdings + watched).firstOrNull()
        return listOf(
            Starter("🧭", "Plan my day — what should I do today?", "what should I do today?"),
            focus?.let { Starter("📰", "What's happening with $it — trend, news and results", "what's happening with $it") }
                ?: Starter("📰", "Why is the market moving today?", "why is the market moving today"),
            if (kite) Starter("💼", "Review my portfolio — trend, concentration, risk", "review my portfolio")
            else Starter("🔌", "Connect Kite to review your own portfolio (read-only)", "Connect Kite"),
            Starter("🎯", "Find a strategy that's working right now", "which strategy should I follow?"),
            Starter("📅", "What's coming up this week — results, dividends, IPOs", "/calendar"),
            Starter("🔎", "Find quality companies with low debt", "/screens quality low debt"),
        )
    }

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
