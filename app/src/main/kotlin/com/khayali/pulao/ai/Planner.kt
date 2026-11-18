package com.khayali.pulao.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The harness's planner: a broad question becomes a short workflow of tool steps instead of one lookup.
 * Each step is an app command; later steps can use what earlier ones found ({top}: the most unusual
 * stock found so far; {best}: the strategy that tested best). The chat runs the steps in order, shows
 * every card, then wraps up with what it shows and what to do next. Pure and unit-tested.
 */
object Planner {
    /** [needs]: "market", "knowledge", "kite" or "web" — steps whose need isn't met are skipped with a fix. */
    data class Step(val title: String, val command: String, val needs: String? = null)
    data class Plan(val goal: String, val steps: List<Step>, val caution: String? = null)

    private const val NO_CALLS = "I can't tell you to buy or sell — here is what to weigh."

    private fun r(p: String) = Regex("""(?i)^\s*(please |can you |could you |hey |ok )?(?:$p)\s*[?.!]*\s*$""")
    private val DAY = r("""what should i (do|look at|focus on)( today| now| this week)?|plan my (day|week)|how (can|do) i make (money|profit|more money)( today| now)?|what to do (today|now)|give me (a |my |the )?(daily |morning )?(plan|brief|briefing)|daily plan""")
    private val PORTFOLIO = r("""(review|check|analy[sz]e|audit) my (portfolio|holdings|stocks)|how (is|are) my (portfolio|holdings|stocks)( doing)?|is my portfolio (ok|okay|safe|risky)|portfolio (review|check|health)""")
    private val MARKET = r("""why (is|did|has) the (market|nifty|sensex) (fall(en|ing)?|fell|drop(ped|ping)?|down|ris(e|en|ing)|rose|up|crash(ed|ing)?|rall(y|ied|ying))( today)?|why (is|are) (the )?(market|nifty|sensex|stocks) moving( today)?|what('s| is) happening (in|with) the (market|nifty|sensex)( today)?|how (is|was|did) the market( doing| do)?( today)?|market (update|today)""")
    private val STRATEGY = r("""(which|what) strategy should i (use|follow|pick)|find (me )?a (good )?strategy|best strategy( for me)?|how (should|can|do) i (invest|grow my money|build wealth)|what('s| is) working in the market( now)?""")
    private val STOCK = Regex("""(?i)^\s*(?:should i (?:buy|sell|hold|exit|add( more)?|average|worry about|keep)(?: more)?|what('s| is) (?:happening|going on) with|analy[sz]e|deep dive( into| on)?|research|tell me about|is|how is|what about|outlook (?:for|on))\s+@?(?<sym>[A-Za-z][A-Za-z0-9&\-]{1,19})(?:\s+(?:shares?|stock|now|today|a good (?:buy|stock|investment)|worth (?:it|buying)|doing|going to (?:rise|fall)|safe))*\s*[?.!]*\s*$""")
    private val IPO = Regex("""(?i)^\s*(?:should i (?:apply|subscribe|bid)(?: (?:for|to|in))?(?: the)?\s+(.+?)\s+ipo|is (?:the )?(.+?) ipo (?:good|worth it|worth applying))\s*[?.!]*\s*$""")
    private val NOT_A_SYMBOL = setOf("THE", "MARKET", "NIFTY", "SENSEX", "IT", "THIS", "THAT", "IPO", "MY", "IN", "A", "GOLD", "PORTFOLIO", "SAFE")

