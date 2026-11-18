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

/**
 * Every chat, kept as private files on the phone (never backed up, never uploaded): chats/index.json lists them and
 * chats/<id>.json holds each one. The chat you were in is remembered, so the app reopens where you left off.
 * The old single chat (chat.json) becomes the first entry. Pinned chats come first, then the most recent.
 */
class ChatLibrary private constructor(ctx: Context) {
    data class Meta(val id: String, val title: String, val created: Long, val updated: Long, val pinned: Boolean = false, val turns: Int = 0)

    private val app = ctx.applicationContext
    private val dir = File(app.filesDir, "chats").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val prefs = app.getSharedPreferences("khayali", Context.MODE_PRIVATE)
    private val gson = com.google.gson.Gson()
    @Volatile private var cache: List<Meta>? = null

    init { migrate() }

    private fun migrate() {
        val old = File(app.filesDir, "chat.json")
        if (!old.exists() || prefs.contains(CURRENT)) return
        val id = newId()
        val at = old.lastModified()                       // read before the move: a moved file has no date
        if (old.renameTo(File(dir, "$id.json"))) {
            val entries = store(id).load()
            if (entries.isNotEmpty()) save(all() + Meta(id, title(entries.first().question), at, at, false, entries.size))
        }
        prefs.edit().putString(CURRENT, id).apply()
    }

    /** The chat on screen. */
    var current: String
        get() = prefs.getString(CURRENT, null) ?: newId().also { prefs.edit().putString(CURRENT, it).apply() }
        set(v) { prefs.edit().putString(CURRENT, v).apply() }

    fun store(id: String = current) = ChatStore(app, "chats/$id.json")

    /** A fresh chat; the one before stays in the list. */
    fun startNew(): String = newId().also { current = it }

    fun all(): List<Meta> = cache ?: synchronized(this) {
        cache ?: runCatching { gson.fromJson(index.readText(), Array<Meta>::class.java).toList() }.getOrDefault(emptyList())
            .map { m -> if (m.updated > 0) m else File(dir, "${m.id}.json").lastModified().let { t -> m.copy(created = t, updated = t) } }   // undated (early build): the file's date
            .let(::sorted).also { cache = it }
    }

    fun meta(id: String) = all().firstOrNull { it.id == id }

    /** After each exchange: add the chat (titled by its first question) or bump it to the top. */
    fun touch(id: String, firstQuestion: String, turns: Int) {
        val now = System.currentTimeMillis()
        val m = meta(id)
        save(if (m == null) all() + Meta(id, title(firstQuestion), now, now, false, turns) else all().map { if (it.id == id) it.copy(updated = now, turns = turns) else it })
    }

    fun rename(id: String, title: String) { val t = title.trim().take(60); if (t.isNotEmpty()) save(all().map { if (it.id == id) it.copy(title = t) else it }) }
    fun pin(id: String, on: Boolean) = save(all().map { if (it.id == id) it.copy(pinned = on) else it })

    /** Deletes a chat's file and its entry (the screen asks first). */
    fun delete(id: String) {
        runCatching { File(dir, "$id.json").delete() }
        save(all().filter { it.id != id })
    }

    /** Titles first, then what was said in each chat — all read on the phone. */
    fun search(q: String): List<Meta> {
        val t = q.trim(); if (t.isEmpty()) return all()
        return all().filter { m -> m.title.contains(t, true) || store(m.id).load().any { it.question.contains(t, true) || it.answer.contains(t, true) } }
    }

    private fun sorted(l: List<Meta>) = l.sortedWith(compareByDescending<Meta> { it.pinned }.thenByDescending { it.updated })

    private fun save(l: List<Meta>) = synchronized(this) {
        // the oldest unpinned chats go first once there are more than MAX
        val keep = sorted(l).let { s -> if (s.size <= MAX) s else s.filter { it.pinned } + s.filterNot { it.pinned }.take(MAX - s.count { it.pinned }) }
        (l - keep.toSet()).forEach { runCatching { File(dir, "${it.id}.json").delete() } }
        cache = sorted(keep)
        runCatching { index.writeText(gson.toJson(keep)) }
    }

    private fun newId() = java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString((Math.random() * 46655).toLong(), 36)

    companion object {
        private const val CURRENT = "chat_current"
        const val MAX = 200
        @Volatile private var instance: ChatLibrary? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: ChatLibrary(ctx).also { instance = it } }

        /** A chat's title: its first question, or a command's plain label ("/pulse" → "Market pulse"). */
        fun title(q: String): String {
            val t = q.trim().let { if (it.startsWith("/")) Labels.of(it) else it }
            return t.replace(Regex("""^[^\p{L}\p{N}@/]+"""), "").replace(Regex("""\s+"""), " ").trim().replaceFirstChar { it.uppercase() }.let { if (it.length > 48) it.take(47).trimEnd() + "…" else it }.ifEmpty { "Chat" }
        }
    }
}
