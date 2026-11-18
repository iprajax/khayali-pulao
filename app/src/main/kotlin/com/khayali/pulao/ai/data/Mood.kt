package com.khayali.pulao.ai.data

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A market mood index (0 = extreme fear, 100 = extreme greed), computed on the phone from the user's own NSE
 * history. Six parts, each 0–100 and equally weighted, all over the liquid universe:
 *  breadth — share above their 50-day average · trend — equal-weight market vs its 125-day average (±8% → 0–100)
 *  momentum — equal-weight 1-month return (±8%) · highs — new 52-week highs ÷ (highs + lows)
 *  calm — 100 − where today's 20-day volatility sits in the past year · advance — advancers ÷ (advancers + decliners), 5-day average.
 */
object Mood {
    data class Part(val name: String, val score: Double, val detail: String)
    data class Reading(val date: LocalDate, val score: Double, val zone: String, val parts: List<Part>)

    fun zone(s: Double) = when { s < 25 -> "Extreme fear"; s < 45 -> "Fear"; s <= 55 -> "Neutral"; s <= 75 -> "Greed"; else -> "Extreme greed" }
    private fun scale(x: Double, lo: Double, hi: Double) = ((x - lo) / (hi - lo) * 100).coerceIn(0.0, 100.0)

    /** The mood on the last day plus a daily history of [history] days (oldest first). */
    fun compute(p: Panel, history: Int = 60): List<Reading> {
        val last = p.days - 1
        if (last < 260) return emptyList()
        val u = Market.liquid(p, last)
        if (u.size < 20) return emptyList()
        val first = max(260, last - history + 1)
        // equal-weight market of the universe, as an index
        val ew = DoubleArray(p.days); val idx = DoubleArray(p.days); var lvl = 100.0
        for (t in 0..last) {
            var sum = 0.0; var n = 0
            for (s in u) { val r = p.ret[s][t]; if (!r.isNaN()) { sum += r; n++ } }
            ew[t] = if (n == 0) 0.0 else sum / n; lvl *= 1 + ew[t]; idx[t] = lvl
        }
        fun sd(a: Int, b: Int): Double { val xs = (a..b).map { ew[it] }; val m = xs.average(); return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1)) * sqrt(252.0) }
        // rolling 52-week highs/lows per stock, only for the days we report
        val highs = IntArray(p.days); val lows = IntArray(p.days)
        for (s in u) {
            val a = p.adj[s]
            for (d in first..last) {
                val v = a[d]; if (v.isNaN()) continue
                var hi = Float.NEGATIVE_INFINITY; var lo = Float.POSITIVE_INFINITY; var n = 0
                for (t in d - 251 until d) { val x = a[t]; if (!x.isNaN()) { if (x > hi) hi = x; if (x < lo) lo = x; n++ } }
                if (n < 120) continue
                if (v > hi) highs[d]++ else if (v < lo) lows[d]++
            }
        }
        return (first..last).map { d ->
            val above = u.count { s -> Market.sma(p, s, d, 50)?.let { p.adj[s][d] > it } == true }; val known = u.count { s -> Market.sma(p, s, d, 50) != null }
            val breadth = if (known == 0) 50.0 else above * 100.0 / known
            val avg125 = (d - 124..d).map { idx[it] }.average(); val trendX = idx[d] / avg125 - 1
            val mom = idx[d] / idx[d - 21] - 1
            val hl = highs[d] + lows[d]; val highsS = if (hl == 0) 50.0 else highs[d] * 100.0 / hl
            val volNow = sd(d - 19, d); val past = (max(40, d - 251)..d).map { sd(it - 19, it) }
            val calm = 100 - past.count { it <= volNow } * 100.0 / past.size
            val adv = (d - 4..d).map { t -> val up = u.count { p.ret[it][t] > 0 }; val dn = u.count { p.ret[it][t] < 0 }; if (up + dn == 0) 50.0 else up * 100.0 / (up + dn) }.average()
            val parts = listOf(
                Part("Breadth", breadth, "${breadth.toInt()}% of ${u.size} liquid stocks above their 50-day average"),
                Part("Trend", scale(trendX, -0.08, 0.08), "market ${"%+.1f".format(trendX * 100)}% vs its 125-day average"),
                Part("Momentum", scale(mom, -0.08, 0.08), "market ${"%+.1f".format(mom * 100)}% over a month"),
                Part("Highs vs lows", highsS, "${highs[d]} new 52-week highs, ${lows[d]} lows"),
                Part("Calm", calm, "volatility ${"%.0f".format(volNow * 100)}%, calmer than ${"%.0f".format(calm)}% of the past year"),
                Part("Advancers", adv, "${"%.0f".format(adv)}% of moving stocks rose (5-day average)"),
            )
            val score = parts.map { it.score }.average()
            Reading(p.dates[d], score, zone(score), parts)
        }
    }
}
