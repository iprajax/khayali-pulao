package com.khayali.pulao.term

import com.khayali.pulao.ai.term.DerivChain
import com.khayali.pulao.ai.term.DerivExpiriesFeature
import com.khayali.pulao.ai.term.DerivIvSmileFeature
import com.khayali.pulao.ai.term.DerivMath
import com.khayali.pulao.ai.term.DerivMath.Leg
import com.khayali.pulao.ai.term.DerivMath.Strike
import com.khayali.pulao.ai.term.DerivMaxPainFeature
import com.khayali.pulao.ai.term.DerivOiChangeFeature
import com.khayali.pulao.ai.term.DerivOptionChainFeature
import com.khayali.pulao.ai.term.DerivPcrFeature
import com.khayali.pulao.ai.term.DerivStraddleFeature
import com.khayali.pulao.ai.term.Feature
import com.khayali.pulao.ai.term.Terminal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The derivatives pack. Fixtures are trimmed copies of real NSE replies (16 Nov 2026 close):
 * deriv_chain_nifty = NIFTY 23-Nov expiry, deriv_chain_nifty_today = NIFTY 17-Nov expiry (expiry day),
 * deriv_chain_reliance = a stock chain, deriv_chain_empty = what NSE answers for an expiry with no contracts.
 */
class DerivTest {
    private val today = LocalDate.of(2026, 11, 17)
    private val nifty get() = TermTesting.fixture("deriv_chain_nifty.json")
    private val niftyToday get() = TermTesting.fixture("deriv_chain_nifty_today.json")
    private val reliance get() = TermTesting.fixture("deriv_chain_reliance.json")
    private val empty get() = TermTesting.fixture("deriv_chain_empty.json")
    private val info get() = TermTesting.fixture("deriv_info_nifty.json")
    private val features: List<Feature> = listOf(DerivOptionChainFeature, DerivPcrFeature, DerivMaxPainFeature, DerivIvSmileFeature, DerivStraddleFeature, DerivOiChangeFeature, DerivExpiriesFeature)

    private fun summary(json: String) = TermTesting.card(json)["summary"].asString
    private fun head(json: String) = TermTesting.card(json).getAsJsonObject("card")
    private fun stats(json: String) = TermTesting.blocks(json).first { it["type"].asString == "stats" }.getAsJsonArray("items").associate { it.asJsonObject["label"].asString to it.asJsonObject["value"].asString }
    private fun rows(json: String) = TermTesting.blocks(json).first { it["type"].asString == "rows" }.getAsJsonArray("rows").map { it.asJsonObject }

    /** A chain as NSE would send it before anything has traded: strikes listed, every figure zero. */
    private fun quiet(spot: Double = 100.0) = """{"records":{"data":[""" + listOf(90, 100, 110).joinToString(",") { k ->
        val leg = """{"strikePrice":$k,"openInterest":0,"changeinOpenInterest":0,"totalTradedVolume":0,"impliedVolatility":0,"lastPrice":0,"underlyingValue":$spot}"""
        """{"expiryDates":"23-Nov-2026","CE":$leg,"PE":$leg,"strikePrice":$k}""" } + """],"timestamp":"17-Nov-2026 09:00:00","underlyingValue":$spot}}"""

    // ------------------------------------------------------------------ the maths, on hand-built chains

    private fun k(strike: Int, callOi: Int, putOi: Int, callLtp: Double = 0.0, putLtp: Double = 0.0, callVol: Int = 0, putVol: Int = 0) =
        Strike(strike.toDouble(), Leg(oi = callOi.toDouble(), ltp = callLtp, vol = callVol.toDouble()), Leg(oi = putOi.toDouble(), ltp = putLtp, vol = putVol.toDouble()))

    @Test fun putCallRatio() {
        val rows = listOf(k(100, 0, 20, callVol = 5, putVol = 30), k(110, 10, 10, callVol = 15, putVol = 10), k(120, 30, 0, callVol = 20, putVol = 0))
        assertEquals(0.75, DerivMath.pcrOi(rows)!!, 1e-12)             // 30 puts ÷ 40 calls
        assertEquals(1.0, DerivMath.pcrVolume(rows)!!, 1e-12)          // 40 ÷ 40
        assertNull(DerivMath.pcrOi(listOf(k(100, 0, 20))))             // nothing to divide by
        assertNull(DerivMath.pcrOi(emptyList()))
        assertEquals(2.0, DerivMath.ratio(10.0, 5.0)!!, 1e-12)
    }

