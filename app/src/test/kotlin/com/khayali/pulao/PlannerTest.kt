package com.khayali.pulao

import com.khayali.pulao.ai.ChatFlow
import com.khayali.pulao.ai.Labels
import com.khayali.pulao.ai.Planner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    private fun goal(q: String) = Planner.plan(q)?.goal

    @Test fun broadQuestionsBecomePlans() {
        assertEquals("Your plan for today", goal("What should I do today?"))
        assertEquals("Your plan for today", goal("how can i make money"))
        assertEquals("A review of your portfolio", goal("review my portfolio"))
        assertEquals("What's moving the market", goal("why did the market fall today?"))
        assertEquals("Finding a strategy that works now", goal("which strategy should I follow?"))
        assertEquals("A closer look at INFY", goal("should I buy infy?"))
        assertEquals("A closer look at TATAMOTORS", goal("what's happening with @TATAMOTORS"))
        assertEquals("Weighing the Crystal Crop IPO", goal("should I apply for the Crystal Crop IPO?"))
        // narrow or unrelated questions don't
        assertNull(goal("/brief")); assertNull(goal("hidden patterns")); assertNull(goal("what is a PE ratio")); assertNull(goal("is the market open"))
    }

    @Test fun buyOrSellQuestionsCarryTheCaution() {
        assertNotNull(Planner.plan("should I sell TCS")!!.caution)
        assertNull(Planner.plan("tell me about TCS")!!.caution)
        assertTrue(Planner.plan("should I buy infy")!!.steps.none { it.command.contains("buy", true) })
    }

    @Test fun stepsChainOnWhatEarlierStepsFound() {
        val brief = """{"summary":"x","pills":["/health FADER","/screen trend","/strategies"],"card":{"blocks":[
            {"type":"movers","title":"This week","rows":[{"symbol":"AAA"}]},
            {"type":"movers","title":"Hidden patterns today","rows":[{"symbol":"ZEEL"}]}]}}"""
        assertEquals("ZEEL", Planner.top(listOf(brief)))
        assertEquals("trend", Planner.best(listOf(brief)))
        assertEquals("/news ZEEL", Planner.resolve("/news {top}", listOf(brief)))
        assertEquals("/screen trend", Planner.resolve("/screen {best}", listOf(brief)))
        assertNull("nothing found yet, so the step is skipped", Planner.resolve("/news {top}", emptyList()))
        assertEquals("/screen sharpe", Planner.resolve("/screen {best}", emptyList()))
        assertEquals(listOf("/health FADER", "/screen trend", "/strategies"), Planner.next(listOf(brief), setOf("/brief")))
    }

    @Test fun wrapUpSaysWhatRanWhatShowsAndWhatNext() {
        val p = Planner.plan("what should I do today")!!
        val done = listOf(Planner.Done(p.steps[0], "/brief", "Weakening market — 40% above their 50-day average. More text.", null),
            Planner.Done(p.steps[1], null, null, "needs web access"))
        val w = Planner.wrapUp(p, done, listOf("/health FADER", "/screen trend"))
        assertTrue(w, w.contains("1 of 2 steps done") && w.contains("Weakening market — 40% above their 50-day average.") && !w.contains("More text"))
        assertTrue(w.contains("Skipped") && w.contains("needs web access"))
        assertTrue(w.contains("🩺 Check FADER's trend"))
    }

    @Test fun plansRespectDownloads() {
        val none = ChatFlow.Env(true, ChatFlow.Kite.OFF, false, emptySet())
        assertEquals(ChatFlow.Action.NeedArtifact("market", "what should I do today?"), ChatFlow.route("what should I do today?", none))
        // the market plan starts from the phone's own data, so it offers that download first too
        assertEquals(ChatFlow.Action.NeedArtifact("market", "why did the market fall today"), ChatFlow.route("why did the market fall today", none))
        assertTrue(ChatFlow.route("why did the market fall today", ChatFlow.Env(true, ChatFlow.Kite.OFF, false, setOf("market"))) is ChatFlow.Action.Plan)
    }

    @Test fun pillsReadAsActions() {
        assertEquals("🎯 Today's trend following picks", Labels.of("/screen trend"))
        assertEquals("📰 News on INFY", Labels.of("/news INFY"))
        assertEquals("⬇ Get 3 years of data", Labels.of("/data 3y"))
        assertEquals("Why is IT falling?", Labels.of("Why is IT falling?"))
        assertEquals("Bulk and block deals · GROWW", Labels.of("/deals GROWW"))
        assertTrue(Labels.of("/fiidii").let { !it.startsWith("/") && it.length <= 31 })
        assertEquals("/nope", Labels.of("/nope"))
    }
}
