package com.khayali.pulao.term

import com.khayali.pulao.ai.term.StockFeeds
import com.khayali.pulao.ai.term.StockHistoryFeature
import com.khayali.pulao.ai.term.StockMindmapFeature
import com.khayali.pulao.ai.term.StockPeersFeature
import com.khayali.pulao.ai.term.StockQuoteFeature
import com.khayali.pulao.ai.term.StockScorecardFeature
import com.khayali.pulao.ai.term.StockShareholdingFeature
import com.khayali.pulao.ai.term.Terminal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** The single-stock pack: every build() against a trimmed copy of the real feed reply, plus the empty, malformed and pre-open cases. */
class StockTest {
    private fun fx(name: String) = TermTesting.fixture("stock_$name.json")
    private fun summary(json: String) = TermTesting.card(json)["summary"].asString
    private fun card(json: String) = TermTesting.card(json).getAsJsonObject("card")
    private fun vizzes(json: String, type: String) = TermTesting.blocks(json).filter { it["type"].asString == "viz" && it["viz"].asString == type }
    private fun stats(json: String) = TermTesting.blocks(json).first { it["type"].asString == "stats" }.getAsJsonArray("items").associate { it.asJsonObject["label"].asString to it.asJsonObject["value"].asString }
    private fun note(json: String) = TermTesting.blocks(json).first { it["type"].asString == "note" }["text"].asString
    private val today = LocalDate.of(2026, 11, 17)

    @Test fun feedHelpers() {
        assertEquals(LocalDate.of(2026, 9, 30), StockFeeds.date("30-SEP-2026"))
        assertEquals(LocalDate.of(2026, 11, 16), StockFeeds.date("16-Nov-2026 16:00:28"))
        assertEquals(LocalDate.of(2026, 9, 30), StockFeeds.date("30 Sep 2026"))
        assertNull(StockFeeds.date("-")); assertNull(StockFeeds.date("31-FEB-2026")); assertNull(StockFeeds.date("12-XYZ-2026"))
        assertEquals("16 Nov 2026, 16:00", StockFeeds.stamp("16-Nov-2026 16:00:28"))
        assertEquals("17-10-2026", StockFeeds.dmy(LocalDate.of(2026, 10, 17)))
        assertEquals("+0.08 pts", StockFeeds.points(0.08)); assertEquals("−0.40 pts", StockFeeds.points(-0.4)); assertEquals("0.00 pts", StockFeeds.points(0.001))
        assertEquals("₹556 Cr", StockFeeds.crore(5556761321.16))
        assertTrue(StockFeeds.quotePath("M&M").endsWith("series=EQ&symbol=M%26M"))
        assertEquals("NIFTY IT", StockFeeds.sectorIndex("it")); assertEquals("NIFTY BANK", StockFeeds.sectorIndex(" nifty  bank "))
        assertEquals("NIFTY FIN SERVICE", StockFeeds.sectorIndex("Financial Services")); assertEquals("NIFTY OIL AND GAS", StockFeeds.sectorIndex("NIFTY OIL & GAS"))
        assertNull(StockFeeds.sectorIndex("INFY")); assertNull(StockFeeds.sectorIndex("NIFTY 500")); assertNull(StockFeeds.sectorIndex(""))
        // the stock's own sector index: the one NSE quotes its P/E against, else the first sector index it is a member of
        assertEquals("NIFTY IT", StockFeeds.sectorIndex(StockFeeds.quote(fx("quote_infy"))!!))
        assertEquals("NIFTY ENERGY", StockFeeds.sectorIndex(StockFeeds.quote(fx("quote_preopen"))!!))
    }

