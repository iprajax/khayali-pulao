package com.khayali.pulao.term

import com.google.gson.JsonObject
import com.khayali.pulao.ai.ChatFlow
import com.khayali.pulao.ai.term.PulseActiveFeature
import com.khayali.pulao.ai.term.PulseBandsFeature
import com.khayali.pulao.ai.term.PulseBreadthFeature
import com.khayali.pulao.ai.term.PulseHighsFeature
import com.khayali.pulao.ai.term.PulseIndicesFeature
import com.khayali.pulao.ai.term.PulseMoversFeature
import com.khayali.pulao.ai.term.PulseSectorsFeature
import com.khayali.pulao.ai.term.PulseStatusFeature
import com.khayali.pulao.ai.term.PulseVolumeFeature
import com.khayali.pulao.ai.term.Terminal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Market-pulse features against trimmed copies of real NSE replies (16–17 Nov 2026). */
class PulseTest {
    private fun fx(name: String) = TermTesting.fixture("pulse_$name.json")
    private fun summary(json: String) = TermTesting.card(json)["summary"].asString
    private fun card(json: String) = TermTesting.card(json).getAsJsonObject("card")
    private fun items(json: String, type: String) = TermTesting.viz(json, type).getAsJsonArray("items").map { it.asJsonObject }
    private fun vizzes(json: String, type: String) = TermTesting.blocks(json).filter { it["type"].asString == "viz" && it["viz"].asString == type }
    private fun rows(json: String) = TermTesting.blocks(json).filter { it["type"].asString == "rows" }.flatMap { b -> b.getAsJsonArray("rows").map { it.asJsonObject } }
    private fun stat(json: String, label: String) = TermTesting.blocks(json).first { it["type"].asString == "stats" }.getAsJsonArray("items").map { it.asJsonObject }.first { it["label"].asString == label }["value"].asString
    private fun JsonObject.bar(label: String) = getAsJsonArray("items").map { it.asJsonObject }.first { it["label"].asString == label }

    /** Every card in this pack: kicker, headline with a sub line, a chart with a caption, a source, 2–4 pills, and no advice. */
    private fun wellFormed(json: String, charts: Int = 1) {
        assertNull(json, TermTesting.error(json))
        val c = card(json); val root = TermTesting.card(json)
        assertTrue(c["kicker"].asString.isNotBlank()); assertTrue(c["headline"].asString.isNotBlank()); assertTrue(c["sub"].asString.isNotBlank())
        val viz = TermTesting.blocks(json).filter { it["type"].asString == "viz" }
        assertEquals(charts, viz.size)
        viz.forEach { v -> assertTrue(v["caption"].asString.length > 20); assertTrue(v["height"].asInt in 120..460)
            if (v["viz"].asString == "divbars") { val d = v.getAsJsonObject("data"); assertTrue(d["xLabel"].asString.isNotBlank() && d["yLabel"].asString.isNotBlank() && d.getAsJsonArray("legend").size() in 2..3) }
            if (v["viz"].asString == "donut") { val d = v.getAsJsonObject("data"); assertTrue(d["nameLabel"].asString.isNotBlank() && d["valueLabel"].asString.isNotBlank() && d["centre"].asString.isNotBlank()) } }
        TermTesting.blocks(json).filter { it["type"].asString == "rows" }.forEach { assertTrue(it.getAsJsonArray("rows").size() <= 10) }
        assertEquals(listOf("NSE"), root.getAsJsonArray("sources").map { it.asString })
        val pills = root.getAsJsonArray("pills").map { it.asString }
        assertTrue(pills.toString(), pills.size in 2..4 && pills.all { it.startsWith("/") } && pills.toSet().size == pills.size)
        val s = root["summary"].asString
        assertTrue(s, s.endsWith("Source: NSE."))
        assertFalse(json, Regex("""(?i)\b(should (buy|sell)|buy now|sell now|recommend|target price|strong buy)\b""").containsMatchIn(json))
    }

