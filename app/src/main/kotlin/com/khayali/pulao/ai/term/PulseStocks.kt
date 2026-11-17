package com.khayali.pulao.ai.term

import com.google.gson.JsonObject
import com.khayali.pulao.ai.term.J.a
import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.o
import com.khayali.pulao.ai.term.J.s
import com.khayali.pulao.ai.term.Pulse.pct
import kotlin.math.abs

/** /movers [nifty|bank|next50|fno|all] — the day's top gainers and losers in one NSE universe. */
object PulseMoversFeature : Feature {
    override val id = "movers"
    override val usage = "/movers fno"
    override val help = "Top gainers and losers today — NIFTY 50 by default, or bank, next50, fno, all"
    override val group = Group.MARKET
    override val triggers = listOf(Regex("""(?i)\b(top|biggest|today'?s) (gainers|losers|movers)\b"""), Regex("""(?i)\bgainers (and|&) losers\b"""))

    /** Argument → (key in the feed, title). */
    fun universe(arg: String): Pair<String, String> { val a = arg.trim().lowercase().replace(" ", ""); return when {
        a.contains("next") || a.contains("junior") -> "NIFTYNEXT50" to "NIFTY NEXT 50"; a.contains("bank") -> "BANKNIFTY" to "NIFTY BANK"
        a.contains("fno") || a.contains("f&o") || a.contains("fo") && a.length <= 3 -> "FOSec" to "F&O stocks"; a.startsWith("all") || a.contains("market") -> "allSec" to "all NSE stocks"
        else -> "NIFTY" to "NIFTY 50" } }

    override suspend fun run(arg: String, f: Fetch): String = build(arg, f.nse("live-analysis-variations?index=gainers"), f.nse("live-analysis-variations?index=loosers"))

    private class Mover(val symbol: String, val change: Double, val price: Double?, val turnover: String)
    private fun parse(json: String, key: String) = J.obj(json)?.o(key)?.a("data").orEmpty().mapNotNull { o ->
        Mover(o.s("symbol").ifEmpty { return@mapNotNull null }, o.pct("perChange", "net_price") ?: return@mapNotNull null, o.d("ltp"), o.s("turnover")) }.distinctBy { it.symbol }

    /** Pure: the gainers and losers feeds → card. */
    fun build(arg: String, gainersJson: String, losersJson: String): String {
        val (key, title) = universe(arg)
        val up = parse(gainersJson, key).filter { it.change > 0 }.sortedByDescending { it.change }
        val down = parse(losersJson, key).filter { it.change < 0 }.sortedBy { it.change }
        if (up.isEmpty() && down.isEmpty()) return Card.error("NSE lists no gainers or losers for $title right now — the market may not have traded yet. Try /status.")
        val asOf = Pulse.asOf(J.obj(gainersJson)?.o(key)?.s("timestamp").orEmpty().ifEmpty { J.obj(losersJson)?.o(key)?.s("timestamp").orEmpty() })
        val g = up.firstOrNull(); val l = down.firstOrNull()
        fun at(m: Mover) = m.price?.let { " at ₹" + N.num(it) } ?: ""
        fun row(m: Mover) = Card.Row(m.symbol, listOfNotNull(m.price?.let { "₹" + N.num(it) }, m.turnover.takeIf { it.isNotEmpty() }?.let { "traded ₹$it" }).joinToString(" · "), N.pct(m.change, 2), N.tone(m.change))
        val bars = up.take(6) + down.take(6).reversed()
        return Card("Top movers · $title$asOf", if (up.size >= down.size) "green" else "red")
            .headline(listOfNotNull(g?.let { "${it.symbol} ${N.pct(it.change, 2)}" }, l?.let { "${it.symbol} ${N.pct(it.change, 2)}" }).joinToString(" · "),
                (if (g != null && l != null) "Biggest gainer and biggest loser" else if (g != null) "Biggest gainer; no losers listed" else "Biggest loser; no gainers listed") + " in $title today")
            .viz("divbars", Pulse.height(bars.size), Pulse.divbars("%", "Today's change (%)", "Stock", bars.map { Pulse.bar(it.symbol, it.change, note = it.price?.let { p -> "₹" + N.num(p) }) }, listOf("Gainers", "Losers")),
                "Bars: today's price change (%) for the largest gainers and losers in $title. Tap a bar for the price.")
            .rows("Top gainers", up.take(4).map(::row)).rows("Top losers", down.take(4).map(::row))
            .note("A large one-day move says nothing about the next one. ${Pulse.DELAY}")
            .sources("NSE").pills(*listOfNotNull(g?.let { "/quote ${it.symbol}" }, if (key == "NIFTY") "/movers fno" else "/movers", "/breadth", "/heatmap").toTypedArray())
            .put("universe", title)
            .json("${title.replaceFirstChar(Char::uppercase)} movers$asOf: " + listOfNotNull(g?.let { "top gainer ${it.symbol} ${N.pct(it.change, 2)}${at(it)}" }, l?.let { "top loser ${it.symbol} ${N.pct(it.change, 2)}${at(it)}" }).joinToString("; ") + ". Source: NSE.")
    }
}

