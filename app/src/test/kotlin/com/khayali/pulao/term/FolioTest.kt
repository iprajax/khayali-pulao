package com.khayali.pulao.term

import com.khayali.pulao.Stock
import com.khayali.pulao.ai.ChatFlow
import com.khayali.pulao.ai.term.Folio
import com.khayali.pulao.ai.term.FolioCorrelationFeature
import com.khayali.pulao.ai.term.FolioIncomeFeature
import com.khayali.pulao.ai.term.FolioMapFeature
import com.khayali.pulao.ai.term.FolioPnlFlowFeature
import com.khayali.pulao.ai.term.FolioRebalanceFeature
import com.khayali.pulao.ai.term.FolioRiskFeature
import com.khayali.pulao.ai.term.FolioRiskReturnFeature
import com.khayali.pulao.ai.term.FolioStressFeature
import com.khayali.pulao.ai.term.FolioXrayFeature
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
 * Portfolio pack. The holdings are made up (round quantities and average costs); the prices are real:
 * each folio_chart_*.json is a quote-feed reply trimmed to its last 90 daily bars, dividends kept.
 */
class FolioTest {
    private fun h(name: String, qty: Double, avg: Double, ltp: Double) = Stock.Holding(name, qty, avg, qty * avg, ltp)
    private val holdings = listOf(
        h("ITC", 400.0, 310.0, 295.1), h("INFY", 60.0, 1300.0, 1145.1), h("HDFCBANK", 150.0, 700.0, 816.5), h("RELIANCE", 50.0, 1250.0, 1344.8), h("TATASTEEL", 500.0, 150.0, 182.26))
    private val charts = holdings.associate { it.name to TermTesting.fixture("folio_chart_${it.name.lowercase()}.json") }
    private val nifty = TermTesting.fixture("folio_chart_nsei.json")
    private val notFound = TermTesting.fixture("folio_chart_notfound.json")
    private val today = LocalDate.of(2026, 11, 17)
    private val all = listOf(FolioXrayFeature, FolioPnlFlowFeature, FolioRiskFeature, FolioCorrelationFeature, FolioRiskReturnFeature, FolioStressFeature, FolioIncomeFeature, FolioRebalanceFeature, FolioMapFeature)

    private fun summary(json: String) = TermTesting.card(json)["summary"].asString
    private fun headline(json: String) = TermTesting.card(json).getAsJsonObject("card")["headline"].asString
    private fun stat(json: String, label: String) = TermTesting.blocks(json).first { it["type"].asString == "stats" }.getAsJsonArray("items").map { it.asJsonObject }.first { it["label"].asString == label }["value"].asString
    private fun fake(hs: List<Stock.Holding> = holdings, withCharts: Boolean = true) =
        TermTesting.Fake(charts = if (withCharts) charts.mapKeys { it.key + ".NS" } + ("^NSEI" to nifty) else emptyMap(), holdings = hs, today = today)

    @Test fun registeredWithTheExpectedCommands() {
        assertEquals(listOf("xray", "pnlflow", "risk", "correlation", "riskreturn", "stress", "income", "rebalance", "foliomap"), all.map { it.id })
        all.forEach { assertEquals(it, Terminal.find(it.id)) }
        assertEquals(setOf("xray", "pnlflow", "rebalance", "foliomap"), all.filter { !it.needsWeb }.map { it.id }.toSet())
        val on = ChatFlow.Env(modelReady = true, kite = ChatFlow.Kite.OFF, webOn = true)
        mapOf("portfolio x-ray" to "xray", "how concentrated am I" to "xray", "show my p&l flow" to "pnlflow", "how risky is my portfolio" to "risk", "value at risk" to "risk",
            "correlation matrix" to "correlation", "risk vs return" to "riskreturn", "stress test my portfolio" to "stress", "what if the market falls" to "stress",
            "my dividend income" to "income", "rebalance" to "rebalance", "portfolio mind map" to "foliomap").forEach { (q, id) ->
            assertEquals(q, id, (ChatFlow.route(q, on) as? ChatFlow.Action.Term)?.id)
        }
        assertEquals(ChatFlow.Action.Term("xray", ""), ChatFlow.route("/xray", on.copy(webOn = false)))            // offline features need no web opt-in
        assertEquals(ChatFlow.Action.EnableWeb("/risk"), ChatFlow.route("/risk", on.copy(webOn = false)))
    }

