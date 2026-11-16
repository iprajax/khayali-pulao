package com.khayali.pulao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CostsTest {

    @Test fun sellChargesOnTenLakhSale() {
        val sell = 10_00_000.0
        val c = Costs.sellCharges(sell)
        // STT 1,000 + txn 29.70 + SEBI 1.00 + GST 5.53 + DP 15.34
        assertEquals(1000.0 + 29.7 + 1.0 + 5.526 + 15.34, c, 0.01)
        assertEquals(0.0, Costs.sellCharges(0.0), 0.0)
    }

    @Test fun ltcgHasExemption() {
        assertEquals(0.0, Costs.tax(1_00_000.0, TaxKind.LTCG), 0.0)
        assertEquals(0.125 * 75_000, Costs.tax(2_00_000.0, TaxKind.LTCG), 1e-9)
        assertEquals(40_000.0, Costs.tax(2_00_000.0, TaxKind.STCG), 1e-9)
        assertEquals(60_000.0, Costs.tax(2_00_000.0, TaxKind.SLAB), 1e-9)
        assertEquals(0.0, Costs.tax(-5_000.0, TaxKind.STCG), 0.0)
    }

    @Test fun inHandAfterLtcg() {
        val gain = 2_00_000.0; val sell = 10_00_000.0
        val net = Costs.inHand(gain, sell, TaxKind.LTCG)
        val ch = Costs.sellCharges(sell)
        assertEquals((gain - ch) - (gain - ch - 125000) * 0.125, net, 1e-6)
        assertTrue(net < gain)
    }

    @Test fun heldPeriodPicksTax() {
        assertEquals(TaxKind.LTCG, Costs.kindFor(1.0, TaxKind.SLAB))
        assertEquals(TaxKind.STCG, Costs.kindFor(0.5, TaxKind.LTCG))
        assertEquals(TaxKind.SLAB, Costs.kindFor(null, TaxKind.SLAB))
    }

    @Test fun benchmarks() {
        assertEquals(15.0, Bench.cagr(100.0, 115.0, 1.0)!!, 1e-9)
        assertEquals(20.83, Bench.cagr(300000.0, 362500.0, 1.0)!!, 0.01)
        assertEquals(41.42, Bench.cagr(100.0, 200.0, 2.0)!!, 0.01)
        assertEquals(100.0, Bench.real(105.0, 5.0, 1.0), 1e-9)
        val names = Bench.list(6.5, 5.0).map { it.name }
        assertEquals(listOf("Savings account", "Bank FD", "PPF", "Gold", "Nifty 50", "Inflation"), names)
        assertEquals(6.5, Bench.list(6.5, 5.0)[1].ratePct, 0.0)
    }
}
