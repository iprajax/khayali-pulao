package com.khayali.pulao.ai

/** Slash commands and @mentions for the chat input. Pure & unit-tested. */
object Commands {

    data class Cmd(val name: String, val usage: String, val help: String, val kiteSection: String? = null)

    val ALL = listOf(
        Cmd("setup", "/setup", "Set up everything in one tap: AI model, market history, knowledge bank, web, Kite"),
        Cmd("holdings", "/holdings", "Your Kite holdings with P&L", "holdings"),
        Cmd("pnl", "/pnl", "Portfolio P&L at a glance", "holdings"),
        Cmd("positions", "/positions", "Open positions today", "positions"),
        Cmd("margins", "/margins", "Available margin", "margins"),
        Cmd("orders", "/orders", "Today's orders", "orders"),
        Cmd("mf", "/mf", "Mutual fund holdings", "mutual_funds"),
        Cmd("chart", "/chart INFY 6m", "Price chart (1w 1m 3m 6m 1y 5y)"),
        Cmd("price", "/price INFY", "Latest share price from the web"),
        Cmd("calendar", "/calendar", "Market calendar for the next 7 days: results, dividends, splits, bonus, AGMs, IPOs, holidays"),
        Cmd("ipo", "/ipo", "Open and upcoming IPOs with grey-market premium, and recent listings"),
        Cmd("gmp", "/gmp Crystal Crop", "An IPO's grey-market premium, its trend and the listing price it implies"),
        Cmd("events", "/events INFY", "One company's calendar: results, dividends, splits, AGM"),
        Cmd("results", "/results tomorrow", "Who reports results on a day — or /results INFY for the latest numbers"),
        Cmd("web", "/web your question", "Search the web (DuckDuckGo) with sources"),
        Cmd("fd", "/fd 3", "Did my portfolio beat an FD over N years?"),
        Cmd("goal", "/goal 50 lakh", "How long to reach an amount?"),
        Cmd("grow", "/grow 1 lakh 12% 10y", "Compounding"),
        Cmd("move", "/move 15", "What if my portfolio moves N%?"),
        Cmd("tax", "/tax", "In hand if I sold everything today"),
        Cmd("remember", "/remember I invest for 10+ years", "Save a note to memory"),
        Cmd("memory", "/memory", "See and edit what the chat remembers"),
        Cmd("forget", "/forget", "Erase all memory"),
        Cmd("compact", "/compact", "Summarise the chat so far to keep the model fast"),
        Cmd("clear", "/clear", "Start a new chat"),
        Cmd("help", "/help", "Show all commands"),
    )

    /** Terminal features as commands, for the typing suggestions and the help list. */
    val TERMINAL: List<Cmd> by lazy { com.khayali.pulao.ai.term.Terminal.FEATURES.map { Cmd(it.id, it.usage, it.help) } }

    sealed class Parsed {
        data class Kite(val section: String) : Parsed()
        data class Chart(val symbol: String, val period: String?) : Parsed()
        data class Prompt(val text: String) : Parsed()
        /** Run one calculator directly (exact and instant; works without the model). */
        data class Tool(val name: String, val args: String) : Parsed()
        data class Web(val kind: ChatFlow.WebKind, val query: String) : Parsed()
        data class Remember(val text: String) : Parsed()
        data object Memory : Parsed()
        data object Forget : Parsed()
        data object Compact : Parsed()
        /** A terminal feature (see ai/term). */
        data class Term(val id: String, val arg: String) : Parsed()
        data object Clear : Parsed()
        data object Help : Parsed()
        data class Unknown(val name: String) : Parsed()
    }

    fun isCommand(text: String) = text.trimStart().startsWith("/")

    /** Commands that start with what the user typed after "/" (exact prefix only). */
    fun suggest(typed: String): List<Cmd> {
        val q = typed.trimStart().removePrefix("/").substringBefore(' ').lowercase()
        return (ALL + TERMINAL).filter { it.name.startsWith(q) }
    }

    /**
     * Commands ranked for what has been typed so far: exact name, then names that start with it, then
     * a word inside the name ("chain" → optionchain), then the description ("dividend" → calendar),
     * then a loose subsequence of the name ("oc" → optionchain). Empty input lists the most useful first.
     */
    fun rank(typed: String, limit: Int = Int.MAX_VALUE): List<Cmd> {
        val q = typed.trimStart().removePrefix("/").substringBefore(' ').lowercase()
        val all = (ALL + TERMINAL).distinctBy { it.name }
        if (q.isEmpty()) return FEATURED.mapNotNull { n -> all.firstOrNull { it.name == n } }.take(limit)
        fun subsequence(name: String): Boolean { var i = 0; for (c in name) if (i < q.length && c == q[i]) i++; return i == q.length }
        fun score(c: Cmd): Int {
            val help = c.help.lowercase()
            return when {
                c.name == q -> 1000
                c.name.startsWith(q) -> 800 - c.name.length
                q.length >= 2 && c.name.contains(q) -> 600 - c.name.indexOf(q)
                q.length >= 3 && Regex("""\b""" + Regex.escape(q)).containsMatchIn(help) -> 400 - help.indexOf(q).coerceAtMost(200)
                q.length >= 2 && subsequence(c.name) -> 200 - c.name.length
                else -> 0
            }
        }
        return all.map { it to score(it) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }.take(limit)
    }