    @Test fun everyFeatureAsksForKiteWhenThereAreNoHoldings() = runBlocking {
        for (f in all) {
            val fake = fake(emptyList())
            assertTrue(f.id, TermTesting.error(f.run("", fake))!!.startsWith("Connect Kite to analyse your portfolio"))
            assertTrue("${f.id} must not fetch anything without holdings", fake.asked.isEmpty())
        }
        // rows Kite reports with no quantity or no price are not holdings
        assertTrue(TermTesting.error(FolioXrayFeature.build(listOf(h("SOLD", 0.0, 100.0, 120.0), h("NOPRICE", 10.0, 100.0, 0.0))))!!.startsWith("Connect Kite"))
    }

    @Test fun onlySymbolsLeaveThePhoneOneAtATime() = runBlocking {
        val expected = holdings.sortedByDescending { it.current }.map { "chart:${it.name}.NS:1y:1d" }
        for (f in all) {
            val fake = fake()
            val json = f.run("", fake)
            assertNull("${f.id}: $json", TermTesting.error(json))
            when {
                !f.needsWeb -> assertTrue("${f.id} is offline", fake.asked.isEmpty())
                f.id == "correlation" || f.id == "income" -> assertEquals(f.id, expected, fake.asked)
                else -> assertEquals(f.id, expected + "chart:^NSEI:1y:1d", fake.asked)
            }
            // nothing but a symbol, a range and an interval is ever asked for: no quantity, cost or value
            fake.asked.forEach { assertTrue(it, Regex("""chart:(\^NSEI|[A-Z]+\.NS):1y:1d""").matches(it)) }
        }
    }

    @Test fun everyCardIsComplete() = runBlocking {
        for (f in all) {
            val json = f.run("", fake()); val c = TermTesting.card(json); val card = c.getAsJsonObject("card")
            assertTrue(f.id, card["kicker"].asString.startsWith("Your portfolio · "))
            assertTrue(f.id, card["headline"].asString.isNotBlank() && card["sub"].asString.isNotBlank())
            val charts = TermTesting.blocks(json).filter { it["type"].asString == "viz" }
            assertTrue("${f.id} charts", charts.size in 1..2 && charts.all { it["caption"].asString.length > 30 })
            assertTrue("${f.id} pills", c.getAsJsonArray("pills").size() in 2..4 && c.getAsJsonArray("pills").all { it.asString.startsWith("/") })
            assertTrue("${f.id} sources", c.getAsJsonArray("sources").size() >= 1)
            TermTesting.blocks(json).filter { it["type"].asString == "rows" }.forEach { assertTrue(f.id, it.getAsJsonArray("rows").size() <= 10) }
            val s = c["summary"].asString
            assertTrue("${f.id}: $s", s.contains("Source: ") && s.endsWith("."))
            listOf("should buy", "should sell", "recommend", "target price", "you should").forEach { assertFalse("${f.id} says '$it'", json.lowercase().contains(it)) }
        }
    }

