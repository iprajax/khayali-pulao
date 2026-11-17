package com.khayali.pulao

import com.google.gson.JsonParser
import com.khayali.pulao.ai.ChatFlow
import com.khayali.pulao.ai.ChatSession
import com.khayali.pulao.ai.Commands
import com.khayali.pulao.ai.Compact
import com.khayali.pulao.ai.ContextPills
import com.khayali.pulao.ai.MarketData
import com.khayali.pulao.ai.MarketData.Kind
import com.khayali.pulao.ai.ToolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Market calendar, IPO life cycle and chat compaction. Company names here are fictional. */
class MarketDataTest {
    private val today = LocalDate.of(2026, 11, 17)

    private val events = """[
        {"symbol":"INFRA","company":"Infra Builders &amp; Co Limited","purpose":"Financial Results/Dividend","bm_desc":"To consider results","date":"18-Nov-2026"},
        {"symbol":"CHAICO","company":"Chai Co Limited","purpose":"Other business matters","bm_desc":"x","date":"19-Nov-2026"},
        {"symbol":"JHUMRI","company":"Jhumri Power Limited","purpose":"Bonus/Other business matters","bm_desc":"x","date":"20-Nov-2026"},
        {"symbol":"BAD","company":"Bad Date Limited","purpose":"Results","date":"soon"}]"""
    private val actions = """[
        {"comp":"Ballia Ports Limited","exDate":"17-Nov-2026","subject":"Interim Dividend - Rs 4.10 Per Share","symbol":"BALLIA","series":"EQ"},
        {"comp":"Jhumri Power Limited","exDate":"21-Nov-2026","subject":"Face Value Split (Sub-Division) - From Rs 10/- Per Share To Re 1/- Per Share","symbol":"JHUMRI"},
        {"comp":"Chai Co Limited","exDate":"23-Nov-2026","subject":"Annual General Meeting","symbol":"CHAICO"},
        {"comp":"Bond Issuer Limited","exDate":"18-Nov-2026","subject":"Interest Payment","symbol":"BOND28"},
        {"comp":"Infra Builders Limited","exDate":"30-Dec-2026","subject":"Rights 5:9","symbol":"INFRA"}]"""
    private val ipos = """[
        {"companyName":"Pulao Foods Limited","issueEndDate":"20-Nov-2026","issuePrice":"Rs.259 to Rs.272","issueSize":"17419685","issueStartDate":"18-Nov-2026","series":"Equity","status":"Forthcoming","symbol":"PULAO"},
        {"companyName":"Thali Rentals Limited","issueEndDate":"17-Nov-2026","issuePrice":"Rs.232 to Rs.245","issueSize":"4902000","issueStartDate":"13-Nov-2026","series":"SME","symbol":"THALI"}]"""
    private val holidays = """{"CM":[{"tradingDate":"24-Nov-2026","weekDay":"Tuesday","description":"Prakash Gurpurb Sri Guru Nanak Dev"}],"FO":[]}"""

    @Test fun classifiesEveryKindOfEvent() {
        assertEquals(Kind.SPLIT, MarketData.kindOf("Face Value Split (Sub-Division) - From Rs 10 To Re 1"))
        assertEquals(Kind.BONUS, MarketData.kindOf("Bonus 1:1"))
        assertEquals(Kind.BONUS, MarketData.kindOf("Bonus/Other business matters"))
        assertEquals(Kind.BUYBACK, MarketData.kindOf("Buy Back"))
        assertEquals(Kind.RIGHTS, MarketData.kindOf("Rights 5:9"))
        assertEquals(Kind.AGM, MarketData.kindOf("Annual General Meeting/Dividend - Rs 2 Per Share"))
        assertEquals(Kind.RESULTS, MarketData.kindOf("Financial Results/Dividend"))
        assertEquals(Kind.DIVIDEND, MarketData.kindOf("Interim Dividend - Rs 4.10 Per Share"))
        assertEquals(Kind.MEETING, MarketData.kindOf("Fund Raising"))
    }

