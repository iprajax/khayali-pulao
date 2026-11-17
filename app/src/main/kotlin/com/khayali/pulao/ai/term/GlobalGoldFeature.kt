package com.khayali.pulao.ai.term

/** /gold — gold in rupees per 10 grams, derived from the dollar gold future and USD/INR, with a 1-year chart. */
object GlobalGoldFeature : Feature {
    override val id = "gold"
    override val usage = "/gold"
    override val help = "Gold in rupees per 10 grams, derived from the dollar price and USD/INR, with a 1-year chart"
    override val group = Group.FUNDS
    override val triggers = listOf(Regex("""(?i)\bgold (price|rate)s?\b"""), Regex("""(?i)\b(price|rate) of gold\b"""))

    const val GOLD = "GC=F"
    const val USDINR = "USDINR=X"
    /** Grams in one troy ounce. */
    const val TROY_OUNCE_GRAMS = 31.1035

    fun per10g(usdPerOunce: Double, usdInr: Double) = usdPerOunce * usdInr / TROY_OUNCE_GRAMS * 10

    override suspend fun run(arg: String, f: Fetch): String = GlobalKit.charts(f, listOf(GOLD, USDINR), "1y").let { build(it[GOLD].orEmpty(), it[USDINR].orEmpty()) }

    /** Pure: 1-year daily chart JSON for the gold future (US$ per troy ounce) and USD/INR → card. Each gold close uses that day's rate, or the latest earlier one. */
    fun build(goldJson: String, fxJson: String): String {
        val gold = Series.parse(goldJson); val fx = Series.parse(fxJson)
        if (gold == null || fx == null) return Card.error("Couldn't load ${if (gold == null) "the gold price" else "the USD/INR rate"} from the quote feed just now. Try again in a minute.")
        var j = 0
        val pts = gold.dates.indices.mapNotNull { i ->
            while (j + 1 < fx.size && !fx.dates[j + 1].isAfter(gold.dates[i])) j++
            if (fx.dates[j].isAfter(gold.dates[i])) null else Triple(gold.dates[i], per10g(gold.close[i], fx.close[j]), fx.close[j])
        }
        if (pts.size < 2) return Card.error("Not enough overlapping gold and USD/INR history to work out a rupee price.")
        val last = pts.last(); val prev = pts[pts.size - 2]; val first = pts.first()
        val day = (last.second / prev.second - 1) * 100; val year = (last.second / first.second - 1) * 100
        val usdMove = (gold.last / gold.close[gold.dates.indexOf(first.first)] - 1) * 100; val fxMove = (last.third / first.third - 1) * 100
        val hi = pts.maxBy { it.second }; val lo = pts.minBy { it.second }
        return Card("Gold · ₹ per 10 g · derived · ${GlobalKit.date(last.first)}", "saffron")
            .headline(N.inr(last.second) + " per 10 g", "Day ${N.pct(day, 2)} · 1 year ${N.pct(year)} · before import duty and GST", N.tone(day))
            .viz("sparkgrid", 200, mapOf("cols" to 1, "yLabel" to "Gold (₹ per 10 g, derived) · low & high marked", "xLabel" to "Date", "items" to listOf(mapOf(
                "label" to "Gold, ₹ per 10 g", "t" to pts.map { it.first.toString() }, "v" to pts.map { Math.round(it.second) }, "digits" to 0, "prefix" to "₹", "change" to year, "changeLabel" to "1 yr", "color" to "saffron"))),
                "Derived gold price in rupees per 10 grams by date, over one year. Tap the line for a day's figure.")
            .stats("Per gram" to N.inr(last.second / 10), "Gold (US$ per troy ounce)" to GlobalKit.usd(gold.last), "USD/INR used" to "₹" + GlobalKit.num(last.third),
                "1-year high" to "${N.inr(hi.second)} (${GlobalKit.date(hi.first)})", "1-year low" to "${N.inr(lo.second)} (${GlobalKit.date(lo.first)})",
                "1-year move: gold in US$ / rupee" to "${N.pct(usdMove)} / ${N.pct(fxMove)}")
            .note("Indicative, derived figure: gold future (US$ per troy ounce) × USD/INR ÷ 31.1035 × 10, for 24-carat metal. Indian retail prices also include import duty and GST, and jewellers add making charges, so shop prices are higher. Quotes may be delayed.")
            .sources(GlobalKit.SOURCE).pills("/fx", "/commodities", "/global", "/inflation")
            .put("inr_per_10g", Math.round(last.second))
            .json("Gold works out to ${N.inr(last.second)} per 10 g on ${GlobalKit.date(last.first)} (day ${N.pct(day, 2)}, 1 year ${N.pct(year)}), derived from ${GlobalKit.usd(gold.last)} per troy ounce at USD/INR ₹${GlobalKit.num(last.third)}. " +
                "Indian retail prices also include import duty and GST. Source: ${GlobalKit.SOURCE}.")
    }
}