    @Test fun indices() {
        assertEquals("broad", PulseIndicesFeature.groupOf("")); assertEquals("sectoral", PulseIndicesFeature.groupOf(" Sector ")); assertEquals("thematic", PulseIndicesFeature.groupOf("themes"))
        assertEquals("strategy", PulseIndicesFeature.groupOf("strategy")); assertEquals("bond", PulseIndicesFeature.groupOf("debt"))
        val json = PulseIndicesFeature.build("", fx("allindices")); wellFormed(json)
        // 6 derivative-eligible + 6 broad indices in the fixture; India VIX is kept out of the ranking
        val bars = items(json, "divbars")
        assertEquals(12, bars.size); assertTrue(bars.none { it["label"].asString.contains("VIX") })
        assertEquals("BANK", bars.first()["label"].asString); assertEquals(0.89, bars.first()["value"].asDouble, 1e-9)
        assertEquals("SMLCAP 100", bars.last()["label"].asString); assertEquals(-1.1, bars.last()["value"].asDouble, 1e-9)
        assertEquals(bars.map { it["value"].asDouble }.sortedDescending(), bars.map { it["value"].asDouble })
        assertEquals("NIFTY 50", bars.first { it["value"].asDouble == 0.58 }["label"].asString)
        assertEquals("7 up · 5 down", card(json)["headline"].asString)
        assertEquals("NSE broad market indices as of 16-Nov-2026 15:30: 7 up and 5 down of 12; NIFTY 50 25,291.10 (+0.58%), P/E 20.6. Best NIFTY BANK +0.89%, worst NIFTY SMALLCAP 100 −1.10%. Source: NSE.", summary(json))
        assertEquals("15.70 (+1.32%)", stat(json, "India VIX")); assertEquals("20.6", stat(json, "NIFTY 50 P/E")); assertEquals("6 up · 3 down", stat(json, "Sectoral"))
        // NIFTY 50: (25291.1 − 22179.9) / (26373.2 − 22179.9) = 74% of its 52-week range
        val r = rows(json); assertEquals(10, r.size)
        assertEquals("P/E 20.6 · 74% of 52-week range", r.first { it["title"].asString == "NIFTY 50" }["sub"].asString)
        assertEquals("up", r.first()["tone"].asString); assertEquals("down", r.last()["tone"].asString)

        val sect = PulseIndicesFeature.build("sectoral", fx("allindices")); wellFormed(sect)
        assertEquals(9, items(sect, "divbars").size); assertEquals("FMCG", items(sect, "divbars").first()["label"].asString)
        // NSE publishes no 52-week range for NIFTY FINANCIAL SERVICES 25/50 (zeros) → only the P/E is shown
        assertEquals("P/E 16.1", rows(sect).first { it["title"].asString == "NIFTY FINANCIAL SERVICES 25/50" }["sub"].asString)
        val bond = PulseIndicesFeature.build("bond", fx("allindices")); wellFormed(bond)
        assertEquals("0 up · 1 down", card(bond)["headline"].asString); assertEquals("3,010.91", rows(bond).single()["sub"].asString)   // no P/E for a bond index

        assertTrue(TermTesting.error(PulseIndicesFeature.build("", ""))!!.contains("unavailable"))
        assertTrue(TermTesting.error(PulseIndicesFeature.build("", "<html>Access Denied</html>"))!!.contains("unavailable"))
        assertTrue(TermTesting.error(PulseIndicesFeature.build("", """{"data":[]}"""))!!.contains("unavailable"))
        assertTrue(TermTesting.error(PulseIndicesFeature.build("thematic", """{"data":[{"key":"BROAD MARKET INDICES","index":"NIFTY 100","last":1,"percentChange":0}]}"""))!!.contains("no thematic"))
        val fake = TermTesting.Fake(nse = mapOf("allIndices" to fx("allindices")))
        wellFormed(runBlocking { PulseIndicesFeature.run("thematic", fake) }); assertEquals(listOf("nse:allIndices"), fake.asked)
    }

