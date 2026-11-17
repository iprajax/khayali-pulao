package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.WebData
import com.khayali.pulao.ai.term.J.a
import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.o
import com.khayali.pulao.ai.term.StockFeeds.fix

/**
 * /scorecard SYMBOL — one stock's past year on a radar: five measures, each placed on a 0–100 scale against a FIXED band
 * (the same band for every stock, so two scorecards can be compared). 100 is the favourable end of each band:
 *
 *   1-year return           −30% → 0     +50% → 100
 *   Volatility (annualised)  60% → 0      15% → 100   (inverted: calmer scores higher)
 *   Max drawdown            −50% → 0      −5% → 100   (inverted: shallower scores higher)
 *   Price vs 200-day average −20% → 0    +20% → 100
 *   Dividend yield            0% → 0       5% → 100   (dividends with an ex-date in the year ÷ last price)
 *
 * Values beyond a band are clamped. The scores describe the past year only; they are not a rating.
 */
object StockScorecardFeature : Feature {
    override val id = "scorecard"
    override val usage = "/scorecard INFY"
    override val help = "Radar of one stock's year: return, volatility, drawdown, trend and dividend yield, each 0–100"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\b(score ?card|radar chart)\b"""))

    class Band(val label: String, val zero: Double, val full: Double, val text: String) {
        fun score(v: Double) = ((v - zero) / (full - zero) * 100).coerceIn(0.0, 100.0)
    }
    val RETURN = Band("1-year return", -30.0, 50.0, "−30% → 0 · +50% → 100")
    val VOLATILITY = Band("Volatility", 60.0, 15.0, "60% → 0 · 15% → 100")
    val DRAWDOWN = Band("Max drawdown", -50.0, -5.0, "−50% → 0 · −5% → 100")
    val TREND = Band("Vs 200-day average", -20.0, 20.0, "−20% → 0 · +20% → 100")
    val DIVIDEND = Band("Dividend yield", 0.0, 5.0, "0% → 0 · 5% → 100")
    const val MIN_BARS = 60

    override suspend fun run(arg: String, f: Fetch): String {
        val sym = f.symbol(arg) ?: return Card.error("Which stock? Try /scorecard INFY.")
        return build(sym, f.chart("$sym.NS", "1y", "1d"))
    }

    /** Cash dividends (₹ a share) whose ex-date falls inside the chart window. */
    fun dividends(chartJson: String): Double =
        J.obj(chartJson)?.o("chart")?.a("result")?.firstOrNull()?.o("events")?.o("dividends")?.entrySet()?.sumOf { e -> e.value.takeIf { it.isJsonObject }?.asJsonObject?.d("amount") ?: 0.0 } ?: 0.0

    /** Pure: quote-feed chart JSON (1 year, daily) → card. */
    fun build(symbol: String, chartJson: String): String {
        val s = Series.parse(chartJson)?.takeIf { it.size >= MIN_BARS } ?: return Card.error("Not enough daily price history for $symbol to score a year. Try /quote $symbol.")
        val ret = s.change()
        val vol = Quant.volatility(s.returns())
        val dd = Quant.drawdown(s.close).min()
        val sma = Quant.sma(s.close, 200).last()
        val trend = sma?.let { (s.last / it - 1) * 100 }
        val paid = dividends(chartJson)
        val divYield = if (s.last > 0) paid / s.last * 100 else 0.0
        data class Axis(val band: Band, val value: Double, val text: String)
        val axes = listOfNotNull(
            Axis(RETURN, ret, N.pct(ret)), Axis(VOLATILITY, vol, fix(vol, 1) + "%"), Axis(DRAWDOWN, dd, N.pct(dd)),
            trend?.let { Axis(TREND, it, N.pct(it)) }, Axis(DIVIDEND, divYield, fix(divYield, 1) + "%"))
        fun sc(a: Axis) = Math.round(a.band.score(a.value)).toInt()
        val from = StockFeeds.day(s.dates.first()); val to = StockFeeds.day(s.dates.last())
        val name = s.name.takeIf { it.isNotBlank() && !it.equals(s.symbol, true) }
        return Card(listOfNotNull(symbol, name, "1-year scorecard").joinToString(" · "), if (ret >= 0) "green" else "red")
            .headline(N.pct(ret) + " in a year", "Volatility ${fix(vol, 1)}% a year · deepest fall ${N.pct(dd)}" + (trend?.let { " · ${N.pct(it)} vs 200-day average" } ?: "") + " · dividend yield ${fix(divYield, 1)}%", N.tone(ret))
            .viz("radar", 290, mapOf("max" to 100, "ringLabel" to "Score (0–100)", "axes" to axes.map { mapOf("label" to it.band.label, "score" to sc(it), "value" to it.text, "band" to it.band.text) }),
                "Each spoke is one measure; distance from the centre is its score from 0 to 100 against a fixed band (further out = higher return, calmer price, shallower fall, stronger trend, more dividend). Tap a point.")
            .viz("history", 180, mapOf("points" to s.dates.indices.map { mapOf("t" to s.dates[it].toString(), "c" to s.close[it]) }), "Daily closing price (₹) by date, $from to $to.")
            .rows("Measure · fixed band", axes.map { Card.Row(it.band.label, it.band.text, "${it.text} · ${sc(it)}/100") })
            .note("Bands are fixed and the same for every stock, so scores can be compared across scorecards. Volatility and drawdown are inverted: a calmer year scores higher. " +
                (if (trend == null) "The 200-day average needs 200 sessions of history, so that spoke is left out. " else "") +
                "Dividend yield counts dividends with an ex-date in this window (₹${fix(paid)} a share) against the last price. " +
                "This describes the past ${s.size} sessions only. It is not a rating and says nothing about what the price will do next. Prices from the quote feed can be delayed.")
            .sources(WebData.QUOTE_NAME).pills("/quote $symbol", "/ta $symbol", "/returns $symbol", "/risk $symbol")
            .put("symbol", symbol).put("scores", axes.associate { it.band.label to sc(it) })
            .json("$symbol over the year to $to: return ${N.pct(ret)} (score ${sc(axes[0])}/100), annualised volatility ${fix(vol, 1)}% (${sc(axes[1])}/100), maximum drawdown ${N.pct(dd)} (${sc(axes[2])}/100)" +
                (trend?.let { t -> ", ${N.pct(t)} against its 200-day average (${sc(axes[3])}/100)" } ?: "") + ", dividend yield ${fix(divYield, 1)}% (${sc(axes.last())}/100). " +
                "Scores use fixed bands and describe the past year only. Source: ${WebData.QUOTE_NAME}.")
    }
}
