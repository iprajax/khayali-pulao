package com.khayali.pulao.term

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.khayali.pulao.ai.ChatFlow
import com.khayali.pulao.ai.term.GlobalCommoditiesFeature
import com.khayali.pulao.ai.term.GlobalCryptoFeature
import com.khayali.pulao.ai.term.GlobalFxFeature
import com.khayali.pulao.ai.term.GlobalGdpFeature
import com.khayali.pulao.ai.term.GlobalGoldFeature
import com.khayali.pulao.ai.term.GlobalInflationFeature
import com.khayali.pulao.ai.term.GlobalMarketsFeature
import com.khayali.pulao.ai.term.GlobalVixFeature
import com.khayali.pulao.ai.term.GlobalWorldBank
import com.khayali.pulao.ai.term.GlobalYieldsFeature
import com.khayali.pulao.ai.term.Hosts
import com.khayali.pulao.ai.term.Terminal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The global-markets pack, checked against trimmed copies of real feed replies (captured 17 Nov 2026). */
class GlobalTest {
    /** A fixture holding several chart replies, keyed by feed symbol. */
    private fun bundle(name: String): Map<String, String> = JsonParser.parseString(TermTesting.fixture(name)).asJsonObject.entrySet().associate { it.key to it.value.toString() }
    private fun summary(json: String) = TermTesting.card(json)["summary"].asString
    private fun card(json: String) = TermTesting.card(json).getAsJsonObject("card")
    private fun items(json: String, type: String = "sparkgrid") = TermTesting.viz(json, type).getAsJsonArray("items").map { it.asJsonObject }
    private fun rows(json: String) = TermTesting.blocks(json).first { it["type"].asString == "rows" }.getAsJsonArray("rows").map { it.asJsonObject }
    private fun stats(json: String) = TermTesting.blocks(json).first { it["type"].asString == "stats" }.getAsJsonArray("items").associate { it.asJsonObject["label"].asString to it.asJsonObject["value"].asString }
    private fun note(json: String) = TermTesting.blocks(json).first { it["type"].asString == "note" }["text"].asString
    private fun JsonObject.last() = getAsJsonArray("v").last().asDouble
    private val closed = """{"chart":{"result":[{"meta":{"symbol":"X"},"indicators":{"quote":[{}]}}],"error":null}}"""        // what the feed sends when it has no bars
    private val missing = """{"chart":{"result":null,"error":{"code":"Not Found","description":"No data found, symbol may be delisted"}}}"""

    /** Every card in this pack names its source, offers follow-ups, labels its chart and never gives advice. */
    private fun wellFormed(json: String) {
        assertNull(json, TermTesting.error(json))
        val root = TermTesting.card(json); val c = card(json)
        assertTrue(c["kicker"].asString.isNotBlank() && c["headline"].asString.isNotBlank() && c["sub"].asString.isNotBlank())
        assertTrue(root.getAsJsonArray("pills").size() in 2..4); assertTrue(root.getAsJsonArray("pills").all { it.asString.startsWith("/") })
        assertTrue(root.getAsJsonArray("sources").size() >= 1)
        assertTrue(summary(json), summary(json).contains("Source: "))
        val charts = TermTesting.blocks(json).filter { it["type"].asString == "viz" }
        assertTrue(charts.size in 1..2); charts.forEach { assertTrue(it["caption"].asString.length > 20) }
        assertFalse(json, Regex("""(?i)\b(you should|buy now|sell now|target price|recommend|good time to)\b""").containsMatchIn(json))
    }