    @Test fun maxPainIsWhereWritersPayLeast() {
        val rows = listOf(k(100, 0, 20), k(110, 10, 10), k(120, 30, 0))
        // at 100: the 110 puts are 10 in the money → 10 × 10; the 120 puts have no OI
        assertEquals(100.0, DerivMath.pain(rows, 100.0), 1e-9)
        assertEquals(0.0, DerivMath.pain(rows, 110.0), 1e-9)
        // at 120: the 110 calls are 10 in the money → 10 × 10; the 100 calls have no OI
        assertEquals(100.0, DerivMath.pain(rows, 120.0), 1e-9)
        assertEquals(110.0, DerivMath.maxPain(rows)!!, 0.0)
        assertEquals(listOf(100.0 to 100.0, 110.0 to 0.0, 120.0 to 100.0), DerivMath.painCurve(rows.reversed()))        // always in strike order
        // heavy put OI above pulls max pain up, heavy call OI below pulls it down
        assertEquals(120.0, DerivMath.maxPain(listOf(k(100, 300, 0), k(110, 0, 0), k(120, 0, 500), k(130, 0, 0)))!!, 0.0)
        assertEquals(100.0, DerivMath.maxPain(listOf(k(90, 0, 0), k(100, 500, 0), k(110, 0, 300)))!!, 0.0)
        // a tie goes to the lower strike; no open interest → no answer
        assertEquals(100.0, DerivMath.maxPain(listOf(k(100, 5, 0), k(120, 0, 5)))!!, 0.0)
        assertNull(DerivMath.maxPain(listOf(k(100, 0, 0), k(110, 0, 0))))
        assertNull(DerivMath.maxPain(emptyList()))
    }

    @Test fun expectedMoveFromTheStraddle() {
        val rows = listOf(k(90, 1, 1, 12.5, 0.5), k(100, 1, 1, 0.0, 3.0), k(110, 1, 1, 2.0, 6.0), k(120, 1, 1, 0.5, 14.0))
        // spot 101: the 100 strike is nearest but its call has no price, so the 110 strike is used
        val m = DerivMath.expectedMove(rows, 101.0)!!
        assertEquals(110.0, m.strike, 0.0); assertEquals(8.0, m.points, 1e-12)
        assertEquals(8.0 / 101.0 * 100, m.pct, 1e-12); assertEquals(102.0, m.low, 1e-12); assertEquals(118.0, m.high, 1e-12)
        assertEquals(90.0, DerivMath.atm(rows, 80.0)!!.strike, 0.0)
        assertNull(DerivMath.expectedMove(listOf(k(100, 1, 1, 0.0, 3.0)), 100.0))
        assertNull(DerivMath.expectedMove(rows, 0.0))
    }

    @Test fun bandsAndDays() {
        val rows = (80..120 step 5).map { k(it, 1, 1) }
        assertEquals(listOf(95.0, 100.0, 105.0), DerivMath.near(rows, 100.0).map { it.strike })          // ±6%
        assertEquals(listOf(90.0, 95.0, 100.0, 105.0, 110.0), DerivMath.near(rows, 100.0, 0.11).map { it.strike })
        assertTrue(DerivMath.near(rows, 0.0).isEmpty())
        assertEquals(listOf(95.0, 100.0, 105.0), DerivMath.closest(rows, 101.0, 3).map { it.strike })
        assertEquals(6, DerivMath.days(today, LocalDate.of(2026, 11, 23))); assertEquals(0, DerivMath.days(today, today))
    }

    // ------------------------------------------------------------------ reading the feeds