    @Test fun xray() {
        val json = FolioXrayFeature.build(holdings)
        assertEquals("4.7 effective stocks", headline(json))
        assertEquals("HDFCBANK 26.2%", stat(json, "Largest holding")); assertEquals("70.9%", stat(json, "Top 3 together")); assertEquals("0.21", stat(json, "Herfindahl index"))
        val d = TermTesting.viz(json, "sunburst")
        val groups = d.getAsJsonObject("root").getAsJsonArray("children").map { it.asJsonObject }
        assertEquals(listOf("In profit", "In loss"), groups.map { it["name"].asString }); assertEquals(listOf("green", "red"), groups.map { it["color"].asString })
        assertEquals(listOf("HDFCBANK", "TATASTEEL", "RELIANCE"), groups[0].getAsJsonArray("children").map { it.asJsonObject["name"].asString })
        assertEquals(listOf("ITC", "INFY"), groups[1].getAsJsonArray("children").map { it.asJsonObject["name"].asString })
        // the outer ring adds up to the whole portfolio: 400×295.10 + 60×1145.10 + 150×816.50 + 50×1344.80 + 500×182.26
        assertEquals(467591.0, groups.sumOf { g -> g.getAsJsonArray("children").sumOf { it.asJsonObject["value"].asDouble } }, 0.01)
        assertEquals("P&L +₹17,475 (+16.6%)", groups[0].getAsJsonArray("children")[0].asJsonObject["note"].asString)
        assertEquals("Your 5 holdings are worth ₹4.68 L. Largest is HDFCBANK at 26.2%; the top 3 make up 70.9%. Herfindahl index 0.21, equal to 4.7 equally weighted stocks. Source: your Kite holdings.", summary(json))
        // one holding: fully concentrated, and no empty "In loss" group
        val one = FolioXrayFeature.build(listOf(h("ITC", 10.0, 100.0, 120.0)))
        assertEquals("1.0 effective stocks", headline(one)); assertEquals("1.00", stat(one, "Herfindahl index"))
        assertEquals(1, TermTesting.viz(one, "sunburst").getAsJsonObject("root").getAsJsonArray("children").size())
        // many holdings: the tail is bundled so the ring stays readable, and still adds up
        val many = (1..20).map { h("STK$it", 10.0, 100.0, 100.0 + it) }
        val kids = TermTesting.viz(FolioXrayFeature.build(many), "sunburst").getAsJsonObject("root").getAsJsonArray("children")[0].asJsonObject.getAsJsonArray("children").map { it.asJsonObject }
        assertEquals(12, kids.size); assertEquals("9 others", kids.last()["name"].asString)
        assertEquals(many.sumOf { it.current }, kids.sumOf { it["value"].asDouble }, 0.01)
    }

    @Test fun pnlFlowBalances() {
        val json = FolioPnlFlowFeature.build(holdings)
        val d = TermTesting.viz(json, "sankey"); val links = d.getAsJsonArray("links").map { it.asJsonObject }
        fun into(id: String) = links.filter { it["to"].asString == id }.sumOf { it["value"].asDouble }
        fun outOf(id: String) = links.filter { it["from"].asString == id }.sumOf { it["value"].asDouble }
        assertEquals(444500.0, outOf("invested"), 0.01); assertEquals(467591.0, into("value"), 0.01)
        assertEquals(17475.0 + 4740.0 + 16130.0, outOf("gain"), 0.01)          // HDFCBANK + RELIANCE + TATASTEEL
        assertEquals(5960.0 + 9294.0, into("loss"), 0.01)                      // ITC + INFY
        assertEquals(outOf("invested") + outOf("gain"), into("value") + into("loss"), 0.01)
        val nodes = d.getAsJsonArray("nodes").map { it.asJsonObject }
        nodes.filter { it["col"].asInt == 1 }.forEach { n -> assertEquals(n["label"].asString, into(n["id"].asString), outOf(n["id"].asString), 0.01) }      // every stock balances
        assertEquals("red", nodes.first { it["label"].asString == "ITC" }["color"].asString); assertEquals("green", nodes.first { it["label"].asString == "HDFCBANK" }["color"].asString)
        assertEquals("+₹23,091", headline(json))
        assertEquals("Your portfolio: ₹4,44,500 invested is worth ₹4,67,591 today, +₹23,091 (+5.2%). 3 stocks are in profit by ₹38,345 and 2 in loss by ₹15,254. Unrealised, before charges and tax. Source: your Kite holdings.", summary(json))
        // all in profit → no Loss node; many holdings → bundled, still balanced
        assertTrue(TermTesting.viz(FolioPnlFlowFeature.build(listOf(h("ITC", 10.0, 100.0, 120.0))), "sankey").getAsJsonArray("nodes").none { it.asJsonObject["id"].asString == "loss" })
        val many = (1..20).map { h("STK$it", 10.0, 100.0, 80.0 + it * 2) }
        val md = TermTesting.viz(FolioPnlFlowFeature.build(many), "sankey"); val ml = md.getAsJsonArray("links").map { it.asJsonObject }
        assertEquals(9, md.getAsJsonArray("nodes").count { it.asJsonObject["col"].asInt == 1 })       // 7 largest + others in profit + others in loss
        assertEquals(many.sumOf { it.current }, ml.filter { it["to"].asString == "value" }.sumOf { it["value"].asDouble }, 0.01)
        assertEquals(many.filter { it.pnl < 0 }.sumOf { -it.pnl }, ml.filter { it["to"].asString == "loss" }.sumOf { it["value"].asDouble }, 0.01)
    }

