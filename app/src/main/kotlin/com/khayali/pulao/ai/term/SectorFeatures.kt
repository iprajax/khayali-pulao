package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.data.Market
import com.khayali.pulao.ai.data.MarketPack
import com.khayali.pulao.ai.data.Panel
import com.khayali.pulao.ai.data.Sectors
import com.khayali.pulao.ai.data.Sectors.Period
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Shared wording and helpers for the sector commands. */
object Sec {
    const val NEED = "Sector analysis uses NSE's official index history on your phone. Type /data update to fetch it (about 15 KB a trading day, from NSE's archive)."
    const val METHOD = "Index levels are NSE's official closes from its daily index file (nsearchives.nseindia.com), downloaded by this phone. 1D compares the last two closes; " +
        "1W/1M/3M/6M/1Y/3Y/5Y compare the latest close with the close on (or just before) the same date that long ago; YTD starts from last year's final close; " +
        "3Y and 5Y also show CAGR = (1 + change)^(1/years) − 1. Members come from NSE Indices' published lists (niftyindices.com), refreshed monthly; " +
        "each member's move uses your NSE price history, so splits and bonuses are already adjusted."
    fun p(v: Double?, d: Int = 1) = v?.let { N.pct(it * 100, d) } ?: "—"
    private val DF = DateTimeFormatter.ofPattern("d MMM yyyy")
    fun d(x: LocalDate): String = x.format(DF)

    /** "1w", "ytd", "2025-01-01 to 2025-06-30" → the period (or range) and its label. */
    data class Window(val period: Period?, val range: Pair<LocalDate, LocalDate?>?) {
        fun label(asOf: LocalDate) = period?.label ?: range!!.let { (a, b) -> "${d(a)} – ${d(b ?: asOf)}" }
        fun from(asOf: LocalDate) = period?.start(asOf) ?: range!!.first
        fun to(asOf: LocalDate) = range?.second ?: asOf
    }
    fun window(arg: String, default: Period): Window {
        Sectors.range(arg)?.let { return Window(null, it) }
        val p = arg.split(Regex("""\s+""")).firstNotNullOfOrNull { Sectors.period(it) }
        return Window(p ?: default, null)
    }
    fun change(row: Sectors.Row, w: Window) = if (w.period != null) row.changes[w.period] else row.range
}