    @Test fun parsesARealChain() {
        val c = DerivChain.parse("NIFTY", nifty)!!
        assertEquals(25277.3, c.spot, 1e-9); assertEquals(LocalDate.of(2026, 11, 23), c.expiry); assertEquals("16-Nov-2026 15:40:00", c.timestamp)
        assertEquals(22, c.all.size); assertEquals(19, c.near.size)                       // 20,050 · 23,000 · 27,500 are beyond 6% of spot
        assertEquals(c.all.map { it.strike }.sorted(), c.all.map { it.strike })
        val atm = c.all.first { it.strike == 25300.0 }
        assertEquals(Leg(oi = 59610.0, dOi = 28736.0, iv = 16.13, ltp = 210.0, vol = 263671.0), atm.ce)
        assertEquals(Leg(oi = 54663.0, dOi = 29561.0, iv = 15.43, ltp = 197.95, vol = 247536.0), atm.pe)
        assertEquals(520865.0, c.callOi, 0.0); assertEquals(555443.0, c.putOi, 0.0)
        assertTrue(c.isIndex); assertFalse(DerivChain.parse("RELIANCE", reliance)!!.isIndex)
        assertNull(DerivChain.parse("NIFTY", empty)); assertNull(DerivChain.parse("NIFTY", "<html>")); assertNull(DerivChain.parse("NIFTY", "")); assertNull(DerivChain.parse("NIFTY", "{}"))
        assertTrue(DerivChain.problem("NIFTY", empty).contains("empty option chain")); assertTrue(DerivChain.problem("NIFTY", "<html>").contains("Couldn't read")); assertTrue(DerivChain.problem("NIFTY", "").contains("Couldn't reach"))
    }

    @Test fun symbolsExpiriesAndPaths() {
        assertEquals("NIFTY", DerivChain.index("")); assertEquals("NIFTY", DerivChain.index("nifty 50")); assertEquals("BANKNIFTY", DerivChain.index("bank nifty"))
        assertEquals("BANKNIFTY", DerivChain.index("NIFTY BANK")); assertEquals("FINNIFTY", DerivChain.index("finnifty")); assertNull(DerivChain.index("reliance"))
        assertEquals(LocalDate.of(2026, 12, 1), DerivChain.parseDate("01-Dec-2026")); assertEquals(LocalDate.of(2026, 12, 1), DerivChain.parseDate("01-DEC-2026")); assertNull(DerivChain.parseDate("soon"))
        assertEquals(18, DerivChain.expiries(info, today).size); assertEquals(today, DerivChain.expiries(info, today).first())
        assertEquals(LocalDate.of(2026, 11, 23), DerivChain.expiries(info, today.plusDays(1)).first())             // an expiry that has passed is dropped
        assertEquals(18, DerivChain.expiries(empty, today).size)                                                  // the chain reply carries the same list under records
        assertTrue(DerivChain.expiries("{}", today).isEmpty()); assertTrue(DerivChain.expiries("<html>", today).isEmpty())
        assertEquals("option-chain-v3?type=Indices&symbol=NIFTY&expiry=01-Dec-2026", DerivChain.chainPath("NIFTY", LocalDate.of(2026, 12, 1)))
        assertEquals("option-chain-v3?type=Equity&symbol=M%26M&expiry=23-Nov-2026", DerivChain.chainPath("M&M", LocalDate.of(2026, 11, 23)))
        assertEquals("option-chain-contract-info?symbol=BANKNIFTY", DerivChain.infoPath("BANKNIFTY"))
        assertEquals("25,300", DerivChain.strike(25300.0)); assertEquals("1,012.50", DerivChain.strike(1012.5)); assertEquals("−1,409", DerivChain.signed(-1409.0)); assertEquals("+95,648", DerivChain.signed(95648.0))
    }

    // ------------------------------------------------------------------ the cards