    @Test fun rebalance() {
        val json = FolioRebalanceFeature.build(holdings)
        val items = TermTesting.viz(json, "divbars").getAsJsonArray("items").map { it.asJsonObject }
        assertEquals("₹", TermTesting.viz(json, "divbars")["unit"].asString); assertEquals("26.2% now, equal share 20.0%", items[0]["note"].asString)
        assertEquals(listOf("HDFCBANK", "ITC", "TATASTEEL", "INFY", "RELIANCE"), items.map { it["label"].asString })
        assertEquals(122475.0 - 93518.2, items[0]["value"].asDouble, 0.01); assertEquals(67240.0 - 93518.2, items[4]["value"].asDouble, 0.01)
        assertEquals(0.0, items.sumOf { it["value"].asDouble }, 0.05)            // over and under cancel out
        assertEquals("₹53,479", headline(json))
        assertEquals("Against an equal share of ₹93,518 (20.0%) in each of 5 holdings, ₹53,479 sits above it. Furthest over: HDFCBANK by ₹28,957; furthest under: RELIANCE by ₹26,278. Arithmetic only, not a suggestion. Source: your Kite holdings.", summary(json))
        assertTrue(TermTesting.error(FolioRebalanceFeature.build(listOf(h("ITC", 10.0, 100.0, 120.0))))!!.contains("at least two holdings"))
        val many = (1..20).map { h("STK$it", 10.0, 100.0, 100.0 + it) }
        assertEquals(12, TermTesting.viz(FolioRebalanceFeature.build(many), "divbars").getAsJsonArray("items").size())
    }

    @Test fun mindMap() {
        val json = FolioMapFeature.build(holdings)
        val root = TermTesting.viz(json, "mindmap").getAsJsonObject("root")
        assertEquals("Portfolio", root["name"].asString); assertEquals(467591.0, root["value"].asDouble, 0.01); assertEquals("up", root["tone"].asString)
        val kids = root.getAsJsonArray("children").map { it.asJsonObject }
        assertEquals(listOf("HDFCBANK", "ITC", "TATASTEEL", "INFY", "RELIANCE"), kids.map { it["name"].asString })
        assertEquals(listOf("up", "down", "up", "down", "up"), kids.map { it["tone"].asString })
        assertEquals(listOf("Weight" to "25.2% · ₹1.18 L", "P&L" to "−₹5,960 (−4.8%)", "Avg → last" to "₹310.00 → ₹295.10"), kids[1].getAsJsonArray("children").map { it.asJsonObject["name"].asString to it.asJsonObject["value"].asString })
        assertEquals(listOf("green", "red", "green", "red", "green"), kids.map { it["color"].asString })
        // the same tree is also the data itself, for a renderer that reads name/children at the top level
        assertEquals(root.getAsJsonArray("children"), TermTesting.viz(json, "mindmap").getAsJsonArray("children")); assertEquals("₹4.68 L · +5.2%", TermTesting.viz(json, "mindmap")["sub"].asString)
        assertEquals("₹4.68 L", headline(json))
        assertEquals("Your 5 holdings are worth ₹4,67,591, +₹23,091 (+5.2%) on ₹4,44,500 invested. Largest: HDFCBANK at 26.2%. Best: TATASTEEL +21.5%; weakest: INFY −11.9%. Source: your Kite holdings.", summary(json))
        val many = (1..20).map { h("STK$it", 10.0, 100.0, 100.0 + it) }
        val mk = TermTesting.viz(FolioMapFeature.build(many), "mindmap").getAsJsonObject("root").getAsJsonArray("children").map { it.asJsonObject }
        assertEquals(10, mk.size); assertEquals("11 others", mk.last()["name"].asString); assertEquals(2, mk.first().getAsJsonArray("children").size())
    }

