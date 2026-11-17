package com.khayali.pulao.ai.data

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The analysis engine over the user's own copy of NSE's daily data. Pure Kotlin, no I/O:
 * a [Panel] (days × stocks) in, plain results out — market regime, anomalies ("hidden patterns"),
 * a holding's diagnostics, and rule-based strategies with honest backtests. Every result names
 * the rule that produced it; none of it is a recommendation.
 */
class Panel(val dates: List<LocalDate>, val symbols: List<String>, val adj: Array<FloatArray>, val ret: Array<FloatArray>, val value: Array<FloatArray>, val volume: Array<FloatArray>) {
    val days get() = dates.size
    /** Running sums of the adjusted price (NaN days count as 0) and how many days had a price, for O(1) moving averages. */
    internal val cum: Array<DoubleArray> by lazy { Array(symbols.size) { s -> DoubleArray(dates.size + 1).also { c -> for (d in dates.indices) c[d + 1] = c[d] + (adj[s][d].takeIf { !it.isNaN() } ?: 0f) } } }
    internal val cnt: Array<IntArray> by lazy { Array(symbols.size) { s -> IntArray(dates.size + 1).also { c -> for (d in dates.indices) c[d + 1] = c[d] + (if (adj[s][d].isNaN()) 0 else 1) } } }
    internal val liquidAt = java.util.concurrent.ConcurrentHashMap<Int, List<Int>>()
    private val index = symbols.withIndex().associate { it.value to it.index }
    fun of(symbol: String) = index[symbol.uppercase()]

    companion object {
        /**
         * Builds a panel from days in order. Prices are chained from NSE's adjusted previous close, so splits
         * and bonuses don't show up as crashes. Missing days for a stock are NaN.
         */
        fun build(days: List<Pair<LocalDate, List<Bhavcopy.Bar>>>): Panel {
            val sorted = days.sortedBy { it.first }
            val syms = sorted.flatMap { d -> d.second.map { it.symbol } }.distinct().sorted()
            val ix = syms.withIndex().associate { it.value to it.index }
            val n = sorted.size
            val adj = Array(syms.size) { FloatArray(n) { Float.NaN } }; val ret = Array(syms.size) { FloatArray(n) { Float.NaN } }
            val value = Array(syms.size) { FloatArray(n) { Float.NaN } }; val vol = Array(syms.size) { FloatArray(n) { Float.NaN } }
            val last = DoubleArray(syms.size) { Double.NaN }
            sorted.forEachIndexed { d, (_, bars) ->
                for (b in bars) {
                    val s = ix[b.symbol] ?: continue
                    val r = if (b.prev > 0) b.close / b.prev - 1 else 0.0
                    val a = if (last[s].isNaN()) b.close else last[s] * (1 + r)
                    last[s] = a; adj[s][d] = a.toFloat(); ret[s][d] = r.toFloat(); value[s][d] = b.value.toFloat(); vol[s][d] = b.volume.toFloat()
                }
            }
            return Panel(sorted.map { it.first }, syms, adj, ret, value, vol)
        }
    }
}

object Market {
    const val LIQUID_VALUE = 5e7          // median ₹5 crore traded a day

    private fun FloatArray.at(d: Int) = if (d in indices) this[d] else Float.NaN
    /** Last non-missing value at or before [d]. */
    private fun FloatArray.asOf(d: Int): Float { var i = minOf(d, size - 1); while (i >= 0) { if (!this[i].isNaN()) return this[i]; i-- }; return Float.NaN }
    private fun median(xs: List<Float>) = xs.filter { !it.isNaN() }.sorted().let { if (it.isEmpty()) Float.NaN else it[it.size / 2] }
    private fun mean(xs: List<Double>) = if (xs.isEmpty()) 0.0 else xs.sum() / xs.size
    private fun sd(xs: List<Double>): Double { if (xs.size < 2) return 0.0; val m = mean(xs); return sqrt(xs.sumOf { (it - m).pow(2) } / (xs.size - 1)) }

