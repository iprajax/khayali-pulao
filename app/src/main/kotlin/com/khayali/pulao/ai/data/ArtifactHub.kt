package com.khayali.pulao.ai.data

import android.content.Context
import com.khayali.pulao.ai.MemoryStore
import com.khayali.pulao.ai.ModelManager
import com.khayali.pulao.ai.ModelSpec

/** One place that lists and manages everything the app keeps on the phone: the AI model, market history, playbook and memory. */
interface ArtifactHub {
    /** [neededBy]: the commands that can't run without it — the dependency edges shown to the user. */
    data class Item(val id: String, val kind: Artifacts.Kind, val name: String, val state: Artifacts.State, val bytes: Long, val detail: String, val actions: List<String>,
                    val neededBy: List<String> = emptyList())
    fun items(): List<Item>
    /** "1y" / "3y" / "5y" / "update" / "stop" / "delete" for market data. Returns a short confirmation. */
    fun market(cmd: String): String
    /** "get" / "delete" for the knowledge bank. */
    fun knowledge(cmd: String): String = "Not available here."
}

class AndroidArtifactHub(private val ctx: Context) : ArtifactHub {
    private val pack get() = MarketPack.get(ctx)

    private fun neededBy(id: String) = com.khayali.pulao.ai.term.Terminal.FEATURES.filter { it.requires == id }.map { "/${it.id}" }

    override fun knowledge(cmd: String): String {
        val kb = KnowledgePack.get(ctx)
        return when (cmd.trim().lowercase()) {
            "delete" -> { kb.delete(); "Deleted the knowledge bank from this phone." }
            else -> if (kb.ready()) "The knowledge bank is already on this phone." else { kb.install(); "Downloading the knowledge bank (${kb.spec.bytes / 1000} KB)…" }
        }
    }

    override fun items(): List<ArtifactHub.Item> {
        pack.refresh()
        val m = ModelManager.get(ctx); m.refresh()
        val model = when (val s = m.state.value) {
            ModelManager.State.Ready -> Artifacts.State.Ready("Gemma 4 E2B", ModelSpec.SIZE_BYTES, 0)
            is ModelManager.State.Downloading -> if (s.paused) Artifacts.State.Paused(s.reason ?: "waiting", Artifacts.State.Downloading(s.done, s.total)) else Artifacts.State.Downloading(s.done, s.total)
            is ModelManager.State.Verifying -> Artifacts.State.Verifying
            is ModelManager.State.Failed -> Artifacts.State.Failed(s.message)
            else -> Artifacts.State.NotInstalled
        }
        val ms = pack.state.value
        val mem = MemoryStore.get(ctx)
        return listOf(
            ArtifactHub.Item("model", Artifacts.Kind.MODEL, "On-device AI (Gemma 4 E2B)", model, if (model is Artifacts.State.Ready) ModelSpec.SIZE_BYTES else 0,
                "Writes answers and calls tools, entirely on this phone. Managed in Settings.", emptyList(), listOf("plain-language questions")),
            ArtifactHub.Item("market", Artifacts.Kind.DATA, "Market history · ${pack.tier.label}", ms, (ms as? Artifacts.State.Ready)?.bytes ?: (ms as? Artifacts.State.Stale)?.bytes ?: 0,
                "Daily prices of every NSE stock, downloaded by this phone from NSE's public archive. Powers /brief, /anomalies, /strategies.",
                when (ms) { Artifacts.State.NotInstalled, is Artifacts.State.Failed -> listOf("/data 1y", "/data 3y", "/data 5y")
                    is Artifacts.State.Stale -> listOf("/data update", "/data delete"); is Artifacts.State.Ready -> listOf("/data 3y", "/data 5y", "/data delete")
                    else -> listOf("/data stop") }, neededBy("market")),
            KnowledgePack.get(ctx).let { kb -> kb.refresh(); ArtifactHub.Item("knowledge", Artifacts.Kind.INDEX, "Strategy knowledge bank", kb.state.value, if (kb.ready()) kb.spec.bytes else 0,
                "500 community stock screens with their rules, searchable by meaning (EmbeddingGemma 2 vectors). ${kb.spec.bytes / 1000} KB, downloaded on demand.",
                if (kb.ready()) listOf("/screens", "/data knowledge delete") else listOf("/data knowledge"), neededBy("knowledge")) },
            ArtifactHub.Item("playbook", Artifacts.Kind.INDEX, "Strategy playbook", Artifacts.State.Ready("${com.khayali.pulao.ai.term.Playbook.ENTRIES.size} strategies", 0, 0),
                0, "Built in. Searched on the phone by keywords.", listOf("/strategies", "/learn momentum")),
            ArtifactHub.Item("memory", Artifacts.Kind.INDEX, "Your memory", Artifacts.State.Ready("${mem.playbook.bullets.size} notes", 0, 0, if (mem.enabled) "on" else "off"),
                0, "What you asked the chat to remember. Only on this phone.", listOf("/memory")),
        )
    }

    private fun started(n: Int, what: String) = when (n) {
        -1 -> "A download is already running — progress is shown below."
        0 -> "Your market history is already up to date (${pack.tier.label})."
        else -> "$what: $n trading days to fetch. Progress is shown below; you can keep chatting."
    }

    override fun market(cmd: String): String = when (cmd.trim().lowercase()) {
        "1y", "3y", "5y" -> { pack.tier = MarketPack.Tier.valueOf("Y" + cmd.trim().first()); started(pack.install(), "Downloading ${pack.tier.label} of NSE daily data to this phone") }
        "update", "" -> started(pack.install(), "Updating your market history")
        "stop" -> { pack.cancel(); "Stopped. What was downloaded is kept." }
        "delete" -> { pack.delete(); "Deleted your market history from this phone." }
        else -> "Try /data 1y, /data 3y, /data 5y, /data update, /data stop or /data delete."
    }
}
