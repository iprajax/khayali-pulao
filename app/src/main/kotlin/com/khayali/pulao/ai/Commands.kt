package com.khayali.pulao.ai

/** Slash commands and @mentions for the chat input. Pure & unit-tested. */
object Commands {

    data class Cmd(val name: String, val usage: String, val help: String, val kiteSection: String? = null)

    val ALL = listOf(
        Cmd("holdings", "/holdings", "Your Kite holdings with P&L", "holdings"),
        Cmd("pnl", "/pnl", "Portfolio P&L at a glance", "holdings"),
        Cmd("positions", "/positions", "Open positions today", "positions"),
        Cmd("margins", "/margins", "Available margin", "margins"),
        Cmd("orders", "/orders", "Today's orders", "orders"),
        Cmd("mf", "/mf", "Mutual fund holdings", "mutual_funds"),
        Cmd("fd", "/fd 3", "Did my portfolio beat an FD over N years?"),
        Cmd("goal", "/goal 50 lakh", "How long to reach an amount?"),
        Cmd("grow", "/grow 1 lakh 12% 10y", "Compounding"),
        Cmd("move", "/move 15", "What if my portfolio moves N%?"),
        Cmd("tax", "/tax", "In hand if I sold everything today"),
        Cmd("clear", "/clear", "Start a new chat"),
        Cmd("help", "/help", "Show all commands"),
    )

    sealed class Parsed {
        data class Kite(val section: String) : Parsed()
        data class Prompt(val text: String) : Parsed()
        data object Clear : Parsed()
        data object Help : Parsed()
        data class Unknown(val name: String) : Parsed()
    }

    fun isCommand(text: String) = text.trimStart().startsWith("/")

    /** Commands that start with what the user typed after "/". */
    fun suggest(typed: String): List<Cmd> {
        val q = typed.trimStart().removePrefix("/").substringBefore(' ').lowercase()
        return ALL.filter { it.name.startsWith(q) }
    }

    fun parse(text: String): Parsed {
        val t = text.trim().removePrefix("/")
        val name = t.substringBefore(' ').lowercase()
        val arg = t.substringAfter(' ', "").trim()
        val cmd = ALL.firstOrNull { it.name == name } ?: return Parsed.Unknown(name)
        cmd.kiteSection?.let { return Parsed.Kite(it) }
        return when (name) {
            "clear" -> Parsed.Clear
            "help" -> Parsed.Help
            "fd" -> Parsed.Prompt("Did my portfolio beat a bank FD? I've held it ${arg.ifBlank { "2" }.removeSuffix("y").trim()} years.")
            "goal" -> Parsed.Prompt("How long to grow my portfolio to ${arg.ifBlank { "1 crore" }}?")
            "grow" -> Parsed.Prompt(if (arg.isBlank()) "What does ₹1 lakh become at 12% a year for 10 years?" else "Compounding: $arg")
            "move" -> Parsed.Prompt("What if my portfolio moves ${arg.ifBlank { "10" }.removeSuffix("%")}%?")
            "tax" -> Parsed.Prompt("How much would I get in hand after tax if I sold all my holdings today?")
            else -> Parsed.Unknown(name)
        }
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
