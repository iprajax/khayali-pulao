package com.khayali.pulao.ai.term

import com.khayali.pulao.Stock

// Portfolio features that need nothing but the user's holdings: they work offline and send nothing anywhere.

/** /xray — allocation as a sunburst (portfolio → gainers / losers → stock) and how concentrated it is. */
object FolioXrayFeature : Feature {
    override val id = "xray"
    override val usage = "/xray"
    override val help = "Portfolio X-ray: allocation sunburst, largest weights and how concentrated your holdings are"
    override val group = Group.PORTFOLIO
    override val needsWeb = false
    override val triggers = listOf(Regex("""(?i)\b(x-?ray|portfolio (allocation|concentration)|how (concentrated|diversified))\b"""))
    private const val MAX_SLICES = 12

    override suspend fun run(arg: String, f: Fetch): String = build(f.holdings)

    /** Pure: holdings → card. */
    fun build(holdings: List<Stock.Holding>): String {
        val pos = Folio.positions(holdings)
        if (pos.isEmpty()) return Card.error(Folio.CONNECT)
        val total = pos.sumOf { it.h.current }
        val hhi = pos.sumOf { it.weight * it.weight }
        val effective = 1 / hhi
        val top3 = pos.take(3).sumOf { it.weight } * 100
        val big = pos.first()
        fun slices(list: List<Folio.Pos>): List<Map<String, Any>> {
            val shown = if (list.size > MAX_SLICES) list.take(MAX_SLICES - 1) else list
            val rest = list.drop(shown.size)
            return shown.map { mapOf("name" to it.sym, "value" to Folio.r2(it.h.current), "note" to "P&L ${Folio.signed(it.h.pnl)} (${N.pct(it.h.pnlPct)})") } +
                (if (rest.isEmpty()) emptyList() else listOf(mapOf("name" to "${rest.size} others", "value" to Folio.r2(rest.sumOf { it.h.current }), "note" to "P&L ${Folio.signed(rest.sumOf { it.h.pnl })}")))
        }
        val gainers = pos.filter { it.h.pnl >= 0 }; val losers = pos.filter { it.h.pnl < 0 }
        val groups = listOfNotNull(
            gainers.takeIf { it.isNotEmpty() }?.let { mapOf("name" to "In profit", "color" to "green", "children" to slices(it)) },
            losers.takeIf { it.isNotEmpty() }?.let { mapOf("name" to "In loss", "color" to "red", "children" to slices(it)) })
        val gainW = gainers.sumOf { it.weight } * 100
        return Card("Your portfolio · X-ray · ${pos.size} holdings", "violet")
            .headline("${Folio.f1(effective)} effective stocks", "${pos.size} holdings worth ${Folio.short(total)} · largest ${big.sym} ${Folio.p(big.weight * 100)} · top 3 ${Folio.p(top3)}")
            .viz("sunburst", 330, mapOf("centre" to "Portfolio value", "unit" to "₹", "inner" to "in profit / in loss", "outer" to "stock", "measure" to "share of portfolio value",
                "root" to mapOf("name" to "Portfolio", "children" to groups)),
                "Inner ring: holdings in profit and in loss. Outer ring: each stock; arc length = its share of today's value (₹). Tap a segment to read it.")
            .stats("Largest holding" to "${big.sym} ${Folio.p(big.weight * 100)}", "Top 3 together" to Folio.p(top3),
                "Herfindahl index" to Folio.f2(hhi), "Effective stocks" to Folio.f1(effective),
                "In profit (by value)" to Folio.p(gainW), "In loss (by value)" to Folio.p(100 - gainW))
            .rows("Weights", pos.take(8).map { Card.Row(it.sym, Folio.short(it.h.current), Folio.p(it.weight * 100), "") })
            .note("The Herfindahl index adds up the squared weights: 1.00 means everything in one stock, ${Folio.f2(1.0 / pos.size)} would be ${pos.size} equal holdings. " +
                "\"Effective stocks\" is its inverse: the number of equally weighted stocks that would be just as concentrated. It says nothing about how alike the stocks are — see /correlation.")
            .sources("Your Kite holdings (on this phone)").pills("/pnlflow", "/rebalance", "/risk", "/foliomap")
            .put("holdings", pos.size).put("largest", big.sym)
            .json("Your ${pos.size} holdings are worth ${Folio.short(total)}. Largest is ${big.sym} at ${Folio.p(big.weight * 100)}; the top 3 make up ${Folio.p(top3)}. " +
                "Herfindahl index ${Folio.f2(hhi)}, equal to ${Folio.f1(effective)} equally weighted stocks. Source: your Kite holdings.")
    }
}

