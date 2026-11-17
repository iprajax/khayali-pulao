package com.khayali.pulao.ai.term

import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The knowledge bank of community stock screens (rules, names, themes, links), with EmbeddingGemma 2
 * vectors computed on a Mac and shipped as int8. The phone can't run EmbeddingGemma 2, so search is
 * hybrid: BM25 keyword search finds anchor screens for the question, then the embeddings add the
 * screens closest in meaning to those anchors. Pure: built from the two asset files' bytes.
 */
class Knowledge(json: String, vectors: ByteArray) {
    data class Screen(val name: String, val query: String, val themes: List<String>, val url: String)
    data class Hit(val screen: Screen, val score: Double, val byMeaning: Boolean)

    val screens: List<Screen>
    val model: String
    private val vec: Array<FloatArray>

    init {
        val o = JsonParser.parseString(json).asJsonObject
        model = o["model"].asString
        screens = o.getAsJsonArray("screens").map { e -> val s = e.asJsonObject
            Screen(s["name"].asString, s["query"].asString, s.getAsJsonArray("themes").map { it.asString }, s["url"].asString) }
        val b = ByteBuffer.wrap(vectors).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.int; val d = b.int
        require(n == screens.size) { "vectors ($n) and screens (${screens.size}) disagree" }
        vec = Array(n) { FloatArray(d) { b.get().toFloat() }.also { v -> val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); if (norm > 0) for (i in v.indices) v[i] /= norm } }
    }

    fun cosine(i: Int, j: Int): Double { val a = vec[i]; val b = vec[j]; var s = 0.0; for (k in a.indices) s += a[k] * b[k]; return s }

    // ---- BM25 over name (twice), rules and themes
    private fun words(s: String) = Regex("""[a-z0-9]+""").findAll(s.lowercase()).map { it.value.removeSuffix("s") }.filter { it.length > 1 && it !in STOP }.toList()
    private val docs by lazy { screens.map { words("${it.name} ${it.name} ${it.query} ${it.themes.joinToString(" ")}") } }
    private val avg by lazy { docs.map { it.size }.average() }
    private val df by lazy { HashMap<String, Int>().also { m -> docs.forEach { d -> d.toSet().forEach { w -> m[w] = (m[w] ?: 0) + 1 } } } }

    private fun bm25(query: String): DoubleArray {
        val q = words(query).toSet(); val n = docs.size.toDouble()
        return DoubleArray(docs.size) { i ->
            val tf = docs[i].groupingBy { it }.eachCount()
            q.sumOf { t -> val f = (tf[t] ?: 0).toDouble(); val dfT = (df[t] ?: 0).toDouble()
                if (f == 0.0) 0.0 else ln(1 + (n - dfT + .5) / (dfT + .5)) * f * 2.2 / (f + 1.2 * (.25 + .75 * docs[i].size / avg)) }
        }
    }

    /** Keyword anchors, then neighbours by meaning. Each hit says whether it came from the words or from the embedding. */
    fun search(query: String, k: Int = 8): List<Hit> {
        val kw = bm25(query); val top = kw.max(); if (top <= 0) return emptyList()
        val anchors = kw.indices.sortedByDescending { kw[it] }.take(3).filter { kw[it] > 0 }
        val score = DoubleArray(screens.size) { i ->
            val lexical = kw[i] / top
            val meaning = anchors.maxOf { a -> cosine(a, i) * kw[a] / top }
            0.55 * lexical + 0.45 * meaning
        }
        return score.indices.sortedByDescending { score[it] }.take(k).map { Hit(screens[it], score[it], kw[it] < top * 0.15) }
    }

    /** The screens closest in meaning to screen [i]. */
    fun similar(i: Int, k: Int = 5): List<Screen> = screens.indices.filter { it != i }.sortedByDescending { cosine(i, it) }.take(k).map { screens[it] }

    /** How often each theme appears across the bank. */
    fun themes(): List<Pair<String, Int>> = screens.flatMap { it.themes }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

    companion object {
        /** The downloaded file: [int32 json length][json][int32 n][int32 dim][int8 vectors], little-endian. */
        fun parse(bytes: ByteArray): Knowledge {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); val n = b.int
            return Knowledge(String(bytes, 4, n, Charsets.UTF_8), bytes.copyOfRange(4 + n, bytes.size))
        }
        private val STOP = setOf("and", "or", "the", "of", "with", "in", "to", "for", "is", "a", "an", "than", "that", "stock", "stocks", "company", "companie", "screen") }
}

/** /screens — search the community screens by idea; shows each screen's rules, themes and link. */
object ScreensFeature : Feature {
    override val requires = "knowledge"
    override val id = "screens"; override val usage = "/screens quality low debt"; override val group = Group.STOCK; override val needsWeb = false
    override val help = "Search 500 community stock screens by idea: their rules, themes and where they come from"
    override val triggers = listOf(Regex("""(?i)^(find|show|search) (me )?(stock )?screens? (for|about|like) .+$"""))
    override suspend fun run(arg: String, f: Fetch): String {
        val kb = f.knowledge ?: return Card.error("The screens knowledge bank isn't available here.")
        val q = arg.replace(Regex("""(?i)^(find|show|search) (me )?(stock )?screens? (for|about|like) """), "").trim()
        if (q.isEmpty()) {
            val th = kb.themes()
            return Card("Community screens · ${kb.screens.size}", "violet")
                .headline("What ${kb.screens.size} public screens look for", "Most screens combine several ideas; a screen can count in more than one theme.")
                .bars("Screens by theme", th.map { Card.Bar(it.first, it.second * 100.0 / kb.screens.size, "${it.second} screens") }, "%")
                .note("Search one idea: /screens quality low debt · /screens dividend · /screens breakout. Thresholds and rules are the screen authors'; this is not advice.")
                .sources("screener.in public screens").pills("/screens quality low debt", "/screens dividend", "/screens promoter", "/learn")
                .json("${kb.screens.size} community screens; most common themes: " + th.take(5).joinToString(", ") { "${it.first} (${it.second})" } + ".")
        }
        val hits = kb.search(q)
        if (hits.isEmpty()) return Card.error("No screen matches \"$q\". Try words like quality, debt, dividend, growth, promoter, breakout.", "/screens", "/learn")
        val learn = Playbook.search(q, 1).firstOrNull()?.first
        return Card("Community screens · \"$q\"", "violet")
            .headline("${hits.size} closest screens", "Found by keywords, then by meaning with ${kb.model.substringAfter('/')} (marked ≈)")
            .rows(null, hits.map { h -> Card.Row((if (h.byMeaning) "≈ " else "") + h.screen.name.lowercase().replaceFirstChar { it.uppercase() },
                h.screen.themes.joinToString(" · ").ifBlank { "Other" } + "\n" + h.screen.query.take(140) + if (h.screen.query.length > 140) "…" else "") })
            .note("Rules are written by each screen's author on screener.in. They need company financials; check a candidate's price trend here with /health SYMBOL. Not advice.")
            .sources("screener.in public screens").pills(*listOfNotNull(learn?.let { "/learn ${it.id}" }, "/screens", "/strategies").toTypedArray())
            .put("links", hits.map { it.screen.url })
            .json("Closest screens: " + hits.take(3).joinToString(", ") { it.screen.name.lowercase().replaceFirstChar { c -> c.uppercase() } } + ". " +
                "Most of them look for " + hits.flatMap { it.screen.themes }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(2).joinToString(" and ") { it.key.lowercase() } + ".")
    }
}
