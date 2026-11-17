package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.data.Artifacts
import com.khayali.pulao.ai.data.Market
import com.khayali.pulao.ai.data.Panel

/** Shared wording for features that run on the user's own downloaded market history. */
private object Local {
    /** Up to 5 years of trading days for backtests. */
    const val HISTORY = 1300
    const val NEED = "This needs your market history on the phone. Type /data to download it (1 year is about 25 MB, from NSE's public archive)."
    const val NOT_ADVICE = "What the data and each rule show — not a recommendation to buy or sell. Past results don't promise future ones; backtests ignore costs and taxes."
    fun p(v: Double?, d: Int = 1) = v?.let { N.pct(it * 100, d) } ?: "—"
    /** The data a card was built from, for its "How this was made" panel. */
    fun span(p: Panel) = "${p.dates.first()} to ${p.dates.last()} · ${p.days} trading days · ${p.symbols.size} stocks"
    fun short(b: Market.Backtest) = b.months < 36
    /** "▲ beat the market by 3.7 pts" / "▼ lagged the market by 2.1 pts". */
    fun verdict(b: Market.Backtest): String { val x = (b.rate - b.benchRate) * 100
        return if (x >= 0) "▲ beat the market by ${"%.1f".format(x)} pts" else "▼ lagged the market by ${"%.1f".format(-x)} pts" }
    /** "+12.3% a year" or, for tests under a year, "+4.1% over 5 months". */
    fun rate(v: Double, b: Market.Backtest) = p(v) + if (b.annualised) " a year" else " over ${b.months} months"
    fun tests(p: Panel): List<Pair<Market.Strategy, Market.Backtest>> = Market.backtestAll(p)
}