    @Test fun optionChainCard() {
        val json = DerivOptionChainFeature.build("NIFTY", nifty)
        val d = TermTesting.viz(json, "butterfly")
        val rows = d.getAsJsonArray("rows").map { it.asJsonObject }
        assertEquals(19, rows.size); assertEquals(25277.3, d["spot"].asDouble, 1e-9)
        assertEquals("Puts", d["left"].asString); assertEquals("Calls", d["right"].asString); assertTrue(d["xLabel"].asString.contains("contracts")); assertEquals("Strike", d["yLabel"].asString)
        val atm = rows.first { it["strike"].asDouble == 25300.0 }
        assertEquals(54663.0, atm["left"].asDouble, 0.0); assertEquals(59610.0, atm["right"].asDouble, 0.0)
        assertTrue(rows.none { it["strike"].asDouble == 20050.0 || it["strike"].asDouble == 27500.0 })
        assertEquals("Calls 25,500 · Puts 25,000", head(json)["headline"].asString)
        assertEquals("NIFTY · option chain · expiry 23 Nov 2026", head(json)["kicker"].asString)
        assertEquals(mapOf("Call OI (all strikes)" to "5,20,865", "Put OI (all strikes)" to "5,55,443", "Put-call ratio (OI)" to "1.07", "Spot" to "25,277.30"), stats(json))
        assertEquals("NIFTY options, expiry 23 Nov 2026, spot 25,277.30: within 6% of spot the most call open interest is at 25,500 (75,789 contracts) and the most put open interest at 25,000 (1,29,885 contracts). Source: NSE, snapshot 16-Nov-2026 15:40:00.", summary(json))
        // a long chain is cut to the strikes nearest spot
        val many = (0 until 60).map { 24000 + it * 50 }.joinToString(",") { s -> val leg = """{"strikePrice":$s,"openInterest":${s % 700 + 1},"lastPrice":1,"underlyingValue":25500}"""; """{"expiryDates":"23-Nov-2026","CE":$leg,"PE":$leg,"strikePrice":$s}""" }
        val cut = TermTesting.viz(DerivOptionChainFeature.build("NIFTY", """{"records":{"data":[$many],"timestamp":"","underlyingValue":25500}}"""), "butterfly").getAsJsonArray("rows").map { it.asJsonObject["strike"].asDouble }
        assertEquals(DerivOptionChainFeature.MAX_ROWS, cut.size); assertEquals(25000.0, cut.first(), 0.0); assertEquals(26000.0, cut.last(), 0.0)
        assertTrue(TermTesting.error(DerivOptionChainFeature.build("NIFTY", quiet()))!!.contains("no open interest"))
    }

    @Test fun pcrCard() {
        val json = DerivPcrFeature.build("NIFTY", listOf(niftyToday, nifty))
        assertEquals("PCR 0.69", head(json)["headline"].asString)
        assertEquals("By open interest: more calls than puts are outstanding · by volume 0.92", head(json)["sub"].asString)
        val d = TermTesting.viz(json, "groupbars")
        assertEquals(listOf("Call OI", "Put OI"), d.getAsJsonArray("series").map { it.asString }); assertEquals("contracts", d["unit"].asString)
        val items = d.getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(listOf("17 Nov", "23 Nov"), items.map { it["label"].asString })
        assertEquals(listOf(917958.0, 630332.0), items[0].getAsJsonArray("values").map { it.asDouble }); assertEquals(listOf(520865.0, 555443.0), items[1].getAsJsonArray("values").map { it.asDouble })
        assertEquals(listOf("OI 0.69 · vol 0.92", "OI 1.07 · vol 0.96"), rows(json).map { it["value"].asString })
        assertEquals("NIFTY put-call ratio, expiry 17 Nov 2026: 0.69 by open interest (6,30,332 put vs 9,17,958 call contracts) and 0.92 by volume — more calls than puts are outstanding. Source: NSE, snapshot 16-Nov-2026 15:40:00.", summary(json))
        assertTrue(summary(DerivPcrFeature.build("NIFTY", listOf(nifty))).contains("1.07 by open interest") && summary(DerivPcrFeature.build("NIFTY", listOf(nifty))).contains("more puts than calls"))
        // the meaning is explained, without a call on direction
        val note = TermTesting.blocks(json).first { it["type"].asString == "note" }["text"].asString
        assertTrue(note, note.contains("Above 1") && note.contains("says nothing reliable about direction"))
        assertTrue(TermTesting.error(DerivPcrFeature.build("NIFTY", listOf(empty, "<html>")))!!.contains("empty option chain"))
        assertTrue(TermTesting.error(DerivPcrFeature.build("NIFTY", emptyList()))!!.contains("Couldn't reach"))
        assertTrue(TermTesting.error(DerivPcrFeature.build("NIFTY", listOf(quiet())))!!.contains("no call open interest"))
    }