    @Test fun quote() {
        val json = StockQuoteFeature.build("INFY", fx("quote_infy"), fx("meta_infy"))
        assertEquals("INFY (Infosys Limited): last ₹1,145.10, +0.32% on the day; day range ₹1,138.20–₹1,159.90, VWAP ₹1,145.23; " +
            "22% of the way from its 52-week low ₹980.40 to its high ₹1,728.00. Volume 48,52,092 shares; delivery 44.35%; P/E 15.05 against 14.62 for NIFTY IT. Source: NSE.", summary(json))
        assertEquals("₹1,145.10", card(json)["headline"].asString); assertEquals("up", card(json)["tone"].asString)
        assertTrue(card(json)["sub"].asString, card(json)["sub"].asString.startsWith("+3.60 · +0.32% on the day · previous close ₹1,141.50 · as of 16 Nov 2026, 16:00"))
        val gauges = vizzes(json, "gauge").map { it.getAsJsonObject("data") }
        assertEquals(2, gauges.size)
        assertEquals(980.4, gauges[0]["min"].asDouble, 1e-9); assertEquals(1728.0, gauges[0]["max"].asDouble, 1e-9); assertEquals(1145.1, gauges[0]["value"].asDouble, 1e-9)
        assertEquals(1138.2, gauges[1]["min"].asDouble, 1e-9); assertEquals(1159.9, gauges[1]["max"].asDouble, 1e-9)
        assertEquals(listOf("VWAP" to 1145.23, "Prev close" to 1141.5, "Open" to 1150.0), gauges[1].getAsJsonArray("marks").map { it.asJsonObject["label"].asString to it.asJsonObject["value"].asDouble })
        val st = stats(json)
        assertEquals("44.35%", st["Delivery · 16 Nov"]); assertEquals("14.62", st["Sector P/E · NIFTY IT"]); assertEquals("48,52,092 shares · ₹556 Cr", st["Volume"]); assertEquals("₹4,64,715 Cr", st["Market cap"])
        assertTrue(st.size <= 6)
        assertTrue(note(json).contains("52-week high ₹1,728.00 on 3 Feb 2026; low ₹980.40 on 29 Sep 2026."))
        assertEquals(4, TermTesting.card(json).getAsJsonArray("pills").size())

        // pre-open: the price is indicative, there is no day range yet, and the card says so
        val pre = StockQuoteFeature.build("RELIANCE", fx("quote_preopen"))
        assertEquals("RELIANCE (RELIANCE INDUSTRIES LIMITED): last ₹1,339.00, −0.43% on the day (pre-open, indicative); " +
            "41% of the way from its 52-week low ₹1,152.20 to its high ₹1,611.80. Volume 38,480 shares; delivery 47.29%; P/E 18.96 against 18.96 for NIFTY 500. Source: NSE.", summary(pre))
        assertEquals(1, vizzes(pre, "gauge").size); assertEquals("down", card(pre)["tone"].asString)
        assertTrue(card(pre)["sub"].asString.contains("pre-open, indicative")); assertTrue(note(pre).contains("pre-open session"))

        // unknown symbol (the feed's real reply), malformed and empty bodies, and a reply with no price
        listOf("""{"error":"Unexpected end of JSON input"}""", "<html>", "", """{"equityResponse":[]}""", """{"equityResponse":[{"metaData":{"symbol":"X","closePrice":0},"orderBook":{"lastPrice":0}}]}""")
            .forEach { assertTrue(it, TermTesting.error(StockQuoteFeature.build("ZZZZQQ", it))!!.contains("no quote for ZZZZQQ")) }
        // a bare-bones reply still makes a card: only what the feed gave
        val bare = StockQuoteFeature.build("X", """{"equityResponse":[{"metaData":{"symbol":"X"},"orderBook":{"lastPrice":10.5}}]}""")
        assertEquals("X: last ₹10.50. Source: NSE.", summary(bare)); assertTrue(vizzes(bare, "gauge").isEmpty())

        assertEquals("EQ", StockQuoteFeature.series(fx("meta_infy"))); assertEquals("EQ", StockQuoteFeature.series("")); assertEquals("EQ", StockQuoteFeature.series("""{"symbol":null,"activeSeries":null}"""))
        assertEquals("BE", StockQuoteFeature.series("""{"activeSeries":["T0","BE"]}"""))
        val fake = TermTesting.Fake(nse = mapOf("NextApi/apiClient/GetQuoteApi?functionName=getSymbolData" to fx("quote_infy"), "NextApi/apiClient/GetQuoteApi?functionName=getMetaData" to fx("meta_infy")))
        assertNull(TermTesting.error(runBlocking { StockQuoteFeature.run("@infy", fake) }))
        assertEquals(listOf("nse:NextApi/apiClient/GetQuoteApi?functionName=getMetaData&symbol=INFY", "nse:NextApi/apiClient/GetQuoteApi?functionName=getSymbolData&marketType=N&series=EQ&symbol=INFY"), fake.asked)
        assertTrue(TermTesting.error(runBlocking { StockQuoteFeature.run("", TermTesting.Fake()) })!!.contains("Which stock"))
        assertTrue(TermTesting.error(runBlocking { StockQuoteFeature.run("INFY", TermTesting.Fake()) })!!.contains("no quote"))        // dead network
    }

