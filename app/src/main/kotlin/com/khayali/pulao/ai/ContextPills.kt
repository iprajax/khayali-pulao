package com.khayali.pulao.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pills curated from what is on screen right now — the last result and the user's real
 * holdings — instead of a fixed list. Pure & unit-tested; the model adds its own on top.
 */
object ContextPills {
    data class Ctx(val kiteConnected: Boolean, val symbols: List<String>, val lastTool: String?, val lastJson: String?, val lastQuestion: String?)

    fun build(c: Ctx): List<String> {
        val o = c.lastJson?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
        val err = o?.get("error")?.asString
        val top = c.symbols.firstOrNull()
        val out = mutableListOf<String>()
        if (err != null) {
            if (!c.kiteConnected) out += "Connect Kite to chat about your real portfolio"
            else out += "Try again"
            return out
        }
        when (c.lastTool) {
            "kite_account" -> when {
                o?.has("holdings") == true -> {
                    val rows = o.getAsJsonArray("holdings").map { it.asJsonObject }
                    rows.maxByOrNull { it["pnl_percent"].asDouble }?.let { out += "/chart ${it["symbol"].asString} 1y" }
                    rows.minByOrNull { it["pnl_percent"].asDouble }?.takeIf { rows.size > 1 }?.let { out += "Why is @${it["symbol"].asString} lagging?" }
                    top?.let { out += "What if @$it moves +20%?" }
                    if ((o.get("largest_weight_percent")?.asDouble ?: 0.0) >= 40) out += "How risky is having one big holding?"
                    out += "/fd 3"; out += "/tax"
                }
                o?.has("positions") == true -> { out += "/holdings"; out += "/margins" }
                o?.has("net") == true -> { out += "/positions"; out += "/orders" }
                else -> out += "/holdings"
            }
            "kite_chart" -> {
                val sym = o?.get("symbol")?.asString ?: top
                val per = o?.get("period")?.asString ?: "6m"
                listOf("1m", "6m", "1y", "5y").filter { it != per }.forEach { out += "/chart $sym $it" }
                sym?.let { if (it in c.symbols) out += "What if @$it moves +20%?" }
            }
            "grow_for_years", "goal_plan" -> if (c.kiteConnected) out += "Do this with my real portfolio"
            "compare_with_fd" -> out += "/tax"
            "in_hand_after_tax" -> out += "/goal 1 crore"
        }
        if (c.kiteConnected && out.none { it.startsWith("/holdings") } && c.lastTool != "kite_account") out += "/holdings"
        if (!c.kiteConnected && out.size < 3) out += "Connect Kite to chat about your real portfolio"
        return out.distinct().take(6)
    }

    data class H(val symbol: String, val current: Double, val pnlPercent: Double)

    /** Opening pills built from the user's live holdings and persona — never a fixed list. */
    fun starters(holdings: List<H>, persona: Persona, kiteConnected: Boolean): List<String> {
        if (!kiteConnected) return listOf("Connect Kite to chat about your real portfolio",
            "What does ₹1 lakh become at 12% for 10 years?", "How long to turn ₹10 lakh into ₹1 crore?")
        if (holdings.isEmpty()) return listOf("/holdings", "/margins", "/positions")
        val total = holdings.sumOf { it.current }
        val big = holdings.maxBy { it.current }
        val best = holdings.maxBy { it.pnlPercent }
        val worst = holdings.minBy { it.pnlPercent }
        val out = mutableListOf<String>()
        when (persona) {
            Persona.PLANNER -> {
                out += "How long until my portfolio reaches ₹${com.khayali.pulao.Fmt.money(com.khayali.pulao.Dreams.niceCeil(total * 2), true)}?"
                out += "Did my portfolio beat an FD over 3 years?"
                out += "Tax if I sold everything today?"
            }
            Persona.ADVISOR -> {
                if (total > 0 && big.current / total >= 0.4) out += "How risky is ${(big.current / total * 100).toInt()}% in @${big.symbol}?"
                out += "/chart ${big.symbol} 1y"
                out += "What if @${big.symbol} falls 20%?"
            }
            Persona.ANALYST -> {
                out += "How is my portfolio doing?"
                out += "What if @${big.symbol} moves +15%?"
                out += "/chart ${best.symbol} 6m"
            }
        }
        if (holdings.size > 1 && worst.symbol != best.symbol) out += "Why is @${worst.symbol} lagging?"
        out += "/holdings"
        return out.distinct().take(5)
    }

    /** Parses the model's "one suggestion per line" reply into clean, short pills. */
    fun parseModel(text: String, max: Int = 3): List<String> =
        text.lines().map { it.trim().trimStart('-', '*', '•', '1', '2', '3', '4', '.', ')', ' ').trim().trim('"') }
            .filter { it.length in 4..60 && !it.contains("suggest", true) && !it.contains(":") || it.startsWith("/") }
            .filter { it.length in 4..60 }
            .distinct().take(max)
}