    @Test fun maxPainCard() {
        val json = DerivMaxPainFeature.build("NIFTY", nifty)
        assertEquals("25,200", head(json)["headline"].asString)
        assertEquals("Max-pain strike · 77 points (0.3%) below spot 25,277.30", head(json)["sub"].asString)
        val d = TermTesting.viz(json, "curve")
        val pts = d.getAsJsonArray("series").single().asJsonObject.getAsJsonArray("points").map { it.asJsonArray.map { v -> v.asDouble } }
        assertEquals(19, pts.size); assertEquals(pts.map { it[0] }.sorted(), pts.map { it[0] })
        assertEquals(listOf(25200.0, 162136000.0), pts.minByOrNull { it[1] })              // the curve's lowest point is the max-pain strike
        assertEquals(listOf("Spot" to 25277.3, "Max pain" to 25200.0), d.getAsJsonArray("markers").map { it.asJsonObject["label"].asString to it.asJsonObject["x"].asDouble })
        assertTrue(d["xLabel"].asString.isNotBlank() && d["yLabel"].asString.contains("contracts × points"))
        assertEquals(25200.0, TermTesting.card(json)["maxPain"].asDouble, 0.0)
        assertEquals("NIFTY max pain, expiry 23 Nov 2026: 25,200, 77 points (0.3%) below spot 25,277.30. It is worked out from open interest (5,20,865 call and 5,55,443 put contracts) and is not a forecast. Source: NSE, snapshot 16-Nov-2026 15:40:00.", summary(json))
        assertEquals("25,250", head(DerivMaxPainFeature.build("NIFTY", niftyToday))["headline"].asString)
        val rel = DerivMaxPainFeature.build("RELIANCE", reliance)
        assertEquals("1,320", head(rel)["headline"].asString); assertTrue(head(rel)["sub"].asString, head(rel)["sub"].asString.contains("19 rupees (1.4%) below spot 1,339.00"))
        assertTrue(TermTesting.error(DerivMaxPainFeature.build("NIFTY", quiet()))!!.contains("no open interest"))
        assertTrue(TermTesting.error(DerivMaxPainFeature.build("NIFTY", empty))!!.contains("empty option chain"))
    }

    @Test fun ivSmileCard() {
        val json = DerivIvSmileFeature.build("NIFTY", nifty)
        assertEquals("15.8%", head(json)["headline"].asString)                                // (16.13 + 15.43) ÷ 2
        assertEquals("At-the-money IV at 25,300 · call 16.1% · put 15.4%", head(json)["sub"].asString)
        val d = TermTesting.viz(json, "curve")
        val series = d.getAsJsonArray("series").map { it.asJsonObject }
        assertEquals(listOf("Calls", "Puts"), series.map { it["name"].asString }); assertEquals("pct", d["yFmt"].asString)
        fun pts(i: Int) = series[i].getAsJsonArray("points").map { it.asJsonArray.map { v -> v.asDouble } }
        assertTrue(pts(0).contains(listOf(25300.0, 16.13))); assertTrue(pts(1).contains(listOf(25300.0, 15.43)))
        assertTrue("calls start just below spot", pts(0).all { it[0] >= 25277.3 * 0.98 && it[1] > 0 }); assertTrue("puts stop just above spot", pts(1).all { it[0] <= 25277.3 * 1.02 && it[1] > 0 })
        assertEquals(25277.3, d.getAsJsonArray("markers").single().asJsonObject["x"].asDouble, 1e-9)
        assertEquals("16.8%", stats(json)["Put IV at 24,800"]); assertEquals("17.8%", stats(json)["Call IV at 26,300"])
        assertTrue(summary(json), summary(json).startsWith("NIFTY implied volatility, expiry 23 Nov 2026: about 15.8% at the money (25,300 strike: call 16.1%, put 15.4%)") && summary(json).endsWith("Source: NSE, snapshot 16-Nov-2026 15:40:00."))
        assertEquals("22.0%", head(DerivIvSmileFeature.build("RELIANCE", reliance))["headline"].asString)
        assertTrue(TermTesting.error(DerivIvSmileFeature.build("NIFTY", quiet()))!!.contains("no implied volatility"))
        assertTrue(TermTesting.error(DerivIvSmileFeature.build("NIFTY", "<html>"))!!.contains("Couldn't read"))
    }