    @Test fun worldIndices() {
        val json = GlobalMarketsFeature.build(bundle("global_indices.json"))
        wellFormed(json)
        val it = items(json)
        assertEquals(listOf("S&P 500", "Nasdaq", "Dow Jones", "FTSE 100", "DAX", "Nikkei 225", "Hang Seng", "Shanghai", "Nifty 50", "Sensex"), it.map { p -> p["label"].asString })
        val sp = it.first()
        assertEquals(7416.59, sp.last(), 1e-9); assertEquals(-1.9186, sp["change"].asDouble, 1e-4)
        assertEquals(21, sp.getAsJsonArray("v").size()); assertEquals(21, sp.getAsJsonArray("t").size()); assertEquals("2026-11-16", sp.getAsJsonArray("t").last().asString)
        assertEquals(2, TermTesting.viz(json, "sparkgrid")["cols"].asInt)
        assertEquals("1 of 10 up", card(json)["headline"].asString)
        assertEquals("Best Sensex +0.63% · weakest Nasdaq −2.49%", card(json)["sub"].asString)
        assertEquals("Latest session: 1 of 10 world indices up; best Sensex +0.63%, weakest Nasdaq −2.49%. S&P 500 7,416.59 (−1.92%), Nikkei 225 58,956.67 (−1.18%), Nifty 50 25,277.30 (−0.05%). Source: Yahoo Finance.", summary(json))
        val r = rows(json)
        assertEquals(10, r.size); assertEquals("Sensex", r.first()["title"].asString); assertEquals("+9.9%", r.first()["value"].asString); assertEquals("Nikkei 225", r.last()["title"].asString)
        assertTrue(note(json), note(json).contains("newest: 17 Nov 2026") && !note(json).contains("No data just now"))
    }

    @Test fun worldIndicesWithPartialOrNoData() {
        val all = bundle("global_indices.json")
        val some = GlobalMarketsFeature.build(mapOf("^NSEI" to all.getValue("^NSEI"), "^GSPC" to "<html>blocked</html>", "^FTSE" to closed, "^DJI" to missing))
        wellFormed(some)
        assertEquals(listOf("Nifty 50"), items(some).map { it["label"].asString })
        assertTrue(note(some), note(some).contains("No data just now for: S&P 500, Nasdaq, Dow Jones"))
        assertTrue(summary(some), summary(some).startsWith("Latest session: 0 of 1 world indices up") && summary(some).contains("Nifty 50 25,277.30 (−0.05%)"))
        assertTrue(TermTesting.error(GlobalMarketsFeature.build(emptyMap()))!!.contains("Couldn't load world indices"))
        assertTrue(TermTesting.error(GlobalMarketsFeature.build(mapOf("^NSEI" to closed)))!!.contains("Couldn't load world indices"))
        val fake = TermTesting.Fake(charts = all)
        wellFormed(runBlocking { GlobalMarketsFeature.run("", fake) })
        assertEquals(10, fake.asked.size); assertTrue(fake.asked.contains("chart:000001.SS:1mo:1d"))
        assertTrue(TermTesting.error(runBlocking { GlobalMarketsFeature.run("", TermTesting.Fake()) })!!.contains("Couldn't load"))     // dead network
    }

