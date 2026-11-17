package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.J.a
import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.o
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

/** Indicator maths for the technical-analysis pack. Pure; every function is unit-tested against hand-computed values. */
object TechMath {
    /** Exponential average seeded with the simple average of the first [n] values (the textbook MACD/EMA start). Null before that. */
    fun ema(x: List<Double>, n: Int): List<Double?> {
        if (n <= 0 || x.size < n) return List(x.size) { null }
        val k = 2.0 / (n + 1); var e = x.subList(0, n).sum() / n
        return x.indices.map { i -> when { i < n - 1 -> null; i == n - 1 -> e; else -> { e = x[i] * k + e * (1 - k); e } } }
    }

    /** Wilder's RSI over [n] periods: 0–100, null for the first [n] closes. */
    fun rsi(close: List<Double>, n: Int = 14): List<Double?> {
        if (n <= 0 || close.size <= n) return List(close.size) { null }
        val out = MutableList<Double?>(close.size) { null }
        var gain = 0.0; var loss = 0.0
        for (i in 1 until close.size) {
            val ch = close[i] - close[i - 1]; val g = maxOf(ch, 0.0); val l = maxOf(-ch, 0.0)
            if (i <= n) { gain += g / n; loss += l / n } else { gain = (gain * (n - 1) + g) / n; loss = (loss * (n - 1) + l) / n }
            if (i >= n) out[i] = if (loss == 0.0) (if (gain == 0.0) 50.0 else 100.0) else 100 - 100 / (1 + gain / loss)
        }
        return out
    }

    class Macd(val line: List<Double?>, val signal: List<Double?>, val hist: List<Double?>)

    /** MACD line = EMA([fast]) − EMA([slow]); signal = EMA([sig]) of the line; histogram = line − signal. */
    fun macd(close: List<Double>, fast: Int = 12, slow: Int = 26, sig: Int = 9): Macd {
        val f = ema(close, fast); val s = ema(close, slow)
        val line = close.indices.map { i -> val a = f[i]; val b = s[i]; if (a != null && b != null) a - b else null }
        val start = line.indexOfFirst { it != null }
        val signal = MutableList<Double?>(close.size) { null }
        if (start >= 0) ema(line.drop(start).map { it!! }, sig).forEachIndexed { i, v -> signal[start + i] = v }
        return Macd(line, signal, close.indices.map { i -> val a = line[i]; val b = signal[i]; if (a != null && b != null) a - b else null })
    }

    class Bands(val mid: List<Double?>, val upper: List<Double?>, val lower: List<Double?>)

    /** Bollinger bands: [n]-period simple average ± [k] population standard deviations. */
    fun bollinger(close: List<Double>, n: Int = 20, k: Double = 2.0): Bands {
        val mid = Quant.sma(close, n)
        val sd = close.indices.map { i -> val m = mid[i] ?: return@map null; sqrt(close.subList(i + 1 - n, i + 1).sumOf { (it - m).pow(2) } / n) }
        return Bands(mid, close.indices.map { i -> mid[i]?.let { it + k * sd[i]!! } }, close.indices.map { i -> mid[i]?.let { it - k * sd[i]!! } })
    }

    /** Annualised volatility (%) of the last [n] daily returns at each close; null until [n] returns exist. */
    fun rollingVol(close: List<Double>, n: Int = 30): List<Double?> {
        val r = close.zipWithNext { a, b -> if (a != 0.0) b / a - 1 else 0.0 }
        return close.indices.map { i -> if (i < n) null else Quant.volatility(r.subList(i - n, i)) }
    }

    class Pivots(val p: Double, val r1: Double, val r2: Double, val r3: Double, val s1: Double, val s2: Double, val s3: Double)

    /** Classic floor-trader pivots from one session's high, low and close. */
    fun pivots(h: Double, l: Double, c: Double): Pivots {
        val p = (h + l + c) / 3
        return Pivots(p, 2 * p - l, p + (h - l), h + 2 * (p - l), 2 * p - h, p - (h - l), l - 2 * (h - p))
    }

    /** The deepest fall from a running peak: indices of the peak before it, the lowest point, and the first close back at the peak (null = not yet). */
    class Episode(val peak: Int, val trough: Int, val recovered: Int?, val depth: Double)

    fun worstDrawdown(close: List<Double>): Episode? {
        val dd = Quant.drawdown(close); if (dd.isEmpty()) return null
        val trough = dd.indices.minByOrNull { dd[it] }!!
        if (dd[trough] >= 0.0) return null
        val peak = (trough downTo 0).first { dd[it] == 0.0 }
        val rec = (trough + 1 until close.size).firstOrNull { close[it] >= close[peak] }
        return Episode(peak, trough, rec, dd[trough])
    }

    /** Where [v] sits between [lo] and [hi], 0–100. */
    fun position(v: Double, lo: Double, hi: Double) = if (hi > lo) ((v - lo) / (hi - lo) * 100).coerceIn(0.0, 100.0) else 50.0

    fun median(x: List<Double>): Double { if (x.isEmpty()) return 0.0; val s = x.sorted(); val m = s.size / 2; return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2 }

    /** Month-on-month change (%) of the last close of each calendar month; only for months whose previous month is present. */
    fun monthlyReturns(dates: List<LocalDate>, close: List<Double>): Map<YearMonth, Double> {
        val last = sortedMapOf<YearMonth, Double>()
        dates.forEachIndexed { i, d -> last[YearMonth.from(d)] = close[i] }
        return last.entries.mapNotNull { (ym, c) -> val prev = last[ym.minusMonths(1)] ?: return@mapNotNull null; if (prev > 0) ym to (c / prev - 1) * 100 else null }.toMap()
    }