    fun plan(text: String, haveHoldings: Boolean = false): Plan? {
        val t = text.trim().replace(Regex("""\s+"""), " ")
        if (t.startsWith("/") || t.split(Regex("""\s+""")).size > 16) return null
        if (DAY.matches(t)) return Plan("Your plan for today", listOfNotNull(
            Step("Read the market and your holdings", "/brief", "market"),
            Step("Find out why the most unusual stock moved", "/why {top}", "market"),
            Step("See what's coming up this week", "/calendar", "web"),
            Step("See today's picks of the best-tested strategy", "/screen {best}", "market")))
        if (PORTFOLIO.matches(t)) return Plan("A review of your portfolio", listOf(
            Step("Read your holdings", "/holdings", "kite"),
            Step("Check each holding's trend", "/health", "market"),
            Step("See how concentrated you are", "/xray", "kite"),
            Step("Stress-test a market fall", "/stress", "kite"),
            Step("Check events for your stocks this week", "/calendar", "web")))
        if (MARKET.matches(t)) return Plan("What's moving the market", listOf(
            Step("Take the market's pulse", "/pulse", "market"),
            Step("Compare every sector today", "/sectorboard 1d", "market"),
            Step("Read the day's headlines", "/news", "web"),
            Step("Check foreign and domestic fund flows", "/fiidii", "web"),
            Step("See the latest filings and SEBI notices", "/radar", "web")))
        if (STRATEGY.matches(t)) return Plan("Finding a strategy that works now", listOf(
            Step("Backtest every strategy on your data", "/strategies", "market"),
            Step("See today's picks of the best one", "/screen {best}", "market"),
            Step("Learn how it works and when it fails", "/learn {best}"),
            Step("Compare with what fundamental screens look for", "/screens quality low debt", "knowledge")), NO_CALLS)
        IPO.find(t)?.let { m -> val name = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
            return Plan("Weighing the $name IPO", listOf(
                Step("See open and upcoming IPOs", "/ipo", "web"),
                Step("Check $name's grey-market premium", "/gmp $name", "web"),
                Step("Search reviews and subscription status", "/web $name IPO review subscription status", "web")), NO_CALLS) }
        STOCK.find(t)?.let { m -> val sym = m.groups["sym"]!!.value.uppercase()
            if (sym !in NOT_A_SYMBOL && (sym.length >= 2)) return Plan("A closer look at $sym", listOf(
                Step("Explain today's move", "/why $sym", "market"),
                Step("Read the latest news", "/news $sym", "web"),
                Step("Check its trend and risk", "/health $sym", "market"),
                Step("See upcoming results and dividends", "/events $sym", "web"),
                Step("Follow the money in its results", "/flow $sym", "web")), if (Regex("""(?i)\b(buy|sell|hold|exit|add|keep|average)\b""").containsMatchIn(t)) NO_CALLS else null) }
        return null
    }

    private fun obj(json: String): JsonObject? = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()

    /** The most unusual stock in earlier results: the first stock in a "hidden patterns" or movers list. */
    fun top(results: List<String>): String? = results.firstNotNullOfOrNull { j ->
        obj(j)?.getAsJsonObject("card")?.getAsJsonArray("blocks")?.map { it.asJsonObject }
            ?.filter { it["type"]?.asString == "movers" }
            ?.sortedByDescending { if (it["title"]?.takeIf { t -> !t.isJsonNull }?.asString.orEmpty().contains("pattern", true)) 1 else 0 }
            ?.firstNotNullOfOrNull { b -> b.getAsJsonArray("rows")?.firstOrNull()?.asJsonObject?.get("symbol")?.asString }
    }

    /** The best-tested strategy in earlier results, from a "/screen <id>" suggestion. */
    fun best(results: List<String>): String? = results.firstNotNullOfOrNull { j ->
        obj(j)?.getAsJsonArray("pills")?.map { it.asString }?.firstOrNull { it.startsWith("/screen ") }?.removePrefix("/screen ")?.trim()
    }

    /** Fills {top} and {best} from what earlier steps found; null when there's nothing to fill them with. */
    fun resolve(command: String, results: List<String>): String? {
        var c = command
        if ("{top}" in c) c = c.replace("{top}", top(results) ?: return null)
        if ("{best}" in c) c = c.replace("{best}", best(results) ?: "sharpe")
        return c
    }

    data class Done(val step: Step, val command: String?, val summary: String?, val skipped: String?)

    /** The wrap-up: what was done, what it shows, and what to do next — from the steps' own summaries. */
    fun wrapUp(plan: Plan, done: List<Done>, next: List<String>): String = buildString {
        val ran = done.filter { it.summary != null }
        append("**${plan.goal}** — ${ran.size} of ${done.size} steps done.\n\n")
        plan.caution?.let { append("_${it}_\n\n") }
        if (ran.isNotEmpty()) {
            append("**What it shows**\n")
            ran.forEach { d -> append("- **${d.step.title}:** ${firstSentence(d.summary!!)}\n") }
        }
        val skipped = done.filter { it.skipped != null }
        if (skipped.isNotEmpty()) {
            append("\n**Skipped**\n")
            skipped.forEach { d -> append("- ${d.step.title} — ${d.skipped}" + (fix(d.skipped!!)?.let { (label, cmd) -> " · [$label](cmd:$cmd)" } ?: "") + "\n") }
        }
        if (next.isNotEmpty()) { append("\n**What to do next**\n"); next.take(4).forEach { append("- [${Labels.of(it)}](cmd:$it)\n") } }
    }

    /** The tap that fixes a skipped step. */
    fun fix(reason: String): Pair<String, String>? = when {
        "Kite" in reason -> "🔌 Connect Kite" to "Connect Kite"
        "web" in reason -> "🌐 Allow web access" to "Allow web access"
        "market history" in reason -> "⬇ Get market history" to "/data 3y"
        "knowledge bank" in reason -> "⬇ Get the knowledge bank" to "/data knowledge"
        else -> null
    }

    private fun firstSentence(s: String): String {
        val t = s.trim().replace(Regex("""\s+"""), " ")
        val cut = Regex("""(?<=[.!?])\s+(?=[A-Z0-9])""").find(t)?.range?.first ?: t.length
        return t.substring(0, cut).let { if (it.length > 220) it.take(217).substringBeforeLast(' ') + "…" else it }
    }

