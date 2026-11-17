package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.J.s
import com.khayali.pulao.ai.term.StockFeeds.price

/**
 * /quote SYMBOL — the full NSE quote for one stock: last price and day change, the day's range against VWAP and the
 * previous close, where the price sits in its 52-week range (a range gauge), volume, delivery and P/E against the sector.
 */
object StockQuoteFeature : Feature {
    override val id = "quote"
    override val usage = "/quote INFY"
    override val help = "Full quote: price, day and 52-week range position, VWAP, volume, delivery, P/E vs sector"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\b(full quote|stock quote|quote (of|for))\b"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val sym = f.symbol(arg) ?: return Card.error("Which stock? Try /quote INFY.")
        val meta = f.nse(StockFeeds.metaPath(sym))
        return build(sym, f.nse(StockFeeds.quotePath(sym, series(meta))), meta)
    }

    /** The series to quote: the first active one that is not the T+0 book; "EQ" when the meta feed says nothing. */
    fun series(metaJson: String): String =
        J.obj(metaJson)?.get("activeSeries")?.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonPrimitive }?.map { it.asString }?.firstOrNull { it.isNotBlank() && it != "T0" } ?: "EQ"

    /** Pure: getSymbolData JSON (+ optional getMetaData JSON) → card. */
    fun build(symbol: String, quoteJson: String, metaJson: String = ""): String {
        val q = StockFeeds.quote(quoteJson) ?: return Card.error("NSE returned no quote for $symbol. Check the symbol, or try /price $symbol.")
        val meta = J.obj(metaJson)
        val name = q.name.ifBlank { meta?.s("companyName").orEmpty() }
        val p = q.pChange ?: 0.0
        val pos = q.position
        val asOf = StockFeeds.stamp(q.asOf)
        val card = Card(listOf(symbol, name, "quote").filter { it.isNotBlank() }.joinToString(" · "), if (p >= 0) "green" else "red")
            .headline(price(q.last), listOfNotNull(
                q.pChange?.let { (q.change?.let { c -> (if (c > 0) "+" else if (c < 0) "−" else "") + N.num(kotlin.math.abs(c)) + " · " } ?: "") + N.pct(it, 2) + " on the day" },
                q.prevClose?.let { "previous close ${price(it)}" }, if (q.preOpen) "pre-open, indicative" else null,
                asOf.takeIf { it.isNotBlank() }?.let { "as of $it" }).joinToString(" · "), N.tone(p))
        if (pos != null) card.viz("gauge", 120, mapOf("title" to "52-week range", "axis" to "Price (₹)", "min" to q.yearLow, "max" to q.yearHigh, "value" to q.last,
            "minLabel" to "52-week low", "maxLabel" to "52-week high", "valueLabel" to "Last"),
            "Horizontal axis: price (₹), from the 52-week low to the 52-week high. The marker is the last price, ${StockFeeds.fix(pos, 0)}% of the way up the range. Tap the bar.")
        if (q.hasDayRange) card.viz("gauge", 160, mapOf("title" to "Today's range", "axis" to "Price (₹)", "min" to q.low, "max" to q.high, "value" to q.last,
            "minLabel" to "Day low", "maxLabel" to "Day high", "valueLabel" to "Last",
            "marks" to listOfNotNull(q.vwap?.let { mapOf("label" to "VWAP", "value" to it) }, q.prevClose?.let { mapOf("label" to "Prev close", "value" to it) }, q.open?.let { mapOf("label" to "Open", "value" to it) })),
            "Horizontal axis: price (₹), from today's low to today's high, with the open, the previous close and VWAP (the volume-weighted average price) marked.")
        card.stats(*listOfNotNull(
            q.vwap?.let { "VWAP" to price(it) },
            q.volume?.let { "Volume" to StockFeeds.count(it) + " shares" + (q.value?.let { v -> " · " + StockFeeds.crore(v) } ?: "") },
            q.deliveryPct?.let { "Delivery" + (q.deliveryDate?.let { d -> " · " + StockFeeds.dayShort(d) } ?: "") to StockFeeds.share(it) },
            q.symbolPe?.let { "P/E" to StockFeeds.fix(it) },
            q.sectorPe?.let { "Sector P/E" + (q.sectorIndex.takeIf { s -> s.isNotBlank() }?.let { s -> " · $s" } ?: "") to StockFeeds.fix(it) },
            q.marketCap?.let { "Market cap" to StockFeeds.crore(it) }).toTypedArray())
        val notes = listOfNotNull(
            if (q.yearHigh != null && q.yearLow != null) "52-week high ${price(q.yearHigh)}${q.yearHighDate?.let { " on " + StockFeeds.day(it) } ?: ""}; low ${price(q.yearLow)}${q.yearLowDate?.let { " on " + StockFeeds.day(it) } ?: ""}." else null,
            if (q.preOpen) "The market is in its pre-open session: the price shown is the indicative opening price, and volume covers pre-open orders only." else null,
            if (q.deliveryPct != null) "Delivery is the share of traded shares that changed hands for settlement rather than being squared off the same day; NSE publishes it after the close, so it is for the last completed session." else null,
            "A quote is a snapshot. It does not tell you whether the price is cheap or expensive.")
        return card.note(notes.joinToString(" "))
            .sources(StockFeeds.SOURCE).pills("/scorecard $symbol", "/shareholding $symbol", "/mindmap $symbol", "/history $symbol")
            .put("symbol", symbol).put("last", q.last)
            .json("$symbol${if (name.isNotBlank()) " ($name)" else ""}: last ${price(q.last)}" + (q.pChange?.let { ", ${N.pct(it, 2)} on the day" } ?: "") + (if (q.preOpen) " (pre-open, indicative)" else "") +
                (if (q.hasDayRange) "; day range ${price(q.low!!)}–${price(q.high!!)}" else "") + (q.vwap?.takeIf { !q.preOpen }?.let { ", VWAP ${price(it)}" } ?: "") +
                (if (pos != null) "; ${StockFeeds.fix(pos, 0)}% of the way from its 52-week low ${price(q.yearLow!!)} to its high ${price(q.yearHigh!!)}" else "") + "." +
                listOfNotNull(q.volume?.let { "volume ${StockFeeds.count(it)} shares" }, q.deliveryPct?.let { "delivery ${StockFeeds.share(it)}" },
                    if (q.symbolPe != null && q.sectorPe != null) "P/E ${StockFeeds.fix(q.symbolPe)} against ${StockFeeds.fix(q.sectorPe)} for ${q.sectorIndex.ifBlank { "its sector" }}" else null)
                    .joinToString("; ").let { if (it.isEmpty()) "" else " " + it.replaceFirstChar { c -> c.uppercase() } + "." } + " Source: NSE.")
    }
}
