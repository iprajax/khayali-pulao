package com.khayali.pulao.ai.term

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.khayali.pulao.Stock
import com.khayali.pulao.ai.WebData
import java.time.LocalDate

/**
 * The "terminal": a registry of self-contained market features. Each feature turns public,
 * no-login data into ONE card (headline, charts, stats, rows) described as JSON, which a
 * single generic renderer draws. A feature never opens a connection itself — it asks [Fetch],
 * so every feature is pure, unit-testable with a fake, and bound to the host allow-list.
 */
interface Fetch {
    val today: LocalDate
    /** The user's holdings (from Kite, if connected). Used on the phone only — never put them in a request. */
    val holdings: List<Stock.Holding>
    /** One NSE API feed as JSON text, e.g. nse("allIndices") or nse("fiidiiTradeReact"). "" when unavailable. */
    suspend fun nse(path: String): String
    /** Quote-feed chart JSON for a feed symbol: "INFY.NS", "^NSEI", "USDINR=X", "GC=F". "" when unavailable. */
    suspend fun chart(symbol: String, range: String, interval: String): String
    /** Plain GET from an allow-listed host (see Hosts). Throws for any other host. */
    suspend fun get(url: String): String
    /** Web search results (DuckDuckGo). */
    suspend fun search(query: String): List<WebData.Result>
    /** "infosys" / "@infy" / "Tata Motors" → NSE symbol, or null. */
    suspend fun symbol(query: String): String?
    /** Symbols the user has looked at in chat (most recent first), kept on the phone. Never put them in a search query. */
    val watched: List<String> get() = emptyList()
    /**
     * Intraday (or [range]) closes for up to 20 feed symbols per call, e.g. "INFY.NS", "^NSEI" → closes and the previous close.
     * Missing symbols are simply absent from the result.
     */
    suspend fun spark(symbols: List<String>, range: String = "1d", interval: String = "5m"): Map<String, Spark> = emptyMap()
    /** The user's own downloaded market history (last [days] trading days), or null if it isn't installed. Local — no network. */
    suspend fun market(days: Int = 300): com.khayali.pulao.ai.data.Panel? = null
    /** Official NSE sector index history, valuations and members, from the phone's own store (null if not downloaded). */
    suspend fun sectors(): com.khayali.pulao.ai.data.MarketPack.SectorBook? = null
    /** The user's price alerts, kept on the phone (null in tests without it). */
    val alerts: com.khayali.pulao.ai.data.Alerts.Store? get() = null
    /** The user's web switch. Features that work offline must check it before any lookup. */
    val webOn: Boolean get() = false
    /** symbol → company name (Nifty 500) from the phone's store, for linking news to stocks. */
    suspend fun names(): Map<String, String> = emptyMap()
    /** The community-screens knowledge bank shipped with the app (null where assets aren't available). */
    val knowledge: com.khayali.pulao.ai.term.Knowledge? get() = null
    /** Models and data kept on the phone (null in tests without it). */
    val artifacts: com.khayali.pulao.ai.data.ArtifactHub? get() = null
    /** What the user told the chat about themselves (goal, horizon, risk), from memory. */
    val profile: List<String> get() = emptyList()
}

/** A small price series for a sparkline: closes in order, the previous session's close, and the latest price. */
data class Spark(val closes: List<Double>, val prevClose: Double?, val last: Double?) {
    val change get() = if (prevClose != null && prevClose > 0 && last != null) (last / prevClose - 1) * 100 else null
}

enum class Group(val title: String) {
    MARKET("Market"), STOCK("Stock"), PORTFOLIO("Portfolio"), DERIVATIVES("Derivatives"), FUNDS("Funds & gold"), MACRO("Global & macro"), TOOLS("Tools")
}

interface Feature {
    /** Command name without the slash, lower-case, e.g. "heatmap". */
    val id: String
    /** How to type it, e.g. "/heatmap NIFTY BANK". */
    val usage: String
    /** One line for the command list. */
    val help: String
    val group: Group
    /** False for features that work fully offline (pure calculators, or ones that only need the user's holdings). */
    val needsWeb: Boolean get() = true
    /** How the card is made — data, universe, method, parameters — for the reply's "How this was made" panel. */
    val method: String get() = ""
    /** The artifact it can't work without ("market", "knowledge"), or null. The chat offers that download first. */
    val requires: String? get() = null
    /** Plain-language phrases that mean this feature; used to route short questions without the model. */
    val triggers: List<Regex> get() = emptyList()
    /** Returns card JSON built with [Card], or [Card.error]. Must not throw for expected failures (no data, bad symbol). */
    suspend fun run(arg: String, f: Fetch): String
}

