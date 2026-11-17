package com.khayali.pulao

import com.khayali.pulao.ai.Labels
import com.khayali.pulao.ai.data.ArtifactHub
import com.khayali.pulao.ai.data.Artifacts
import com.khayali.pulao.ai.term.Terminal
import com.khayali.pulao.term.TermTesting
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deleting data is never one tap, and every data action says what it does. */
class DataSafetyTest {
    private class Hub : ArtifactHub {
        val calls = mutableListOf<String>()
        override fun items() = listOf(ArtifactHub.Item("market", Artifacts.Kind.DATA, "Market history", Artifacts.State.Ready("5y", 130_000_000, 0), 130_000_000, "", listOf("/data update", "/data 5y")))
        override fun market(cmd: String): String { calls += "market:$cmd"; return "ok" }
        override fun knowledge(cmd: String): String { calls += "knowledge:$cmd"; return "ok" }
    }
    private fun run(arg: String, hub: Hub) = runBlocking { Terminal.find("data")!!.run(arg, object : com.khayali.pulao.ai.term.Fetch by TermTesting.Fake() { override val artifacts = hub }) }

    @Test fun aBareDeleteOnlyAsks() {
        val hub = Hub()
        val ask = run("delete", hub)
        assertTrue(hub.calls.isEmpty())
        assertTrue(ask.contains("Delete your market history?") && ask.contains("/data delete confirm") && ask.contains("130 MB"))
        run("knowledge delete", hub); assertTrue(hub.calls.isEmpty())
        run("delete confirm", hub); assertEquals(listOf("market:delete"), hub.calls)
        run("knowledge delete confirm", hub); assertEquals(listOf("market:delete", "knowledge:delete"), hub.calls)
    }

    @Test fun noPillOrNextStepDeletes() {
        assertFalse(run("", Hub()).contains("/data delete\""))
        assertEquals("🗑 Delete market history", Labels.of("/data delete"))
        assertEquals("🔄 Update market history", Labels.of("/data update"))
        assertEquals("📦 Models & data", Labels.of("/data"))
        assertEquals("📚 Get the knowledge bank", Labels.of("/data knowledge"))
    }
}
