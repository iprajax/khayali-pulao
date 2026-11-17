package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.TechFeed.Sym
import com.khayali.pulao.ai.term.TechMath.r2
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
private fun span(a: LocalDate, b: LocalDate): String { val d = ChronoUnit.DAYS.between(a, b); return if (d == 1L) "1 day" else "$d days" }
private fun years(a: LocalDate, b: LocalDate): String { val y = ChronoUnit.DAYS.between(a, b) / 365.25; val r = Math.round(y); return if (kotlin.math.abs(y - r) < 0.08 && r >= 1) (if (r == 1L) "1 year" else "$r years") else one(y) + " years" }

/** /drawdown SYMBOL — the "underwater" chart: how far below its running peak the price has been over five years. */
object TechDrawdownFeature : Feature {
    override val id = "drawdown"
    override val usage = "/drawdown INFY"
    override val help = "Underwater chart: fall from the running peak over 5 years, the worst drawdown and its recovery time"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\b(draw ?downs?|underwater chart|fall from (its |the )?peak)\b"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val sym = TechFeed.resolve(arg, f) ?: return TechFeed.which(usage)
        return build(sym, TechFeed.chart(f, sym, "5y", "1d"))
    }

    fun build(sym: Sym, json: String): String {
        val b = TechFeed.bars(json) ?: return TechFeed.noData(sym, usage)
        val s = b.s; val dd = Quant.drawdown(s.close); val now = dd.last(); val period = years(s.dates.first(), s.dates.last())
        val w = TechMath.worstDrawdown(s.close)
            ?: return Card.error("${sym.label} has not closed below an earlier peak in the ${s.size} sessions on the quote feed, so there is no drawdown to chart.")
        val peak = s.dates[w.peak]; val trough = s.dates[w.trough]; val rec = w.recovered?.let { s.dates[it] }
        val recovery = if (rec != null) "it closed back at that peak on ${TechFeed.day(rec)}, ${span(trough, rec)} after the low"
            else "it has not yet closed back at that peak (${span(trough, s.dates.last())} since the low)"
        val below = dd.count { it < 0 } * 100.0 / dd.size
        // thin to what a phone chart can show, keeping the deepest point of every bucket
        val step = Math.ceil(dd.size / 320.0).toInt().coerceAtLeast(1)
        val keep = (dd.indices.chunked(step).map { ch -> ch.minByOrNull { dd[it] }!! } + (dd.size - 1)).distinct()
        return Card("${sym.label} · drawdown · $period", "red")
            .headline(N.pct(w.depth), "Deepest fall from a peak: ${TechFeed.day(peak)} to ${TechFeed.day(trough)} (${span(peak, trough)})", "down")
            .viz("underwater", 230, mapOf(
                "t" to keep.map { s.dates[it].toString() }, "dd" to keep.map { r2(dd[it]) },
                "marks" to mapOf("peak" to peak.toString(), "trough" to trough.toString(), "recovered" to rec?.toString()),
                "yLabel" to "Below running peak (%)", "xLabel" to "Date"),
                "Percent below the highest close so far (0 = at a new high), by date. Shaded span: the deepest fall and its recovery. Drag to read values.")
            .stats("Worst drawdown" to N.pct(w.depth), "Peak to low" to span(peak, trough), "Low to recovery" to (rec?.let { span(trough, it) } ?: "Not yet recovered"),
                "Now vs highest close" to (if (now < 0) N.pct(now) else "At the high"), "Sessions below a peak" to "${Math.round(below)}%", "Highest close" to "${sym.money(s.close.max())}")
            .note("Based on daily closes ${if (sym.index) "of the index" else "(price only, dividends not included)"}; intraday lows were deeper. Past drawdowns show how rough the ride has been, not how the next one will go. ${TechFeed.DELAY}")
            .sources(TechFeed.SOURCE).pills("/returns ${sym.label}", "/volatility ${sym.label}", "/ta ${sym.label}", "/compare ${sym.label} NIFTY 5y")
            .put("symbol", sym.label).put("worst_drawdown_pct", r2(w.depth)).put("as_of", b.asOf.toString())
            .json("${sym.label}'s deepest fall from a peak in the last $period was ${N.pct(w.depth)}, from ${TechFeed.day(peak)} to ${TechFeed.day(trough)} (${span(peak, trough)}); $recovery. " +
                (if (now < 0) "It is now ${N.pct(now)} from its highest close of the period" else "It is now at its highest close of the period") + ". Source: ${TechFeed.SOURCE}.")
    }
}

