package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.TechFeed.Sym
import com.khayali.pulao.ai.term.TechMath.r2

/** /ta SYMBOL — a year of price with its 20/50/200-day moving averages and Bollinger bands. */
object TechTaFeature : Feature {
    override val id = "ta"
    override val usage = "/ta INFY"
    override val help = "Price with 20/50/200-day moving averages and Bollinger bands"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\b(moving averages?|bollinger|technical (chart|analysis)|\d{2,3}[- ]?(dma|day moving))\b"""))
    private const val SHOWN = 252

    override suspend fun run(arg: String, f: Fetch): String {
        val sym = TechFeed.resolve(arg, f) ?: return TechFeed.which(usage)
        return build(sym, TechFeed.chart(f, sym, "2y", "1d"))      // two years, so the 200-day average covers the whole year drawn
    }

    /** Pure: quote-feed chart JSON → card. Draws the last year of whatever history it is given. */
    fun build(sym: Sym, json: String): String {
        val b = TechFeed.bars(json) ?: return TechFeed.noData(sym, usage)
        val s = b.s; val c = s.close
        if (c.size < 21) return Card.error("${sym.label} has only ${c.size} sessions of history on the quote feed — too few for a 20-day average.")
        val m20 = Quant.sma(c, 20); val m50 = Quant.sma(c, 50); val m200 = Quant.sma(c, 200); val bb = TechMath.bollinger(c, 20, 2.0)
        val from = maxOf(0, c.size - SHOWN); val last = c.last()
        fun <T> cut(x: List<T>) = x.subList(from, x.size)
        val avgs = listOf(Triple("20-day", m20, "saffron"), Triple("50-day", m50, "violet"), Triple("200-day", m200, "pink")).filter { it.second.last() != null }
        val above = avgs.filter { last > it.second.last()!! }.map { it.first }; val below = avgs.filter { last <= it.second.last()!! }.map { it.first }
        fun join(x: List<String>) = when (x.size) { 0 -> ""; 1 -> x[0]; else -> x.dropLast(1).joinToString(", ") + " and " + x.last() }
        fun avgWord(x: List<String>) = join(x) + if (x.size > 1) " averages" else " average"
        val where = listOfNotNull(above.takeIf { it.isNotEmpty() }?.let { "above its ${avgWord(it)}" }, below.takeIf { it.isNotEmpty() }?.let { "below its ${avgWord(it)}" }).joinToString(", ")
        val up = bb.upper.last()!!; val lo = bb.lower.last()!!; val pos = TechMath.position(last, lo, up)
        val card = Card("${sym.label} · moving averages & Bollinger bands", "blue")
            .headline(sym.money(last), where.replaceFirstChar { it.uppercase() } + " · ${b.whenText}")
            .viz("overlay", 260, mapOf(
                "t" to cut(s.dates).map { it.toString() }, "price" to r2(cut(c)), "name" to (if (sym.index) "Level" else "Price"), "unit" to sym.unit,
                "lines" to avgs.map { (k, v, col) -> mapOf("name" to "$k avg", "color" to col, "v" to r2(cut(v))) },
                "band" to mapOf("name" to "Bollinger band", "upper" to r2(cut(bb.upper)), "lower" to r2(cut(bb.lower))),
                "yLabel" to sym.priceAxis, "xLabel" to "Date"),
                "${sym.priceAxis} by date. Lines: daily close and its 20/50/200-day simple averages. Shaded: Bollinger band (20-day average ± 2 standard deviations). Drag to read values.")
            .stats(*(avgs.map { (k, v, _) -> "$k average" to sym.money(v.last()!!) } + listOf(
                "Bollinger upper" to sym.money(up), "Bollinger lower" to sym.money(lo), "Position in band" to "${Math.round(pos)}% (0 = lower, 100 = upper)")).toTypedArray())
            .note("Moving averages and bands summarise past prices; they do not say where the price goes next. ${TechFeed.DELAY}")
            .sources(TechFeed.SOURCE).pills("/rsi ${sym.label}", "/macd ${sym.label}", "/levels ${sym.label}", "/returns ${sym.label}")
            .put("symbol", sym.label).put("as_of", b.asOf.toString())
        return card.json("${sym.label} ${b.verb} ${sym.money(last)} on ${TechFeed.day(b.asOf)}: " + avgs.joinToString(", ") { (k, v, _) -> "$k average ${sym.money(v.last()!!)}" } +
            "; Bollinger band (20, 2) ${sym.money(lo)} to ${sym.money(up)}. Source: ${TechFeed.SOURCE}.")
    }
}

/** /levels SYMBOL — classic floor pivots from the last completed session, and the 52-week high and low, drawn on the recent price. */
object TechLevelsFeature : Feature {
    override val id = "levels"
    override val usage = "/levels INFY"
    override val help = "Support and resistance: classic pivot levels from the last session, plus the 52-week high and low"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\b(support (and|&|/|or) resistance|pivot (points?|levels?)|(support|resistance) levels?)\b"""))
    private const val SHOWN = 60

    override suspend fun run(arg: String, f: Fetch): String {
        val sym = TechFeed.resolve(arg, f) ?: return TechFeed.which(usage)
        return build(sym, TechFeed.chart(f, sym, "1y", "1d"))
    }

    fun build(sym: Sym, json: String): String {
        val b = TechFeed.bars(json) ?: return TechFeed.noData(sym, usage)
        val s = b.s; val ref = if (b.live) s.size - 2 else s.size - 1       // pivots need a finished session
        if (ref < 0) return TechFeed.noData(sym, usage)
        val p = TechMath.pivots(s.high[ref], s.low[ref], s.close[ref]); val last = s.last
        val yearAgo = b.asOf.minusYears(1); val yr = s.dates.indices.filter { !s.dates[it].isBefore(yearAgo) }
        val hiI = yr.maxByOrNull { s.high[it] }!!; val loI = yr.minByOrNull { s.low[it] }!!; val hi = s.high[hiI]; val lo = s.low[loI]
        val ladder = listOf("S3" to p.s3, "S2" to p.s2, "S1" to p.s1, "Pivot" to p.p, "R1" to p.r1, "R2" to p.r2, "R3" to p.r3)
        val under = ladder.lastOrNull { it.second <= last }; val over = ladder.firstOrNull { it.second > last }
        val where = when { under == null -> "below S3 (${sym.money(p.s3)})"; over == null -> "above R3 (${sym.money(p.r3)})"
            else -> "between ${under.first} (${sym.money(under.second)}) and ${over.first} (${sym.money(over.second)})" }
        val from = maxOf(0, s.size - SHOWN); val refDay = TechFeed.day(s.dates[ref])
        fun gap(v: Double) = if (last > 0) N.pct((v / last - 1) * 100, 2) + " from the last price" else ""
        fun level(label: String, v: Double, color: String, fit: Boolean = true, short: String = label) = mapOf("label" to label, "short" to short, "value" to r2(v), "color" to color, "fit" to fit)
        return Card("${sym.label} · support & resistance · pivots from $refDay", "saffron")
            .headline(sym.money(last), "${where.replaceFirstChar { it.uppercase() }} · ${b.whenText}")
            .viz("overlay", 270, mapOf(
                "t" to s.dates.subList(from, s.size).map { it.toString() }, "price" to r2(s.close.subList(from, s.size)), "name" to (if (sym.index) "Level" else "Price"), "unit" to sym.unit,
                "zones" to listOf(mapOf("lo" to r2(p.s2), "hi" to r2(p.s1), "color" to "green", "label" to "Support S2–S1"), mapOf("lo" to r2(p.r1), "hi" to r2(p.r2), "color" to "red", "label" to "Resistance R1–R2")),
                "levels" to listOf(level("R2", p.r2, "red"), level("R1", p.r1, "red"), level("P", p.p, "saffron"), level("S1", p.s1, "green"), level("S2", p.s2, "green"),
                    level("52-wk high", hi, "muted", false, "52wH"), level("52-wk low", lo, "muted", false, "52wL")),
                "yLabel" to sym.priceAxis, "xLabel" to "Date"),
                "${sym.priceAxis} by date, last ${s.size - from} sessions. Dashed lines: pivot (P), supports (S) and resistances (R) worked out from the session of $refDay. Bands: S2–S1 and R1–R2.")
            .rows("Levels", (ladder.reversed().map { (k, v) -> Card.Row(if (k == "Pivot") "Pivot" else "$k · ${if (k.startsWith("R")) "resistance" else "support"}", gap(v), sym.money(v)) } +
                listOf(Card.Row("52-week high", TechFeed.day(s.dates[hiI]) + " · " + gap(hi), sym.money(hi)), Card.Row("52-week low", TechFeed.day(s.dates[loI]) + " · " + gap(lo), sym.money(lo)))))
            .note("Classic floor pivots are plain arithmetic on one session's high (${sym.money(s.high[ref])}), low (${sym.money(s.low[ref])}) and close (${sym.money(s.close[ref])}). They are reference points many traders watch, not forecasts. ${TechFeed.DELAY}")
            .sources(TechFeed.SOURCE).pills("/ta ${sym.label}", "/rsi ${sym.label}", "/volatility ${sym.label}", "/price ${sym.label}")
            .put("symbol", sym.label).put("as_of", b.asOf.toString())
            .json("${sym.label} ${b.verb} ${sym.money(last)} on ${TechFeed.day(b.asOf)}; classic pivots from the session of $refDay: pivot ${sym.money(p.p)}, support ${sym.money(p.s1)} and ${sym.money(p.s2)}, resistance ${sym.money(p.r1)} and ${sym.money(p.r2)}. " +
                "Its 52-week range is ${sym.money(lo)} to ${sym.money(hi)}. Source: ${TechFeed.SOURCE}.")
    }
}
