package com.khayali.pulao.term

import com.khayali.pulao.ai.ChatFlow
import com.khayali.pulao.ai.Commands
import com.khayali.pulao.ai.term.HeatmapFeature
import com.khayali.pulao.ai.term.Hosts
import com.khayali.pulao.ai.term.Quant
import com.khayali.pulao.ai.term.ResultsFlowFeature
import com.khayali.pulao.ai.term.Series
import com.khayali.pulao.ai.term.Terminal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerminalTest {
    @Test fun registryIsConsistent() {
        val ids = Terminal.FEATURES.map { it.id }
        assertEquals("duplicate feature ids", ids.size, ids.toSet().size)
        for (f in Terminal.FEATURES) {
            assertTrue("${f.id}: id must be lower-case letters/digits", Regex("[a-z][a-z0-9]{1,14}").matches(f.id))
            assertTrue("${f.id}: usage must start with /${f.id}", f.usage == "/${f.id}" || f.usage.startsWith("/${f.id} "))
            assertTrue("${f.id}: help must be one useful line", f.help.length in 12..110 && !f.help.contains('\n'))
            assertNull("${f.id} clashes with a built-in command", Commands.ALL.firstOrNull { it.name == f.id })
            assertEquals(f, Terminal.find(f.id.uppercase()))
        }
        assertTrue(Terminal.catalogue().contains("heatmap"))
    }

    @Test fun commandsAndPlainQuestionsReachFeatures() {
        val on = ChatFlow.Env(modelReady = true, kite = ChatFlow.Kite.OFF, webOn = true)
        assertEquals(ChatFlow.Action.Term("heatmap", "NIFTY BANK"), ChatFlow.route("/heatmap NIFTY BANK", on))
        assertEquals(ChatFlow.Action.Term("flow", "INFRA"), ChatFlow.route("/flow @INFRA", on))
        assertEquals(ChatFlow.Action.Term("heatmap", ""), ChatFlow.route("show me the heatmap", on))        // short and unambiguous → no model needed
        assertEquals(ChatFlow.Action.EnableWeb("/heatmap"), ChatFlow.route("/heatmap", on.copy(webOn = false)))      // still opt-in
        // long or open questions go to the model, which may call the terminal tool itself
        assertTrue(ChatFlow.route("can you explain what a heatmap tells me about market breadth and why it matters today", on) is ChatFlow.Action.Model)
        assertTrue(Commands.suggest("/he").any { it.name == "heatmap" })
    }

    @Test fun onlyAllowListedHostsCanBeFetched() {
        assertTrue(Hosts.allowed("https://query1.finance.yahoo.com/v8/finance/chart/X"))
        assertFalse(Hosts.allowed("http://query1.finance.yahoo.com/v8/finance/chart/X"))        // https only
        assertFalse(Hosts.allowed("https://evil.example/query1.finance.yahoo.com"))
        assertFalse(Hosts.allowed("https://query1.finance.yahoo.com.evil.example/x"))
    }

    @Test fun featuresArePureAndChartsAreOffline() {
        // Features never connect themselves: they only ask Fetch. Networking stays in WebClient / KiteMcpClient.
        File("src/main/kotlin/com/khayali/pulao/ai/term").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val t = f.readText()
            listOf("java.net.", "HttpURLConnection", "WebView", "okhttp", "Socket(").forEach { assertFalse("${f.name} uses $it", t.contains(it)) }
            assertFalse("${f.name} hard-codes a URL — build it from a host constant and add the host to Hosts.ALLOWED", Regex("""https?://[a-z0-9]""").containsMatchIn(t))
        }
        // Chart plug-ins are local scripts: no network calls, no remote resources.
        File("src/main/assets/viz").listFiles()!!.filter { it.name.startsWith("r_") && it.extension == "js" }.forEach { f ->
            val t = f.readText()
            listOf("http:", "https:", "fetch(", "XMLHttpRequest", "import(", "WebSocket").forEach { assertFalse("${f.name} uses $it", t.contains(it)) }
            assertTrue("${f.name} must be listed in viz.html", File("src/main/assets/viz/viz.html").readText().contains("\"${f.name}\""))
        }
    }

    @Test fun heatmap() {
        assertEquals("NIFTY 50", HeatmapFeature.index("")); assertEquals("NIFTY BANK", HeatmapFeature.index("bank")); assertEquals("NIFTY IT", HeatmapFeature.index("nifty  it"))
        val json = HeatmapFeature.build("NIFTY 50", TermTesting.fixture("heatmap_nifty50.json"), mine = setOf("MAXHEALTH"))
        val items = TermTesting.viz(json, "treemap").getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(8, items.size)
        assertTrue(items.first { it["label"].asString == "MAXHEALTH" }["mine"].asBoolean)
        assertEquals(6.63, items.first { it["label"].asString == "MAXHEALTH" }["change"].asDouble, 1e-9)
        val s = TermTesting.card(json)["summary"].asString
        assertTrue(s, s.startsWith("NIFTY 50 today:") && s.contains("Best MAXHEALTH +6.63%") && s.endsWith("Source: NSE."))
        assertTrue(TermTesting.error(HeatmapFeature.build("NIFTY XYZ", """{"data":null,"error":{}}"""))!!.contains("No heatmap data"))
        val fake = TermTesting.Fake(nse = mapOf("NextApi/apiClient/indexTrackerApi" to TermTesting.fixture("heatmap_nifty50.json")))
        runBlocking { HeatmapFeature.run("bank", fake) }
        assertTrue(fake.asked.single(), fake.asked.single().endsWith("index=NIFTY%20BANK"))
    }

    @Test fun resultsMoneyFlowBalances() {
        val json = ResultsFlowFeature.build("INFY", TermTesting.fixture("flow_results.json"))
        val d = TermTesting.viz(json, "sankey")
        val links = d.getAsJsonArray("links").map { it.asJsonObject }
        fun into(id: String) = links.filter { it["to"].asString == id }.sumOf { it["value"].asDouble }
        fun outOf(id: String) = links.filter { it["from"].asString == id }.sumOf { it["value"].asDouble }
        assertEquals(34915.0 + 1001.0, into("income"), 0.5)                    // sales + other income, ₹ lakh → ₹ crore
        assertEquals(into("income"), outOf("income"), into("income") * 0.01)    // costs + profit before tax account for all income
        assertEquals(into("pbt"), outOf("pbt"), 0.5)                            // tax + net profit = profit before tax
        val s = TermTesting.card(json)["summary"].asString
        assertTrue(s, s.startsWith("INFY Q3 FY25: sales ₹34,915 Cr, net profit ₹6,358 Cr (net margin 18.2%)") && s.contains("vs a year ago"))
        assertEquals(5, TermTesting.viz(json, "groupbars").getAsJsonArray("items").size())
        assertTrue(TermTesting.error(ResultsFlowFeature.build("X", """{"resCmpData":[],"bankNonBnking":"N"}"""))!!.contains("no quarterly results"))
        assertTrue(TermTesting.error(ResultsFlowFeature.build("X", """{"resCmpData":[],"bankNonBnking":"B"}"""))!!.contains("banking format"))
        assertTrue(TermTesting.error(ResultsFlowFeature.build("X", "<html>"))!!.contains("Couldn't read"))
    }

    @Test fun moneyFlowFromTheLatestXbrlFiling() {
        val p = com.khayali.pulao.ai.term.Xbrl.parse(TermTesting.fixture("flow_xbrl.xml"))!!
        assertEquals("INFY", p.symbol); assertEquals("2026-09-30", p.to); assertTrue(p.standalone)
        assertEquals(40719.0, p.revenue, 0.5); assertEquals(1702.0, p.otherIncome, 0.5); assertEquals(19100.0, p.staff, 0.5)
        assertEquals(10905.0, p.beforeTax, 0.5); assertEquals(3012.0, p.tax, 0.5); assertEquals(7893.0, p.profit, 0.5); assertEquals(19.45, p.eps!!, 1e-9)
        // the quarter's costs and pre-tax profit add up to its income (within 1%)
        assertEquals(p.income, p.materials + p.staff + p.finance + p.depreciation + p.otherExpenses + p.beforeTax, p.income * 0.01)
        val qs = ResultsFlowFeature.quarters(TermTesting.fixture("flow_topcorp.json"))
        assertEquals(5, qs.size); assertEquals(java.time.LocalDate.of(2026, 9, 30), qs.first().to)
        val json = ResultsFlowFeature.fromFiling("INFY", p, qs)
        val s = TermTesting.card(json)["summary"].asString
        assertTrue(s, s.startsWith("INFY Q2 FY27: sales ₹40,719 Cr, net profit ₹7,893 Cr") && s.contains("vs a year ago") && s.contains("XBRL filing"))
        assertEquals(listOf("Q2 FY26", "Q3 FY26", "Q4 FY26", "Q1 FY27", "Q2 FY27"), TermTesting.viz(json, "groupbars").getAsJsonArray("items").map { it.asJsonObject["label"].asString })
        assertNull(com.khayali.pulao.ai.term.Xbrl.parse("<xbrl/>"))
        // run(): uses the filing when the snapshot links one on the archive host, and only that host
        val fake = TermTesting.Fake(nse = mapOf("top-corp-info" to TermTesting.fixture("flow_topcorp.json")), pages = mapOf("INTEGRATED_FILING" to TermTesting.fixture("flow_xbrl.xml")))
        val out = runBlocking { ResultsFlowFeature.run("INFY", fake) }
        assertTrue(out, TermTesting.card(out)["summary"].asString.startsWith("INFY Q2 FY27"))
        assertTrue(fake.asked.any { it.startsWith("get:https://nsearchives.nseindia.in/") })
    }

    @Test fun quant() {
        val chart = """{"chart":{"result":[{"meta":{"symbol":"INFRA.NS","longName":"Infra Builders"},"timestamp":[1794195900,1794282300,1794368700,1794455100],
            "indicators":{"quote":[{"open":[100,null,110,121],"high":[101,null,111,122],"low":[99,null,109,120],"close":[100,null,110,121],"volume":[1,null,2,3]}]}}]}}"""
        val s = Series.parse(chart)!!
        assertEquals(3, s.size); assertEquals(21.0, s.change(), 1e-9)
        assertEquals(listOf(0.1, 0.1), s.returns().map { Math.round(it * 1e6) / 1e6 })
        assertNull(Series.parse("{}")); assertNull(Series.parse("<html>"))
        assertEquals(listOf(0.0, -10.0, 0.0, -50.0), Quant.drawdown(listOf(100.0, 90.0, 100.0, 50.0)).map { Math.round(it * 1e6) / 1e6 })
        assertEquals(1.0, Quant.correlation(listOf(1.0, 2.0, 3.0, 4.0), listOf(2.0, 4.0, 6.0, 8.0)), 1e-9)
        assertEquals(2.0, Quant.beta(listOf(0.02, 0.04, -0.02, 0.06), listOf(0.01, 0.02, -0.01, 0.03)), 1e-9)
        assertEquals(10.0, Quant.cagr(100.0, 121.0, 2.0), 1e-9)
        assertEquals(listOf(null, 1.5, 2.5), Quant.sma(listOf(1.0, 2.0, 3.0), 2))
    }
}
