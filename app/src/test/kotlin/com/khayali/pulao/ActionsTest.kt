package com.khayali.pulao

import com.khayali.pulao.ai.Actions
import com.khayali.pulao.ai.Asks
import com.khayali.pulao.ai.ChatFlow
import com.khayali.pulao.ai.Md
import com.khayali.pulao.ai.Params
import com.khayali.pulao.ai.Planner
import com.khayali.pulao.ai.Tickers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionsTest {
    private fun cmds(t: String) = Actions.find(t).map { it.second }

    @Test fun commandsInTextBecomeTapTargetsWithTheirRealArguments() {
        assertEquals(listOf("/screen momentum", "/strategies"), cmds("Run it on your own data: /screen momentum · compare every rule: /strategies"))
        assertEquals(listOf("/data 3y"), cmds("A longer pack (/data 3y) gives longer tests."))
        assertEquals(listOf("/sip 10000 12% 15y"), cmds("try /sip 10000 12% 15y."))
        assertEquals(listOf("/health INFY"), cmds("check /health INFY now"))
        assertEquals(listOf("/screens quality low debt"), cmds("Search one idea: /screens quality low debt · /screens dividend").take(1))
        // ordinary words after a command are not arguments
        assertEquals(listOf("/strategies"), cmds("/strategies to compare them"))
        assertEquals(listOf("/data"), cmds("type /data to download it"))
        // unknown commands, paths and fractions are not actions
        assertTrue(cmds("1/2 of /usr/bin and /notacommand").isEmpty())
        assertTrue(Actions.isAction("/brief") && Actions.isAction("Connect Kite") && !Actions.isAction("/nope") && !Actions.isAction("hello"))
    }

    @Test fun tickersInTextNeedToBeKnown() {
        Tickers.known = setOf("INFY", "FIRSTCRY")
        assertEquals(listOf("INFY", "FIRSTCRY"), Tickers.find("INFY and FIRSTCRY moved; RBI and FII did not; HDFC unknown").map { it.second })
        assertFalse(Tickers.isSymbol("NSE")); assertTrue(Tickers.isSymbol("★ INFY")); assertFalse(Tickers.isSymbol("Low pe stocks"))
        val menu = Tickers.menu("INFY", web = false, market = true).map { it.second }
        assertTrue(menu.containsAll(listOf("/health INFY", "/chart INFY 1y", "/news INFY", "what's happening with INFY")))
        Tickers.known = emptySet()
    }

    @Test fun actionLinksParse() {
        val m = Md.ACTION.find("- [🩺 Check FADER's trend](cmd:/health FADER) and more")!!
        assertEquals("🩺 Check FADER's trend", m.groupValues[1]); assertEquals("/health FADER", m.groupValues[2])
        assertNull(Md.ACTION.find("[2] a citation, not an action"))
    }

    @Test fun parametersAreAskedOneAtATime() {
        var s = Params.step("/sip ", emptyList())!!
        assertEquals("How much a month?", s.ask); assertFalse(s.canRun); assertTrue("10000" in s.choices)
        var t = Params.pick("/sip ", "10000"); s = Params.step(t, emptyList())!!
        assertEquals("Expected yearly return?", s.ask)
        t = Params.pick(t, "12%"); t = Params.pick(t, "15y")
        assertEquals("/sip 10000 12% 15y ", t); assertTrue(Params.step(t, emptyList())!!.complete)
        // optional parameters can be skipped; stocks come from the user's own list first
        s = Params.step("/chart ", listOf("ZEEL"))!!; assertEquals("ZEEL", s.choices.first()); assertFalse(s.canRun)
        s = Params.step("/chart ZEEL ", listOf("ZEEL"))!!; assertTrue(s.canRun); assertTrue("1y" in s.choices)
        s = Params.step("/chart IN", emptyList())!!; assertEquals(listOf("INFY"), s.choices)
        // multi-word choices complete in one tap
        t = Params.pick("/screens ", "quality low debt"); assertTrue(Params.step(t, emptyList())!!.complete)
        // commands with nothing to ask run straight away
        assertTrue(Params.slots("brief", emptyList()).isEmpty() && Params.slots("holdings", emptyList()).isEmpty())
        assertTrue(Params.slots("screen", emptyList()).isNotEmpty())
    }

    @Test fun startersAreTasksThatRun() {
        val env = ChatFlow.Env(true, ChatFlow.Kite.CONNECTED, true)
        val withStock = Asks.starters(listOf("ZEEL"), emptyList(), kite = true)
        assertTrue(withStock.any { it.text.contains("ZEEL") })
        for (st in withStock + Asks.starters(emptyList(), emptyList(), kite = false)) {
            val a = ChatFlow.route(st.run, env)
            assertTrue("${st.run} -> $a", a is ChatFlow.Action.Plan || a is ChatFlow.Action.Term || a is ChatFlow.Action.Web || a is ChatFlow.Action.ConnectKite)
        }
        assertTrue(ChatFlow.route("what should I do today?", env) is ChatFlow.Action.Plan)
    }

    @Test fun wrapUpNextStepsAndFixesAreTappable() {
        val p = Planner.plan("what should I do today")!!
        val w = Planner.wrapUp(p, listOf(Planner.Done(p.steps[0], "/brief", "Weak.", null), Planner.Done(p.steps[2], null, null, "needs web access")), listOf("/health FADER"))
        assertTrue(w, w.contains("[🩺 Check FADER's trend](cmd:/health FADER)"))
        assertTrue(w, w.contains("(cmd:Allow web access)"))
        Md.ACTION.findAll(w).forEach { assertTrue(it.groupValues[2], Actions.isAction(it.groupValues[2])) }
    }
}
