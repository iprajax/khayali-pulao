package com.khayali.pulao

import com.khayali.pulao.ai.ChatFlow
import com.khayali.pulao.ai.Intents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentsTest {
    @Test fun plainRequestsBecomeCommands() {
        val cases = mapOf(
            "show my holdings" to "/holdings", "My holdings?" to "/holdings", "what are my positions" to "/positions",
            "my P&L" to "/pnl", "available margin" to "/margins", "today's orders" to "/orders", "my mutual funds" to "/mf",
            "market calendar" to "/calendar week", "results calendar tomorrow" to "/calendar tomorrow", "what's happening in the market this week" to "/calendar week",
            "who reports results tomorrow" to "/results tomorrow", "which IPOs are open" to "/ipo", "upcoming IPOs" to "/ipo", "any IPO this week" to "/ipo",
            "GMP of Crystal Crop" to "/gmp Crystal Crop", "grey market premium" to "/gmp",
            "price of infosys" to "/price infosys", "what's the share price of INFY?" to "/price INFY", "how much is TCS trading at" to "/price TCS",
            "chart of TCS for 1 year" to "/chart TCS 1y", "show me a chart of infy over the last 6 months" to "/chart INFY 6m", "chart INFY" to "/chart INFY",
            "dividends of ITC" to "/events ITC", "news on reliance" to "/news reliance", "latest results of INFY" to "/results INFY",
            "what do you remember about me" to "/memory", "remember that I invest for 10 years" to "/remember I invest for 10 years",
            "what can you do" to "/help", "help" to "/help",
        )
        for ((said, cmd) in cases) assertEquals(said, cmd, Intents.match(said))
    }

    @Test fun realQuestionsGoToTheModel() {
        for (q in listOf("should I worry about my holdings if interest rates rise this year?", "what if my biggest holding falls 20%",
            "explain what a price to earnings ratio means", "is TCS a good buy", "compare my portfolio with an FD over 3 years", "/holdings", "", "price"))
            assertNull(q, Intents.match(q))
    }

    @Test fun periods() {
        assertEquals("1y", Intents.period("1 year")); assertEquals("1y", Intents.period("a year")); assertEquals("5y", Intents.period("five years"))
        assertEquals("6m", Intents.period("6 months")); assertEquals("3m", Intents.period("3 months")); assertEquals("1m", Intents.period("a month"))
        assertEquals("1w", Intents.period("1 week")); assertNull(Intents.period("ever"))
    }

    @Test fun routedLikeTheTypedCommand() {
        val on = ChatFlow.Env(modelReady = false, kite = ChatFlow.Kite.CONNECTED, webOn = true)
        assertEquals(ChatFlow.Action.KiteRead("holdings"), ChatFlow.route("show my holdings", on))       // works with no model at all
        assertEquals(ChatFlow.Action.Web(ChatFlow.WebKind.IPO, ""), ChatFlow.route("which IPOs are open?", on))
        assertEquals(ChatFlow.Action.EnableWeb("/price infosys"), ChatFlow.route("price of infosys", on.copy(webOn = false)))   // still asks for web first
        assertEquals(ChatFlow.Action.ConnectKite("/holdings", expired = true), ChatFlow.route("my holdings", on.copy(kite = ChatFlow.Kite.EXPIRED)))
        assertTrue(ChatFlow.route("should I worry about my holdings if rates rise?", on.copy(modelReady = true)) is ChatFlow.Action.Model)
    }
}