    @Test fun sectors() {
        assertEquals("today", PulseSectorsFeature.period("")); assertEquals("30d", PulseSectorsFeature.period("30d")); assertEquals("30d", PulseSectorsFeature.period("month")); assertEquals("1y", PulseSectorsFeature.period("1 year"))
        val json = PulseSectorsFeature.build("", fx("allindices")); wellFormed(json)
        val d = TermTesting.viz(json, "divbars")
        assertEquals("%", d["unit"].asString); assertEquals("Today's change (%)", d["xLabel"].asString)
        assertEquals(listOf("FMCG", "PVT BANK", "FINSRV25 50", "PSU BANK", "MEDIA", "IT", "AUTO", "METAL", "PHARMA"), items(json, "divbars").map { it["label"].asString })
        assertEquals(1.87, d.bar("FMCG")["value"].asDouble, 1e-9); assertEquals(-1.53, d.bar("PHARMA")["value"].asDouble, 1e-9)
        assertEquals("today +1.87% · 30 days +6.93% · 1 year −14.31%", d.bar("FMCG")["note"].asString)
        assertEquals("6 up · 3 down", card(json)["headline"].asString)
        assertEquals("NSE sector indices as of 16-Nov-2026 15:30: 6 up and 3 down of 9 today. Best NIFTY FMCG +1.87% (today +1.87% · 30 days +6.93% · 1 year −14.31%); " +
            "worst NIFTY PHARMA −1.53% (today −1.53% · 30 days +8.99% · 1 year +22.87%). Source: NSE.", summary(json))
        assertEquals("30 days +8.99% · 1 year +22.87%", rows(json).last()["sub"].asString); assertEquals(9, rows(json).size)
        assertTrue(TermTesting.card(json).getAsJsonArray("pills").map { it.asString }.contains("/heatmap NIFTY FMCG"))

        // ranked by the 1-year change instead: PHARMA +22.87% leads, FMCG −14.31% trails
        val year = PulseSectorsFeature.build("1y", fx("allindices")); wellFormed(year)
        assertEquals("PHARMA", items(year, "divbars").first()["label"].asString); assertEquals(22.87, items(year, "divbars").first()["value"].asDouble, 1e-9)
        assertEquals("FMCG", items(year, "divbars").last()["label"].asString); assertEquals("1-year change (%)", TermTesting.viz(year, "divbars")["xLabel"].asString)
        assertTrue(summary(year), summary(year).contains("over 1 year. Best NIFTY PHARMA +22.87%"))
        // a 1-year change of 0 means NSE has none (young index): shown as n/a and left out of the 1-year ranking
        val young = """{"timestamp":"16-Nov-2026 15:30","data":[{"key":"SECTORAL INDICES","index":"NIFTY CEMENT","indexSymbol":"NIFTY CEMENT","last":14418.35,"percentChange":1.04,"perChange30d":6.05,"perChange365d":0}]}"""
        assertTrue(summary(PulseSectorsFeature.build("", young)).contains("30 days +6.05% · 1 year n/a"))
        assertTrue(TermTesting.error(PulseSectorsFeature.build("1y", young))!!.contains("no 1-year change"))
        assertTrue(TermTesting.error(PulseSectorsFeature.build("", "{}"))!!.contains("unavailable"))
        assertTrue(TermTesting.error(PulseSectorsFeature.build("", "not json"))!!.contains("unavailable"))
    }

