package com.khayali.pulao.ai.term

/** /fx — the rupee against six currencies, with day and 1-year change and a 1-year USD/INR line. */
object GlobalFxFeature : Feature {
    override val id = "fx"
    override val usage = "/fx"
    override val help = "Rupee against USD, EUR, GBP, JPY, AED and SGD with day and 1-year change"
    override val group = Group.MACRO
    override val triggers = listOf(Regex("""(?i)\b(usd ?/? ?inr|forex rates?|exchange rates?|currency rates?|dollar (rate|to rupees?|vs rupee)|rupee (rate|vs|against))\b"""))

    private class Pair6(val symbol: String, val code: String, val name: String, val scale: Double = 1.0)
    private val PAIRS = listOf(Pair6("USDINR=X", "USD", "US dollar"), Pair6("EURINR=X", "EUR", "Euro"), Pair6("GBPINR=X", "GBP", "British pound"),
        Pair6("JPYINR=X", "JPY", "100 Japanese yen", 100.0), Pair6("AEDINR=X", "AED", "UAE dirham"), Pair6("SGDINR=X", "SGD", "Singapore dollar"))
    val SYMBOLS get() = PAIRS.map { it.symbol }

    override suspend fun run(arg: String, f: Fetch): String = build(GlobalKit.charts(f, SYMBOLS, "1y"))

    /** Pure: 1-year daily chart JSON by feed symbol → card. Rates are rupees per unit of the foreign currency (per 100 for the yen). */
    fun build(charts: Map<String, String>): String {
        val qs = PAIRS.mapNotNull { p -> GlobalKit.quote(charts, p.symbol, p.name, p.scale)?.let { p to it } }
        val usd = qs.firstOrNull { it.first.code == "USD" }?.second
            ?: return Card.error("Couldn't load the rupee's exchange rates from the quote feed just now. Try again in a minute.")
        fun rate(q: GlobalKit.Quote) = "₹" + GlobalKit.num(q.last)
        val others = qs.filter { it.first.code != "USD" }
        return Card("Rupee exchange rates · ${GlobalKit.date(usd.date)}", "blue")
            .headline(rate(usd) + " per US dollar", "Day ${N.pct(usd.day, 2)} · 1 year ${N.pct(usd.period, 2)} · a higher figure means a weaker rupee")
            .viz("sparkgrid", 190, mapOf("cols" to 1, "yLabel" to "Rupees per US dollar · low & high marked", "xLabel" to "Date",
                "items" to listOf(GlobalKit.panel(usd.let { GlobalKit.Quote(it.symbol, "USD/INR", it.s) }, prefix = "₹", change = usd.period, changeLabel = "1 yr", color = "blue"))),
                "USD/INR daily closes over one year (rupees per dollar). Tap the line for a day's rate.")
            .rows("Rupees per unit of foreign currency", qs.map { (p, q) -> Card.Row(q.label, "${p.code} · 1 year ${N.pct(q.period, 2)}", "${rate(q)} · ${N.pct(q.day, 2)}") })
            .stats("USD/INR 1-year high" to "₹${GlobalKit.num(usd.high)} (${GlobalKit.date(usd.highDate)})", "USD/INR 1-year low" to "₹${GlobalKit.num(usd.low)} (${GlobalKit.date(usd.lowDate)})")
            .note("Indicative mid-market rates, possibly delayed; banks, cards and money changers add their own spread. A rising figure means the rupee has weakened against that currency. This describes past moves only — it says nothing about where rates go next.")
            .sources(GlobalKit.SOURCE).pills("/gold", "/yields", "/global", "/commodities")
            .put("usdinr", usd.last)
            .json("USD/INR ${rate(usd)} on ${GlobalKit.date(usd.date)} (day ${N.pct(usd.day, 2)}, 1 year ${N.pct(usd.period, 2)}; a higher figure means a weaker rupee)" +
                (if (others.isEmpty()) "" else "; " + others.joinToString(", ") { (p, q) -> "${if (p.scale == 1.0) p.code else q.label} ${rate(q)}" }) + ". Indicative rates. Source: ${GlobalKit.SOURCE}.")
    }
}