    @Test fun shareholding() {
        val json = StockShareholdingFeature.build("INFY", fx("shp_infy"))
        assertEquals("INFY shareholding as of 30 Sep 2026: promoters 13.90%, public 85.88%, employee trusts 0.21%; promoter holding +0.08 pts against the previous filing (30 Jun 2026). Source: NSE.", summary(json))
        assertEquals("Promoters 13.90%", card(json)["headline"].asString)
        val d = TermTesting.viz(json, "stream")
        assertEquals(listOf("Promoters", "Public", "Employee trusts"), d.getAsJsonArray("series").map { it.asString })
        val pts = d.getAsJsonArray("points").map { it.asJsonObject }
        assertEquals(12, pts.size)                                                            // the fixture has 14 filings; the card shows the last 12, oldest first
        assertEquals("2024-03-31", pts.first()["t"].asString); assertEquals("2026-09-30", pts.last()["t"].asString)
        assertEquals(listOf(13.9, 85.88, 0.21), pts.last().getAsJsonArray("values").map { it.asDouble })
        assertEquals(pts.map { it["t"].asString }.sorted(), pts.map { it["t"].asString })
        pts.forEach { p -> assertEquals(100.0, p.getAsJsonArray("values").sumOf { it.asDouble }, 0.06) }      // the three parts are the whole company
        val st = stats(json)
        assertEquals("+0.08 pts", st["Promoters vs previous filing"]); assertEquals("−0.40 pts", st["Promoters vs a year ago"])     // 13.90 against 14.30 on 30 Sep 2025
        assertEquals("14.71%", st["Promoter high · Mar 2024"]); assertEquals("13.82%", st["Promoter low · Jun 2026"])
        assertEquals(5, TermTesting.blocks(json).first { it["type"].asString == "rows" }.getAsJsonArray("rows").size())

        listOf("[]", "<html>", "", """[{"date":"-","pr_and_prgrp":"x"}]""").forEach { assertTrue(it, TermTesting.error(StockShareholdingFeature.build("ZZZZQQ", it))!!.contains("no shareholding pattern")) }
        // one filing: still a card, no comparison claimed; a re-filed date counts once (first = latest submission)
        val one = StockShareholdingFeature.build("X", """[{"date":"30-SEP-2026","pr_and_prgrp":"50.44","public_val":"49.56","employeeTrusts":"0"},{"date":"30-SEP-2026","pr_and_prgrp":"50.4","public_val":"49.6","employeeTrusts":"0"}]""")
        assertEquals("X shareholding as of 30 Sep 2026: promoters 50.44%, public 49.56%, employee trusts 0.00%. Source: NSE.", summary(one))
        assertEquals(1, TermTesting.viz(one, "stream").getAsJsonArray("points").size())
        val fake = TermTesting.Fake(nse = mapOf("corporate-share-holdings-master" to fx("shp_infy")))
        runBlocking { StockShareholdingFeature.run("INFY", fake) }
        assertEquals("nse:corporate-share-holdings-master?index=equities&symbol=INFY", fake.asked.single())
    }