    /** Every command in sections for a bare "/": popular first, then account & calculators, then each terminal group. */
    fun sections(): List<Pair<String, List<Cmd>>> {
        val all = (ALL + TERMINAL).distinctBy { it.name }
        val popular = FEATURED.mapNotNull { n -> all.firstOrNull { it.name == n } }
        val shown = popular.map { it.name }.toMutableSet()
        val out = mutableListOf("Popular" to popular)
        val chat = ALL.filter { it.name !in shown }; shown += chat.map { it.name }
        out += "Your account, web & calculators" to chat
        for (g in com.khayali.pulao.ai.term.Group.entries) {
            val cmds = com.khayali.pulao.ai.term.Terminal.FEATURES.filter { it.group == g && it.id !in shown }.map { f -> TERMINAL.first { it.name == f.id } }
            if (cmds.isNotEmpty()) { out += g.title to cmds; shown += cmds.map { it.name } }
        }
        return out
    }

    /** Shown when only "/" has been typed. */
    val FEATURED = listOf("report", "holdings", "calendar", "ipo", "chart", "heatmap", "flow", "sip", "help")

    internal val PERIODS = listOf("1w", "1m", "3m", "6m", "1y", "5y")
    internal val INDEX_ARGS = listOf("NIFTY BANK", "NIFTY IT", "NIFTY AUTO", "NIFTY PHARMA", "NIFTY FMCG", "NIFTY METAL", "NIFTY REALTY", "NIFTY ENERGY")
    internal val SYMBOL_COMMANDS = WatchStore.STOCK_COMMANDS + setOf("compare", "gmp", "insider", "scorecard")

    /**
     * Completions for the argument being typed after a command name: the user's own symbols for stock
     * commands, then periods for /chart; index names for /heatmap; and so on. [symbols] = holdings and
     * watched stocks, most relevant first.
     */
    fun argHints(text: String, symbols: List<String>): List<String> {
        val t = text.trimStart().removePrefix("/")
        val name = t.substringBefore(' ').lowercase()
        val args = t.substringAfter(' ', "").split(' ')
        val current = args.lastOrNull().orEmpty().uppercase(); val index = args.size - 1
        val opts: List<String> = when {
            name == "chart" && index >= 1 -> PERIODS
            name == "compare" && index >= 1 && args.dropLast(1).size >= 2 -> PERIODS + symbols
            name in SYMBOL_COMMANDS -> symbols
            name == "heatmap" || name == "peers" -> INDEX_ARGS.map { if (name == "peers") it.removePrefix("NIFTY ") else it }
            name == "indices" -> listOf("broad", "sectoral", "thematic", "strategy")
            name == "sectors" -> listOf("30d", "1y")
            name == "movers" -> listOf("bank", "next50", "fno", "all")
            name == "calendar" || name == "results" -> listOf("today", "tomorrow", "week", "month") + if (name == "calendar") symbols.take(3) else emptyList()
            else -> emptyList()
        }
        // multi-word choices ("NIFTY BANK") are matched against everything typed after the command name
        val whole = t.substringAfter(' ', "").trimStart().uppercase()
        return opts.filter { o -> val typed = if (o.contains(' ')) whole else current; o.uppercase().startsWith(typed) && !o.equals(typed, true) }.distinct().take(8)
    }

    /** Puts [choice] in place of what is being typed (the whole argument for a multi-word choice) and adds a space. */
    fun completeArg(text: String, choice: String): String =
        if (choice.contains(' ')) text.trimStart().substringBefore(' ') + " " + choice + " " else Regex("""\S*$""").replaceFirst(text, choice) + " "

