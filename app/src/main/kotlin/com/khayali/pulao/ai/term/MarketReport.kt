package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.J.a
import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.o
import com.khayali.pulao.ai.term.J.s
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Shared readers for NSE's market feeds used by the report, pre-open, sector and watch features. Pure. */
object Nse {
    data class Index(val name: String, val group: String, val last: Double, val change: Double, val m1: Double?, val y1: Double?, val up: Int?, val down: Int?, val pe: String)
    data class Move(val symbol: String, val price: Double, val change: Double, val prev: Double?, val note: String = "")

    fun indices(json: String): List<Index> = J.arr(json, "data").mapNotNull { o ->
        Index(o.s("index"), o.s("key"), o.d("last") ?: return@mapNotNull null, o.d("percentChange") ?: 0.0, o.d("perChange30d"), o.d("perChange365d"),
            o.s("advances").toIntOrNull(), o.s("declines").toIntOrNull(), o.s("pe"))
    }

    /** live-analysis-variations (gainers / "loosers") → one index's list, e.g. key "NIFTY". */
    fun variations(json: String, key: String = "NIFTY"): List<Move> = J.obj(json)?.o(key)?.a("data").orEmpty().mapNotNull { o ->
        Move(o.s("symbol"), o.d("ltp") ?: return@mapNotNull null, o.d("perChange") ?: o.d("net_price") ?: 0.0, o.d("prev_price"), o.s("turnover").takeIf { it.isNotBlank() }?.let { "₹$it traded" }.orEmpty())
    }

    /** An index's constituents with today's move (NextApi heatmap or heatmap-symbols). */
    fun constituents(json: String): List<Move> = (J.arr(json, "data").ifEmpty { J.arr(json) }).mapNotNull { o ->
        val p = o.d("lastPrice") ?: return@mapNotNull null
        val ch = o.d("pchange") ?: o.d("pChange") ?: return@mapNotNull null
        Move(o.s("symbol"), p, ch, if (ch > -100) p / (1 + ch / 100) else null)
    }

    data class Flow(val who: String, val buy: Double, val sell: Double, val net: Double, val date: String)
    fun fiiDii(json: String): List<Flow> = J.arr(json).mapNotNull { o -> Flow(o.s("category"), o.d("buyValue") ?: 0.0, o.d("sellValue") ?: 0.0, o.d("netValue") ?: return@mapNotNull null, o.s("date")) }

    /** Readable sector names: "NIFTY FINANCIAL SERVICES 25/50" → "Financial Services 25/50". */
    fun short(index: String) = index.removePrefix("NIFTY ").removePrefix("Nifty ").removeSuffix(" INDEX").lowercase().split(' ').joinToString(" ") { w ->
        when (w) { "it", "psu", "fmcg", "ex-bank", "reits", "bank" -> w.uppercase().replace("BANK", "Bank"); else -> w.replaceFirstChar { it.uppercase() } }
    }.replace("IT &", "IT &").replace("Oil & gas", "Oil & Gas")

    /** The headline sectors, in the order people look for them. */
    val SECTORS = listOf("NIFTY BANK", "NIFTY IT", "NIFTY AUTO", "NIFTY PHARMA", "NIFTY FMCG", "NIFTY METAL", "NIFTY REALTY", "NIFTY ENERGY", "NIFTY PSU BANK", "NIFTY PRIVATE BANK",
        "NIFTY FINANCIAL SERVICES", "NIFTY MEDIA", "NIFTY OIL & GAS", "NIFTY HEALTHCARE INDEX", "NIFTY CONSUMER DURABLES", "NIFTY CHEMICALS", "NIFTY CEMENT")

    fun heatmapPath(index: String) = "NextApi/apiClient/indexTrackerApi?functionName=getIndicesHeatMap&&index=" + com.khayali.pulao.ai.WebData.encode(index).replace("+", "%20")
}

/**
 * /report — the whole market on one card: index tape, key figures, breadth, sectors ranked,
 * Nifty 50 gainers and losers with sparklines, institutional flows, your stocks, and a written
 * read-out where every figure comes from the feeds.
 */
object MarketReportFeature : Feature {
    override val id = "report"
    override val usage = "/report"
    override val help = "Market report: indices, breadth, sectors, top gainers and losers, FII/DII flows, your stocks — one card"
    override val group = Group.MARKET
    override val triggers = listOf(Regex("""(?i)^(market (report|summary|today|update|overview)|how(?:'s| is) the market( (doing|today))?\??|what(?:'s| is) happening in the market\??)$"""))