    @Test fun alignedReturns() {
        val m = Folio.market(holdings, charts, nifty)!!
        assertEquals(89, m.days); assertEquals(LocalDate.of(2026, 7, 10), m.dates.first()); assertEquals(LocalDate.of(2026, 11, 17), m.dates.last())
        assertEquals(5, m.lines.size); assertTrue(m.missing.isEmpty()); assertEquals(467591.0, m.value, 0.01)
        // the portfolio's beta is the weighted sum of its holdings' betas
        assertEquals(m.lines.sumOf { m.weight(it) * m.beta(it)!! }, com.khayali.pulao.ai.term.Quant.beta(m.portfolio, m.indexReturns!!), 1e-9)
        assertNull(Folio.market(holdings, emptyMap(), nifty)); assertNull(Folio.market(holdings, charts.mapValues { "<html>" }, nifty)); assertNull(Folio.market(holdings, charts.mapValues { notFound }, nifty))
        // a live bar repeating the last day counts once
        val twice = """{"chart":{"result":[{"meta":{"symbol":"X.NS"},"timestamp":[1794195900,1794282300,1794285000],"indicators":{"quote":[{"open":[1,1,1],"high":[1,1,1],"low":[1,1,1],"close":[100,110,121],"volume":[1,1,1]}]}}]}}"""
        val r = Folio.returnsByDate(com.khayali.pulao.ai.term.Series.parse(twice)!!)
        assertEquals(1, r.size); assertEquals(0.21, r.values.single(), 1e-9)
    }