    fun parse(text: String): Parsed {
        val t = text.trim().removePrefix("/")
        val name = t.substringBefore(' ').lowercase()
        val arg = t.substringAfter(' ', "").trim()
        val cmd = ALL.firstOrNull { it.name == name }
            ?: return com.khayali.pulao.ai.term.Terminal.find(name)?.let { Parsed.Term(it.id, arg.removePrefix("@")) } ?: Parsed.Unknown(name)
        cmd.kiteSection?.let { return Parsed.Kite(it) }
        return when (name) {
            "chart" -> arg.split(' ').filter { it.isNotBlank() }.let { a ->
                if (a.isEmpty()) Parsed.Help else Parsed.Chart(a[0].removePrefix("@").uppercase(), a.getOrNull(1)?.lowercase())
            }
            "clear" -> Parsed.Clear
            "help" -> Parsed.Help
            "fd" -> Parsed.Tool("compare_with_fd", obj("years_held" to (number(arg) ?: 2.0)))
            "goal" -> Parsed.Tool("goal_plan", obj("target_rupees" to arg.ifBlank { "1 crore" }))
            "move" -> Parsed.Tool("project_move", obj("percent" to (signedNumber(arg) ?: 10.0)))
            "tax" -> Parsed.Tool("in_hand_after_tax", "{}")
            "grow" -> grow(arg)
            "price" -> if (arg.isBlank()) Parsed.Help else Parsed.Web(ChatFlow.WebKind.PRICE, arg.removePrefix("@"))
            "calendar" -> Parsed.Web(ChatFlow.WebKind.CALENDAR, arg.removePrefix("@").ifBlank { "week" })
            "ipo" -> if (arg.isBlank()) Parsed.Web(ChatFlow.WebKind.IPO, "") else Parsed.Web(ChatFlow.WebKind.GMP, arg)
            "gmp" -> Parsed.Web(ChatFlow.WebKind.GMP, arg)
            "events" -> if (arg.isBlank()) Parsed.Web(ChatFlow.WebKind.CALENDAR, "week") else Parsed.Web(ChatFlow.WebKind.CALENDAR, arg.removePrefix("@"))
            // "/results" or "/results tomorrow" = who reports that day; "/results INFY" = that company's latest numbers
            "results" -> if (arg.isBlank()) Parsed.Web(ChatFlow.WebKind.CALENDAR, "today")
                else if (MarketData.window(arg, java.time.LocalDate.now()) != null) Parsed.Web(ChatFlow.WebKind.CALENDAR, arg)
                else Parsed.Web(ChatFlow.WebKind.SEARCH, "${arg.removePrefix("@")} latest quarterly results revenue net profit")
            "compact" -> Parsed.Compact
            "web" -> if (arg.isBlank()) Parsed.Help else Parsed.Web(ChatFlow.WebKind.SEARCH, arg)
            "remember" -> if (arg.isBlank()) Parsed.Memory else Parsed.Remember(arg)
            "memory" -> Parsed.Memory
            "forget" -> Parsed.Forget
            else -> Parsed.Unknown(name)
        }
    }

    private fun obj(vararg kv: Pair<String, Any>) = com.google.gson.JsonObject().apply {
        for ((k, v) in kv) if (v is Number) addProperty(k, v) else addProperty(k, v.toString())
    }.toString()

    private fun number(s: String) = Regex("""\d+(?:\.\d+)?""").find(s)?.value?.toDoubleOrNull()
    private fun signedNumber(s: String) = Regex("""[+\-−]?\s*\d+(?:\.\d+)?""").find(s)?.value?.replace("−", "-")?.replace(" ", "")?.toDoubleOrNull()

    /** "/grow 1 lakh 12% 10y" → grow_for_years. Anything it can't read goes to the model as a question. */
    private fun grow(arg: String): Parsed {
        if (arg.isBlank()) return Parsed.Tool("grow_for_years", obj("amount_rupees" to "1 lakh", "yearly_return_percent" to 12.0, "years" to 10.0))
        val rate = Regex("""(\d+(?:\.\d+)?)\s*%""").find(arg)
        val years = Regex("""(\d+(?:\.\d+)?)\s*(?:y|yr|yrs|year|years)\b""", RegexOption.IGNORE_CASE).find(arg)
        if (rate == null || years == null) return Parsed.Prompt("Compounding: $arg")
        val amount = arg.replace(rate.value, " ").replace(years.value, " ").replace(Regex("""\b(at|for|a year|per year)\b""", RegexOption.IGNORE_CASE), " ").trim()
        return if (amount.isBlank() || Money.parse(amount) == null) Parsed.Prompt("Compounding: $arg")
        else Parsed.Tool("grow_for_years", obj("amount_rupees" to amount.replace(Regex("""\s+"""), " "), "yearly_return_percent" to rate.groupValues[1].toDouble(), "years" to years.groupValues[1].toDouble()))
    }

    // ------------------------------------------------------------- mentions

    private val MENTION = Regex("""@([A-Za-z0-9&\-]{1,20})""")

    /** The @token currently being typed at the end of the input, or null. */
    fun typingMention(text: String): String? = Regex("""(?:^|\s)@([A-Za-z0-9&\-]*)$""").find(text)?.groupValues?.get(1)

    fun completeMention(text: String, symbol: String): String =
        text.replace(Regex("""@([A-Za-z0-9&\-]*)$"""), "@$symbol ")

    fun mentions(text: String): List<String> = MENTION.findAll(text).map { it.groupValues[1].uppercase() }.distinct().toList()

    fun matching(typed: String, known: List<String>, limit: Int = 8): List<String> {
        val q = typed.uppercase()
        return (known.filter { it.startsWith(q) } + known.filter { q.isNotEmpty() && it.contains(q) && !it.startsWith(q) }).distinct().take(limit)
    }
}
