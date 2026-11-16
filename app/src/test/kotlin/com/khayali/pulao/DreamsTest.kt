package com.khayali.pulao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamsTest {

    @Test fun yearsToGoal() {
        assertEquals(6.116, Dreams.yearsToGoal(100.0, 200.0, 12.0)!!, 0.001)   // rule of 72-ish
        assertEquals(0.0, Dreams.yearsToGoal(200.0, 100.0, 12.0)!!, 0.0)       // already there
        assertNull(Dreams.yearsToGoal(100.0, 200.0, 0.0))
        assertNull(Dreams.yearsToGoal(0.0, 200.0, 12.0))
    }

    @Test fun compoundRoundTrip() {
        val fv = Dreams.compound(1e7, 12.0, 10.0)
        assertEquals(1e7 * 3.1058482, fv, 1.0)
        assertEquals(10.0, Dreams.yearsToGoal(1e7, fv, 12.0)!!, 1e-9)
        assertEquals(6.116, Dreams.doublingYears(12.0)!!, 0.001)
    }

    @Test fun goalPct() {
        assertEquals(100.0, Dreams.pctToGoal(1e7, 2e7), 1e-9)
        assertEquals(0.0, Dreams.pctToGoal(0.0, 2e7), 0.0)
    }

    @Test fun niceRounding() {
        assertEquals(3e7, Dreams.niceCeil(2.81e7), 1e-6)          // 2.81 Cr -> 3 Cr
        assertEquals(1.5e7, Dreams.niceCeil(1.41e7), 1e-6)        // 1.41 Cr -> 1.5 Cr
        assertEquals(1e5, Dreams.niceCeil(1e5), 1e-6)
        assertEquals(7.5e4, Dreams.niceCeil(6.1e4), 1e-6)
        val chips = Dreams.goalChips(1_23_45_678.0)
        assertTrue(chips.all { it > 1_23_45_678.0 })
        assertEquals(chips, chips.sorted())
        assertEquals(listOf(2e7, 2.5e7, 4e7, 7.5e7, 1.5e8), chips)
    }

    @Test fun regret() {
        val r = Dreams.regret(250.0, 960.0, 1450.0)
        assertEquals(240000.0, r.paid, 1e-6)
        assertEquals(362500.0, r.now, 1e-6)
        assertEquals(51.04, r.gainPct, 0.01)
    }

    @Test fun yearsLabel() {
        assertEquals("6 months", Dreams.years(0.45))
        assertEquals("6.1 years", Dreams.years(6.116))
        assertEquals("3 years", Dreams.years(3.0))
        assertEquals("under a month", Dreams.years(0.01))
    }

    @Test fun pulaoMaths() {
        assertNull(PulaoMaths.describe(10.0))
        assertEquals("≈ 1 cutting chai ☕", PulaoMaths.describe(15.0))
        assertEquals("≈ 1 plate of biryani 🍛  ·  20 cutting chais ☕", PulaoMaths.describe(300.0))
        assertEquals("≈ 1 plot in Jhumri Telaiya 🏡  ·  1,00,000 cutting chais ☕", PulaoMaths.describe(15_00_000.0))
        assertEquals("≈ 1 plot in Ballia 🌾  ·  60,000 cutting chais ☕", PulaoMaths.describe(9_00_000.0))
        assertEquals("≈ 1 plot in Jhumri Telaiya 🏡  ·  90,000 cutting chais ☕", PulaoMaths.describe(13_50_000.0))
        assertEquals("≈ 1 family trip to Ballia on Pawan Express (6 people, 3AC) 🚂  ·  2,000 cutting chais ☕ less", PulaoMaths.describe(-30_000.0))
        assertEquals("≈ 2 Ballia → Sealdah tickets on Bhrigu Express 🚆  ·  200 cutting chais ☕", PulaoMaths.describe(3_000.0))
        assertTrue(PulaoMaths.ITEMS.none { it.one.contains("Goa") })
        // No brands any more.
        assertTrue(PulaoMaths.ITEMS.none { it.one.contains("Nexon") || it.one.contains("Enfield") || it.one.contains("iPhone") })
    }

    @Test fun customDreamUnit() {
        val thar = PulaoMaths.Item("Thar for the farm", "Thar for the farm", "⭐", 15_00_000.0)
        assertEquals("≈ 1 Thar for the farm ⭐  ·  1,10,000 cutting chais ☕", PulaoMaths.describe(16_50_000.0, thar))
        assertEquals(thar, PulaoMaths.items(thar).first { it.emoji == "⭐" })
    }

    @Test fun masalaQuips() {
        val m = Tone.MASALA
        assertTrue(PulaoMaths.quip(-50_000.0, -25.0, m)!!.contains("Notional"))
        assertTrue(PulaoMaths.quip(-500.0, -2.0, m)!!.contains("only a loss"))
        assertTrue(PulaoMaths.quip(100.0, 0.5, m)!!.contains("chai"))
        assertTrue(PulaoMaths.quip(5_000.0, 3.0, m)!!.contains("FD wale uncle"))
        assertTrue(PulaoMaths.quip(5_00_000.0, 16.16, m)!!.contains("Ballia"))
        assertTrue(PulaoMaths.quip(5_00_000.0, 150.0, m)!!.contains("Buffett"))
        assertTrue(PulaoMaths.fdLine(false, m)!!.contains("Maine kaha tha"))
    }

    @Test fun seriousIsSilent() {
        assertNull(PulaoMaths.quip(5_00_000.0, 16.16, Tone.SERIOUS))
        assertNull(PulaoMaths.fdLine(true, Tone.SERIOUS))
    }

    @Test fun lightIsCalmNoTaunts() {
        val cases = listOf(-50_000.0 to -25.0, -500.0 to -2.0, 100.0 to 0.5, 5_000.0 to 3.0,
            5_00_000.0 to 16.16, 5_00_000.0 to 45.0, 5_00_000.0 to 150.0)
        val taunts = listOf("uncle", "Sharma", "Buffett", "Rishtedaar", "dil", "chai", "Ballia", "Party")
        for ((g, p) in cases) {
            val line = PulaoMaths.quip(g, p, Tone.LIGHT)!!
            taunts.forEach { assertFalse("'$line' has '$it'", line.contains(it, ignoreCase = true)) }
        }
        assertTrue(PulaoMaths.fdLine(true, Tone.LIGHT)!!.contains("Ahead of a fixed deposit"))
    }

    @Test fun benchRatesAreConfigurable() {
        val b = Bench.list(7.0, 6.0, savings = 3.0, ppf = 7.5, gold = 11.0, nifty = 13.0)
        assertEquals(listOf(3.0, 7.0, 7.5, 11.0, 13.0, 6.0), b.map { it.ratePct })
    }

    @Test fun portfolioFromKiteNodes() {
        // Same node order as Kite's Holdings screen; figures are fictional.
        val descs = listOf(
            "Open recent chart", "NIFTY 50\nlast traded price 25,291.10", "Holdings\ntab, contains 1 items.",
            "Invested", "Current", "3,00,000", "3,62,500", "P&L\n+62,500\n+20.83%", "Search",
        )
        val p = KiteParser.portfolio(descs)!!
        assertEquals(300000.0, p.invested, 0.0)
        assertEquals(362500.0, p.current, 0.0)
        assertEquals(20.83, p.pnlPct, 0.01)
        assertNull(KiteParser.portfolio(listOf("Invested", "3,00,000")))
    }

    @Test fun portfolioProjection() {
        val p = Stock.Portfolio(300000.0, 362500.0)
        val d = Projector.project(p, 10.0, Base.LTP, QtyMode.AMOUNT, 0.0)
        assertEquals(398750.0, d.value, 1e-6)
        assertEquals(36250.0, d.vsNow, 1e-6)
        val a = Projector.project(p, 10.0, Base.AVG, QtyMode.AMOUNT, 0.0)
        assertEquals(330000.0, a.value, 1e-6)
        assertEquals(10.0, a.profitPct, 1e-9)
    }
}