    @Test fun movers() {
        assertEquals("NIFTY", PulseMoversFeature.universe("").first); assertEquals("BANKNIFTY", PulseMoversFeature.universe("bank nifty").first); assertEquals("NIFTYNEXT50", PulseMoversFeature.universe("next50").first)
        assertEquals("FOSec", PulseMoversFeature.universe("F&O").first); assertEquals("FOSec", PulseMoversFeature.universe("fno").first); assertEquals("allSec", PulseMoversFeature.universe("all").first)
        val json = PulseMoversFeature.build("", fx("gainers"), fx("losers")); wellFormed(json)
        val bars = items(json, "divbars")
        assertEquals(12, bars.size)
        assertEquals("MAXHEALTH", bars.first()["label"].asString); assertEquals(6.63, bars.first()["value"].asDouble, 1e-9)
        assertEquals("TMPV", bars.last()["label"].asString); assertEquals(-8.7, bars.last()["value"].asDouble, 1e-9)
        assertEquals(bars.map { it["value"].asDouble }.sortedDescending(), bars.map { it["value"].asDouble })
        assertEquals("MAXHEALTH +6.63% · TMPV −8.70%", card(json)["headline"].asString)
        assertEquals("NIFTY 50 movers as of 16-Nov-2026 16:00: top gainer MAXHEALTH +6.63% at ₹1,009.90; top loser TMPV −8.70% at ₹262.40. Source: NSE.", summary(json))
        val r = rows(json); assertEquals(8, r.size)
        assertEquals("₹1,009.90 · traded ₹821.11 Cr", r.first()["sub"].asString); assertEquals("+6.63%", r.first()["value"].asString); assertEquals("down", r.last()["tone"].asString)

        // NIFTY BANK that day: three gainers in the fixture, a single loser
        val bank = PulseMoversFeature.build("bank", fx("gainers"), fx("losers")); wellFormed(bank)
        assertEquals(4, items(bank, "divbars").size); assertTrue(summary(bank), summary(bank).startsWith("NIFTY BANK movers") && summary(bank).contains("top gainer YESBANK +3.44% at ₹18.02; top loser BANKBARODA −1.33% at ₹270.20"))
        // one feed missing → the other side still makes a card
        val only = PulseMoversFeature.build("", fx("gainers"), ""); wellFormed(only)
        assertEquals(6, items(only, "divbars").size); assertFalse(summary(only).contains("loser")); assertTrue(card(only)["sub"].asString.contains("no losers listed"))
        // before the open NSE sends the keys with empty lists
        val closed = """{"legends":[["NIFTY","NIFTY 50"]],"NIFTY":{"data":[],"timestamp":"17-Nov-2026 09:00:00"}}"""
        assertTrue(TermTesting.error(PulseMoversFeature.build("", closed, closed))!!.contains("no gainers or losers for NIFTY 50"))
        assertTrue(TermTesting.error(PulseMoversFeature.build("fno", "", "<html>"))!!.contains("F&O stocks"))
        val fake = TermTesting.Fake(nse = mapOf("live-analysis-variations?index=gainers" to fx("gainers"), "live-analysis-variations?index=loosers" to fx("losers")))
        wellFormed(runBlocking { PulseMoversFeature.run("all", fake) })
        assertEquals(listOf("nse:live-analysis-variations?index=gainers", "nse:live-analysis-variations?index=loosers"), fake.asked)
    }

    @Test fun breadth() {
        val json = PulseBreadthFeature.build(fx("advance")); wellFormed(json)
        val d = TermTesting.viz(json, "donut")
        assertEquals(listOf("Advances" to 1168.0, "Declines" to 2504.0, "Unchanged" to 102.0), items(json, "donut").map { it["label"].asString to it["value"].asDouble })
        assertEquals(listOf("green", "red", "muted"), items(json, "donut").map { it["color"].asString })
        assertEquals("0.47", d["centre"].asString)                                   // 1168 / 2504
        assertEquals("1,168 up · 2,504 down", card(json)["headline"].asString); assertEquals("down", card(json)["tone"].asString)
        assertEquals("NSE breadth as of 16-Nov-2026 16:00: 1,168 advances, 2,504 declines and 102 unchanged of 3,774 stocks — 0.47 advances per decline. Source: NSE.", summary(json))
        assertEquals("30.9%", stat(json, "Advancing")); assertEquals("66.3%", stat(json, "Declining")); assertEquals("3,774", stat(json, "Stocks counted"))
        val r = rows(json); assertEquals(5, r.size)
        assertEquals("RELIANCE", r.first()["title"].asString); assertEquals("₹1,344.80 · market value ₹18,19,856 Cr", r.first()["sub"].asString); assertEquals("+0.38%", r.first()["value"].asString)

        // no declines → no ratio, and the card still stands
        val allUp = PulseBreadthFeature.build("""{"timestamp":"x","advance":{"count":{"Advances":10,"Unchange":0,"Declines":0,"Total":10},"data":[]}}""")
        assertNull(TermTesting.error(allUp)); assertEquals("10", TermTesting.viz(allUp, "donut")["centre"].asString); assertFalse(summary(allUp).contains("per decline"))
        assertTrue(TermTesting.error(PulseBreadthFeature.build("""{"timestamp":"17-Nov-2026 09:00:00","advance":{"count":{"Advances":0,"Unchange":0,"Declines":0,"Total":0},"data":[]}}"""))!!.contains("may not have traded"))
        assertTrue(TermTesting.error(PulseBreadthFeature.build(""))!!.contains("unavailable"))
        assertTrue(TermTesting.error(PulseBreadthFeature.build("""{"advance":null}"""))!!.contains("unavailable"))
    }

