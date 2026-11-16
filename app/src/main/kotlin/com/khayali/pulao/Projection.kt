package com.khayali.pulao

import kotlin.math.abs
import kotlin.math.floor

enum class Base { AVG, LTP }
enum class QtyMode { AMOUNT, SHARES }

/** What a position would look like after a [pct] move. Pure math — unit tested. */
data class Dream(
    val pct: Double,
    /** Price the move is applied to (avg or LTP). */
    val basePrice: Double,
    /** Price after the move. */
    val price: Double,
    val qty: Double,
    /** What you paid (holding: invested; watchlist: qty × LTP). */
    val cost: Double,
    /** Worth at today's LTP. */
    val now: Double,
    /** Worth at the dream price. */
    val value: Double,
) {
    val profit get() = value - cost
    val vsNow get() = value - now
    val profitPct get() = if (cost != 0.0) profit / cost * 100 else 0.0

    /** Profit after tax, tax charged only on gains. */
    fun afterTax(ratePct: Double) = if (profit > 0) profit * (1 - ratePct / 100) else profit
}

object Projector {
    fun holding(h: Stock.Holding, base: Base, pct: Double): Dream {
        val bp = if (base == Base.AVG) h.avg else h.ltp
        val price = bp * (1 + pct / 100)
        return Dream(pct, bp, price, h.qty, h.invested, h.current, h.qty * price)
    }

    /** [input] is a rupee amount (whole shares bought with it) or a share count. */
    fun quote(q: Stock.Quote, mode: QtyMode, input: Double, pct: Double): Dream {
        val qty = when (mode) {
            QtyMode.SHARES -> input
            QtyMode.AMOUNT -> if (q.ltp > 0) floor(input / q.ltp) else 0.0
        }
        val cost = qty * q.ltp
        val price = q.ltp * (1 + pct / 100)
        return Dream(pct, q.ltp, price, qty, cost, cost, qty * price)
    }

    /** Whole portfolio: "price" is the total value; qty is 1. */
    fun portfolio(p: Stock.Portfolio, base: Base, pct: Double): Dream {
        val bv = if (base == Base.AVG) p.invested else p.current
        val v = bv * (1 + pct / 100)
        return Dream(pct, bv, v, 1.0, p.invested, p.current, v)
    }

    fun project(s: Stock, pct: Double, base: Base, mode: QtyMode, input: Double): Dream = when (s) {
        is Stock.Holding -> holding(s, base, pct)
        is Stock.Quote -> quote(s, mode, input, pct)
        is Stock.Portfolio -> portfolio(s, base, pct)
    }

    /** % move needed to go from [basePrice] to [target]. */
    fun pctForTarget(target: Double, basePrice: Double) =
        if (basePrice > 0) (target / basePrice - 1) * 100 else 0.0
}

object Levels {
    /** Fine 0.5% steps up to 5%, then bigger jumps. */
    const val DEFAULT_GAINS = "0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5, 5, 7, 10, 12, 15, 20, 25, 30"
    const val DEFAULT_LOSSES = "0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5, 5, 7, 10, 12, 15, 20"

    /**
     * Next stop for the −/+ buttons: walk the user's steps (both signs, plus 0).
     * Past the last step, move 5% at a time.
     */
    fun next(gains: List<Double>, losses: List<Double>, pct: Double, up: Boolean): Double {
        val stops = (gains + losses.map { -it } + 0.0).distinct().sorted()
        val eps = 1e-9
        return if (up) stops.firstOrNull { it > pct + eps } ?: (pct + 5)
        else stops.lastOrNull { it < pct - eps } ?: (pct - 5)
    }

    /** "1, 3,abc; 2.5 -5" → [1.0, 2.5, 3.0, 5.0]. Signs are ignored; 0 and absurd values dropped. */
    fun parse(text: String): List<Double> =
        text.split(',', ' ', ';', '\n')
            .mapNotNull { it.trim().removeSuffix("%").toDoubleOrNull() }
            .map { abs(it) }
            .filter { it > 0 && it <= 1000 }
            .distinct()
            .sorted()

    /** Top-to-bottom ladder: biggest gain first, biggest loss last. */
    fun ladder(gains: List<Double>, losses: List<Double>, showLosses: Boolean): List<Double> {
        val g = gains.ifEmpty { parse(DEFAULT_GAINS) }
        return g.sortedDescending() + if (showLosses) losses.sorted().map { -it } else emptyList()
    }
}