    @Test fun readsNseFeeds() {
        val e = MarketData.parseEvents(events)
        assertEquals(listOf("INFRA", "CHAICO", "JHUMRI"), e.map { it.symbol })                 // the undated row is dropped
        assertEquals(listOf(Kind.RESULTS, Kind.MEETING, Kind.BONUS), e.map { it.kind })
        assertEquals("Infra Builders & Co Limited", e[0].company)
        val a = MarketData.parseActions(actions)
        assertEquals(listOf(Kind.DIVIDEND, Kind.SPLIT, Kind.AGM, Kind.RIGHTS), a.map { it.kind })   // the bond coupon is dropped
        assertEquals(LocalDate.of(2026, 11, 21), a[1].date)
        assertEquals(listOf(LocalDate.of(2026, 11, 24)), MarketData.parseHolidays(holidays).map { it.date })
        assertEquals(emptyList<MarketData.Item>(), MarketData.parseEvents("<html>blocked</html>"))
        assertEquals(emptyList<MarketData.Item>(), MarketData.parseHolidays("[]"))
    }

    @Test fun weekCalendarPutsYourHoldingsFirstAndCountsByKind() {
        val all = MarketData.parseEvents(events) + MarketData.parseActions(actions) + MarketData.ipoItems(MarketData.parseIpos(ipos)) + MarketData.parseHolidays(holidays)
        val o = JsonParser.parseString(MarketData.calendarJson("Market calendar", all, today, today.plusDays(7), today, mine = setOf("JHUMRI"))).asJsonObject
        val rows = o.getAsJsonArray("events").map { it.asJsonObject }
        assertTrue(rows.none { it["date"].asString > "2026-11-24" })                           // the December rights issue is outside the week
        assertEquals("today", rows.first { it["symbol"].asString == "BALLIA" }["phase"].asString)
        assertEquals("upcoming", rows.first { it["kind"].asString == "Stock split" }["phase"].asString)
        assertEquals(setOf("JHUMRI"), rows.filter { it["mine"].asBoolean }.map { it["symbol"].asString }.toSet())
        // within a day your own stock comes before everything else
        assertEquals("JHUMRI", rows.first { it["date"].asString == "2026-11-20" }["symbol"].asString)
        val s = o["summary"].asString
        assertTrue(s, s.contains("In your holdings: JHUMRI bonus on Fri 20 Nov") && s.contains("Market closed on Tue 24 Nov") && s.contains("1 IPO opens") && s.contains("Source: NSE."))
        assertEquals(1, o.getAsJsonObject("counts")["Stock split"].asInt)
        assertTrue(ToolJson.error(MarketData.calendarJson("Market calendar", emptyList(), today, today, today)) != null)
    }

    @Test fun companyCalendarSaysWhatIsNextAndWhatWasLast() {
        val items = listOf(MarketData.Item(LocalDate.of(2026, 10, 23), Kind.RESULTS, "INFRA", "Infra", "Financial Results"),
            MarketData.Item(LocalDate.of(2026, 12, 30), Kind.RIGHTS, "INFRA", "Infra", "Ex-date: Rights 5:9"))
        val s = ToolJson.summary(MarketData.calendarJson("INFRA", items, today.minusDays(120), today.plusDays(180), today))!!
        assertTrue(s, s.startsWith("INFRA: next is rights issue on Wed 30 Dec (Rights 5:9); most recent was results on Fri 23 Oct."))
        val past = ToolJson.summary(MarketData.calendarJson("INFRA", items.take(1), today.minusDays(120), today.plusDays(180), today))!!
        assertTrue(past, past.contains("nothing is scheduled ahead yet"))
    }

    @Test fun sameEventFromTwoFeedsIsCountedOnce() {
        val a = MarketData.Item(today, Kind.RESULTS, "INFRA", "Infra Builders Limited", "Financial Results")
        val b = MarketData.Item(today, Kind.RESULTS, "INFRA", "Infra Builders", "Q2 FY26-27", MarketData.MC_NAME)
        assertEquals(1, MarketData.merge(listOf(a, b)).size)
        assertEquals(2, MarketData.merge(listOf(a, a.copy(kind = Kind.DIVIDEND))).size)
    }

