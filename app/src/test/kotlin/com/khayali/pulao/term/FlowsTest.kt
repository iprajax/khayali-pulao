package com.khayali.pulao.term

import com.khayali.pulao.ai.term.Flows
import com.khayali.pulao.ai.term.FlowsAnnouncementsFeature
import com.khayali.pulao.ai.term.FlowsDealsFeature
import com.khayali.pulao.ai.term.FlowsEtfsFeature
import com.khayali.pulao.ai.term.FlowsFiiDiiFeature
import com.khayali.pulao.ai.term.FlowsFilingsFeature
import com.khayali.pulao.ai.term.FlowsInsiderFeature
import com.khayali.pulao.ai.term.FlowsOiSpurtsFeature
import com.khayali.pulao.ai.term.FlowsSgbFeature
import com.khayali.pulao.ai.term.Terminal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class FlowsTest {
    private fun fx(name: String) = TermTesting.fixture(name)
    private fun summary(json: String) = TermTesting.card(json)["summary"].asString
    private fun vizzes(json: String, type: String) = TermTesting.blocks(json).filter { it["type"].asString == "viz" && it["viz"].asString == type }.map { it.getAsJsonObject("data") }
    private fun rows(json: String, title: String) = TermTesting.blocks(json).first { it["type"].asString == "rows" && it["title"].asString == title }.getAsJsonArray("rows").map { it.asJsonObject }
    private fun headline(json: String) = TermTesting.card(json).getAsJsonObject("card")["headline"].asString
    private fun sub(json: String) = TermTesting.card(json).getAsJsonObject("card")["sub"].asString
    private val today = LocalDate.of(2026, 11, 17)

    /** Keeps each card built from a fixture under build/flows-cards, so its charts can be rendered with tools/vizshot.sh and looked at. */
    private fun dump(name: String, json: String) { runCatching { File("build/flows-cards").apply { mkdirs() }.resolve("$name.json").writeText(json) } }

    @Test fun everyFeatureIsRegisteredAndWellFormed() {
        val mine = listOf(FlowsFiiDiiFeature, FlowsDealsFeature, FlowsInsiderFeature, FlowsAnnouncementsFeature, FlowsOiSpurtsFeature, FlowsFilingsFeature, FlowsEtfsFeature, FlowsSgbFeature)
        assertEquals(listOf("fiidii", "deals", "insider", "announcements", "oispurts", "filings", "etfs", "sgb"), mine.map { it.id })
        mine.forEach { assertEquals(it, Terminal.find(it.id)) }
        // short plain phrasings reach the right feature, with the company as the argument
        assertEquals(FlowsFiiDiiFeature to "", Terminal.match("what did FIIs do today"))
        assertEquals(FlowsDealsFeature to "", Terminal.match("any bulk deals today?"))
        assertEquals(FlowsInsiderFeature to "INFY", Terminal.match("insider trading @INFY"))
        assertEquals(FlowsAnnouncementsFeature to "tata motors", Terminal.match("latest announcements for tata motors"))
        assertEquals(FlowsOiSpurtsFeature to "", Terminal.match("show oi spurts"))
        assertEquals(FlowsFilingsFeature to "INFY", Terminal.match("recent filings @INFY"))
        assertEquals(FlowsEtfsFeature to "", Terminal.match("most traded etfs"))
        assertEquals(FlowsSgbFeature to "", Terminal.match("sovereign gold bonds"))
        assertNull(Terminal.match("what is an etf"))          // a definition question is not a request for the list
    }

    @Test fun helpers() {
        assertEquals(LocalDate.of(2026, 11, 16), Flows.date("16-Nov-2026")); assertEquals(LocalDate.of(2026, 9, 30), Flows.date("30 Sep 2026"))
        assertEquals(LocalDate.of(2026, 11, 12), Flows.date("12-Nov-2026 11:19:58")); assertNull(Flows.date("-")); assertNull(Flows.date("19-SEP-17")); assertNull(Flows.date("31-Feb-2026"))
        assertEquals("16 Nov 2026 15:40", Flows.stamp("16-Nov-2026 15:40:15")); assertEquals("16 Nov 2026", Flows.stamp("16-Nov-2026")); assertEquals("soon", Flows.stamp(" soon "))
        assertEquals("Nov '26", Flows.month(LocalDate.of(2026, 11, 16)))
        assertEquals("₹1,905.30 Cr", Flows.rupees(19053000000.0)); assertEquals("₹26.33 L", Flows.rupees(2632737.0)); assertEquals("₹31,743", Flows.rupees(31743.0)); assertEquals("−₹1.00 Cr", Flows.rupees(-1e7))
        assertEquals("+₹4,865 Cr", Flows.signedCr(4864.68)); assertEquals("−₹3,519 Cr", Flows.signedCr(-3519.05))
        assertEquals("abcd…", Flows.clip("abcdefgh", 5)); assertEquals("a b", Flows.clip(" a \n b ", 5))
        // head(): the first n objects of a huge array, without being fooled by brackets or quotes inside strings
        val big = """ [{"a":"x ] } \" {","b":[1,{"c":2}]},{"a":"2"},{"a":"3"}""" + ",{\"a\":\"pad\"}".repeat(50_000) + "]"
        assertEquals("""[{"a":"x ] } \" {","b":[1,{"c":2}]},{"a":"2"}]""", Flows.head(big, 2))
        assertEquals("""[{"a":1}]""", Flows.head("""[{"a":1}]""", 5)); assertEquals("<html>", Flows.head("<html>", 5)); assertEquals("", Flows.head("", 5)); assertEquals("""{"a":[1]}""", Flows.head("""{"a":[1]}""", 1))
    }

    @Test fun fiiDii() {
        val json = FlowsFiiDiiFeature.build(fx("flows_fiidii.json")); dump("fiidii", json)
        assertEquals("FII/FPI +₹4,865 Cr", headline(json))
        assertEquals("FII/FPI net bought ₹4,865 Cr · DII net sold ₹3,519 Cr", sub(json))
        val bars = TermTesting.viz(json, "groupbars")
        assertEquals(listOf("Bought", "Sold"), bars.getAsJsonArray("series").map { it.asString })
        val fii = bars.getAsJsonArray("items").map { it.asJsonObject }.first { it["label"].asString == "FII/FPI" }.getAsJsonArray("values").map { it.asDouble }
        assertEquals(listOf(15636.88, 10772.2), fii)
        val net = TermTesting.viz(json, "netbars").getAsJsonArray("items").map { it.asJsonObject }.associate { it["label"].asString to it["value"].asDouble }
        assertEquals(mapOf("FII/FPI" to 4864.68, "DII" to -3519.05, "Combined" to 1345.63), net)
        assertEquals("On 16 Nov 2026 FII/FPI bought ₹15,637 Cr and sold ₹10,772 Cr (net +₹4,865 Cr); DII bought ₹11,752 Cr and sold ₹15,271 Cr (net −₹3,519 Cr). Provisional cash-market figures. Source: NSE.", summary(json))
        // one side missing → still a card for the other; nothing usable → a plain message
        val only = FlowsFiiDiiFeature.build("""[{"buyValue":"100.5","category":"DII","date":"16-Nov-2026","netValue":"-20.5","sellValue":"121"}]""")
        assertEquals("DII −₹21 Cr", headline(only)); assertEquals(1, TermTesting.viz(only, "netbars").getAsJsonArray("items").size())
        assertTrue(TermTesting.error(FlowsFiiDiiFeature.build("[]"))!!.contains("not published"))
        assertTrue(TermTesting.error(FlowsFiiDiiFeature.build("<html>Access Denied</html>"))!!.contains("not published"))
        assertTrue(TermTesting.error(FlowsFiiDiiFeature.build("""[{"category":"FII/FPI","buyValue":"-","sellValue":null}]"""))!!.contains("not published"))
    }

    @Test fun deals() {
        val json = FlowsDealsFeature.build(fx("flows_largedeal.json"), fx("flows_blockdeal.json")); dump("deals", json)
        assertEquals("14 bulk · 7 block", headline(json))
        val items = TermTesting.viz(json, "netbars").getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(setOf("INOXWIND", "KAYNES", "MARSONS", "V2RETAIL", "MEESHO"), items.map { it["label"].asString }.toSet())
        // KAYNES: (849421 × 2928.85 + 408224 × 2946.71) bought − (385511 × 2951.40 + 845348 × 2925.01) sold = −₹1.71 Cr
        assertEquals((849421 * 2928.85 + 408224 * 2946.71 - 385511 * 2951.4 - 845348 * 2925.01) / 1e7, items.first { it["label"].asString == "KAYNES" }["value"].asDouble, 0.006)
        assertTrue(TermTesting.viz(json, "netbars")["horizontal"].asBoolean)
        val biggest = rows(json, "Largest deals").first()
        assertEquals("MEESHO · INDIVIDUAL CLIENT 1", biggest["title"].asString); assertEquals("₹1,885.17 Cr", biggest["value"].asString); assertEquals("down", biggest["tone"].asString)
        assertEquals("Block · sold 7,64,00,000 @ ₹246.75 · 13 Nov 2026", biggest["sub"].asString)
        val window = rows(json, "Block-deal window (latest session data)")
        assertEquals("GROWW", window.first()["title"].asString); assertEquals("₹1,905.30 Cr", window.first()["value"].asString)
        val s = summary(json)
        assertTrue(s, s.startsWith("As on 16 Nov 2026 NSE lists 14 bulk and 7 block deals; disclosed buying ") && s.contains("Largest: MEESHO — INDIVIDUAL CLIENT 1 sold 7,64,00,000 shares at ₹246.75 (₹1,885.17 Cr)") && s.endsWith("Source: NSE."))

        // one stock: bars are per client, and the pills follow the stock
        val one = FlowsDealsFeature.build(fx("flows_largedeal.json"), fx("flows_blockdeal.json"), "marsons"); dump("deals_one", one)
        assertEquals("6 bulk · 0 block", headline(one)); assertTrue(summary(one), summary(one).startsWith("MARSONS: as on 16 Nov 2026 NSE lists 6 bulk and 0 block deals; disclosed buying ₹155.74 Cr, selling ₹28.04 Cr."))
        assertEquals("Client", TermTesting.viz(one, "netbars")["yLabel"].asString)
        assertEquals(5, TermTesting.viz(one, "netbars").getAsJsonArray("items").size())       // QE Securities appears on both sides → one bar
        assertTrue(TermTesting.card(one).getAsJsonArray("pills").map { it.asString }.contains("/insider MARSONS"))
        // a stock only in today's block window; a stock with nothing; feeds down
        val win = FlowsDealsFeature.build(fx("flows_largedeal.json"), fx("flows_blockdeal.json"), "ALKEM")
        assertEquals("1 in the block window", headline(win)); assertTrue(summary(win), summary(win).contains("the largest ALKEM (₹301.90 Cr)"))
        assertTrue(TermTesting.error(FlowsDealsFeature.build(fx("flows_largedeal.json"), fx("flows_blockdeal.json"), "INFY"))!!.contains("No bulk or block deals are listed for INFY"))
        assertTrue(TermTesting.error(FlowsDealsFeature.build("", "<html>"))!!.contains("Couldn't read"))
        assertTrue(TermTesting.error(FlowsDealsFeature.build("""{"as_on_date":"16-Nov-2026","BULK_DEALS_DATA":[],"BLOCK_DEALS_DATA":[]}""", """{"data":[]}"""))!!.contains("lists no bulk or block deals"))
        val fake = TermTesting.Fake(nse = mapOf("snapshot-capital-market-largedeal" to fx("flows_largedeal.json"), "block-deal" to fx("flows_blockdeal.json")))
        val ran = runBlocking { FlowsDealsFeature.run("@kaynes", fake) }
        assertEquals(listOf("nse:snapshot-capital-market-largedeal", "nse:block-deal"), fake.asked); assertEquals("4 bulk · 0 block", headline(ran))
    }

    @Test fun oiSpurts() {
        val json = FlowsOiSpurtsFeature.build(fx("flows_oispurts.json")); dump("oispurts", json)
        assertEquals("KAYNES +72.3%", headline(json))
        assertEquals("largest change in open interest · 8 of 12 underlyings rose, 4 fell", sub(json))
        val d = TermTesting.viz(json, "netbars"); val items = d.getAsJsonArray("items").map { it.asJsonObject }
        assertEquals("%", d["unit"].asString); assertEquals("Change in open interest (%)", d["xLabel"].asString)
        assertEquals(9, items.size)                                                            // six largest rises, three largest falls
        assertEquals(72.3, items.first()["value"].asDouble, 1e-9); assertEquals("POWERINDIA", items.last()["label"].asString); assertEquals(-7.95, items.last()["value"].asDouble, 1e-9)
        assertEquals("OI 59,514, previous 34,540", items.first()["note"].asString)
        assertEquals("Open-interest spurts as of 16 Nov 2026 15:40: largest rise KAYNES +72.3% (OI 59,514, previous 34,540), largest fall POWERINDIA −8.0%; 8 of 12 underlyings saw open interest rise. Source: NSE.", summary(json))
        assertTrue(TermTesting.error(FlowsOiSpurtsFeature.build("""{"data":[],"timestamp":"16-Nov-2026 15:40:15"}"""))!!.contains("No open-interest data"))
        assertTrue(TermTesting.error(FlowsOiSpurtsFeature.build(""))!!.contains("No open-interest data"))
        assertTrue(TermTesting.error(FlowsOiSpurtsFeature.build("""{"data":[{"symbol":"X","latestOI":null,"prevOI":1,"avgInOI":"-"}]}"""))!!.contains("No open-interest data"))
    }

    @Test fun insider() {
        val json = FlowsInsiderFeature.build("RELIANCE", fx("flows_insider.json")); dump("insider", json)
        // bought: 44,30,088 + 13,34,544 (+ gifts and a transfer with no value); sold: 32,94,168 + 13,85,00,000 + 3,46,25,000 + 22,36,401
        assertEquals("Net sold ₹17.29 Cr", headline(json))
        assertEquals("22 May 2023 to 13 Feb 2026 · bought ₹57.65 L · sold ₹17.87 Cr", sub(json))
        val months = TermTesting.viz(json, "netbars").getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(listOf("Nov '23", "Sep '25", "Feb '26"), months.map { it["label"].asString })          // gifts and a nil-value transfer are not counted
        assertEquals(-17.3125, months.first { it["label"].asString == "Sep '25" }["value"].asDouble, 0.001)        // −(13.85 + 3.4625) ₹ Cr
        assertEquals(0.247, months.last()["value"].asDouble, 1e-9)                                               // Feb '26: 44,30,088 + 13,34,544 − 32,94,168
        val points = TermTesting.viz(json, "timeline").getAsJsonArray("points").map { it.asJsonObject }
        assertEquals(6, points.size)                                                                              // pledges and nil-value gifts are not trades
        assertEquals("2023-11-23", points.first()["t"].asString); assertEquals("sell", points.first { it["t"].asString == "2025-09-09" }["side"].asString)
        assertEquals(1.385e8, points.first { it["t"].asString == "2025-09-09" }["value"].asDouble, 1e-6)
        val latest = rows(json, "Latest disclosures")
        assertEquals(8, latest.size); assertEquals("Sell ₹32.94 L", latest.first()["value"].asString); assertEquals("Other · Off Market · 2,320 shares · 13 Feb 2026", latest.first()["sub"].asString)
        assertEquals("Revokation of Pledge ₹16.78 L", latest[3]["value"].asString); assertEquals("", latest[3]["tone"].asString)
        assertEquals("Gift", latest[6]["value"].asString); assertEquals("", latest[6]["tone"].asString)
        assertEquals("RELIANCE insider disclosures (12 filings, 22 May 2023 to 13 Feb 2026): bought ₹57.65 L, sold ₹17.87 Cr, net sold ₹17.29 Cr. Latest: Insider A sold 2,320 shares on 13 Feb 2026. Source: NSE.", summary(json))
        // an ESOP allotment of depository receipts (as NSE reports them for some companies) is worded as what it is
        val esop = FlowsInsiderFeature.build("DEMO", """{"data":[{"acqMode":"ESOP","acqName":"Insider Z","acqfromDt":"02-Apr-2026","secAcq":"2061","secVal":"2632737","secType":"ADR/GDR/FCCB","tdpTransactionType":"-","personCategory":"-"}]}""")
        assertTrue(summary(esop), summary(esop).contains("bought ₹0, sold ₹0, net bought ₹0. Latest: Insider Z — ESOP, 2,061 ADR/GDR/FCCB on 02 Apr 2026."))
        assertTrue(vizzes(esop, "netbars").isEmpty()); assertEquals(listOf("ESOP"), vizzes(esop, "timeline").single().getAsJsonArray("lanes").map { it.asString })
        assertTrue(TermTesting.error(FlowsInsiderFeature.build("XYZ", """{"acqNameList":[],"data":[]}"""))!!.contains("no insider-trading disclosures for XYZ"))
        assertTrue(TermTesting.error(FlowsInsiderFeature.build("XYZ", "<html>"))!!.contains("Couldn't read"))
        val fake = TermTesting.Fake(nse = mapOf("corporates-pit" to fx("flows_insider.json")))
        runBlocking { FlowsInsiderFeature.run("@M&M", fake) }
        assertEquals("nse:corporates-pit?index=equities&symbol=M%26M", fake.asked.single())
        assertTrue(TermTesting.error(runBlocking { FlowsInsiderFeature.run("  ", fake) })!!.contains("Which company"))
    }

    @Test fun announcements() {
        val json = FlowsAnnouncementsFeature.build("TCS", fx("flows_announce.json")); dump("announcements", json)
        assertEquals("8 latest announcements", headline(json))
        assertEquals("14 Oct 2026 to 09 Nov 2026 · most recent: Analysts/Institutional Investor Meet/Con. Call Updates", sub(json))
        val d = TermTesting.viz(json, "timeline")
        assertEquals(listOf("Analysts/Institut…", "Updates", "Copy of Newspaper…", "Other"), d.getAsJsonArray("lanes").map { it.asString })
        val points = d.getAsJsonArray("points").map { it.asJsonObject }
        assertEquals(8, points.size); assertEquals("2026-10-14", points.first()["t"].asString); assertEquals("2026-11-09", points.last()["t"].asString)
        assertEquals(3, points.count { it["lane"].asString == "Updates" }); assertEquals(1, points.count { it["lane"].asString == "Other" })
        val recent = rows(json, "Most recent")
        assertEquals("Updates", recent[1]["title"].asString)
        assertTrue(recent[1]["sub"].asString, recent[1]["sub"].asString.startsWith("04 Nov 2026 · Press Release - TCS Enterprise AI Study: 84% say productivity"))
        assertEquals("TCS: 8 latest exchange announcements, 14 Oct 2026 to 09 Nov 2026. Most recent (09 Nov 2026): Analysts/Institutional Investor Meet/Con. Call Updates — Schedule of meet. Source: NSE.", summary(json))
        // a multi-megabyte reply: only the first 15 entries are read
        val one = fx("flows_announce.json").trim().removePrefix("[").removeSuffix("]").substringBefore(",{\"an_dt\"")
        val huge = "[" + List(3000) { one }.joinToString(",") + "]"
        assertTrue(huge.length > 1_500_000)
        assertEquals("15 latest announcements", headline(FlowsAnnouncementsFeature.build("TCS", huge)))
        assertTrue(TermTesting.error(FlowsAnnouncementsFeature.build("XYZ", "[]"))!!.contains("no announcements for XYZ"))
        assertTrue(TermTesting.error(FlowsAnnouncementsFeature.build("XYZ", "<html>"))!!.contains("Couldn't read"))
        assertTrue(TermTesting.error(FlowsAnnouncementsFeature.build("XYZ", """{"error":"x"}"""))!!.contains("Couldn't read"))
        // run(): the last 90 days first; the full list only when that is empty
        val fake = TermTesting.Fake(nse = mapOf("corporate-announcements?index=equities&symbol=TCS&from_date" to fx("flows_announce.json")))
        runBlocking { FlowsAnnouncementsFeature.run("tcs", fake) }
        assertEquals(listOf("nse:corporate-announcements?index=equities&symbol=TCS&from_date=19-08-2026&to_date=17-11-2026"), fake.asked)
        val quiet = TermTesting.Fake(nse = mapOf("corporate-announcements?index=equities&symbol=TCS&from_date" to "[]", "corporate-announcements?index=equities&symbol=TCS" to fx("flows_announce.json")))
        assertEquals("8 latest announcements", headline(runBlocking { FlowsAnnouncementsFeature.run("tcs", quiet) }))
        assertEquals("nse:corporate-announcements?index=equities&symbol=TCS", quiet.asked.last()); assertEquals(2, quiet.asked.size)
    }

    @Test fun filings() {
        val json = FlowsFilingsFeature.build("RELIANCE", fx("flows_topcorp.json"), today); dump("filings", json)
        assertEquals("Last filing 16 Nov 2026", headline(json))
        assertEquals("Announcement: Updates · board met 30 Oct 2026 · ex-date 05 Jun 2026", sub(json))
        val d = TermTesting.viz(json, "timeline")
        assertEquals(listOf("Announcements", "Corporate actions", "Board meetings", "Results"), d.getAsJsonArray("lanes").map { it.asString })
        val points = d.getAsJsonArray("points").map { it.asJsonObject }
        assertEquals(8, points.count { it["lane"].asString == "Announcements" })
        assertEquals(listOf("2026-06-05", "2025-08-14"), points.filter { it["lane"].asString == "Corporate actions" }.map { it["t"].asString })      // the chart keeps to two years; older ones stay in the rows
        assertEquals(4, points.count { it["lane"].asString == "Board meetings" })              // NSE lists each meeting twice; shown once per date
        assertEquals(listOf("2026-10-30", "2026-07-17", "2026-04-24", "2026-01-16", "2025-10-17"), points.filter { it["lane"].asString == "Results" }.map { it["t"].asString })
        val q = TermTesting.viz(json, "groupbars").getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(listOf("Sep '25", "Dec '25", "Mar '26", "Jun '26", "Sep '26"), q.map { it["label"].asString })
        assertEquals(listOf(178567.0, 15354.0), q.last().getAsJsonArray("values").map { it.asDouble })           // ₹ lakh → ₹ crore
        val stats = TermTesting.blocks(json).first { it["type"].asString == "stats" }.getAsJsonArray("items").map { it.asJsonObject }.associate { it["label"].asString to it["value"].asString }
        assertEquals("₹1,78,567 Cr", stats["Income, quarter to 30 Sep 2026"]); assertEquals("₹11.34", stats["EPS (diluted)"]); assertEquals("50.44%", stats["Promoter holding (30 Sep 2026)"])
        assertEquals("Dividend - Rs 6 Per Share", rows(json, "Corporate actions").first()["title"].asString)
        assertEquals(2, rows(json, "Board meetings").size)
        assertTrue(rows(json, "Board meetings").first()["sub"].asString, rows(json, "Board meetings").first()["sub"].asString.startsWith("Board Meeting to be held on 30-Oct-2026 to consider and approve"))
        assertEquals("RELIANCE filings: latest announcement 16 Nov 2026 (Updates); latest corporate action Dividend - Rs 6 Per Share, ex-date 05 Jun 2026; latest board meeting 30 Oct 2026; " +
            "quarter ended 30 Sep 2026: income ₹1,78,567 Cr, profit after tax ₹15,354 Cr (non-consolidated, un-audited). Source: NSE.", summary(json))
        // seen before the board meeting: it is marked as still ahead
        val before = FlowsFilingsFeature.build("RELIANCE", fx("flows_topcorp.json"), LocalDate.of(2026, 10, 20))
        assertTrue(sub(before), sub(before).contains("board meets 30 Oct 2026")); assertEquals("warn", rows(before, "Board meetings").first()["tone"].asString)
        assertTrue(rows(before, "Board meetings").first()["sub"].asString.startsWith("Upcoming · "))
        // an unknown symbol answers with empty sections (seen live); a broken reply is reported as such
        val empty = """{"latest_announcements":{"data":[]},"corporate_actions":{"data":[]},"shareholdings_patterns":{"data":{}},"financial_results":{"data":[]},"borad_meeting":{"data":[]}}"""
        assertTrue(TermTesting.error(FlowsFilingsFeature.build("NOSUCHSYM", empty, today))!!.contains("no corporate filings for NOSUCHSYM"))
        assertTrue(TermTesting.error(FlowsFilingsFeature.build("X", "", today))!!.contains("Couldn't read"))
        val fake = TermTesting.Fake(nse = mapOf("top-corp-info" to fx("flows_topcorp.json")))
        runBlocking { FlowsFilingsFeature.run("reliance", fake) }
        assertEquals("nse:top-corp-info?symbol=RELIANCE&market=equities", fake.asked.single())
    }

    @Test fun etfs() {
        val json = FlowsEtfsFeature.build(fx("flows_etf.json")); dump("etfs", json)
        assertEquals("SILVERBEES · ₹869.84 Cr", headline(json))
        val charts = vizzes(json, "netbars")
        assertEquals(2, charts.size)
        assertEquals("Traded value (₹ Cr)", charts[0]["xLabel"].asString); assertEquals("blue", charts[0]["color"].asString)
        assertEquals(869.84, charts[0].getAsJsonArray("items").first().asJsonObject["value"].asDouble, 1e-9)
        assertEquals(8, charts[0].getAsJsonArray("items").size())
        assertEquals("Day change (%)", charts[1]["xLabel"].asString); assertEquals(-4.89, charts[1].getAsJsonArray("items").first().asJsonObject["value"].asDouble, 1e-9)
        val first = rows(json, "Most traded").first()
        assertEquals("SILVERBEES", first["title"].asString); assertEquals("₹160.50 · −4.89%", first["value"].asString); assertEquals("down", first["tone"].asString)
        val s = summary(json)
        assertTrue(s, s.startsWith("ETFs on NSE as of 16 Nov 2026 16:00: most traded SILVERBEES (₹869.84 Cr traded, ₹160.50, −4.89%), then GOLDBEES (₹521.36 Cr traded, ₹110.10, −1.62%); ") && s.endsWith("Source: NSE."))
        // a word narrows the list to matching symbols or underlyings
        val gold = FlowsEtfsFeature.build(fx("flows_etf.json"), "Gold"); dump("etfs_gold", gold)
        assertTrue(headline(gold), headline(gold).startsWith("GOLDBEES · ₹521.36 Cr"))
        assertTrue(vizzes(gold, "netbars")[0].getAsJsonArray("items").map { it.asJsonObject["label"].asString }.none { it == "NIFTYBEES" })
        assertTrue(TermTesting.error(FlowsEtfsFeature.build(fx("flows_etf.json"), "zzz"))!!.contains("No ETF on NSE matches \"zzz\""))
        assertTrue(TermTesting.error(FlowsEtfsFeature.build("""{"timestamp":"x","data":[]}"""))!!.contains("No ETF data"))
        assertTrue(TermTesting.error(FlowsEtfsFeature.build("<html>"))!!.contains("No ETF data"))
    }

    @Test fun sovereignGoldBonds() {
        val json = FlowsSgbFeature.build(fx("flows_sgb.json")); dump("sgb", json)
        assertEquals("SGBSEP31II ₹13,909.01", headline(json))
        assertEquals("most traded · 136.8% above its issue price of ₹5,873 · −1.91% today", sub(json))
        val g = TermTesting.viz(json, "groupbars")
        assertEquals(listOf("Issue price", "Last price"), g.getAsJsonArray("series").map { it.asString })
        val bars = g.getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(5, bars.size); assertEquals("SEP31II", bars.first()["label"].asString); assertEquals(listOf(5873.0, 13909.01), bars.first().getAsJsonArray("values").map { it.asDouble })
        val gains = TermTesting.viz(json, "netbars").getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(8, gains.size)                                                            // SGBJUN27 did not trade, so it is not ranked
        assertFalse(gains.any { it["label"].asString == "SGBJUN27" })
        assertEquals(136.8, gains.first()["value"].asDouble, 1e-9)                             // 13909.01 / 5873 − 1
        assertEquals("9 sovereign gold bonds on NSE as of 16 Nov 2026 16:00; most traded SGBSEP31II at ₹13,909.01 (−1.91% today), 136.8% above its ₹5,873 issue price. 1 rose and 7 fell. Source: NSE.", summary(json))
        // a bond below its issue price is worded as such; nothing traded → still a card; empty or broken → a message
        val below = FlowsSgbFeature.build("""{"timestamp":"16-Nov-2026 16:00:00","data":[{"symbol":"SGBTEST","issue_price":"5000","ltP":"4500","per":"0","qty":"0","trdVal":"0"}]}""")
        assertTrue(sub(below), sub(below).contains("10.0% below its issue price of ₹5,000"))
        assertTrue(TermTesting.error(FlowsSgbFeature.build("""{"data":[]}"""))!!.contains("No sovereign gold bond data"))
        assertTrue(TermTesting.error(FlowsSgbFeature.build("Access Denied"))!!.contains("No sovereign gold bond data"))
    }

    @Test fun everyCardHasTheCommonParts() {
        val cards = listOf(
            FlowsFiiDiiFeature.build(fx("flows_fiidii.json")), FlowsDealsFeature.build(fx("flows_largedeal.json"), fx("flows_blockdeal.json")), FlowsOiSpurtsFeature.build(fx("flows_oispurts.json")),
            FlowsInsiderFeature.build("RELIANCE", fx("flows_insider.json")), FlowsAnnouncementsFeature.build("TCS", fx("flows_announce.json")), FlowsFilingsFeature.build("RELIANCE", fx("flows_topcorp.json"), today),
            FlowsEtfsFeature.build(fx("flows_etf.json")), FlowsSgbFeature.build(fx("flows_sgb.json")))
        for (json in cards) {
            val c = TermTesting.card(json); val card = c.getAsJsonObject("card"); val blocks = TermTesting.blocks(json)
            assertNull(json.take(80), c["error"])
            assertTrue(card["kicker"].asString.isNotBlank() && card["headline"].asString.isNotBlank() && card["sub"].asString.isNotBlank())
            assertEquals(listOf("NSE"), c.getAsJsonArray("sources").map { it.asString })
            assertTrue(c.getAsJsonArray("pills").size() in 2..4); assertTrue(c.getAsJsonArray("pills").all { it.asString.startsWith("/") })
            val charts = blocks.filter { it["type"].asString == "viz" }
            assertTrue(charts.size in 1..2); charts.forEach { assertTrue(it["caption"].asString.length > 20); assertTrue(it["height"].asInt in 120..320) }
            assertTrue("≤ 10 rows", blocks.filter { it["type"].asString == "rows" }.sumOf { it.getAsJsonArray("rows").size() } <= 10)
            val s = c["summary"].asString
            assertTrue(s, s.endsWith("Source: NSE.") && s.length < 420)
            // this app describes; it never advises
            assertFalse(json, Regex("""(?i)\b(should (buy|sell)|recommend|target price|bullish|bearish|good time to)\b""").containsMatchIn(json))
        }
    }
}