    @Test fun rupeeRates() {
        val json = GlobalFxFeature.build(bundle("global_fx.json"))
        wellFormed(json)
        assertEquals("₹93.68 per US dollar", card(json)["headline"].asString)
        assertEquals("Day −0.59% · 1 year +4.83% · a higher figure means a weaker rupee", card(json)["sub"].asString)
        val line = items(json).single()
        assertEquals("USD/INR", line["label"].asString); assertEquals(93.6775, line.last(), 1e-9); assertEquals(89.3648, line.getAsJsonArray("v").first().asDouble, 1e-9)
        assertEquals("₹", line["prefix"].asString); assertEquals("1 yr", line["changeLabel"].asString); assertEquals(4.8259, line["change"].asDouble, 1e-4)
        val r = rows(json)
        assertEquals(listOf("US dollar", "Euro", "British pound", "100 Japanese yen", "UAE dirham", "Singapore dollar"), r.map { it["title"].asString })
        assertEquals("₹58.77 · +2.67%", r[3]["value"].asString)                 // the yen is quoted per 100
        assertEquals("EUR · 1 year −0.47%", r[1]["sub"].asString)
        assertEquals("₹96.88 (24 Jul 2026)", stats(json).getValue("USD/INR 1-year high"))
        assertEquals("USD/INR ₹93.68 on 17 Nov 2026 (day −0.59%, 1 year +4.83%; a higher figure means a weaker rupee); EUR ₹103.09, GBP ₹123.59, 100 Japanese yen ₹58.77, AED ₹25.47, SGD ₹72.73. Indicative rates. Source: Yahoo Finance.", summary(json))
        // the dollar rate is the anchor: without it there is no card; without the others the card still stands
        assertTrue(TermTesting.error(GlobalFxFeature.build(bundle("global_fx.json") - "USDINR=X"))!!.contains("exchange rates"))
        assertTrue(TermTesting.error(GlobalFxFeature.build(mapOf("USDINR=X" to "")))!!.contains("exchange rates"))
        val only = GlobalFxFeature.build(mapOf("USDINR=X" to bundle("global_fx.json").getValue("USDINR=X"), "EURINR=X" to missing))
        wellFormed(only); assertEquals(1, rows(only).size); assertTrue(summary(only), summary(only).contains("weaker rupee). Indicative"))
        val fake = TermTesting.Fake(charts = bundle("global_fx.json"))
        wellFormed(runBlocking { GlobalFxFeature.run("", fake) }); assertEquals(GlobalFxFeature.SYMBOLS.map { "chart:$it:1y:1d" }.toSet(), fake.asked.toSet())
    }

    @Test fun commodities() {
        val json = GlobalCommoditiesFeature.build(bundle("global_commodities.json"))
        wellFormed(json)
        assertEquals("2 of 6 up on the day", card(json)["headline"].asString)
        assertEquals("Largest 1-month move: Silver −16.7%", card(json)["sub"].asString)
        val it = items(json)
        assertEquals(listOf("Gold", "Silver", "Crude oil (WTI)", "Brent crude", "Natural gas", "Copper"), it.map { p -> p["label"].asString })
        assertEquals(3877.6, it[0].last(), 1e-9); assertEquals("$", it[0]["prefix"].asString); assertEquals(3, it[4]["digits"].asInt); assertEquals(23, it[2].getAsJsonArray("v").size())
        val r = rows(json)
        assertEquals("$3,877.60 · +0.64%", r[0]["value"].asString); assertEquals("up", r[0]["tone"].asString)
        assertEquals("$3.105 · −0.54%", r[4]["value"].asString); assertEquals("per MMBtu · 1 month −0.4%", r[4]["sub"].asString); assertEquals("down", r[4]["tone"].asString)
        val s = summary(json)
        assertTrue(s, s.startsWith("Commodities on 17 Nov 2026: Gold $3,877.60 (day +0.64%, 1 month −7.3%), Silver $47.48 (day +1.47%, 1 month −16.7%), Crude oil (WTI) $85.07 (day −0.76%, 1 month −16.1%), Brent crude $91.72"))
        assertTrue(s, s.endsWith("US dollar futures, may be delayed. Source: Yahoo Finance."))
        assertTrue(TermTesting.error(GlobalCommoditiesFeature.build(mapOf("GC=F" to "", "CL=F" to "not json")))!!.contains("commodity prices"))
        val two = GlobalCommoditiesFeature.build(bundle("global_commodities.json").filterKeys { it == "GC=F" || it == "HG=F" })
        wellFormed(two); assertEquals(2, items(two).size); assertEquals("1 of 2 up on the day", card(two)["headline"].asString)
    }