    @Test fun risk() {
        val json = FolioRiskFeature.build(holdings, charts, nifty)
        assertEquals("₹7,610 value-at-risk", headline(json))
        assertEquals("15.7%", stat(json, "Volatility (a year)")); assertEquals("1.06", stat(json, "Beta vs Nifty 50")); assertEquals("₹7,610 · 1.63%", stat(json, "VaR 95%, 1 day"))
        assertEquals("₹6,369 · 1.36%", stat(json, "Worst 1 day in 20 (actual)")); assertEquals("−2.10% on 2026-10-08", stat(json, "Worst day in the period")); assertEquals("12.5%", stat(json, "Nifty 50 volatility"))
        val d = TermTesting.viz(json, "scatter"); val pts = d.getAsJsonArray("points").map { it.asJsonObject }
        assertEquals("Beta vs Nifty 50", d["xLabel"].asString); assertEquals("Volatility (% a year)", d["yLabel"].asString)
        assertEquals(7, pts.size)                                                // 5 holdings + the portfolio + the index
        val rel = pts.first { it["label"].asString == "RELIANCE" }
        assertEquals(1.25, rel["x"].asDouble, 1e-9); assertEquals(27.84, rel["y"].asDouble, 1e-9); assertEquals(14.38, rel["size"].asDouble, 1e-9)
        assertEquals(1.06, pts.first { it["label"].asString == "Portfolio" }["x"].asDouble, 1e-9); assertEquals(1.0, pts.first { it["label"].asString == "Nifty 50" }["x"].asDouble, 1e-9)
        assertEquals("Your portfolio (5 of 5 holdings priced, 89 trading days of daily prices): volatility 15.7% a year, beta 1.06 vs Nifty 50, 1-day 95% value-at-risk ₹7,610 (1.63% of ₹4,67,591). " +
            "An estimate from past prices, not a limit on losses. Source: Yahoo Finance prices; your Kite holdings.", summary(json))
        // a holding the feed does not know is left out and named; no index → no beta and no beta chart, but volatility and VaR still stand
        val part = FolioRiskFeature.build(holdings + h("UNLISTED", 10.0, 100.0, 100.0), charts + ("UNLISTED" to notFound), "")
        assertTrue(summary(part), summary(part).contains("5 of 6 holdings priced (99.8% of value)") && !summary(part).contains("beta"))
        assertTrue(TermTesting.blocks(part).none { it["type"].asString == "viz" })
        assertTrue(TermTesting.blocks(part).first { it["type"].asString == "note" }["text"].asString.contains("Not priced and left out: UNLISTED."))
        assertTrue(TermTesting.error(FolioRiskFeature.build(holdings, emptyMap(), ""))!!.contains("Couldn't get enough price history"))
        assertTrue(TermTesting.error(FolioRiskFeature.build(holdings, charts.mapValues { "<html>blocked</html>" }, nifty))!!.contains("Couldn't get enough price history"))
    }

    @Test fun correlation() {
        val json = FolioCorrelationFeature.build(holdings, charts)
        val d = TermTesting.viz(json, "heatgrid")
        val labels = listOf("HDFCBANK", "ITC", "TATASTEEL", "INFY", "RELIANCE")
        assertEquals(labels, d.getAsJsonArray("rows").map { it.asString }); assertEquals(labels, d.getAsJsonArray("cols").map { it.asString })
        assertEquals(-1, d["min"].asInt); assertEquals(1, d["max"].asInt); assertEquals("correlation", d["unit"].asString)
        val cells = d.getAsJsonArray("cells").map { row -> row.asJsonArray.map { it.asDouble } }
        assertEquals(listOf(1.0, 0.24, 0.14, 0.34, 0.26), cells[0]); assertEquals(listOf(0.26, 0.3, 0.47, 0.03, 1.0), cells[4])
        for (i in cells.indices) for (j in cells.indices) assertEquals(cells[i][j], cells[j][i], 0.0)          // symmetric, 1 on the diagonal
        assertEquals("0.22 average correlation", headline(json))
        assertEquals("Daily returns of your 5 largest holdings over 89 trading days: average pairwise correlation 0.22. Most alike: TATASTEEL and RELIANCE at 0.47; least alike: TATASTEEL and INFY at 0.03. " +
            "Source: Yahoo Finance prices; your Kite holdings.", summary(json))
        // only the 8 largest are asked for and shown
        val many = (1..10).map { h("STK$it", 10.0, 100.0, 100.0 + it) }
        val fake = TermTesting.Fake(charts = many.associate { it.name + ".NS" to charts.getValue("ITC") }, holdings = many)
        val mj = runBlocking { FolioCorrelationFeature.run("", fake) }
        assertEquals(8, fake.asked.size); assertEquals(8, TermTesting.viz(mj, "heatgrid").getAsJsonArray("rows").size())
        assertTrue(TermTesting.error(FolioCorrelationFeature.build(holdings.take(1), charts))!!.contains("at least two holdings"))
        assertTrue(TermTesting.error(FolioCorrelationFeature.build(holdings, charts.filterKeys { it == "ITC" }))!!.contains("Couldn't get enough price history"))     // one priced holding is not a matrix
        assertEquals("moved closely together", FolioCorrelationFeature.describe(0.8)); assertEquals("little relation", FolioCorrelationFeature.describe(0.03)); assertEquals("tended to move opposite ways", FolioCorrelationFeature.describe(-0.4))
    }

