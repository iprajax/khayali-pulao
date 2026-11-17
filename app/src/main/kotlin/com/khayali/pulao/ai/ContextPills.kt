package com.khayali.pulao.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pills curated from what is on screen right now — the last result and the user's real
 * holdings — instead of a fixed list. Pure & unit-tested; the model adds its own on top.
 */
object ContextPills {
    data class Ctx(val kiteConnected: Boolean, val symbols: List<String>, val lastTool: String?, val lastJson: String?, val lastQuestion: String?, val webOn: Boolean = false,
        /** The user's own goal, from memory — pills connect results back to it. */
        val goal: String? = null)

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
        // Terminal cards say for themselves what makes sense next.
        if (c.lastTool?.startsWith("term_") == true) {
            o?.getAsJsonArray("pills")?.mapNotNull { runCatching { it.asString }.getOrNull() }?.filter { it.isNotBlank() }?.let { out += it }
            return out.distinct().take(6)
        }
        when (c.lastTool) {
            "kite_account" -> when {
                o?.has("holdings") == true -> {
                    val rows = o.getAsJsonArray("holdings").map { it.asJsonObject }
                    rows.maxByOrNull { it["pnl_percent"].asDouble }?.let { out += "/chart ${it["symbol"].asString} 1y" }
                    if (c.webOn) rows.firstOrNull()?.let { out += "/events ${it["symbol"].asString}" }
                    rows.minByOrNull { it["pnl_percent"].asDouble }?.takeIf { rows.size > 1 }?.let { out += "Why is @${it["symbol"].asString} lagging?" }
                    top?.let { out += "What if @$it moves +20%?" }
                    if ((o.get("largest_weight_percent")?.asDouble ?: 0.0) >= 40) out += "How risky is having one big holding?"
                    out += "/fd 3"; out += "/tax"
                }
                o?.has("positions") == true -> { out += "/holdings"; out += "/margins" }
                o?.has("net") == true -> { out += "/positions"; out += "/orders" }
                else -> out += "/holdings"
            }
            "kite_chart", "web_quote" -> {
                val sym = o?.get("symbol")?.asString ?: top
                val per = o?.get("period")?.asString ?: "6m"
                listOf("1m", "6m", "1y", "5y").filter { it != per }.take(if (c.webOn) 2 else 3).forEach { out += "/chart $sym $it" }
                sym?.let { if (it in c.symbols) out += "What if @$it moves +20%?" }
                if (c.webOn && sym != null && !sym.startsWith("^")) { out += "/events $sym"; out += "/news $sym" }
            }
            "web_events" -> o?.get("company")?.asString?.let { out += "/price $it"; out += "/results $it"; out += "/news $it" }
            "web_calendar" -> {
                val rows = o?.getAsJsonArray("events")?.map { it.asJsonObject }.orEmpty()
                val one = o?.get("title")?.asString?.takeIf { it != "Market calendar" }
                if (one != null) { out += "/price $one"; out += "/news $one"; out += "/calendar" }
                else {
                    // your own stocks first, then the day's biggest themes
                    rows.filter { it["mine"]?.asBoolean == true }.map { it["symbol"].asString }.distinct().take(2).forEach { out += "/events $it" }
                    if (o?.get("from")?.asString == o?.get("to")?.asString) out += "/calendar week" else out += "/results tomorrow"
                    out += "/ipo"
                    if (rows.size >= 20) out += "/calendar today" else out += "/calendar month"
                }
            }
            "web_ipo" -> {
                // one pill per issue in play, nearest first: its grey-market trend is the natural next look
                o?.getAsJsonArray("ipos")?.map { it.asJsonObject }?.filter { it["stage"]?.asString != "Listed" }?.take(3)
                    ?.forEach { out += "/gmp ${it["name"].asString.removeSuffix(" Limited").split(' ').take(2).joinToString(" ")}" }
                out += "/calendar"
            }
            "web_gmp" -> {
                val name = o?.get("name")?.asString
                if (name != null) { out += "/news ${name.split(' ').take(2).joinToString(" ")} IPO"; out += "/ipo"; out += "/gmp" } else { out += "/ipo"; out += "/calendar" }
            }
            "web_search" -> if (c.kiteConnected) top?.let { out += "/news $it" }
            "grow_for_years", "goal_plan" -> if (c.kiteConnected) out += "Do this with my real portfolio"
            "compare_with_fd" -> out += "/tax"
            "in_hand_after_tax" -> out += "/goal 1 crore"
        }
        if (c.goal != null && c.lastTool in setOf("grow_for_years", "project_move", "compare_with_fd", "kite_account", "get_current_stock") && Money.parse(c.goal.substringBefore(" by ").trim()) != null)
            out.add(minOf(1, out.size), "/goal ${c.goal.substringBefore(" by ").trim()}")
        if (c.kiteConnected && out.none { it.startsWith("/holdings") } && c.lastTool != "kite_account") out += "/holdings"
        if (!c.kiteConnected && out.size < 3) out += "Connect Kite to chat about your real portfolio"
        return out.distinct().take(6)
    }

    data class H(val symbol: String, val current: Double, val pnlPercent: Double)

    /** "my biggest holding", "my worst stock"… → which holding the user means, or null. */
    fun pick(text: String, holdings: List<com.khayali.pulao.Stock.Holding>): Pair<String, com.khayali.pulao.Stock.Holding>? {
        if (holdings.isEmpty() || !Regex("""(?i)\b(holding|stock|share|position|performer)s?\b""").containsMatchIn(text)) return null
        return when {
            Regex("""(?i)\b(biggest|largest|top|main)\b""").containsMatchIn(text) -> "biggest" to holdings.maxBy { it.current }
            Regex("""(?i)\b(smallest)\b""").containsMatchIn(text) -> "smallest" to holdings.minBy { it.current }
            Regex("""(?i)\b(best|winner|most profitable)\b""").containsMatchIn(text) -> "best-performing" to holdings.maxBy { it.pnlPct }
            Regex("""(?i)\b(worst|loser|lagging|laggard)\b""").containsMatchIn(text) -> "worst-performing" to holdings.minBy { it.pnlPct }
            else -> null
        }
    }

    /** Opening pills built from the user's live holdings and persona — never a fixed list. */
    fun starters(holdings: List<H>, persona: Persona, kiteConnected: Boolean, webOn: Boolean = false): List<String> {
        if (!kiteConnected) return listOf("Connect Kite to chat about your real portfolio") +
            (if (webOn) listOf("/calendar", "/ipo", "/price NIFTY") else emptyList()) +
            listOf("/grow 1 lakh 12% 10y", "How long to turn ₹10 lakh into ₹1 crore?")
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
        if (webOn) { out += "/calendar"; out += "/events ${big.symbol}" }
        if (holdings.size > 1 && worst.symbol != best.symbol) out += if (webOn) "/news ${worst.symbol}" else "Why is @${worst.symbol} lagging?"
        out += "/holdings"
        return out.distinct().take(7)
    }

    /** Parses the model's "one suggestion per line" reply into clean, short pills. */
    fun parseModel(text: String, max: Int = 3): List<String> =
        text.lines().map { it.trim().replace(Regex("""^(?:[-*•]|\d+[.)])\s+"""), "").trim().trim('"') }
            .filter { it.length in 4..60 && !it.contains("suggest", true) && !it.contains(":") || it.startsWith("/") }
            .filter { it.length in 4..60 }
            .distinct().take(max)
}
