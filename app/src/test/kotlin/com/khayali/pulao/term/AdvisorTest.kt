package com.khayali.pulao.term

import com.khayali.pulao.Stock
import com.khayali.pulao.ai.ChatFlow
import com.khayali.pulao.ai.data.Bhavcopy
import com.khayali.pulao.ai.data.Panel
import com.khayali.pulao.ai.term.Playbook
import com.khayali.pulao.ai.term.Terminal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AdvisorTest {
    /** 340 weekdays of fictional stocks: a riser, a fader, a choppy one, and 20 quiet ones for a market. */
    private val panel: Panel by lazy {
        val days = Bhavcopy.tradingDayCandidates(LocalDate.of(2025, 7, 1), LocalDate.of(2026, 11, 13)).take(340)
        val drift = mapOf("RISER" to 0.003, "FADER" to -0.002, "CHOPPY" to 0.0) + (1..20).associate { "Q%02d".format(it) to 0.0003 * it }
        val px = drift.keys.associateWith { 100.0 }.toMutableMap()
        Panel.build(days.mapIndexed { i, d -> d to drift.map { (s, mu) ->
            val prev = px[s]!!; val r = mu + (if (s == "CHOPPY") (if (i % 2 == 0) 0.02 else -0.019) else if (i % 3 == 0) 0.004 else -0.002)
            val close = prev * (1 + r); px[s] = close
            val vol = if (s == "Q05" && i == days.size - 1) 9_000_000L else 1_000_000L
            Bhavcopy.Bar(s, "EQ", prev, maxOf(prev, close), minOf(prev, close), close, prev, vol, close * vol, 100, "")
        } })
    }
    private val fake get() = TermTesting.Fake(panel = panel, holdings = listOf(Stock.Holding("FADER", 100.0, 100.0, 10000.0, 60.0), Stock.Holding("RISER", 1.0, 100.0, 100.0, 200.0)),
        profile = listOf("Goal: retire in 20 years"))
    private fun run(cmd: String, f: TermTesting.Fake = fake) = runBlocking { Terminal.find(cmd.substringBefore(' '))!!.run(cmd.substringAfter(' ', ""), f) }

    @Test fun briefCoversMarketPatternsHoldingsAndStrategies() {
        val j = run("brief")
        assertEquals(null, TermTesting.error(j))
        val types = TermTesting.blocks(j).map { it["type"].asString }
        assertTrue(types.toString(), types.containsAll(listOf("kpis", "breadth", "movers", "rows")))
        val text = j
        assertTrue("holding flagged", text.contains("FADER") && text.contains("below its 200-day average"))
        assertTrue("concentration noted", text.contains("Concentration"))
        assertTrue("volume spike surfaced", text.contains("Q05"))
        assertTrue("not advice", text.contains("not a recommendation"))
        assertTrue("profile used", text.contains("long-horizon rule"))
        assertTrue("short history warned", text.contains("too short to judge"))
        assertTrue("next steps", text.contains("Next steps") && text.contains("/health FADER") && text.contains("/stress"))
        assertTrue("pills lead with actions", TermTesting.card(j).getAsJsonArray("pills").first().asString.startsWith("/health"))
    }

    @Test fun everyLocalFeatureWorksAndNeedsNoWeb() {
        for (c in listOf("regime", "anomalies", "strategies", "screen trend", "screen buy the dip", "health RISER", "health", "learn golden cross")) {
            val j = run(c); assertEquals(c, null, TermTesting.error(j))
            assertTrue(c, !Terminal.find(c.substringBefore(' '))!!.needsWeb)
        }
        assertTrue(run("screen trend").contains("RISER"))
    }

    @Test fun withoutDataTheyAskForADownload() {
        val j = run("brief", TermTesting.Fake())
        assertTrue(TermTesting.error(j)!!.contains("/data"))
    }

    @Test fun plainQuestionsRouteToTheAdvisorWithoutTheModelOrWeb() {
        val env = ChatFlow.Env(modelReady = false, kite = ChatFlow.Kite.OFF, webOn = false)
        // broad questions become plans (see PlannerTest); short specific ones still run one command
        for (q in listOf("what should I do today?", "what should I do today ?", "  What  should I do today !", "How can I make profit"))
            assertTrue(q, ChatFlow.route(q, env) is ChatFlow.Action.Plan)
        for ((q, id) in listOf("hidden patterns" to "anomalies",
                "which strategy works?" to "strategies", "market regime" to "regime", "explain momentum strategy" to "learn", "download market data" to "data")) {
            assertEquals(q, ChatFlow.Action.Term(id, ChatFlow.route(q, env).let { (it as? ChatFlow.Action.Term)?.arg ?: "" }), ChatFlow.route(q, env))
        }
    }

    @Test fun playbookSearchFindsByMeaningfulWords() {
        assertEquals("reversal", Playbook.search("buy the dip when oversold").first().first.id)
        assertEquals("lowvol", Playbook.search("safe defensive stocks").first().first.id)
        assertEquals("trend", Playbook.search("golden cross moving average").first().first.id)
        assertNotNull(Playbook.get("momentum"))
    }
}