    override suspend fun run(arg: String, f: Fetch): String = coroutineScope {
        val idx = async { f.nse("allIndices") }
        val gain = async { f.nse("live-analysis-variations?index=gainers") }
        val lose = async { f.nse("live-analysis-variations?index=loosers") }
        val flows = async { f.nse("fiidiiTradeReact") }
        val status = async { f.nse("marketStatus") }
        val mine = (f.holdings.map { it.name } + f.watched).map { it.uppercase() }.distinct().take(8)
        val g = Nse.variations(gain.await()); val l = Nse.variations(lose.await())
        val sparkSyms = listOf("^NSEI", "^NSEBANK", "^BSESN", "USDINR=X", "GC=F") + (g.take(5) + l.take(5)).map { Sparks.feed(it.symbol) } + mine.map { Sparks.feed(it) }
        val sparks = f.spark(sparkSyms.distinct())
        build(idx.await(), g, l, flows.await(), status.await(), sparks, mine, f.holdings.map { it.name.uppercase() }.toSet())
    }

    private fun spark(sp: Map<String, Spark>, sym: String) = sp[Sparks.feed(sym)]

    /** Pure: every feed's text (or parsed list) → the report card. */
    fun build(indicesJson: String, gainers: List<Nse.Move>, losers: List<Nse.Move>, flowsJson: String, statusJson: String,
              sp: Map<String, Spark>, mine: List<String>, holdings: Set<String>): String {
        val idx = Nse.indices(indicesJson)
        val n50 = idx.firstOrNull { it.name == "NIFTY 50" } ?: return Card.error("Couldn't read NSE's index data right now. Try again in a moment.")
        fun i(n: String) = idx.firstOrNull { it.name == n }
        val bank = i("NIFTY BANK"); val vix = i("INDIA VIX"); val mid = i("NIFTY MIDCAP 100"); val small = i("NIFTY SMALLCAP 100"); val n500 = i("NIFTY 500")
        val sectors = Nse.SECTORS.mapNotNull { n -> i(n) }.sortedByDescending { it.change }
        val state = J.obj(statusJson)?.a("marketState")?.firstOrNull { it.s("market") == "Capital Market" }
        val open = state?.s("marketStatus")?.equals("Open", true) == true
        val asOf = state?.s("tradeDate").orEmpty()
        val flows = Nse.fiiDii(flowsJson)
        val fii = flows.firstOrNull { it.who.startsWith("FII") }; val dii = flows.firstOrNull { it.who.startsWith("DII") }
        val sensex = sp["^BSESN"]; val usd = sp["USDINR=X"]; val gold = sp["GC=F"]

        val card = Card("Market report · ${if (open) "live" else "last close"}${if (asOf.isNotBlank()) " · $asOf" else ""}", if (n50.change >= 0) "green" else "red")
            .headline("Nifty 50 " + N.num(n50.last), "${N.pct(n50.change, 2)} today · Bank Nifty ${bank?.let { N.pct(it.change, 2) } ?: "—"} · VIX ${vix?.let { N.num(it.last) } ?: "—"}", N.tone(n50.change))
            .tape(listOfNotNull(
                Card.Tick("NIFTY", N.num(n50.last), n50.change), bank?.let { Card.Tick("BANKNIFTY", N.num(it.last), it.change) },
                sensex?.last?.let { Card.Tick("SENSEX", N.num(it), sensex.change) }, mid?.let { Card.Tick("MIDCAP 100", N.num(it.last), it.change) },
                small?.let { Card.Tick("SMALLCAP 100", N.num(it.last), it.change) }, vix?.let { Card.Tick("INDIA VIX", N.num(it.last), it.change) },
                usd?.last?.let { Card.Tick("USD/INR", N.num(it), usd.change) }, gold?.last?.let { Card.Tick("GOLD $/oz", N.num(it, 0), gold.change) }) +
                sectors.take(3).map { Card.Tick(Nse.short(it.name).uppercase(), N.num(it.last, 0), it.change) })
            .kpis(listOfNotNull(
                Card.Kpi("NIFTY 50", N.num(n50.last), n50.change, "P/E ${n50.pe}"), bank?.let { Card.Kpi("BANK NIFTY", N.num(it.last), it.change) },
                vix?.let { Card.Kpi("INDIA VIX", N.num(it.last), it.change, "fear gauge") },
                mid?.let { Card.Kpi("MIDCAP 100", N.num(it.last, 0), it.change) }, small?.let { Card.Kpi("SMALLCAP 100", N.num(it.last, 0), it.change) },
                usd?.last?.let { Card.Kpi("USD / INR", N.num(it), usd.change) }))
        n500?.let { if (it.up != null && it.down != null) card.breadth("Breadth · Nifty 500", it.up, it.down, (500 - it.up - it.down).coerceAtLeast(0)) }
        if (sectors.isNotEmpty()) card.bars("Sectors today", sectors.map { Card.Bar(Nse.short(it.name), it.change, it.m1?.let { m -> "1 month ${N.pct(m)}" }.orEmpty()) })
        fun movers(list: List<Nse.Move>) = list.take(5).map { m -> val s = spark(sp, m.symbol)
            Card.Mover(m.symbol, m.note, m.price, m.change, s?.closes.orEmpty(), s?.prevClose ?: m.prev, m.symbol.uppercase() in holdings) }
        card.movers("Top gainers · Nifty 50", movers(gainers), "up").movers("Top losers · Nifty 50", movers(losers), "down")
        if (fii != null || dii != null) card.section("Institutional flows · ${fii?.date ?: dii?.date}").kpis(listOfNotNull(
            fii?.let { Card.Kpi("FII / FPI NET", N.cr(it.net), null, "bought ${N.cr(it.buy)} · sold ${N.cr(it.sell)}") },
            dii?.let { Card.Kpi("DII NET", N.cr(it.net), null, "bought ${N.cr(it.buy)} · sold ${N.cr(it.sell)}") }))
        val yours = mine.mapNotNull { sym -> spark(sp, sym)?.let { s -> Card.Mover(sym, if (sym in holdings) "your holding" else "you looked at it", s.last, s.change, s.closes, s.prevClose, sym in holdings) } }
        card.movers("Your stocks", yours)

        // The read-out: plain sentences, every figure from the data above.
        val best = sectors.firstOrNull(); val worst = sectors.lastOrNull()
        val read = buildString {
            append("Nifty 50 ${if (open) "is" else "closed"} at ${N.num(n50.last)} (${N.pct(n50.change, 2)})")
            bank?.let { append(", Bank Nifty ${N.pct(it.change, 2)}") }; append(". ")
            if (n500?.up != null && n500.down != null) append("Breadth is ${if (n500.up > n500.down) "positive" else if (n500.down > n500.up) "negative" else "even"}: ${n500.up} of the Nifty 500 up, ${n500.down} down. ")
            if (best != null && worst != null && best != worst) append("${Nse.short(best.name)} leads (${N.pct(best.change, 2)}); ${Nse.short(worst.name)} lags (${N.pct(worst.change, 2)}). ")
            gainers.firstOrNull()?.let { append("Top Nifty gainer ${it.symbol} ${N.pct(it.change, 2)}") }; losers.firstOrNull()?.let { append(", top loser ${it.symbol} ${N.pct(it.change, 2)}") }; append(". ")
            if (fii != null && dii != null) append("On ${fii.date}, FIIs were net ${if (fii.net >= 0) "buyers" else "sellers"} of ${N.cr(kotlin.math.abs(fii.net))} and DIIs net ${if (dii.net >= 0) "buyers" else "sellers"} of ${N.cr(kotlin.math.abs(dii.net))}. ")
            vix?.let { append("India VIX is ${N.num(it.last)}") ; append(if (it.last < 13) ", low — the market expects calm" else if (it.last > 20) ", high — the market expects big swings" else ", in its usual range"); append(". ") }
            append("Source: NSE${if (sp.isNotEmpty()) " and Yahoo Finance (prices may be delayed)" else ""}.")
        }
        return card.sources("NSE", "Yahoo Finance").pills("/sectormovers", "/heatmap", "/preopen", "/watch", "/calendar").put("nifty", n50.last).put("nifty_change", n50.change).json(read)
    }
}

