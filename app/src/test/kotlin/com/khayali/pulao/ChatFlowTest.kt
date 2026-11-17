package com.khayali.pulao

import com.khayali.pulao.ai.ChatFlow
import com.khayali.pulao.ai.ChatFlow.Action
import com.khayali.pulao.ai.ChatFlow.Ev
import com.khayali.pulao.ai.ChatFlow.Phase
import com.khayali.pulao.ai.ChatFlow.busy
import com.khayali.pulao.ai.ChatFlow.canStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat lifecycle: every path through the state machine, and where each kind of message is routed. */
class ChatFlowTest {
    private fun run(start: Phase, vararg evs: Ev) = evs.fold(start) { p, e -> ChatFlow.next(p, e) }
    private val all = ChatFlow.Env(modelReady = true, kite = ChatFlow.Kite.CONNECTED, webOn = true)
    private val bare = ChatFlow.Env(modelReady = false, kite = ChatFlow.Kite.OFF, webOn = false)

    @Test fun warmModelTurn() {
        assertEquals(Phase.THINKING, run(Phase.IDLE, Ev.StartModel(engineLoaded = true)))
        assertEquals(Phase.IDLE, run(Phase.IDLE, Ev.StartModel(true), Ev.ToolCall, Ev.ToolResult, Ev.Token, Ev.Token, Ev.Finished))
    }

    @Test fun coldModelTurnWarmsUpFirst() {
        assertEquals(Phase.WARMING, run(Phase.IDLE, Ev.StartModel(engineLoaded = false)))
        assertEquals(Phase.THINKING, run(Phase.IDLE, Ev.StartModel(false), Ev.EngineReady))
        assertEquals(Phase.STREAMING, run(Phase.IDLE, Ev.StartModel(false), Ev.EngineReady, Ev.ToolCall, Ev.ToolResult, Ev.Token))
    }

    @Test fun toolsCanRunMoreThanOnceAndMidAnswer() {
        assertEquals(Phase.THINKING, run(Phase.THINKING, Ev.ToolCall, Ev.ToolResult, Ev.ToolCall, Ev.ToolResult))
        assertEquals(Phase.TOOL, run(Phase.STREAMING, Ev.ToolCall))
    }

    @Test fun appOnlyWorkNeedsNoModel() {
        assertEquals(Phase.FETCHING, run(Phase.IDLE, Ev.StartApp))
        assertEquals(Phase.FETCHING, run(Phase.NEEDS_MODEL, Ev.StartApp))      // commands work before the model is downloaded
        assertEquals(Phase.NEEDS_MODEL, run(Phase.NEEDS_MODEL, Ev.StartApp, Ev.Finished, Ev.ModelMissing))
    }

    @Test fun everyBusyPhaseCanBeStoppedOrFail() {
        for (p in Phase.entries.filter { it.busy }) {
            assertTrue("$p must offer Stop", p.canStop)
            assertEquals(Phase.IDLE, ChatFlow.next(p, Ev.Stopped))
            assertEquals(Phase.IDLE, ChatFlow.next(p, Ev.Failed))
            assertEquals(Phase.IDLE, ChatFlow.next(p, Ev.Reset))
        }
        assertFalse(Phase.IDLE.busy); assertFalse(Phase.NEEDS_MODEL.busy)
    }

    @Test fun nothingStartsWhileBusy() {
        for (p in Phase.entries.filter { it.busy }) {
            assertEquals(p, ChatFlow.next(p, Ev.StartApp))
            assertEquals(p, ChatFlow.next(p, Ev.StartModel(true)))
        }
    }

    @Test fun lateCallbacksCannotCorruptAnIdleChat() {
        // e.g. a token or tool result arriving after Stop
        for (e in listOf(Ev.Token, Ev.ToolCall, Ev.ToolResult, Ev.EngineReady)) assertEquals(Phase.IDLE, ChatFlow.next(Phase.IDLE, e))
        assertEquals(Phase.FETCHING, ChatFlow.next(Phase.FETCHING, Ev.Token))
    }

