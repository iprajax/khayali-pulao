package com.khayali.pulao

import com.khayali.pulao.ai.data.Filings
import com.khayali.pulao.ai.data.Sectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Real files saved from NSE's announcements feed, SEBI's What's New page and NSE's EQUITY_L list (18 Nov 2026). */
class FilingsTest {
    @Test fun nseFilingsKnowTheirSymbolAndCategory() {
        val f = Filings.parseNse(File("src/test/resources/data/nse_announcements.xml").readText())
        assertTrue("NAV declarations are dropped", f.size in 2..60 && f.none { it.subject.contains("NAV") })
        val biocon = f.first { it.company == "Biocon Limited" }
        assertEquals("BIOCON", biocon.symbol); assertEquals("Press Release", biocon.subject); assertTrue(biocon.at != null && biocon.link.endsWith(".pdf"))
        assertTrue("almost every filing maps to a symbol", f.count { it.symbol != null } >= f.size * 0.9)
        assertTrue(f.all { it.category in Filings.ORDER })
        assertEquals("Results", Filings.categorize("Outcome of Board Meeting", "approved the unaudited financial results"))
        assertEquals("Orders & deals", Filings.categorize("Bagging/Receiving of orders/contracts", ""))
        assertEquals("Management change", Filings.categorize("Resignation", "Resignation of Chief Financial Officer"))
    }

    @Test fun sebiListParses() {
        val n = Filings.parseSebi(File("src/test/resources/data/sebi_whatsnew.html").readText())
        assertEquals(25, n.size)
        assertEquals(LocalDate.of(2026, 11, 17), n[0].date); assertEquals("Circulars", n[0].category)
        assertEquals("Review of Margin Framework for Commodity Derivatives Segment", n[0].title)
        assertTrue(n.all { it.link.startsWith("https://www.sebi.gov.in/") })
    }

    @Test fun equityListGivesEveryName() {
        val names = Sectors.parseNames(File("src/test/resources/data/equity_l.csv").readText())
        assertTrue(names.size > 2500); assertEquals("20 Microns Limited", names["20MICRONS"])
    }

    @Test fun buyAndSellCallsAreNeverRelayed() {
        for (t in listOf("Stock to buy today: XYZ (₹123) – BUY", "Stocks to buy or sell: experts recommend these 3", "Top picks for the week", "Buy ABC, target ₹500: analyst", "Should you buy this IPO?"))
            assertTrue(t, Filings.isCall(t))
        for (t in listOf("Infosys shares jump 4% after deal win", "SEBI tightens rules on F&O", "Buyback: TCS sets record date", "Sell-off deepens as FIIs exit"))
            assertFalse(t, Filings.isCall(t))
    }
}

class FilingsApiTest {
    @Test fun apiShapeParses() {
        val j = """{"data":[{"symbol":"INFY","sm_name":"Infosys Limited","desc":"Outcome of Board Meeting","attchmntText":"Approved the unaudited financial results","attchmntFile":"https://x/INFY.pdf","an_dt":"17-Nov-2026 18:02:11"},
            {"symbol":"MFX","sm_name":"Some MF","desc":"Declaration of NAV","attchmntText":"","attchmntFile":"","an_dt":"17-Nov-2026 07:00:00"}]}"""
        val f = Filings.parseNseApi(j)
        assertEquals(1, f.size); assertEquals("INFY", f[0].symbol); assertEquals("Results", f[0].category)
        assertTrue(Filings.parseNseApi("not json").isEmpty())
    }
}