/** /pnlflow — the portfolio as a Sankey: money invested (plus gains) into each stock, out to today's value or losses. */
object FolioPnlFlowFeature : Feature {
    override val id = "pnlflow"
    override val usage = "/pnlflow"
    override val help = "Portfolio money-flow (Sankey): what you invested in each stock and what it became — gain or loss"
    override val group = Group.PORTFOLIO
    override val needsWeb = false
    override val triggers = listOf(Regex("""(?i)\b(pnl|p&l|profit and loss|portfolio) (flow|sankey)\b"""))
    private const val MAX_NODES = 8

    override suspend fun run(arg: String, f: Fetch): String = build(f.holdings)

    /** Pure: holdings → card. Flows balance: invested + gains = value today + losses. */
    fun build(holdings: List<Stock.Holding>): String {
        val pos = Folio.positions(holdings)
        if (pos.isEmpty()) return Card.error(Folio.CONNECT)
        class Item(val label: String, val invested: Double, val current: Double) { val pnl get() = current - invested }
        val shown = if (pos.size > MAX_NODES) pos.take(MAX_NODES - 1) else pos
        val rest = pos.drop(shown.size)
        // the "others" bundle is split into its winners and losers so a gain in one never hides a loss in another
        val items = shown.map { Item(it.sym, it.h.invested, it.h.current) } +
            listOfNotNull(rest.filter { it.h.pnl >= 0 }.takeIf { it.isNotEmpty() }?.let { r -> Item("${r.size} others in profit", r.sumOf { it.h.invested }, r.sumOf { it.h.current }) },
                rest.filter { it.h.pnl < 0 }.takeIf { it.isNotEmpty() }?.let { r -> Item("${r.size} others in loss", r.sumOf { it.h.invested }, r.sumOf { it.h.current }) })
        val invested = pos.sumOf { it.h.invested }; val current = pos.sumOf { it.h.current }; val pnl = current - invested
        val gains = pos.filter { it.h.pnl > 0 }; val losses = pos.filter { it.h.pnl < 0 }
        val gainSum = gains.sumOf { it.h.pnl }; val lossSum = -losses.sumOf { it.h.pnl }
        val nodes = mutableListOf<Map<String, Any>>(); val links = mutableListOf<Map<String, Any>>()
        fun node(id: String, label: String, col: Int, color: String) { nodes += mapOf("id" to id, "label" to label, "col" to col, "color" to color) }
        fun link(a: String, b: String, v: Double) { if (v > 0) links += mapOf("from" to a, "to" to b, "value" to Folio.r2(v)) }
        node("invested", "Invested", 0, "blue"); if (gainSum > 0) node("gain", "Gain", 0, "green")
        items.forEachIndexed { i, it ->
            val id = "s$i"; node(id, it.label, 1, if (it.pnl >= 0) "green" else "red")
            link("invested", id, it.invested); if (it.pnl > 0) link("gain", id, it.pnl)
            link(id, "value", it.current); if (it.pnl < 0) link(id, "loss", -it.pnl)
        }
        node("value", "Value today", 2, "blue"); if (lossSum > 0) node("loss", "Loss", 2, "red")
        val pct = if (invested > 0) pnl / invested * 100 else 0.0
        val byPnl = pos.sortedByDescending { it.h.pnl }
        val movers = (byPnl.take(3).filter { it.h.pnl > 0 } + byPnl.takeLast(3).filter { it.h.pnl < 0 }).distinctBy { it.sym }
        return Card("Your portfolio · profit & loss flow · ${pos.size} holdings", if (pnl >= 0) "green" else "red")
            .headline(Folio.signed(pnl), "${N.pct(pct)} on ${Folio.short(invested)} invested · worth ${Folio.short(current)} today", N.tone(pnl))
            .viz("sankey", (70 + items.size * 40).coerceIn(260, 400), mapOf("unit" to "₹", "nodes" to nodes, "links" to links),
                "Width of each band = rupees. Left: what you invested, plus gains. Middle: each stock. Right: what it is worth today, and losses. Tap a band.")
            .stats("Invested" to N.inr(invested), "Value today" to N.inr(current),
                "Gains · ${gains.size} stocks" to Folio.signed(gainSum), "Losses · ${losses.size} stocks" to N.inr(-lossSum))
            .rows("Biggest contributors", movers.map { Card.Row(it.sym, "${N.inr(it.h.invested)} → ${N.inr(it.h.current)}", "${Folio.signed(it.h.pnl)} · ${N.pct(it.h.pnlPct)}", N.tone(it.h.pnl)) })
            .note("Unrealised figures at the last price Kite showed, before charges and tax. Dividends received are not included.")
            .sources("Your Kite holdings (on this phone)").pills("/xray", "/income", "/rebalance", "/tax")
            .json("Your portfolio: ${N.inr(invested)} invested is worth ${N.inr(current)} today, ${Folio.signed(pnl)} (${N.pct(pct)}). " +
                "${gains.size} stocks are in profit by ${N.inr(gainSum)} and ${losses.size} in loss by ${N.inr(lossSum)}. Unrealised, before charges and tax. Source: your Kite holdings.")
    }
}

