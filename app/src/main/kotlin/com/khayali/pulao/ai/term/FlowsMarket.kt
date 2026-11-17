package com.khayali.pulao.ai.term

import com.google.gson.JsonObject
import com.khayali.pulao.ai.term.J.a
import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.s

/** /fiidii — what foreign (FII/FPI) and domestic (DII) institutions bought and sold in the cash market on the latest day. */
object FlowsFiiDiiFeature : Feature {
    override val id = "fiidii"
    override val usage = "/fiidii"
    override val help = "FII/FPI and DII buying, selling and net flow in the cash market for the latest day"
    override val group = Group.MARKET
    override val triggers = listOf(Regex("""(?i)\b(fii|fpi|dii)s?\b"""), Regex("""(?i)\b(foreign|institutional) (investors?|flows?|buying|selling)\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(f.nse("fiidiiTradeReact"))

    private class Side(val buy: Double, val sell: Double, val net: Double, val date: String)

    /** Pure: feed JSON → card. */
    fun build(json: String): String {
        val rows = J.arr(json)
        fun side(tag: String) = rows.firstOrNull { it.s("category").uppercase().contains(tag) }?.let { o ->
            val b = o.d("buyValue"); val s = o.d("sellValue")
            if (b == null || s == null) null else Side(b, s, o.d("netValue") ?: (b - s), o.s("date"))
        }
        val fii = side("FII") ?: side("FPI"); val dii = side("DII")
        if (fii == null && dii == null) return Card.error("NSE has not published FII/DII figures right now. They usually appear in the evening after a trading day — try again later.")
        val day = Flows.date((fii ?: dii)!!.date)?.let { Flows.show(it) } ?: (fii ?: dii)!!.date
        val both = listOfNotNull(fii?.let { "FII/FPI" to it }, dii?.let { "DII" to it })
        val combined = both.sumOf { it.second.net }
        fun verb(v: Double) = if (v >= 0) "net bought" else "net sold"
        val lead = both.first()
        val card = Card("FII / DII · cash market · $day", if (lead.second.net >= 0) "green" else "red")
            .headline("${lead.first} ${Flows.signedCr(lead.second.net)}",
                both.joinToString(" · ") { (k, v) -> "$k ${verb(v.net)} ${N.cr(kotlin.math.abs(v.net))}" }, N.tone(lead.second.net))
            .viz("groupbars", 200, mapOf("unit" to "₹ Cr", "xLabel" to "Investor group", "series" to listOf("Bought", "Sold"),
                "items" to both.map { (k, v) -> mapOf("label" to k, "values" to listOf(v.buy, v.sell)) }),
                "Shares bought and sold by each group on $day, in ₹ crore.")
            .viz("netbars", 190, mapOf("unit" to "₹ Cr", "signed" to true, "yLabel" to "Net bought (₹ Cr)", "xLabel" to "Investor group",
                "items" to (both.map { (k, v) -> mapOf("label" to k, "value" to v.net) } + if (both.size > 1) listOf(mapOf("label" to "Combined", "value" to Flows.round(combined))) else emptyList())),
                "Bought minus sold, in ₹ crore. Above zero = net buying, below zero = net selling.")
            .stats(*(both.flatMap { (k, v) -> listOf("$k bought" to N.cr(v.buy), "$k sold" to N.cr(v.sell)) } + if (both.size > 1) listOf("Combined net" to Flows.signedCr(combined)) else emptyList()).toTypedArray())
            .note("Provisional figures as published by NSE for the cash market only; derivatives are not included and the numbers can be revised. One day's flow does not say where prices go next.")
            .sources("NSE").pills("/deals", "/indices", "/breadth", "/sectors")
            .put("date", day)
        return card.json("On $day " + both.joinToString("; ") { (k, v) -> "$k bought ${N.cr(v.buy)} and sold ${N.cr(v.sell)} (net ${Flows.signedCr(v.net)})" } +
            ". Provisional cash-market figures. Source: NSE.")
    }
}

/** /deals [symbol] — bulk and block deals: who bought or sold what, how many shares and at what price. */
object FlowsDealsFeature : Feature {
    override val id = "deals"
    override val usage = "/deals INFY"
    override val help = "Bulk and block deals: who bought or sold, quantity and price — all, or for one stock"
    override val group = Group.MARKET
    override val triggers = listOf(Regex("""(?i)\b(bulk|block|large) deals?\b"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val symbol = arg.trim().takeIf { it.isNotEmpty() }?.let { f.symbol(it) ?: it.removePrefix("@").uppercase() }
        return build(f.nse("snapshot-capital-market-largedeal"), f.nse("block-deal"), symbol)
    }

    class Deal(val kind: String, val symbol: String, val client: String, val buy: Boolean, val qty: Double, val price: Double, val date: String) { val value get() = qty * price }

    /** Pure: the large-deal snapshot and the block-deal window feed → card. [symbol] narrows it to one stock. */
    fun build(largeJson: String, blockJson: String, forSymbol: String? = null): String {
        val symbol = forSymbol?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val root = J.obj(largeJson)
        fun deals(key: String, kind: String) = root?.a(key).orEmpty().mapNotNull { o ->
            val q = o.d("qty"); val p = o.d("watp"); val side = o.s("buySell").uppercase()
            if (q == null || p == null || side.isEmpty()) null else Deal(kind, o.s("symbol"), o.s("clientName"), side.startsWith("B"), q, p, o.s("date"))
        }
        fun mine(s: String) = symbol == null || s.equals(symbol, ignoreCase = true)
        val bulk = deals("BULK_DEALS_DATA", "Bulk").filter { mine(it.symbol) }; val block = deals("BLOCK_DEALS_DATA", "Block").filter { mine(it.symbol) }
        val shorts = root?.a("SHORT_DEALS_DATA").orEmpty().filter { mine(it.s("symbol")) }
        val window = J.arr(blockJson, "data").filter { mine(it.s("symbol")) && (it.d("totalTradedValue") ?: 0.0) > 0 }.sortedByDescending { it.d("totalTradedValue") }
        val all = (bulk + block).sortedByDescending { it.value }
        if (all.isEmpty() && window.isEmpty()) return Card.error(
            if (root == null && J.obj(blockJson) == null) "Couldn't read NSE's bulk and block deal lists right now. Try again in a minute."
            else if (symbol != null) "No bulk or block deals are listed for $symbol in NSE's latest snapshot. Try /deals for every stock."
            else "NSE's latest snapshot lists no bulk or block deals.")
        val asOn = root?.s("as_on_date").orEmpty().let { s -> Flows.date(s)?.let { Flows.show(it) } ?: s }.ifEmpty { Flows.stamp(J.obj(blockJson)?.s("timestamp").orEmpty()) }
        val bought = all.filter { it.buy }.sumOf { it.value }; val sold = all.filter { !it.buy }.sumOf { it.value }
        fun day(s: String) = Flows.date(s)?.let { Flows.show(it) } ?: s
        fun line(d: Deal) = "${d.kind} · ${if (d.buy) "bought" else "sold"} ${Flows.count(d.qty)} @ ₹${N.num(d.price)} · ${day(d.date)}"

        val card = Card(listOfNotNull(symbol, "bulk & block deals", asOn.takeIf { it.isNotEmpty() }?.let { "as on $it" }).joinToString(" · "), "violet")
        if (all.isNotEmpty()) {
            card.headline("${bulk.size} bulk · ${block.size} block", "Disclosed buying ${Flows.rupees(bought)} · selling ${Flows.rupees(sold)}" +
                (if (symbol == null) " · ${all.map { it.symbol }.distinct().size} stocks" else ""))
            // net of disclosed buying and selling, by stock (or by client when one stock is asked for)
            val groups = all.groupBy { if (symbol == null) it.symbol else it.client }.map { (k, v) -> Triple(k, v.sumOf { if (it.buy) it.value else -it.value }, v.sumOf { it.value }) }
                .sortedByDescending { it.third }.take(8).sortedByDescending { it.second }
            card.viz("netbars", 60 + groups.size * 26, mapOf("unit" to "₹ Cr", "signed" to true, "horizontal" to true, "yLabel" to if (symbol == null) "Stock" else "Client",
                "xLabel" to "Disclosed buying minus selling (₹ Cr)",
                "items" to groups.map { (k, net, gross) -> mapOf("label" to Flows.clip(k, 15), "value" to Flows.round(net / 1e7), "note" to "$k · deals worth ${Flows.rupees(gross)}") }),
                (if (symbol == null) "The ${groups.size} stocks with the largest deal value" else "Clients named in $symbol deals") + ": disclosed buying minus disclosed selling, ₹ crore. Tap a bar.")
            card.rows("Largest deals", all.take(if (window.isEmpty()) 8 else 6).map { d -> Card.Row((if (symbol == null) d.symbol + " · " else "") + Flows.clip(d.client, 34), line(d), Flows.rupees(d.value), if (d.buy) "up" else "down") })
        } else card.headline("${window.size} in the block window", "No bulk or block deal disclosures in the latest snapshot" + (symbol?.let { " for $it" } ?: ""))
        card.rows("Block-deal window (latest session data)", window.take(4).map { o ->
            Card.Row(o.s("symbol"), "${Flows.count(o.d("totalTradedVolume") ?: 0.0)} shares @ ₹${N.num(o.d("lastPrice") ?: 0.0)} · ${o.s("session")}", Flows.rupees(o.d("totalTradedValue") ?: 0.0), N.tone(o.d("pchange") ?: 0.0)) })
        card.stats(*listOfNotNull(
            "Bulk deals" to bulk.size.toString(), "Block deals" to block.size.toString(),
            shorts.takeIf { it.isNotEmpty() }?.let { s -> if (symbol == null) "Short-selling reports" to "${s.size} stocks" else "Short selling reported" to "${Flows.count(s.sumOf { it.d("qty") ?: 0.0 })} shares" }).toTypedArray())
        card.note("A bulk deal is one client trading more than 0.5% of a company's shares in a day; a block deal is a single large trade in a separate window. Only disclosed clients are counted, so buying and selling need not match. A deal shows who traded, not why.")
        val top = all.firstOrNull()
        card.sources("NSE").put("as_on", asOn)
        if (symbol != null) { card.pills("/insider $symbol", "/shareholding $symbol", "/filings $symbol", "/deals"); card.put("symbol", symbol) }
        else card.pills(*listOfNotNull(top?.let { "/deals ${it.symbol}" }, "/fiidii", "/oispurts", "/movers").toTypedArray())
        val what = (symbol?.let { "$it: as" } ?: "As").let { if (asOn.isNotEmpty()) "$it on $asOn " else it.removeSuffix("as").removeSuffix("As") } + "NSE lists "
        return card.json(if (top != null) what + "${bulk.size} bulk and ${block.size} block deals; disclosed buying ${Flows.rupees(bought)}, selling ${Flows.rupees(sold)}. " +
            "Largest: ${top.symbol} — ${top.client} ${if (top.buy) "bought" else "sold"} ${Flows.count(top.qty)} shares at ₹${N.num(top.price)} (${Flows.rupees(top.value)}). Source: NSE."
        else what + "no bulk or block deal disclosures; ${window.size} ${if (window.size == 1) "stock" else "stocks"} traded in the block-deal window, the largest ${window.first().s("symbol")} (${Flows.rupees(window.first().d("totalTradedValue") ?: 0.0)}). Source: NSE.")
    }
}

/** /oispurts — derivatives underlyings whose open interest changed the most against the previous trading day. */
object FlowsOiSpurtsFeature : Feature {
    override val id = "oispurts"
    override val usage = "/oispurts"
    override val help = "Open-interest spurts: derivatives underlyings with the biggest change in open interest"
    override val group = Group.DERIVATIVES
    override val triggers = listOf(Regex("""(?i)\b(oi|open interest) (spurts?|build ?up|change|gainers)\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(f.nse("live-analysis-oi-spurts-underlyings"))

    /** Pure: feed JSON → card. */
    fun build(json: String): String {
        class U(val o: JsonObject, val symbol: String, val pct: Double, val latest: Double, val prev: Double)
        val rows = J.arr(json, "data").mapNotNull { o -> val p = o.d("avgInOI"); val l = o.d("latestOI"); val pr = o.d("prevOI")
            if (p == null || l == null || pr == null || o.s("symbol").isEmpty()) null else U(o, o.s("symbol"), p, l, pr) }.sortedByDescending { it.pct }
        if (rows.isEmpty()) return Card.error("No open-interest data from NSE right now. It is published while the derivatives market is open and after the close.")
        val root = J.obj(json)
        val asOf = Flows.stamp(root?.s("timestamp").orEmpty())
        val up = rows.count { it.pct > 0 }; val down = rows.count { it.pct < 0 }
        val top = rows.first(); val bottom = rows.last()
        val rises = rows.filter { it.pct > 0 }.take(6); val falls = rows.filter { it.pct < 0 }.takeLast(3).filter { f -> rises.none { it.symbol == f.symbol } }
        val shown = (rises + falls).ifEmpty { rows.take(8) }
        fun oi(u: U) = "OI ${Flows.count(u.latest)}, previous ${Flows.count(u.prev)}"
        return Card("Open-interest spurts" + (if (asOf.isNotEmpty()) " · $asOf" else ""), "saffron")
            .headline("${top.symbol} ${N.pct(top.pct)}", "largest change in open interest · $up of ${rows.size} underlyings rose, $down fell")
            .viz("netbars", 60 + shown.size * 26, mapOf("unit" to "%", "signed" to true, "horizontal" to true, "color" to "saffron", "yLabel" to "Underlying", "xLabel" to "Change in open interest (%)",
                "items" to shown.map { u -> mapOf("label" to u.symbol, "value" to u.pct, "note" to oi(u)) }),
                "Change in open interest against the previous trading day, in %: the largest rises" + (if (falls.isNotEmpty()) " and the largest falls" else "") + ". One colour on purpose: more open interest is neither good nor bad. Tap a bar.")
            .rows("Largest rises", rises.take(5).map { u -> Card.Row(u.symbol, oi(u) + (u.o.d("underlyingValue")?.let { " · underlying ₹${N.num(it, 0)}" } ?: ""), N.pct(u.pct)) })
            .rows("Largest falls", falls.reversed().map { u -> Card.Row(u.symbol, oi(u), N.pct(u.pct)) })
            .note("Open interest is the number of futures and options contracts still open. A jump means new positions were opened; it does not show whether they expect a rise or a fall. Figures are as of the time shown and may be delayed.")
            .sources("NSE").pills("/optionchain ${top.symbol}", "/pcr", "/quote ${top.symbol}", "/movers")
            .put("as_of", asOf)
            .json("Open-interest spurts" + (if (asOf.isNotEmpty()) " as of $asOf" else "") + ": largest rise ${top.symbol} ${N.pct(top.pct)} (${oi(top)})" +
                (if (bottom.pct < 0) ", largest fall ${bottom.symbol} ${N.pct(bottom.pct)}" else "") + "; $up of ${rows.size} underlyings saw open interest rise. Source: NSE.")
    }
}
