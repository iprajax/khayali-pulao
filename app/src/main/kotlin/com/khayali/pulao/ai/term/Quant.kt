package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.J.a
import com.khayali.pulao.ai.term.J.o
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.pow
import kotlin.math.sqrt

/** A daily (or weekly) price series from the quote feed, and the statistics features share. Pure. */
class Series(val symbol: String, val name: String, val dates: List<LocalDate>, val open: List<Double>, val high: List<Double>, val low: List<Double>, val close: List<Double>, val volume: List<Double>) {
    val size get() = close.size
    val last get() = close.last()

    /** Simple returns, one fewer than the closes. */
    fun returns(): List<Double> = close.zipWithNext { a, b -> if (a != 0.0) b / a - 1 else 0.0 }
    /** Total change over the series, in percent. */
    fun change(): Double = if (close.first() != 0.0) (close.last() / close.first() - 1) * 100 else 0.0

    companion object {
        private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

        /** Parses quote-feed chart JSON. Bars with no close are dropped. Null when the reply has no usable data. */
        fun parse(json: String): Series? = runCatching {
            val res = J.obj(json)!!.o("chart")!!.a("result").first()
            val meta = res.o("meta")!!
            val ts = res.getAsJsonArray("timestamp").map { it.asLong }
            val q = res.o("indicators")!!.a("quote").first()
            fun col(k: String) = q.getAsJsonArray(k).map { if (it.isJsonNull) null else it.asDouble }
            val o = col("open"); val h = col("high"); val l = col("low"); val c = col("close"); val v = col("volume")
            val keep = ts.indices.filter { c.getOrNull(it) != null }
            if (keep.size < 2) return@runCatching null
            Series(
                meta.get("symbol")?.asString.orEmpty(), (meta.get("longName") ?: meta.get("shortName"))?.takeIf { !it.isJsonNull }?.asString ?: meta.get("symbol")?.asString.orEmpty(),
                keep.map { Instant.ofEpochSecond(ts[it]).atZone(IST).toLocalDate() },
                keep.map { o.getOrNull(it) ?: c[it]!! }, keep.map { h.getOrNull(it) ?: c[it]!! }, keep.map { l.getOrNull(it) ?: c[it]!! }, keep.map { c[it]!! }, keep.map { v.getOrNull(it) ?: 0.0 })
        }.getOrNull()
    }
}

object Quant {
    fun mean(x: List<Double>) = if (x.isEmpty()) 0.0 else x.sum() / x.size
    fun stdev(x: List<Double>): Double { if (x.size < 2) return 0.0; val m = mean(x); return sqrt(x.sumOf { (it - m).pow(2) } / (x.size - 1)) }
    /** Annualised volatility in percent from daily returns. */
    fun volatility(dailyReturns: List<Double>) = stdev(dailyReturns) * sqrt(252.0) * 100
    /** Pearson correlation of two equally long lists (the shorter length is used). */
    fun correlation(a: List<Double>, b: List<Double>): Double {
        val n = minOf(a.size, b.size); if (n < 3) return 0.0
        val x = a.takeLast(n); val y = b.takeLast(n); val mx = mean(x); val my = mean(y)
        val den = sqrt(x.sumOf { (it - mx).pow(2) } * y.sumOf { (it - my).pow(2) })
        return if (den == 0.0) 0.0 else x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / den
    }
    /** Sensitivity of [asset] returns to [market] returns. */
    fun beta(asset: List<Double>, market: List<Double>): Double {
        val n = minOf(asset.size, market.size); if (n < 3) return 0.0
        val x = market.takeLast(n); val y = asset.takeLast(n); val mx = mean(x); val my = mean(y)
        val varX = x.sumOf { (it - mx).pow(2) }
        return if (varX == 0.0) 0.0 else x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / varX
    }
    /** Fall from the running peak at each point, in percent (0 or negative). */
    fun drawdown(close: List<Double>): List<Double> { var peak = Double.MIN_VALUE; return close.map { peak = maxOf(peak, it); if (peak > 0) (it / peak - 1) * 100 else 0.0 } }
    /** Compound yearly growth in percent between two values over [years]. */
    fun cagr(start: Double, end: Double, years: Double) = if (start > 0 && years > 0) ((end / start).pow(1 / years) - 1) * 100 else 0.0
    fun sma(x: List<Double>, n: Int): List<Double?> = x.indices.map { i -> if (i + 1 < n) null else x.subList(i + 1 - n, i + 1).sum() / n }
    fun ema(x: List<Double>, n: Int): List<Double> { val k = 2.0 / (n + 1); var e = x.firstOrNull() ?: return emptyList(); return x.map { e = it * k + e * (1 - k); e } }
}