    @Test fun riskReturn() {
        val json = FolioRiskReturnFeature.build(holdings, charts, nifty)
        assertEquals("+3.2% at 15.7% volatility", headline(json))
        val d = TermTesting.viz(json, "scatter"); val pts = d.getAsJsonArray("points").map { it.asJsonObject }
        assertEquals("Volatility (% a year)", d["xLabel"].asString); assertEquals("Return over 89 trading days (%)", d["yLabel"].asString); assertEquals("Bubble = weight", d["sizeLabel"].asString)
        val infy = pts.first { it["label"].asString == "INFY" }; val steel = pts.first { it["label"].asString == "TATASTEEL" }
        assertEquals(31.53, infy["x"].asDouble, 1e-9); assertEquals(8.97, infy["y"].asDouble, 1e-9); assertEquals(14.69, infy["size"].asDouble, 1e-9); assertEquals("green", infy["color"].asString)
        assertEquals(-3.01, steel["y"].asDouble, 1e-9); assertEquals("red", steel["color"].asString)
        assertEquals(100.0, pts.filter { it.has("size") }.sumOf { it["size"].asDouble }, 0.02)
        assertEquals(12.54, pts.first { it["label"].asString == "Nifty 50" }["x"].asDouble, 1e-9); assertEquals(5.49, pts.first { it["label"].asString == "Nifty 50" }["y"].asDouble, 1e-9)
        assertEquals("Over 89 trading days, today's holdings (5 of 5 holdings priced) returned +3.2% at 15.7% yearly volatility; the Nifty 50 returned +5.5% at 12.5%. " +
            "Highest return INFY +9.0%, lowest TATASTEEL −3.0%. Price returns, not your own. Source: Yahoo Finance prices; your Kite holdings.", summary(json))
        val noIndex = FolioRiskReturnFeature.build(holdings, charts, notFound)
        assertEquals(6, TermTesting.viz(noIndex, "scatter").getAsJsonArray("points").size()); assertFalse(summary(noIndex).contains("Nifty"))
        assertTrue(TermTesting.error(FolioRiskReturnFeature.build(holdings, emptyMap(), nifty))!!.contains("Couldn't get enough price history"))
    }

    @Test fun stress() {
        val json = FolioStressFeature.build(holdings, charts, nifty)
        val d = TermTesting.viz(json, "groupbars"); val items = d.getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(listOf("−5%", "−10%", "−20%", "−30%"), items.map { it["label"].asString }); assertEquals(2, d.getAsJsonArray("series").size()); assertEquals("Nifty 50 fall", d["xLabel"].asString)
        assertEquals(listOf(444211.45, 420831.9, 374072.8, 327313.7), items.map { it.getAsJsonArray("values")[0].asDouble })       // moving exactly with the market
        assertEquals(listOf(442829.16, 418067.32, 368543.64, 319019.96), items.map { it.getAsJsonArray("values")[1].asDouble })    // with each holding's beta
        assertEquals("−₹49,524", headline(json)); assertEquals("1.06", stat(json, "Portfolio beta"))
        assertEquals("RELIANCE · beta 1.25", stat(json, "Most sensitive")); assertEquals("TATASTEEL · beta 0.91", stat(json, "Least sensitive"))
        assertEquals("Stress test with betas from 89 trading days (portfolio beta 1.06): if the Nifty 50 fell 10%, your ₹4,67,591 portfolio would be worth about ₹4,18,067 (−₹49,524); " +
            "at −20% about ₹3,68,544, at −30% about ₹3,19,020. A what-if from past betas, not a forecast. Source: Yahoo Finance prices; your Kite holdings.", summary(json))
        // an unpriced holding is assumed to move with the market, and the card says so
        val extra = FolioStressFeature.build(holdings + h("UNLISTED", 100.0, 100.0, 100.0), charts, nifty)
        assertEquals(418067.32 + 9000.0, TermTesting.viz(extra, "groupbars").getAsJsonArray("items")[1].asJsonObject.getAsJsonArray("values")[1].asDouble, 0.01)
        assertTrue(TermTesting.blocks(extra).first { it["type"].asString == "note" }["text"].asString.contains("(UNLISTED) are assumed to fall exactly with the market"))
        assertTrue(TermTesting.error(FolioStressFeature.build(holdings, charts, ""))!!.contains("Couldn't get Nifty 50 prices"))
        assertTrue(TermTesting.error(FolioStressFeature.build(holdings, emptyMap(), nifty))!!.contains("Couldn't get enough price history"))
    }