    @Test fun straddleCard() {
        val json = DerivStraddleFeature.build("NIFTY", nifty, today)
        assertEquals("±408 points", head(json)["headline"].asString)
        assertEquals("±1.61% of spot 25,277.30 · 6 days to expiry", head(json)["sub"].asString)
        assertEquals(mapOf("ATM strike" to "25,300", "Call" to "₹210.00", "Put" to "₹197.95", "Straddle" to "₹407.95", "Lower break-even" to "24,892.05", "Upper break-even" to "25,707.95"), stats(json))
        val d = TermTesting.viz(json, "curve")
        val pts = d.getAsJsonArray("series").single().asJsonObject.getAsJsonArray("points").map { it.asJsonArray.map { v -> v.asDouble } }
        assertEquals(407.95, pts.first { it[0] == 25300.0 }[1], 1e-9); assertEquals(listOf(25350.0, 182.2 + 220.4), pts.minByOrNull { it[1] }); assertEquals("price", d["yFmt"].asString)
        assertEquals("NIFTY 25,300 straddle, expiry 23 Nov 2026 (6 days to expiry): call 210.00 + put 197.95 = 407.95, so options price in a move of about ±407.95 points (±1.61% of spot 25,277.30), " +
            "break-evens 24,892.05 and 25,707.95. Indicative, from last traded prices. Source: NSE, snapshot 16-Nov-2026 15:40:00.", summary(json))
        val exp = DerivStraddleFeature.build("NIFTY", niftyToday, today)
        assertEquals("±168 points", head(exp)["headline"].asString); assertTrue(head(exp)["sub"].asString.endsWith("expires today")); assertTrue(summary(exp).contains("call 82.00 + put 85.90 = 167.90"))
        val rel = DerivStraddleFeature.build("RELIANCE", reliance, today)
        assertEquals("±₹30.55", head(rel)["headline"].asString); assertTrue(summary(rel), summary(rel).contains("±₹30.55 (±2.28% of spot 1,339.00)"))
        assertTrue(TermTesting.error(DerivStraddleFeature.build("NIFTY", quiet(), today))!!.contains("no straddle price"))
        assertTrue(TermTesting.error(DerivStraddleFeature.build("NIFTY", "", today))!!.contains("Couldn't reach"))
    }

    @Test fun oiChangeCard() {
        val json = DerivOiChangeFeature.build("NIFTY", nifty)
        assertEquals("25,300 PE +29,561", head(json)["headline"].asString)
        assertTrue(head(json)["sub"].asString, head(json)["sub"].asString.startsWith("Biggest addition of contracts · biggest unwinding 25,150 CE −1,409"))
        val d = TermTesting.viz(json, "groupbars")
        assertEquals(listOf("Call OI change", "Put OI change"), d.getAsJsonArray("series").map { it.asString }); assertEquals("Strike", d["xLabel"].asString)
        val items = d.getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(DerivOiChangeFeature.CHART_STRIKES, items.size); assertEquals(items.map { it["label"].asString.toInt() }.sorted(), items.map { it["label"].asString.toInt() })
        assertEquals(listOf(28736.0, 29561.0), items.first { it["label"].asString == "25300" }.getAsJsonArray("values").map { it.asDouble })
        assertEquals(mapOf("Net change, calls" to "+95,648", "Net change, puts" to "+1,45,826"), stats(json))
        val r = rows(json)
        assertEquals(listOf("25,300 PE", "25,300 CE", "25,200 PE", "25,150 CE", "25,050 CE", "25,100 CE"), r.map { it["title"].asString })
        assertEquals(listOf("+29,561", "+28,736", "+28,698", "−1,409", "−395", "−31"), r.map { it["value"].asString })
        assertEquals("NIFTY open-interest change, expiry 23 Nov 2026, within 6% of spot 25,277.30: biggest addition 25,300 PE +29,561 contracts, biggest unwinding 25,150 CE −1,409 contracts; " +
            "net +95,648 in calls and +1,45,826 in puts. Source: NSE, snapshot 16-Nov-2026 15:40:00.", summary(json))
        assertTrue(summary(DerivOiChangeFeature.build("RELIANCE", reliance)).contains("biggest addition 1,300 PE +928 contracts, biggest unwinding 1,300 CE −1,444 contracts; net −810 in calls and +3,661 in puts"))
        assertTrue(TermTesting.error(DerivOiChangeFeature.build("NIFTY", quiet()))!!.contains("no change in NIFTY open interest"))
    }