    @Test fun scorecard() {
        val json = StockScorecardFeature.build("INFY", fx("chart_infy"))
        // 248 daily closes, 1,507.60 → 1,145.10; dividends of ₹25 and ₹26 in the window
        assertEquals("INFY over the year to 17 Nov 2026: return −24.0% (score 7/100), annualised volatility 31.9% (62/100), maximum drawdown −41.7% (18/100), " +
            "−3.7% against its 200-day average (41/100), dividend yield 4.5% (89/100). Scores use fixed bands and describe the past year only. Source: Yahoo Finance.", summary(json))
        val axes = TermTesting.viz(json, "radar").getAsJsonArray("axes").map { it.asJsonObject }
        assertEquals(listOf("1-year return" to 7, "Volatility" to 62, "Max drawdown" to 18, "Vs 200-day average" to 41, "Dividend yield" to 89), axes.map { it["label"].asString to it["score"].asInt })
        assertEquals("−24.0%", axes[0]["value"].asString); assertEquals("−30% → 0 · +50% → 100", axes[0]["band"].asString)
        assertTrue(axes.all { it["score"].asInt in 0..100 })
        val pts = TermTesting.viz(json, "history").getAsJsonArray("points")
        assertEquals(248, pts.size()); assertEquals(1507.6, pts[0].asJsonObject["c"].asDouble, 1e-9); assertEquals("2025-11-17", pts[0].asJsonObject["t"].asString)
        assertEquals(51.0, StockScorecardFeature.dividends(fx("chart_infy")), 1e-9); assertEquals(0.0, StockScorecardFeature.dividends("<html>"), 0.0)
        assertTrue(note(json).contains("₹51.00 a share")); assertTrue(note(json).contains("not a rating"))
        assertEquals("down", card(json)["tone"].asString)

        // the documented bands: linear between the ends, clamped beyond them, two of them inverted
        assertEquals(0.0, StockScorecardFeature.RETURN.score(-30.0), 1e-9); assertEquals(50.0, StockScorecardFeature.RETURN.score(10.0), 1e-9); assertEquals(100.0, StockScorecardFeature.RETURN.score(80.0), 1e-9)
        assertEquals(0.0, StockScorecardFeature.VOLATILITY.score(75.0), 1e-9); assertEquals(100.0, StockScorecardFeature.VOLATILITY.score(15.0), 1e-9); assertEquals(60.0, StockScorecardFeature.VOLATILITY.score(33.0), 1e-9)
        assertEquals(0.0, StockScorecardFeature.DRAWDOWN.score(-50.0), 1e-9); assertEquals(100.0, StockScorecardFeature.DRAWDOWN.score(0.0), 1e-9)
        assertEquals(50.0, StockScorecardFeature.TREND.score(0.0), 1e-9); assertEquals(40.0, StockScorecardFeature.DIVIDEND.score(2.0), 1e-9)

        // under 200 sessions there is no 200-day average: that spoke is left out rather than guessed
        val n = 100; val t0 = 1763351100L
        val short = """{"chart":{"result":[{"meta":{"symbol":"NEWCO.NS"},"timestamp":[${(0 until n).joinToString(",") { (t0 + it * 86400L).toString() }}],
            "indicators":{"quote":[{"open":[],"high":[],"low":[],"volume":[],"close":[${(0 until n).joinToString(",") { (100 + it).toString() }}]}]}}]}}"""
        val young = StockScorecardFeature.build("NEWCO", short)
        assertEquals(listOf("1-year return", "Volatility", "Max drawdown", "Dividend yield"), TermTesting.viz(young, "radar").getAsJsonArray("axes").map { it.asJsonObject["label"].asString })
        assertFalse(summary(young).contains("200-day")); assertTrue(note(young).contains("that spoke is left out"))
        assertTrue(summary(young), summary(young).contains("return +99.0% (score 100/100)") && summary(young).contains("maximum drawdown 0.0% (100/100)") && summary(young).contains("dividend yield 0.0% (0/100)"))

        val three = """{"chart":{"result":[{"meta":{"symbol":"X.NS"},"timestamp":[1,2,3],"indicators":{"quote":[{"open":[],"high":[],"low":[],"volume":[],"close":[1,2,3]}]}}]}}"""
        listOf("", "<html>", "{}", """{"chart":{"result":null,"error":{"code":"Not Found"}}}""", three).forEach { assertTrue(it, TermTesting.error(StockScorecardFeature.build("X", it))!!.contains("Not enough daily price history")) }
        val fake = TermTesting.Fake(charts = mapOf("INFY.NS" to fx("chart_infy")))
        assertNull(TermTesting.error(runBlocking { StockScorecardFeature.run("infy", fake) }))
        assertEquals("chart:INFY.NS:1y:1d", fake.asked.single())
    }

