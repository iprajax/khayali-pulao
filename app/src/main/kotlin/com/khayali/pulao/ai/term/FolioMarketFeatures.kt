package com.khayali.pulao.ai.term

import com.khayali.pulao.Stock
import com.khayali.pulao.ai.term.J.a
import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.o
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.min

// Portfolio features that also need public price history. Each asks the quote feed for one symbol at a
// time (Folio.charts); quantities and amounts stay on the phone and are combined with the prices here.

/** /risk — portfolio volatility, beta against the Nifty 50, and 1-day 95% value-at-risk in rupees. */
object FolioRiskFeature : Feature {
    override val id = "risk"
    override val usage = "/risk"
    override val help = "Portfolio risk: volatility, beta vs Nifty 50 and 1-day 95% value-at-risk in rupees"
    override val group = Group.PORTFOLIO
    override val triggers = listOf(Regex("""(?i)\b(portfolio (risk|volatility|beta)|value[ -]at[ -]risk|how risky is my portfolio)\b"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val pos = Folio.positions(f.holdings)
        if (pos.isEmpty()) return Card.error(Folio.CONNECT)
        return build(f.holdings, Folio.charts(f, pos.take(Folio.MAX_PRICED).map { it.sym }), f.chart(Folio.INDEX, "1y", "1d"))
    }

    /** Pure: holdings + chart replies by symbol + Nifty reply → card. */
    fun build(holdings: List<Stock.Holding>, charts: Map<String, String>, indexJson: String): String {
        if (Folio.positions(holdings).isEmpty()) return Card.error(Folio.CONNECT)
        val m = Folio.market(holdings, charts, indexJson) ?: return Card.error(Folio.NO_PRICES)
        val rp = m.portfolio
        val vol = Quant.volatility(rp); val daily = Quant.stdev(rp)
        val beta = m.indexReturns?.let { Quant.beta(rp, it) }
        val varPct = Folio.Z95 * daily * 100; val varInr = varPct / 100 * m.value
        val sorted = rp.sorted()
        val hist = -sorted[min(sorted.size - 1, floor(0.05 * sorted.size).toInt())] * 100      // the loss exceeded on 1 day in 20
        val worst = -sorted.first() * 100
        val worstDay = m.dates[rp.indexOf(sorted.first())]
        val indexVol = m.indexReturns?.let { Quant.volatility(it) }
        val span = Folio.span(m.days)
        val points = m.lines.map { l -> mapOf("label" to l.pos.sym, "x" to Folio.r2(m.beta(l) ?: 0.0), "y" to Folio.r2(Quant.volatility(m.returns(l))), "size" to Folio.r2(m.weight(l) * 100),
            "color" to "blue", "note" to "${Folio.p(m.weight(l) * 100)} of the priced portfolio") } +
            listOfNotNull(beta?.let { mapOf("label" to "Portfolio", "x" to Folio.r2(it), "y" to Folio.r2(vol), "color" to "saffron", "note" to "all priced holdings together") },
                indexVol?.let { mapOf("label" to Folio.INDEX_NAME, "x" to 1.0, "y" to Folio.r2(it), "color" to "violet", "note" to "the market, by definition beta 1") })
        val card = Card("Your portfolio · risk · $span of daily prices", "saffron")
            .headline(N.inr(varInr) + " value-at-risk", "1 day, 95% · ${Folio.p(varPct, 2)} of ${Folio.short(m.value)} · volatility ${Folio.p(vol)} a year" + (beta?.let { " · beta ${Folio.f2(it)}" } ?: ""))
        if (beta != null) card.viz("scatter", 280, mapOf("xLabel" to "Beta vs ${Folio.INDEX_NAME}", "yLabel" to "Volatility (% a year)", "xShort" to "beta", "yShort" to "volatility", "xUnit" to "", "yUnit" to "%", "xZero" to false,
            "sizeLabel" to "Bubble = weight", "legend" to listOf(listOf("Holding", "blue"), listOf("Portfolio", "saffron"), listOf(Folio.INDEX_NAME, "violet")), "points" to points),
            "Across: beta, how strongly each holding moved with the ${Folio.INDEX_NAME}. Up: volatility, % a year. Bubble size: weight. Tap a bubble.")
        card.stats(*listOfNotNull("Volatility (a year)" to Folio.p(vol), beta?.let { "Beta vs ${Folio.INDEX_NAME}" to Folio.f2(it) },
            "VaR 95%, 1 day" to "${N.inr(varInr)} · ${Folio.p(varPct, 2)}", "Worst 1 day in 20 (actual)" to "${N.inr(hist / 100 * m.value)} · ${Folio.p(hist, 2)}",
            "Worst day in the period" to "${N.pct(-worst, 2)} on $worstDay", indexVol?.let { "${Folio.INDEX_NAME} volatility" to Folio.p(it) }).toTypedArray())
        card.rows("By holding", m.lines.take(8).map { l -> Card.Row(l.pos.sym, (m.beta(l)?.let { "beta ${Folio.f2(it)} · " } ?: "") + "volatility ${Folio.p(Quant.volatility(m.returns(l)))}", Folio.p(m.weight(l) * 100), "") })
        val missing = if (m.missing.isEmpty()) "" else " Not priced and left out: ${m.missing.take(6).joinToString(", ")}${if (m.missing.size > 6) " and ${m.missing.size - 6} more" else ""}."
        return card.note("Value-at-risk here is ${Folio.Z95} × the daily volatility of today's holdings over $span: on about 19 trading days in 20 the one-day loss stayed within it, if returns are roughly normal. " +
            "It is an estimate from past prices, not a ceiling on losses, and says nothing about what happens next.$missing" + if (beta == null) " ${Folio.INDEX_NAME} prices were unavailable, so beta is not shown." else "")
            .sources("Yahoo Finance (daily prices)", "Your Kite holdings (on this phone)").pills("/stress", "/correlation", "/riskreturn", "/xray")
            .put("days", m.days)
            .json("Your portfolio (${m.coverage()}, $span of daily prices): volatility ${Folio.p(vol)} a year" + (beta?.let { ", beta ${Folio.f2(it)} vs ${Folio.INDEX_NAME}" } ?: "") +
                ", 1-day 95% value-at-risk ${N.inr(varInr)} (${Folio.p(varPct, 2)} of ${N.inr(m.value)}). An estimate from past prices, not a limit on losses. Source: Yahoo Finance prices; your Kite holdings.")
    }
}

/** /correlation — how the largest holdings move together: a matrix of correlations of daily returns. */
object FolioCorrelationFeature : Feature {
    override val id = "correlation"
    override val usage = "/correlation"
    override val help = "How your holdings move together: correlation matrix of daily returns (8 largest holdings)"
    override val group = Group.PORTFOLIO
    override val triggers = listOf(Regex("""(?i)\b(correlation (matrix|of my|between my)|how correlated)\b"""))
    const val MAX = 8

    override suspend fun run(arg: String, f: Fetch): String {
        val pos = Folio.positions(f.holdings)
        if (pos.isEmpty()) return Card.error(Folio.CONNECT)
        if (pos.size < 2) return Card.error("Correlation needs at least two holdings; you have one (${pos.first().sym}).")
        return build(f.holdings, Folio.charts(f, pos.take(MAX).map { it.sym }))
    }

    /** Pure: holdings + chart replies by symbol → card. */
    fun build(holdings: List<Stock.Holding>, charts: Map<String, String>): String {
        val pos = Folio.positions(holdings)
        if (pos.isEmpty()) return Card.error(Folio.CONNECT)
        if (pos.size < 2) return Card.error("Correlation needs at least two holdings; you have one (${pos.first().sym}).")
        val m = Folio.market(holdings, charts, null, need = 2, limit = MAX) ?: return Card.error(Folio.NO_PRICES)
        val labels = m.lines.map { it.pos.sym }; val r = m.lines.map { m.returns(it) }
        val cells = r.indices.map { i -> r.indices.map { j -> if (i == j) 1.0 else Folio.r2(Quant.correlation(r[i], r[j])) } }
        data class Pair2(val a: String, val b: String, val c: Double)
        val pairs = r.indices.flatMap { i -> (i + 1 until r.size).map { j -> Pair2(labels[i], labels[j], Quant.correlation(r[i], r[j])) } }.sortedByDescending { it.c }
        val avg = pairs.map { it.c }.average(); val hi = pairs.first(); val lo = pairs.last()
        val span = Folio.span(m.days)
        val rows = (pairs.take(3) + pairs.takeLast(3)).distinct().map { Card.Row("${it.a} & ${it.b}", describe(it.c), Folio.f2(it.c), "") }
        val left = pos.size - m.lines.size
        return Card("Your portfolio · correlation · ${m.lines.size} largest holdings", "violet")
            .headline("${Folio.f2(avg)} average correlation", "most alike ${hi.a} & ${hi.b} ${Folio.f2(hi.c)} · least alike ${lo.a} & ${lo.b} ${Folio.f2(lo.c)}")
            .viz("heatgrid", (98 + labels.size * 30).coerceIn(160, 340), mapOf("rows" to labels, "cols" to labels, "cells" to cells, "min" to -1, "max" to 1, "unit" to "correlation", "xLabel" to "Holding", "yLabel" to "Holding"),
                "Rows and columns: your holdings. Each cell: correlation of their daily returns over $span, from −1 (opposite) through 0 (unrelated) to +1 (together). Tap a cell.")
            .rows("Pairs", rows)
            .note("Correlation of daily returns over $span (${m.days} days all of them traded). It describes the past and shifts over time; in sharp market falls, stocks tend to move together more than usual." +
                if (left > 0) " $left smaller or unpriced holdings are not in the matrix." else "")
            .sources("Yahoo Finance (daily prices)", "Your Kite holdings (on this phone)").pills("/risk", "/riskreturn", "/xray", "/stress")
            .put("days", m.days)
            .json("Daily returns of your ${m.lines.size} largest holdings over $span: average pairwise correlation ${Folio.f2(avg)}. " +
                "Most alike: ${hi.a} and ${hi.b} at ${Folio.f2(hi.c)}; least alike: ${lo.a} and ${lo.b} at ${Folio.f2(lo.c)}. Source: Yahoo Finance prices; your Kite holdings.")
    }

    fun describe(c: Double) = when {
        c >= 0.7 -> "moved closely together"
        c >= 0.4 -> "often moved together"
        c >= 0.15 -> "loosely related"
        c > -0.15 -> "little relation"
        else -> "tended to move opposite ways"
    }
}

/** /riskreturn — each holding as a bubble: volatility across, return up, weight as size. */
object FolioRiskReturnFeature : Feature {
    override val id = "riskreturn"
    override val usage = "/riskreturn"
    override val help = "Risk vs return scatter: each holding's 1-year volatility, return and weight"
    override val group = Group.PORTFOLIO
    override val triggers = listOf(Regex("""(?i)\brisk[ -]?(vs\.?|versus|and|v|/)?[ -]?returns?\b"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val pos = Folio.positions(f.holdings)
        if (pos.isEmpty()) return Card.error(Folio.CONNECT)
        return build(f.holdings, Folio.charts(f, pos.take(Folio.MAX_PRICED).map { it.sym }), f.chart(Folio.INDEX, "1y", "1d"))
    }

    /** Pure: holdings + chart replies by symbol + Nifty reply → card. */
    fun build(holdings: List<Stock.Holding>, charts: Map<String, String>, indexJson: String): String {
        if (Folio.positions(holdings).isEmpty()) return Card.error(Folio.CONNECT)
        val m = Folio.market(holdings, charts, indexJson) ?: return Card.error(Folio.NO_PRICES)
        class Pt(val sym: String, val vol: Double, val ret: Double, val w: Double)
        val pts = m.lines.map { Pt(it.pos.sym, Quant.volatility(m.returns(it)), Folio.compound(m.returns(it)), m.weight(it) * 100) }
        val pVol = Quant.volatility(m.portfolio); val pRet = Folio.compound(m.portfolio)
        val iVol = m.indexReturns?.let { Quant.volatility(it) }; val iRet = m.indexReturns?.let { Folio.compound(it) }
        val span = Folio.span(m.days)
        val best = pts.maxByOrNull { it.ret }!!; val worst = pts.minByOrNull { it.ret }!!; val calm = pts.minByOrNull { it.vol }!!; val wild = pts.maxByOrNull { it.vol }!!
        val points = pts.map { mapOf("label" to it.sym, "x" to Folio.r2(it.vol), "y" to Folio.r2(it.ret), "size" to Folio.r2(it.w), "color" to (if (it.ret >= 0) "green" else "red"), "note" to "${Folio.p(it.w)} of the priced portfolio") } +
            listOfNotNull(mapOf("label" to "Portfolio", "x" to Folio.r2(pVol), "y" to Folio.r2(pRet), "color" to "saffron", "note" to "today's holdings held throughout"),
                if (iVol != null && iRet != null) mapOf("label" to Folio.INDEX_NAME, "x" to Folio.r2(iVol), "y" to Folio.r2(iRet), "color" to "violet", "note" to "the index, for scale") else null)
        return Card("Your portfolio · risk vs return · $span", if (pRet >= 0) "green" else "red")
            .headline(N.pct(pRet) + " at " + Folio.p(pVol) + " volatility", "today's holdings over $span" + (if (iVol != null && iRet != null) " · ${Folio.INDEX_NAME} ${N.pct(iRet)} at ${Folio.p(iVol)}" else ""), N.tone(pRet))
            .viz("scatter", 300, mapOf("xLabel" to "Volatility (% a year)", "yLabel" to "Return over $span (%)", "xShort" to "volatility", "yShort" to "return", "xUnit" to "%", "yUnit" to "%", "xZero" to false,
                "sizeLabel" to "Bubble = weight", "legend" to listOfNotNull(listOf("Up", "green"), listOf("Down", "red"), listOf("Portfolio", "saffron"), iVol?.let { listOf(Folio.INDEX_NAME, "violet") }), "points" to points),
                "Across: how much each holding's price swung (volatility, % a year). Up: its price return over $span (%). Bubble size: weight. Tap a bubble.")
            .stats("Highest return" to "${best.sym} ${N.pct(best.ret)}", "Lowest return" to "${worst.sym} ${N.pct(worst.ret)}",
                "Steadiest" to "${calm.sym} ${Folio.p(calm.vol)}", "Most volatile" to "${wild.sym} ${Folio.p(wild.vol)}")
            .rows("By holding", pts.sortedByDescending { it.w }.take(8).map { Card.Row(it.sym, "volatility ${Folio.p(it.vol)} · weight ${Folio.p(it.w)}", N.pct(it.ret), N.tone(it.ret)) })
            .note("Price returns over $span of the feed's adjusted daily closes — not your own return, which depends on when you bought. Dividends are not added. " +
                "The portfolio point assumes today's holdings were held for the whole period. Past figures do not tell you what comes next." +
                if (m.missing.isEmpty()) "" else " Not priced and left out: ${m.missing.take(6).joinToString(", ")}.")
            .sources("Yahoo Finance (daily prices)", "Your Kite holdings (on this phone)").pills("/risk", "/correlation", "/stress", "/income")
            .put("days", m.days)
            .json("Over $span, today's holdings (${m.coverage()}) returned ${N.pct(pRet)} at ${Folio.p(pVol)} yearly volatility" +
                (if (iVol != null && iRet != null) "; the ${Folio.INDEX_NAME} returned ${N.pct(iRet)} at ${Folio.p(iVol)}" else "") +
                ". Highest return ${best.sym} ${N.pct(best.ret)}, lowest ${worst.sym} ${N.pct(worst.ret)}. Price returns, not your own. Source: Yahoo Finance prices; your Kite holdings.")
    }
}

/** /stress — what the portfolio would be worth if the market fell 5 / 10 / 20 / 30%, using each holding's beta. */
object FolioStressFeature : Feature {
    override val id = "stress"
    override val usage = "/stress"
    override val help = "Stress test: portfolio value if the Nifty fell 5, 10, 20 or 30%, using each holding's beta"
    override val group = Group.PORTFOLIO
    override val triggers = listOf(Regex("""(?i)\b(stress[ -]?test|if the market (falls|fell|crashes|crashed|drops|dropped))\b"""))
    val FALLS = listOf(5, 10, 20, 30)