    /** Change in the adjusted price over [back] trading days ending at [d], or null. */
    fun change(p: Panel, s: Int, d: Int, back: Int): Double? {
        val a = p.adj[s].at(d); val b = p.adj[s].asOf(d - back)
        return if (a.isNaN() || b.isNaN() || b <= 0f || d - back < 0) null else (a / b - 1.0)
    }
    fun sma(p: Panel, s: Int, d: Int, n: Int): Double? {
        if (d + 1 < n) return null
        val k = p.cnt[s][d + 1] - p.cnt[s][d + 1 - n]
        return if (k < n * 0.8) null else (p.cum[s][d + 1] - p.cum[s][d + 1 - n]) / k
    }
    fun vol(p: Panel, s: Int, d: Int, n: Int): Double? {
        if (d + 1 < n) return null
        val r = (d - n + 1..d).map { p.ret[s][it] }.filter { !it.isNaN() }.map { it.toDouble() }
        return if (r.size < n * 0.8) null else sd(r) * sqrt(252.0)
    }
    fun liquid(p: Panel, d: Int, lookback: Int = 60): List<Int> {
        if (lookback == 60) p.liquidAt[d]?.let { return it }
        val r = p.symbols.indices.filter { s -> !p.adj[s].at(d).isNaN() && median((max(0, d - lookback + 1)..d).map { p.value[s][it] }) >= LIQUID_VALUE }
        if (lookback == 60) p.liquidAt[d] = r
        return r
    }

    // ------------------------------------------------------------------ regime

    data class Regime(val date: LocalDate, val stocks: Int, val above50: Double, val above200: Double, val advances: Int, val declines: Int,
                      val newHighs: Int, val newLows: Int, val market1d: Double, val market1w: Double, val market1m: Double,
                      val volNow: Double?, val volPercentile: Double?, val label: String)

    /** The state of the (liquid) market on day [d]: participation, trend, new highs/lows, volatility. */
    fun regime(p: Panel, d: Int = p.days - 1): Regime {
        val u = liquid(p, d)
        fun share(f: (Int) -> Boolean?) = u.mapNotNull(f).let { if (it.isEmpty()) 0.0 else it.count { b -> b } * 100.0 / it.size }
        val a50 = share { s -> sma(p, s, d, 50)?.let { p.adj[s][d] > it } }
        val a200 = share { s -> sma(p, s, d, 200)?.let { p.adj[s][d] > it } }
        val adv = u.count { (p.ret[it].at(d)) > 0 }; val dec = u.count { p.ret[it].at(d) < 0 }
        val look = minOf(252, d)
        val hi = u.count { s -> val w = (d - look..d).map { p.adj[s][it] }.filter { !it.isNaN() }; w.size > look / 2 && p.adj[s][d] >= w.max() }
        val lo = u.count { s -> val w = (d - look..d).map { p.adj[s][it] }.filter { !it.isNaN() }; w.size > look / 2 && p.adj[s][d] <= w.min() }
        // equal-weight market of the liquid universe
        val ew = (0..d).map { t -> u.map { p.ret[it].at(t) }.filter { !it.isNaN() }.let { if (it.isEmpty()) 0.0 else it.average() } }
        fun cum(n: Int) = ew.takeLast(n).fold(1.0) { a, r -> a * (1 + r) } - 1
        val vNow = if (ew.size >= 20) sd(ew.takeLast(20)) * sqrt(252.0) else null
        val vHist = if (ew.size >= 80) (20..ew.size).map { sd(ew.subList(it - 20, it)) * sqrt(252.0) }.takeLast(252) else emptyList()
        val vPct = vNow?.let { v -> if (vHist.isEmpty()) null else vHist.count { it <= v } * 100.0 / vHist.size }
        val label = when {
            a200 >= 60 && a50 >= 55 -> "Broad uptrend"
            a200 <= 40 && a50 <= 45 -> "Broad downtrend"
            a50 > a200 + 10 -> "Recovering"
            a50 < a200 - 10 -> "Weakening"
            else -> "Mixed"
        }
        return Regime(p.dates[d], u.size, a50, a200, adv, dec, hi, lo, cum(1), cum(5), cum(21), vNow, vPct, label)
    }

    // --------------------------------------------------------------- anomalies

    data class Anomaly(val symbol: String, val kind: String, val detail: String, val score: Double, val move: Double)