    @Test fun expiriesCard() {
        val json = DerivExpiriesFeature.build("NIFTY", info, today)
        assertEquals("17 Nov 2026", head(json)["headline"].asString); assertEquals("Nearest expiry · Tuesday · today", head(json)["sub"].asString)
        val d = TermTesting.viz(json, "groupbars")
        val items = d.getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(listOf("17 Nov", "23 Nov", "1 Dec", "8 Dec", "15 Dec", "29 Dec"), items.map { it["label"].asString })
        assertEquals(listOf(0, 6, 14, 21, 28, 42), items.map { it.getAsJsonArray("values").single().asInt }); assertEquals("days", d["unit"].asString); assertEquals("Expiry date", d["xLabel"].asString)
        val r = rows(json)
        assertEquals(10, r.size); assertEquals("today", r[0]["value"].asString); assertEquals("6 days", r[1]["value"].asString)
        assertEquals("Tuesday · weekly", r[0]["sub"].asString); assertEquals("Monday · monthly", r[1]["sub"].asString)
        assertEquals("NIFTY has 18 option expiries listed. The nearest is 17 Nov 2026 (today), then 23 Nov 2026 (in 6 days). Source: NSE.", summary(json))
        val later = DerivExpiriesFeature.build("NIFTY", info, LocalDate.of(2026, 11, 20))
        assertTrue(summary(later), summary(later).startsWith("NIFTY has 17 option expiries listed. The nearest is 23 Nov 2026 (in 3 days)"))
        assertEquals("X has 1 option expiry listed. The nearest is 24 Nov 2026 (in 1 day). Source: NSE.", summary(DerivExpiriesFeature.build("X", """{"expiryDates":["24-Nov-2026"]}""", LocalDate.of(2026, 11, 23))))
        assertTrue(TermTesting.error(DerivExpiriesFeature.build("ZZZZ", "{}", today))!!.contains("no upcoming option expiries"))       // what NSE answers for a symbol without options
        assertTrue(TermTesting.error(DerivExpiriesFeature.build("NIFTY", "<html>", today))!!.contains("Couldn't read"))
        assertTrue(TermTesting.error(DerivExpiriesFeature.build("NIFTY", "", today))!!.contains("Couldn't reach"))
        assertTrue(TermTesting.error(DerivExpiriesFeature.build("NIFTY", info, LocalDate.of(2032, 1, 1)))!!.contains("no upcoming"))
    }

    // ------------------------------------------------------------------ fetching, routing, house rules

    @Test fun runFetchesContractInfoThenTheNearestChain() = runBlocking {
        val fake = TermTesting.Fake(nse = mapOf("option-chain-contract-info?symbol=NIFTY" to info, "option-chain-v3?type=Indices&symbol=NIFTY&expiry=17-Nov-2026" to niftyToday))
        val json = DerivMaxPainFeature.run("", fake)
        assertEquals(listOf("nse:option-chain-contract-info?symbol=NIFTY", "nse:option-chain-v3?type=Indices&symbol=NIFTY&expiry=17-Nov-2026"), fake.asked)
        assertEquals("25,250", head(json)["headline"].asString)

        // the nearest expiry comes back empty (as NSE does off-hours) → the next one is used
        val skip = TermTesting.Fake(nse = mapOf("option-chain-contract-info" to info, "option-chain-v3?type=Indices&symbol=NIFTY&expiry=17-Nov-2026" to empty, "option-chain-v3?type=Indices&symbol=NIFTY&expiry=23-Nov-2026" to nifty))
        assertEquals("NIFTY · option chain · expiry 23 Nov 2026", head(DerivOptionChainFeature.run("nifty", skip))["kicker"].asString)
        assertEquals(3, skip.asked.size)

        // the put-call ratio reads up to three expiries
        val three = TermTesting.Fake(nse = mapOf("option-chain-contract-info" to info, "option-chain-v3?type=Indices&symbol=NIFTY&expiry=17-Nov-2026" to niftyToday, "option-chain-v3?type=Indices&symbol=NIFTY&expiry=23-Nov-2026" to nifty,
            "option-chain-v3?type=Indices&symbol=NIFTY&expiry=01-Dec-2026" to nifty))
        assertEquals(3, TermTesting.viz(DerivPcrFeature.run("NIFTY", three), "groupbars").getAsJsonArray("items").size())
        assertEquals(4, three.asked.size)

        // a stock goes to the equity chain
        val stock = TermTesting.Fake(nse = mapOf("option-chain-contract-info?symbol=RELIANCE" to """{"expiryDates":["23-Nov-2026","29-Dec-2026","25-Jan-2027"],"strikePrice":["1300","1340"]}""", "option-chain-v3?type=Equity&symbol=RELIANCE&expiry=23-Nov-2026" to reliance))
        assertEquals("±₹30.55", head(DerivStraddleFeature.run("@reliance", stock))["headline"].asString)
        assertEquals("RELIANCE · option expiries", head(DerivExpiriesFeature.run("reliance", stock))["kicker"].asString)

        // failures are explained, never thrown
        val dead = TermTesting.Fake()
        for (f in features) assertTrue(f.id, TermTesting.error(f.run("NIFTY", dead))!!.contains("Couldn't reach NSE"))
        val none = TermTesting.Fake(nse = mapOf("option-chain-contract-info" to "{}"))
        for (f in features) assertTrue(f.id, TermTesting.error(f.run("ZZZZ", none))!!.contains("NSE lists no"))
        val closed = TermTesting.Fake(nse = mapOf("option-chain-contract-info" to info, "option-chain-v3" to empty))
        for (f in features - DerivExpiriesFeature) assertTrue(f.id, TermTesting.error(f.run("NIFTY", closed))!!.contains("empty option chain"))
        for (f in features) assertTrue(f.id, TermTesting.error(f.run("tata motors ltd", dead))!!.contains("Which index or stock?"))
    }

