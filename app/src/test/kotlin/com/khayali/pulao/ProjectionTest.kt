package com.khayali.pulao

import org.junit.Assert.assertEquals
import org.junit.Test

/** Projection math across scenarios, on a fictional holding. */
class ProjectionTest {

    // Fictional: 250 shares, avg 1,200, invested 3,00,000, LTP 1,450 (value 3,62,500).
    private val infra = Stock.Holding("INFRA", 250.0, 1200.0, 300000.0, 1450.0)
    private val chai = Stock.Quote("CHAI CO", "NSE", 205.29)

    @Test fun holdingFromAvg() {
        val d = Projector.holding(infra, Base.AVG, 10.0)
        assertEquals(1320.0, d.price, 1e-9)
        assertEquals(330000.0, d.value, 1e-6)
        assertEquals(30000.0, d.profit, 1e-6)
        assertEquals(10.0, d.profitPct, 1e-6)
    }

    @Test fun holdingFromLtp() {
        val d = Projector.holding(infra, Base.LTP, 10.0)
        assertEquals(1595.0, d.price, 1e-9)
        assertEquals(36250.0, d.vsNow, 1e-6)            // +10% of today's 3,62,500
        assertEquals(98750.0, d.profit, 1e-6)           // total P&L vs invested
    }

    @Test fun zeroPctFromLtpEqualsToday() {
        val d = Projector.holding(infra, Base.LTP, 0.0)
        assertEquals(infra.current, d.value, 1e-6)
        assertEquals(infra.pnl, d.profit, 1e-6)
        assertEquals(0.0, d.vsNow, 1e-6)
    }

    @Test fun holdingLosses() {
        val d = Projector.holding(infra, Base.AVG, -20.0)
        assertEquals(960.0, d.price, 1e-9)
        assertEquals(-60000.0, d.profit, 1e-6)
        assertEquals(-20.0, d.profitPct, 1e-6)
    }

    @Test fun watchlistByShares() {
        val d = Projector.quote(chai, QtyMode.SHARES, 100.0, 5.0)
        assertEquals(100.0, d.qty, 0.0)
        assertEquals(20529.0, d.cost, 1e-6)
        assertEquals(21555.45, d.value, 1e-6)
        assertEquals(1026.45, d.profit, 1e-6)
    }

    @Test fun watchlistByAmountBuysWholeShares() {
        val d = Projector.quote(chai, QtyMode.AMOUNT, 10_000.0, 10.0)
        assertEquals(48.0, d.qty, 0.0)                      // floor(10000 / 205.29)
        assertEquals(48 * 205.29, d.cost, 1e-6)
        assertEquals(10.0, d.profitPct, 1e-6)
    }

    @Test fun amountTooSmallForOneShare() {
        val d = Projector.quote(Stock.Quote("PRICEY", "NSE", 1_50_000.0), QtyMode.AMOUNT, 10_000.0, 10.0)
        assertEquals(0.0, d.qty, 0.0)
        assertEquals(0.0, d.profit, 0.0)
        assertEquals(0.0, d.profitPct, 0.0)                 // no divide-by-zero
    }

    @Test fun targetPriceToPct() {
        assertEquals(25.0, Projector.pctForTarget(1500.0, 1200.0), 1e-9)
        assertEquals(-50.0, Projector.pctForTarget(600.0, 1200.0), 1e-9)
        assertEquals(0.0, Projector.pctForTarget(100.0, 0.0), 0.0)
        // round-trip
        val p = Projector.pctForTarget(2500.0, infra.ltp)
        assertEquals(2500.0, Projector.holding(infra, Base.LTP, p).price, 1e-9)
    }

    @Test fun taxOnlyOnGains() {
        val gain = Projector.holding(infra, Base.AVG, 10.0)
        assertEquals(gain.profit * 0.8, gain.afterTax(20.0), 1e-6)
        val loss = Projector.holding(infra, Base.AVG, -10.0)
        assertEquals(loss.profit, loss.afterTax(20.0), 1e-6)
    }

    @Test fun levelParsing() {
        assertEquals(listOf(1.0, 2.5, 3.0, 5.0), Levels.parse("1, 3,abc; 2.5 -5, 3, 0, 5%"))
        assertEquals(emptyList<Double>(), Levels.parse(""))
        assertEquals(listOf(1000.0), Levels.parse("1000, 5000"))
    }

    @Test fun ladderOrder() {
        val l = Levels.ladder(listOf(1.0, 10.0, 5.0), listOf(3.0, 1.0), showLosses = true)
        assertEquals(listOf(10.0, 5.0, 1.0, -1.0, -3.0), l)
        assertEquals(listOf(10.0, 5.0, 1.0), Levels.ladder(listOf(1.0, 10.0, 5.0), listOf(3.0), showLosses = false))
        // empty gains fall back to defaults
        assertEquals(Levels.parse(Levels.DEFAULT_GAINS).sortedDescending(), Levels.ladder(emptyList(), emptyList(), false))
    }

    @Test fun defaultStepsAreHalfPercentTo5ThenBigger() {
        assertEquals(
            listOf(0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0, 7.0, 10.0, 12.0, 15.0, 20.0, 25.0, 30.0),
            Levels.parse(Levels.DEFAULT_GAINS),
        )
    }

    @Test fun plusMinusWalkTheSteps() {
        val g = Levels.parse(Levels.DEFAULT_GAINS)
        val l = Levels.parse(Levels.DEFAULT_LOSSES)
        fun up(p: Double) = Levels.next(g, l, p, up = true)
        fun down(p: Double) = Levels.next(g, l, p, up = false)
        assertEquals(0.5, up(0.0), 0.0)
        assertEquals(1.0, up(0.5), 0.0)
        assertEquals(5.0, up(4.5), 0.0)
        assertEquals(7.0, up(5.0), 0.0)
        assertEquals(10.0, up(7.0), 0.0)
        assertEquals(12.0, up(10.0), 0.0)
        assertEquals(15.0, up(12.0), 0.0)
        assertEquals(12.0, up(11.3), 0.0)        // from a typed target, snaps to next stop
        assertEquals(35.0, up(30.0), 0.0)        // past the last step: +5
        assertEquals(0.0, down(0.5), 0.0)
        assertEquals(-0.5, down(0.0), 0.0)
        assertEquals(-7.0, down(-5.0), 0.0)
        assertEquals(-25.0, down(-20.0), 0.0)    // past the last loss: −5
    }
}