    @Test fun modelAppearingOrVanishing() {
        assertEquals(Phase.IDLE, ChatFlow.next(Phase.NEEDS_MODEL, Ev.ModelReady))
        assertEquals(Phase.NEEDS_MODEL, ChatFlow.next(Phase.IDLE, Ev.ModelMissing))
        assertEquals(Phase.STREAMING, ChatFlow.next(Phase.STREAMING, Ev.ModelMissing))     // never yank a running answer
        assertEquals(Phase.NEEDS_MODEL, ChatFlow.next(Phase.NEEDS_MODEL, Ev.StartModel(true)))
    }

    // ------------------------------------------------------------------ routing

    @Test fun kiteCommandsNeedALiveLogin() {
        assertEquals(Action.KiteRead("holdings"), ChatFlow.route("/holdings", all))
        assertEquals(Action.ConnectKite("/holdings", expired = false), ChatFlow.route("/holdings", bare))
        // the "woke up next morning" case: the login lapsed overnight → offer reconnect and keep the command
        assertEquals(Action.ConnectKite("/positions", expired = true), ChatFlow.route("/positions", all.copy(kite = ChatFlow.Kite.EXPIRED)))
        assertEquals(Action.ConnectKite(null, expired = true), ChatFlow.route("Reconnect Kite", all.copy(kite = ChatFlow.Kite.EXPIRED)))
    }

    @Test fun chartPrefersKiteThenWebThenAsks() {
        assertEquals(Action.Chart("INFRA", "1y", ChatFlow.Source.KITE), ChatFlow.route("/chart infra 1y", all))
        assertEquals(Action.Chart("INFRA", null, ChatFlow.Source.WEB), ChatFlow.route("/chart INFRA", all.copy(kite = ChatFlow.Kite.OFF)))
        assertEquals(Action.EnableWeb("/chart INFRA"), ChatFlow.route("/chart INFRA", bare))
    }

    @Test fun calculatorsRunWithoutTheModel() {
        val a = ChatFlow.route("/grow 1 lakh 12% 10y", bare) as Action.Tool
        assertEquals("grow_for_years", a.name)
        assertTrue(a.args, a.args.contains("\"yearly_return_percent\":12.0") && a.args.contains("\"years\":10.0") && a.args.contains("1 lakh"))
        assertEquals("compare_with_fd", (ChatFlow.route("/fd 3y", bare) as Action.Tool).name)
        assertTrue((ChatFlow.route("/move -20%", bare) as Action.Tool).args.contains("-20.0"))
        assertEquals("in_hand_after_tax", (ChatFlow.route("/tax", bare) as Action.Tool).name)
        assertTrue((ChatFlow.route("/goal 50 lakh", bare) as Action.Tool).args.contains("50 lakh"))
    }

    @Test fun webCommandsAreOptIn() {
        assertEquals(Action.Web(ChatFlow.WebKind.PRICE, "INFRA"), ChatFlow.route("/price @INFRA", all))
        assertEquals(Action.Web(ChatFlow.WebKind.CALENDAR, "INFRA"), ChatFlow.route("/events INFRA", all))
        assertTrue((ChatFlow.route("/results INFRA", all) as Action.Web).query.contains("quarterly results"))
        assertEquals(Action.EnableWeb("/news INFRA"), ChatFlow.route("/news INFRA", bare))
        assertEquals(Action.Help, ChatFlow.route("/price", all))
    }

    @Test fun freeTextNeedsTheModel() {
        assertEquals(Action.Model("What if INFRA falls 20%?"), ChatFlow.route("  What if INFRA falls 20%?  ", all))
        assertEquals(Action.NeedModel, ChatFlow.route("What if INFRA falls 20%?", bare))
        assertEquals(Action.Ignore, ChatFlow.route("   ", all))
    }

    @Test fun memoryAndHousekeeping() {
        assertEquals(Action.Remember("I invest for 10+ years"), ChatFlow.route("/remember I invest for 10+ years", bare))
        assertEquals(Action.ShowMemory, ChatFlow.route("/memory", bare))
        assertEquals(Action.ForgetAll, ChatFlow.route("/forget", bare))
        assertEquals(Action.Clear, ChatFlow.route("/clear", bare))
        assertEquals(Action.Unknown("buy"), ChatFlow.route("/buy INFRA", all))        // there are no trading commands
    }
}
