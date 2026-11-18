package com.khayali.pulao.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Explainability for every reply: why the user is seeing it (what they did and how the chat routed it)
 * and how each part was made — the data and where it came from, the exact method and parameters, the
 * limits, and how fresh it is. Shown in the reply's "How this was made" accordion. Pure and unit-tested.
 */
object Methods {
    data class Method(val title: String, val data: String, val how: String, val limits: String, val asOf: String? = null)

    private fun obj(json: String): JsonObject? = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
    private fun JsonObject.str(k: String) = get(k)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

    // ------------------------------------------------------------------ why

    /**
     * Why this reply exists. [via] says how the request arrived when it wasn't typed: "pill:<label>",
     * "action", "ticker:<SYM>", "starter", "model".
     */
    fun why(text: String, action: ChatFlow.Action, via: String? = null): String {
        val t = text.trim()
        val arrived = when {
            via == null -> null
            via.startsWith("pill:") -> "You tapped the suggestion “${via.removePrefix("pill:")}”"
            via == "action" -> "You tapped a next step in an earlier reply"
            via.startsWith("ticker:") -> "You picked an action from ${via.removePrefix("ticker:")}'s menu"
            via == "starter" -> "You picked a starter task"
            via == "model" -> "The AI model chose to run this to answer you"
            via == "voice" -> "You asked by voice (recognised on the phone)"
            else -> null
        }
        val typed = t.startsWith("/")
        fun cmd(c: String) = if (arrived != null) "$arrived, which runs $c." else if (typed) "You typed $c." else
            "Your words “${t.take(60)}” mean $c, so it ran directly — exact and instant, no AI needed."
        return when (action) {
            is ChatFlow.Action.Plan -> (arrived?.let { "$it. " } ?: "") + "A broad question, so the planner made it a plan of ${action.plan.steps.size} steps " +
                "(“${action.plan.goal}”): each step runs one command and shows its own card; later steps use what earlier ones found."
            is ChatFlow.Action.Term -> cmd("/${action.id}${if (action.arg.isBlank()) "" else " " + action.arg}")
            is ChatFlow.Action.KiteRead -> cmd("/${action.section}") + " It reads your Kite account (read-only)."
            is ChatFlow.Action.Chart -> cmd("/chart ${action.symbol}${action.period?.let { " $it" } ?: ""}")
            is ChatFlow.Action.Tool -> cmd("the ${action.name.replace('_', ' ')} calculator")
            is ChatFlow.Action.Web -> cmd(t.takeIf { typed } ?: "a web ${action.kind.name.lowercase()} lookup")
            is ChatFlow.Action.NeedArtifact -> "This needs the ${if (action.artifact == "market") "market history" else "knowledge bank"} download first; what you asked runs as soon as it's ready."
            is ChatFlow.Action.EnableWeb -> "This needs web access, which is off; it runs once you allow it."
            is ChatFlow.Action.ConnectKite -> "This needs your Kite account (read-only), which isn't connected."
            is ChatFlow.Action.Model -> (arrived?.let { "$it. " } ?: "") + "Not a command, so Gemma (the AI on your phone) wrote the answer, calling the app's tools whenever it needed exact figures."
            else -> arrived?.let { "$it." } ?: "You asked for this."
        }
    }

    // ------------------------------------------------------------------ how

    private val SOURCES = linkedMapOf(
        "NSE (your copy)" to "your own copy of NSE's daily price files, downloaded by this phone from NSE's public archive",
        "NSE" to "NSE's public website feeds, read by this phone through a private browser engine (cookies used only during the load, then wiped)",
        "Your Kite holdings" to "your Kite holdings (read-only), matched on this phone and never sent anywhere",
        "Yahoo Finance" to "public price history from Yahoo Finance's chart feed (only the symbol is sent)",
        "screener.in public screens" to "the knowledge bank: rules of 500 public screens from screener.in, with EmbeddingGemma 2 vectors, downloaded once",
        "Playbook" to "the app's built-in strategy playbook",
        "SEBI" to "SEBI's own What's New list on sebi.gov.in, read by this phone",
        "NSE announcements" to "NSE's corporate-announcements list (company filings), read by this phone",
        "NSE live feed" to "NSE's live announcements feed on its archive, read by this phone",
        "NSE filings" to "NSE's company filings for this stock, read by this phone",
        "News feeds" to "public RSS feeds of Economic Times, Mint, Business Standard, BusinessLine and CNBC-TV18 (only the feed address is requested)",
        "Economic Times" to "Economic Times' public markets RSS feed", "Mint" to "Mint's public markets RSS feed", "Business Standard" to "Business Standard's public markets RSS feed",
        "BusinessLine" to "BusinessLine's public markets RSS feed", "CNBC-TV18" to "CNBC-TV18's public market RSS feed",
        "This phone" to "what is installed on this phone",
    )

    fun source(name: String): String = SOURCES.entries.firstOrNull { name.startsWith(it.key) }?.value ?: name

