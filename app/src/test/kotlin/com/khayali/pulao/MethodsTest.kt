package com.khayali.pulao

import com.khayali.pulao.ai.ChatFlow
import com.khayali.pulao.ai.Methods
import com.khayali.pulao.ai.ToolEvent
import com.khayali.pulao.ai.term.Terminal
import org.junit.Assert.assertTrue
import org.junit.Test

class MethodsTest {
    private val env = ChatFlow.Env(true, ChatFlow.Kite.CONNECTED, true)
    private fun why(t: String, via: String? = null) = Methods.why(t, ChatFlow.route(t, env), via)

    @Test fun everyRouteSaysWhyTheReplyExists() {
        assertTrue(why("/strategies").startsWith("You typed /strategies."))
        assertTrue(why("hidden patterns").contains("mean /anomalies") && why("hidden patterns").contains("no AI needed"))
        assertTrue(why("what should I do today?").contains("plan of 4 steps"))
        assertTrue(why("/screen sharpe", "pill:🎯 Today's picks").startsWith("You tapped the suggestion “🎯 Today's picks”, which runs /screen sharpe."))
        assertTrue(why("/health INFY", "ticker:INFY").startsWith("You picked an action from INFY's menu"))
        assertTrue(why("/news INFY", "action").startsWith("You tapped a next step"))
        assertTrue(why("tell me a joke about bonds").contains("Gemma"))
        assertTrue(why("/holdings").contains("read-only"))
        val none = ChatFlow.Env(true, ChatFlow.Kite.OFF, false, emptySet())
        assertTrue(Methods.why("/brief", ChatFlow.route("/brief", none)).contains("market history download first"))
    }

    @Test fun everyStepSaysHowItWasMade() {
        val brief = Methods.of(ToolEvent("term_brief", "", true, """{"sources":["NSE (your copy)"],"span":"2021-11-17 to 2026-11-17 · 1234 trading days · 1884 stocks","asOf":"2026-11-17"}""", """{"command":"/brief"}"""))
        assertTrue(brief.data.contains("own copy of NSE's daily price files") && brief.data.contains("1234 trading days"))
        assertTrue(brief.how.contains("₹5 crore") && brief.how.contains("No costs, slippage or taxes"))
        assertTrue(brief.asOf == "2026-11-17")
        val sectors = Methods.of(ToolEvent("term_sectors", "", true, """{"sources":["NSE"]}"""))
        assertTrue(sectors.data.contains("private browser engine") && sectors.how == Terminal.find("sectors")!!.help)
        assertTrue(Methods.of(ToolEvent("web_search", "", true, "{}", """{"query":"rbi policy"}""")).how.contains("amounts, quantities and IDs are stripped"))
        assertTrue(Methods.of(ToolEvent("kite_account", "Read Kite holdings", true, "{}", """{"section":"holdings"}""")).limits.contains("cannot place"))
        assertTrue(Methods.of(ToolEvent("grow_for_years", "", true, "{}")).how.contains("(1 + r)^years"))
        assertTrue(Methods.of(ToolEvent("web_gmp", "", true, "{}")).limits.contains("unofficial"))
        assertTrue(Methods.model("GPU").how.contains("removed"))
    }

    @Test fun everyAdvisorFeatureDocumentsItsMethod() {
        for (id in listOf("brief", "regime", "anomalies", "strategies", "screen", "health", "learn", "data", "screens"))
            assertTrue(id, Terminal.find(id)!!.method.length > 80)
    }
}