    /** Unusual behaviour on day [d] among liquid stocks, strongest first. "Unusual" is always relative to the stock's own history. */
    fun anomalies(p: Panel, d: Int = p.days - 1, max: Int = 12): List<Anomaly> {
        val out = mutableListOf<Anomaly>()
        for (s in liquid(p, d)) {
            val sym = p.symbols[s]; val r = p.ret[s].at(d).toDouble(); if (r.isNaN()) continue
            val medVol = median((max(0, d - 20)..d - 1).map { p.volume[s][it] }).toDouble()
            val v = p.volume[s].at(d).toDouble()
            val sigma = vol(p, s, d - 1, 60)?.let { it / sqrt(252.0) }
            if (medVol > 0 && v / medVol >= 4) out += Anomaly(sym, "Volume spike", "${"%.1f".format(v / medVol)}× its usual volume", v / medVol, r)
            if (sigma != null && sigma > 0 && abs(r) / sigma >= 3.5) out += Anomaly(sym, if (r > 0) "Outsized rise" else "Outsized fall", "a ${"%.1f".format(abs(r) / sigma)}-sigma day for this stock", abs(r) / sigma, r)
            var k = 0; var dir = 0
            for (t in d downTo max(0, d - 15)) { val x = p.ret[s][t]; if (x.isNaN() || x == 0f) break; val sg = if (x > 0) 1 else -1; if (dir == 0) dir = sg; if (sg != dir) break; k++ }
            if (k >= 7) out += Anomaly(sym, if (dir > 0) "Winning streak" else "Losing streak", "$k sessions in a row", k.toDouble() / 2, r)
            val w = (max(0, d - 252)..d - 1).map { p.adj[s][it] }.filter { !it.isNaN() }
            if (w.size > 120 && p.adj[s][d] > w.max() && medVol > 0 && v / medVol >= 2) out += Anomaly(sym, "52-week breakout", "new 52-week high on ${"%.1f".format(v / medVol)}× volume", 3.0 + v / medVol / 4, r)
        }
        return out.sortedByDescending { it.score }.distinctBy { it.symbol }.take(max)
    }

    // ---------------------------------------------------------------- holdings

    data class Health(val symbol: String, val r1w: Double?, val r1m: Double?, val r3m: Double?, val r1y: Double?, val vs50: Double?, val vs200: Double?,
                      val fromHigh: Double?, val vol: Double?, val flags: List<String>)

    fun health(p: Panel, symbol: String, d: Int = p.days - 1): Health? {
        val s = p.of(symbol) ?: return null
        if (p.adj[s].at(d).isNaN()) return null
        val px = p.adj[s][d].toDouble()
        val s50 = sma(p, s, d, 50); val s200 = sma(p, s, d, 200)
        val hi = (max(0, d - 252)..d).map { p.adj[s][it] }.filter { !it.isNaN() }.maxOrNull()?.toDouble()
        val flags = mutableListOf<String>()
        s200?.let { if (px < it) flags += "below its 200-day average" }
        s50?.let { a -> s200?.let { b -> if (a < b) flags += "50-day average under the 200-day (downtrend)" } }
        hi?.let { if (px / it - 1 <= -0.25) flags += "more than 25% below its 52-week high" }
        anomalies(p, d, 500).firstOrNull { it.symbol == p.symbols[s] }?.let { flags += it.kind.lowercase() + " today" }
        return Health(p.symbols[s], change(p, s, d, 5), change(p, s, d, 21), change(p, s, d, 63), change(p, s, d, 252),
            s50?.let { px / it - 1 }, s200?.let { px / it - 1 }, hi?.let { px / it - 1 }, vol(p, s, d, 60), flags)
    }

    // --------------------------------------------------------------- strategies

    /** A rule that ranks stocks on a day using only data up to that day. */
    data class Strategy(val id: String, val name: String, val rule: String, val top: Int, val need: Int, val pick: (Panel, Int, List<Int>) -> List<Int>)