    /** How one step of a reply was made. */
    fun of(e: ToolEvent): Method {
        val o = obj(e.resultJson)
        val args = obj(e.argsJson)
        val asOf = o?.str("asOf")
        val span = o?.str("span")
        return when {
            e.name.startsWith("term_") -> {
                val id = e.name.removePrefix("term_"); val f = com.khayali.pulao.ai.term.Terminal.find(id)
                val srcs = o?.getAsJsonArray("sources")?.map { it.asString }.orEmpty()
                val data = (if (srcs.isEmpty()) listOf(if (f?.needsWeb == false) "this phone" else "public market feeds") else srcs.map(::source)).joinToString("; ").replaceFirstChar { it.uppercase() } +
                    (span?.let { " · $it" } ?: "")
                Method("/$id${args?.str("command")?.removePrefix("/$id")?.let { it } ?: ""}", data,
                    f?.method?.ifBlank { null } ?: f?.help ?: "Built by the /$id command on the phone.",
                    if (f?.needsWeb == true) "Values as the source publishes them; they can be delayed. Nothing about you is sent — at most a symbol or index name." else
                        "Computed entirely on the phone. History-based; past patterns don't promise future ones.", asOf)
            }
            e.name == "kite_account" || e.name == "kite_quote" || e.name == "kite_chart" -> Method(e.label.ifBlank { "Kite" },
                "Zerodha's official Kite MCP, read-only — your account as Kite reports it at this moment",
                when (e.name) { "kite_chart" -> "Daily candles from Kite's historical data for the period shown."; "kite_quote" -> "Last traded price from Kite."
                    else -> "The ${args?.str("section") ?: "account"} section exactly as Kite returns it; totals and P&L are summed on the phone." },
                "Read-only: the connection cannot place, modify or cancel orders. Kite logins expire daily.")
            e.name == "web_search" -> Method("Web search", "DuckDuckGo results for: “${args?.str("query") ?: ""}”",
                "Only topic words are searched — amounts, quantities and IDs are stripped first, and holdings are never sent. The written summary keeps only figures that appear in the sources, each cited [n].",
                "Search results can be outdated or wrong; check the sources.")
            e.name == "web_quote" -> Method("Price", "Yahoo Finance's public chart feed for ${args?.str("symbol") ?: "the symbol"}",
                "Latest price and the period's closes as the feed reports them.", "Can lag the exchange by a few minutes; not a live trading price.")
            e.name == "web_calendar" || e.name == "web_events" -> Method("Market calendar", "NSE's corporate-actions, board-meeting and IPO feeds, plus Moneycontrol's results calendar",
                "Events in the window shown, grouped by day and kind; your holdings are matched and starred on the phone.", "Companies can change dates; NSE's notice is the authority.")
            e.name == "web_ipo" -> Method("IPOs", "NSE's lists of current, upcoming and recently listed issues", "Bid windows, price band, lot and listing dates as NSE publishes them.", "Check the offer document before applying.")
            e.name == "web_gmp" -> Method("Grey-market premium", "InvestorGain's public GMP pages", "The latest premium, its day-by-day trend and the listing price it implies (issue price + premium).",
                "GMP is unofficial and unregulated — an indication of sentiment, not a price or a promise.")
            else -> calculator(e, args)
        }
    }

    private fun calculator(e: ToolEvent, args: JsonObject?): Method {
        val exact = "Exact arithmetic by the app — no AI, no network."
        return when (e.name) {
            "project_move" -> Method("What-if move", "Your holding's quantity and price (from Kite or the screen)", "Value = shares × price × (1 + move %), from your average buy price or today's price. $exact", "A projection, not a forecast.")
            "compare_with_fd" -> Method("vs FD and others", "Your holding's return, and the rates in Settings (FD, PPF, gold, Nifty, inflation)",
                "Your yearly return (CAGR) over the years held, against each alternative compounded yearly at its rate. $exact", "Uses the rates you set; real returns vary.")
            "goal_plan" -> Method("Goal plan", "Your target and starting amount", "Growth needed = target ÷ current − 1; years at r % a year = ln(target ÷ current) ÷ ln(1 + r). $exact", "Assumes a steady yearly return.")
            "grow_for_years" -> Method("Compounding", "The amount, return and years given", "Future value = amount × (1 + r)^years. $exact", "Assumes a steady yearly return; before tax.")
            "in_hand_after_tax" -> Method("Money in hand", "Your gain or sale value",
                "Equity capital-gains tax: LTCG 12.5% above ₹1.25 lakh a year if held over 12 months, otherwise STCG 20%; then STT and DP charges. $exact", "An estimate, not tax advice.")
            "regret" -> Method("Regret check", "Your quantity and the price you named", "Value of the same shares bought at that price, against what you paid. $exact", "Hindsight only.")
            "get_current_stock", "get_settings" -> Method(e.label.ifBlank { e.name }, "What is on screen or in your settings, on this phone", "Read as is.", "—")
            else -> Method(e.label.ifBlank { e.name.replace('_', ' ') }, "The app", "Computed on the phone.", "—")
        }
    }

    /** How the written part of an AI answer was made. */
    fun model(backend: String?): Method = Method("Written answer", "The tool results above (nothing else)",
        "Gemma 4 E2B wrote it on your phone${backend?.let { " ($it)" } ?: ""}. Every number in the text is checked against the tools' output; any figure not found there is removed.",
        "A small on-device model can misread a question — the cards are the exact figures. Educational only, not investment advice.")
}
