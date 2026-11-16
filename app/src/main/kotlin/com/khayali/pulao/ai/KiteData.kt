package com.khayali.pulao.ai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.khayali.pulao.Fmt

/**
 * Pure helpers for Zerodha's Kite MCP: the read-only allowlist, MCP message parsing,
 * and shrinking Kite's large JSON into what a small on-device model can use.
 */
object KiteData {
    const val HOST = "mcp.kite.trade"
    const val AUTH_BASE = "https://$HOST"
    const val ENDPOINT = "$AUTH_BASE/mcp"
    /** OAuth redirect: a static page on the project site that hands the code to the app. */
    const val REDIRECT = "https://iprajax.github.io/khayali-pulao/callback.html"

    /** The ONLY Kite MCP tools this app will ever call. No order / GTT writes, ever. */
    val READ_ONLY = setOf(
        "login", "get_profile", "get_margins", "get_holdings", "get_positions", "get_mf_holdings",
        "get_orders", "get_trades", "get_gtts", "get_ltp", "get_quotes", "get_ohlc", "search_instruments",
    )

    fun allowed(tool: String) = tool in READ_ONLY

    /** Account sections the chat may ask for → Kite tool. */
    val SECTIONS = mapOf(
        "holdings" to "get_holdings", "positions" to "get_positions", "margins" to "get_margins",
        "profile" to "get_profile", "mutual_funds" to "get_mf_holdings", "orders" to "get_orders", "trades" to "get_trades",
    )