    @Test fun goldInRupees() {
        assertEquals(116788.54, GlobalGoldFeature.per10g(3877.7, 93.6775), 0.01)
        val b = bundle("global_gold.json")
        val json = GlobalGoldFeature.build(b.getValue("GC=F"), b.getValue("USDINR=X"))
        wellFormed(json)
        assertEquals("₹1,16,789 per 10 g", card(json)["headline"].asString)
        assertEquals("Day +0.04% · 1 year +0.4% · before import duty and GST", card(json)["sub"].asString)
        assertTrue(TermTesting.viz(json, "sparkgrid")["yLabel"].asString.contains("₹ per 10 g"))
        val line = items(json).single(); val t = line.getAsJsonArray("t"); val v = line.getAsJsonArray("v")
        assertEquals(51, v.size()); assertEquals(51, t.size()); assertEquals("2025-11-21", t.first().asString); assertEquals(116328.0, v.first().asDouble, 0.0)
        assertEquals("2026-11-17", t.last().asString); assertEquals(116789.0, v.last().asDouble, 0.0); assertEquals("₹", line["prefix"].asString); assertEquals(0.396, line["change"].asDouble, 1e-3)
        val st = stats(json)
        assertEquals("₹11,679", st.getValue("Per gram")); assertEquals("$3,877.70", st.getValue("Gold (US$ per troy ounce)")); assertEquals("₹93.68", st.getValue("USD/INR used"))
        assertEquals("₹1,53,551 (27 Feb 2026)", st.getValue("1-year high"))
        assertTrue(note(json), note(json).contains("import duty and GST") && note(json).contains("÷ 31.1035 × 10"))
        assertEquals("Gold works out to ₹1,16,789 per 10 g on 17 Nov 2026 (day +0.04%, 1 year +0.4%), derived from $3,877.70 per troy ounce at USD/INR ₹93.68. Indian retail prices also include import duty and GST. Source: Yahoo Finance.", summary(json))
        assertTrue(TermTesting.error(GlobalGoldFeature.build("", b.getValue("USDINR=X")))!!.contains("the gold price"))
        assertTrue(TermTesting.error(GlobalGoldFeature.build(b.getValue("GC=F"), closed))!!.contains("the USD/INR rate"))
        assertTrue(TermTesting.error(GlobalGoldFeature.build("<html>", "<html>"))!!.contains("Couldn't load"))
        val fake = TermTesting.Fake(charts = b)
        wellFormed(runBlocking { GlobalGoldFeature.run("", fake) }); assertEquals(setOf("chart:GC=F:1y:1d", "chart:USDINR=X:1y:1d"), fake.asked.toSet())
    }

    @Test fun goldNeedsOverlappingHistory() {
        fun chart(ts: List<Long>, c: List<Double>) = """{"chart":{"result":[{"meta":{"symbol":"T"},"timestamp":$ts,"indicators":{"quote":[{"open":$c,"high":$c,"low":$c,"close":$c,"volume":$c}]}}]}}"""
        // gold bars that are all older than the first exchange rate cannot be converted
        val json = GlobalGoldFeature.build(chart(listOf(1790000000, 1790086400), listOf(4000.0, 4010.0)), chart(listOf(1794000000, 1794086400), listOf(93.0, 94.0)))
        assertTrue(TermTesting.error(json)!!.contains("Not enough overlapping"))
        // a gold day with no rate of its own (a currency holiday) uses the latest earlier rate
        val ok = GlobalGoldFeature.build(chart(listOf(1794000000, 1794086400, 1794172800), listOf(4000.0, 4000.0, 4000.0)), chart(listOf(1794000000, 1794172800), listOf(90.0, 93.0)))
        val pts = items(ok).single().getAsJsonArray("v").map { it.asDouble }
        assertEquals(listOf(115743.0, 115743.0, 119601.0), pts)
    }