/** /returns SYMBOL — price returns over 1 week to 5 years, and CAGR, against the Nifty 50 over the same periods. */
object TechReturnsFeature : Feature {
    override val id = "returns"
    override val usage = "/returns INFY"
    override val help = "Returns over 1 week to 5 years, with CAGR, against the Nifty 50 over the same periods"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\b((price|stock|share|trailing) returns?|returns? (vs|versus|against) nifty)\b"""))

    private class Period(val key: String, val name: String, val years: Int, val back: (LocalDate) -> LocalDate)
    private val PERIODS = listOf(
        Period("1W", "1 week", 0) { it.minusWeeks(1) }, Period("1M", "1 month", 0) { it.minusMonths(1) }, Period("3M", "3 months", 0) { it.minusMonths(3) },
        Period("6M", "6 months", 0) { it.minusMonths(6) }, Period("1Y", "1 year", 1) { it.minusYears(1) }, Period("3Y", "3 years", 3) { it.minusYears(3) }, Period("5Y", "5 years", 5) { it.minusYears(5) })

    override suspend fun run(arg: String, f: Fetch): String {
        val sym = TechFeed.resolve(arg, f) ?: return TechFeed.which(usage)
        val own = TechFeed.chart(f, sym, "5y", "1d")
        return build(sym, own, if (sym == TechFeed.NIFTY) "" else f.chart(TechFeed.NIFTY.feed, "5y", "1d"))
    }

    /** Pure: the symbol's and the Nifty 50's chart JSON → card. [benchJson] may be blank (no comparison is drawn). */
    fun build(sym: Sym, json: String, benchJson: String): String {
        val b = TechFeed.bars(json) ?: return TechFeed.noData(sym, usage)
        val n = if (sym == TechFeed.NIFTY) null else TechFeed.bars(benchJson)
        fun ret(x: TechFeed.Bars, p: Period): Pair<Double, Double>? {       // (start close, % change)
            val base = TechMath.closeAt(x.s.dates, x.s.close, p.back(x.asOf), if (p.years >= 1) 10 else 0) ?: return null
            return if (base > 0) base to (x.s.last / base - 1) * 100 else null
        }
        class Line(val p: Period, val own: Double, val base: Double, val bench: Double?, val benchBase: Double?)
        val lines = PERIODS.mapNotNull { p -> val r = ret(b, p) ?: return@mapNotNull null; val m = n?.let { ret(it, p) }; Line(p, r.second, r.first, m?.second, m?.first) }
        if (lines.isEmpty()) return Card.error("${sym.label} has less than a week of history on the quote feed, so there are no returns to show yet.")
        val bench = n != null && lines.all { it.bench != null }; val nifty = TechFeed.NIFTY.label
        fun chart(part: List<Line>) = mapOf("unit" to "% return", "xLabel" to "Period ending ${TechFeed.day(b.asOf)}", "series" to listOfNotNull(sym.label, nifty.takeIf { bench }),
            "items" to part.map { l -> mapOf("label" to l.p.key, "values" to listOfNotNull(r2(l.own), l.bench?.takeIf { bench }?.let(::r2))) })
        val short = lines.filter { it.p.years == 0 }; val long = lines.filter { it.p.years >= 1 }
        val head = lines.firstOrNull { it.p.key == "1Y" } ?: lines.last()
        val gap = head.bench?.let { head.own - it }
        val sub = head.p.name + " price return" + (if (bench && gap != null) " · $nifty ${N.pct(head.bench)} · " + (if (gap >= 0) "ahead" else "behind") + " by ${one(kotlin.math.abs(gap))} points" else "") + " · to ${TechFeed.day(b.asOf)}"
        val card = Card("${sym.label} · returns" + (if (bench) " vs $nifty" else ""), if (head.own >= 0) "green" else "red").headline(N.pct(head.own), sub, N.tone(head.own))
        if (short.isNotEmpty()) card.viz("groupbars", 190, chart(short), "Total price return (%) over each period up to 6 months" + (if (bench) ", next to the $nifty." else "."))
        if (long.isNotEmpty()) card.viz("groupbars", 190, chart(long), "Total price return (%) over 1, 3 and 5 years (not annualised)" + (if (bench) ", next to the $nifty." else "."))
        val cagr = long.filter { it.p.years >= 3 }.map { l -> Triple(l, Quant.cagr(l.base, b.s.last, l.p.years.toDouble()), l.benchBase?.let { Quant.cagr(it, n!!.s.last, l.p.years.toDouble()) }) }
        return card.rows("Period returns", lines.map { l -> Card.Row(l.p.name, if (bench) "$nifty ${N.pct(l.bench!!)}" else "", N.pct(l.own), N.tone(l.own)) } +
                cagr.map { (l, own, m) -> Card.Row("${l.p.years}-year CAGR", if (bench && m != null) "$nifty ${N.pct(m)} a year" else "", N.pct(own) + " a year", N.tone(own)) })
            .note("Price returns from daily closes${if (sym.index) "" else "; dividends are not included, so total returns were higher for dividend payers"}. CAGR is the steady yearly rate that gives the same total. Past returns say nothing about future ones. ${TechFeed.DELAY}")
            .sources(TechFeed.SOURCE).pills("/drawdown ${sym.label}", "/seasonality ${sym.label}", "/compare ${sym.label} NIFTY", "/ta ${sym.label}")
            .put("symbol", sym.label).put("as_of", b.asOf.toString())
            .json("${sym.label} price return to ${TechFeed.day(b.asOf)}: " + lines.joinToString(", ") { "${it.p.name} ${N.pct(it.own)}" } +
                (cagr.lastOrNull()?.let { (l, own, _) -> " (${l.p.years}-year CAGR ${N.pct(own)} a year)" } ?: "") +
                (if (bench) ". $nifty over the same periods: " + lines.joinToString(", ") { "${it.p.key} ${N.pct(it.bench!!)}" } else "") + ". Source: ${TechFeed.SOURCE}.")
    }
}

/** /seasonality SYMBOL — every month's return for ten years as a month × year heat matrix, with each month's average. */
object TechSeasonalityFeature : Feature {
    override val id = "seasonality"
    override val usage = "/seasonality INFY"
    override val help = "Month by year grid of monthly returns as a heat matrix, with the average for each month"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\b(seasonality|seasonal (pattern|trend)s?|monthly returns?|(best|worst|strongest|weakest) months?)\b"""))
    private fun mon(m: Int, style: TextStyle = TextStyle.SHORT) = Month.of(m).getDisplayName(style, Locale.ENGLISH)

    override suspend fun run(arg: String, f: Fetch): String {
        val sym = TechFeed.resolve(arg, f) ?: return TechFeed.which(usage)
        return build(sym, TechFeed.chart(f, sym, "10y", "1mo"), f.today)
    }

    /** Pure. The month containing [today] is shown in the grid as "so far" but left out of every average. */
    fun build(sym: Sym, json: String, today: LocalDate): String {
        val b = TechFeed.bars(json) ?: return TechFeed.noData(sym, usage)
        val all = TechMath.monthlyReturns(b.s.dates, b.s.close); val current = YearMonth.from(today)
        val done = all.filterKeys { it.isBefore(current) }
        if (done.size < 12) return Card.error("${sym.label} has only ${done.size} complete months of history on the quote feed — a seasonality grid needs at least 12.")
        class Stat(val month: Int, val avg: Double, val up: Int, val n: Int)
        val stats = (1..12).mapNotNull { m -> val v = done.filterKeys { it.monthValue == m }.values; if (v.isEmpty()) null else Stat(m, v.average(), v.count { it > 0 }, v.size) }
        val best = stats.maxByOrNull { it.avg }!!; val worst = stats.minByOrNull { it.avg }!!; val now = stats.firstOrNull { it.month == current.monthValue }
        val yearsList = (all.keys.minOf { it.year }..all.keys.maxOf { it.year }).toList()
        val first = done.keys.min(); val last = done.keys.max()
        fun ym(x: YearMonth) = "${mon(x.monthValue)} ${x.year}"
        fun line(s: Stat) = "${N.pct(s.avg)} avg · up ${s.up} of ${s.n} years"
        val soFar = all[current]
        return Card("${sym.label} · seasonality · ${ym(first)} to ${ym(last)}", "violet")
            .headline("${mon(best.month, TextStyle.FULL)}: ${N.pct(best.avg)} on average", "Strongest month on average (up ${best.up} of ${best.n} years) · weakest ${mon(worst.month, TextStyle.FULL)} ${N.pct(worst.avg)} (up ${worst.up} of ${worst.n})", N.tone(best.avg))
            .viz("matrix", 70 + 22 * (yearsList.size + 1), mapOf(
                "cols" to (1..12).map { mon(it) }, "unit" to "%", "xLabel" to "Month", "yLabel" to "Year",
                "rows" to yearsList.map { y -> mapOf("label" to y.toString(), "values" to (1..12).map { m -> all[YearMonth.of(y, m)]?.let(::r2) }) },
                "footer" to mapOf("label" to "Avg", "values" to (1..12).map { m -> stats.firstOrNull { it.month == m }?.let { r2(it.avg) } }),
                "partial" to (if (soFar != null) mapOf("row" to current.year.toString(), "col" to current.monthValue - 1) else null)),
                "Rows: year. Columns: month. Each cell: that month's price change (%), green up, red down. Bottom row: the average for each month" + (if (soFar != null) "; the dotted cell is this month so far and is not in the averages." else ".") + " Tap a cell.")
            .stats(*listOfNotNull("Strongest month" to "${mon(best.month)} · ${line(best)}", "Weakest month" to "${mon(worst.month)} · ${line(worst)}",
                now?.let { "${mon(it.month)} in past years" to line(it) }, soFar?.let { "${mon(current.monthValue)} ${current.year} so far" to N.pct(it) },
                "Months counted" to "${done.size}").toTypedArray())
            .note("Each month has only ${stats.minOf { it.n }}–${stats.maxOf { it.n }} past readings, which is a small sample: an average can be driven by one unusual year, and monthly patterns often fail to repeat. Price changes only; dividends not included. ${TechFeed.DELAY}")
            .sources(TechFeed.SOURCE).pills("/returns ${sym.label}", "/drawdown ${sym.label}", "/ta ${sym.label}", "/compare ${sym.label} NIFTY")
            .put("symbol", sym.label).put("months", done.size)
            .json("${sym.label} monthly price changes, ${ym(first)} to ${ym(last)} (${done.size} months): strongest month on average ${mon(best.month, TextStyle.FULL)} ${N.pct(best.avg)} (up ${best.up} of ${best.n} years), " +
                "weakest ${mon(worst.month, TextStyle.FULL)} ${N.pct(worst.avg)} (up ${worst.up} of ${worst.n} years). Source: ${TechFeed.SOURCE}.")
    }
}

