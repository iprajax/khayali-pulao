package com.khayali.pulao.ai.term

import kotlin.math.sqrt

/** /vix — India VIX: level, where it sits in its 1-year range, and the daily move that level roughly implies. */
object GlobalVixFeature : Feature {
    override val id = "vix"
    override val usage = "/vix"
    override val help = "India VIX: level, position in its 1-year range and the daily move it roughly implies"
    override val group = Group.DERIVATIVES
    override val triggers = listOf(Regex("""(?i)\b(india )?vix\b"""), Regex("""(?i)\bfear (index|gauge)\b"""))

    const val SYMBOL = "^INDIAVIX"
    /** Annualised volatility (%) → rough one-standard-deviation move over 1/[periodsPerYear] of a year. */
    fun implied(vix: Double, periodsPerYear: Double) = vix / sqrt(periodsPerYear)

    override suspend fun run(arg: String, f: Fetch): String = build(f.chart(SYMBOL, "1y", "1d"))

    /** Pure: 1-year daily chart JSON for India VIX → card. */
    fun build(json: String): String {
        val q = Series.parse(json)?.let { GlobalKit.Quote(SYMBOL, "India VIX", it) } ?: return Card.error("Couldn't load India VIX from the quote feed just now. Try again in a minute.")
        val pos = if (q.high > q.low) (q.last - q.low) / (q.high - q.low) * 100 else 50.0
        val avg = Quant.mean(q.s.close)
        val daily = implied(q.last, 252.0); val weekly = implied(q.last, 52.0); val monthly = implied(q.last, 12.0)
        fun pm(v: Double) = "±" + GlobalKit.num(v) + "%"
        val vsAvg = if (q.last > avg) "above" else if (q.last < avg) "below" else "at"
        return Card("India VIX · ${GlobalKit.date(q.date)}", "saffron")
            .headline(GlobalKit.num(q.last), "Day ${N.pct(q.day, 2)} · ${Math.round(pos)}% of the way up its 1-year range · implies roughly ${pm(daily)} a day")
            .viz("sparkgrid", 190, mapOf("cols" to 1, "yLabel" to "India VIX (annualised %) · low & high marked", "xLabel" to "Date",
                "items" to listOf(GlobalKit.panel(q, change = q.period, changeLabel = "1 yr", color = "saffron"))),
                "India VIX daily closes over one year (annualised expected volatility, %). Tap the line for a day's level.")
            .stats("Implied daily move" to pm(daily), "Implied weekly move" to pm(weekly), "Implied monthly move" to pm(monthly),
                "1-year low" to "${GlobalKit.num(q.low)} (${GlobalKit.date(q.lowDate)})", "1-year high" to "${GlobalKit.num(q.high)} (${GlobalKit.date(q.highDate)})", "1-year average" to GlobalKit.num(avg))
            .note("India VIX is worked out by NSE from Nifty option prices: the annualised volatility the options market expects over the next 30 days. Dividing by √252 gives an approximate one-standard-deviation daily move for the Nifty. It says nothing about direction, and actual moves can be larger.")
            .sources(GlobalKit.SOURCE, "NSE (index owner)").pills("/optionchain NIFTY", "/pcr", "/indices", "/global")
            .put("vix", q.last).put("range_position_pct", Math.round(pos))
            .json("India VIX is ${GlobalKit.num(q.last)} on ${GlobalKit.date(q.date)} (day ${N.pct(q.day, 2)}), ${Math.round(pos)}% of the way up its 1-year closing range of ${GlobalKit.num(q.low)}–${GlobalKit.num(q.high)} and $vsAvg its 1-year average of ${GlobalKit.num(avg)}. " +
                "That level implies an approximate daily Nifty move of ${pm(daily)} (VIX ÷ √252), in either direction. Source: ${GlobalKit.SOURCE}.")
    }
}