    @Test fun crypto() {
        val json = GlobalCryptoFeature.build(bundle("global_crypto.json"))
        wellFormed(json)
        assertEquals("$64,929.62 bitcoin", card(json)["headline"].asString)
        // the feed had no bar for 16 Nov, so the change is labelled with the close it is measured from
        assertEquals("−0.57% since the 15 Nov close · 1 month −20.2%", card(json)["sub"].asString)
        val it = items(json)
        assertEquals(listOf("Bitcoin (US$)", "Ether (US$)", "Bitcoin (₹)", "Ether (₹)"), it.map { p -> p["label"].asString })
        assertEquals(31, it[0].getAsJsonArray("v").size()); assertEquals(6090146.5, it[2].last(), 1e-6); assertEquals("₹", it[2]["prefix"].asString); assertEquals(0, it[2]["digits"].asInt)
        assertEquals("₹60,90,147 · −1.06%", rows(json)[2]["value"].asString); assertEquals("$1,829.98 · −0.30%", rows(json)[1]["value"].asString)
        assertTrue(note(json), note(json).startsWith(GlobalCryptoFeature.RISK))
        val s = summary(json)
        assertTrue(s, s.startsWith("Crypto on 17 Nov 2026: Bitcoin (US$) $64,929.62 (−0.57% since the 15 Nov close, 1 month −20.2%), Ether (US$) $1,829.98 (−0.30% since the 15 Nov close, 1 month −23.7%), Bitcoin (₹) ₹60,90,147"))
        assertTrue(s, s.endsWith("Indicative prices; crypto is highly volatile. Source: Yahoo Finance."))
        assertTrue(TermTesting.error(GlobalCryptoFeature.build(mapOf("BTC-USD" to missing)))!!.contains("crypto prices"))
    }

    @Test fun indiaVix() {
        assertEquals(0.9877, GlobalVixFeature.implied(15.68, 252.0), 1e-4)
        val json = GlobalVixFeature.build(bundle("global_vix.json").getValue("^INDIAVIX"))
        wellFormed(json)
        assertEquals("15.68", card(json)["headline"].asString)
        assertEquals("Day +1.16% · 37% of the way up its 1-year range · implies roughly ±0.99% a day", card(json)["sub"].asString)      // (15.68 − 9.15) ÷ (26.80 − 9.15)
        val p = items(json).single()
        assertEquals(49, p.getAsJsonArray("v").size()); assertEquals(15.68, p.last(), 1e-9); assertEquals("saffron", p["color"].asString)
        val st = stats(json)
        assertEquals("±0.99%", st.getValue("Implied daily move")); assertEquals("±2.17%", st.getValue("Implied weekly move")); assertEquals("±4.53%", st.getValue("Implied monthly move"))
        assertEquals("9.15 (26 Dec 2025)", st.getValue("1-year low")); assertEquals("26.80 (27 Mar 2026)", st.getValue("1-year high")); assertEquals("14.34", st.getValue("1-year average"))
        assertEquals("India VIX is 15.68 on 16 Nov 2026 (day +1.16%), 37% of the way up its 1-year closing range of 9.15–26.80 and above its 1-year average of 14.34. " +
            "That level implies an approximate daily Nifty move of ±0.99% (VIX ÷ √252), in either direction. Source: Yahoo Finance.", summary(json))
        assertTrue(note(json).contains("nothing about direction"))
        listOf("", "<html>", closed, missing).forEach { assertTrue(TermTesting.error(GlobalVixFeature.build(it))!!.contains("India VIX")) }
        val fake = TermTesting.Fake(charts = bundle("global_vix.json"))
        wellFormed(runBlocking { GlobalVixFeature.run("", fake) }); assertEquals(listOf("chart:^INDIAVIX:1y:1d"), fake.asked)
    }