    @Test fun peers() {
        val json = StockPeersFeature.build("NIFTY IT", fx("peers_it"), "INFY")
        assertEquals("NIFTY IT as of 16 Nov 2026, 16:00: 4 of 10 stocks up and 6 down, average move −0.22%. Best TECHM +0.70% at ₹1,776.40, worst LTM −1.33% at ₹4,199.70; INFY +0.32% at ₹1,145.10. Source: NSE.", summary(json))
        assertEquals("4 up · 6 down", card(json)["headline"].asString); assertTrue(card(json)["sub"].asString.endsWith("INFY +0.32%, 4 of 10"))
        val bars = TermTesting.viz(json, "percentbars").getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(10, bars.size); assertEquals("TECHM", bars.first()["label"].asString); assertEquals(0.7, bars.first()["value"].asDouble, 1e-9)
        assertEquals("▸ INFY", bars[3]["label"].asString); assertEquals(-1.33, bars.last()["value"].asDouble, 1e-9)
        assertEquals(bars.map { it["value"].asDouble }.sortedDescending(), bars.map { it["value"].asDouble })
        assertEquals(250, vizzes(json, "percentbars").single()["height"].asInt)
        val tiles = TermTesting.viz(json, "treemap").getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(5556761321.16, tiles.first { it["label"].asString == "INFY" }["value"].asDouble, 0.01)
        val rows = TermTesting.blocks(json).first { it["type"].asString == "rows" }.getAsJsonArray("rows").map { it.asJsonObject }
        assertEquals("INFY", rows.first()["title"].asString); assertEquals("Traded ₹556 Cr", rows.first()["sub"].asString); assertEquals("₹1,145.10 · +0.32%", rows.first()["value"].asString)
        assertTrue(rows.size <= 10)
        assertFalse(summary(StockPeersFeature.build("NIFTY IT", fx("peers_it"))).contains("; INFY"))

        // before trading starts some rows carry no traded value: no size-by-value treemap then; unusable rows are skipped
        val early = StockPeersFeature.build("NIFTY BANK", """[{"symbol":"YESBANK","lastPrice":"18.02","pChange":"3.44","quantityTraded":"0","lastUpdatedTime":"16-Nov-2026 16:00:28"},
            {"symbol":"PNB","lastPrice":"144.85","pChange":"1.43","quantityTraded":"34197491.65"},{"symbol":"BANKBARODA","lastPrice":"270.1","pChange":"-1.37","quantityTraded":"3969659.7"},
            {"symbol":"NOPRICE","lastPrice":"0","pChange":"1"},{"symbol":"","lastPrice":"5","pChange":"1"},{"symbol":"NOCHANGE","lastPrice":"5"}]""")
        assertTrue(vizzes(early, "treemap").isEmpty()); assertEquals(3, TermTesting.viz(early, "percentbars").getAsJsonArray("items").size())
        assertTrue(summary(early), summary(early).startsWith("NIFTY BANK as of 16 Nov 2026, 16:00: 2 of 3 stocks up and 1 down, average move +1.17%. Best YESBANK +3.44% at ₹18.02, worst BANKBARODA −1.37% at ₹270.10."))
        // a broad sector with many names shows the strongest and weakest only, and says so
        val many = StockPeersFeature.build("NIFTY ENERGY", (1..40).joinToString(",", "[", "]") { """{"symbol":"S$it","lastPrice":"100","pChange":"${it - 20}","quantityTraded":"1000"}""" })
        assertEquals(16, TermTesting.viz(many, "percentbars").getAsJsonArray("items").size()); assertTrue(vizzes(many, "percentbars").single()["caption"].asString.contains("8 strongest and 8 weakest of 40"))

        listOf("[]", "<html>", "").forEach { assertTrue(TermTesting.error(StockPeersFeature.build("NIFTY XYZ", it))!!.contains("no stocks for NIFTY XYZ")) }
        // a sector name goes straight to the constituents feed; a stock symbol is quoted first to find its sector index
        val bySector = TermTesting.Fake(nse = mapOf("heatmap-symbols" to fx("peers_it")))
        assertNull(TermTesting.error(runBlocking { StockPeersFeature.run("it", bySector) }))
        assertEquals("nse:heatmap-symbols?type=Sectoral%20Indices&indices=NIFTY%20IT", bySector.asked.single())
        val byStock = TermTesting.Fake(nse = mapOf("heatmap-symbols" to fx("peers_it"), "NextApi/apiClient/GetQuoteApi?functionName=getSymbolData" to fx("quote_infy")))
        assertTrue(summary(runBlocking { StockPeersFeature.run("INFY", byStock) }).contains("; INFY +0.32% at ₹1,145.10"))
        assertEquals(listOf("nse:NextApi/apiClient/GetQuoteApi?functionName=getSymbolData&marketType=N&series=EQ&symbol=INFY", "nse:heatmap-symbols?type=Sectoral%20Indices&indices=NIFTY%20IT"), byStock.asked)
        assertTrue(TermTesting.error(runBlocking { StockPeersFeature.run("", TermTesting.Fake()) })!!.contains("Which sector"))
        assertTrue(TermTesting.error(runBlocking { StockPeersFeature.run("ZZZZQQ", TermTesting.Fake()) })!!.contains("not a sector I know"))
    }