/** /rebalance — how far each holding is from an equal-weight portfolio, in rupees. Arithmetic only, not advice. */
object FolioRebalanceFeature : Feature {
    override val id = "rebalance"
    override val usage = "/rebalance"
    override val help = "Equal-weight comparison: how many rupees over or under an equal share each holding is"
    override val group = Group.PORTFOLIO
    override val needsWeb = false
    override val triggers = listOf(Regex("""(?i)\b(rebalanc\w*|equal[ -]weight\w*)\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(f.holdings)

    /** Pure: holdings → card. */
    fun build(holdings: List<Stock.Holding>): String {
        val pos = Folio.positions(holdings)
        if (pos.isEmpty()) return Card.error(Folio.CONNECT)
        if (pos.size < 2) return Card.error("An equal-weight comparison needs at least two holdings; you have one (${pos.first().sym}).")
        val total = pos.sumOf { it.h.current }; val target = total / pos.size; val targetPct = 100.0 / pos.size
        val gaps = pos.map { it to it.h.current - target }.sortedByDescending { it.second }
        val shift = gaps.filter { it.second > 0 }.sumOf { it.second }
        val over = gaps.first(); val under = gaps.last()
        // the chart shows the largest gaps on both sides when there are too many holdings to label
        val charted = if (gaps.size > 12) gaps.take(6) + gaps.takeLast(6) else gaps
        fun word(v: Double) = if (v >= 0) "${N.inr(v)} over" else "${N.inr(-v)} under"
        val rows = if (gaps.size > 10) gaps.take(5) + gaps.takeLast(5) else gaps
        return Card("Your portfolio · against equal weight · ${pos.size} holdings", "saffron")
            .headline(N.inr(shift), "sits above an equal share of ${N.inr(target)} (${Folio.p(targetPct)}) per holding · ${Folio.p(shift / total * 100)} of the portfolio")
            .viz("divbars", (40 + charted.size * 26).coerceIn(160, 360), mapOf("unit" to "₹", "digits" to 0, "xLabel" to "Over (+) or under (−) an equal share, ₹", "yLabel" to "Holding",
                "items" to charted.map { (p, g) -> mapOf("label" to p.sym, "value" to Folio.r2(g), "tone" to "flat", "note" to "${Folio.p(p.weight * 100)} now, equal share ${Folio.p(targetPct)}") }),
                "Each bar: a holding's value today minus an equal share (₹). Right of zero = over, left = under.")
            .stats("Equal share" to "${N.inr(target)} · ${Folio.p(targetPct)}", "Furthest over" to "${over.first.sym} ${Folio.signed(over.second)}",
                "Furthest under" to "${under.first.sym} ${N.inr(under.second)}", "Above equal share in total" to N.inr(shift))
            .rows("Holding by holding", rows.map { (p, g) -> Card.Row(p.sym, "${Folio.p(p.weight * 100)} now · equal share ${Folio.p(targetPct)}", word(g), "") })
            .note("This is arithmetic, not a suggestion. Equal weight is only one reference point; it ignores company size, your reasons for each holding, and the brokerage and tax that moving money would cost.")
            .sources("Your Kite holdings (on this phone)").pills("/xray", "/risk", "/correlation", "/tax")
            .json("Against an equal share of ${N.inr(target)} (${Folio.p(targetPct)}) in each of ${pos.size} holdings, ${N.inr(shift)} sits above it. " +
                "Furthest over: ${over.first.sym} by ${N.inr(over.second)}; furthest under: ${under.first.sym} by ${N.inr(-under.second)}. Arithmetic only, not a suggestion. Source: your Kite holdings.")
    }
}

/** /foliomap — the portfolio as a mind map: total in the centre, a branch per holding with weight and P&L. */
object FolioMapFeature : Feature {
    override val id = "foliomap"
    override val usage = "/foliomap"
    override val help = "Portfolio mind map: total value at the centre, one branch per holding with weight and P&L"
    override val group = Group.PORTFOLIO
    override val needsWeb = false
    override val triggers = listOf(Regex("""(?i)\b(portfolio|holdings) (mind ?map|map|tree)\b"""))
    private const val MAX_BRANCHES = 10

    override suspend fun run(arg: String, f: Fetch): String = build(f.holdings)

    /** Pure: holdings → card. */
    fun build(holdings: List<Stock.Holding>): String {
        val pos = Folio.positions(holdings)
        if (pos.isEmpty()) return Card.error(Folio.CONNECT)
        val invested = pos.sumOf { it.h.invested }; val current = pos.sumOf { it.h.current }; val pnl = current - invested
        val pct = if (invested > 0) pnl / invested * 100 else 0.0
        val shown = if (pos.size > MAX_BRANCHES) pos.take(MAX_BRANCHES - 1) else pos
        val rest = pos.drop(shown.size)
        fun leaf(name: String, value: String, tone: String = "") = mapOf("name" to name, "value" to value, "tone" to tone)
        fun colour(pnl: Double) = if (pnl >= 0) "green" else "red"
        val detailed = shown.size <= 5                                  // few holdings → room for a third figure on each branch
        val branches = shown.map { p ->
            mapOf("name" to p.sym, "value" to Folio.r2(p.h.current), "tone" to N.tone(p.h.pnl), "color" to colour(p.h.pnl), "children" to listOfNotNull(
                leaf("Weight", "${Folio.p(p.weight * 100)} · ${Folio.short(p.h.current)}"),
                leaf("P&L", "${Folio.signed(p.h.pnl)} (${N.pct(p.h.pnlPct)})", N.tone(p.h.pnl)),
                if (detailed) leaf("Avg → last", "₹${N.num(p.h.avg)} → ₹${N.num(p.h.ltp)}") else null))
        } + (if (rest.isEmpty()) emptyList() else listOf(rest.sumOf { it.h.pnl }.let { rp ->
            mapOf("name" to "${rest.size} others", "value" to Folio.r2(rest.sumOf { it.h.current }), "tone" to N.tone(rp), "color" to "blue", "children" to listOf(
                leaf("Weight", "${Folio.p(rest.sumOf { it.weight } * 100)} · ${Folio.short(rest.sumOf { it.h.current })}"), leaf("P&L", Folio.signed(rp), N.tone(rp))))
        }))
        // the tree is given both ways round — as the data itself and under "root" — so either reading of the mind-map shape finds it
        val tree = mapOf("name" to "Portfolio", "sub" to "${Folio.short(current)} · ${N.pct(pct)}", "value" to Folio.r2(current), "tone" to N.tone(pnl), "children" to branches)
        val best = pos.maxByOrNull { it.h.pnlPct }!!; val worst = pos.minByOrNull { it.h.pnlPct }!!
        return Card("Your portfolio · mind map · ${pos.size} holdings", if (pnl >= 0) "green" else "red")
            .headline(Folio.short(current), "${Folio.signed(pnl)} (${N.pct(pct)}) on ${Folio.short(invested)} invested", N.tone(pnl))
            .viz("mindmap", (40 + 21 * (branches.size + branches.sumOf { (it["children"] as List<*>).size })).coerceIn(220, 560), tree + mapOf("root" to tree),
                "Left to right: the portfolio, each holding, and its figures — weight and value today (₹), unrealised P&L" + (if (detailed) ", average cost → last price" else "") + ". Green = in profit, red = in loss.")
            .stats("Holdings" to pos.size.toString(), "Largest" to "${pos.first().sym} ${Folio.p(pos.first().weight * 100)}",
                "Best" to "${best.sym} ${N.pct(best.h.pnlPct)}", "Weakest" to "${worst.sym} ${N.pct(worst.h.pnlPct)}")
            .note("Prices are the last ones Kite showed. Today's change per holding is not part of this view, because holdings carry no day figures; /heatmap shows today's moves.")
            .sources("Your Kite holdings (on this phone)").pills("/xray", "/pnlflow", "/risk", "/heatmap")
            .json("Your ${pos.size} holdings are worth ${N.inr(current)}, ${Folio.signed(pnl)} (${N.pct(pct)}) on ${N.inr(invested)} invested. " +
                "Largest: ${pos.first().sym} at ${Folio.p(pos.first().weight * 100)}. Best: ${best.sym} ${N.pct(best.h.pnlPct)}; weakest: ${worst.sym} ${N.pct(worst.h.pnlPct)}. Source: your Kite holdings.")
    }
}
