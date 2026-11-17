package com.khayali.pulao.term

import com.google.gson.JsonObject
import com.khayali.pulao.ai.term.Series
import com.khayali.pulao.ai.term.TechCompareFeature
import com.khayali.pulao.ai.term.TechDrawdownFeature
import com.khayali.pulao.ai.term.TechFeed
import com.khayali.pulao.ai.term.TechLevelsFeature
import com.khayali.pulao.ai.term.TechMacdFeature
import com.khayali.pulao.ai.term.TechMath
import com.khayali.pulao.ai.term.TechReturnsFeature
import com.khayali.pulao.ai.term.TechRsiFeature
import com.khayali.pulao.ai.term.TechSeasonalityFeature
import com.khayali.pulao.ai.term.TechTaFeature
import com.khayali.pulao.ai.term.TechVolatilityFeature
import com.khayali.pulao.ai.term.Terminal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * Technical-analysis pack. Fixtures are trimmed copies of real quote-feed replies captured on 17 Nov 2026 before the
 * market opened (so each ends with the feed's pre-open placeholder bar): INFY 2y daily (last 270 bars), INFY and
 * Nifty 50 5y weekly, INFY 10y monthly, TCS 6mo daily, and the feed's "not found" reply.
 */
class TechTest {
    private val infy = TechFeed.Sym("INFY.NS", "INFY")
    private val tcs = TechFeed.Sym("TCS.NS", "TCS")
    private val today = LocalDate.of(2026, 11, 17)
    private fun fx(n: String) = TermTesting.fixture(n)
    private val daily get() = fx("tech_infy_2y.json")
    private fun summary(json: String) = TermTesting.card(json)["summary"].asString
    private fun head(json: String): JsonObject = TermTesting.card(json).getAsJsonObject("card")
    private fun JsonObject.nums(k: String) = getAsJsonArray(k).map { if (it.isJsonNull) null else it.asDouble }

    /** Three daily bars; [rmt] is the feed's last-trade time and the stamp of the third bar. Session of 17 Nov 2026: 09:15–15:30 IST. */
    private fun three(rmt: Long) = """{"chart":{"result":[{"meta":{"symbol":"INFRA.NS","longName":"Infra Builders","regularMarketTime":$rmt,
        "currentTradingPeriod":{"regular":{"start":1794887100,"end":1794909600}}},"timestamp":[1794541500,1794800700,$rmt],
        "indicators":{"quote":[{"open":[100,104,106],"high":[108,110,112],"low":[98,100,105],"close":[104,105,111],"volume":[10,20,5]}]}}]}}"""

    // ---------- maths, against hand-computed values ----------

    @Test fun rsiIsWildersSmoothing() {
        // changes +1, −0.5, +1, +1, −0.5 with n = 3: first averages 2/3 and 1/6 → RS 4 → 80; then 7 → 87.5; then 0.518519 / 0.240741 → 68.2927
        val r = TechMath.rsi(listOf(10.0, 11.0, 10.5, 11.5, 12.5, 12.0), 3)
        assertEquals(listOf(null, null, null), r.take(3))
        assertEquals(80.0, r[3]!!, 1e-9); assertEquals(87.5, r[4]!!, 1e-9); assertEquals(68.292683, r[5]!!, 1e-6)
        assertEquals(100.0, TechMath.rsi(listOf(1.0, 2.0, 3.0, 4.0), 3).last()!!, 1e-9)           // no losses at all
        assertEquals(50.0, TechMath.rsi(listOf(5.0, 5.0, 5.0, 5.0), 3).last()!!, 1e-9)            // no movement
        assertTrue(TechMath.rsi(listOf(1.0, 2.0), 14).all { it == null })
    }

    @Test fun emaAndMacd() {
        assertEquals(listOf(null, null, 2.0, 3.0, 4.0), TechMath.ema(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3))       // seed = mean(1,2,3), k = 0.5
        // closes 1,2,4,8,16 with (2, 3, 2): EMA2 = 1.5, 19/6, 115/18, 691/54; EMA3 = 7/3, 31/6, 127/12
        val m = TechMath.macd(listOf(1.0, 2.0, 4.0, 8.0, 16.0), 2, 3, 2)
        assertEquals(listOf(null, null), m.line.take(2))
        assertEquals(5.0 / 6, m.line[2]!!, 1e-12); assertEquals(11.0 / 9, m.line[3]!!, 1e-12); assertEquals(239.0 / 108, m.line[4]!!, 1e-12)
        assertNull(m.signal[2]); assertEquals(37.0 / 36, m.signal[3]!!, 1e-12); assertEquals(589.0 / 324, m.signal[4]!!, 1e-12)
        assertEquals(128.0 / 324, m.hist[4]!!, 1e-12)
        assertTrue(TechMath.macd(listOf(1.0, 2.0, 3.0)).hist.all { it == null })
    }

    @Test fun bollingerPivotsVolatility() {
        val b = TechMath.bollinger(listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0), 8, 2.0)        // mean 5, population sd 2
        assertEquals(5.0, b.mid.last()!!, 1e-12); assertEquals(9.0, b.upper.last()!!, 1e-12); assertEquals(1.0, b.lower.last()!!, 1e-12); assertNull(b.upper[6])
        val p = TechMath.pivots(110.0, 100.0, 105.0)
        assertEquals(listOf(105.0, 110.0, 115.0, 120.0, 100.0, 95.0, 90.0), listOf(p.p, p.r1, p.r2, p.r3, p.s1, p.s2, p.s3))
        // returns +1%, −1%, +1%, −1%: sample sd = sqrt(4e-4 / 3); × sqrt(252) × 100 = 18.3303
        val c = listOf(100.0, 101.0, 99.99, 100.9899, 99.980001)
        val v = TechMath.rollingVol(c, 4)
        assertEquals(listOf(null, null, null, null), v.take(4)); assertEquals(18.330303, v[4]!!, 1e-5)
        assertEquals(25.0, TechMath.position(12.5, 10.0, 20.0), 1e-12); assertEquals(50.0, TechMath.position(1.0, 2.0, 2.0), 1e-12)
        assertEquals(2.5, TechMath.median(listOf(4.0, 1.0, 3.0, 2.0)), 1e-12); assertEquals(2.0, TechMath.median(listOf(3.0, 1.0, 2.0)), 1e-12)
    }

    @Test fun worstDrawdownMonthlyReturnsAndLookups() {
        val w = TechMath.worstDrawdown(listOf(100.0, 120.0, 90.0, 60.0, 80.0, 120.0, 130.0, 117.0))!!
        assertEquals(1, w.peak); assertEquals(3, w.trough); assertEquals(5, w.recovered); assertEquals(-50.0, w.depth, 1e-9)
        assertNull(TechMath.worstDrawdown(listOf(100.0, 80.0, 90.0))!!.recovered)
        assertNull(TechMath.worstDrawdown(listOf(1.0, 2.0, 3.0)))
        val d = listOf("2026-01-15", "2026-01-30", "2026-02-27", "2026-04-30", "2026-05-29").map(LocalDate::parse)
        val m = TechMath.monthlyReturns(d, listOf(90.0, 100.0, 110.0, 99.0, 108.9))
        assertEquals(setOf(YearMonth.of(2026, 2), YearMonth.of(2026, 5)), m.keys)        // April has no March before it, so no April figure
        assertEquals(10.0, m[YearMonth.of(2026, 2)]!!, 1e-9); assertEquals(10.0, m[YearMonth.of(2026, 5)]!!, 1e-9)
        assertEquals(100.0, TechMath.closeAt(d, listOf(90.0, 100.0, 110.0, 99.0, 108.9), LocalDate.parse("2026-02-10"))!!, 0.0)
        assertEquals(90.0, TechMath.closeAt(d, listOf(90.0, 100.0, 110.0, 99.0, 108.9), LocalDate.parse("2026-01-08"))!!, 0.0)       // starts a week late: accepted
        assertNull(TechMath.closeAt(d, listOf(90.0, 100.0, 110.0, 99.0, 108.9), LocalDate.parse("2026-01-08"), 0))
        assertNull(TechMath.closeAt(d, listOf(90.0, 100.0, 110.0, 99.0, 108.9), LocalDate.parse("2025-06-01")))
        assertEquals(listOf(0, 3, 6, 9), TechFeed.thin(10, 4)); assertEquals(listOf(0, 1, 2), TechFeed.thin(3, 10))
    }

    // ---------- the feed: settled, pre-open and in-session last bars ----------

    @Test fun feedKnowsWhetherTheLastBarIsSettled() {
        val real = TechFeed.bars(daily)!!                    // captured at 09:09, before the open: the placeholder bar is dropped
        assertEquals(270, Series.parse(daily)!!.size); assertEquals(269, real.s.size)
        assertFalse(real.live); assertEquals(LocalDate.of(2026, 11, 16), real.asOf); assertEquals(1145.1, real.s.last, 1e-9)
        val open = TechFeed.bars(three(1794890700))!!        // 10:15 on the 17th: session in progress
        assertTrue(open.live); assertEquals(3, open.s.size); assertEquals("was at", open.verb); assertTrue(open.whenText, open.whenText.endsWith("17 Nov 2026 (indicative)"))
        val pre = TechFeed.bars(three(1794886800))!!         // 09:10: before the open
        assertFalse(pre.live); assertEquals(2, pre.s.size); assertEquals(LocalDate.of(2026, 11, 16), pre.asOf)
        val shut = TechFeed.bars(three(1794909600))!!        // 15:30: the session is over, the bar is final
        assertFalse(shut.live); assertEquals(3, shut.s.size); assertEquals("closed at", shut.verb)
        assertNull(TechFeed.bars("")); assertNull(TechFeed.bars("<html>")); assertNull(TechFeed.bars(fx("tech_notfound.json")))
    }

    // ---------- features ----------

    @Test fun ta() {
        val json = TechTaFeature.build(infy, daily)
        val s = summary(json)
        assertEquals("INFY closed at ₹1,145.10 on 16 Nov 2026: 20-day average ₹1,154.19, 50-day average ₹1,089.75, 200-day average ₹1,191.18; " +
            "Bollinger band (20, 2) ₹1,122.85 to ₹1,185.52. Source: Yahoo Finance.", s)
        assertEquals("₹1,145.10", head(json)["headline"].asString)
        assertTrue(head(json)["sub"].asString, head(json)["sub"].asString.startsWith("Above its 50-day average, below its 20-day and 200-day averages"))
        val d = TermTesting.viz(json, "overlay")
        assertEquals(252, d.getAsJsonArray("t").size()); assertEquals("2026-11-16", d.getAsJsonArray("t").last().asString); assertEquals(1145.1, d.nums("price").last()!!, 1e-9)
        val lines = d.getAsJsonArray("lines").map { it.asJsonObject }
        assertEquals(listOf("20-day avg", "50-day avg", "200-day avg"), lines.map { it["name"].asString })
        assertEquals(252, lines[2].nums("v").size); assertNull(lines[2].nums("v").first())          // 269 bars: the 200-day average starts part-way
        assertEquals(1191.18, lines[2].nums("v").last()!!, 1e-9); assertEquals(1154.19, lines[0].nums("v").last()!!, 0.0051)
        assertEquals(1185.52, d.getAsJsonObject("band").nums("upper").last()!!, 1e-9); assertEquals(1122.85, d.getAsJsonObject("band").nums("lower").last()!!, 1e-9)
        assertEquals("Price (₹)", d["yLabel"].asString); assertEquals("Date", d["xLabel"].asString); assertEquals("₹", d["unit"].asString)
        // an index prints levels, not rupees
        val idx = TechTaFeature.build(TechFeed.NIFTY, fx("tech_nsei_5y_1wk.json"))
        assertTrue(summary(idx), summary(idx).startsWith("Nifty 50 closed at 25,291.10 on 16 Nov 2026")); assertEquals("Index level", TermTesting.viz(idx, "overlay")["yLabel"].asString)
        assertTrue(TermTesting.error(TechTaFeature.build(infy, three(1794909600)))!!.contains("too few for a 20-day average"))
        assertTrue(TermTesting.error(TechTaFeature.build(infy, fx("tech_notfound.json")))!!.contains("No price history for INFY"))
        assertTrue(TermTesting.error(TechTaFeature.build(infy, ""))!!.contains("No price history"))
    }

    @Test fun levels() {
        val json = TechLevelsFeature.build(infy, daily)
        // 16 Nov 2026: high 1,159.90, low 1,138.20, close 1,145.10 → P = 1,147.73
        assertEquals("INFY closed at ₹1,145.10 on 16 Nov 2026; classic pivots from the session of 16 Nov 2026: pivot ₹1,147.73, support ₹1,135.57 and ₹1,126.03, " +
            "resistance ₹1,157.27 and ₹1,169.43. Its 52-week range is ₹980.40 to ₹1,728.00. Source: Yahoo Finance.", summary(json))
        assertTrue(head(json)["sub"].asString.startsWith("Between S1 (₹1,135.57) and Pivot (₹1,147.73)"))
        val d = TermTesting.viz(json, "overlay")
        assertEquals(60, d.getAsJsonArray("price").size())
        val lv = d.getAsJsonArray("levels").map { it.asJsonObject }.associate { it["label"].asString to it }
        assertEquals(setOf("R2", "R1", "P", "S1", "S2", "52-wk high", "52-wk low"), lv.keys)
        assertEquals(1147.73, lv["P"]!!["value"].asDouble, 1e-9); assertEquals(1728.0, lv["52-wk high"]!!["value"].asDouble, 1e-9); assertFalse(lv["52-wk high"]!!["fit"].asBoolean)
        val zones = d.getAsJsonArray("zones").map { it.asJsonObject }
        assertEquals(listOf(1126.03, 1135.57), listOf(zones[0]["lo"].asDouble, zones[0]["hi"].asDouble)); assertEquals(listOf(1157.27, 1169.43), listOf(zones[1]["lo"].asDouble, zones[1]["hi"].asDouble))
        val rows = TermTesting.blocks(json).first { it["type"].asString == "rows" }.getAsJsonArray("rows").map { it.asJsonObject }
        assertEquals(9, rows.size); assertEquals("R3 · resistance", rows[0]["title"].asString); assertEquals("₹1,178.97", rows[0]["value"].asString)       // H + 2(P − L)
        // while a session is on, pivots come from the last finished one: 110 / 100 / 105 → pivot 105
        val live = summary(TechLevelsFeature.build(infy, three(1794890700)))
        assertTrue(live, live.startsWith("INFY was at ₹111.00 on 17 Nov 2026; classic pivots from the session of 16 Nov 2026: pivot ₹105.00, support ₹100.00 and ₹95.00, resistance ₹110.00 and ₹115.00."))
        assertTrue(TermTesting.error(TechLevelsFeature.build(infy, "{}"))!!.contains("No price history"))
    }

    @Test fun rsi() {
        val json = TechRsiFeature.build(infy, daily)
        assertEquals("INFY's 14-day RSI was 53.3 at the close of 16 Nov 2026, in the upper half of the neutral 30–70 range: gains have outweighed losses over the last 14 sessions; " +
            "it was 60.6 five sessions earlier. Source: Yahoo Finance.", summary(json))
        assertEquals("RSI 53.3", head(json)["headline"].asString); assertEquals(53.28, TermTesting.card(json)["rsi"].asDouble, 1e-9)
        val d = TermTesting.viz(json, "oscillator")
        assertEquals(126, d.getAsJsonArray("line").size()); assertEquals(126, d.getAsJsonArray("t").size()); assertEquals(53.28, d.nums("line").last()!!, 1e-9)
        assertTrue(d.nums("line").all { it!! in 0.0..100.0 }); assertEquals(0, d["min"].asInt); assertEquals(100, d["max"].asInt)
        assertEquals(listOf(70.0, 30.0), d.getAsJsonArray("thresholds").map { it.asJsonObject["value"].asDouble }); assertEquals(2, d.getAsJsonArray("zones").size())
        assertEquals("RSI (0–100)", d["yLabel"].asString)
        assertEquals("Above 70", TechRsiFeature.reading(70.0).first); assertEquals("Below 30", TechRsiFeature.reading(30.0).first)
        assertTrue(TechRsiFeature.reading(49.9).first.startsWith("Lower half")); assertTrue(TechRsiFeature.reading(81.0).second.contains("overbought"))
        assertTrue(TermTesting.error(TechRsiFeature.build(infy, three(1794909600)))!!.contains("needs at least 15"))
        assertTrue(TermTesting.error(TechRsiFeature.build(infy, "<html>"))!!.contains("No price history"))
    }

    @Test fun macd() {
        val json = TechMacdFeature.build(infy, daily)
        assertEquals("INFY MACD (12, 26, 9) at the close of 16 Nov 2026: MACD line 17.47, signal 21.77, histogram −4.30 — the MACD line is below its signal line and above zero; " +
            "it moved below the signal line on 11 Nov 2026, 3 sessions ago. Source: Yahoo Finance.", summary(json))
        val d = TermTesting.viz(json, "oscillator")
        assertEquals(126, d.getAsJsonArray("t").size())
        assertEquals(17.47, d.nums("line").last()!!, 1e-9); assertEquals(21.77, d.nums("signal").last()!!, 1e-9); assertEquals(-4.3, d.nums("hist").last()!!, 1e-9)
        assertEquals("MACD (₹)", d["yLabel"].asString); assertEquals("Signal (9)", d["signalName"].asString); assertFalse(d.has("min"))
        assertTrue(TermTesting.error(TechMacdFeature.build(infy, three(1794909600)))!!.contains("needs at least 34"))
        assertTrue(TermTesting.error(TechMacdFeature.build(infy, fx("tech_notfound.json")))!!.contains("No price history"))
    }

    @Test fun volatility() {
        val json = TechVolatilityFeature.build(infy, daily)
        assertEquals("INFY's 30-day annualised volatility was 35.9% at the close of 16 Nov 2026, in the upper third of its range over the last 239 sessions of 16.1% to 44.7% (median 30.9%). " +
            "Source: Yahoo Finance.", summary(json))
        assertEquals("35.9% a year", head(json)["headline"].asString)
        val d = TermTesting.viz(json, "oscillator")
        assertEquals(239, d.getAsJsonArray("line").size()); assertEquals(35.89, d.nums("line").last()!!, 1e-9)
        assertEquals(listOf(44.73, 30.92, 16.06), d.getAsJsonArray("thresholds").map { it.asJsonObject["value"].asDouble })
        assertEquals("Volatility (% a year)", d["yLabel"].asString); assertEquals("%", d["suffix"].asString)
        assertTrue(TermTesting.error(TechVolatilityFeature.build(infy, three(1794909600)))!!.contains("needs at least 31"))
        assertTrue(TermTesting.error(TechVolatilityFeature.build(infy, ""))!!.contains("No price history"))
    }

    @Test fun drawdown() {
        val json = TechDrawdownFeature.build(infy, fx("tech_infy_5y_1wk.json"))
        assertEquals("INFY's deepest fall from a peak in the last 5 years was −50.0%, from 9 Dec 2024 to 21 Sep 2026 (651 days); it has not yet closed back at that peak (56 days since the low). " +
            "It is now −42.7% from its highest close of the period. Source: Yahoo Finance.", summary(json))
        assertEquals("−50.0%", head(json)["headline"].asString); assertEquals("down", head(json)["tone"].asString)
        val d = TermTesting.viz(json, "underwater")
        val dd = d.nums("dd").map { it!! }
        assertEquals(262, dd.size); assertEquals(d.getAsJsonArray("t").size(), dd.size); assertTrue(dd.all { it <= 0.0 }); assertEquals(0.0, dd.first(), 0.0)
        assertEquals(-49.98, dd.min(), 1e-9); assertEquals(-42.74, dd.last(), 1e-9)
        val marks = d.getAsJsonObject("marks")
        assertEquals("2024-12-09", marks["peak"].asString); assertEquals("2026-09-21", marks["trough"].asString); assertFalse(marks.has("recovered"))
        assertEquals("Below running peak (%)", d["yLabel"].asString)
        // the Nifty's worst fall of the period did recover
        val nifty = summary(TechDrawdownFeature.build(TechFeed.NIFTY, fx("tech_nsei_5y_1wk.json")))
        assertTrue(nifty, nifty.startsWith("Nifty 50's deepest fall from a peak in the last 5 years was −") && nifty.contains("it closed back at that peak on "))
        assertTrue(TermTesting.error(TechDrawdownFeature.build(infy, fx("tech_notfound.json")))!!.contains("No price history"))
        val rising = """{"chart":{"result":[{"meta":{"symbol":"UP.NS"},"timestamp":[1794541500,1794800700],"indicators":{"quote":[{"open":[1,2],"high":[1,2],"low":[1,2],"close":[1,2],"volume":[1,1]}]}}]}}"""
        assertTrue(TermTesting.error(TechDrawdownFeature.build(infy, rising))!!.contains("no drawdown to chart"))
    }

    @Test fun returns() {
        val json = TechReturnsFeature.build(infy, fx("tech_infy_5y_1wk.json"), fx("tech_nsei_5y_1wk.json"))
        assertEquals("INFY price return to 16 Nov 2026: 1 week +0.3%, 1 month −1.4%, 3 months −2.1%, 6 months +2.3%, 1 year −23.8%, 3 years −20.3%, 5 years −35.6% (5-year CAGR −8.4% a year). " +
            "Nifty 50 over the same periods: 1W +0.6%, 1M +9.3%, 3M +3.8%, 6M +7.0%, 1Y −2.4%, 3Y +28.2%, 5Y +42.4%. Source: Yahoo Finance.", summary(json))
        assertEquals("−23.8%", head(json)["headline"].asString); assertEquals("down", head(json)["tone"].asString)
        assertTrue(head(json)["sub"].asString, head(json)["sub"].asString.startsWith("1 year price return · Nifty 50 −2.4% · behind by 21.4 points"))
        val charts = TermTesting.blocks(json).filter { it["type"].asString == "viz" }.map { it.getAsJsonObject("data") }
        assertEquals(2, charts.size)
        assertEquals(listOf("1W", "1M", "3M", "6M"), charts[0].getAsJsonArray("items").map { it.asJsonObject["label"].asString })
        assertEquals(listOf("1Y", "3Y", "5Y"), charts[1].getAsJsonArray("items").map { it.asJsonObject["label"].asString })
        assertEquals(listOf("INFY", "Nifty 50"), charts[0].getAsJsonArray("series").map { it.asString })
        val fiveY = charts[1].getAsJsonArray("items").last().asJsonObject.getAsJsonArray("values").map { it.asDouble }
        assertEquals(2, fiveY.size); assertEquals(-35.6, fiveY[0], 0.05); assertEquals(42.4, fiveY[1], 0.05)
        val rows = TermTesting.blocks(json).first { it["type"].asString == "rows" }.getAsJsonArray("rows").map { it.asJsonObject }
        assertEquals(9, rows.size); assertEquals("5-year CAGR", rows.last()["title"].asString); assertEquals("−8.4% a year", rows.last()["value"].asString)
        // no benchmark reply → the symbol alone; the index itself is never compared with itself
        val alone = TechReturnsFeature.build(infy, fx("tech_infy_5y_1wk.json"), "")
        assertFalse(summary(alone).contains("Nifty")); assertEquals(listOf("INFY"), TermTesting.viz(alone, "groupbars").getAsJsonArray("series").map { it.asString })
        assertFalse(summary(TechReturnsFeature.build(TechFeed.NIFTY, fx("tech_nsei_5y_1wk.json"), fx("tech_nsei_5y_1wk.json"))).contains("same periods"))
        // six months of history: only the periods that fit are shown
        val shortHist = TechReturnsFeature.build(tcs, fx("tech_tcs_6mo.json"), "")
        assertEquals(listOf("1W", "1M", "3M"), TermTesting.viz(shortHist, "groupbars").getAsJsonArray("items").map { it.asJsonObject["label"].asString })
        assertTrue(TermTesting.error(TechReturnsFeature.build(infy, "", fx("tech_nsei_5y_1wk.json")))!!.contains("No price history"))
    }

    @Test fun seasonality() {
        val json = TechSeasonalityFeature.build(infy, fx("tech_infy_10y_1mo.json"), today)
        assertEquals("INFY monthly price changes, Jan 2017 to Oct 2026 (118 months): strongest month on average July +8.8% (up 9 of 10 years), weakest April −3.8% (up 3 of 10 years). " +
            "Source: Yahoo Finance.", summary(json))
        assertEquals("July: +8.8% on average", head(json)["headline"].asString)
        val d = TermTesting.viz(json, "matrix")
        assertEquals(12, d.getAsJsonArray("cols").size()); assertEquals("Jan", d.getAsJsonArray("cols").first().asString)
        val rows = d.getAsJsonArray("rows").map { it.asJsonObject }
        assertEquals((2017..2026).map { it.toString() }, rows.map { it["label"].asString }); assertTrue(rows.all { it.getAsJsonArray("values").size() == 12 })
        assertEquals(-8.11, rows.first().nums("values")[0]!!, 1e-9); assertEquals(9.02, rows.first().nums("values")[1]!!, 1e-9)        // 505.30 → 464.30 → 506.20
        assertEquals(-3.67, rows.last().nums("values")[10]!!, 1e-9); assertNull(rows.last().nums("values")[11])                       // November so far; December not yet
        assertEquals(8.8, d.getAsJsonObject("footer").nums("values")[6]!!, 1e-9)
        assertEquals("2026", d.getAsJsonObject("partial")["row"].asString); assertEquals(10, d.getAsJsonObject("partial")["col"].asInt)
        assertEquals("Month", d["xLabel"].asString); assertEquals("Year", d["yLabel"].asString)
        assertTrue(TermTesting.error(TechSeasonalityFeature.build(tcs, fx("tech_tcs_6mo.json"), today))!!.contains("needs at least 12"))
        assertTrue(TermTesting.error(TechSeasonalityFeature.build(infy, "<html>", today))!!.contains("No price history"))
    }

    @Test fun compare() {
        assertEquals(listOf("INFY", "TCS") to "1y", TechCompareFeature.parse("INFY TCS 1y"))
        assertEquals(listOf("INFY", "TCS", "HDFCBANK") to "6mo", TechCompareFeature.parse("@infy, tcs vs hdfcbank 6m"))
        assertEquals(listOf("INFY", "NIFTY") to "1y", TechCompareFeature.parse("infy and nifty"))
        assertEquals(4, TechCompareFeature.parse("a b c d e 5y").first.size)
        val json = TechCompareFeature.build(listOf(infy, tcs), listOf(daily, fx("tech_tcs_6mo.json")), "6mo")
        // from 18 May 2026: INFY 1,142.50 → 1,145.10, TCS 2,283.20 → 2,405.00
        assertEquals("Rebased to 100 on 18 May 2026, by 16 Nov 2026 (6 months): TCS 105.3 (+5.3%), INFY 100.2 (+0.2%). Source: Yahoo Finance.", summary(json))
        assertEquals("TCS +5.3%", head(json)["headline"].asString); assertEquals("INFY vs TCS · rebased to 100 · 6 months", head(json)["kicker"].asString)
        val d = TermTesting.viz(json, "multiline")
        val series = d.getAsJsonArray("series").map { it.asJsonObject }
        assertEquals(listOf("INFY", "TCS"), series.map { it["name"].asString }); assertEquals(125, d.getAsJsonArray("t").size())
        assertTrue(series.all { it.nums("v").size == 125 && it.nums("v").first() == 100.0 })
        assertEquals(100.23, series[0].nums("v").last()!!, 1e-9); assertEquals(105.33, series[1].nums("v").last()!!, 1e-9)
        assertEquals("2026-05-18", d.getAsJsonArray("t").first().asString); assertEquals(100, d["base"].asInt)
        // one symbol with no data is left out and named; fewer than two is an error
        val three = TechCompareFeature.build(listOf(infy, TechFeed.Sym("NOPE.NS", "NOPE"), tcs), listOf(daily, fx("tech_notfound.json"), fx("tech_tcs_6mo.json")), "6mo")
        assertEquals(2, TermTesting.viz(three, "multiline").getAsJsonArray("series").size())
        assertTrue(TermTesting.blocks(three).first { it["type"].asString == "note" }["text"].asString.startsWith("No data for NOPE"))
        assertTrue(TermTesting.error(TechCompareFeature.build(listOf(infy, tcs), listOf(daily, ""), "1y"))!!.contains("No price history for TCS"))
    }

    // ---------- run(): what is asked of the feed ----------

    @Test fun runAsksOnlyTheQuoteFeed() = runBlocking {
        val charts = mapOf("INFY.NS" to daily, "TCS.NS" to fx("tech_tcs_6mo.json"), "^NSEI" to fx("tech_nsei_5y_1wk.json"), "OLD.BO" to daily)
        fun fake() = TermTesting.Fake(charts = charts)
        suspend fun asked(run: suspend (TermTesting.Fake) -> String): List<String> { val f = fake(); assertNull(TermTesting.error(run(f))); return f.asked }
        assertEquals(listOf("chart:INFY.NS:2y:1d"), asked { TechTaFeature.run("INFY", it) })
        assertEquals(listOf("chart:INFY.NS:1y:1d"), asked { TechRsiFeature.run("@infy", it) })
        assertEquals(listOf("chart:INFY.NS:1y:1d"), asked { TechMacdFeature.run("INFY", it) })
        assertEquals(listOf("chart:INFY.NS:1y:1d"), asked { TechLevelsFeature.run("INFY", it) })
        assertEquals(listOf("chart:INFY.NS:2y:1d"), asked { TechVolatilityFeature.run("INFY", it) })
        assertEquals(listOf("chart:INFY.NS:5y:1d"), asked { TechDrawdownFeature.run("INFY", it) })
        assertEquals(listOf("chart:INFY.NS:5y:1d", "chart:^NSEI:5y:1d"), asked { TechReturnsFeature.run("INFY", it) })
        assertEquals(listOf("chart:^NSEI:5y:1d"), asked { TechReturnsFeature.run("nifty", it) })
        assertEquals(listOf("chart:INFY.NS:10y:1mo"), asked { TechSeasonalityFeature.run("INFY", it) })
        assertEquals(listOf("chart:INFY.NS:6mo:1d", "chart:TCS.NS:6mo:1d"), asked { TechCompareFeature.run("INFY TCS 6m", it) })
        assertEquals(listOf("chart:INFY.NS:5y:1wk", "chart:^NSEI:5y:1wk"), asked { TechCompareFeature.run("infy vs nifty 5y", it) })
        assertEquals(listOf("chart:^NSEI:2y:1d"), asked { TechTaFeature.run("Nifty 50", it) })
        assertEquals(listOf("chart:OLD.NS:1y:1d", "chart:OLD.BO:1y:1d"), asked { TechRsiFeature.run("OLD", it) })        // not on NSE → tried once on BSE
        for (f in Terminal.FEATURES.filter { it.javaClass.simpleName.startsWith("Tech") }) {
            assertTrue(f.id, TermTesting.error(f.run("", fake()))!!.contains(f.usage))                                         // no symbol → says how to ask
            if (f.id != "compare") assertTrue(f.id, TermTesting.error(f.run("ZZZZ", fake()))!!.contains("No price history for ZZZZ"))
        }
        assertTrue(TermTesting.error(TechCompareFeature.run("INFY", fake()))!!.contains("two to four symbols"))
        assertTrue(TermTesting.error(TechCompareFeature.run("INFY infy", fake()))!!.contains("two to four symbols"))
        assertTrue(TermTesting.error(TechCompareFeature.run("INFY ZZZZ", fake()))!!.contains("No price history for ZZZZ"))
    }

    @Test fun registeredWithTriggersThatRouteShortQuestions() {
        val ids = listOf("ta", "rsi", "macd", "drawdown", "returns", "seasonality", "compare", "levels", "volatility")
        for (id in ids) assertNotNull(id, Terminal.find(id))
        fun hit(q: String) = Terminal.match(q)?.let { it.first.id to it.second }
        assertEquals("ta" to "INFY", hit("moving averages of INFY")); assertEquals("ta" to "TCS", hit("bollinger bands for TCS")); assertEquals("ta" to "", hit("200 dma"))
        assertEquals("rsi" to "INFY", hit("rsi of INFY")); assertEquals("rsi" to "RELIANCE", hit("is @RELIANCE overbought"))
        assertEquals("macd" to "INFY", hit("macd for INFY"))
        assertEquals("drawdown" to "INFY", hit("max drawdown of INFY")); assertEquals("returns" to "INFY", hit("price returns of INFY"))
        assertEquals("seasonality" to "TCS", hit("seasonality of TCS")); assertEquals("seasonality" to "INFY", hit("best months for INFY"))
        assertEquals("levels" to "INFY", hit("support and resistance for INFY")); assertEquals("levels" to "INFY", hit("pivot levels of INFY"))
        assertEquals("volatility" to "INFY", hit("how volatile is @INFY")); assertEquals("volatility" to "INFY", hit("historical volatility of INFY"))
        assertEquals("heatmap", hit("show me the heatmap")?.first)
        assertNull(hit("what is the price of INFY")); assertNull(hit("my returns"))
        // implied volatility belongs to the derivatives pack, not technical analysis
        assertTrue(hit("implied volatility of nifty options")?.first !in setOf("volatility", "ta", "rsi", "macd"))
    }

    // ---------- rules every card in the pack must keep ----------

    @Test fun everyCardIsCompleteAndNeverAdvises() {
        val cards = mapOf(
            "ta" to TechTaFeature.build(infy, daily), "levels" to TechLevelsFeature.build(infy, daily), "rsi" to TechRsiFeature.build(infy, daily),
            "macd" to TechMacdFeature.build(infy, daily), "volatility" to TechVolatilityFeature.build(infy, daily),
            "drawdown" to TechDrawdownFeature.build(infy, fx("tech_infy_5y_1wk.json")),
            "returns" to TechReturnsFeature.build(infy, fx("tech_infy_5y_1wk.json"), fx("tech_nsei_5y_1wk.json")),
            "seasonality" to TechSeasonalityFeature.build(infy, fx("tech_infy_10y_1mo.json"), today),
            "compare" to TechCompareFeature.build(listOf(infy, tcs), listOf(daily, fx("tech_tcs_6mo.json")), "6mo"))
        val advice = Regex("""(?i)\b(buy|sell|hold|target price|should you|recommend\w*|bullish|bearish|signal to)\b""")
        for ((id, json) in cards) {
            val c = TermTesting.card(json); val h = c.getAsJsonObject("card")
            assertNull(id, c["error"])
            assertTrue(id, h["kicker"].asString.isNotBlank() && h["headline"].asString.isNotBlank() && h["sub"].asString.isNotBlank())
            assertEquals(id, listOf("Yahoo Finance"), c.getAsJsonArray("sources").map { it.asString })
            val pills = c.getAsJsonArray("pills").map { it.asString }
            assertTrue(id, pills.size in 2..4 && pills.all { it.startsWith("/") } && pills.none { it.startsWith("/$id ") })
            val s = c["summary"].asString
            assertTrue("$id: $s", s.endsWith("Source: Yahoo Finance.") && s.length < 420)
            val blocks = TermTesting.blocks(json); val charts = blocks.filter { it["type"].asString == "viz" }
            assertTrue(id, charts.size in 1..2 && charts.all { it["caption"].asString.length > 30 && it["height"].asInt in 150..330 })
            assertTrue(id, blocks.filter { it["type"].asString == "rows" }.all { it.getAsJsonArray("rows").size() <= 10 })
            assertTrue(id, blocks.any { it["type"].asString == "note" && it["text"].asString.contains("can be delayed") })
            val words = h.toString() + s
            assertNull("$id advises: ${advice.find(words)?.value}", advice.find(words))
            assertTrue("$id: card too large for a phone (${json.length})", json.length < 40_000)
        }
    }
}