/** /compare A B [C D] [period] — two to four symbols rebased to 100 at the start of a period. */
object TechCompareFeature : Feature {
    override val id = "compare"
    override val usage = "/compare INFY TCS 1y"
    override val help = "Two to four stocks or indices rebased to 100 over a period (1mo 3mo 6mo 1y 2y 5y)"
    override val group = Group.STOCK

    private val PERIODS = linkedMapOf("1mo" to "1 month", "3mo" to "3 months", "6mo" to "6 months", "1y" to "1 year", "2y" to "2 years", "5y" to "5 years")

    /** "INFY, TCS vs HDFCBANK 6m" → ([INFY, TCS, HDFCBANK], "6mo"). The period defaults to 1y. */
    fun parse(arg: String): Pair<List<String>, String> {
        val tokens = arg.trim().split(Regex("""[\s,]+""")).filter { it.isNotBlank() && it.lowercase() !in setOf("vs", "vs.", "v", "versus", "and", "with", "against", "over") }
        val p = tokens.lastOrNull()?.lowercase()?.let { t -> if (Regex("""\d+m""").matches(t)) t + "o" else t }?.takeIf { it in PERIODS }
        return (if (p != null) tokens.dropLast(1) else tokens).map { it.removePrefix("@").uppercase() }.distinct().take(4) to (p ?: "1y")
    }