    @Test fun yieldsAndDollar() {
        assertEquals("+97 bp", GlobalYieldsFeature.bp(4.15, 5.12)); assertEquals("−3 bp", GlobalYieldsFeature.bp(5.154, 5.121)); assertEquals("0 bp", GlobalYieldsFeature.bp(4.122, 4.125))
        val json = GlobalYieldsFeature.build(bundle("global_yields.json"))
        wellFormed(json)
        assertEquals("5.12% US 10-year", card(json)["headline"].asString)
        assertEquals("Day −3 bp · 1 year +106 bp · 13-week bill 4.13% · dollar index 103.52", card(json)["sub"].asString)
        val it = items(json)
        assertEquals(listOf("US 10-year Treasury yield", "US 13-week T-bill yield", "US dollar index (DXY)"), it.map { p -> p["label"].asString })
        assertEquals("+106 bp", it[0]["changeText"].asString); assertEquals("%", it[0]["suffix"].asString); assertEquals(5.121, it[0].last(), 1e-9)
        assertEquals("+38 bp", it[1]["changeText"].asString); assertFalse(it[2].has("changeText")); assertEquals(3.329, it[2]["change"].asDouble, 1e-3)
        assertEquals(1, TermTesting.viz(json, "sparkgrid")["cols"].asInt)
        val st = stats(json)
        assertEquals("3.96% – 5.28%", st.getValue("10-year: 1-year range")); assertEquals("+100 bp", st.getValue("10-year minus 13-week")); assertEquals("96.88 – 103.55", st.getValue("Dollar index: 1-year range"))
        assertEquals("US 10-year yield 5.12% (day −3 bp, 1 year +106 bp); 13-week bill 4.13% (1 year +38 bp); dollar index 103.52 (day −0.03%, 1 year +3.3%) on 16 Nov 2026. Source: Yahoo Finance.", summary(json))
        assertTrue(note(json).contains("13-week Treasury bill (^IRX) rather than the 2-year note"))
        val dollarOnly = GlobalYieldsFeature.build(bundle("global_yields.json").filterKeys { it == "DX-Y.NYB" })
        wellFormed(dollarOnly); assertEquals("103.52 dollar index", card(dollarOnly)["headline"].asString); assertEquals(1, items(dollarOnly).size)
        assertTrue(summary(dollarOnly), summary(dollarOnly).startsWith("Dollar index 103.52 (day −0.03%, 1 year +3.3%) on 17 Nov 2026."))
        assertTrue(TermTesting.error(GlobalYieldsFeature.build(mapOf("^TNX" to closed)))!!.contains("US yields"))
    }

    @Test fun inflationByYear() {
        val wb = GlobalWorldBank.parse(TermTesting.fixture("global_wb_cpi.json"))!!
        assertEquals(30, wb.points.size); assertEquals(1996 to 8.97715233826452, wb.points.first()); assertEquals("Inflation, consumer prices (annual %)", wb.name); assertEquals("2026-11-11", wb.updated)
        val json = GlobalInflationFeature.build(TermTesting.fixture("global_wb_cpi.json"))
        wellFormed(json)
        assertEquals("2.4% in 2025", card(json)["headline"].asString)
        assertEquals("10-year average 4.7% (2016–2025) · 2024: 5.0%", card(json)["sub"].asString)
        val d = TermTesting.viz(json, "yearbars"); val bars = d.getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(15, bars.size); assertEquals("2011", bars.first()["label"].asString); assertEquals("2025", bars.last()["label"].asString)
        assertEquals(2.39884954182294, bars.last()["value"].asDouble, 1e-12); assertEquals(4.739962854702831, d["avg"].asDouble, 1e-9)
        assertEquals("%", d["unit"].asString); assertEquals("CPI inflation (% a year)", d["yLabel"].asString); assertEquals("Year", d["xLabel"].asString)
        assertEquals("10.0% (2013)", stats(json).getValue("Highest since 2011")); assertEquals("2.4% (2025)", stats(json).getValue("Lowest since 2011"))
        assertEquals("India's CPI inflation was 2.4% in 2025 (5.0% in 2024); the 10-year average for 2016–2025 is 4.7%. Source: World Bank (FP.CPI.TOTL.ZG).", summary(json))
        assertTrue(note(json).contains("last updated 2026-11-11"))
    }