    /** Next steps across all results: their own suggestions, most useful kinds first, without repeating what ran. */
    fun next(results: List<String>, ran: Set<String>): List<String> {
        val all = results.flatMap { j -> obj(j)?.getAsJsonArray("pills")?.map { it.asString }.orEmpty() }.filter { it.startsWith("/") && it !in ran }.distinct()
        val order = listOf("/why", "/health", "/screen", "/news", "/stress", "/events", "/learn", "/chart")
        return all.sortedBy { p -> order.indexOfFirst { p.startsWith(it) }.let { if (it < 0) order.size else it } }.take(5)
    }
}

/** Plain-language labels for command pills: the user sees the action, the app runs the exact command. */
object Labels {
    fun of(cmd: String): String {
        if (!cmd.startsWith("/")) return cmd
        val name = cmd.removePrefix("/").substringBefore(' ').lowercase(); val arg = cmd.substringAfter(' ', "").trim()
        return when (name) {
            "brief" -> "🧭 What to do today"
            "strategies" -> "📊 Compare strategies"
            "screen" -> "🎯 Today's ${if (arg.isBlank()) "" else (com.khayali.pulao.ai.term.Playbook.get(arg)?.name?.lowercase() ?: arg) + " "}picks"
            "learn" -> if (arg.isBlank()) "📘 Strategy playbook" else "📘 Learn ${com.khayali.pulao.ai.term.Playbook.get(arg)?.name?.lowercase() ?: arg}"
            "health" -> if (arg.isBlank()) "🩺 Check my holdings" else "🩺 Check $arg's trend"
            "news" -> if (arg.isBlank()) "📰 Market news" else "📰 News on $arg"
            "why" -> "❓ Why $arg moved"
            "pulse" -> "💓 Market pulse"
            "mood" -> "🌡 Market mood"
            "redflags" -> "🚩 Red flags"
            "positioning" -> "📊 FII positioning"
            "find" -> "🔎 Find: ${arg.take(24)}"
            "healthscore" -> "🩺 Portfolio health"
            "harvest" -> "🧾 Tax-loss harvesting"
            "concall" -> "🎙 $arg earnings calls"
            "alert" -> if (arg.isBlank()) "🔔 My alerts" else "🔔 Alert: $arg"
            "sebi" -> if (arg.isBlank()) "⚖ SEBI updates" else "⚖ SEBI $arg"
            "radar" -> if (arg == "mine") "📡 Filings on my stocks" else "📡 Filings radar"
            "events" -> if (arg.isBlank()) "📅 This week's events" else "📅 $arg's events"
            "chart" -> "📈 Chart ${arg.substringBefore(' ')}"
            "quote" -> "💹 $arg quote"
            "anomalies" -> "🔍 Hidden patterns"
            "regime" -> "🌡 Market trend"
            "stress" -> "🧯 Stress-test my portfolio"
            "xray" -> "🧩 Portfolio X-ray"
            "holdings" -> "💼 My holdings"
            "calendar" -> "📅 What's coming up"
            "report" -> "📰 Market report"
            "screens" -> if (arg.isBlank()) "🔎 Community screens" else "🔎 Screens: $arg"
            "data" -> when {
                arg.matches(Regex("""\d+y""")) -> "⬇ Get ${arg.dropLast(1)} years of data"
                arg.isBlank() -> "📦 Models & data"
                arg == "update" -> "🔄 Update market history"
                arg == "stop" -> "⏹ Stop the download"
                arg == "knowledge" -> "📚 Get the knowledge bank"
                arg.contains("delete") -> if (arg.startsWith("knowledge")) "🗑 Delete the knowledge bank" else "🗑 Delete market history"
                else -> cmd
            }
            "flow" -> "💸 $arg's results flow"
            "web" -> "🌐 Search: ${arg.take(28)}"
            "ipo" -> "🆕 Open IPOs"
            "gmp" -> "📈 $arg grey-market premium"
            "setup" -> "⚡ Set up everything"
            "sector" -> "🏷 ${arg.ifBlank { "Sector" }}${if (arg.isBlank()) "" else ""} sector".replace(Regex("""\s+(1d|1w|1m|3m|6m|ytd|1y|3y|5y) sector$"""), " sector · $1")
            "sectorboard" -> "🧭 All sectors${if (arg.isBlank()) "" else " · $arg"}"
            else -> {
                // any other terminal command: its own short name, plus the stock or option it was given
                val f = com.khayali.pulao.ai.term.Terminal.find(name) ?: return cmd
                val short = f.help.substringBefore(':').substringBefore(" — ").substringBefore(" (").trim().let { if (it.length > 30) it.take(28).substringBeforeLast(' ') + "…" else it }
                if (arg.isBlank()) short else "$short · $arg"
            }
        }
    }
}
