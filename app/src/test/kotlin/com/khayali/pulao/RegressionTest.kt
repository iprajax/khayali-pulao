package com.khayali.pulao

import com.khayali.pulao.ai.DownloadText
import com.khayali.pulao.ai.Md
import com.khayali.pulao.ai.Money
import com.khayali.pulao.ai.NumberGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One test per defect found in the 0.8 audit, so none of them comes back. */
class RegressionTest {
    @Test fun inputsAcceptTheGroupedNumbersTheAppPrints() {
        assertEquals(50000.0, Fmt.parse(Fmt.qty(5e4))!!, 1e-9)          // the ₹50K quick chip fills "50,000"
        assertEquals(123456.5, Fmt.parse("₹1,23,456.5")!!, 1e-9)
        assertEquals(null, Fmt.parse("abc"))
    }

    @Test fun moneyRollsOverToCroreInsteadOfHundredLakh() {
        assertEquals("1 Cr", Fmt.money(9_999_900.0, true))
        assertEquals("99.99 L", Fmt.money(9_999_000.0, true))
        assertEquals("1 Cr", Fmt.money(1e7, true))
    }

    @Test fun tinyNegativesDoNotPrintAsMinusZero() {
        assertEquals("+0", Fmt.signed(-0.4))
        assertEquals("+0.0%", Fmt.pct1(-0.04))
        assertEquals("−1", Fmt.signed(-0.6))
        assertEquals("−0.1%", Fmt.pct1(-0.06))
    }

    @Test fun watchlistRowsOnDifferentExchangesAreDifferentStocks() {
        assertFalse(Stock.Quote("INFRA", "NSE", 100.0).sameAs(Stock.Quote("INFRA", "BSE", 101.0)))
        assertTrue(Stock.Quote("INFRA", "NSE", 100.0).sameAs(Stock.Quote("INFRA", "NSE", 102.0)))
    }

    @Test fun moneyParsesSignsAndPercent() {
        assertEquals(-10.0, Money.parse("-10")!!, 1e-9)
        assertEquals(10.0, Money.parse("10%")!!, 1e-9)
        assertEquals(-250000.0, Money.parse("−2.5 lakh")!!, 1e-9)
        assertEquals(1e7, Money.parse("1 crore")!!, 1e-9)
    }

    @Test fun numberGuardUnderstandsUnitWords() {
        assertEquals("worth ₹250 Cr", NumberGuard.verify("worth ₹250 Cr", listOf(2.5e9)))
        assertEquals("worth ₹2.5 lakh", NumberGuard.verify("worth ₹2.5 lakh", listOf(250000.0)))
        assertEquals("an unverifiable amount takes its sentence with it", "", NumberGuard.verify("worth ₹7 crore", listOf(250000.0)))
        assertEquals("Kept. Also kept.", NumberGuard.verify("Kept. It is worth ₹9,99,999. Also kept.", listOf(250000.0)))
    }

    @Test fun markdownEdgeCases() {
        val list = Md.parse("1. A\n\n2. B\n\n3. C")
        assertEquals(listOf(Md.Block.Bullets(listOf("A", "B", "C"), ordered = true)), list)         // one list, numbered 1-2-3
        assertEquals(listOf(Md.Block.Heading("Deep", 3)), Md.parse("#### Deep"))
        assertEquals("2*3*4", Md.plain("2*3*4"))
        assertEquals("an emphasised word", Md.plain("an *emphasised* word"))
        // a list still ends at a paragraph
        assertEquals(2, Md.parse("- a\n- b\n\nAfter the list.").size)
    }

    @Test fun byteSizesNeverReadAsThousandMegabytes() {
        assertEquals("1.00 GB", DownloadText.bytes(999_999_999))
        assertEquals("999 MB", DownloadText.bytes(999_400_000))
    }
}

class AnswerTidyTest {
    private val valid = { c: String -> c in setOf("/holdings", "/why INFY") }
    @org.junit.Test fun brokenModelLinksAreRepairedOrDropped() {
        val md = "Would you like more? See all holdings details](cmd:/holdings)  Project move](cmd:/move symbol NSE)  * Analyze flow](cmd:/nlflow)"
        val out = NumberGuard.tidyLinks(md, valid)
        org.junit.Assert.assertEquals("Would you like more? [See all holdings details](cmd:/holdings)", out)
        org.junit.Assert.assertEquals("Try [Why it moved](cmd:/why INFY).", NumberGuard.tidyLinks("Try [Why it moved](cmd:/why INFY).", valid))
        org.junit.Assert.assertEquals("No links here.", NumberGuard.tidyLinks("No links here.", valid))
    }

    @org.junit.Test fun spokenPhrasingsReachTheExactCommand() {
        val env = com.khayali.pulao.ai.ChatFlow.Env(true, com.khayali.pulao.ai.ChatFlow.Kite.CONNECTED, true, setOf("market", "knowledge"))
        for (t in listOf("What’s the market mood today", "What's the market mood today.", "what is the market mood today?"))
            org.junit.Assert.assertEquals(t, com.khayali.pulao.ai.ChatFlow.Action.Term("mood", ""), com.khayali.pulao.ai.ChatFlow.route(t, env))
        org.junit.Assert.assertEquals(com.khayali.pulao.ai.ChatFlow.Action.Term("why", "Infosys"), com.khayali.pulao.ai.ChatFlow.route("Why did Infosys fall today", env))
    }
}
