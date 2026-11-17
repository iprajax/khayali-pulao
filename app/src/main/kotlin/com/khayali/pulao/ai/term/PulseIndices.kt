package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.s

/** One row of NSE's allIndices feed. */
internal class PulseIndex(val key: String, val name: String, val symbol: String, val last: Double, val change: Double, val pe: Double?,
                          val yearHigh: Double?, val yearLow: Double?, val d30: Double?, val d365: Double?) {
    /** Where the last value sits between the 52-week low (0%) and high (100%); null when NSE publishes no range. */
    val position: Double? get() { val h = yearHigh ?: return null; val l = yearLow ?: return null; return if (h > l) ((last - l) / (h - l) * 100).coerceIn(0.0, 100.0) else null }
    val group: String get() = when { key.contains("SECTORAL") -> "sectoral"; key.contains("THEMATIC") -> "thematic"; key.contains("STRATEGY") -> "strategy"; key.contains("FIXED") -> "bond"; else -> "broad" }

    companion object {
        fun parse(json: String): List<PulseIndex> = J.arr(json, "data").mapNotNull { o ->
            val last = o.d("last") ?: return@mapNotNull null; val ch = o.d("percentChange") ?: return@mapNotNull null
            val name = o.s("index").ifEmpty { return@mapNotNull null }
            fun pos(k: String) = o.d(k)?.takeIf { it > 0 }
            // NSE sends 0 for a 1-year change it cannot compute (an index younger than a year)
            PulseIndex(o.s("key"), name, o.s("indexSymbol").ifEmpty { name }, last, ch, pos("pe"), pos("yearHigh"), pos("yearLow"), o.d("perChange30d"), o.d("perChange365d")?.takeIf { it != 0.0 })
        }
    }
}

/** /indices [broad|sectoral|thematic|strategy|bond] — NSE indices of one group ranked by today's move, with P/E and 52-week position. */
object PulseIndicesFeature : Feature {
    override val id = "indices"
    override val usage = "/indices sectoral"
    override val help = "NSE indices ranked by today's move (broad, sectoral, thematic, strategy), with P/E and 52-week position"
    override val group = Group.MARKET
    override val triggers = listOf(Regex("""(?i)\b(all|nse|nifty|market) indices\b"""), Regex("""(?i)\bindices (today|now|list)\b"""))

    private val TITLES = linkedMapOf("broad" to "broad market", "sectoral" to "sectoral", "thematic" to "thematic", "strategy" to "strategy", "bond" to "fixed income")

    /** "" → "broad", "sector" → "sectoral", "themes" → "thematic", "debt" → "bond". */
    fun groupOf(arg: String): String { val a = arg.trim().lowercase(); return when {
        a.startsWith("sect") -> "sectoral"; a.startsWith("them") -> "thematic"; a.startsWith("strat") || a.startsWith("factor") -> "strategy"
        a.startsWith("bond") || a.startsWith("debt") || a.startsWith("fixed") || a.startsWith("g-sec") || a.startsWith("gsec") -> "bond"; else -> "broad" } }

    override suspend fun run(arg: String, f: Fetch): String = build(arg, f.nse("allIndices"))

    /** Pure: allIndices JSON → card for the group named by [arg]. */
    fun build(arg: String, json: String): String {
        val all = PulseIndex.parse(json)
        if (all.isEmpty()) return Card.error("NSE's index list is unavailable right now. Try again in a minute.")
        val g = groupOf(arg); val title = TITLES.getValue(g)
        val vix = all.firstOrNull { it.name == "INDIA VIX" }                       // a volatility gauge, not a price index: shown apart
        val rows = all.filter { it.group == g && it !== vix }.sortedByDescending { it.change }
        if (rows.isEmpty()) return Card.error("NSE lists no $title indices right now. Try /indices.")
        val up = rows.count { it.change > 0 }; val down = rows.count { it.change < 0 }
        val best = rows.first(); val worst = rows.last()
        val shown = if (rows.size > 16) rows.take(8) + rows.takeLast(8) else rows
        val table = if (rows.size > 10) rows.take(5) + rows.takeLast(5) else rows
        val nifty = all.firstOrNull { it.name == "NIFTY 50" }; val bank = all.firstOrNull { it.name == "NIFTY BANK" }
        val asOf = Pulse.asOf(Pulse.stamp(json))
        fun level(i: PulseIndex) = "${N.num(i.last)} (${N.pct(i.change, 2)})"
        fun tally(k: String) = all.filter { it.group == k && it !== vix }.takeIf { it.isNotEmpty() }?.let { l -> "${l.count { it.change > 0 }} up · ${l.count { it.change < 0 }} down" }
        val card = Card("NSE indices · $title$asOf", if (up >= down) "green" else "red")
            .headline("$up up · $down down", "of ${rows.size} $title indices · best ${best.name} ${N.pct(best.change, 2)} · worst ${worst.name} ${N.pct(worst.change, 2)}", N.tone((up - down).toDouble()))
            .viz("divbars", Pulse.height(shown.size), Pulse.divbars("%", "Today's change (%)", "Index", shown.map { i ->
                Pulse.bar(Pulse.short(i.symbol), i.change, note = listOfNotNull(N.num(i.last), i.pe?.let { "P/E " + Pulse.fixed(it, 1) }).joinToString(" · ")) }),
                "Bars: today's change (%) for each index, best to worst" + (if (shown.size < rows.size) " — the top 8 and bottom 8 of ${rows.size}" else "") + ". Tap a bar for the level and P/E.")
            .stats(*listOfNotNull(
                nifty?.let { "NIFTY 50" to level(it) }, bank?.let { "NIFTY BANK" to level(it) }, vix?.let { "India VIX" to level(it) }, nifty?.pe?.let { "NIFTY 50 P/E" to Pulse.fixed(it, 1) },
                *TITLES.keys.filter { it != g && it != "bond" }.take(2).mapNotNull { k -> tally(k)?.let { TITLES.getValue(k).replaceFirstChar(Char::uppercase) to it } }.toTypedArray()).toTypedArray())
            .rows("P/E and 52-week position (0% = 52-week low, 100% = high)", table.map { i ->
                Card.Row(i.name, listOfNotNull(i.pe?.let { "P/E " + Pulse.fixed(it, 1) }, i.position?.let { Pulse.fixed(it, 0) + "% of 52-week range" }).joinToString(" · ").ifEmpty { N.num(i.last) }, N.pct(i.change, 2), N.tone(i.change)) })
            .note("P/E and the 52-week range are as published by NSE. ${Pulse.DELAY}")
            .sources("NSE").pills("/sectors", if (g == "broad") "/indices thematic" else "/indices", "/heatmap", "/breadth")
        return card.put("group", g).json("NSE $title indices$asOf: $up up and $down down of ${rows.size}" +
            (nifty?.let { "; NIFTY 50 ${N.num(it.last)} (${N.pct(it.change, 2)})" + (it.pe?.let { p -> ", P/E " + Pulse.fixed(p, 1) } ?: "") } ?: "") +
            ". Best ${best.name} ${N.pct(best.change, 2)}, worst ${worst.name} ${N.pct(worst.change, 2)}. Source: NSE.")
    }
}

