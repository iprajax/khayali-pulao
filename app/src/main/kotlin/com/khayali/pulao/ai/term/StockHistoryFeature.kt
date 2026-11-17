package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.s
import com.khayali.pulao.ai.term.StockFeeds.price
import java.time.LocalDate

/**
 * /history SYMBOL [YYYY-MM] — NSE's own daily record for a month: open, high, low, close, VWAP, shares traded,
 * turnover and number of trades for each session. Without a month it covers the last month up to today.
 */
object StockHistoryFeature : Feature {
    override val id = "history"
    override val usage = "/history INFY"
    override val help = "NSE daily history for a month: open-high-low-close candles, VWAP, volume and turnover"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\b(daily history|day[- ]by[- ]day prices)\b"""))

    class Day(val date: LocalDate, val open: Double, val high: Double, val low: Double, val close: Double, val prev: Double?, val volume: Double, val value: Double, val trades: Double, val vwap: Double?)

    private val MONTH = Regex("""\s+(\d{4})-(\d{1,2})$""")

    /** "INFY 2026-09" → ("INFY", 1 Sep 2026, 30 Sep 2026); "INFY" → ("INFY", a month before today, today). */
    fun window(arg: String, today: LocalDate): Triple<String, LocalDate, LocalDate> {
        val m = MONTH.find(arg.trim())
        val first = m?.let { runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), 1) }.getOrNull() }?.takeIf { !it.isAfter(today) }
        return if (m != null && first != null) Triple(arg.trim().removeRange(m.range).trim(), first, minOf(today, first.plusMonths(1).minusDays(1)))
        else Triple(m?.let { arg.trim().removeRange(it.range).trim() } ?: arg.trim(), today.minusMonths(1), today)
    }

    override suspend fun run(arg: String, f: Fetch): String {
        val (name, from, to) = window(arg, f.today)
        val sym = f.symbol(name) ?: return Card.error("Which stock? Try /history INFY, or /history INFY 2026-09 for one month.")
        return build(sym, f.nse(StockFeeds.historyPath(sym, from, to)))
    }

    fun days(json: String): List<Day> = J.arr(json).mapNotNull { o ->
        Day(StockFeeds.date(o.s("mtimestamp")) ?: return@mapNotNull null, o.d("chOpeningPrice") ?: return@mapNotNull null, o.d("chTradeHighPrice") ?: return@mapNotNull null,
            o.d("chTradeLowPrice") ?: return@mapNotNull null, o.d("chClosingPrice")?.takeIf { it > 0 } ?: return@mapNotNull null, o.d("chPreviousClsPrice")?.takeIf { it > 0 },
            o.d("chTotTradedQty") ?: 0.0, o.d("chTotTradedVal") ?: 0.0, o.d("chTotalTrades") ?: 0.0, o.d("vwap")?.takeIf { it > 0 })
    }.distinctBy { it.date }.sortedBy { it.date }

    /** Pure: getHistoricalTradeData JSON → card. */
    fun build(symbol: String, json: String): String {
        val days = days(json)
        if (days.size < 2) return Card.error("NSE returned no daily history for $symbol in that period. Try /history $symbol, or /price $symbol.")
        val first = days.first(); val last = days.last()
        val base = first.prev ?: first.open
        val change = (last.close / base - 1) * 100
        val hi = days.maxByOrNull { it.high }!!; val lo = days.minByOrNull { it.low }!!
        val turnover = days.sumOf { it.value }; val avgVol = days.map { it.volume }.average()
        val up = days.count { d -> d.prev?.let { d.close > it } == true }; val down = days.count { d -> d.prev?.let { d.close < it } == true }
        val span = "${StockFeeds.dayShort(first.date)} – ${StockFeeds.day(last.date)}"
        fun dayChange(d: Day) = d.prev?.let { (d.close / it - 1) * 100 }
        return Card("$symbol · NSE daily history · ${days.size} sessions", if (change >= 0) "green" else "red")
            .headline(N.pct(change, 2), "${price(base)} → ${price(last.close)} · $span", N.tone(change))
            .viz("candles", 260, mapOf("points" to days.map { mapOf("t" to it.date.toString(), "o" to it.open, "h" to it.high, "l" to it.low, "c" to it.close, "v" to it.volume) }),
                "Each candle is one session: open, high, low and close in ₹ (vertical axis) by date (horizontal axis). Bars underneath: shares traded.")
            .stats("Period high · ${StockFeeds.dayShort(hi.date)}" to price(hi.high), "Period low · ${StockFeeds.dayShort(lo.date)}" to price(lo.low),
                "Average daily volume" to StockFeeds.count(avgVol) + " shares", "Total turnover" to StockFeeds.crore(turnover),
                "Up days · down days" to "$up · $down", "Last VWAP" to (last.vwap?.let { price(it) } ?: "—"))
            .rows("Latest sessions", days.takeLast(6).reversed().map { d ->
                Card.Row(StockFeeds.day(d.date), "Range ${price(d.low)}–${price(d.high)} · turnover ${StockFeeds.crore(d.value)}", price(d.close) + (dayChange(d)?.let { " · " + N.pct(it, 2) } ?: ""), dayChange(d)?.let { N.tone(it) } ?: "") })
            .note("End-of-day figures as recorded by NSE for the EQ series; the change is measured from the close before the first session shown. This feed carries turnover and trade counts but not delivery — /quote $symbol shows delivery for the last session. Past sessions do not tell you what comes next.")
            .sources(StockFeeds.SOURCE).pills("/quote $symbol", "/scorecard $symbol", "/ta $symbol", "/price $symbol")
            .put("symbol", symbol).put("sessions", days.size)
            .json("$symbol on NSE, $span (${days.size} sessions): closed at ${price(last.close)}, ${N.pct(change, 2)} over the period; high ${price(hi.high)} on ${StockFeeds.dayShort(hi.date)}, low ${price(lo.low)} on ${StockFeeds.dayShort(lo.date)}; " +
                "total turnover ${StockFeeds.crore(turnover)}, average ${StockFeeds.count(avgVol)} shares a day. Source: NSE.")
    }
}
