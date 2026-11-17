package com.khayali.pulao.ai.term

/** /yields — US 10-year Treasury yield, the 13-week bill yield and the dollar index, each with a 1-year line. */
object GlobalYieldsFeature : Feature {
    override val id = "yields"
    override val usage = "/yields"
    override val help = "US 10-year yield, 13-week bill yield and the dollar index with 1-year lines"
    override val group = Group.MACRO
    override val triggers = listOf(Regex("""(?i)\b((bond|treasury|us) yields?|us 10[- ]?(year|yr)|dollar index|dxy)\b"""))

    const val TEN_YEAR = "^TNX"
    const val BILL = "^IRX"
    const val DOLLAR = "DX-Y.NYB"

    override suspend fun run(arg: String, f: Fetch): String = build(GlobalKit.charts(f, listOf(TEN_YEAR, BILL, DOLLAR), "1y"))

    /** Change of a yield in basis points: 4.15% → 5.12% is +97 bp. */
    fun bp(from: Double, to: Double): String { val v = Math.round((to - from) * 100); return (if (v > 0) "+" else if (v < 0) "−" else "") + kotlin.math.abs(v) + " bp" }

    /** Pure: 1-year daily chart JSON by feed symbol → card. Yields are in percent; the dollar index is a level. */
    fun build(charts: Map<String, String>): String {
        val ten = GlobalKit.quote(charts, TEN_YEAR, "US 10-year Treasury yield"); val bill = GlobalKit.quote(charts, BILL, "US 13-week T-bill yield"); val dxy = GlobalKit.quote(charts, DOLLAR, "US dollar index (DXY)")
        val all = listOfNotNull(ten, bill, dxy)
        if (all.isEmpty()) return Card.error("Couldn't load US yields or the dollar index from the quote feed just now. Try again in a minute.")
        fun y(q: GlobalKit.Quote) = GlobalKit.level(q.last, 2)
        fun dayBp(q: GlobalKit.Quote) = bp(q.prev, q.last)
        fun yearBp(q: GlobalKit.Quote) = bp(q.s.close.first(), q.last)
        val head = all.first()
        val isYield = { q: GlobalKit.Quote -> q !== dxy }
        val parts = listOfNotNull(
            ten?.let { "US 10-year yield ${y(it)} (day ${dayBp(it)}, 1 year ${yearBp(it)})" }, bill?.let { "13-week bill ${y(it)} (1 year ${yearBp(it)})" },
            dxy?.let { "dollar index ${GlobalKit.num(it.last)} (day ${N.pct(it.day, 2)}, 1 year ${N.pct(it.period)})" })
        val spread = if (ten != null && bill != null) ten.last - bill.last else null
        return Card("US rates and the dollar · ${GlobalKit.date(head.date)}", "blue")
            .headline(if (isYield(head)) y(head) + " " + (if (head === ten) "US 10-year" else "US 13-week bill") else GlobalKit.num(head.last) + " dollar index",
                (if (isYield(head)) "Day ${dayBp(head)} · 1 year ${yearBp(head)}" else "Day ${N.pct(head.day, 2)} · 1 year ${N.pct(head.period)}") +
                    (if (head === ten && bill != null) " · 13-week bill ${y(bill)}" else "") + (if (head !== dxy && dxy != null) " · dollar index ${GlobalKit.num(dxy.last)}" else ""))
            .viz("sparkgrid", GlobalKit.gridHeight(all.size, 1, 100), mapOf("cols" to 1, "yLabel" to "Yield (%) · dollar index (level)", "xLabel" to "Date",
                "items" to all.map { q -> if (isYield(q)) GlobalKit.panel(q, suffix = "%", change = q.last - q.s.close.first(), changeText = yearBp(q), changeLabel = "1 yr", color = if (q === ten) "blue" else "violet")
                    else GlobalKit.panel(q, change = q.period, changeLabel = "1 yr", color = "saffron") }),
                "Daily closes over one year: yields in percent, the dollar index as a level; each panel has its own scale with its low and high marked. Tap a line for a day's value.")
            .stats(*listOfNotNull(
                ten?.let { "10-year: 1-year range" to "${GlobalKit.level(it.low, 2)} – ${GlobalKit.level(it.high, 2)}" }, bill?.let { "13-week: 1-year range" to "${GlobalKit.level(it.low, 2)} – ${GlobalKit.level(it.high, 2)}" },
                spread?.let { "10-year minus 13-week" to bp(0.0, it) }, dxy?.let { "Dollar index: 1-year range" to "${GlobalKit.num(it.low)} – ${GlobalKit.num(it.high)}" }).toTypedArray())
            .note("The quote feed carries the 13-week Treasury bill (^IRX) rather than the 2-year note, so the short end shown here is the 13-week bill. 100 basis points (bp) = 1 percentage point. The dollar index measures the dollar against six major currencies; the rupee is not one of them. Quotes may be delayed.")
            .sources(GlobalKit.SOURCE).pills("/fx", "/global", "/gold", "/commodities")
            .json(parts.joinToString("; ").replaceFirstChar { it.uppercase() } + " on ${GlobalKit.date(head.date)}. Source: ${GlobalKit.SOURCE}.")
    }
}