    @Test fun active() {
        val json = PulseActiveFeature.build(fx("active_volume"), fx("active_value")); wellFormed(json, charts = 2)
        val (value, volume) = vizzes(json, "divbars").map { it.getAsJsonObject("data") }
        assertEquals("₹ Cr", value["unit"].asString); assertEquals("Traded value (₹ crore)", value["xLabel"].asString); assertEquals("Shares traded (lakh)", volume["xLabel"].asString)
        assertEquals(8, value.getAsJsonArray("items").size()); assertEquals(8, volume.getAsJsonArray("items").size())
        assertEquals(497.5493, value.bar("LIQUIDBEES")["value"].asDouble, 1e-6)         // ₹4,975,493,000 → ₹ crore
        assertEquals("flat", value.bar("LIQUIDBEES")["tone"].asString); assertEquals("up", value.bar("HDFCBANK")["tone"].asString)
        assertEquals(252.20695, volume.bar("PVTBANIETF")["value"].asDouble, 1e-6)       // 25,220,695 shares → lakh
        assertEquals("down", volume.bar("PHARMABEES")["tone"].asString); assertEquals("₹28.85 · −1.60%", volume.bar("PHARMABEES")["note"].asString)
        assertEquals("LIQUIDBEES · ₹498 Cr", card(json)["headline"].asString)
        assertEquals("Most active on NSE as of 16-Nov-2026 16:00: by value LIQUIDBEES, ₹498 Cr traded (0.00%); by volume PVTBANIETF, 2.52 Cr shares (+1.82%). Source: NSE.", summary(json))
        assertEquals(6, rows(json).size); assertEquals("₹498 Cr", rows(json).first()["value"].asString); assertEquals("2.52 Cr", rows(json)[3]["value"].asString)

        val one = PulseActiveFeature.build(fx("active_volume"), ""); wellFormed(one)
        assertEquals("PVTBANIETF · 2.52 Cr shares", card(one)["headline"].asString); assertFalse(summary(one).contains("by value"))
        assertTrue(TermTesting.error(PulseActiveFeature.build("""{"data":[],"timestamp":"17-Nov-2026 09:00:00"}""", """{"data":[]}"""))!!.contains("no active securities"))
        assertTrue(TermTesting.error(PulseActiveFeature.build("", "oops"))!!.contains("no active securities"))
        val fake = TermTesting.Fake(nse = mapOf("live-analysis-most-active-securities?index=volume" to fx("active_volume"), "live-analysis-most-active-securities?index=value" to fx("active_value")))
        wellFormed(runBlocking { PulseActiveFeature.run("", fake) }, charts = 2)
        assertEquals(listOf("nse:live-analysis-most-active-securities?index=volume", "nse:live-analysis-most-active-securities?index=value"), fake.asked)
    }