    @Test fun ipoLifeCycle() {
        val list = MarketData.parseIpos(ipos)
        assertEquals("₹259 – ₹272", list[0].price)
        assertEquals("Forthcoming", list[0].status(today))
        assertEquals("Open", list[0].status(LocalDate.of(2026, 11, 19)))
        assertEquals("Open", list[0].status(LocalDate.of(2026, 11, 20)))           // the last day still counts
        assertEquals("Closed", list[0].status(LocalDate.of(2026, 11, 21)))
        assertEquals("Open", list[1].status(today))
        val o = JsonParser.parseString(MarketData.ipoJson(list, emptyList(), today)).asJsonObject
        assertEquals(listOf("Thali Rentals Limited", "Pulao Foods Limited"), o.getAsJsonArray("ipos").map { it.asJsonObject["name"].asString })   // by opening date
        assertEquals("SME", o.getAsJsonArray("ipos")[0].asJsonObject["board"].asString)
        val s = o["summary"].asString
        assertTrue(s, s.startsWith("Open now: Thali Rentals Limited (closes Tue 17 Nov") && s.contains("Coming up: Pulao Foods Limited opens Wed 18 Nov and closes Fri 20 Nov"))
        assertTrue(ToolJson.error(MarketData.ipoJson(emptyList(), emptyList(), today)) != null)
    }

    @Test fun listingsLeaveOutBondsFundsAndRightsEntitlements() {
        val json = """{"data":[{"instrument":"Bonds","listing_date":"17-Nov-2026","name":"Bond Issuer","series":"N0","symbol":"0BOND30"},
            {"instrument":"Equity","listing_date":"13-Nov-2026","name":"Pulao Foods Limited","series":"EQ","symbol":"PULAO"},
            {"instrument":"Equity","listing_date":"16-Nov-2026","name":"Thali Rentals Limited","series":"BE","symbol":"THALI-RE1"}]}"""
        assertEquals(listOf("PULAO"), MarketData.parseListings(json).map { it.symbol })
        assertEquals(emptyList<MarketData.Item>(), MarketData.parseListings("""{"data":[],"msg":"no data found"}"""))
    }

    @Test fun readsMoneycontrolResultsPage() {
        val page = """<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"resultCalendarData":{"tableData":{"list":[
            {"date":"18 Nov","stockName":"Infra Builders","resultType":"Q2 FY26-27","time":"Time Not Available"},
            {"date":"18 Nov","stockName":"Chai Co","resultType":"Q2 FY26-27","time":"04:30 PM"}]}}}}}</script></html>"""
        val r = MarketData.parseResultsPage(page, LocalDate.of(2026, 11, 18))
        assertEquals(listOf("Infra Builders", "Chai Co"), r.map { it.company })
        assertEquals(listOf("Q2 FY26-27", "Q2 FY26-27 · 04:30 PM"), r.map { it.title })
        assertTrue(r.all { it.kind == Kind.RESULTS && it.source == "Moneycontrol" })
        assertEquals(emptyList<MarketData.Item>(), MarketData.parseResultsPage("<html>changed</html>", today))
    }

    @Test fun periodsAndSymbols() {
        assertEquals(today to today.plusDays(7), MarketData.window("week", today))
        assertEquals(today to today.plusDays(7), MarketData.window("", today))
        assertEquals(today.plusDays(1) to today.plusDays(1), MarketData.window("Tomorrow", today))
        assertEquals(today to today.plusDays(30), MarketData.window("month", today))
        assertEquals(LocalDate.of(2026, 11, 18) to LocalDate.of(2026, 11, 18), MarketData.window("2026-11-18", today))
        assertEquals(LocalDate.of(2026, 11, 20) to LocalDate.of(2026, 11, 20), MarketData.window("20 Nov", today))
        assertEquals(LocalDate.of(2027, 1, 5), MarketData.window("5 jan", today)!!.first)          // a past month means next year
        assertNull(MarketData.window("INFRA", today))                                              // a symbol, not a period
        assertEquals("M&M", MarketData.symbolOrNull("@m&m")); assertNull(MarketData.symbolOrNull("Infra Builders"))
        assertTrue(MarketData.eventsUrl(today, today.plusDays(7)).endsWith("from_date=17-11-2026&to_date=24-11-2026"))
        assertTrue(MarketData.actionsUrl("m&m").endsWith("symbol=M%26M"))
    }