/** /active — the most traded securities today, by rupee value and by number of shares. */
object PulseActiveFeature : Feature {
    override val id = "active"
    override val usage = "/active"
    override val help = "Most active securities today: by traded value (₹ crore) and by number of shares"
    override val group = Group.MARKET
    override val triggers = listOf(Regex("""(?i)\bmost (active|traded) (stocks|shares|securities)\b"""), Regex("""(?i)\b(stocks|shares) most (active|traded)\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(f.nse("live-analysis-most-active-securities?index=volume"), f.nse("live-analysis-most-active-securities?index=value"))

    private class Active(val symbol: String, val price: Double?, val change: Double, val shares: Double, val rupees: Double)
    private fun parse(json: String) = J.arr(json, "data").mapNotNull { o ->
        Active(o.s("symbol").ifEmpty { return@mapNotNull null }, o.d("lastPrice"), o.d("pChange") ?: 0.0, o.d("quantityTraded") ?: o.d("totalTradedVolumecsv") ?: 0.0, o.d("totalTradedValuecsv") ?: 0.0) }.distinctBy { it.symbol }

    /** Pure: the by-volume and by-value feeds → card. Either may be empty, not both. */
    fun build(volumeJson: String, valueJson: String): String {
        val byVol = parse(volumeJson).filter { it.shares > 0 }.sortedByDescending { it.shares }
        val byVal = parse(valueJson).filter { it.rupees > 0 }.sortedByDescending { it.rupees }
        if (byVol.isEmpty() && byVal.isEmpty()) return Card.error("NSE lists no active securities right now — the market may not have traded yet. Try /status.")
        val asOf = Pulse.asOf(Pulse.stamp(valueJson).ifEmpty { Pulse.stamp(volumeJson) })
        val v = byVal.firstOrNull(); val q = byVol.firstOrNull()
        val legend = listOf("Price up", "Price down", "Flat")
        fun tone(a: Active) = if (a.change > 0) "up" else if (a.change < 0) "down" else "flat"
        fun note(a: Active) = listOfNotNull(a.price?.let { "₹" + N.num(it) }, N.pct(a.change, 2)).joinToString(" · ")
        val card = Card("Most active on NSE$asOf", "blue")
            .headline(v?.let { "${it.symbol} · ${Pulse.rupees(it.rupees)}" } ?: "${q!!.symbol} · ${Pulse.qty(q.shares)} shares",
                if (v != null && q != null) "Most traded by value today · most shares traded: ${q.symbol}, ${Pulse.qty(q.shares)}" else if (v != null) "Most traded by value today" else "Most shares traded today")
        if (byVal.isNotEmpty()) card.viz("divbars", Pulse.height(minOf(8, byVal.size)), Pulse.divbars("₹ Cr", "Traded value (₹ crore)", "Security", byVal.take(8).map { Pulse.bar(it.symbol, it.rupees / 1e7, tone(it), note(it)) }, legend, 0),
            "Bars: rupee value traded today (₹ crore). Colour: whether the price is up or down today (blue = unchanged). Tap a bar.")
        if (byVol.isNotEmpty()) card.viz("divbars", Pulse.height(minOf(8, byVol.size)), Pulse.divbars("lakh", "Shares traded (lakh)", "Security", byVol.take(8).map { Pulse.bar(it.symbol, it.shares / 1e5, tone(it), note(it)) }, legend, 0),
            "Bars: number of shares traded today, in lakh (1 lakh = 100,000). Colour: price up or down today.")
        return card.rows("By traded value", byVal.take(3).map { Card.Row(it.symbol, note(it), Pulse.rupees(it.rupees), N.tone(it.change)) })
            .rows("By shares traded", byVol.take(3).map { Card.Row(it.symbol, note(it), Pulse.qty(it.shares), N.tone(it.change)) })
            .note("Activity shows where trading was concentrated, not whether buyers or sellers were in control. Exchange-traded funds are included. ${Pulse.DELAY}")
            .sources("NSE").pills(*listOfNotNull((v ?: q)?.let { "/quote ${it.symbol}" }, "/volume", "/movers", "/deals").toTypedArray())
            .json("Most active on NSE$asOf: " + listOfNotNull(v?.let { "by value ${it.symbol}, ${Pulse.rupees(it.rupees)} traded (${N.pct(it.change, 2)})" },
                q?.let { "by volume ${it.symbol}, ${Pulse.qty(it.shares)} shares (${N.pct(it.change, 2)})" }).joinToString("; ") + ". Source: NSE.")
    }
}

/** /highs — stocks that set a new 52-week high or low in today's session. */
object PulseHighsFeature : Feature {
    override val id = "highs"
    override val usage = "/highs"
    override val help = "Stocks that set a new 52-week high or low today, with the count on each side"
    override val group = Group.MARKET
    override val triggers = listOf(Regex("""(?i)\b(new )?52[- ]?(week|wk) (highs|lows)\b"""), Regex("""(?i)\b(stocks|shares) (at|hitting|near) (a |their )?(new )?52[- ]?(week|wk) (high|low)s?\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(f.nse("live-analysis-data-52weekhighstock"), f.nse("live-analysis-data-52weeklowstock"))

    private class Mark(val symbol: String, val name: String, val price: Double?, val change: Double, val level: Double?)
    // NSE's company-name field really is spelt "comapnyName". The feed repeats some stocks, and one of the copies can carry a
    // stale previous close (seen: a +719% "change" beside the real +1.6%), so of the duplicates the smallest move is kept.
    private fun parse(json: String) = J.arr(json, "data").mapNotNull { o ->
        Mark(o.s("symbol").ifEmpty { return@mapNotNull null }, o.s("comapnyName").ifEmpty { o.s("companyName") }, o.d("ltp"), o.d("pChange") ?: 0.0, o.d("new52WHL")) }
        .groupBy { it.symbol }.map { (_, same) -> same.minBy { abs(it.change) } }

    /** Pure: the 52-week-high and 52-week-low feeds → card. */
    fun build(highJson: String, lowJson: String): String {
        if (J.obj(highJson) == null && J.obj(lowJson) == null) return Card.error("NSE's 52-week high/low lists are unavailable right now. Try again in a minute.")
        val hi = parse(highJson); val lo = parse(lowJson)
        if (hi.isEmpty() && lo.isEmpty()) return Card.error("NSE lists no new 52-week highs or lows yet today — the market may not have traded. Try /status.")
        val asOf = Pulse.asOf(Pulse.stamp(highJson).ifEmpty { Pulse.stamp(lowJson) })
        val topHi = hi.sortedByDescending { it.change }.take(5); val topLo = lo.sortedBy { it.change }.take(5)
        fun row(m: Mark, what: String) = Card.Row(m.symbol, listOfNotNull(m.level?.let { "$what ₹" + N.num(it) }, m.price?.let { "last ₹" + N.num(it) }).joinToString(" · "), N.pct(m.change, 2), N.tone(m.change))
        fun stocks(n: Int) = if (n == 1) "1 stock" else "$n stocks"
        return Card("52-week highs and lows · NSE$asOf", if (hi.size >= lo.size) "green" else "red")
            .headline("${hi.size} highs · ${lo.size} lows", "Stocks that set a new 52-week high or low in the session", N.tone((hi.size - lo.size).toDouble()))
            .viz("donut", 150, Pulse.donut("${hi.size} : ${lo.size}", "highs : lows", "New 52-week", "Stocks", listOf(Pulse.slice("Highs", hi.size.toDouble(), "green"), Pulse.slice("Lows", lo.size.toDouble(), "red"))),
                "Ring: number of stocks at a new 52-week high versus a new 52-week low today. Tap a slice.")
            .viz("divbars", Pulse.height(topHi.size + topLo.size), Pulse.divbars("%", "Today's change (%)", "Stock",
                topHi.map { Pulse.bar(it.symbol, it.change, "up", it.level?.let { l -> "new high ₹" + N.num(l) }) } + topLo.reversed().map { Pulse.bar(it.symbol, it.change, "down", it.level?.let { l -> "new low ₹" + N.num(l) }) },
                listOf("New 52-week high", "New 52-week low")),
                "Bars: today's price change (%), as reported by NSE, for the new highs that rose most and the new lows that fell most. Tap a bar for the level.")
            .rows("New highs · biggest gains today", topHi.map { row(it, "high") }).rows("New lows · biggest falls today", topLo.map { row(it, "low") })
            .note("A new high or low records where the price has been; it does not say where it goes next. ${Pulse.DELAY}")
            .sources("NSE").pills(*listOfNotNull("/breadth", "/movers", (topHi.firstOrNull() ?: topLo.firstOrNull())?.let { "/quote ${it.symbol}" }, "/bands").toTypedArray())
            .put("highs", hi.size).put("lows", lo.size)
            .json("NSE$asOf: ${stocks(hi.size)} set a new 52-week high and ${stocks(lo.size)} a new 52-week low in the session. Source: NSE.")
    }
}

/** /bands — stocks that hit their upper or lower price band (circuit limit) today. */
object PulseBandsFeature : Feature {
    override val id = "bands"
    override val usage = "/bands"
    override val help = "Stocks that hit their upper or lower price band (circuit limit) today"
    override val group = Group.MARKET
    override val triggers = listOf(Regex("""(?i)\b(stocks|shares) (in|at|on|hitting|hit|locked (in|at)) (the |an? )?(upper|lower) (circuit|band)s?\b"""), Regex("""(?i)\b((upper|lower) circuit (stocks|list)|(price )?band hitters|circuit hitters)\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(f.nse("live-analysis-price-band-hitter"))

    private class Hit(val symbol: String, val series: String, val price: Double?, val change: Double?, val band: String, val turnover: Double)
    private fun parse(side: JsonObject?) = side?.o("AllSec")?.a("data").orEmpty().mapNotNull { o ->
        Hit(o.s("symbol").ifEmpty { return@mapNotNull null }, o.s("series"), o.d("ltp"), o.d("pChange"), o.s("priceBand"), o.d("turnovercsv") ?: 0.0) }.distinctBy { it.symbol }.sortedByDescending { it.turnover }

    /** Pure: the price-band-hitter feed → card. */
    fun build(json: String): String {
        val root = J.obj(json) ?: return Card.error("NSE's price-band list is unavailable right now. Try again in a minute.")
        val upper = parse(root.o("upper")); val lower = parse(root.o("lower")); val both = parse(root.o("both"))
        val count = root.o("count") ?: root.o("upper")?.o("AllSec")?.o("count")
        if (count == null && root.o("upper") == null && root.o("lower") == null) return Card.error("NSE's price-band list is unavailable right now. Try again in a minute.")
        val nUp = count?.d("UPPER")?.toInt() ?: upper.size; val nLo = count?.d("LOWER")?.toInt() ?: lower.size; val nBoth = count?.d("BOTH")?.toInt() ?: both.size
        val total = nUp + nLo + nBoth
        val asOf = Pulse.asOf(root.o("upper")?.o("AllSec")?.s("timestamp").orEmpty())
        if (total == 0) return Card("Price-band hitters · NSE$asOf").headline("None", "No stock is listed at its upper or lower price band right now")
            .note("NSE fills this list during the trading session. ${Pulse.DELAY}").sources("NSE").pills("/status", "/movers", "/volume")
            .json("NSE price-band hitters$asOf: no stocks are listed at their upper or lower price band. Source: NSE.")
        fun row(h: Hit) = Card.Row(h.symbol + (if (h.series.isNotEmpty() && h.series != "EQ") " (${h.series})" else ""),
            listOfNotNull(h.price?.let { "₹" + N.num(it) }, h.band.takeIf { it.isNotEmpty() }?.let { "band $it%" }, h.turnover.takeIf { it > 0 }?.let { "traded " + Pulse.rupees(it) }).joinToString(" · "),
            h.change?.let { N.pct(it, 2) }.orEmpty(), h.change?.let { N.tone(it) }.orEmpty())
        return Card("Price-band hitters · NSE$asOf", if (nUp >= nLo) "green" else "red")
            .headline("$nUp upper · $nLo lower", (if (nBoth > 0) "$nBoth more hit both bands · " else "") + "$total stocks hit a price band in the session", N.tone((nUp - nLo).toDouble()))
            .viz("donut", 160, Pulse.donut("$total", if (total == 1) "stock" else "stocks", "Band hit", "Stocks",
                listOf(Pulse.slice("Upper band", nUp.toDouble(), "green"), Pulse.slice("Lower band", nLo.toDouble(), "red"), Pulse.slice("Both bands", nBoth.toDouble(), "saffron"))),
                "Ring: number of stocks that hit their upper band, lower band, or both today. Tap a slice.")
            .rows("Hit the upper band · most traded", upper.take(5).map(::row)).rows("Hit the lower band · most traded", lower.take(5).map(::row))
            .note("A price band is the most a stock may move in a day (2%, 5%, 10% or 20%). The list shows stocks that touched a band; the last price may have moved off it since. Many are thinly traded.")
            .sources("NSE").pills("/volume", "/movers", "/highs", "/breadth")
            .put("upper", nUp).put("lower", nLo).put("both", nBoth)
            .json("NSE price-band hitters$asOf: $nUp hit the upper band, $nLo the lower band and $nBoth both ($total in all). Source: NSE.")
    }
}

/** /volume — stocks trading at a multiple of their one-week average volume. */
object PulseVolumeFeature : Feature {
    override val id = "volume"
    override val usage = "/volume"
    override val help = "Unusual volume: stocks trading at several times their 1-week average volume today"
    override val group = Group.MARKET
    override val triggers = listOf(Regex("""(?i)\b(unusual volumes?|volume (gainers|shockers|spurts?|buzzers|spikes?))\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(f.nse("live-analysis-volume-gainers"))

    private class Spurt(val symbol: String, val name: String, val volume: String, val avg: Double?, val times: Double, val price: Double?, val change: Double, val turnover: String)

    /** Pure: the volume-gainers feed → card. */
    fun build(json: String): String {
        val rows = J.arr(json, "data").mapNotNull { o ->
            Spurt(o.s("symbol").ifEmpty { return@mapNotNull null }, o.s("companyName"), o.s("volume"), o.d("week1AvgVolume"), o.d("week1volChange")?.takeIf { it > 0 } ?: return@mapNotNull null, o.d("ltp"), o.d("pChange") ?: 0.0, o.s("turnover")) }
            .distinctBy { it.symbol }.sortedByDescending { it.times }
        if (rows.isEmpty()) return Card.error("NSE lists no volume gainers right now — the market may not have traded yet. Try /status.")
        val asOf = Pulse.asOf(Pulse.stamp(json)); val top = rows.first()
        fun x(v: Double) = Pulse.fixed(v, 1)
        fun tone(s: Spurt) = if (s.change > 0) "up" else if (s.change < 0) "down" else "flat"
        return Card("Unusual volume · NSE$asOf", "violet")
            .headline("${top.symbol} · ${x(top.times)}× usual volume", "${rows.size} stocks on NSE's list of volume well above the 1-week average")
            .viz("divbars", Pulse.height(minOf(10, rows.size)), Pulse.divbars("x", "Today's volume ÷ 1-week average (times)", "Stock",
                rows.take(10).map { Pulse.bar(it.symbol, it.times, tone(it), listOfNotNull(it.volume.takeIf { v -> v.isNotEmpty() }?.let { v -> "$v shares" }, "price " + N.pct(it.change, 2)).joinToString(" · ")) }, listOf("Price up", "Price down", "Flat")),
                "Bars: today's volume as a multiple of the stock's 1-week average. Colour: price up or down today (blue = unchanged). Tap a bar.")
            .rows("Highest multiples", rows.take(5).map { Card.Row(it.symbol, listOfNotNull(it.volume.takeIf { v -> v.isNotEmpty() }?.let { v -> "$v shares" }, it.avg?.let { a -> "1-week avg " + Pulse.qty(a) }, it.price?.let { p -> "₹" + N.num(p) }).joinToString(" · "), x(it.times) + "×", N.tone(it.change)) })
            .note("Heavy volume shows unusual attention, not its cause or direction; small, thinly traded stocks reach high multiples easily. ${Pulse.DELAY}")
            .sources("NSE").pills("/quote ${top.symbol}", "/active", "/deals", "/bands")
            .json("NSE volume gainers$asOf: ${rows.size} stocks listed; highest ${top.symbol} at ${x(top.times)} times its 1-week average volume" +
                (top.volume.takeIf { it.isNotEmpty() }?.let { " ($it shares, price ${N.pct(top.change, 2)})" } ?: "") + ". Source: NSE.")
    }
}