    @Test fun status() {
        assertEquals("Closed", PulseStatusFeature.state("Open", "Normal Market has Closed")); assertEquals("Closed", PulseStatusFeature.state("Close", "Market is Closed"))
        assertEquals("Open", PulseStatusFeature.state("Open", "Normal Market is Open")); assertEquals("Pre-open", PulseStatusFeature.state("Open", "Pre-Open Session Closed – Normal Market will begin shortly"))
        assertEquals("Closed", PulseStatusFeature.state("Closed", "")); assertEquals("Open", PulseStatusFeature.state("Open", "")); assertEquals("Unknown", PulseStatusFeature.state("", ""))
        // after the close NSE's short status still read "Open" — the message decides
        val json = PulseStatusFeature.build(fx("status"), fx("turnover")); wellFormed(json)
        assertEquals("Closed", card(json)["headline"].asString); assertEquals("NSE: \"Normal Market has Closed\" · NIFTY 50 25,291.10 (+0.58%)", card(json)["sub"].asString)
        val d = TermTesting.viz(json, "donut")
        assertEquals("₹ Cr", d["unit"].asString); assertEquals("₹3.33 L Cr", d["centre"].asString)
        val slices = items(json, "donut")
        assertEquals(listOf("Stock Futures", "Equities", "Index Options", "Index Futures", "Stock Options", "Other"), slices.map { it["label"].asString })
        assertEquals(110779.624, slices[1]["value"].asDouble, 0.01)                    // ₹1,107,796,241,968.77 → ₹ crore
        assertEquals(332501.86, slices.sumOf { it["value"].asDouble }, 0.01)           // slices add up to NSE's total
        assertEquals("NSE equity market: Closed (NSE says \"Normal Market has Closed\"); NIFTY 50 25,291.10 (+0.58%). Previous session turnover ₹3,32,502 Cr across segments, equities ₹1,10,780 Cr. Source: NSE.", summary(json))
        assertEquals("₹504.63 L Cr", stat(json, "Market value of NSE-listed companies")); assertEquals("25,291.50 (−0.05%)", stat(json, "GIFT Nifty (indicative)")); assertEquals("₹93.95", stat(json, "USDINR futures"))
        val r = rows(json); assertEquals(5, r.size)
        assertEquals("Equities (capital market)", r.first()["title"].asString); assertEquals("Closed", r.first()["value"].asString); assertEquals("Normal Market has Closed · 16-Nov-2026 15:30", r.first()["sub"].asString)
        assertEquals("Closed", TermTesting.card(json)["state"].asString)

        // 09:12 on a trading day: pre-open for equities, other segments open
        val pre = PulseStatusFeature.build(fx("status_preopen"), fx("turnover")); wellFormed(pre)
        assertEquals("Pre-open", card(pre)["headline"].asString); assertEquals("warn", rows(pre).first()["tone"].asString)
        assertEquals("Open", rows(pre)[1]["value"].asString); assertEquals("up", rows(pre)[1]["tone"].asString)
        assertTrue(summary(pre), summary(pre).startsWith("NSE equity market: Pre-open (NSE says \"Pre-Open Session Closed – Normal Market will begin shortly\"); NIFTY 50 25,277.30 (−0.05%)."))
        // turnover feed down → status alone, no chart, nothing invented
        val bare = PulseStatusFeature.build(fx("status"), "")
        assertNull(TermTesting.error(bare)); assertTrue(vizzes(bare, "donut").isEmpty()); assertFalse(summary(bare).contains("turnover")); assertTrue(summary(bare).endsWith("Source: NSE."))
        assertTrue(TermTesting.error(PulseStatusFeature.build("", fx("turnover")))!!.contains("unavailable"))
        assertTrue(TermTesting.error(PulseStatusFeature.build("""{"marketState":[]}""", ""))!!.contains("unavailable"))
        val fake = TermTesting.Fake(nse = mapOf("marketStatus" to fx("status"), "market-turnover" to fx("turnover")))
        wellFormed(runBlocking { PulseStatusFeature.run("", fake) }); assertEquals(listOf("nse:marketStatus", "nse:market-turnover"), fake.asked)
    }