/** /sectorboard — every NSE sector index: 1D to 5Y, CAGR, valuations, sorted by the period you pick; each row opens the sector. */
object SectorBoardFeature : Feature {
    override val id = "sectorboard"; override val usage = "/sectorboard 1w"; override val group = Group.MARKET; override val needsWeb = false
    override val requires = "market"
    override val help = "All sectors: 1D to 5Y returns, CAGR and valuations from official NSE indices; tap one to open it"
    override val method = Sec.METHOD + " Rows are sorted by the period you choose; the Nifty 50 is the benchmark."
    override val triggers = listOf(Regex("""(?i)^((all |every )?sectors? (analysis|board|returns|cagr|performance (this|over the last) (week|month|year))|all sectors|sector wise (returns|performance)|compare (all )?sectors)\??$"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val book = f.sectors() ?: return Card.error(Sec.NEED, "/data update")
        val w = Sec.window(arg, Period.D1)
        return build(book, w)
    }

    fun build(book: MarketPack.SectorBook, w: Sec.Window): String {
        val rows = Sectors.board(book.series, book.meta, w.range).sortedByDescending { Sec.change(it, w) ?: Double.NEGATIVE_INFINITY }
        if (rows.isEmpty()) return Card.error(Sec.NEED, "/data update")
        val asOf = rows.maxOf { it.asOf }
        val nifty = book.series[Sectors.BENCHMARK]
        val niftyCh = nifty?.let { if (w.period != null) it.change(w.period, asOf) else it.change(w.from(asOf), w.to(asOf)) }
        val label = w.label(asOf)
        val known = rows.filter { Sec.change(it, w) != null }
        if (known.isEmpty()) return Card.error("Your index history doesn't reach back ${w.label(asOf)} yet. A longer download fixes that.", "/data 5y", "/sectorboard 1y")
        val best = known.first(); val worst = known.last()
        val up = rows.count { (Sec.change(it, w) ?: 0.0) > 0 }
        val c = Card("Sectors · official NSE indices · ${Sec.d(asOf)}", "blue")
            .headline("${best.sector.short} leads, ${worst.sector.short} lags · $label", "$up of ${rows.size} sectors up · Nifty 50 ${Sec.p(niftyCh)} over the same $label")
            .bars("$label · every sector", rows.map { Card.Bar(it.sector.short, (Sec.change(it, w) ?: 0.0) * 100) })
            .rows("Tap a sector for its stocks · 1D · 1W · 1M · 1Y · 5Y CAGR", rows.map { r ->
                Card.Row(r.sector.short,
                    "1D ${Sec.p(r.changes[Period.D1], 2)} · 1W ${Sec.p(r.changes[Period.W1])} · 1M ${Sec.p(r.changes[Period.M1])} · 1Y ${Sec.p(r.changes[Period.Y1])} · 5Y CAGR ${Sec.p(r.cagr5)}" +
                        (r.pe?.let { " · P/E ${"%.1f".format(it)}" } ?: ""),
                    Sec.p(Sec.change(r, w), 2), N.tone(Sec.change(r, w) ?: 0.0), action = "/sector ${r.sector.short}")
            })
            .note("Official index closes from NSE's daily index file, on your phone. 3Y and 5Y also show CAGR in /sector. Past moves don't promise future ones.")
            .sources("NSE (your copy)").put("asOf", asOf.toString())
            .put("span", nifty?.points?.firstOrNull()?.let { "${it.first} to $asOf · ${nifty.points.size} trading days of index history" })
        val periods = listOf("1d", "1w", "1m", "1y", "5y").filter { Sectors.period(it) != w.period }.take(3).map { "/sectorboard $it" }
        return c.pills(*(listOf("/sector ${best.sector.short}") + periods).toTypedArray())
            .json("Over $label (to ${Sec.d(asOf)}): ${best.sector.short} led at ${Sec.p(Sec.change(best, w))} and ${worst.sector.short} lagged at ${Sec.p(Sec.change(worst, w))}; " +
                "$up of ${rows.size} sectors rose; the Nifty 50 moved ${Sec.p(niftyCh)}. Source: NSE's official index closes on this phone.")
    }
}

/** /sector BANK [period] — one sector: every period and CAGR, valuation, chart vs Nifty 50, and its gainers and losers. */
object SectorFeature : Feature {
    override val id = "sector"; override val usage = "/sector Bank 1m"; override val group = Group.MARKET; override val needsWeb = false
    override val requires = "market"
    override val help = "One sector: 1D to 5Y and CAGR, valuation, chart vs Nifty 50, and its gainers and losers"
    override val method = Sec.METHOD + " Gainers and losers rank the sector's members by their move over the chosen period; breadth counts members up and down."

    override suspend fun run(arg: String, f: Fetch): String {
        val book = f.sectors() ?: return Card.error(Sec.NEED, "/data update")
        val words = arg.replace(Regex("""\d{4}-\d{2}-\d{2}|\b(1d|1w|5d|1m|3m|6m|ytd|1y|3y|5y|from|to|day|week|month|year)\b""", RegexOption.IGNORE_CASE), " ").trim()
        if (words.isBlank()) return SectorBoardFeature.build(book, Sec.window(arg, Period.D1))      // no sector named: show them all
        val s = Sectors.find(words) ?: return Card.error("Which sector? Try ${Sectors.ALL.take(6).joinToString(", ") { it.short }}…", "/sectorboard")
        return build(book, f.market(1300), s, Sec.window(arg, Period.M1), f.holdings.map { it.name.uppercase() }.toSet())
    }

    fun build(book: MarketPack.SectorBook, p: Panel?, s: Sectors.Sector, w: Sec.Window, mine: Set<String> = emptySet()): String {
        val ser = book.series[s.index] ?: return Card.error("No history for ${s.index} yet.", "/data update")
        val (asOf, close) = ser.last!!
        val ch = if (w.period != null) ser.change(w.period, asOf) else ser.change(w.from(asOf), w.to(asOf))
        val nifty = book.series[Sectors.BENCHMARK]
        val niftyCh = nifty?.let { if (w.period != null) it.change(w.period, asOf) else it.change(w.from(asOf), w.to(asOf)) }
        val m = book.meta[s.index]
        val label = w.label(asOf)
        val c = Card("${s.index} · official index · ${Sec.d(asOf)}", if ((ch ?: 0.0) >= 0) "green" else "saffron")
            .headline("${Sec.p(ch, 2)} over $label", "Nifty 50 ${Sec.p(niftyCh, 2)} · ${if ((ch ?: 0.0) >= (niftyCh ?: 0.0)) "▲ beat" else "▼ lagged"} the market by ${"%.1f".format(kotlin.math.abs(((ch ?: 0.0) - (niftyCh ?: 0.0)) * 100))} pts · level ${"%,.2f".format(close)}",
                if ((ch ?: 0.0) >= 0) "up" else "down")
            .kpis(listOf(Period.D1, Period.W1, Period.M1, Period.M3, Period.M6, Period.YTD, Period.Y1).map { per -> ser.change(per, asOf).let { v -> Card.Kpi(per.label, Sec.p(v, 2), v?.times(100)) } } +
                listOf(Card.Kpi("3Y CAGR", Sec.p(ser.cagr(Period.Y3, asOf)), ser.cagr(Period.Y3, asOf)?.times(100), "total ${Sec.p(ser.change(Period.Y3, asOf), 0)}"),
                    Card.Kpi("5Y CAGR", Sec.p(ser.cagr(Period.Y5, asOf)), ser.cagr(Period.Y5, asOf)?.times(100), "total ${Sec.p(ser.change(Period.Y5, asOf), 0)}"),
                    Card.Kpi("P/E · P/B", listOfNotNull(m?.first?.let { "%.1f".format(it) }, m?.second?.let { "%.1f".format(it) }).joinToString(" · ").ifBlank { "—" }, null, m?.third?.let { "div yield ${"%.2f".format(it)}%" } ?: "")))
        // one year, rebased to 100, against the Nifty 50
        val year = ser.points.filter { it.first >= asOf.minusYears(1) }
        if (year.size > 20) {
            val b0 = year.first().second; val n0 = nifty?.at(year.first().first)?.second
            c.viz("multiline", 200, mapOf("t" to year.map { it.first.toString() }, "base" to 100, "yLabel" to "Value of ₹100", "xLabel" to "Date",
                "series" to listOfNotNull(mapOf("name" to s.short, "color" to "blue", "v" to year.map { it.second / b0 * 100 }),
                    n0?.let { n -> mapOf("name" to "Nifty 50", "color" to "muted", "v" to year.map { (nifty.at(it.first)?.second ?: n) / n * 100 }) })),
                "${s.short} vs the Nifty 50 over a year, both rebased to 100.")
        }
        // members: who moved the sector
        val members = book.members[s.index].orEmpty()
        if (p != null && members.isNotEmpty()) {
            val from = w.from(asOf); val to = w.to(asOf)
            val moves = members.mapNotNull { sym -> p.of(sym)?.let { i ->
                val v = if (w.period == Period.D1) Market.change(p, i, Market.dayAt(p, to), 1) else Market.changeBetween(p, i, from, to)
                v?.let { Triple(sym, i, it) } } }.sortedByDescending { it.third }
            if (moves.isNotEmpty()) {
                val up = moves.count { it.third > 0 }; val down = moves.count { it.third < 0 }
                c.breadth("${s.short} members · $label", up, down, moves.size - up - down)
                fun mover(t: Triple<String, Int, Double>): Card.Mover {
                    val d = Market.dayAt(p, to)
                    val spark = (maxOf(0, d - 29)..d).map { p.adj[t.second][it] }.filter { !it.isNaN() }.map { it.toDouble() }
                    return Card.Mover(t.first, listOfNotNull(Market.change(p, t.second, d, 1)?.let { "1D ${Sec.p(it)}" }, Market.change(p, t.second, d, 252)?.let { "1Y ${Sec.p(it)}" }).joinToString(" · "),
                        p.adj[t.second].getOrNull(d)?.toDouble(), t.third * 100, spark, spark.firstOrNull(), t.first in mine)
                }
                c.movers("Gainers · $label", moves.take(5).filter { it.third > 0 }.map(::mover))
                c.movers("Losers · $label", moves.takeLast(5).reversed().filter { it.third < 0 }.map(::mover))
                val missing = members.size - moves.size
                if (missing > 0) c.note("$missing member${if (missing > 1) "s" else ""} not in your price history for this span (new listings or thinly traded).")
            }
        } else if (members.isEmpty()) c.note("The member list arrives with the next update (/data update).")
        val other = listOf("1d", "1w", "1m", "1y").filter { Sectors.period(it) != w.period }.take(3).map { "/sector ${s.short} $it" }
        return c.note("Official index closes from NSE; members from NSE Indices; member moves from your NSE price history. Not a recommendation.")
            .sources("NSE (your copy)").put("asOf", asOf.toString())
            .put("span", "${ser.points.first().first} to $asOf · ${ser.points.size} trading days of ${s.index}")
            .pills(*(other + "/sectorboard").toTypedArray())
            .json("${s.index} ${Sec.p(ch, 2)} over $label to ${Sec.d(asOf)} (Nifty 50 ${Sec.p(niftyCh, 2)}); 1Y ${Sec.p(ser.change(Period.Y1, asOf))}, 5Y CAGR ${Sec.p(ser.cagr(Period.Y5, asOf))}" +
                (m?.first?.let { ", P/E ${"%.1f".format(it)}" } ?: "") + ". Source: NSE's official index closes on this phone.")
    }
}