    override suspend fun run(arg: String, f: Fetch): String {
        val pos = Folio.positions(f.holdings)
        if (pos.isEmpty()) return Card.error(Folio.CONNECT)
        return build(f.holdings, Folio.charts(f, pos.take(Folio.MAX_PRICED).map { it.sym }), f.chart(Folio.INDEX, "1y", "1d"))
    }

    /** Pure: holdings + chart replies by symbol + Nifty reply → card. Holdings without a beta are assumed to fall with the market (beta 1). */
    fun build(holdings: List<Stock.Holding>, charts: Map<String, String>, indexJson: String): String {
        val pos = Folio.positions(holdings)
        if (pos.isEmpty()) return Card.error(Folio.CONNECT)
        val m = Folio.market(holdings, charts, indexJson) ?: return Card.error(Folio.NO_PRICES)
        if (m.indexReturns == null) return Card.error("Couldn't get ${Folio.INDEX_NAME} prices, which the stress test needs to work out each holding's beta. Try again in a moment.")
        val betas = m.lines.associate { it.pos.sym to m.beta(it)!! }
        val total = pos.sumOf { it.h.current }
        /** Value after a market fall of [fall]%: each holding loses beta × fall, never more than everything, and a negative beta gains. */
        fun after(fall: Int) = pos.sumOf { it.h.current * (1 - ((betas[it.sym] ?: 1.0) * fall / 100).coerceAtMost(1.0)) }
        val beta = pos.sumOf { it.weight * (betas[it.sym] ?: 1.0) }
        val span = Folio.span(m.days)
        val ranked = m.lines.sortedByDescending { betas.getValue(it.pos.sym) }
        val high = ranked.first(); val low = ranked.last()
        val ten = after(10)
        val assumed = if (m.missing.isEmpty()) "" else " ${m.missing.size} holdings without price history (${m.missing.take(5).joinToString(", ")}) are assumed to fall exactly with the market."
        return Card("Your portfolio · stress test · betas from $span", "red")
            .headline(N.inr(ten - total), "if the ${Folio.INDEX_NAME} fell 10% · ${Folio.short(total)} → ${Folio.short(ten)} · portfolio beta ${Folio.f2(beta)}", "down")
            .viz("groupbars", 230, mapOf("unit" to "₹", "xLabel" to "${Folio.INDEX_NAME} fall", "series" to listOf("Moving with the market", "Using your betas"),
                "items" to FALLS.map { mapOf("label" to "−$it%", "values" to listOf(Folio.r2(total * (1 - it / 100.0)), Folio.r2(after(it)))) }),
                "Across: how far the ${Folio.INDEX_NAME} falls. Up: what the portfolio would be worth (₹) — if it moved exactly with the market, and using each holding's own beta. Tap a bar.")
            .rows("Estimated value", FALLS.map { val v = after(it); Card.Row("${Folio.INDEX_NAME} −$it%", "portfolio ${N.pct((v / total - 1) * 100)} · ${N.inr(v - total)}", N.inr(v), "down") })
            .stats("Value today" to N.inr(total), "Portfolio beta" to Folio.f2(beta),
                "Most sensitive" to "${high.pos.sym} · beta ${Folio.f2(betas.getValue(high.pos.sym))}", "Least sensitive" to "${low.pos.sym} · beta ${Folio.f2(betas.getValue(low.pos.sym))}")
            .note("A what-if, not a forecast. Beta is how each stock moved with the ${Folio.INDEX_NAME} on ordinary days over $span; in a real fall, individual stocks can drop far more or less than beta suggests, and betas change.$assumed")
            .sources("Yahoo Finance (daily prices)", "Your Kite holdings (on this phone)").pills("/risk", "/correlation", "/xray", "/move 10")
            .put("beta", Folio.r2(beta))
            .json("Stress test with betas from $span (portfolio beta ${Folio.f2(beta)}): if the ${Folio.INDEX_NAME} fell 10%, your ${N.inr(total)} portfolio would be worth about ${N.inr(ten)} (${N.inr(ten - total)}); " +
                "at −20% about ${N.inr(after(20))}, at −30% about ${N.inr(after(30))}. A what-if from past betas, not a forecast. Source: Yahoo Finance prices; your Kite holdings.")
    }
}

/** /income — dividends per share over the last 12 months × today's quantity = estimated yearly dividend income and yield. */
object FolioIncomeFeature : Feature {
    override val id = "income"
    override val usage = "/income"
    override val help = "Dividend income: last 12 months' dividends per share × your quantity, with yield"
    override val group = Group.PORTFOLIO
    override val triggers = listOf(Regex("""(?i)\b(dividend income|my dividends?|dividends? (from|of|on) my (portfolio|holdings|stocks))\b"""))
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    override suspend fun run(arg: String, f: Fetch): String {
        val pos = Folio.positions(f.holdings)
        if (pos.isEmpty()) return Card.error(Folio.CONNECT)
        return build(f.holdings, Folio.charts(f, pos.take(Folio.MAX_PRICED).map { it.sym }), f.today)
    }