/** /brief — the action brief: what is happening, what changed, what is unusual, how your holdings look, what each rule flags, and what to review. */
object BriefFeature : Feature {
    override val method = "Regime = share of liquid stocks above their 50- and 200-day simple averages (Broad uptrend ≥60/55%, Broad downtrend ≤40/45%), new 52-week highs and lows, and 20-day volatility of the equal-weight market against its past year. Hidden patterns = volume ≥4× the stock's 20-day median, a move ≥3.5× its 60-day daily volatility, 7+ days in one direction, or a new 52-week high on ≥2× volume. Holdings: trend vs 50/200-day averages and distance from the 52-week high. Next steps are tied to those numbers. Universe: liquid NSE stocks — median daily traded value of at least ₹5 crore over the last 60 sessions (ETFs and fund units excluded). Prices are chained from NSE's adjusted previous close, so splits and bonuses don't look like crashes. Backtests: every rule picks on the last trading day of each month using only data up to that day, holds its picks equally weighted for the month, and is compared with the equal-weight market of the same liquid stocks — all from one common start date. No costs, slippage or taxes."
    override val requires = "market"
    override val id = "brief"
    override val usage = "/brief"
    override val help = "Action brief: market regime, the week, hidden patterns, your holdings, strategy signals"
    override val group = Group.MARKET
    override val needsWeb = false
    override val triggers = listOf(Regex("""(?i)^(what should i (do|look at)( today)?|how (can|do) i make (money|profit)|what to do (today|now)|give me (a|my|the) (brief|briefing|plan)|morning brief|daily brief|action plan)\??$"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val p = f.market(Local.HISTORY) ?: return Card.error(Local.NEED, "/data 1y", "/data 3y", "/data 5y")
        return build(p, f.holdings.map { it.name.uppercase() to (it.current) }, f.profile, f.sectors())
    }

    fun build(p: Panel, holdings: List<Pair<String, Double>>, profile: List<String>, sectors: com.khayali.pulao.ai.data.MarketPack.SectorBook? = null): String {
        val r = Market.regime(p); val last = p.days - 1
        val anomalies = Market.anomalies(p, last, 6)
        val u = Market.liquid(p, last)
        val week = u.mapNotNull { s -> Market.change(p, s, last, 5)?.let { p.symbols[s] to it } }.sortedByDescending { it.second }
        val health = holdings.mapNotNull { (s, v) -> Market.health(p, s)?.let { it to v } }
        val total = holdings.sumOf { it.second }.takeIf { it > 0 }
        val tests = Local.tests(p)
        val longHorizon = profile.any { Regex("""(?i)(10|15|20|long|retire|years)""").containsMatchIn(it) }
        val cautious = profile.any { Regex("""(?i)(safe|low risk|conservative|cautious|steady)""").containsMatchIn(it) }

        // things to review — each one is tied to a number above
        val review = mutableListOf<Card.Row>()
        health.sortedByDescending { it.first.flags.size }.filter { it.first.flags.isNotEmpty() }.take(4).forEach { (h, _) ->
            review += Card.Row(h.symbol, h.flags.joinToString("; "), Local.p(h.r1m), if ((h.vs200 ?: 0.0) < 0) "warn" else "") }
        if (total != null) holdings.maxByOrNull { it.second }?.let { (s, v) -> if (v / total >= 0.4) review += Card.Row("Concentration", "$s is ${(v / total * 100).toInt()}% of your portfolio", "", "warn") }
        if (r.above50 < 40) review += Card.Row("Weak breadth", "only ${r.above50.toInt()}% of liquid stocks are above their 50-day average", "", "warn")
        if ((r.volPercentile ?: 0.0) >= 80) review += Card.Row("Elevated volatility", "market swings are higher than ${r.volPercentile!!.toInt()}% of the past year", "", "warn")

        val tone = when (r.label) { "Broad uptrend", "Recovering" -> "up"; "Broad downtrend", "Weakening" -> "down"; else -> "" }
        val card = Card("Action brief · ${p.dates[last]} · your own NSE data", if (tone == "down") "saffron" else "green")
            .headline(r.label, "${r.above50.toInt()}% of ${r.stocks} liquid stocks above their 50-day average, ${r.above200.toInt()}% above their 200-day · market ${Local.p(r.market1w)} this week", tone)
            .kpis(listOf(
                Card.Kpi("TODAY (EQUAL-WEIGHT)", Local.p(r.market1d, 2), r.market1d * 100), Card.Kpi("THIS WEEK", Local.p(r.market1w), r.market1w * 100),
                Card.Kpi("THIS MONTH", Local.p(r.market1m), r.market1m * 100), Card.Kpi("NEW HIGHS / LOWS", "${r.newHighs} / ${r.newLows}", null, "52-week"),
                Card.Kpi("VOLATILITY", r.volNow?.let { "%.0f%%".format(it * 100) } ?: "—", null, r.volPercentile?.let { "higher than ${it.toInt()}% of the year" } ?: ""),
                Card.Kpi("ADV / DEC", "${r.advances} / ${r.declines}", null, "today")))
            .breadth("Breadth today · liquid stocks", r.advances, r.declines, (r.stocks - r.advances - r.declines).coerceAtLeast(0))
        if (week.size >= 6) card.bars("This week · biggest moves", (week.take(4) + week.takeLast(4)).map { Card.Bar(it.first, it.second * 100) })
        card.movers("Hidden patterns today", anomalies.map { Card.Mover(it.symbol, "${it.kind} · ${it.detail}", null, it.move * 100) })
        if (health.isNotEmpty()) card.movers("Your holdings · 1 month", health.map { (h, _) ->
            Card.Mover(h.symbol, listOfNotNull(h.vs200?.let { "${Local.p(it)} vs 200-day" }, h.fromHigh?.let { "${Local.p(it)} from high" }).joinToString(" · "), null, h.r1m?.let { it * 100 }, emptyList(), null, true) })
        // sectors this week, from NSE's official indices: leaders and laggards, each opens its own view
        val secRows = sectors?.let { com.khayali.pulao.ai.data.Sectors.board(it.series, it.meta) }.orEmpty()
            .filter { it.changes[com.khayali.pulao.ai.data.Sectors.Period.W1] != null }.sortedByDescending { it.changes[com.khayali.pulao.ai.data.Sectors.Period.W1] }
        if (secRows.size >= 4) card.rows("Sectors this week · official NSE indices", (secRows.take(3) + secRows.takeLast(3)).map { r ->
            val w = r.changes[com.khayali.pulao.ai.data.Sectors.Period.W1]!!
            Card.Row(r.sector.short, "1D ${Local.p(r.changes[com.khayali.pulao.ai.data.Sectors.Period.D1], 2)} · 1M ${Local.p(r.changes[com.khayali.pulao.ai.data.Sectors.Period.M1])}", Local.p(w, 2), N.tone(w), action = "/sector ${r.sector.short} 1w") })
        card.section("What each strategy flags now")
        card.rows(null, tests.sortedByDescending { it.second.rate - it.second.benchRate }.map { (st, b) ->
            val fit = when { cautious && st.id == "lowvol" -> " · fits your low-risk note"; longHorizon && (st.id == "momentum" || st.id == "trend") -> " · long-horizon rule"; else -> "" }
            Card.Row(st.name, "${Local.verdict(b)} · now: ${b.current.take(3).joinToString(", ")}$fit", Local.p(b.rate), N.tone(b.rate), action = "/screen ${st.id}") })
        card.rows("Worth reviewing", review)
        // Next steps: concrete actions, most useful first, each tied to a number above and to one command
        val best0 = tests.maxByOrNull { it.second.rate - it.second.benchRate }
        val next = mutableListOf<Card.Row>()
        health.filter { it.first.flags.isNotEmpty() }.sortedBy { it.first.vs200 ?: 0.0 }.take(2).forEach { (h, _) ->
            next += Card.Row("Review ${h.symbol}", h.flags.first().replaceFirstChar { it.uppercase() } + " — check its trend and news", "/health ${h.symbol}") }
        if (total != null) holdings.maxByOrNull { it.second }?.let { (s, v) -> if (v / total >= 0.4) next += Card.Row("Reduce concentration risk", "$s is ${(v / total * 100).toInt()}% of your portfolio — see how a fall would hit you", "/stress") }
        best0?.let { (st, b) -> if (b.rate > b.benchRate) next += Card.Row("See today's ${st.name.lowercase()} picks", "the best rule on your data: ${Local.p(b.rate)} vs the market's ${Local.p(b.benchRate)}", "/screen ${st.id}") }
        secRows.firstOrNull()?.let { r -> next += Card.Row("See what's driving ${r.sector.short}", "the strongest sector this week: ${Local.p(r.changes[com.khayali.pulao.ai.data.Sectors.Period.W1])}", "/sector ${r.sector.short} 1w") }
        anomalies.firstOrNull()?.let { a -> next += Card.Row("Find out why ${a.symbol} moved", "${a.kind.lowercase()}: ${a.detail}", "/news ${a.symbol}") }
        if (holdings.isEmpty()) next += Card.Row("Add your holdings", "connect Kite (read-only) and the brief reviews each of your stocks", "Connect Kite")
        if (profile.isEmpty()) next += Card.Row("Tell me your goal and horizon", "so the brief can point to rules that fit you", "/remember my goal is …")
        tests.firstOrNull()?.second?.let { if (Local.short(it)) next += Card.Row("Get a fairer backtest", "only ${it.months} months tested so far", "/data 5y") }
        card.section("Next steps", "up")
        card.rows(null, next.take(5))
        tests.firstOrNull()?.second?.let { if (Local.short(it)) card.note("Backtests cover only ${it.months} months of your downloaded data — too short to judge a strategy. A 3- or 5-year pack (/data) gives longer tests.") }
        card.note(Local.NOT_ADVICE)
        val best = tests.maxByOrNull { it.second.rate - it.second.benchRate }
        val summary = buildString {
            append("${p.dates[last]}: ${r.label.lowercase()} — ${r.above50.toInt()}% of ${r.stocks} liquid stocks above their 50-day average, ${r.above200.toInt()}% above their 200-day; equal-weight market ${Local.p(r.market1w)} this week and ${Local.p(r.market1m)} this month. ")
            anomalies.firstOrNull()?.let { append("Most unusual today: ${it.symbol} (${it.kind.lowercase()}, ${it.detail}). ") }
            if (secRows.size >= 2) append("Sectors this week: ${secRows.first().sector.short} led (${Local.p(secRows.first().changes[com.khayali.pulao.ai.data.Sectors.Period.W1])}), ${secRows.last().sector.short} lagged (${Local.p(secRows.last().changes[com.khayali.pulao.ai.data.Sectors.Period.W1])}). ")
            if (health.isNotEmpty()) append("Of your ${health.size} holdings, ${health.count { it.first.flags.isNotEmpty() }} have something worth reviewing. ")
            best?.let { (st, b) -> append("Over the last ${b.months} months the ${st.name.lowercase()} rule did best (${Local.rate(b.rate, b)} vs ${Local.p(b.benchRate)} for the market). ") }
            append("Source: NSE daily data on this phone.")
        }
        val nextCmds = next.map { it.value }.filter { it.startsWith("/") && !it.endsWith("…") }
        return card.sources("NSE (your copy)").put("span", Local.span(p)).put("asOf", p.dates.last().toString()).pills(*(nextCmds + listOf("/strategies", "/anomalies")).distinct().take(5).toTypedArray())
            .put("regime", r.label).json(summary)
    }
}

object RegimeFeature : Feature {
    override val method = "Share of liquid stocks above their 50- and 200-day simple averages; labels: Broad uptrend (≥60% above 200-day and ≥55% above 50-day), Broad downtrend (≤40% and ≤45%), Recovering/Weakening when the 50-day share leads or lags by 10 points. New highs/lows over 252 sessions; volatility = 20-day standard deviation of the equal-weight market, annualised, ranked against the past year. Universe: liquid NSE stocks — median daily traded value of at least ₹5 crore over the last 60 sessions (ETFs and fund units excluded). Prices are chained from NSE's adjusted previous close, so splits and bonuses don't look like crashes."
    override val requires = "market"
    override val id = "regime"; override val usage = "/regime"; override val group = Group.MARKET; override val needsWeb = false
    override val help = "Market regime from your own data: breadth, trend, new highs and lows, volatility"
    override val triggers = listOf(Regex("""(?i)^(market regime|is the market (in an? )?(up|down)trend|market (health|trend))\??$"""))
    override suspend fun run(arg: String, f: Fetch): String {
        val p = f.market(300) ?: return Card.error(Local.NEED, "/data 1y", "/data 3y", "/data 5y")
        val r = Market.regime(p)
        return Card("Market regime · ${r.date}", if (r.above50 >= 50) "green" else "saffron")
            .headline(r.label, "${r.stocks} liquid stocks · market ${Local.p(r.market1m)} this month", if (r.above50 >= 50) "up" else "down")
            .bars("Share of liquid stocks above their average", listOf(Card.Bar("Above 50-day", r.above50 - 50, "${r.above50.toInt()}% (50% = neutral)"), Card.Bar("Above 200-day", r.above200 - 50, "${r.above200.toInt()}%")), "pts")
            .breadth("Advances and declines today", r.advances, r.declines, (r.stocks - r.advances - r.declines).coerceAtLeast(0))
            .kpis(listOf(Card.Kpi("NEW 52-WK HIGHS", "${r.newHighs}"), Card.Kpi("NEW 52-WK LOWS", "${r.newLows}"), Card.Kpi("VOLATILITY", r.volNow?.let { "%.0f%%".format(it * 100) } ?: "—", null, r.volPercentile?.let { "percentile ${it.toInt()}" } ?: "")))
            .note("Regime is read from participation: how many stocks are above their 50- and 200-day averages. " + Local.NOT_ADVICE)
            .sources("NSE (your copy)").put("span", Local.span(p)).put("asOf", p.dates.last().toString()).pills("/brief", "/anomalies", "/strategies")
            .json("${r.date}: ${r.label.lowercase()}; ${r.above50.toInt()}% of ${r.stocks} liquid stocks above their 50-day average and ${r.above200.toInt()}% above their 200-day; ${r.newHighs} new 52-week highs and ${r.newLows} lows. Source: NSE daily data on this phone.")
    }
}

object AnomaliesFeature : Feature {
    override val method = "Each stock is compared with its own history: volume spike = today's volume ≥4× its 20-day median; outsized move = today's move ≥3.5× its 60-day daily volatility; streak = 7+ sessions in one direction; breakout = a new 52-week closing high on ≥2× volume. Strongest signal first. Universe: liquid NSE stocks — median daily traded value of at least ₹5 crore over the last 60 sessions (ETFs and fund units excluded). Prices are chained from NSE's adjusted previous close, so splits and bonuses don't look like crashes."
    override val requires = "market"
    override val id = "anomalies"; override val usage = "/anomalies"; override val group = Group.MARKET; override val needsWeb = false
    override val help = "Hidden patterns today: volume spikes, outsized moves, streaks, breakouts vs each stock's history"
    override val triggers = listOf(Regex("""(?i)^((any |show )?(hidden patterns?|anomal(y|ies)|unusual (moves|activity|stocks)|what('s| is) unusual( today)?))\??$"""))
    override suspend fun run(arg: String, f: Fetch): String {
        val p = f.market(300) ?: return Card.error(Local.NEED, "/data 1y", "/data 3y", "/data 5y")
        val a = Market.anomalies(p, p.days - 1, 15)
        if (a.isEmpty()) return Card.error("Nothing unusual among liquid stocks on ${p.dates.last()}.")
        val mine = f.holdings.map { it.name.uppercase() }.toSet() + f.watched
        val kinds = a.groupingBy { it.kind }.eachCount()
        return Card("Hidden patterns · ${p.dates.last()}", "violet")
            .headline("${a.size} unusual stocks", kinds.entries.joinToString(" · ") { "${it.value} ${it.key.lowercase()}" })
            .movers(null, a.map { Card.Mover(it.symbol, "${it.kind} · ${it.detail}", null, it.move * 100, emptyList(), null, it.symbol in mine) })
            .note("Each pattern is measured against that stock's own recent history (volume vs its 20-day median; moves vs its 60-day volatility). Unusual activity often follows news — check what happened before reading anything into it.")
            .sources("NSE (your copy)").put("span", Local.span(p)).put("asOf", p.dates.last().toString()).pills("/brief", "/news ${a.first().symbol}", "/health ${a.first().symbol}")
            .json("${a.size} unusual liquid stocks on ${p.dates.last()}: " + a.take(4).joinToString("; ") { "${it.symbol} ${it.kind.lowercase()} (${it.detail}, ${Local.p(it.move)})" } + ". Source: NSE daily data on this phone.")
    }
}

object StrategiesFeature : Feature {
    override val method = "Fourteen price-and-volume rules from the playbook (see /learn), each backtested on your history. Backtests: every rule picks on the last trading day of each month using only data up to that day, holds its picks equally weighted for the month, and is compared with the equal-weight market of the same liquid stocks — all from one common start date. No costs, slippage or taxes. Universe: liquid NSE stocks — median daily traded value of at least ₹5 crore over the last 60 sessions (ETFs and fund units excluded). Prices are chained from NSE's adjusted previous close, so splits and bonuses don't look like crashes."
    override val requires = "market"
    override val id = "strategies"; override val usage = "/strategies"; override val group = Group.STOCK; override val needsWeb = false
    override val help = "Every strategy in the playbook backtested on your data, with what each flags today"
    override val triggers = listOf(Regex("""(?i)^((which|what) strateg(y|ies)( work(s|ed)?| is best)?|compare strategies|all strategies|strategy (list|playbook))\??$"""))
    override suspend fun run(arg: String, f: Fetch): String {
        val p = f.market(Local.HISTORY) ?: return Card.error(Local.NEED, "/data 1y", "/data 3y", "/data 5y")
        val tests = Local.tests(p)
        if (tests.isEmpty()) return Card.error("Not enough history for a backtest yet: ${p.days} trading days on the phone, and the rules need up to a year of warm-up plus a few months to test. Download 3 or 5 years.", "/data 3y", "/data 5y", "/learn momentum")
        val b0 = tests.first().second
        val c = Card("Strategy playbook · ${b0.from} to ${b0.to}", "blue")
            .headline("${tests.count { it.second.rate > it.second.benchRate }} of ${tests.size} beat the market", "Equal-weight liquid market: ${Local.rate(b0.benchRate, b0)}, same period")
            .viz("multiline", 220, mapOf("t" to b0.curve.map { it.first.toString() }, "base" to 100, "yLabel" to "Value of ₹100", "xLabel" to "Month",
                "series" to (tests.mapIndexed { i, (st, b) -> mapOf("name" to st.name, "color" to listOf("blue", "green", "saffron", "violet", "pink", "teal", "red", "yellow")[i % 8], "v" to b.curve.map { it.second * 100 }) } +
                    listOf(mapOf("name" to "Market", "color" to "muted", "v" to b0.bench.map { it.second * 100 })))), "Value of ₹100 in each strategy, rebalanced monthly, vs the equal-weight market.")
            .rows("Results · vs the market's ${Local.rate(b0.benchRate, b0)}", tests.sortedByDescending { it.second.rate }.map { (st, b) -> Card.Row(st.name, Local.verdict(b) + " · worst fall ${Local.p(b.maxDrawdown)}", Local.rate(b.rate, b), N.tone(b.rate), action = "/screen ${st.id}") })
        val skipped = Market.STRATEGIES.filter { st -> tests.none { it.first == st } }
        if (skipped.isNotEmpty()) c.rows("Need more history", skipped.map { Card.Row(it.name, "needs ${it.need} trading days of warm-up plus 3 months to test; you have ${p.days}", "/data 3y", "warn") })
        if (skipped.isNotEmpty()) c.pills("/data 3y")
        if (Local.short(b0)) c.note("Only ${b0.months} months of history — too short to judge any strategy. A longer pack (/data) gives a fairer test.")
        return c.note(Local.NOT_ADVICE).sources("NSE (your copy)").put("span", Local.span(p)).put("asOf", p.dates.last().toString()).pills("/screen ${tests.maxBy { it.second.rate }.first.id}", "/learn ${tests.maxBy { it.second.rate }.first.id}", "/brief")
            .json(tests.sortedByDescending { it.second.rate }.let { r -> "Best over ${b0.months} months: ${r[0].first.name} ${Local.rate(r[0].second.rate, b0)}" +
                (r.getOrNull(1)?.let { ", then ${it.first.name} ${Local.p(it.second.rate)}" } ?: "") + "; the market made ${Local.p(b0.benchRate)}. " +
                "${tests.count { it.second.rate > it.second.benchRate }} of ${tests.size} rules beat it. " +
                "All results: " + r.joinToString("; ") { (st, b) -> "${st.name} ${Local.p(b.rate)} (worst fall ${Local.p(b.maxDrawdown)})" } + ". No costs or taxes. Source: NSE daily data on this phone." })
    }
}

object ScreenFeature : Feature {
    override val method = "Applies one rule to the latest trading day and lists its top picks, with the same rule's backtest. Backtests: every rule picks on the last trading day of each month using only data up to that day, holds its picks equally weighted for the month, and is compared with the equal-weight market of the same liquid stocks — all from one common start date. No costs, slippage or taxes. Universe: liquid NSE stocks — median daily traded value of at least ₹5 crore over the last 60 sessions (ETFs and fund units excluded). Prices are chained from NSE's adjusted previous close, so splits and bonuses don't look like crashes."
    override val requires = "market"
    override val id = "screen"; override val usage = "/screen momentum"; override val group = Group.STOCK; override val needsWeb = false
    override val help = "Run one strategy today (momentum, breakout, lowvol, rsi, … see /strategies): its picks and backtest"
    override suspend fun run(arg: String, f: Fetch): String {
        val st = Market.strategy(arg.trim().ifBlank { "momentum" }) ?: Playbook.search(arg).firstOrNull()?.first?.let { Market.strategy(it.id) }
            ?: return Card.error("Pick a strategy: ${Market.STRATEGIES.joinToString(", ") { it.id }}.")
        val p = f.market(Local.HISTORY) ?: return Card.error(Local.NEED, "/data 1y", "/data 3y", "/data 5y")
        val last = p.days - 1
        val picks = st.pick(p, last, Market.liquid(p, last)).take(st.top)
        val b = Market.backtestCached(p, st)
        val mine = f.holdings.map { it.name.uppercase() }.toSet()
        val c = Card("${st.name} · screen · ${p.dates[last]}", "blue")
            .headline("${picks.size} stocks match", st.rule)
            .movers("What the rule picks today", picks.map { s -> Card.Mover(p.symbols[s], listOfNotNull(Market.change(p, s, last, 21)?.let { "1 month ${Local.p(it)}" }, Market.change(p, s, last, 252)?.let { "1 year ${Local.p(it)}" }).joinToString(" · "),
                null, Market.change(p, s, last, 1)?.let { it * 100 }, emptyList(), null, p.symbols[s] in mine) })
        b?.let { c.viz("multiline", 200, mapOf("t" to it.curve.map { x -> x.first.toString() }, "base" to 100, "yLabel" to "Value of ₹100", "xLabel" to "Month",
            "series" to listOf(mapOf("name" to st.name, "color" to "blue", "v" to it.curve.map { x -> x.second * 100 }), mapOf("name" to "Market", "color" to "muted", "v" to it.bench.map { x -> x.second * 100 }))),
            "Backtest: ${Local.rate(it.rate, it)} vs ${Local.p(it.benchRate)} for the market; max fall ${Local.p(it.maxDrawdown)}.") }
        return c.note(Local.NOT_ADVICE).sources("NSE (your copy)").put("span", Local.span(p)).put("asOf", p.dates.last().toString()).pills("/learn ${st.id}", "/strategies", "/health ${picks.firstOrNull()?.let { p.symbols[it] } ?: ""}".trim())
            .json("${st.name} today picks ${picks.take(8).joinToString(", ") { p.symbols[it] }}" + (b?.let { "; backtest ${Local.rate(it.rate, it)} vs ${Local.p(it.benchRate)} for the market" } ?: "") + ". Source: NSE daily data on this phone.")
    }
}

object HealthFeature : Feature {
    override val method = "Returns over 1 week, 1 month, 3 months and 1 year from adjusted prices; distance from the 50- and 200-day simple averages and from the 52-week high; 60-day annualised volatility; flags when below the 200-day average, when the 50-day is under the 200-day, or more than 25% below the high."
    override val requires = "market"
    override val id = "health"; override val usage = "/health INFY"; override val group = Group.PORTFOLIO; override val needsWeb = false
    override val help = "One stock's health from your data: trend vs its averages, distance from its high, volatility, anything unusual"
    override suspend fun run(arg: String, f: Fetch): String {
        val p = f.market(300) ?: return Card.error(Local.NEED, "/data 1y", "/data 3y", "/data 5y")
        val syms = if (arg.isBlank()) f.holdings.map { it.name } else listOf(arg.trim().removePrefix("@"))
        if (syms.isEmpty()) return Card.error("Which stock? Try /health INFY.")
        val hs = syms.mapNotNull { Market.health(p, it) }
        if (hs.isEmpty()) return Card.error("No data for ${syms.first().uppercase()} in your market history.")
        val c = Card(if (hs.size == 1) "${hs[0].symbol} · health · ${p.dates.last()}" else "Your holdings · health", "blue")
            .headline(if (hs.size == 1) (if (hs[0].flags.isEmpty()) "Nothing flagged" else "${hs[0].flags.size} thing(s) to look at") else "${hs.count { it.flags.isNotEmpty() }} of ${hs.size} flagged",
                if (hs.size == 1) hs[0].flags.joinToString("; ").ifBlank { "above its averages, near its high" } else "trend, distance from high and volatility for each")
        hs.forEach { h -> c.kpis(listOf(Card.Kpi("${h.symbol} · 1 WEEK", Local.p(h.r1w), h.r1w?.times(100)), Card.Kpi("1 MONTH", Local.p(h.r1m), h.r1m?.times(100)), Card.Kpi("1 YEAR", Local.p(h.r1y), h.r1y?.times(100)),
            Card.Kpi("VS 200-DAY", Local.p(h.vs200), h.vs200?.times(100)), Card.Kpi("FROM 52W HIGH", Local.p(h.fromHigh), h.fromHigh?.times(100)), Card.Kpi("VOLATILITY", h.vol?.let { "%.0f%%".format(it * 100) } ?: "—"))) }
        return c.note(Local.NOT_ADVICE).sources("NSE (your copy)").put("span", Local.span(p)).put("asOf", p.dates.last().toString()).pills("/chart ${hs[0].symbol} 1y", "/events ${hs[0].symbol}", "/brief")
            .json(hs.joinToString(" ") { h -> "${h.symbol}: 1 month ${Local.p(h.r1m)}, 1 year ${Local.p(h.r1y)}, ${Local.p(h.vs200)} vs its 200-day average, ${Local.p(h.fromHigh)} from its 52-week high" + (if (h.flags.isEmpty()) "." else "; " + h.flags.joinToString(", ") + ".") } + " Source: NSE daily data on this phone.")
    }
}

object LearnFeature : Feature {
    override val method = "Text from the app's playbook. For the fundamental strategies, the thresholds shown are the ones most used across 500 public stock screens on screener.in (counted on a Mac when the knowledge bank was built)."
    override val id = "learn"; override val usage = "/learn momentum"; override val group = Group.TOOLS; override val needsWeb = false
    override val help = "Learn a strategy: the idea, why it has worked, when it struggles, how to read it"
    override val triggers = listOf(Regex("""(?i)^(explain|what is|teach me|how does) (the )?(momentum|low[- ]?vol(atility)?|trend following|52[- ]week high|mean reversion|pullback|golden cross|magic formula|piotroski|coffee can|garp|peg|graham|dividend|quality)( strategy| investing| score)?\??$"""),
        Regex("""(?i)^(strategy playbook|all strategies to learn|teach me strategies)\??$"""))
    override suspend fun run(arg: String, f: Fetch): String {
        val q = arg.trim()
        if (q.isEmpty() || q.equals("all", true)) return catalogue()
        val hits = Playbook.get(q)?.let { listOf(it to 1.0) } ?: Playbook.search(q)
        val e = hits.firstOrNull()?.first ?: return Card.error("I don't have a strategy matching \"$q\".", "/learn")
        val runs = e.needs.isEmpty()
        val c = Card("Playbook · ${e.name}", if (runs) "violet" else "blue")
            .headline(e.name, e.idea)
        if (e.rules.isNotBlank()) c.rows("Typical rules", listOf(Card.Row("Most used thresholds", e.rules)))
        c.rows(null, listOf(Card.Row("▲ Why it has worked", e.why), Card.Row("▼ When it struggles", e.struggles), Card.Row("How to read it", e.read)))
        f.knowledge?.search(e.name + " " + e.tags.joinToString(" "), 3)?.takeIf { it.isNotEmpty() }?.let { hs ->
            c.rows("Community screens using this idea", hs.map { Card.Row(it.screen.name, it.screen.query.take(120) + if (it.screen.query.length > 120) "…" else "") }) }
        if (runs) c.note("Runs on your own data: /screen ${e.id} · compare every rule: /strategies")
        else c.note("Needs ${e.needs}. Run it on a fundamentals screener; here you can check a candidate's price trend with /health SYMBOL.")
        return c.sources("Playbook").pills(*listOfNotNull(if (runs) "/screen ${e.id}" else null, hits.getOrNull(1)?.first?.let { "/learn ${it.id}" }, "/learn", "/strategies").toTypedArray())
            .json("${e.name}: ${e.idea}" + (if (e.rules.isNotBlank()) " Typical rules: ${e.rules}." else "") + " It struggles in ${e.struggles.replaceFirstChar { it.lowercase() }}")
    }

    private fun catalogue(): String {
        val (here, fin) = Playbook.ENTRIES.partition { it.needs.isEmpty() }
        return Card("Strategy playbook", "violet")
            .headline("${Playbook.ENTRIES.size} strategies", "${here.size} run on your own NSE data · ${fin.size} need company financials")
            .rows("Runs on your data · /screen and /strategies", here.map { Card.Row(it.name, it.idea, "/learn ${it.id}") })
            .rows("Needs company financials · learn the rules", fin.map { Card.Row(it.name, it.rules.ifBlank { it.idea }, "/learn ${it.id}") })
            .note("Thresholds for the financial strategies are the ones most used across 500 public stock screens. Educational only.")
            .sources("Playbook").pills("/learn quality", "/learn magic formula", "/learn momentum", "/strategies")
            .json("${Playbook.ENTRIES.size} strategies: ${here.size} run on your own NSE data (${here.joinToString(", ") { it.name }}), ${fin.size} need company financials (${fin.joinToString(", ") { it.name }}).")
    }
}

/** /data — the artifact manager: what's on the phone, its state, and what you can do with it. */
object DataFeature : Feature {
    override val method = "Lists what is installed and its state (not installed, downloading, checking, ready, update available). Market history: NSE's daily bhavcopy files fetched by this phone from nsearchives.nseindia.com, in both of NSE's formats, stored in a private database. Knowledge bank: one file from the project site, accepted only if its SHA-256 matches."
    override val id = "data"; override val usage = "/data"; override val group = Group.TOOLS; override val needsWeb = false
    override val help = "Models and data on this phone: download 1, 3 or 5 years of NSE history, update or delete"
    override val triggers = listOf(Regex("""(?i)^((download|get|update) (the )?(market )?(data|history)|manage (models|data|downloads)|what('s| is) (downloaded|on (my|the) phone))\??$"""))
    override suspend fun run(arg: String, f: Fetch): String {
        val hub = f.artifacts ?: return Card.error("Model and data management is only available on the phone.")
        val a = arg.trim().lowercase()
        // deleting is never one tap: a bare "delete" only asks; "delete confirm" deletes
        if (a == "delete" || a == "knowledge delete") {
            val what = if (a == "delete") "your market history" else "the knowledge bank"
            val item = hub.items().firstOrNull { it.id == (if (a == "delete") "market" else "knowledge") }
            return Card("Delete $what?", "saffron")
                .headline("This removes $what from the phone", (item?.bytes?.takeIf { it > 0 }?.let { "${it / 1_000_000} MB · " } ?: "") + "you can download it again, but it takes time.")
                .rows(null, listOf(Card.Row("Yes, delete it", "Can't be undone", "", "down", action = "/data $a confirm"), Card.Row("Keep it", "Nothing changes", "", "", action = "/data")))
                .sources("This phone").json("Asked to confirm before deleting $what.")
        }
        val said = when {
            a.isEmpty() -> null
            a == "delete confirm" -> hub.market("delete")
            a == "knowledge delete confirm" -> hub.knowledge("delete")
            a.startsWith("knowledge", true) -> hub.knowledge(a.removePrefix("knowledge").trim())
            else -> hub.market(a)
        }
        val items = hub.items()
        val c = Card("Models & data on this phone", "blue")
            .headline(said ?: "${items.count { it.state is Artifacts.State.Ready }} of ${items.size} ready", "Everything here is downloaded or built by this phone and stays on it. Nothing is redistributed.")
        c.rows(null, items.map { Card.Row(it.name, Artifacts.describe(it.state) + (if (it.bytes >= 1_000_000) " · ${it.bytes / 1_000_000} MB" else if (it.bytes > 0) " · ${it.bytes / 1000} KB" else "") +
            (if (it.neededBy.isNotEmpty()) " · needed by ${it.neededBy.take(4).joinToString(" ")}" else "") + "\n" + it.detail, Artifacts.short(it.state),
            when (it.state) { is Artifacts.State.Ready -> "up"; is Artifacts.State.Failed -> "down"; is Artifacts.State.Stale -> "warn"; else -> "" }) })
        c.note("Market history comes straight from NSE's public archive (nsearchives.nseindia.com) to this phone. About 25 MB a year of history. Tap /data again to see progress.")
        return c.sources("This phone").pills(*items.flatMap { it.actions }.distinct().take(5).toTypedArray()).json(said ?: "${items.count { it.state is Artifacts.State.Ready }} of ${items.size} ready.")
    }
}
