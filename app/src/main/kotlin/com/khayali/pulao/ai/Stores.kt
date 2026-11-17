package com.khayali.pulao.ai

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** The memory playbook, kept in a private file on the phone (never backed up, never uploaded). */
class MemoryStore private constructor(ctx: Context) {
    private val file = File(ctx.applicationContext.filesDir, "memory.json")
    private val prefs = ctx.applicationContext.getSharedPreferences("khayali", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("memory_on", true)
        set(v) { prefs.edit().putBoolean("memory_on", v).apply() }

    @Volatile private var cached: Ace.Playbook? = null

    var playbook: Ace.Playbook
        get() = cached ?: synchronized(this) { cached ?: Ace.fromJson(runCatching { file.readText() }.getOrNull()).also { cached = it } }
        set(v) = synchronized(this) { cached = v; runCatching { file.writeText(Ace.toJson(v)) } }

    /** Reflect → curate in one step. Returns what was actually added or strengthened. */
    fun learn(deltas: List<Ace.Delta>): Int {
        if (!enabled || deltas.isEmpty()) return 0
        val before = playbook
        val after = Ace.curate(before, deltas, System.currentTimeMillis())
        if (after != before) playbook = after
        return after.bullets.size - before.bullets.size
    }

    fun feedback(ids: Collection<Int>, good: Boolean) {
        if (ids.isEmpty()) return
        playbook = Ace.feedback(playbook, ids, good, System.currentTimeMillis())
    }

    fun select(query: String): List<Ace.Bullet> = if (enabled) Ace.select(playbook, query) else emptyList()
    fun remove(id: Int) { playbook = Ace.remove(playbook, id) }
    fun clear() { playbook = Ace.Playbook() }

    companion object {
        @Volatile private var instance: MemoryStore? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: MemoryStore(ctx).also { instance = it } }
    }
}

/**
 * The current chat, saved so it survives the app being closed or killed by Android.
 * Private file, cleared by "New chat"; oversized tool results are stored without their chart data.
 */
class ChatStore(ctx: Context, name: String = "chat.json") {
    data class Entry(val question: String, val answer: String, val tool: String? = null, val json: String? = null)

    private val file = File(ctx.applicationContext.filesDir, name)

    fun load(): List<Entry> = runCatching {
        JsonParser.parseString(file.readText()).asJsonArray.map { it.asJsonObject }.map { o ->
            Entry(o["q"].asString, o["a"].asString, o["tool"]?.takeIf { !it.isJsonNull }?.asString, o["json"]?.takeIf { !it.isJsonNull }?.asString)
        }
    }.getOrDefault(emptyList())

    fun save(entries: List<Entry>) {
        runCatching {
            file.writeText(JsonArray().apply {
                entries.takeLast(MAX).forEach { e ->
                    add(JsonObject().apply {
                        addProperty("q", e.question.take(600)); addProperty("a", e.answer.take(4000))
                        if (e.tool != null && e.json != null && e.json.length <= MAX_JSON) { addProperty("tool", e.tool); addProperty("json", e.json) }
                    })
                }
            }.toString())
        }
    }

    fun clear() { runCatching { file.delete() } }

    companion object { const val MAX = 20; const val MAX_JSON = 120_000 }
}