/** /sectormovers — every sector's move, with its top gainers and losers today. */
object SectorMoversFeature : Feature {
    override val id = "sectormovers"
    override val usage = "/sectormovers"
    override val help = "Sector-wise top gainers and losers: each sector's move with its best and worst stocks"
    override val group = Group.MARKET
    override val triggers = listOf(Regex("""(?i)\bsector(?:-| )?wise\b|\b(gainers|losers) (by|in each|per) sector"""))
    private val SECTORS = listOf("NIFTY BANK", "NIFTY IT", "NIFTY AUTO", "NIFTY PHARMA", "NIFTY FMCG", "NIFTY METAL", "NIFTY REALTY", "NIFTY ENERGY", "NIFTY PSU BANK", "NIFTY MEDIA")

    override suspend fun run(arg: String, f: Fetch): String = coroutineScope {
        val idx = async { f.nse("allIndices") }
        // a few at a time: each sector is one page load
        val members = SECTORS.chunked(4).flatMap { batch -> batch.map { s -> async { s to f.nse(Nse.heatmapPath(s)) } }.map { it.await() } }.toMap()
        build(idx.await(), members, f.holdings.map { it.name.uppercase() }.toSet() + f.watched)
    }

    fun build(indicesJson: String, members: Map<String, String>, mine: Set<String> = emptySet()): String {
        val idx = Nse.indices(indicesJson).associateBy { it.name }
        val rows = members.mapNotNull { (sector, json) ->
            val m = Nse.constituents(json).sortedByDescending { it.change }
            if (m.isEmpty()) null else Triple(sector, idx[sector]?.change ?: m.map { it.change }.average(), m)
        }.sortedByDescending { it.second }
        if (rows.isEmpty()) return Card.error("Couldn't read sector data from NSE right now. Try again in a moment.")
        val up = rows.count { it.second > 0 }
        val card = Card("Sector movers · ${rows.size} sectors", if (up * 2 >= rows.size) "green" else "red")
            .headline("$up of ${rows.size} sectors up", "Best ${Nse.short(rows.first().first)} ${N.pct(rows.first().second, 2)} · worst ${Nse.short(rows.last().first)} ${N.pct(rows.last().second, 2)}", N.tone(rows.sumOf { it.second }))
            .bars("Sectors ranked", rows.map { Card.Bar(Nse.short(it.first), it.second, "${it.third.count { s -> s.change > 0 }} of ${it.third.size} stocks up") })
        for ((sector, ch, m) in rows) {
            val best = m.take(2); val worst = m.takeLast(2).reversed().filter { it !in best }
            card.movers("${Nse.short(sector)} ${N.pct(ch, 2)}", (best + worst).map { s -> Card.Mover(s.symbol, if (s in best) "leading" else "lagging", s.price, s.change, emptyList(), null, s.symbol.uppercase() in mine) }, N.tone(ch))
        }
        val read = "${rows.size} sectors: $up up. ${Nse.short(rows.first().first)} leads at ${N.pct(rows.first().second, 2)} (best stock ${rows.first().third.first().symbol} ${N.pct(rows.first().third.first().change, 2)}); " +
            "${Nse.short(rows.last().first)} lags at ${N.pct(rows.last().second, 2)} (weakest ${rows.last().third.last().symbol} ${N.pct(rows.last().third.last().change, 2)}). Source: NSE."
        return card.sources("NSE").pills("/report", "/heatmap NIFTY ${rows.first().first.removePrefix("NIFTY ")}", "/heatmap NIFTY ${rows.last().first.removePrefix("NIFTY ")}", "/preopen").json(read)
    }
}