    @Test fun highs() {
        // the fixture keeps NSE's duplicate rows: APLAPOLLO, KALYANKJIL (highs) and ALPEXSOLAR (lows) appear twice → 8 and 7 distinct stocks
        val json = PulseHighsFeature.build(fx("52high"), fx("52low")); wellFormed(json, charts = 2)
        assertEquals("8 highs · 7 lows", card(json)["headline"].asString)
        assertEquals(listOf("Highs" to 8.0, "Lows" to 7.0), items(json, "donut").map { it["label"].asString to it["value"].asDouble }); assertEquals("8 : 7", TermTesting.viz(json, "donut")["centre"].asString)
        val bars = items(json, "divbars"); assertEquals(10, bars.size)
        assertEquals("AONESTEELS", bars.first()["label"].asString); assertEquals(5.09, bars.first()["value"].asDouble, 0.005); assertEquals("up", bars.first()["tone"].asString); assertEquals("new high ₹481.05", bars.first()["note"].asString)
        assertEquals("ALPEXSOLAR", bars.last()["label"].asString); assertEquals(-14.62, bars.last()["value"].asDouble, 0.005); assertEquals("down", bars.last()["tone"].asString)
        assertEquals("NSE as of 16-Nov-2026 16:00: 8 stocks set a new 52-week high and 7 stocks a new 52-week low in the session. Source: NSE.", summary(json))
        val r = rows(json); assertEquals(10, r.size)
        assertEquals("high ₹481.05 · last ₹473.90", r.first()["sub"].asString); assertEquals("+5.09%", r.first()["value"].asString); assertEquals("low ₹620.00 · last ₹640.00", r[5]["sub"].asString)
        assertEquals(8, TermTesting.card(json)["highs"].asInt)
        // one KALYANKJIL row carries a stale previous close (₹87 → "+719.43%"); the real +1.60% row is the one used. Same for APLAPOLLO (−22.18% vs −0.06%).
        assertEquals(1.6, TermTesting.viz(json, "divbars").bar("KALYANKJIL")["value"].asDouble, 0.005); assertTrue(bars.none { it["label"].asString == "APLAPOLLO" })
        assertTrue(bars.all { kotlin.math.abs(it["value"].asDouble) < 20 })

        // a session with highs but no lows
        val none = PulseHighsFeature.build(fx("52high"), """{"data":[],"low":0,"timestamp":"16-Nov-2026 16:00:00"}"""); wellFormed(none, charts = 2)
        assertEquals("8 highs · 0 lows", card(none)["headline"].asString); assertEquals(5, items(none, "divbars").size); assertTrue(summary(none).contains("8 stocks set a new 52-week high and 0 stocks a new 52-week low"))
        assertTrue(TermTesting.error(PulseHighsFeature.build("""{"high":0,"data":[],"timestamp":"17-Nov-2026 09:00:00"}""", """{"data":[],"low":0}"""))!!.contains("no new 52-week highs or lows"))
        assertTrue(TermTesting.error(PulseHighsFeature.build("", "<html>"))!!.contains("unavailable"))
    }

    @Test fun bands() {
        val json = PulseBandsFeature.build(fx("bands")); wellFormed(json)
        // counts come from NSE's own tally (the fixture lists are trimmed to four rows a side)
        assertEquals("17 upper · 6 lower", card(json)["headline"].asString); assertTrue(card(json)["sub"].asString.startsWith("3 more hit both bands · 26 stocks"))
        assertEquals(listOf("Upper band" to 17.0, "Lower band" to 6.0, "Both bands" to 3.0), items(json, "donut").map { it["label"].asString to it["value"].asDouble }); assertEquals("26", TermTesting.viz(json, "donut")["centre"].asString)
        assertEquals("NSE price-band hitters as of 16-Nov-2026 16:00: 17 hit the upper band, 6 the lower band and 3 both (26 in all). Source: NSE.", summary(json))
        val r = rows(json); assertEquals(8, r.size)
        assertEquals("FILATFASH", r.first()["title"].asString); assertEquals("₹0.18 · band 10% · traded ₹30.93 L", r.first()["sub"].asString); assertEquals("+5.88%", r.first()["value"].asString)   // pChange arrives as "   5.88"
        assertEquals("SIGIND (BE)", r[1]["title"].asString); assertEquals("0.00%", r[1]["value"].asString)
        assertEquals("VIRTUALG", r[4]["title"].asString); assertEquals("₹0.45 · band 5% · traded ₹1.05 L", r[4]["sub"].asString)
        assertEquals(17, TermTesting.card(json)["upper"].asInt)

        // nothing at a band (early in the session): a plain card that says so, not an error and not a chart of zeros
        val zero = """{"count":{"TOTAL":"0","UPPER":"0","LOWER":"0","BOTH":"0"},"upper":{"AllSec":{"data":[],"timestamp":"17-Nov-2026 09:16:00"}},"lower":{"AllSec":{"data":[]}},"both":{"AllSec":{"data":[]}}}"""
        val quiet = PulseBandsFeature.build(zero)
        assertNull(TermTesting.error(quiet)); assertEquals("None", card(quiet)["headline"].asString); assertTrue(TermTesting.blocks(quiet).none { it["type"].asString == "viz" })
        assertEquals("NSE price-band hitters as of 17-Nov-2026 09:16: no stocks are listed at their upper or lower price band. Source: NSE.", summary(quiet))
        assertTrue(TermTesting.error(PulseBandsFeature.build(""))!!.contains("unavailable")); assertTrue(TermTesting.error(PulseBandsFeature.build("{}"))!!.contains("unavailable"))
        assertTrue(TermTesting.error(PulseBandsFeature.build("[1,2]"))!!.contains("unavailable"))
    }