    override suspend fun run(arg: String, f: Fetch): String {
        val (names, period) = parse(arg)
        if (names.size < 2) return Card.error("Give two to four symbols and, if you like, a period — e.g. $usage.")
        val syms = names.map { TechFeed.resolve(it, f) ?: return Card.error("Couldn't recognise \"$it\". Try $usage.") }.distinctBy { it.feed }
        if (syms.size < 2) return Card.error("Give two different symbols — e.g. $usage.")
        return build(syms, syms.map { TechFeed.chart(f, it, period, if (period == "5y") "1wk" else "1d") }, period)
    }

    fun build(syms: List<Sym>, jsons: List<String>, period: String): String {
        val got = syms.zip(jsons).mapNotNull { (s, j) -> TechFeed.bars(j)?.let { s to it } }
        val missing = syms.filter { s -> got.none { it.first == s } }.map { it.label }
        if (got.size < 2) return Card.error("No price history for ${missing.joinToString(" or ")} on the quote feed right now, so there is nothing to compare. Check the symbols — e.g. $usage.")
        val maps = got.map { (_, b) -> b.s.dates.zip(b.s.close).toMap() }
        val days = maps.map { it.keys }.reduce { a, c -> a intersect c }.sorted()
        if (days.size < 2) return Card.error("${got.joinToString(" and ") { it.first.label }} have no trading days in common over this period.")
        class Line(val sym: Sym, val v: List<Double>) { val change get() = v.last() - 100 }
        val lines = got.mapIndexed { i, (s, _) -> val base = maps[i][days.first()]!!; Line(s, days.map { maps[i][it]!! / base * 100 }) }
        val ranked = lines.sortedByDescending { it.change }; val lead = ranked.first(); val name = PERIODS[period] ?: period
        val keep = TechFeed.thin(days.size, 260); val live = got.any { it.second.live }
        fun rets(l: Line) = l.v.zipWithNext { a, c -> c / a - 1 }
        val perYear = if (period == "5y") 52.0 else 252.0
        fun vol(l: Line) = Quant.stdev(rets(l)) * Math.sqrt(perYear) * 100
        val corr = if (lines.size == 2) Quant.correlation(rets(lines[0]), rets(lines[1])) else null
        val start = TechFeed.day(days.first()); val end = TechFeed.day(days.last())
        return Card(lines.joinToString(" vs ") { it.sym.label } + " · rebased to 100 · $name", if (lead.change >= 0) "green" else "red")
            .headline("${lead.sym.label} ${N.pct(lead.change)}", "Ahead over $name · " + ranked.drop(1).joinToString(" · ") { "${it.sym.label} ${N.pct(it.change)}" } + " · $start to $end" + (if (live) " (indicative)" else ""), N.tone(lead.change))
            .viz("multiline", 250, mapOf("t" to keep.map { days[it].toString() }, "base" to 100,
                "series" to lines.map { l -> mapOf("name" to l.sym.label, "v" to keep.map { r2(l.v[it]) }) }, "yLabel" to "Rebased value (start = 100)", "xLabel" to "Date"),
                "Each line starts at 100 on $start; 120 means +20% since then. By date. Drag to read values.")
            .rows("From 100 on $start", ranked.map { l -> Card.Row(l.sym.label, "100 → ${one(l.v.last())} · volatility ${one(vol(l))}% a year", N.pct(l.change), N.tone(l.change)) })
            .stats(*listOfNotNull("Sessions compared" to "${days.size}", corr?.let { "Correlation of ${if (period == "5y") "weekly" else "daily"} moves" to String.format(Locale.US, "%.2f", it) },
                ("Gap, first to last" to "${one(lead.change - ranked.last().change)} points")).toTypedArray())
            .note((if (missing.isNotEmpty()) "No data for ${missing.joinToString(", ")}, so it is left out. " else "") +
                "Price changes only (dividends not included), on the days all the symbols traded. A lead over one period says nothing about the next. ${TechFeed.DELAY}")
            .sources(TechFeed.SOURCE).pills("/returns ${lead.sym.label}", "/ta ${lines[0].sym.label}", "/drawdown ${lines[0].sym.label}", "/volatility ${lines[0].sym.label}")
            .put("period", period)
            .json("Rebased to 100 on $start, by $end ($name): " + ranked.joinToString(", ") { "${it.sym.label} ${one(it.v.last())} (${N.pct(it.change)})" } + ". Source: ${TechFeed.SOURCE}.")
    }
}
