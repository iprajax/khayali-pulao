package com.khayali.pulao.ai.term

/** /commodities — gold, silver, crude (WTI and Brent), natural gas and copper futures in dollars, with day and 1-month change. */
object GlobalCommoditiesFeature : Feature {
    override val id = "commodities"
    override val usage = "/commodities"
    override val help = "Gold, silver, crude oil, natural gas and copper in dollars with day and 1-month change"
    override val group = Group.MACRO
    override val triggers = listOf(Regex("""(?i)\b(commodit(y|ies)|(crude|oil|brent|silver|copper|natural gas) (price|rate)s?|crude oil)\b"""))

    private class Item(val symbol: String, val name: String, val unit: String, val digits: Int = 2)
    private val ITEMS = listOf(Item("GC=F", "Gold", "per troy ounce"), Item("SI=F", "Silver", "per troy ounce"), Item("CL=F", "Crude oil (WTI)", "per barrel"),
        Item("BZ=F", "Brent crude", "per barrel"), Item("NG=F", "Natural gas", "per MMBtu", 3), Item("HG=F", "Copper", "per pound", 3))
    val SYMBOLS get() = ITEMS.map { it.symbol }

    override suspend fun run(arg: String, f: Fetch): String = build(GlobalKit.charts(f, SYMBOLS, "1mo"))

    /** Pure: 1-month daily chart JSON by feed symbol → card. */
    fun build(charts: Map<String, String>): String {
        val qs = ITEMS.mapNotNull { i -> GlobalKit.quote(charts, i.symbol, i.name)?.let { i to it } }
        if (qs.isEmpty()) return Card.error("Couldn't load commodity prices from the quote feed just now. Try again in a minute.")
        val up = qs.count { it.second.day > 0 }
        val big = qs.maxBy { kotlin.math.abs(it.second.period) }.second
        fun price(p: Pair<Item, GlobalKit.Quote>) = GlobalKit.usd(p.second.last, p.first.digits)
        val newest = qs.maxOf { it.second.date }
        return Card("Commodities · US dollar futures · ${GlobalKit.date(newest)}", "saffron")
            .headline("$up of ${qs.size} up on the day", "Largest 1-month move: ${big.label} ${N.pct(big.period)}", if (up * 2 > qs.size) "up" else if (up * 2 < qs.size) "down" else "")
            .viz("sparkgrid", GlobalKit.gridHeight(qs.size, 2, 76), mapOf("cols" to 2, "yLabel" to "Price (US$) · low & high per panel", "xLabel" to "Date",
                "items" to qs.map { (i, q) -> GlobalKit.panel(q, digits = i.digits, prefix = "$") }),
                "Each panel: daily closes over about one month in US dollars (own scale), the last price and the day's change. Tap a line for a day's close.")
            .rows("Last price · day change", qs.map { p -> Card.Row(p.second.label, "${p.first.unit} · 1 month ${N.pct(p.second.period)}", "${price(p)} · ${N.pct(p.second.day, 2)}", N.tone(p.second.day)) })
            .note("Front-month futures on US exchanges, in US dollars, and quotes may be delayed. Indian (MCX) prices also reflect the rupee, duties and local premiums, so they differ. For gold in rupees see /gold.")
            .sources(GlobalKit.SOURCE).pills("/gold", "/fx", "/global", "/inflation")
            .json("Commodities on ${GlobalKit.date(newest)}: " + qs.joinToString(", ") { p -> "${p.second.label} ${price(p)} (day ${N.pct(p.second.day, 2)}, 1 month ${N.pct(p.second.period)})" } +
                ". US dollar futures, may be delayed. Source: ${GlobalKit.SOURCE}.")
    }
}