    @Test fun registeredAndRoutedFromPlainQuestions() {
        for (f in features) { assertEquals(f, Terminal.find(f.id)); assertEquals("Derivatives", f.group.title); assertTrue(f.id, f.triggers.isNotEmpty()) }
        assertEquals(listOf("optionchain", "pcr", "maxpain", "ivsmile", "straddle", "oichange", "expiries"), features.map { it.id })
        fun route(q: String) = Terminal.match(q)?.let { it.first.id to it.second }
        assertEquals("optionchain" to "", route("show the option chain"))
        assertEquals("pcr" to "nifty", route("put-call ratio for nifty"))
        assertEquals("pcr" to "", route("what is the PCR"))
        assertEquals("maxpain" to "bank nifty", route("max pain of bank nifty"))
        assertEquals("ivsmile" to "", route("iv smile"))
        assertEquals("straddle" to "RELIANCE", route("expected move @RELIANCE"))
        assertEquals("oichange" to "", route("change in open interest today"))
        assertEquals("expiries" to "", route("next expiry"))
        assertNull(route("what options do I have for saving tax"))
    }

    @Test fun everyCardFollowsTheHouseRules() {
        val cards = listOf(DerivOptionChainFeature.build("NIFTY", nifty), DerivPcrFeature.build("NIFTY", listOf(niftyToday, nifty)), DerivMaxPainFeature.build("NIFTY", nifty), DerivIvSmileFeature.build("NIFTY", nifty),
            DerivStraddleFeature.build("NIFTY", nifty, today), DerivOiChangeFeature.build("NIFTY", nifty), DerivExpiriesFeature.build("NIFTY", info, today),
            DerivOptionChainFeature.build("RELIANCE", reliance), DerivIvSmileFeature.build("RELIANCE", reliance), DerivOiChangeFeature.build("NIFTY", niftyToday))
        for (json in cards) {
            val c = TermTesting.card(json); val card = c.getAsJsonObject("card"); val name = card["kicker"].asString
            assertNull(name, c["error"])
            assertTrue(name, card["headline"].asString.isNotBlank() && card["sub"].asString.isNotBlank())
            assertEquals(name, listOf("NSE"), c.getAsJsonArray("sources").map { it.asString })
            val pills = c.getAsJsonArray("pills").map { it.asString }
            assertTrue(name, pills.size in 2..4 && pills.all { it.startsWith("/") })
            val blocks = TermTesting.blocks(json)
            val charts = blocks.filter { it["type"].asString == "viz" }
            assertTrue(name, charts.size in 1..2 && charts.all { it["caption"].asString.length > 20 && it["height"].asInt in 120..380 })
            assertTrue(name, blocks.filter { it["type"].asString == "rows" }.all { it.getAsJsonArray("rows").size() <= 10 })
            val s = c["summary"].asString
            assertTrue(name, s.contains("Source: NSE") && s.endsWith("."))
            // describes; never advises
            val text = json.lowercase()
            for (w in listOf("should buy", "should sell", "bullish", "bearish", "target", "recommend", "support", "resistance")) assertFalse("$name says \"$w\"", text.contains(w))
            assertNotNull(name, c["symbol"])
        }
    }
}