/** /preopen — the 9:00–9:08 pre-open session for F&O stocks: indicative opening prices, breadth, biggest gaps. */
object PreopenFeature : Feature {
    override val id = "preopen"
    override val usage = "/preopen"
    override val help = "Pre-open session: indicative opening prices, gap-ups and gap-downs in F&O stocks"
    override val group = Group.MARKET
    override val triggers = listOf(Regex("""(?i)\bpre[- ]?open(ing)?\b|\bgap (up|down)s?\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(f.nse("market-data-pre-open?key=FO"), f.holdings.map { it.name.uppercase() }.toSet() + f.watched)

    fun build(json: String, mine: Set<String> = emptySet()): String {
        val o = J.obj(json)
        val rows = J.arr(json, "data").mapNotNull { r -> r.o("metadata")?.let { m ->
            Nse.Move(m.s("symbol"), m.d("iep")?.takeIf { it > 0 } ?: m.d("lastPrice") ?: return@mapNotNull null, m.d("pChange") ?: return@mapNotNull null, m.d("previousClose"),
                m.d("finalQuantity")?.let { q -> "${com.khayali.pulao.Fmt.amount(q)} shares matched" }.orEmpty()) } }
        if (o == null || rows.isEmpty()) return Card.error("No pre-open data right now. NSE's pre-open runs 9:00–9:08 IST on trading days; the figures stay up until the next session.")
        val up = o.d("advances")?.toInt() ?: rows.count { it.change > 0 }; val down = o.d("declines")?.toInt() ?: rows.count { it.change < 0 }; val flat = o.d("unchanged")?.toInt() ?: 0
        val sorted = rows.sortedByDescending { it.change }
        val when_ = o.s("timestamp")
        fun mv(m: Nse.Move) = Card.Mover(m.symbol, m.note, m.price, m.change, emptyList(), m.prev, m.symbol.uppercase() in mine)
        val avg = rows.map { it.change }.average()
        val yours = rows.filter { it.symbol.uppercase() in mine }
        return Card("Pre-open · F&O stocks${if (when_.isNotBlank()) " · $when_" else ""}", if (up >= down) "green" else "red")
            .headline("$up gap up · $down gap down", "Average indicated move ${N.pct(avg, 2)} across ${rows.size} stocks", N.tone(avg))
            .breadth("Pre-open breadth", up, down, flat)
            .bars("Opening move, spread", listOf(-3.0 to "below −2%", -1.5 to "−2% to −1%", -0.5 to "−1% to 0", 0.5 to "0 to +1%", 1.5 to "+1% to +2%", 3.0 to "above +2%").map { (mid, label) ->
                Card.Bar(label, rows.count { r -> when { mid < -2 -> r.change < -2; mid < -1 -> r.change in -2.0..<-1.0; mid < 0 -> r.change in -1.0..<0.0; mid < 1 -> r.change in 0.0..<1.0; mid < 2 -> r.change in 1.0..<2.0; else -> r.change >= 2 } }.toDouble() * (if (mid < 0) -1 else 1))
            }, "stocks")
            .movers("Biggest gap-ups", sorted.take(5).map(::mv), "up").movers("Biggest gap-downs", sorted.takeLast(5).reversed().map(::mv), "down")
            .movers("Your stocks in pre-open", yours.map(::mv))
            .note("Indicative equilibrium prices (IEP) from the call auction; the market opens at 9:15 and prices can move away from these.")
            .sources("NSE").pills("/report", "/sectormovers", "/heatmap")
            .json("Pre-open (F&O stocks): $up indicated up and $down down, average ${N.pct(avg, 2)}. Biggest gap-up ${sorted.first().symbol} ${N.pct(sorted.first().change, 2)}, biggest gap-down ${sorted.last().symbol} ${N.pct(sorted.last().change, 2)}. Source: NSE.")
    }
}

/** /watch — stocks you've looked at in chat and your holdings, with today's sparkline and move. */
object WatchFeature : Feature {
    override val id = "watch"
    override val usage = "/watch"
    override val help = "Your watch list: stocks you've looked at in chat plus your holdings, with sparklines"
    override val group = Group.PORTFOLIO
    override val triggers = listOf(Regex("""(?i)^(my )?(watch ?list|stocks i (looked at|watch(ed)?))\??$"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val held = f.holdings.map { it.name.uppercase() }
        val syms = (f.watched + held).map { it.uppercase() }.distinct().take(20)
        if (syms.isEmpty()) return Card.error("Nothing to watch yet. Look at a stock first — e.g. /price INFY or /chart TCS — and it will appear here.")
        return build(syms, f.spark(syms.map { Sparks.feed(it) }), held.toSet())
    }

    fun build(syms: List<String>, sp: Map<String, Spark>, held: Set<String>): String {
        val rows = syms.mapNotNull { s -> sp[Sparks.feed(s)]?.let { k -> s to k } }
        if (rows.isEmpty()) return Card.error("Couldn't get prices for your watch list right now.")
        val ch = rows.mapNotNull { it.second.change }
        val up = ch.count { it > 0 }; val down = ch.count { it < 0 }
        val sorted = rows.sortedByDescending { it.second.change ?: -999.0 }
        return Card("Watch list · ${rows.size} stocks", if (up >= down) "green" else "red")
            .headline("$up up · $down down", "Best ${sorted.first().first} ${sorted.first().second.change?.let { N.pct(it, 2) } ?: "—"} · worst ${sorted.last().first} ${sorted.last().second.change?.let { N.pct(it, 2) } ?: "—"}", N.tone(ch.sum()))
            .tape(rows.map { (s, k) -> Card.Tick(s, k.last?.let { N.num(it) } ?: "—", k.change) })
            .movers(null, sorted.map { (s, k) -> Card.Mover(s, if (s in held) "your holding" else "you looked at it", k.last, k.change, k.closes, k.prevClose, s in held) })
            .note("Stocks you've asked about in chat are remembered on this phone (up to ${com.khayali.pulao.ai.WatchStore.MAX}); prices may be delayed.")
            .sources("Yahoo Finance").pills("/report", "/chart ${sorted.first().first} 1m", "/events ${sorted.first().first}", "/sectormovers")
            .json("Your watch list: $up of ${rows.size} up today. Best ${sorted.first().first} ${sorted.first().second.change?.let { N.pct(it, 2) } ?: "—"}, worst ${sorted.last().first} ${sorted.last().second.change?.let { N.pct(it, 2) } ?: "—"}. Source: Yahoo Finance (may be delayed).")
    }
}