    @Test fun volume() {
        val json = PulseVolumeFeature.build(fx("volume")); wellFormed(json)
        val d = TermTesting.viz(json, "divbars")
        assertEquals("x", d["unit"].asString); assertEquals(8, items(json, "divbars").size)
        assertEquals("RPSGVENT", items(json, "divbars").first()["label"].asString)
        assertEquals(2659000.0 / 539834, d.bar("RPSGVENT")["value"].asDouble, 0.01)      // 26.59 L shares ÷ 1-week average 5,39,834 = 4.93×
        assertEquals("up", d.bar("RPSGVENT")["tone"].asString); assertEquals("26.59 L shares · price +0.71%", d.bar("RPSGVENT")["note"].asString)
        assertEquals(items(json, "divbars").map { it["value"].asDouble }.sortedDescending(), items(json, "divbars").map { it["value"].asDouble })
        assertEquals("RPSGVENT · 4.9× usual volume", card(json)["headline"].asString)
        assertEquals("NSE volume gainers as of 16-Nov-2026 16:00: 8 stocks listed; highest RPSGVENT at 4.9 times its 1-week average volume (26.59 L shares, price +0.71%). Source: NSE.", summary(json))
        val r = rows(json); assertEquals(5, r.size)
        assertEquals("26.59 L shares · 1-week avg 5.40 L · ₹850.65", r.first()["sub"].asString); assertEquals("4.9×", r.first()["value"].asString); assertEquals("TVSSCS", r.last()["title"].asString)
        assertTrue(TermTesting.error(PulseVolumeFeature.build("""{"data":[],"timestamp":"17-Nov-2026 09:00:00"}"""))!!.contains("no volume gainers"))
        assertTrue(TermTesting.error(PulseVolumeFeature.build(""))!!.contains("no volume gainers")); assertTrue(TermTesting.error(PulseVolumeFeature.build("<html>"))!!.contains("no volume gainers"))
        assertTrue(TermTesting.error(PulseVolumeFeature.build("""{"data":[{"symbol":"X","week1volChange":null}]}"""))!!.contains("no volume gainers"))
    }

    @Test fun commandsAndShortQuestionsRoute() {
        val on = ChatFlow.Env(modelReady = true, kite = ChatFlow.Kite.OFF, webOn = true)
        for (id in listOf("indices", "sectors", "movers", "breadth", "active", "status", "highs", "bands", "volume")) {
            val f = Terminal.find(id)!!
            assertTrue("${f.id} object name", f.javaClass.simpleName.startsWith("Pulse"))
            assertEquals(ChatFlow.Action.Term(id, ""), ChatFlow.route("/$id", on))
        }
        fun hit(q: String) = Terminal.match(q)?.first?.id
        assertEquals("indices", hit("show all indices")); assertEquals("indices", hit("nifty indices today"))
        assertEquals("sectors", hit("how are the sectors doing")); assertEquals("sectors", hit("best sectors today")); assertEquals("sectors", hit("sector performance"))
        assertEquals("movers", hit("top gainers today")); assertEquals("movers", hit("biggest losers")); assertEquals("movers", hit("gainers and losers"))
        assertEquals("breadth", hit("market breadth")); assertEquals("breadth", hit("advance decline ratio")); assertEquals("breadth", hit("advances vs declines"))
        assertEquals("active", hit("most active stocks")); assertEquals("status", hit("is the market open")); assertEquals("status", hit("are markets closed today")); assertEquals("status", hit("market status"))
        assertEquals("highs", hit("stocks at 52 week high")); assertEquals("highs", hit("new 52-week lows"))
        assertEquals("bands", hit("stocks in upper circuit")); assertEquals("bands", hit("price band hitters"))
        assertEquals("volume", hit("unusual volume today")); assertEquals("volume", hit("volume shockers"))
        // questions about one company, or general ones, are not captured
        for (q in listOf("what is the 52 week high of INFY", "is INFY in upper circuit", "which sector is TCS in", "what is the volume of SBIN", "is INFY active", "show me the heatmap", "why did the market open lower"))
            assertTrue(q, hit(q).let { it == null || it == "heatmap" })
        assertEquals("heatmap", hit("show me the heatmap"))
    }
}