    /**
     * Dividends per share with ex-dates in the 12 months up to [today], from one chart reply (chart.result[0].events.dividends,
     * an object keyed by timestamp: { amount, date }). Null when the reply is not a chart; an empty list when it has no dividends.
     */
    fun dividends(json: String, today: LocalDate): List<Pair<LocalDate, Double>>? {
        val res = J.obj(json)?.o("chart")?.a("result")?.firstOrNull() ?: return null
        val divs = res.o("events")?.o("dividends") ?: return emptyList()
        return divs.entrySet().mapNotNull { (_, v) ->
            val e = v.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val amount = e.d("amount") ?: return@mapNotNull null; val ts = e.d("date") ?: return@mapNotNull null
            val day = Instant.ofEpochSecond(ts.toLong()).atZone(IST).toLocalDate()
            if (amount > 0 && day > today.minusYears(1) && !day.isAfter(today)) day to amount else null
        }.sortedBy { it.first }
    }

    /** Pure: holdings + chart replies by symbol → card. */
    fun build(holdings: List<Stock.Holding>, charts: Map<String, String>, today: LocalDate): String {
        val pos = Folio.positions(holdings)
        if (pos.isEmpty()) return Card.error(Folio.CONNECT)
        class Line(val p: Folio.Pos, val divs: List<Pair<LocalDate, Double>>) {
            val perShare = divs.sumOf { it.second }; val income = perShare * p.h.qty
            val yieldNow = if (p.h.ltp > 0) perShare / p.h.ltp * 100 else 0.0
        }
        val lines = mutableListOf<Line>(); val missing = mutableListOf<String>()
        pos.forEachIndexed { i, p -> val d = if (i < Folio.MAX_PRICED) charts[p.sym]?.let { dividends(it, today) } else null; if (d != null) lines += Line(p, d) else missing += p.sym }
        if (lines.isEmpty()) return Card.error("Couldn't get dividend history for your holdings right now. Check the connection and try again.")
        val income = lines.sumOf { it.income }; val value = lines.sumOf { it.p.h.current }; val cost = lines.sumOf { it.p.h.invested }
        val yieldNow = if (value > 0) income / value * 100 else 0.0; val yieldCost = if (cost > 0) income / cost * 100 else 0.0
        val payers = lines.filter { it.income > 0 }.sortedByDescending { it.income }
        val coverage = "${lines.size} of ${lines.size + missing.size} holdings checked"
        val left = if (missing.isEmpty()) "" else " No dividend data for: ${missing.take(6).joinToString(", ")}${if (missing.size > 6) " and ${missing.size - 6} more" else ""}."
        val card = Card("Your portfolio · dividend income · last 12 months", "green")
            .headline(N.inr(income) + " a year", "estimated from the last 12 months · yield ${Folio.p(yieldNow, 2)} on today's value · ${payers.size} of ${lines.size} holdings paid")
        if (payers.isNotEmpty()) card.viz("bars", (30 + min(8, payers.size) * 30).coerceAtLeast(120), mapOf("items" to payers.take(8).map { mapOf("label" to it.p.sym, "value" to Folio.r2(it.income), "color" to "#67AD5B") }),
            "Down: your holdings that paid a dividend. Across: dividends over the last 12 months × the quantity you hold today (₹).")
        card.stats("Estimated yearly income" to N.inr(income), "Yield on today's value" to Folio.p(yieldNow, 2), "Yield on your cost" to Folio.p(yieldCost, 2),
            "Largest payer" to (payers.firstOrNull()?.let { "${it.p.sym} ${N.inr(it.income)}" } ?: "none"))
        card.rows("By holding", lines.sortedByDescending { it.income }.take(10).map {
            Card.Row(it.p.sym, if (it.divs.isEmpty()) "no dividend in the last 12 months" else "₹${N.num(it.perShare)} a share · ${it.divs.size} ${if (it.divs.size == 1) "payout" else "payouts"} · yield ${Folio.p(it.yieldNow, 2)}",
                N.inr(it.income), if (it.income > 0) "up" else "") })
        return card.note("An estimate, not a record of what you received: it multiplies dividends with ex-dates between ${today.minusYears(1).plusDays(1)} and $today by the quantity you hold today. " +
            "You may not have held every share on each ex-date, companies change their dividends, and the figure is before tax.$left")
            .sources("Yahoo Finance (dividend history)", "Your Kite holdings (on this phone)").pills("/pnlflow", "/calendar", "/xray", "/riskreturn")
            .json("Estimated dividend income from your holdings ($coverage): ${N.inr(income)} a year, a yield of ${Folio.p(yieldNow, 2)} on today's value of ${N.inr(value)}; ${payers.size} of ${lines.size} paid in the last 12 months" +
                (payers.firstOrNull()?.let { ", the largest ${it.p.sym} at ${N.inr(it.income)}" } ?: "") + ". Based on today's quantities, before tax. Source: Yahoo Finance dividend history; your Kite holdings.")
    }
}