/** /sectors [30d|1y] — NSE sectoral indices ranked by today's move (or the 30-day / 1-year change), with all three in a table. */
object PulseSectorsFeature : Feature {
    override val id = "sectors"
    override val usage = "/sectors"
    override val help = "Sector indices ranked by today's move, with 30-day and 1-year change (/sectors 30d or 1y to rank by those)"
    override val group = Group.MARKET
    override val triggers = listOf(Regex("""(?i)\b(sector performance|sectoral indices|sector(al)? (moves?|movers|ranking))\b"""), Regex("""(?i)\b(best|worst|top) sectors?\b"""), Regex("""(?i)\bhow are (the )?sectors\b"""))

    /** "" → "today", "30d" / "month" → "30d", "1y" / "year" → "1y". */
    fun period(arg: String): String { val a = arg.trim().lowercase(); return when {
        a.startsWith("30") || a.contains("month") || a == "1m" -> "30d"; a.startsWith("1y") || a.contains("year") || a.startsWith("365") || a == "12m" -> "1y"; else -> "today" } }

    override suspend fun run(arg: String, f: Fetch): String = build(arg, f.nse("allIndices"))

    /** Pure: allIndices JSON → card of the sectoral group. */
    fun build(arg: String, json: String): String {
        val all = PulseIndex.parse(json).filter { it.group == "sectoral" }
        if (all.isEmpty()) return Card.error("NSE's sector indices are unavailable right now. Try again in a minute.")
        val p = period(arg)
        fun v(i: PulseIndex): Double? = when (p) { "30d" -> i.d30; "1y" -> i.d365; else -> i.change }
        val label = when (p) { "30d" -> "30-day change"; "1y" -> "1-year change"; else -> "today's change" }
        val word = when (p) { "30d" -> "over 30 days"; "1y" -> "over 1 year"; else -> "today" }
        val rows = all.filter { v(it) != null }.sortedByDescending { v(it)!! }
        if (rows.isEmpty()) return Card.error("NSE has no $label for its sector indices right now. Try /sectors.")
        val up = rows.count { v(it)!! > 0 }; val down = rows.count { v(it)!! < 0 }
        val best = rows.first(); val worst = rows.last()
        val shown = rows.take(22); val table = if (rows.size > 10) rows.take(5) + rows.takeLast(5) else rows
        val asOf = Pulse.asOf(Pulse.stamp(json))
        fun trail(i: PulseIndex) = "today ${N.pct(i.change, 2)} · 30 days ${i.d30?.let { N.pct(it, 2) } ?: "n/a"} · 1 year ${i.d365?.let { N.pct(it, 2) } ?: "n/a"}"
        return Card("NSE sectors · $label$asOf", if (up >= down) "green" else "red")
            .headline("$up up · $down down", "of ${rows.size} sector indices $word · best ${Pulse.short(best.name)} ${N.pct(v(best)!!, 2)} · worst ${Pulse.short(worst.name)} ${N.pct(v(worst)!!, 2)}", N.tone((up - down).toDouble()))
            .viz("divbars", Pulse.height(shown.size), Pulse.divbars("%", label.replaceFirstChar(Char::uppercase) + " (%)", "Sector index", shown.map { Pulse.bar(Pulse.short(it.symbol), v(it)!!, note = trail(it)) }),
                "Bars: $label (%) for each NSE sector index, best to worst. Tap a bar for today, 30 days and 1 year.")
            .rows("Leaders and laggards · today, 30 days, 1 year", table.map { Card.Row(Pulse.short(it.name), if (p == "today") trail(it).substringAfter(" · ") else trail(it), N.pct(v(it)!!, 2), N.tone(v(it)!!)) })
            .note("Index changes only — they say nothing about what happens next. \"n/a\" means NSE publishes no figure for that period. ${Pulse.DELAY}")
            .sources("NSE").pills("/heatmap ${best.name}", "/indices", "/movers", if (p == "today") "/sectors 30d" else "/sectors")
            .put("period", p)
            .json("NSE sector indices$asOf: $up up and $down down of ${rows.size} $word. Best ${best.name} ${N.pct(v(best)!!, 2)} (${trail(best)}); worst ${worst.name} ${N.pct(v(worst)!!, 2)} (${trail(worst)}). Source: NSE.")
    }
}