    /** MCP over HTTP may answer with plain JSON or an SSE stream ("data: {…}"). Returns the JSON-RPC message. */
    fun parseRpc(body: String): JsonObject? {
        val t = body.trim()
        if (t.startsWith("{")) return runCatching { JsonParser.parseString(t).asJsonObject }.getOrNull()
        return t.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
            .mapNotNull { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
            .lastOrNull { it.has("result") || it.has("error") }
    }

    /** Joins the text parts of an MCP tool result. */
    fun resultText(rpc: JsonObject): String {
        rpc.getAsJsonObject("error")?.let { return """{"error": ${Gson().toJson(it.get("message")?.asString ?: "Kite error")}}""" }
        val res = rpc.getAsJsonObject("result") ?: return """{"error":"Empty reply from Kite"}"""
        val parts = res.getAsJsonArray("content")?.mapNotNull { it.asJsonObject.get("text")?.asString } ?: emptyList()
        val text = parts.joinToString("\n")
        return if (res.get("isError")?.asBoolean == true) """{"error": ${Gson().toJson(text.take(300))}}""" else text
    }

    /** Finds a Kite login URL inside a login tool reply. */
    fun loginUrl(text: String): String? =
        Regex("""https://kite\.zerodha\.com/connect/login[^\s"')\]]+""").find(text)?.value

    fun looksLoggedOut(text: String) =
        Regex("""(?i)(not logged in|please log ?in|login required|session (expired|invalid)|token is invalid|TokenException)""").containsMatchIn(text)

    private fun JsonObject.d(k: String) = get(k)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0
    private fun JsonObject.s(k: String) = get(k)?.takeIf { !it.isJsonNull }?.asString ?: ""
    private fun r(v: Double) = Math.round(v).toDouble()
    private fun inr(v: Double) = "₹" + Fmt.amount(v)

    private fun arrayIn(e: JsonElement): JsonArray? = when {
        e.isJsonArray -> e.asJsonArray
        e.isJsonObject -> e.asJsonObject.entrySet().firstNotNullOfOrNull { (_, v) -> if (v.isJsonArray) v.asJsonArray else null }
        else -> null
    }

    /**
     * Turn a raw Kite reply into a compact, model-friendly summary with totals and a one-line
     * "summary" (exact ₹ amounts the UI can render and the number guard can verify).
     */
    fun summarize(section: String, raw: String): String {
        val gson = Gson()
        val el = runCatching { JsonParser.parseString(raw) }.getOrNull()
            ?: return gson.toJson(mapOf("text" to raw.take(1500)))
        if (el.isJsonObject && el.asJsonObject.has("error")) return raw
        return when (section) {
            "holdings" -> {
                val rows = arrayIn(el)?.map { it.asJsonObject } ?: emptyList()
                val items = rows.map { h ->
                    val qty = h.d("quantity") + h.d("t1_quantity")
                    val avg = h.d("average_price"); val ltp = h.d("last_price")
                    val inv = qty * avg; val cur = qty * ltp
                    mapOf("symbol" to h.s("tradingsymbol"), "qty" to qty, "avg_price" to avg, "ltp" to ltp,
                        "invested" to r(inv), "current" to r(cur), "pnl" to r(cur - inv),
                        "pnl_percent" to if (inv > 0) Math.round((cur - inv) / inv * 1000) / 10.0 else 0.0,
                        "day_change_percent" to h.d("day_change_percentage"))
                }.sortedByDescending { it["current"] as Double }
                val inv = items.sumOf { it["invested"] as Double }; val cur = items.sumOf { it["current"] as Double }
                gson.toJson(mapOf(
                    "summary" to "${items.size} holdings: invested ${inr(inv)}, now ${inr(cur)}, P&L ${if (cur >= inv) "+" else "−"}${inr(kotlin.math.abs(cur - inv))}.",
                    "count" to items.size, "invested" to r(inv), "current" to r(cur), "pnl" to r(cur - inv),
                    "pnl_percent" to if (inv > 0) Math.round((cur - inv) / inv * 1000) / 10.0 else 0.0,
                    "largest_weight_percent" to if (cur > 0 && items.isNotEmpty()) Math.round((items[0]["current"] as Double) / cur * 1000) / 10.0 else 0.0,
                    "holdings" to items.take(25)))
            }
            "positions" -> {
                val net = (if (el.isJsonObject) el.asJsonObject.getAsJsonArray("net") else null) ?: arrayIn(el) ?: JsonArray()
                val items = net.map { it.asJsonObject }.filter { it.d("quantity") != 0.0 || it.d("pnl") != 0.0 }.map { p ->
                    mapOf("symbol" to p.s("tradingsymbol"), "product" to p.s("product"), "qty" to p.d("quantity"),
                        "avg_price" to p.d("average_price"), "ltp" to p.d("last_price"), "pnl" to r(p.d("pnl")))
                }
                val pnl = items.sumOf { it["pnl"] as Double }
                gson.toJson(mapOf("summary" to "${items.size} open positions, P&L ${if (pnl >= 0) "+" else "−"}${inr(kotlin.math.abs(pnl))}.",
                    "count" to items.size, "pnl" to r(pnl), "positions" to items.take(25)))
            }
            "mutual_funds" -> {
                val items = (arrayIn(el) ?: JsonArray()).map { it.asJsonObject }.map { m ->
                    val inv = m.d("quantity") * m.d("average_price"); val cur = m.d("quantity") * m.d("last_price")
                    mapOf("fund" to m.s("fund"), "invested" to r(inv), "current" to r(cur), "pnl" to r(cur - inv))
                }
                val inv = items.sumOf { it["invested"] as Double }; val cur = items.sumOf { it["current"] as Double }
                gson.toJson(mapOf("summary" to "${items.size} funds: invested ${inr(inv)}, now ${inr(cur)}.",
                    "invested" to r(inv), "current" to r(cur), "funds" to items.take(25)))
            }
            "margins" -> {
                val eq = if (el.isJsonObject) el.asJsonObject.getAsJsonObject("equity") ?: el.asJsonObject else JsonObject()
                val avail = eq.get("available")?.takeIf { it.isJsonObject }?.asJsonObject
                val net = eq.d("net"); val cash = avail?.d("live_balance") ?: avail?.d("cash") ?: 0.0
                gson.toJson(mapOf("summary" to "Equity margin available ${inr(net)} (cash ${inr(cash)}).", "net" to r(net), "cash" to r(cash)))
            }
            "profile" -> {
                // Only what's useful for a greeting — never echo email/phone/PAN into the model.
                val o = if (el.isJsonObject) el.asJsonObject else JsonObject()
                gson.toJson(mapOf("name" to o.s("user_shortname").ifBlank { o.s("user_name").substringBefore(' ') },
                    "broker" to o.s("broker"), "exchanges" to o.get("exchanges")))
            }
            "orders", "trades" -> {
                val items = (arrayIn(el) ?: JsonArray()).map { it.asJsonObject }.takeLast(20).map { o ->
                    mapOf("symbol" to o.s("tradingsymbol"), "side" to o.s("transaction_type"), "qty" to o.d("quantity"),
                        "price" to (o.d("average_price").takeIf { it > 0 } ?: o.d("price")), "status" to o.s("status"),
                        "time" to (o.s("order_timestamp").ifBlank { o.s("fill_timestamp") }))
                }
                gson.toJson(mapOf("summary" to "${items.size} $section today.", section to items))
            }
            else -> raw.take(3000)
        }
    }
}
