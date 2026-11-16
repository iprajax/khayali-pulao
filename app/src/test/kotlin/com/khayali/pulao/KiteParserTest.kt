package com.khayali.pulao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parser + formatting. Strings use the exact *shape* Kite exposes; all figures are fictional. */
class KiteParserTest {

    private val holdingRow = "INFRA, quantity 250, average price 1,200.00, P N L 62,500.00, P N L percent 20.83%, " +
        "invested money 3,00,000.00, last traded price 1,450.00, change 1.20%"

    @Test fun parsesHoldingRow() {
        val s = KiteParser.parse(holdingRow) as Stock.Holding
        assertEquals("INFRA", s.name)
        assertEquals(250.0, s.qty, 0.0)
        assertEquals(1200.0, s.avg, 0.0)
        assertEquals(300000.0, s.invested, 0.001)
        assertEquals(1450.0, s.ltp, 0.0)
        assertEquals(62500.0, s.pnl, 0.01)            // matches the row's own P&L
        assertEquals(20.83, s.pnlPct, 0.01)
    }

    @Test fun parsesLossMakingHolding() {
        val s = KiteParser.parse(
            "LOSSCO, quantity 1000, average price 15.40, P N L -7,370.00, P N L percent -47.86%, " +
                "invested money 15,400.00, last traded price 8.03, change -1.23%",
        ) as Stock.Holding
        assertEquals(-7370.0, s.pnl, 0.01)
        assertTrue(s.pnlPct < 0)
    }

    @Test fun parsesFractionalQtyHolding() {
        val s = KiteParser.parse(
            "NIFTYBEES, quantity 12.5, average price 250.00, P N L 0, P N L percent 0%, " +
                "invested money 3,125.00, last traded price 250.00, change 0%",
        ) as Stock.Holding
        assertEquals(12.5, s.qty, 0.0)
    }

    @Test fun parsesWatchlistRow() {
        val s = KiteParser.parse(
            "BALLIA PORTS\nlast traded price\n1,893.00\nexchange\nNSE\nAbsolute change\n-10.30\nChange percent\n (-0.54%)",
        )
        assertEquals(Stock.Quote("BALLIA PORTS", "NSE", 1893.0), s)
    }

    @Test fun parsesBseWatchlistRow() {
        val s = KiteParser.parse("TELAIYA MOTORS\nlast traded price\n262.40\nexchange\nBSE\nAbsolute change\n-25.00")
        assertEquals(Stock.Quote("TELAIYA MOTORS", "BSE", 262.4), s)
    }

    @Test fun ignoresOtherNodes() {
        assertNull(KiteParser.parse("NIFTY 50\nlast traded price 25,291.10\nchange +146.80 and +0.58%"))
        assertNull(KiteParser.parse("Invested"))
        assertNull(KiteParser.parse("3,00,000"))
        assertNull(KiteParser.parse("Holdings\ntab, contains 1 items.\nTab 1 of 2"))
        assertNull(KiteParser.parse(""))
        assertNull(KiteParser.parse(null))
    }

    @Test fun sameInstrumentIgnoresPrice() {
        val a = Stock.Quote("JHUMRI POWER", "NSE", 205.29)
        assertTrue(a.sameAs(a.copy(ltp = 206.0)))
        assertFalse(a.sameAs(a.copy(name = "JHUMRI GREEN")))
        assertFalse(a.sameAs(Stock.Holding("JHUMRI POWER", 1.0, 1.0, 1.0, 1.0)))
        assertFalse(a.sameAs(null))
    }

    @Test fun indianFormatting() {
        assertEquals("1,23,45,678", Fmt.amount(12345678.0))
        assertEquals("999", Fmt.amount(999.0))
        assertEquals("1,000", Fmt.amount(1000.0))
        assertEquals("+6,54,321", Fmt.signed(654321.4))
        assertEquals("−5,000", Fmt.signed(-5000.0))
        assertEquals("1,450.50", Fmt.price(1450.5))
        assertEquals("-1,23,456", Fmt.amount(-123456.0))
    }

    @Test fun compactMoney() {
        assertEquals("99,999", Fmt.money(99999.0, compact = true))
        assertEquals("5.12 L", Fmt.money(512000.0, compact = true))
        assertEquals("5 L", Fmt.money(500000.0, compact = true))
        assertEquals("2 Cr", Fmt.money(2e7, compact = true))
        assertEquals("1.23 Cr", Fmt.money(12345678.0, compact = true))
        assertEquals("1,23,45,678", Fmt.money(12345678.0, compact = false))
        assertEquals("−1.23 Cr", Fmt.signedMoney(-12345678.0, compact = true))
    }

    @Test fun pctLabels() {
        assertEquals("+10%", Fmt.pct(10.0))
        assertEquals("−2.5%", Fmt.pct(-2.5))
        assertEquals("0%", Fmt.pct(0.0))
        assertEquals("+16.2%", Fmt.pct1(16.16))
        assertEquals("12.5", Fmt.qty(12.5))
        assertEquals("1,250", Fmt.qty(1250.0))
    }

    @Test fun navigationTabsOnly() {
        // Exact strings from Kite's dump.
        listOf("Portfolio, tab", "Watchlist, tab", "Orders, tab", "IPO & Bids, tab", "account, tab",
            "Positions\nTab 2 of 2", "Holdings\ntab, contains 1 items.\nTab 1 of 2", "MW 7\nTab 7 of 7",
        ).forEach { assertTrue(it, KiteParser.isNavigationTab(it)) }
        // Never pass through anything else.
        listOf("Buy", "Sell", "BUY", "Exit", "Search", "Filter", "Authorisation", "Analytics",
            "Watchlist, tab and more", "JHUMRI POWER\nlast traded price\n205.29\nexchange\nNSE",
        ).forEach { assertFalse(it, KiteParser.isNavigationTab(it)) }
    }
}
