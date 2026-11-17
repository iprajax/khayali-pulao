package com.khayali.pulao.term

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.khayali.pulao.Stock
import com.khayali.pulao.ai.WebData
import com.khayali.pulao.ai.term.Fetch
import java.io.File
import java.time.LocalDate

/** Shared helpers for terminal feature tests: fixtures from src/test/resources/term and a scripted [Fetch]. */
object TermTesting {
    fun fixture(name: String): String = File("src/test/resources/term/$name").readText()
    fun card(json: String): JsonObject = JsonParser.parseString(json).asJsonObject
    fun blocks(json: String) = card(json).getAsJsonObject("card").getAsJsonArray("blocks").map { it.asJsonObject }
    fun viz(json: String, type: String): JsonObject = blocks(json).first { it["type"].asString == "viz" && it["viz"].asString == type }.getAsJsonObject("data")
    fun error(json: String): String? = card(json)["error"]?.asString

    /** A [Fetch] that answers from maps; anything not scripted returns "" (as a dead network would). */
    class Fake(
        private val nse: Map<String, String> = emptyMap(), private val charts: Map<String, String> = emptyMap(), private val pages: Map<String, String> = emptyMap(),
        override val holdings: List<Stock.Holding> = emptyList(), override val today: LocalDate = LocalDate.of(2026, 11, 17),
        private val panel: com.khayali.pulao.ai.data.Panel? = null, override val profile: List<String> = emptyList(),
        override val knowledge: com.khayali.pulao.ai.term.Knowledge? = null,
    ) : Fetch {
        override suspend fun market(days: Int) = panel
        val asked = mutableListOf<String>()
        override suspend fun nse(path: String): String { asked += "nse:$path"; return nse.entries.firstOrNull { path.startsWith(it.key) }?.value.orEmpty() }
        override suspend fun chart(symbol: String, range: String, interval: String): String { asked += "chart:$symbol:$range:$interval"; return charts[symbol].orEmpty() }
        override suspend fun get(url: String): String { asked += "get:$url"; return pages.entries.firstOrNull { url.contains(it.key) }?.value.orEmpty() }
        override suspend fun search(query: String): List<WebData.Result> { asked += "search:$query"; return emptyList() }
        override suspend fun symbol(query: String): String? = query.trim().removePrefix("@").uppercase().takeIf { it.isNotBlank() && !it.contains(' ') }
    }
}