    @Test fun history() {
        val json = StockHistoryFeature.build("INFY", fx("history_infy"))
        assertEquals("INFY on NSE, 19 Oct – 16 Nov 2026 (20 sessions): closed at ₹1,145.10, −1.39% over the period; high ₹1,198.80 on 6 Nov, low ₹1,038.05 on 23 Oct; " +
            "total turnover ₹30,815 Cr, average 1,33,76,634 shares a day. Source: NSE.", summary(json))
        assertEquals("−1.39%", card(json)["headline"].asString); assertEquals("₹1,161.20 → ₹1,145.10 · 19 Oct – 16 Nov 2026", card(json)["sub"].asString)      // from the close before the first session
        val pts = TermTesting.viz(json, "candles").getAsJsonArray("points").map { it.asJsonObject }
        assertEquals(20, pts.size); assertEquals("2026-10-19", pts.first()["t"].asString); assertEquals("2026-11-16", pts.last()["t"].asString)      // the feed is newest-first; the chart is oldest-first
        assertEquals(listOf(1150.0, 1159.9, 1138.2, 1145.1, 4852092.0), listOf("o", "h", "l", "c", "v").map { pts.last()[it].asDouble })
        assertTrue(pts.all { it["h"].asDouble >= it["l"].asDouble && it["c"].asDouble in it["l"].asDouble..it["h"].asDouble })
        val st = stats(json)
        assertEquals("₹1,198.80", st["Period high · 6 Nov"]); assertEquals("9 · 11", st["Up days · down days"]); assertEquals("₹1,145.23", st["Last VWAP"]); assertEquals(6, st.size)
        val rows = TermTesting.blocks(json).first { it["type"].asString == "rows" }.getAsJsonArray("rows").map { it.asJsonObject }
        assertEquals(6, rows.size); assertEquals("16 Nov 2026", rows[0]["title"].asString); assertEquals("₹1,145.10 · +0.32%", rows[0]["value"].asString); assertEquals("down", rows[1]["tone"].asString)

        listOf("[]", "<html>", "", """[{"mtimestamp":"16-Nov-2026","chOpeningPrice":1,"chTradeHighPrice":1,"chTradeLowPrice":1,"chClosingPrice":1}]""")
            .forEach { assertTrue(it, TermTesting.error(StockHistoryFeature.build("ZZZZQQ", it))!!.contains("no daily history for ZZZZQQ")) }

        assertEquals(Triple("INFY", LocalDate.of(2026, 10, 17), today), StockHistoryFeature.window("INFY", today))
        assertEquals(Triple("INFY", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), StockHistoryFeature.window(" INFY  2026-09 ", today))
        assertEquals(Triple("INFY", LocalDate.of(2026, 11, 1), today), StockHistoryFeature.window("INFY 2026-11", today))                  // never past today
        assertEquals(Triple("INFY", LocalDate.of(2026, 10, 17), today), StockHistoryFeature.window("INFY 2027-01", today))                 // a future month falls back to the last month
        assertEquals(Triple("INFY", LocalDate.of(2026, 10, 17), today), StockHistoryFeature.window("INFY 2026-13", today))
        val fake = TermTesting.Fake(nse = mapOf("NextApi/apiClient/GetQuoteApi?functionName=getHistoricalTradeData" to fx("history_infy")), today = today)
        assertNull(TermTesting.error(runBlocking { StockHistoryFeature.run("INFY", fake) }))
        assertEquals("nse:NextApi/apiClient/GetQuoteApi?functionName=getHistoricalTradeData&symbol=INFY&series=EQ&fromDate=17-10-2026&toDate=17-11-2026", fake.asked.single())
        val month = TermTesting.Fake(today = today); runBlocking { StockHistoryFeature.run("INFY 2026-09", month) }
        assertTrue(month.asked.single(), month.asked.single().endsWith("symbol=INFY&series=EQ&fromDate=01-09-2026&toDate=30-09-2026"))
    }