    @Test fun gdpGrowthByYear() {
        val json = GlobalGdpFeature.build(TermTesting.fixture("global_wb_gdp.json"))
        wellFormed(json)
        assertEquals("7.6% in 2025", card(json)["headline"].asString)
        val bars = TermTesting.viz(json, "yearbars").getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(-5.77772470687303, bars.first { it["label"].asString == "2020" }["value"].asDouble, 1e-12)
        assertEquals("−5.8% (2020)", stats(json).getValue("Lowest since 2011")); assertEquals("9.7% (2021)", stats(json).getValue("Highest since 2011"))
        assertEquals("India's real GDP grew 7.6% in 2025 (7.1% in 2024); the 10-year average for 2016–2025 is 5.9%. Source: World Bank (NY.GDP.MKTP.KD.ZG).", summary(json))
        assertTrue(note(json).contains("fiscal year (April–March)"))
        // a contraction is worded as one, and a short series says how many years its average covers
        val short = GlobalGdpFeature.build("""[{"page":1,"lastupdated":""},[{"date":"2021","value":null},{"date":"2020","value":-5.78},{"date":"2019","value":3.87},{"date":"bad","value":1}]]""")
        assertEquals("India's real GDP shrank 5.8% in 2020 (3.9% in 2019); the 2-year average for 2019–2020 is −1.0%. Source: World Bank (NY.GDP.MKTP.KD.ZG).", summary(short))
    }

    @Test fun worldBankRepliesThatCarryNoData() {
        val invalid = """[{"message":[{"id":"120","key":"Invalid value","value":"The provided parameter value is not valid"}]}]"""      // the real reply to an unknown indicator
        listOf("", "<html>", "{}", "[]", invalid, """[{"page":0,"total":0},null]""", """[{"page":1},[{"date":"2025","value":null}]]""").forEach {
            assertNull(it, GlobalWorldBank.parse(it))
            assertTrue(TermTesting.error(GlobalInflationFeature.build(it))!!.contains("World Bank")); assertTrue(TermTesting.error(GlobalGdpFeature.build(it))!!.contains("World Bank"))
        }
        // the host is allow-listed, the URL is built from it, and a refused or dead fetch becomes a plain error card
        assertTrue(Hosts.allowed(GlobalWorldBank.url(GlobalInflationFeature.INDICATOR)))
        val fake = TermTesting.Fake(pages = mapOf("indicator/FP.CPI.TOTL.ZG" to TermTesting.fixture("global_wb_cpi.json"), "indicator/NY.GDP.MKTP.KD.ZG" to TermTesting.fixture("global_wb_gdp.json")))
        wellFormed(runBlocking { GlobalInflationFeature.run("", fake) }); wellFormed(runBlocking { GlobalGdpFeature.run("", fake) })
        assertEquals(listOf("get:https://api.worldbank.org/v2/country/IN/indicator/FP.CPI.TOTL.ZG?format=json&per_page=30", "get:https://api.worldbank.org/v2/country/IN/indicator/NY.GDP.MKTP.KD.ZG?format=json&per_page=30"), fake.asked)
        assertTrue(TermTesting.error(runBlocking { GlobalGdpFeature.run("", TermTesting.Fake()) })!!.contains("World Bank"))
    }

    @Test fun commandsAndPlainPhrasesReachThePack() {
        val on = ChatFlow.Env(modelReady = true, kite = ChatFlow.Kite.OFF, webOn = true)
        listOf("global", "fx", "commodities", "gold", "crypto", "vix", "inflation", "gdp", "yields").forEach {
            assertEquals(ChatFlow.Action.Term(it, ""), ChatFlow.route("/$it", on)); assertTrue(Terminal.find(it)!!.needsWeb)
        }
        mapOf("how are global markets doing" to "global", "what is the usd inr rate" to "fx", "dollar to rupee today" to "fx", "crude oil price" to "commodities", "gold rate today" to "gold",
            "bitcoin price" to "crypto", "where is india vix" to "vix", "india's inflation" to "inflation", "india gdp growth" to "gdp", "us 10 year yield" to "yields", "dollar index" to "yields")
            .forEach { (text, id) -> assertEquals(text, id, Terminal.match(text)?.first?.id) }
        // ordinary words on their own do not trigger a card
        listOf("should I hold gold or equity", "is the world ending", "my returns after inflation", "dollar cost averaging").forEach { assertNull(it, Terminal.match(it)) }
    }
}
