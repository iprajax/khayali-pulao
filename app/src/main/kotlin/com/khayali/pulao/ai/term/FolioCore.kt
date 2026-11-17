package com.khayali.pulao.ai.term

import com.khayali.pulao.Stock
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * Shared arithmetic for the portfolio features. Everything here runs on the phone: the user's holdings are
 * read from [Fetch.holdings] and never leave it. The only thing a portfolio feature may send out is one
 * symbol at a time to [Fetch.chart] (see [charts]) — never a quantity, an amount or a list of holdings.
 */
object Folio {
    const val CONNECT = "Connect Kite to analyse your portfolio. This view is worked out on the phone from your own holdings, so it needs them loaded first."
    const val INDEX = "^NSEI"
    const val INDEX_NAME = "Nifty 50"
    /** Largest holdings priced per card; keeps a card to a bounded number of requests. */
    const val MAX_PRICED = 15
    /** Fewest daily returns a holding needs to be included in a statistic. */
    const val MIN_RETURNS = 30
    /** Fewest days all priced holdings must share. */
    const val MIN_COMMON = 20
    /** One-sided 95% point of the normal distribution. */
    const val Z95 = 1.645

    /** One holding with its share of the portfolio's current value (0–1). */
    class Pos(val h: Stock.Holding, val weight: Double) {
        val sym: String get() = h.name.trim().uppercase()
    }

    /** Holdings that are actually owned and priced, largest first, with weights that add up to 1. */
    fun positions(holdings: List<Stock.Holding>): List<Pos> {
        val live = holdings.filter { it.name.isNotBlank() && it.qty > 0 && it.current > 0 }.sortedByDescending { it.current }
        val total = live.sumOf { it.current }
        return if (total <= 0) emptyList() else live.map { Pos(it, it.current / total) }
    }

    fun feedSymbol(sym: String) = sym.trim().uppercase().replace(" ", "") + ".NS"

    /** One year of daily prices per symbol, asked one symbol at a time. Nothing but the symbol is sent. */
    suspend fun charts(f: Fetch, symbols: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (s in symbols) out[s] = f.chart(feedSymbol(s), "1y", "1d")
        return out
    }

    // ---------- wording ----------
    fun p(v: Double, digits: Int = 1) = String.format(Locale.US, "%.${digits}f", v) + "%"
    fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
    fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    /** ₹ in lakh / crore for headlines: 123456 → "₹1.23 L". */
    fun short(v: Double): String {
        val a = abs(v); val sign = if (v < 0) "−" else ""
        return when {
            a >= 1e7 -> sign + "₹" + f2(a / 1e7) + " Cr"
            a >= 1e5 -> sign + "₹" + f2(a / 1e5) + " L"
            else -> N.inr(v)
        }
    }
    fun signed(v: Double) = (if (v > 0) "+" else "") + N.inr(v)
    fun span(days: Int) = if (days >= 230) "1 year" else "$days trading days"
    fun r2(v: Double) = Math.round(v * 100) / 100.0
    fun r4(v: Double) = Math.round(v * 10000) / 10000.0

    // ---------- price history shared by risk, correlation, risk-return and stress ----------

    /** A priced holding: its daily returns keyed by the day they ended on. */
    class Line(val pos: Pos, val name: String, val ret: Map<LocalDate, Double>)

    /** Daily returns by date. Two bars on the same day (the feed adds a live bar) count once: the later one wins. */
    fun returnsByDate(s: Series): Map<LocalDate, Double> {
        val close = LinkedHashMap<LocalDate, Double>()
        s.dates.forEachIndexed { i, d -> close[d] = s.close[i] }
        val out = LinkedHashMap<LocalDate, Double>()
        close.entries.zipWithNext { a, b -> if (a.value > 0) out[b.key] = b.value / a.value - 1 }
        return out
    }

    /**
     * The priced part of the portfolio on the days every priced holding (and the index, when given) traded.
     * Weights are today's values, re-based to the priced holdings.
     */
    class Market(val lines: List<Line>, val missing: List<String>, val index: Map<LocalDate, Double>?, val dates: List<LocalDate>, val total: Double) {
        val value = lines.sumOf { it.pos.h.current }
        val days get() = dates.size
        fun weight(l: Line) = if (value > 0) l.pos.h.current / value else 0.0
        fun returns(l: Line): List<Double> = dates.map { l.ret.getValue(it) }
        val indexReturns: List<Double>? = index?.let { m -> dates.map { m.getValue(it) } }
        /** Daily return of the priced holdings at today's weights. */
        val portfolio: List<Double> = dates.map { d -> lines.sumOf { weight(it) * it.ret.getValue(d) } }
        fun beta(l: Line): Double? = indexReturns?.let { Quant.beta(returns(l), it) }
        val coveredPct get() = if (total > 0) value / total * 100 else 0.0
        fun coverage() = "${lines.size} of ${lines.size + missing.size} holdings priced" + if (missing.isEmpty()) "" else " (${p(coveredPct)} of value)"
    }

    /** Total change in percent from compounding daily returns. */
    fun compound(r: List<Double>) = (r.fold(1.0) { a, b -> a * (1 + b) } - 1) * 100

    /**
     * Pure: holdings + chart replies (by holding symbol) + optional index reply → aligned returns.
     * Null when fewer than [need] holdings have usable history or they share too few days.
     */
    fun market(holdings: List<Stock.Holding>, charts: Map<String, String>, indexJson: String?, need: Int = 1, limit: Int = MAX_PRICED): Market? {
        val pos = positions(holdings)
        val lines = mutableListOf<Line>(); val missing = mutableListOf<String>()
        pos.forEachIndexed { i, p ->
            val s = if (i < limit) charts[p.sym]?.let { Series.parse(it) } else null
            val r = s?.let { returnsByDate(it) }
            if (s != null && r != null && r.size >= MIN_RETURNS) lines += Line(p, s.name, r) else missing += p.sym
        }
        if (lines.size < need || lines.isEmpty()) return null
        val index = indexJson?.let { Series.parse(it) }?.let { returnsByDate(it) }?.takeIf { it.size >= MIN_RETURNS }
        var common: Set<LocalDate> = lines.first().ret.keys
        lines.drop(1).forEach { common = common intersect it.ret.keys }
        if (index != null) common = common intersect index.keys
        val dates = common.sorted()
        if (dates.size < MIN_COMMON) return null
        return Market(lines, missing, index, dates, pos.sumOf { it.h.current })
    }

    const val NO_PRICES = "Couldn't get enough price history for your holdings right now. Check the connection and try again."
}