    @Test fun mindmap() {
        fun build(day: LocalDate = today, quote: String = fx("quote_infy"), shp: String = fx("shp_infy"), res: String = fx("rescomp_infy"), corp: String = fx("topcorp_infy"), index: String? = "NIFTY IT", peers: String = fx("peers_it")) =
            StockMindmapFeature.build("INFY", quote, shp, res, corp, index, peers, day)
        val json = build()
        assertEquals("INFY (Infosys Limited): last ₹1,145.10 (+0.32% on the day), 52-week range ₹980.40–₹1,728.00; Q2 FY27 total income ₹42,421 Cr and net profit ₹7,893 Cr; " +
            "promoters 13.90%, public 85.88% as of 30 Sep 2026; last event 3 Nov 2026 (Interim Dividend - Rs 26 Per Share). Source: NSE.", summary(json))
        val tree = TermTesting.viz(json, "mindmap")
        assertEquals("INFY", tree["name"].asString)
        val branches = tree.getAsJsonArray("children").map { it.asJsonObject }
        assertEquals(listOf("Price", "Results", "Ownership", "Events", "Sector peers"), branches.map { it["name"].asString })
        fun leaves(branch: String, t: com.google.gson.JsonObject = tree) = t.getAsJsonArray("children").map { it.asJsonObject }.first { it["name"].asString == branch }.getAsJsonArray("children").map { it.asJsonObject }.associate { it["name"].asString to it["value"].asString }
        assertEquals(mapOf("Last" to "₹1,145.10", "Day change" to "+0.32%", "52-week range" to "₹980.40 – ₹1,728.00", "P/E" to "15.05 · sector 14.62"), leaves("Price"))
        // the newer of the two results feeds wins: top-corp-info has the quarter to 30 Sep 2026, results-comparison stops at 31 Dec 2024 (₹ lakh → ₹ crore)
        assertEquals(mapOf("Quarter" to "Q2 FY27 · to 30 Sep 2026", "Total income" to "₹42,421 Cr", "Net profit" to "₹7,893 Cr", "Diluted EPS" to "₹19.45"), leaves("Results"))
        assertEquals(mapOf("Promoters" to "13.90%", "Public" to "85.88%", "Employee trusts" to "0.21%", "Reported for" to "30 Sep 2026"), leaves("Ownership"))
        assertEquals(mapOf("3 Nov 2026" to "Interim Dividend - Rs 26 Per Share", "23 Oct 2026" to "Board meeting", "23 Jul 2026" to "Board meeting"), leaves("Events"))      // nothing ahead of 17 Nov: the last three
        assertEquals(listOf("TCS", "COFORGE", "HCLTECH", "TECHM"), leaves("Sector peers").keys.toList())          // largest traded value first, the company itself left out
        assertEquals("₹2,405.00 · −0.10%", leaves("Sector peers")["TCS"])
        assertEquals("₹1,145.10", card(json)["headline"].asString)
        assertTrue(TermTesting.card(json).getAsJsonArray("pills").map { it.asString }.contains("/peers IT"))

        // seen from 1 Nov 2026 the 3 Nov ex-date is still ahead, and is flagged as next
        val before = build(day = LocalDate.of(2026, 11, 1))
        assertEquals(mapOf("3 Nov 2026" to "Next: Interim Dividend - Rs 26 Per Share", "23 Oct 2026" to "Board meeting", "23 Jul 2026" to "Board meeting"), leaves("Events", TermTesting.viz(before, "mindmap")))
        assertTrue(summary(before).contains("next event 3 Nov 2026 (Interim Dividend - Rs 26 Per Share)"))
        // without top-corp-info the older results-comparison quarter is used and named for what it is
        val old = build(corp = "")
        assertEquals(mapOf("Quarter" to "Q3 FY25 · to 31 Dec 2024", "Sales" to "₹34,915 Cr", "Net profit" to "₹6,358 Cr", "Diluted EPS" to "₹15.29"), leaves("Results", TermTesting.viz(old, "mindmap")))
        assertEquals(listOf("Price", "Results", "Ownership", "Sector peers"), TermTesting.viz(old, "mindmap").getAsJsonArray("children").map { it.asJsonObject["name"].asString })
        assertTrue(note(old).contains("Left out because NSE returned nothing for them: Events."))
        // only the quote answered: one branch, and the card says what is missing
        val lone = build(shp = "[]", res = "<html>", corp = """{"corporate_actions":{"data":[]},"financial_results":{"data":[]},"borad_meeting":{"data":[]}}""", index = null, peers = "")
        assertEquals(listOf("Price"), TermTesting.viz(lone, "mindmap").getAsJsonArray("children").map { it.asJsonObject["name"].asString })
        assertTrue(note(lone).contains("Results, Ownership, Events, Sector peers"))
        assertEquals("INFY (Infosys Limited): last ₹1,145.10 (+0.32% on the day), 52-week range ₹980.40–₹1,728.00. Source: NSE.", summary(lone))
        // no quote but filings exist: still a map, with no price claimed
        val noQuote = build(quote = """{"error":"Unexpected end of JSON input"}""")
        assertEquals("INFY", card(noQuote)["headline"].asString); assertTrue(summary(noQuote).startsWith("INFY: Q2 FY27 total income ₹42,421 Cr"))
        assertTrue(TermTesting.error(build(quote = "", shp = "", res = "", corp = "", index = null, peers = ""))!!.contains("NSE returned nothing for INFY"))

        val q = StockMindmapFeature.quarter(fx("rescomp_infy"), fx("topcorp_infy"))!!
        assertEquals(LocalDate.of(2026, 9, 30), q.end); assertEquals(4242100.0, q.income, 1e-9); assertEquals(789300.0, q.profit, 1e-9); assertEquals("standalone", q.basis)
        assertNull(StockMindmapFeature.quarter("", "")); assertTrue(StockMindmapFeature.events("<html>").isEmpty())
        assertEquals(6, StockMindmapFeature.events(fx("topcorp_infy")).size)                         // 4 corporate actions + 2 distinct board-meeting dates

        val fake = TermTesting.Fake(today = today, nse = mapOf("NextApi/apiClient/GetQuoteApi?functionName=getSymbolData" to fx("quote_infy"), "corporate-share-holdings-master" to fx("shp_infy"),
            "results-comparision" to fx("rescomp_infy"), "top-corp-info" to fx("topcorp_infy"), "heatmap-symbols" to fx("peers_it")))
        assertEquals(summary(json), summary(runBlocking { StockMindmapFeature.run("INFY", fake) }))
        assertEquals(listOf("nse:NextApi/apiClient/GetQuoteApi?functionName=getSymbolData&marketType=N&series=EQ&symbol=INFY", "nse:corporate-share-holdings-master?index=equities&symbol=INFY",
            "nse:results-comparision?symbol=INFY", "nse:top-corp-info?symbol=INFY&market=equities", "nse:heatmap-symbols?type=Sectoral%20Indices&indices=NIFTY%20IT"), fake.asked)
        assertTrue(TermTesting.error(runBlocking { StockMindmapFeature.run("INFY", TermTesting.Fake()) })!!.contains("NSE returned nothing"))
    }