/** Builder for the one JSON shape every feature returns. Keep cards short: a headline, 1–3 charts, a few rows. */
class Card(private val kicker: String, private val accent: String = "blue") {
    /** [action]: a command the row runs when tapped (a value that is itself a command counts too). */
    data class Row(val title: String, val sub: String = "", val value: String = "", val tone: String = "", val action: String = "")

    private var headline: String? = null
    private var sub: String? = null
    private var tone: String = ""
    private val blocks = mutableListOf<Map<String, Any?>>()
    private val pills = mutableListOf<String>()
    private val sources = mutableListOf<String>()
    private val extra = mutableMapOf<String, Any?>()

    /** The big figure and the line under it. [tone] = "up" | "down" | "" colours the sub line. */
    fun headline(text: String, sub: String? = null, tone: String = "") = apply { headline = text; this.sub = sub; this.tone = tone }
    /** A D3 chart. [type] is a renderer name in assets/viz; [data] is that renderer's input; [caption] names what the axes show. */
    fun viz(type: String, height: Int, data: Any, caption: String? = null) = apply { blocks += mapOf("type" to "viz", "viz" to type, "height" to height, "data" to data, "caption" to caption) }
    /** Up to ~6 labelled figures in a grid. */
    fun stats(vararg items: Pair<String, String>) = apply { if (items.isNotEmpty()) blocks += mapOf("type" to "stats", "items" to items.map { mapOf("label" to it.first, "value" to it.second) }) }
    /** A short list (keep to ≤ 10 rows). Row.tone = "up" | "down" | "warn" | "" colours the value. */
    fun rows(title: String?, rows: List<Row>) = apply { if (rows.isNotEmpty()) blocks += mapOf("type" to "rows", "title" to title, "rows" to rows.map { mapOf("title" to it.title, "sub" to it.sub, "value" to it.value, "tone" to it.tone, "action" to it.action) }) }
    fun note(text: String) = apply { blocks += mapOf("type" to "note", "text" to text) }

    // ---- native blocks: drawn by the app itself (crisp text, colour-coded), not by a web chart ----
    data class Tick(val label: String, val value: String, val change: Double?)
    data class Mover(val symbol: String, val sub: String = "", val price: Double? = null, val change: Double? = null, val spark: List<Double> = emptyList(), val base: Double? = null, val mine: Boolean = false)
    data class Bar(val label: String, val value: Double, val sub: String = "")
    data class Kpi(val label: String, val value: String, val change: Double? = null, val sub: String = "")

    /** A scrolling ticker tape of labels, values and colour-coded changes. */
    fun tape(items: List<Tick>) = apply { if (items.isNotEmpty()) blocks += mapOf("type" to "tape", "items" to items.map { mapOf("label" to it.label, "value" to it.value, "change" to it.change) }) }
    /** Rows of symbol · sparkline · price · coloured change pill. [base] draws a dashed previous-close line. */
    fun movers(title: String?, rows: List<Mover>, tone: String = "") = apply { if (rows.isNotEmpty()) blocks += mapOf("type" to "movers", "title" to title, "tone" to tone, "rows" to rows.map {
        mapOf("symbol" to it.symbol, "sub" to it.sub, "price" to it.price, "change" to it.change, "spark" to it.spark, "base" to it.base, "mine" to it.mine) }) }
    /** Diverging horizontal bars with crisp labels, e.g. sectors by % change. */
    fun bars(title: String?, rows: List<Bar>, unit: String = "%") = apply { if (rows.isNotEmpty()) blocks += mapOf("type" to "bars", "title" to title, "unit" to unit,
        "rows" to rows.map { mapOf("label" to it.label, "value" to it.value, "sub" to it.sub) }) }
    /** Coloured stat tiles (2 or 3 to a row). */
    fun kpis(items: List<Kpi>) = apply { if (items.isNotEmpty()) blocks += mapOf("type" to "kpis", "items" to items.map { mapOf("label" to it.label, "value" to it.value, "change" to it.change, "sub" to it.sub) }) }
    /** Advances / declines / unchanged as one proportional bar. */
    /** A half-circle gauge, 0–100, coloured from red (fear) to green (greed), with a needle at [value]. */
    fun gauge(title: String, value: Double, label: String, sub: String = "", left: String = "Fear", right: String = "Greed") =
        apply { blocks += mapOf("type" to "gauge", "title" to title, "value" to value, "label" to label, "sub" to sub, "left" to left, "right" to right) }
    fun breadth(title: String, up: Int, down: Int, flat: Int) = apply { blocks += mapOf("type" to "breadth", "title" to title, "up" to up, "down" to down, "flat" to flat) }
    /** A coloured section heading that separates parts of a long card. */
    fun section(title: String, tone: String = "") = apply { blocks += mapOf("type" to "section", "title" to title, "tone" to tone) }
    /** Follow-up commands that make sense after this card, most useful first (≤ 5). */
    fun pills(vararg p: String) = apply { pills += p }
    /** Where the data came from, e.g. "NSE", "Yahoo Finance". Shown on the card. */
    fun sources(vararg names: String) = apply { sources += names }
    /** Extra top-level fields for the model's compact copy (small scalars only). */
    fun put(key: String, value: Any?) = apply { extra[key] = value }

