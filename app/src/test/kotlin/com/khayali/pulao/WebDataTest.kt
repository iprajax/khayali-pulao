package com.khayali.pulao

import com.google.gson.JsonParser
import com.khayali.pulao.ai.NumberGuard
import com.khayali.pulao.ai.ToolJson
import com.khayali.pulao.ai.WebData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class WebDataTest {
    private val page = File("src/test/resources/ddg_results.html").readText()

    @Test fun queriesNeverCarryAmountsOrIdentifiers() {
        assertEquals("INFRA share price", WebData.sanitize("INFRA share price ₹3,62,500"))
        assertEquals("should I worry about my shares of INFRA", WebData.sanitize("should I worry about my 250000 shares of INFRA"))
        assertEquals("grow to in INFRA", WebData.sanitize("grow 5 lakh to 1.5 crore in INFRA"))
        assertEquals("contact about INFRA", WebData.sanitize("contact someone@example.com about INFRA"))
        assertEquals("INFRA Q3 FY27 results 2026", WebData.sanitize("INFRA Q3 FY27 results 2026"))     // short numbers that are part of the topic stay
        assertTrue(WebData.sanitize("x".repeat(400)).length <= 120)
        assertFalse(WebData.searchUrl("INFRA ₹9,99,999").contains("999"))
    }

    @Test fun encodeDecodeRoundTrip() {
        assertEquals("M%26M+Q3+results", WebData.encode("M&M Q3 results"))
        assertEquals("M&M Q3 results", WebData.decode(WebData.encode("M&M Q3 results")))
        assertEquals("https://example.com/a?b=1", WebData.unwrap("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1&amp;rut=abc"))
        assertNull(WebData.unwrap("javascript:alert(1)"))
    }

    @Test fun readsResultsAndSkipsAds() {
        val r = WebData.parseSearch(page)
        assertEquals(3, r.size)
        assertEquals(listOf(1, 2, 3), r.map { it.n })
        assertEquals("economictimes.indiatimes.com", r[0].site)
        assertTrue(r[0].url.startsWith("https://economictimes.indiatimes.com/"))
        assertTrue(r[0].title.startsWith("Infosys Share Price Today"))
        assertTrue(r[0].snippet, r[0].snippet.contains("Get live NSE/BSE quotes") && !r[0].snippet.contains("<"))
        assertTrue(r.none { it.url.contains("duckduckgo.com") })
        assertFalse(WebData.isChallenge(page))
        assertTrue(WebData.isChallenge("""<div class="anomaly-modal__title">Unfortunately, bots use DuckDuckGo too.</div>"""))
        assertEquals(emptyList<WebData.Result>(), WebData.parseSearch("<html></html>"))
    }

    @Test fun modelGetsNoLinksTheCardDoes() {
        val r = WebData.parseSearch(page)
        val forModel = WebData.searchJson("infosys", r, withUrls = false)
        val forCard = WebData.searchJson("infosys", r, withUrls = true)
        assertFalse(forModel.contains("https://"))
        assertEquals(r, WebData.resultsOf(forCard))
        assertTrue(WebData.extract(r).lines().all { Regex("""\[\d]$""").containsMatchIn(it) })       // every line is cited
        assertTrue(ToolJson.error(WebData.searchJson("zzz", emptyList(), false))!!.contains("No web results"))
    }

    @Test fun quoteSymbols() {
        assertEquals(listOf("INFRA.NS", "INFRA.BO"), WebData.candidates("nse:infra"))
        assertEquals(listOf("^NSEI"), WebData.candidates("Nifty 50"))
        assertEquals(listOf("^BSESN"), WebData.candidates("sensex"))
        assertEquals(listOf("M&M.NS", "M&M.BO"), WebData.candidates("M&M"))
        assertEquals(emptyList<String>(), WebData.candidates("Infra Builders Limited"))      // a name → looked up first
        assertEquals("INFRA.NS", WebData.parseLookup("""{"quotes":[{"exchange":"NYQ","symbol":"INFRA"},{"exchange":"BSE","symbol":"INFRA.BO"},{"exchange":"NSI","symbol":"INFRA.NS"}]}"""))
        assertNull(WebData.parseLookup("""{"quotes":[{"exchange":"NYQ","symbol":"X"}]}"""))
        assertTrue(WebData.chartUrl("^NSEI", "5y").contains("%5ENSEI?range=5y&interval=1wk"))
    }

    // Two sessions: 13 Nov closes 1,200; 14 Nov trades up to 1,230 (regularMarketTime is on the 14th, IST).
    private val chart = """{"chart":{"result":[{"meta":{"currency":"INR","symbol":"INFRA.NS","longName":"Infra Builders Limited","fullExchangeName":"NSE",
        "regularMarketPrice":1230.0,"chartPreviousClose":1100.0,"regularMarketDayHigh":1240.0,"regularMarketDayLow":1195.5,"fiftyTwoWeekHigh":1500.0,"fiftyTwoWeekLow":900.0,"regularMarketTime":1794650400},
        "timestamp":[1794541500,1794627900],
        "indicators":{"quote":[{"open":[1180.0,1201.0],"high":[1210.0,1240.0],"low":[1175.0,1195.5],"close":[1200.0,1230.0],"volume":[500000,650000]}]},
        "events":{"dividends":{"1793677500":{"amount":26.0,"date":1793677500}}}}],"error":null}}"""

    @Test fun readsQuoteHistoryAndDividends() {
        val o = JsonParser.parseString(WebData.parseChart(chart, "1m")).asJsonObject
        assertEquals("INFRA", o["symbol"].asString)
        assertEquals(1230.0, o["price"].asDouble, 1e-9)
        assertEquals(1200.0, o["prev_close"].asDouble, 1e-9)          // yesterday's close, not the period's first bar
        assertEquals(2.5, o["day_change_percent"].asDouble, 1e-9)
        assertEquals(1240.0, o["high"].asDouble, 1e-9); assertEquals(1175.0, o["low"].asDouble, 1e-9)
        assertEquals(2, o.getAsJsonArray("points").size())
        assertTrue(o.getAsJsonArray("points")[0].asJsonObject.has("o"))
        val s = o["summary"].asString
        assertTrue(s, s.contains("₹1,230.00") && s.contains("+2.5% on the day") && s.contains("Yahoo Finance") && s.contains("may be delayed"))
        assertEquals("Dividend", o.getAsJsonArray("events")[0].asJsonObject["kind"].asString)
        // every figure in the summary can be verified against the JSON (so the number guard accepts it)
        assertEquals(s, NumberGuard.grounded(s, o.toString().replace(s, "")).first.ifBlank { s })
    }

    @Test fun quoteErrorsAreReadable() {
        assertEquals("No data found, symbol may be delisted",
            ToolJson.error(WebData.parseChart("""{"chart":{"result":null,"error":{"code":"Not Found","description":"No data found, symbol may be delisted"}}}""", null)))
        assertTrue(ToolJson.error(WebData.parseChart("<html>busy</html>", null)) != null)
    }

    @Test fun findsDatedEventsInSnippets() {
        val results = listOf(
            WebData.Result(1, "Outcome of the Board meeting", "https://a.example/1", "a.example",
                "The Board, at their meeting held on October 22-23, 2026 transacted the following items of business."),
            WebData.Result(2, "INFRA results: common questions", "https://b.example/2", "b.example",
                "A year ago, the same quarter's results meeting was on Thursday, 16 October 2025. The company was founded in 1981."),
            WebData.Result(3, "INFRA AGM notice", "https://c.example/3", "c.example", "The 45th Annual General Meeting will be held on 24th July 2027 by video conference."),
            WebData.Result(4, "Blog", "https://d.example/4", "d.example", "Posted on 3 March 2026 by the editor."),      // a date with no event word
        )
        val ev = WebData.events(results)
        assertEquals(listOf(LocalDate.of(2025, 10, 16), LocalDate.of(2026, 10, 22), LocalDate.of(2027, 7, 24)), ev.map { it.date })
        assertEquals(listOf("Results", "Board meeting", "AGM"), ev.map { it.kind })
        assertEquals(listOf(2, 1, 3), ev.map { it.source })
        val o = JsonParser.parseString(WebData.eventsJson("infra", ev, results, today = LocalDate.of(2026, 11, 17))).asJsonObject
        assertTrue(o["summary"].asString, o["summary"].asString.contains("next dated event found is AGM on 24 Jul 2027") && o["summary"].asString.contains("most recent is Board meeting on 22 Oct 2026"))
        assertEquals(listOf(false, false, true), o.getAsJsonArray("events").map { it.asJsonObject["upcoming"].asBoolean })
        assertTrue(ToolJson.error(WebData.eventsJson("x", emptyList(), emptyList())) != null)
    }

    @Test fun webAnswersKeepOnlyFiguresFoundInTheSources() {
        val corpus = """Infra Builders reported revenue of ₹1,234 crore, up 8.5% year on year, on 22 October 2026."""
        val (kept, dropped) = NumberGuard.grounded("Revenue was ₹1,234 crore, up 8.5% [1]. Net profit was ₹987 crore [2]. Results came on 22 October 2026.", corpus)
        assertEquals("Revenue was ₹1,234 crore, up 8.5% [1]. Results came on 22 October 2026.", kept)
        assertTrue(dropped)
        assertEquals("No figures here at all [1]." to false, NumberGuard.grounded("No figures here at all [1].", corpus))
        assertEquals("" to true, NumberGuard.grounded("It trades at ₹4,321.", corpus))
    }

    @Test fun modelCopyOfAToolResultIsSmall() {
        val big = """{"summary":"s","points":[{"t":"2026-01-01","c":1}],"results":[{"n":1,"title":"t","url":"https://x.example","snippet":"s"}],
            "holdings":[${(1..25).joinToString(",") { """{"symbol":"S$it","token":$it,"exchange":"NSE","qty":1}""" }}]}"""
        val small = JsonParser.parseString(ToolJson.compact(big)).asJsonObject
        assertFalse(small.has("points"))
        assertEquals(10, small.getAsJsonArray("holdings").size())
        assertEquals("10 of 25", small["holdings_shown"].asString)
        assertFalse(small.toString().contains("https://")); assertFalse(small.toString().contains("token"))
        assertEquals("s", ToolJson.summary(big))
    }
}
