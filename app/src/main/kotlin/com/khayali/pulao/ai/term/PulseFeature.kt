package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.data.Market
import com.khayali.pulao.ai.data.Mood
import com.khayali.pulao.ai.data.Sectors
import java.time.Instant

/**
 * /pulse — the whole market on one screen: mood gauge, trend and breadth, every sector today, hidden patterns,
 * and (with web on) the headlines that matter and the regulator's latest. Works offline from the phone's own data.
 */
object PulseFeature : Feature {
    override val id = "pulse"; override val usage = "/pulse"; override val group = Group.MARKET; override val needsWeb = false
    override val requires = "market"
    override val help = "Market pulse on one screen: mood, breadth, sectors, hidden patterns and the headlines that matter"
    override val method = "Mood, breadth, sectors and hidden patterns come from your own copy of NSE's daily files (see /mood, /regime, /sectorboard, /anomalies for each method). " +
        "With web on, headlines are read from public news feeds and the regulator's feed, folded and tone-tagged on the phone (see /news, /sebi)."
    override val triggers = listOf(Regex("""(?i)^((market )?pulse|how('s| is) the market( doing)?( today)?|market (today|snapshot|overview|at a glance)|what('s| is) happening in the market( today)?)\??$"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val p = f.market(1300) ?: return Card.error("The pulse uses your NSE history. Type /data 1y to download it.", "/data 1y")
        val last = p.days - 1
        val r = Market.regime(p, last)
        val mood = Mood.compute(p, 6).lastOrNull()
        val book = f.sectors()
        val secs = book?.let { Sectors.board(it.series, it.meta) }.orEmpty().filter { it.changes[Sectors.Period.D1] != null }.sortedByDescending { it.changes[Sectors.Period.D1] }
        val nifty = book?.series?.get(Sectors.BENCHMARK)
        val an = Market.anomalies(p, last, 5)
        val mine = f.holdings.map { it.name.uppercase() }.toSet()
        val c = Card("Market pulse · ${p.dates[last]}", if ((r.market1d) >= 0) "green" else "saffron")
            .headline("${r.label} · ${mood?.zone ?: "—"}", "Nifty 50 ${Sec.p(nifty?.change(Sectors.Period.D1), 2)} · ${r.advances} up, ${r.declines} down · ${r.newHighs} new highs, ${r.newLows} lows",
                if (r.market1d >= 0) "up" else "down")
        mood?.let { c.gauge("Mood", it.score, it.zone, "breadth ${it.parts[0].score.toInt()} · momentum ${it.parts[2].score.toInt()} · calm ${it.parts[4].score.toInt()}") }
        c.kpis(listOfNotNull(
            nifty?.let { Card.Kpi("NIFTY 50", "%,.0f".format(it.last!!.second), (it.change(Sectors.Period.D1) ?: 0.0) * 100) },
            Card.Kpi("1 WEEK", Sec.p(nifty?.change(Sectors.Period.W1)), (nifty?.change(Sectors.Period.W1) ?: 0.0) * 100),
            Card.Kpi("ABOVE 50-DAY", "${r.above50.toInt()}%", null, "of ${r.stocks} liquid"),
            Card.Kpi("VOLATILITY", r.volNow?.let { "%.0f%%".format(it * 100) } ?: "—", null, r.volPercentile?.let { "pct ${it.toInt()}" } ?: "")))
        c.breadth("Advancers and decliners", r.advances, r.declines, (r.stocks - r.advances - r.declines).coerceAtLeast(0))
        if (secs.isNotEmpty()) c.bars("Sectors today · official NSE indices", secs.map { Card.Bar(it.sector.short, (it.changes[Sectors.Period.D1] ?: 0.0) * 100) })
        if (an.isNotEmpty()) c.movers("Hidden patterns", an.map { Card.Mover(it.symbol, "${it.kind} · ${it.detail}", null, it.move * 100, emptyList(), null, it.symbol in mine) })
        var news = 0
        if (f.webOn && Feeds.NEWS.isNotEmpty()) {
            val st = runCatching { NewsRead.stories(f, 20) }.getOrDefault(emptyList())
            val pick = (st.filter { s -> s.tickers.any { it in mine } } + st.filter { it.tickers.isNotEmpty() } + st).distinct().take(5)
            if (pick.isNotEmpty()) { news = pick.size; c.rows("Headlines that matter", pick.map { NewsRead.mover(it, Instant.now(), mine) }) }
            runCatching { com.khayali.pulao.ai.data.Filings.parseSebi(f.get(Feeds.SEBI.url)) }.getOrDefault(emptyList()).take(3).takeIf { it.isNotEmpty() }?.let { n ->
                c.rows("From SEBI", n.map { Card.Row(it.title, listOfNotNull(it.date?.let(Sec::d), it.category).joinToString(" · "), "", "", action = it.link) }) }
        } else c.note("Switch Web on to add the day's headlines and the regulator's latest here.")
        val best = secs.firstOrNull(); val worst = secs.lastOrNull()
        return c.sources(*listOfNotNull("NSE (your copy)", if (news > 0) "News feeds" else null).toTypedArray())
            .put("asOf", p.dates[last].toString()).put("span", "${p.dates.first()} to ${p.dates.last()} · ${p.days} trading days")
            .pills(*listOfNotNull(best?.let { "/sector ${it.sector.short}" }, an.firstOrNull()?.let { "/why ${it.symbol}" }, "/mood", "/news", "/radar").toTypedArray())
            .json("${p.dates[last]}: ${r.label.lowercase()}, mood ${mood?.score?.toInt() ?: "—"} (${mood?.zone?.lowercase() ?: "—"}); Nifty 50 ${Sec.p(nifty?.change(Sectors.Period.D1), 2)}; " +
                "${r.advances} advancers vs ${r.declines} decliners" + (if (best != null && worst != null) "; ${best.sector.short} led (${Sec.p(best.changes[Sectors.Period.D1])}) and ${worst.sector.short} lagged (${Sec.p(worst.changes[Sectors.Period.D1])})" else "") +
                (an.firstOrNull()?.let { "; most unusual: ${it.symbol} (${it.kind.lowercase()})" } ?: "") + ". Source: NSE daily data on this phone.")
    }
}