    /** The close on the last bar on or before [on]; if the series starts within [slackDays] after [on], its first close. Null otherwise. */
    fun closeAt(dates: List<LocalDate>, close: List<Double>, on: LocalDate, slackDays: Long = 10): Double? {
        val i = dates.indexOfLast { !it.isAfter(on) }
        if (i >= 0) return close[i]
        return if (dates.isNotEmpty() && !dates.first().isAfter(on.plusDays(slackDays))) close.first() else null
    }

    fun r2(v: Double) = Math.round(v * 100) / 100.0
    fun r2(v: List<Double?>) = v.map { it?.let(::r2) }
}

/** Quote-feed plumbing the technical features share: symbol → feed symbol, and a parsed series that knows whether its last bar is settled. */
object TechFeed {
    const val SOURCE = "Yahoo Finance"
    const val DELAY = "Prices come from a public quote feed and can be delayed; treat the latest bar as indicative."
    private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    fun day(d: LocalDate): String = d.format(DAY)

    /** [feed] is the quote-feed symbol ("INFY.NS", "^NSEI"); [label] is what the card prints. Index levels are not rupee prices. */
    data class Sym(val feed: String, val label: String) {
        val index get() = feed.startsWith("^")
        fun money(v: Double) = if (index) N.num(v) else "₹" + N.num(v)
        val unit get() = if (index) "" else "₹"
        val priceAxis get() = if (index) "Index level" else "Price (₹)"
    }

    val NIFTY = Sym("^NSEI", "Nifty 50")
    private val INDICES = mapOf(
        "NIFTY" to NIFTY, "NIFTY50" to NIFTY, "NIFTY 50" to NIFTY, "^NSEI" to NIFTY,
        "BANKNIFTY" to Sym("^NSEBANK", "Nifty Bank"), "NIFTY BANK" to Sym("^NSEBANK", "Nifty Bank"), "NIFTYBANK" to Sym("^NSEBANK", "Nifty Bank"),
        "SENSEX" to Sym("^BSESN", "Sensex"))

    suspend fun resolve(arg: String, f: Fetch): Sym? {
        val a = arg.trim().removePrefix("@").uppercase().replace(Regex("""\s+"""), " ")
        if (a.isEmpty()) return null
        INDICES[a]?.let { return it }
        val s = f.symbol(arg.trim()) ?: return null
        return if (s.startsWith("^") || s.endsWith(".NS") || s.endsWith(".BO")) Sym(s, s.removeSuffix(".NS").removeSuffix(".BO")) else Sym("$s.NS", s)
    }

    /** The chart for [sym]; an NSE symbol with no data is tried once more on BSE. */
    suspend fun chart(f: Fetch, sym: Sym, range: String, interval: String): String {
        val a = f.chart(sym.feed, range, interval)
        if (!sym.feed.endsWith(".NS") || Series.parse(a) != null) return a
        return f.chart(sym.feed.removeSuffix(".NS") + ".BO", range, interval).ifBlank { a }
    }

    /**
     * A parsed series. The feed appends a bar stamped with the last trade time while a session is on, and a
     * placeholder copy of the previous close before the open. The placeholder is dropped; a bar from a session
     * still in progress is kept and flagged [live], so cards can say "so far today" and pivots can skip it.
     */
    class Bars(val s: Series, val live: Boolean) {
        val asOf: LocalDate get() = s.dates.last()
        /** "close of 16 Nov 2026" or "during the session of 17 Nov 2026 (indicative)". */
        val whenText get() = if (live) "during the session of ${day(asOf)} (indicative)" else "at the close of ${day(asOf)}"
        val verb get() = if (live) "was at" else "closed at"
    }

    fun bars(json: String): Bars? {
        val s = Series.parse(json) ?: return null
        val res = J.obj(json)?.o("chart")?.a("result")?.firstOrNull() ?: return Bars(s, false)
        val meta = res.o("meta"); val period = meta?.o("currentTradingPeriod")?.o("regular")
        val rmt = meta?.d("regularMarketTime"); val start = period?.d("start"); val end = period?.d("end")
        val ts = runCatching { res.getAsJsonArray("timestamp").map { it.asLong } }.getOrNull().orEmpty()
        val closes = runCatching { res.o("indicators")!!.a("quote").first().getAsJsonArray("close") }.getOrNull()
        val lastHasClose = closes != null && closes.size() == ts.size && ts.isNotEmpty() && !closes.last().isJsonNull
        val stamped = lastHasClose && rmt != null && ts.last().toDouble() == rmt        // the extra bar, not a settled session bar
        if (stamped && start != null && rmt!! < start) {                                 // before the open: a copy of the previous close
            if (s.size < 3) return null
            return Bars(Series(s.symbol, s.name, s.dates.dropLast(1), s.open.dropLast(1), s.high.dropLast(1), s.low.dropLast(1), s.close.dropLast(1), s.volume.dropLast(1)), false)
        }
        return Bars(s, stamped && end != null && rmt!! < end)
    }

    /** Keeps at most [max] evenly spaced indices of 0 until [n], always including the last. */
    fun thin(n: Int, max: Int): List<Int> {
        if (n <= max) return (0 until n).toList()
        val step = Math.ceil(n / max.toDouble()).toInt()
        return ((0 until n step step) + (n - 1)).distinct()
    }

    fun noData(sym: Sym, usage: String) = Card.error("No price history for ${sym.label} on the quote feed right now. Check the symbol, or try again in a moment — e.g. $usage.")
    fun which(usage: String) = Card.error("Which stock or index? Try $usage.")
}