    @Test fun income() {
        assertEquals(listOf(LocalDate.of(2026, 2, 4) to 6.5, LocalDate.of(2026, 5, 27) to 8.0), FolioIncomeFeature.dividends(charts.getValue("ITC"), today))
        assertEquals(listOf(LocalDate.of(2026, 5, 27) to 8.0), FolioIncomeFeature.dividends(charts.getValue("ITC"), LocalDate.of(2027, 2, 4)))      // a payout exactly a year old has dropped out
        assertEquals(emptyList<Any>(), FolioIncomeFeature.dividends(nifty, today)); assertNull(FolioIncomeFeature.dividends(notFound, today)); assertNull(FolioIncomeFeature.dividends("<html>", today))
        val json = FolioIncomeFeature.build(holdings, charts, today)
        assertEquals("₹13,110 a year", headline(json))
        assertEquals("2.80%", stat(json, "Yield on today's value")); assertEquals("2.95%", stat(json, "Yield on your cost")); assertEquals("ITC ₹5,800", stat(json, "Largest payer"))
        val items = TermTesting.viz(json, "bars").getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(listOf("ITC" to 5800.0, "INFY" to 3060.0, "TATASTEEL" to 2000.0, "HDFCBANK" to 1950.0, "RELIANCE" to 300.0), items.map { it["label"].asString to it["value"].asDouble })
        val rows = TermTesting.blocks(json).first { it["type"].asString == "rows" }.getAsJsonArray("rows").map { it.asJsonObject }
        assertEquals("₹51.00 a share · 2 payouts · yield 4.45%", rows[1]["sub"].asString); assertEquals("₹13.00 a share · 1 payout · yield 1.59%", rows[3]["sub"].asString)
        assertEquals("Estimated dividend income from your holdings (5 of 5 holdings checked): ₹13,110 a year, a yield of 2.80% on today's value of ₹4,67,591; 5 of 5 paid in the last 12 months, the largest ITC at ₹5,800. " +
            "Based on today's quantities, before tax. Source: Yahoo Finance dividend history; your Kite holdings.", summary(json))
        // nobody paid → still a card, with no chart; nothing readable → an error
        val none = FolioIncomeFeature.build(holdings.take(2), mapOf("ITC" to nifty, "INFY" to nifty), today)
        assertEquals("₹0 a year", headline(none)); assertTrue(TermTesting.blocks(none).none { it["type"].asString == "viz" }); assertEquals("none", stat(none, "Largest payer"))
        val part = FolioIncomeFeature.build(holdings, charts - "RELIANCE" + ("INFY" to notFound), today)
        assertTrue(summary(part), summary(part).contains("3 of 5 holdings checked") && summary(part).contains("₹9,750 a year"))
        assertTrue(TermTesting.blocks(part).first { it["type"].asString == "note" }["text"].asString.contains("No dividend data for: INFY, RELIANCE."))
        assertTrue(TermTesting.error(FolioIncomeFeature.build(holdings, emptyMap(), today))!!.contains("Couldn't get dividend history"))
        assertNotNull(FolioIncomeFeature.dividends("""{"chart":{"result":[{"events":{"dividends":{"1":{"amount":"x"},"2":7}}}]}}""", today))        // malformed entries are skipped, not fatal
    }
}