    @Test fun registeredRoutedAndDrawn() {
        val mine = listOf(StockQuoteFeature, StockShareholdingFeature, StockMindmapFeature, StockScorecardFeature, StockPeersFeature, StockHistoryFeature)
        assertEquals(listOf("quote", "shareholding", "mindmap", "scorecard", "peers", "history"), mine.map { it.id })
        mine.forEach { assertEquals(it, Terminal.find(it.id)) }
        assertEquals(StockShareholdingFeature to "infosys", Terminal.match("shareholding of infosys"))
        assertEquals(StockShareholdingFeature to "INFY", Terminal.match("promoter holding @INFY"))
        assertEquals(StockMindmapFeature to "tata motors", Terminal.match("mind map of tata motors"))
        assertEquals(StockScorecardFeature to "INFY", Terminal.match("scorecard for INFY"))
        assertEquals(StockQuoteFeature to "reliance", Terminal.match("full quote for reliance"))
        assertEquals(StockPeersFeature to "INFY", Terminal.match("peers of INFY"))
        assertEquals(StockHistoryFeature to "INFY", Terminal.match("daily history of INFY"))
        listOf("what is a good stock", "quote me happy", "history of india", "show me the heatmap").forEach { t -> assertFalse(t, mine.contains(Terminal.match(t)?.first)) }
        // every chart type a card of this pack asks for has a renderer: a plug-in file that registers it, or a built-in of viz.html
        val viz = File("src/main/assets/viz"); val html = File(viz, "viz.html").readText()
        listOf("gauge", "radar", "stream", "mindmap").forEach { t ->
            val js = File(viz, "r_$t.js").readText()
            assertTrue("r_$t.js registers R.$t", js.contains("R.$t = function (d, h)")); assertTrue(html.contains("\"r_$t.js\""))
            assertTrue("r_$t.js answers a tap", js.contains("showTip(")); assertTrue("r_$t.js animates in", js.contains(".transition()")); assertTrue("r_$t.js names its axes", js.contains("\"axl\"") || js.contains("axes(s"))
        }
        listOf("percentbars", "candles", "history").forEach { assertTrue(it, Regex("""\n  $it\(d, h\) \{""").containsMatchIn(html)) }
        assertNotNull(File(viz, "r_treemap.js").takeIf { it.exists() })
    }
}