    val STRATEGIES = listOf(
        Strategy("momentum", "12-1 month momentum", "Rank liquid stocks by their return from 12 months to 1 month ago; hold the top 20, rebalanced monthly.", 20, 253) { p, d, u ->
            u.mapNotNull { s -> val a = p.adj[s].asOf(d - 21); val b = p.adj[s].asOf(d - 252); if (a.isNaN() || b.isNaN() || b <= 0f || d < 252) null else s to (a / b - 1.0) }
                .sortedByDescending { it.second }.map { it.first } },
        Strategy("highs", "Near 52-week high", "Liquid stocks above their 200-day average, ranked by closeness to their 52-week high; top 20, monthly.", 20, 201) { p, d, u ->
            u.mapNotNull { s -> val px = p.adj[s][d]; val s200 = sma(p, s, d, 200) ?: return@mapNotNull null; if (px < s200) return@mapNotNull null
                val hi = (max(0, d - 252)..d).map { p.adj[s][it] }.filter { !it.isNaN() }.max(); s to px / hi.toDouble() }.sortedByDescending { it.second }.map { it.first } },
        Strategy("lowvol", "Low volatility", "The 20 liquid stocks with the lowest volatility over the last 60 sessions, monthly.", 20, 61) { p, d, u ->
            u.mapNotNull { s -> vol(p, s, d, 60)?.let { s to it } }.sortedBy { it.second }.map { it.first } },
        Strategy("trend", "Trend following", "Liquid stocks whose 50-day average is above their 200-day average and price above both, ranked by 3-month return; top 20, monthly.", 20, 201) { p, d, u ->
            u.mapNotNull { s -> val px = p.adj[s][d].toDouble(); val a = sma(p, s, d, 50) ?: return@mapNotNull null; val b = sma(p, s, d, 200) ?: return@mapNotNull null
                if (a > b && px > a) change(p, s, d, 63)?.let { s to it } else null }.sortedByDescending { it.second }.map { it.first } },
        Strategy("reversal", "Pullback in an uptrend", "Liquid stocks above their 200-day average that fell the most over the last month; top 15, monthly.", 15, 201) { p, d, u ->
            u.mapNotNull { s -> val b = sma(p, s, d, 200) ?: return@mapNotNull null; if (p.adj[s][d] < b) return@mapNotNull null; change(p, s, d, 21)?.let { s to it } }
                .sortedBy { it.second }.map { it.first } },
        Strategy("breakout", "Breakout on volume", "Liquid stocks closing at a 50-day high with volume at least twice its 50-day average; strongest volume first, top 15, monthly.", 15, 51) { p, d, u ->
            u.mapNotNull { s -> val w = (d - 50 until d).map { p.adj[s][it] }.filter { !it.isNaN() }; if (w.size < 40 || p.adj[s][d] <= w.max()) return@mapNotNull null
                val v = meanAt(p.volume[s], d - 50, d - 1) ?: return@mapNotNull null; val now = p.volume[s][d].toDouble(); if (v <= 0 || now < 2 * v) null else s to now / v }
                .sortedByDescending { it.second }.map { it.first } },
        Strategy("mom6", "6-month momentum", "Rank liquid stocks by their 6-month return, skipping the last week; top 20, monthly.", 20, 127) { p, d, u ->
            u.mapNotNull { s -> val a = p.adj[s].asOf(d - 5); val b = p.adj[s].asOf(d - 126); if (a.isNaN() || b.isNaN() || b <= 0f) null else s to (a / b - 1.0) }
                .sortedByDescending { it.second }.map { it.first } },
        Strategy("sharpe", "Steady compounders", "Liquid stocks with the best 1-year return per unit of volatility (a Sharpe-like ratio); top 20, monthly.", 20, 253) { p, d, u ->
            u.mapNotNull { s -> val r = change(p, s, d, 252) ?: return@mapNotNull null; val v = vol(p, s, d, 252) ?: return@mapNotNull null; if (v <= 0) null else s to r / v }
                .sortedByDescending { it.second }.map { it.first } },
        Strategy("lowbeta", "Low beta", "The 20 liquid stocks that moved least with the market over the last 6 months (lowest beta), monthly.", 20, 121) { p, d, u ->
            val m = marketReturns(p, d, u, 120); u.mapNotNull { s -> beta(p, s, d, u, 120, m)?.let { s to it } }.sortedBy { it.second }.map { it.first } },
        Strategy("accumulation", "Accumulation", "Liquid stocks up over 3 months whose last-month volume grew most against their 6-month average; top 20, monthly.", 20, 127) { p, d, u ->
            u.mapNotNull { s -> val r = change(p, s, d, 63) ?: return@mapNotNull null; if (r <= 0) return@mapNotNull null
                val recent = meanAt(p.volume[s], d - 20, d) ?: return@mapNotNull null; val base = meanAt(p.volume[s], d - 126, d - 21) ?: return@mapNotNull null
                if (base <= 0) null else s to recent / base }.sortedByDescending { it.second }.map { it.first } },
        Strategy("rsi", "RSI pullback", "Liquid stocks above their 200-day average with the lowest 14-day RSI (short-term weakness inside a long uptrend); top 15, monthly.", 15, 201) { p, d, u ->
            u.mapNotNull { s -> val b = sma(p, s, d, 200) ?: return@mapNotNull null; if (p.adj[s][d] < b) return@mapNotNull null; rsi(p, s, d)?.let { s to it } }
                .sortedBy { it.second }.map { it.first } },
        Strategy("nolottery", "No lottery tickets", "Liquid stocks whose biggest single-day jump last month was smallest, avoiding lottery-like stocks; top 20, monthly.", 20, 22) { p, d, u ->
            u.mapNotNull { s -> (d - 20..d).map { p.ret[s][it] }.filter { !it.isNaN() }.takeIf { it.size >= 15 }?.max()?.let { s to it.toDouble() } }
                .sortedBy { it.second }.map { it.first } },
        Strategy("largecap", "Largest traded", "The 30 stocks with the highest median daily traded value over 3 months, a proxy for the biggest companies, equal-weighted, monthly.", 30, 61) { p, d, u ->
            u.mapNotNull { s -> (d - 60..d).map { p.value[s][it] }.filter { !it.isNaN() }.sorted().takeIf { it.isNotEmpty() }?.let { s to it[it.size / 2].toDouble() } }
                .sortedByDescending { it.second }.map { it.first } },
        Strategy("weekrev", "Weekly reversal", "Liquid stocks that fell the most over the last week, betting on a short-term bounce; top 20, monthly.", 20, 6) { p, d, u ->
            u.mapNotNull { s -> change(p, s, d, 5)?.let { s to it } }.sortedBy { it.second }.map { it.first } },
    )