    @Test fun commandsRouteToTheCalendar() {
        val on = ChatFlow.Env(modelReady = false, kite = ChatFlow.Kite.OFF, webOn = true)
        assertEquals(ChatFlow.Action.Web(ChatFlow.WebKind.CALENDAR, "week"), ChatFlow.route("/calendar", on))
        assertEquals(ChatFlow.Action.Web(ChatFlow.WebKind.CALENDAR, "INFRA"), ChatFlow.route("/events @INFRA", on))
        assertEquals(ChatFlow.Action.Web(ChatFlow.WebKind.CALENDAR, "tomorrow"), ChatFlow.route("/results tomorrow", on))
        assertEquals(ChatFlow.Action.Web(ChatFlow.WebKind.CALENDAR, "today"), ChatFlow.route("/results", on))
        assertTrue((ChatFlow.route("/results INFRA", on) as ChatFlow.Action.Web).kind == ChatFlow.WebKind.SEARCH)
        assertEquals(ChatFlow.Action.Web(ChatFlow.WebKind.IPO, ""), ChatFlow.route("/ipo", on))
        assertEquals(ChatFlow.Action.EnableWeb("/ipo"), ChatFlow.route("/ipo", on.copy(webOn = false)))       // still opt-in
        assertEquals(ChatFlow.Action.Compact, ChatFlow.route("/compact", on))
        assertTrue(Commands.suggest("/c").map { it.name }.containsAll(listOf("calendar", "chart", "clear", "compact")))
    }

    @Test fun pillsFollowTheCalendarAndTheUsersGoal() {
        val cal = """{"title":"Market calendar","from":"2026-11-17","to":"2026-11-24","events":[{"symbol":"JHUMRI","mine":true},{"symbol":"BALLIA","mine":false}]}"""
        val p = ContextPills.build(ContextPills.Ctx(true, listOf("JHUMRI"), "web_calendar", cal, "/calendar", webOn = true))
        assertEquals("/events JHUMRI", p.first())                                                  // your own stock's event leads
        assertTrue(p.toString(), p.contains("/ipo") && p.contains("/results tomorrow"))
        val g = ContextPills.build(ContextPills.Ctx(false, emptyList(), "grow_for_years", """{"summary":"x"}""", "q", goal = "1 crore by 2035"))
        assertTrue(g.toString(), g.contains("/goal 1 crore"))
        assertFalse(ContextPills.build(ContextPills.Ctx(false, emptyList(), "grow_for_years", """{"summary":"x"}""", "q")).any { it.startsWith("/goal") })
    }

    @Test fun compactionKeepsQuestionsAndCheckedFigures() {
        assertNull(Compact.summary(listOf(ChatSession.Turn("hi", "Hello."))))                       // nothing worth compacting
        val turns = listOf(
            ChatSession.Turn("/fd 3", "₹3,00,000 became ₹3,62,500 in 3.0 years (+6.5% a year). A 6.45% FD would have made it ₹3,61,870."),
            ChatSession.Turn("What if INFRA falls 20%?", "If INFRA moves −20% (from today's price), it becomes ₹1,160.00 a share; 250 shares worth ₹2,90,000. This is a notable drop."),
            ChatSession.Turn("/price X", "⚠️ No internet connection."))
        val s = Compact.summary(turns)!!
        assertEquals(2, s.lines().size)                                                             // the failed turn is left out
        assertTrue(s, s.contains("Asked: /fd 3 → ₹3,00,000 became ₹3,62,500 in 3.0 years (+6.5% a year)."))
        assertTrue(s, s.contains("₹1,160.00 a share") && !s.contains("notable drop"))               // first sentence only; decimals don't end it
        val long = (1..12).map { ChatSession.Turn("Question number $it about compounding over many years", "Answer $it is a fairly long sentence about what the calculator found for this case.") }
        val c = Compact.summary(long)!!
        assertTrue(c.length <= 980 && c.contains("earlier exchanges left out") && c.contains("Question number 12"))
    }
}
