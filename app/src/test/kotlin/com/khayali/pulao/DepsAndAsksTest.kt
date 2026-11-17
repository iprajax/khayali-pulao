package com.khayali.pulao

import com.khayali.pulao.ai.Asks
import com.khayali.pulao.ai.ChatFlow
import com.khayali.pulao.ai.ChatFlow.Action
import com.khayali.pulao.ai.term.Terminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepsAndAsksTest {
    private fun env(vararg ready: String) = ChatFlow.Env(modelReady = true, kite = ChatFlow.Kite.OFF, webOn = false, ready = ready.toSet())

    @Test fun commandsWaitForWhatTheyNeed() {
        assertEquals(Action.NeedArtifact("market", "/brief"), ChatFlow.route("/brief", env()))
        assertEquals(Action.NeedArtifact("market", "what should I do today?"), ChatFlow.route("what should I do today?", env()))
        assertEquals(Action.Term("brief", ""), ChatFlow.route("/brief", env("market")))
        assertEquals(Action.NeedArtifact("knowledge", "/screens dividend"), ChatFlow.route("/screens dividend", env("market")))
        assertEquals(Action.Term("screens", "dividend"), ChatFlow.route("/screens dividend", env("knowledge")))
        // things that need nothing are never blocked
        assertEquals(Action.Term("learn", "momentum"), ChatFlow.route("/learn momentum", env()))
        assertEquals(Action.Term("data", "3y"), ChatFlow.route("/data 3y", env()))
        // untracked environments (older callers, tests) behave as before
        assertEquals(Action.Term("brief", ""), ChatFlow.route("/brief", ChatFlow.Env(true, ChatFlow.Kite.OFF, false)))
    }

    @Test fun everyRequirementIsAKnownArtifact() {
        val known = setOf("market", "knowledge")
        Terminal.FEATURES.mapNotNull { it.requires }.forEach { assertTrue(it, it in known) }
        assertTrue(Terminal.FEATURES.count { it.requires == "market" } >= 6)
    }

    @Test fun suggestionsLeadToActions() {
        assertEquals("/brief", Asks.suggest("what should", setOf("market")).first().command)
        assertEquals("/ipo", Asks.suggest("ipo", emptySet()).first().command)
        assertTrue(Asks.suggest("/hol", emptySet()).isEmpty())
        assertTrue(Asks.suggest("", emptySet()).isEmpty())
        // a ready suggestion outranks an equally good one that still needs a download
        val r = Asks.suggest("market", setOf("web")).map { it.command }
        assertTrue(r.toString(), r.indexOf("/report") < r.indexOf("/regime").let { if (it < 0) Int.MAX_VALUE else it })
        // every suggestion runs something real
        Asks.ALL.forEach { a ->
            val act = ChatFlow.route(a.command, ChatFlow.Env(true, ChatFlow.Kite.CONNECTED, true))
            assertTrue("${a.command} -> $act", act !is Action.Unknown && act !is Action.Help)
        }
    }
}