    /** 14-day RSI from daily returns (simple averages of up and down days). */
    fun rsi(p: Panel, s: Int, d: Int, n: Int = 14): Double? {
        if (d < n) return null
        val r = (d - n + 1..d).map { p.ret[s][it] }.filter { !it.isNaN() }
        if (r.size < n * 0.8) return null
        val up = r.filter { it > 0 }.sum().toDouble(); val dn = -r.filter { it < 0 }.sum().toDouble()
        return if (dn == 0.0) 100.0 else 100 - 100 / (1 + up / dn)
    }
    /** Beta of a stock against the equal-weight liquid market over [n] days. */
    fun beta(p: Panel, s: Int, d: Int, u: List<Int>, n: Int = 120, market: DoubleArray = marketReturns(p, d, u, n)): Double? {
        if (d < n) return null
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for ((i, t) in (d - n + 1..d).withIndex()) {
            val y = p.ret[s][t]; if (y.isNaN() || market[i].isNaN()) continue
            xs += market[i]; ys += y.toDouble()
        }
        if (xs.size < n * 0.8) return null
        val mx = xs.average(); val my = ys.average()
        val cov = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }; val v = xs.sumOf { (it - mx).pow(2) }
        return if (v == 0.0) null else cov / v
    }
    /** Equal-weight daily return of [u] for the [n] days ending at [d] (NaN where nothing traded). */
    fun marketReturns(p: Panel, d: Int, u: List<Int>, n: Int): DoubleArray = DoubleArray(n) { i ->
        val t = d - n + 1 + i; if (t < 0) return@DoubleArray Double.NaN
        var sum = 0.0; var k = 0; for (o in u) { val v = p.ret[o][t]; if (!v.isNaN()) { sum += v; k++ } }
        if (k == 0) Double.NaN else sum / k
    }

    private val tested = java.util.WeakHashMap<Panel, MutableMap<String, Backtest?>>()

    /** Backtests every strategy that has enough history, from one common start, in parallel across the phone's cores. */
    fun backtestAll(p: Panel): List<Pair<Strategy, Backtest>> {
        val w = commonWarmup(p) ?: return emptyList()
        val todo = STRATEGIES.filter { it.need <= w }
        monthEnds(p).filter { it >= w }.parallelStream().forEach { liquid(p, it) }      // shared by every strategy
        return todo.parallelStream().map { st -> st to backtestCached(p, st, w) }.collect(java.util.stream.Collectors.toList()).mapNotNull { (st, b) -> b?.let { st to it } }
    }
    /** [backtest], remembered per panel: the panel is replaced when the data changes, so results never go stale. */
    fun backtestCached(p: Panel, st: Strategy, warmup: Int = st.need): Backtest? {
        val key = "${st.id}@$warmup"
        synchronized(tested) { tested[p]?.let { m -> if (m.containsKey(key)) return m[key] } }
        val b = backtest(p, st, warmup)
        synchronized(tested) { tested.getOrPut(p) { HashMap() }[key] = b }
        return b
    }

    private fun meanAt(arr: FloatArray, from: Int, to: Int): Double? = (maxOf(0, from)..to).map { arr[it] }.filter { !it.isNaN() }.takeIf { it.isNotEmpty() }?.average()

    fun strategy(id: String) = STRATEGIES.firstOrNull { it.id == id.lowercase() }

    data class Backtest(val strategy: String, val from: LocalDate, val to: LocalDate, val months: Int, val cagr: Double, val benchCagr: Double, val vol: Double, val maxDrawdown: Double,
                        val beatMonths: Double, val curve: List<Pair<LocalDate, Double>>, val bench: List<Pair<LocalDate, Double>>, val current: List<String>) {
        val total get() = curve.last().second - 1
        val benchTotal get() = bench.last().second - 1
        /** Under a year, a yearly rate exaggerates; the total over the period is the honest figure. */
        val annualised get() = months >= 12
        val rate get() = if (annualised) cagr else total
        val benchRate get() = if (annualised) benchCagr else benchTotal
    }

    /** One common start for comparing strategies: the latest warm-up any of them needs that still leaves 3 months to test. */
    fun commonWarmup(p: Panel): Int? = STRATEGIES.map { it.need }.filter { w -> monthEnds(p).count { it >= w } >= 3 }.maxOrNull()

    /** Indices of the last trading day of each month. */
    fun monthEnds(p: Panel): List<Int> = p.dates.indices.filter { i -> i == p.days - 1 || p.dates[i + 1].month != p.dates[i].month }

    /**
     * Monthly-rebalanced, equal-weight backtest of [st] against the equal-weight liquid market. Picks use only data
     * up to the rebalance day. A stock that stops trading is held at its last price. No costs or taxes — the result
     * says so.
     */
    fun backtest(p: Panel, st: Strategy, warmup: Int = st.need): Backtest? {
        val ends = monthEnds(p).filter { it >= maxOf(warmup, st.need) }
        if (ends.size < 3) return null
        var eq = 1.0; var be = 1.0; val curve = mutableListOf(p.dates[ends[0]] to 1.0); val bench = mutableListOf(p.dates[ends[0]] to 1.0)
        val monthly = mutableListOf<Double>(); var beat = 0
        for (k in 0 until ends.size - 1) {
            val d = ends[k]; val nxt = ends[k + 1]; val u = liquid(p, d)
            val picks = st.pick(p, d, u).take(st.top)
            fun hold(s: Int): Double? { val a = p.adj[s][d]; val b = p.adj[s].asOf(nxt); return if (a.isNaN() || b.isNaN() || a <= 0f) null else b / a - 1.0 }
            val r = picks.mapNotNull(::hold).let { if (it.isEmpty()) 0.0 else it.average() }
            val rb = u.mapNotNull(::hold).let { if (it.isEmpty()) 0.0 else it.average() }
            eq *= 1 + r; be *= 1 + rb; monthly += r; if (r > rb) beat++
            curve += p.dates[nxt] to eq; bench += p.dates[nxt] to be
        }
        val years = (ends.size - 1) / 12.0
        var peak = 0.0; var mdd = 0.0; for ((_, v) in curve) { peak = max(peak, v); mdd = minOf(mdd, v / peak - 1) }
        val last = p.days - 1
        return Backtest(st.id, p.dates[ends[0]], p.dates[ends.last()], ends.size - 1, eq.pow(1 / years) - 1, be.pow(1 / years) - 1, sd(monthly) * sqrt(12.0), mdd,
            beat * 100.0 / (ends.size - 1), curve, bench, st.pick(p, last, liquid(p, last)).take(st.top).map { p.symbols[it] })
    }
}
