package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.StockFeeds.price

/**
 * /peers SECTOR — the stocks of a sector index side by side: today's change for each, and last prices.
 * The argument is a sector (IT, BANK, AUTO, PHARMA, FMCG, METAL…); a stock symbol also works and shows that stock's sector.
 */
object StockPeersFeature : Feature {
    override val id = "peers"
    override val usage = "/peers IT"
    override val help = "Sector peers side by side: every stock of a sector index with price and today's change"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\b(sector peers|peer comparison|peers (of|for|in))\b"""))

    const val MAX_BARS = 16

    override suspend fun run(arg: String, f: Fetch): String {
        if (arg.isBlank()) return Card.error("Which sector? Try /peers IT. Sectors: ${StockFeeds.SECTOR_HINT}.")
        StockFeeds.sectorIndex(arg)?.let { return build(it, f.nse(StockFeeds.peersPath(it))) }
        val sym = f.symbol(arg) ?: return Card.error("\"${arg.trim()}\" is not a sector I know. Try /peers IT. Sectors: ${StockFeeds.SECTOR_HINT}.")
        val q = StockFeeds.quote(f.nse(StockFeeds.quotePath(sym))) ?: return Card.error("\"${arg.trim()}\" is not a sector I know, and NSE returned no quote for $sym. Try /peers IT.")
        val index = StockFeeds.sectorIndex(q) ?: return Card.error("NSE does not place $sym in a sector index this view covers. Try /peers IT. Sectors: ${StockFeeds.SECTOR_HINT}.")
        return build(index, f.nse(StockFeeds.peersPath(index)), sym)
    }

    /** Pure: sector-index constituents JSON → card. [focus] is the stock the user asked about, if any. */
    fun build(index: String, json: String, focus: String? = null): String {
        val rows = StockFeeds.peers(json).sortedByDescending { it.pChange }
        if (rows.isEmpty()) return Card.error("NSE returned no stocks for $index. Try /peers IT. Sectors: ${StockFeeds.SECTOR_HINT}.")
        val up = rows.count { it.pChange > 0 }; val down = rows.count { it.pChange < 0 }
        val avg = rows.map { it.pChange }.average()
        val best = rows.first(); val worst = rows.last()
        val me = focus?.let { s -> rows.indexOfFirst { it.symbol.equals(s, true) }.takeIf { it >= 0 } }
        val shown = if (rows.size <= MAX_BARS) rows else (rows.take(MAX_BARS / 2) + rows.takeLast(MAX_BARS / 2))
        val asOf = StockFeeds.stamp(StockFeeds.peersAsOf(json))
        val traded = rows.filter { it.value > 0 }
        val card = Card("$index · peers · ${rows.size} stocks", if (up >= down) "green" else "red")
            .headline("$up up · $down down", "Average move ${N.pct(avg, 2)} · best ${best.symbol} ${N.pct(best.pChange, 2)} · worst ${worst.symbol} ${N.pct(worst.pChange, 2)}" +
                (me?.let { " · ${rows[it].symbol} ${N.pct(rows[it].pChange, 2)}, ${it + 1} of ${rows.size}" } ?: ""), N.tone(avg))
            .viz("percentbars", 30 + 22 * shown.size, mapOf("label" to "Day change (%)", "items" to shown.map { mapOf("label" to (if (me != null && it.symbol == rows[me].symbol) "▸ " else "") + it.symbol, "value" to it.pChange) }),
                "Each bar is one stock of $index; its length is the day's change in percent" + (if (shown.size < rows.size) " (the ${MAX_BARS / 2} strongest and ${MAX_BARS / 2} weakest of ${rows.size})." else "."))
        if (traded.size >= 3 && traded.size == rows.size) card.viz("treemap", 220, mapOf("items" to rows.map { mapOf("label" to it.symbol, "value" to it.value, "change" to it.pChange, "price" to it.price, "mine" to false) }),
            "Tile size: the day's traded value. Colour: the day's change (%). Tap a tile.")
        return card.rows("Last price", (if (traded.size == rows.size) rows.sortedByDescending { it.value } else rows).take(10).map {
                Card.Row(it.symbol, if (it.value > 0) "Traded " + StockFeeds.crore(it.value) else "", price(it.price) + " · " + N.pct(it.pChange, 2), N.tone(it.pChange)) })
            .note((if (asOf.isNotBlank()) "Prices as of $asOf. " else "") + "Day change compares each stock with its own previous close. " +
                "Stocks in one sector index can differ a lot in size and business mix, so a side-by-side move is a starting point, not a like-for-like comparison.")
            .sources(StockFeeds.SOURCE).pills("/heatmap $index", "/quote ${(me?.let { rows[it] } ?: best).symbol}", "/sectors", "/movers")
            .put("index", index)
            .json("$index${if (asOf.isNotBlank()) " as of $asOf" else ""}: $up of ${rows.size} stocks up and $down down, average move ${N.pct(avg, 2)}. Best ${best.symbol} ${N.pct(best.pChange, 2)} at ${price(best.price)}, worst ${worst.symbol} ${N.pct(worst.pChange, 2)} at ${price(worst.price)}" +
                (me?.let { "; ${rows[it].symbol} ${N.pct(rows[it].pChange, 2)} at ${price(rows[it].price)}" } ?: "") + ". Source: NSE.")
    }
}
