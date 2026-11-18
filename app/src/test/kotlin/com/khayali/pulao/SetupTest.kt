package com.khayali.pulao

import com.khayali.pulao.ai.ChatFlow
import com.khayali.pulao.ai.TickerWheel
import com.khayali.pulao.ai.data.Artifacts.State
import com.khayali.pulao.ai.data.Setup
import com.khayali.pulao.ai.data.Setup.Phase
import com.khayali.pulao.ai.data.Setup.S
import com.khayali.pulao.ai.data.Setup.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupTest {
    private val none = Setup.Facts(State.NotInstalled, State.NotInstalled, State.NotInstalled, web = false, kite = false)
    private fun states(chosen: Set<Step>, started: Boolean, f: Setup.Facts) = Step.entries.associateWith { Setup.status(it, it in chosen, started, f) }
    private val defaults = Step.entries.filter { it.byDefault }.toSet()

    @Test fun defaultsAreTheDownloadsWithoutPersonalAccess() {
        assertEquals(setOf(Step.MODEL, Step.MARKET, Step.KNOWLEDGE), defaults)      // web and Kite are opt-in
    }

    @Test fun lifeCycleFollowsTheRealStates() {
        val chosen = defaults + Step.WEB + Step.KITE
        assertEquals(Phase.NOT_STARTED, Setup.phase(false, states(chosen, false, none)))
        // started: everything chosen is queued; the agent starts all of them at once
        assertEquals(listOf(Step.MODEL, Step.MARKET, Step.KNOWLEDGE, Step.WEB), Setup.toStart(chosen, states(chosen, true, none)))
        val running = none.copy(model = State.Paused("Waiting for Wi-Fi", State.Downloading(0, 10)), market = State.Downloading(30, 100), web = true)
        val st = states(chosen, true, running)
        assertEquals(S.Running(30), st[Step.MARKET]); assertEquals(S.Running(null, "Waiting for Wi-Fi"), st[Step.MODEL]); assertEquals(S.Done("on"), st[Step.WEB])
        assertEquals(Phase.RUNNING, Setup.phase(true, st))
        // downloads finished; only Kite's login is left
        val almost = running.copy(model = State.Ready("Gemma 4 E2B", 1, 0), market = State.Ready("x", 1, 0), knowledge = State.Ready("v1", 1, 0))
        assertEquals(S.NeedsYou("Connect Kite"), states(chosen, true, almost)[Step.KITE])
        assertEquals(Phase.NEEDS_YOU, Setup.phase(true, states(chosen, true, almost)))
        assertEquals(Phase.DONE, Setup.phase(true, states(chosen, true, almost.copy(kite = true))))
        // a failure is retried by starting that step again
        val failed = states(defaults, true, none.copy(knowledge = State.Failed("HTTP 404")))
        assertEquals(Phase.FAILED, Setup.phase(true, failed)); assertTrue(Step.KNOWLEDGE in Setup.toStart(defaults, failed))
        // unchosen steps are skipped and never block DONE
        val ready = none.copy(model = State.Ready("m", 1, 0), market = State.Stale("x", 1, "1 day behind"), knowledge = State.Ready("v1", 1, 0))
        assertEquals(Phase.DONE, Setup.phase(true, states(defaults, true, ready)))
        assertEquals(S.Skipped, states(defaults, true, ready)[Step.KITE])
    }

    @Test fun choicesSurviveARestart() {
        val c = setOf(Step.MARKET, Step.WEB)
        assertEquals(c, Setup.decode(Setup.encode(c))); assertEquals(null, Setup.decode(null))
        assertEquals("Setting up… 1 of 2 ready", Setup.headline(Phase.RUNNING, mapOf(Step.MARKET to S.Done(), Step.WEB to S.Waiting, Step.KITE to S.Skipped)))
    }

    @Test fun setupIsReachableInWords() {
        val env = ChatFlow.Env(true, ChatFlow.Kite.OFF, false)
        for (q in listOf("/setup", "set up everything", "Setup", "get started!")) assertEquals(q, ChatFlow.Action.Setup, ChatFlow.route(q, env))
    }

    @Test fun theWheelHasEveryTickerAction() {
        val w = TickerWheel.items("BALLIA", web = true, market = true)
        assertEquals(listOf("Why", "Trend", "Chart", "News", "Events", "Quote", "Deep dive", "Ask"), w.map { it.second })
        assertEquals(listOf("why", "trend", "chart", "news", "events", "quote", "dive", "ask"), w.map { it.first })      // line icons, not emoji
        assertTrue(w.all { it.third.isNotBlank() })
        assertEquals("/why BALLIA", w[0].third); assertEquals("/chart BALLIA 1y", w[2].third)
    }
}