    /**
     * [summary] is what the user reads under the card and what the model is told: one or two exact
     * sentences with the key figures and the source. Every number in it must come from the data.
     */
    fun json(summary: String): String = gson.toJson(extra + mapOf(
        "summary" to summary, "pills" to pills, "sources" to sources,
        "card" to mapOf("kicker" to kicker, "accent" to accent, "headline" to headline, "sub" to sub, "tone" to tone, "blocks" to blocks)))

    companion object {
        private val gson = Gson()
        fun error(message: String): String = gson.toJson(mapOf("error" to message))
        /** An error with buttons that fix it, e.g. error("Needs market history", "/data 1y", "/data 3y"). Shown as tappable actions under the message. */
        fun error(message: String, vararg actions: String): String = gson.toJson(mapOf("error" to message, "actions" to actions.toList()))
    }
}

/** Small JSON reading helpers so features stay short and never crash on a missing field. */
object J {
    fun parse(text: String): JsonElement? = runCatching { JsonParser.parseString(text) }.getOrNull()
    fun obj(text: String): JsonObject? = parse(text)?.takeIf { it.isJsonObject }?.asJsonObject
    fun arr(text: String, key: String? = null): List<JsonObject> {
        val el = parse(text) ?: return emptyList()
        val a: JsonArray? = when { el.isJsonArray -> el.asJsonArray; key != null && el.isJsonObject -> el.asJsonObject.get(key)?.takeIf { it.isJsonArray }?.asJsonArray; else -> null }
        return a?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()
    }
    fun JsonObject.s(k: String): String = get(k)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim().orEmpty()
    /** Number from a field that may be a number or a string like "1,234.50". */
    fun JsonObject.d(k: String): Double? = get(k)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let { e ->
        runCatching { if (e.asJsonPrimitive.isNumber) e.asDouble else e.asString.replace(",", "").trim().toDouble() }.getOrNull()
    }
    fun JsonObject.o(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
    fun JsonObject.a(k: String): List<JsonObject> = get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()
}

/** Number wording shared by every feature. */
object N {
    fun pct(v: Double, digits: Int = 1) = (if (v > 0) "+" else if (v < 0) "−" else "") + String.format(java.util.Locale.US, "%.${digits}f", kotlin.math.abs(v)) + "%"
    fun num(v: Double, digits: Int = 2) = com.khayali.pulao.Fmt.let { if (digits == 0) it.amount(v) else it.price(v) }
    fun inr(v: Double, digits: Int = 0) = (if (v < 0) "−₹" else "₹") + num(kotlin.math.abs(v), digits)
    /** ₹ crore, the unit Indian filings use: 12345.6 → "₹12,346 Cr". */
    fun cr(v: Double) = (if (v < 0) "−₹" else "₹") + com.khayali.pulao.Fmt.amount(kotlin.math.abs(v)) + " Cr"
    fun tone(v: Double) = if (v > 0) "up" else if (v < 0) "down" else ""
}
