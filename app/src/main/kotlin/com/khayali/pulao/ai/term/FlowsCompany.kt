package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.J.a
import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.o
import com.khayali.pulao.ai.term.J.s
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** /insider SYMBOL — insider-trading (PIT) disclosures for one company: who, buy or sell, quantity, value, date. */
object FlowsInsiderFeature : Feature {
    override val id = "insider"
    override val usage = "/insider INFY"
    override val help = "Insider trades disclosed for one company: who bought or sold, how much and when"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\b(insiders?|promoters?) (trad\w*|buying|selling|deals?|transactions?)\b"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val symbol = Flows.symbol(arg, f) ?: return Card.error("Which company? Try /insider INFY.")
        return build(symbol, f.nse("corporates-pit?index=equities&symbol=" + Flows.q(symbol)))
    }

    class Trade(val who: String, val category: String, val mode: String, val side: String, val security: String, val qty: Double, val value: Double, val date: LocalDate)

    /** Pure: feed JSON → card. */
    fun build(symbol: String, json: String): String {
        val root = J.obj(json) ?: return Card.error("Couldn't read insider disclosures for $symbol right now. Try again in a minute.")
        val trades = root.a("data").mapNotNull { o ->
            val date = Flows.date(o.s("acqfromDt")) ?: Flows.date(o.s("acqtoDt")) ?: Flows.date(o.s("intimDt")) ?: Flows.date(o.s("date")) ?: return@mapNotNull null
            val type = o.s("tdpTransactionType").lowercase(); val mode = o.s("acqMode")
            val side = when { type == "buy" -> "buy"; type == "sell" -> "sell"; mode.contains("Purchase", true) -> "buy"; mode.contains("Sale", true) -> "sell"; else -> "" }
            Trade(o.s("acqName"), o.s("personCategory").takeIf { it != "-" }.orEmpty(), mode, side, o.s("secType"), o.d("secAcq") ?: 0.0, o.d("secVal") ?: 0.0, date)
        }.sortedByDescending { it.date }
        if (trades.isEmpty()) return Card.error("NSE lists no insider-trading disclosures for $symbol. Check the symbol, or try /shareholding $symbol.")
        // gifts and transfers reported with no value are listed, but are not counted as buying or selling
        val dealt = trades.filter { it.side.isNotEmpty() && it.value > 0 }
        fun unit(t: Trade) = if (t.security.contains("Equity", true)) "shares" else t.security.ifEmpty { "securities" }
        val bought = dealt.filter { it.side == "buy" }.sumOf { it.value }; val sold = dealt.filter { it.side == "sell" }.sumOf { it.value }
        val net = bought - sold
        val from = trades.last().date; val to = trades.first().date
        // net value by month, for the six most recent months that have a disclosure
        val months = dealt.groupBy { YearMonth.from(it.date) }.toSortedMap().entries.toList().takeLast(6)
        fun what(t: Trade) = if (t.value <= 0) t.mode.ifEmpty { "Other" } else when (t.side) { "buy" -> "Buy"; "sell" -> "Sell"; else -> t.mode.ifEmpty { "Other" } }
        val card = Card("$symbol · insider disclosures · ${trades.size} filings", if (net >= 0) "green" else "red")
            .headline((if (net >= 0) "Net bought " else "Net sold ") + Flows.rupees(abs(net)),
                "${Flows.show(from)} to ${Flows.show(to)} · bought ${Flows.rupees(bought)} · sold ${Flows.rupees(sold)}", N.tone(net))
        if (months.isNotEmpty()) card.viz("netbars", 200, mapOf("unit" to "₹ Cr", "signed" to true, "yLabel" to "Net bought (₹ Cr)", "xLabel" to "Month of trade",
            "items" to months.map { (m, list) -> mapOf("label" to Flows.month(m.atDay(1)), "value" to Flows.round(list.sumOf { if (it.side == "buy") it.value else -it.value } / 1e7, 3),
                "note" to "${list.size} ${if (list.size == 1) "disclosure" else "disclosures"}") }),
            "Disclosed buying minus selling by insiders in each month, ₹ crore (months with a disclosure only).")
        if (dealt.isNotEmpty()) card.viz("timeline", 210, mapOf("yLabel" to "Value (₹)", "xLabel" to "Trade date", "buy" to "Bought", "sell" to "Sold",
            "points" to dealt.reversed().map { t -> mapOf("t" to t.date.toString(), "label" to "${t.who} · ${t.mode}", "value" to t.value, "side" to t.side) }),
            "Each dot is one disclosure on its trade date: height = value in ₹, sells drawn below zero. Tap a dot.")
        else {      // nothing with a value (only gifts, pledges, allotments): show when each kind of disclosure happened
            val kinds = trades.groupingBy { it.mode.ifEmpty { "Other" } }.eachCount().entries.sortedByDescending { it.value }.take(4).map { it.key }
            card.viz("timeline", 70 + kinds.size * 34, mapOf("lanes" to kinds.map { Flows.clip(it, 18) }, "yLabel" to "Kind", "xLabel" to "Trade date",
                "points" to trades.filter { it.mode.ifEmpty { "Other" } in kinds }.reversed().map { t -> mapOf("t" to t.date.toString(), "label" to "${t.who} · ${Flows.count(t.qty)} ${unit(t)}", "lane" to Flows.clip(t.mode.ifEmpty { "Other" }, 18)) }),
                "Each dot is one disclosure on its trade date, grouped by kind. None of them reports a buy or sell with a value. Tap a dot.")
        }
        return card.rows("Latest disclosures", trades.take(8).map { t ->
                Card.Row(Flows.clip(t.who, 34), listOf(t.category, t.mode, "${Flows.count(t.qty)} ${unit(t)}", Flows.show(t.date)).filter { it.isNotBlank() }.joinToString(" · "),
                    what(t) + (if (t.value > 0) " " + Flows.rupees(t.value) else ""), if (t.value <= 0) "" else when (t.side) { "buy" -> "up"; "sell" -> "down"; else -> "" }) })
            .note("Disclosures under SEBI's insider-trading rules, as filed with NSE. They include gifts, ESOP allotments, pledges and off-market transfers, some with no value; nil-value entries are listed but not counted. The feed returns only the most recent filings (about 20). A disclosure records a trade; it does not explain the reason for it.")
            .sources("NSE").pills("/shareholding $symbol", "/deals $symbol", "/filings $symbol", "/quote $symbol")
            .put("symbol", symbol)
            .json("$symbol insider disclosures (${trades.size} filings, ${Flows.show(from)} to ${Flows.show(to)}): bought ${Flows.rupees(bought)}, sold ${Flows.rupees(sold)}, " +
                "net ${if (net >= 0) "bought" else "sold"} ${Flows.rupees(abs(net))}. Latest: " + trades.first().let { t -> t.who + when (what(t)) { "Buy" -> " bought "; "Sell" -> " sold "; else -> " — ${what(t)}, " } +
                    "${Flows.count(t.qty)} ${unit(t)} on ${Flows.show(t.date)}" } + ". Source: NSE.")
    }
}

/** /announcements SYMBOL — a company's latest exchange announcements. */
object FlowsAnnouncementsFeature : Feature {
    override val id = "announcements"
    override val usage = "/announcements INFY"
    override val help = "A company's latest exchange announcements: subject, date and a one-line gist"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\b(exchange|corporate|company|latest|recent) announcements?\b"""), Regex("""(?i)\bannouncements? (of|for|by|from)\b"""))

    /** How many entries are read; the unbounded reply runs to megabytes. */
    const val LIMIT = 15
    private val DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    override suspend fun run(arg: String, f: Fetch): String {
        val symbol = Flows.symbol(arg, f) ?: return Card.error("Which company? Try /announcements INFY.")
        val path = "corporate-announcements?index=equities&symbol=" + Flows.q(symbol)
        // ask for the last 90 days first (a small reply); a quiet company falls back to the full list, of which only the head is read
        var json = f.nse(path + "&from_date=" + f.today.minusDays(90).format(DMY) + "&to_date=" + f.today.format(DMY))
        if (J.arr(Flows.head(json, 1)).isEmpty()) json = f.nse(path)
        return build(symbol, json)
    }

    private class Item(val date: LocalDate, val subject: String, val text: String)

    /** Pure: feed JSON → card. Only the first [LIMIT] entries are parsed. */
    fun build(symbol: String, json: String): String {
        val head = Flows.head(json, LIMIT)
        if (J.parse(head)?.isJsonArray != true) return Card.error("Couldn't read announcements for $symbol right now. Try again in a minute.")
        var company = ""
        val items = J.arr(head).take(LIMIT).mapNotNull { o ->
            val d = Flows.date(o.s("an_dt")) ?: runCatching { LocalDate.parse(o.s("sort_date").take(10)) }.getOrNull() ?: return@mapNotNull null
            if (company.isEmpty()) company = o.s("sm_name")
            Item(d, o.s("desc").ifEmpty { "Announcement" }, Flows.gist(o.s("attchmntText")))
        }.sortedByDescending { it.date }
        if (items.isEmpty()) return Card.error("NSE lists no announcements for $symbol. Check the symbol, or try /filings $symbol.")
        val first = items.first(); val from = items.last().date
        // lanes: the three most frequent subjects, everything else as "Other"
        val topSubjects = items.groupingBy { it.subject }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }
        fun lane(s: String) = if (s in topSubjects) Flows.clip(s, 18) else "Other"
        val lanes = (topSubjects.map { Flows.clip(it, 18) } + if (items.any { it.subject !in topSubjects }) listOf("Other") else emptyList()).distinct()
        return Card("$symbol · exchange announcements", "blue")
            .headline("${items.size} latest announcements", "${Flows.show(from)} to ${Flows.show(first.date)} · most recent: ${Flows.clip(first.subject, 60)}")
            .viz("timeline", 70 + lanes.size * 34, mapOf("lanes" to lanes, "yLabel" to "Subject", "xLabel" to "Date announced",
                "points" to items.reversed().map { mapOf("t" to it.date.toString(), "label" to Flows.clip(it.text.ifEmpty { it.subject }, 44), "lane" to lane(it.subject)) }),
                "Each dot is one announcement on the date it was made, grouped by subject. Tap a dot.")
            .rows("Most recent", items.take(8).map { Card.Row(Flows.clip(it.subject, 48), listOf(Flows.show(it.date), Flows.clip(it.text, 110)).filter { t -> t.isNotBlank() }.joinToString(" · ")) })
            .note("Subjects and wording are the company's own, as filed with the exchange. Open the filing on NSE for the full document.")
            .sources("NSE").pills("/filings $symbol", "/events $symbol", "/news $symbol", "/insider $symbol")
            .put("symbol", symbol).put("company", company)
            .json("$symbol: ${items.size} latest exchange announcements, ${Flows.show(from)} to ${Flows.show(first.date)}. Most recent (${Flows.show(first.date)}): ${first.subject}" +
                (if (first.text.isNotEmpty()) " — " + Flows.clip(first.text, 140).trimEnd('.') else "").let { if (it.endsWith("…")) "$it Source: NSE." else "$it. Source: NSE." })
    }
}

/** /filings SYMBOL — one company's corporate snapshot: announcements, corporate actions, board meetings and result filings. */
object FlowsFilingsFeature : Feature {
    override val id = "filings"
    override val usage = "/filings INFY"
    override val help = "Company snapshot: latest announcements, corporate actions, board meetings and results filed"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\b(corporate|company|exchange|latest|recent) filings?\b"""), Regex("""(?i)\bcorporate (snapshot|actions?)\b"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val symbol = Flows.symbol(arg, f) ?: return Card.error("Which company? Try /filings INFY.")
        return build(symbol, f.nse("top-corp-info?symbol=" + Flows.q(symbol) + "&market=equities"), f.today)
    }

    private class Event(val date: LocalDate, val text: String)

    /** Pure: feed JSON → card. [today] only marks dates that are still ahead. */
    fun build(symbol: String, json: String, today: LocalDate): String {
        val root = J.obj(json) ?: return Card.error("Couldn't read filings for $symbol right now. Try again in a minute.")
        fun events(section: String, dateKey: String, textKey: String) = root.o(section)?.a("data").orEmpty()
            .mapNotNull { o -> Flows.date(o.s(dateKey))?.let { Event(it, Flows.gist(o.s(textKey))) } }.sortedByDescending { it.date }
        val news = events("latest_announcements", "broadcastdate", "subject")
        val actions = events("corporate_actions", "exdate", "purpose")
        val meetings = events("borad_meeting", "meetingdate", "purpose").distinctBy { it.date }      // "borad" is NSE's spelling
        val results = root.o("financial_results")?.a("data").orEmpty().mapNotNull { o -> Flows.date(o.s("to_date"))?.let { it to o } }.sortedByDescending { it.first }
        if (news.isEmpty() && actions.isEmpty() && meetings.isEmpty() && results.isEmpty())
            return Card.error("NSE has no corporate filings for $symbol. Check the symbol — try the exact NSE ticker, e.g. /filings INFY.")
        fun ahead(d: LocalDate) = if (d.isAfter(today)) "Upcoming · " else ""
        val filed = results.mapNotNull { (d, o) -> Flows.date(o.s("re_broadcast_timestamp"))?.let { Event(it, "Results for the quarter ended ${Flows.show(d)}") } }
        val lanes = listOf("Announcements" to news, "Corporate actions" to actions, "Board meetings" to meetings, "Results" to filed).filter { it.second.isNotEmpty() }
        val latest = lanes.flatMap { (k, v) -> v.filter { !it.date.isAfter(today) }.map { k to it } }.maxByOrNull { it.second.date }
        // the chart keeps to the last two years so recent filings are not squeezed by old dividends
        val since = today.minusYears(2)
        val recent = lanes.map { (k, v) -> k to v.filter { !it.date.isBefore(since) } }.filter { it.second.isNotEmpty() }
        val drawn = if (recent.sumOf { it.second.size } >= 3) recent else lanes
        val card = Card("$symbol · corporate filings", "blue")
            .headline(latest?.let { "Last filing ${Flows.show(it.second.date)}" } ?: "${lanes.sumOf { it.second.size }} dated entries",
                listOfNotNull(news.firstOrNull()?.let { "Announcement: ${Flows.clip(it.text, 40)}" }, meetings.firstOrNull()?.let { "board met ${Flows.show(it.date)}".let { t -> if (it.date.isAfter(today)) "board meets ${Flows.show(it.date)}" else t } },
                    actions.firstOrNull()?.let { "ex-date ${Flows.show(it.date)}" }).joinToString(" · "))
            .viz("timeline", 70 + drawn.size * 34, mapOf("lanes" to drawn.map { it.first }, "yLabel" to "Type", "xLabel" to "Date",
                "points" to drawn.flatMap { (k, v) -> v.map { mapOf("t" to it.date.toString(), "label" to Flows.clip(it.text, 44), "lane" to k) } }),
                "Each dot is one entry on its date" + (if (drawn === recent) ", last two years" else "") + ": announcements (broadcast date), corporate actions (ex-date), board meetings, and results (date filed). Tap a dot.")
        // figures arrive in ₹ lakh; shown in ₹ crore
        val quarters = results.take(5).mapNotNull { (d, o) -> val inc = o.d("income"); val pat = o.d("proLossAftTax"); if (inc == null || pat == null) null else Triple(d, inc / 100, pat / 100) }
        if (quarters.size >= 2) card.viz("groupbars", 200, mapOf("unit" to "₹ Cr", "xLabel" to "Quarter ended", "series" to listOf("Income", "Profit after tax"),
            "items" to quarters.reversed().map { (d, inc, pat) -> mapOf("label" to Flows.month(d), "values" to listOf(Flows.round(inc), Flows.round(pat))) }),
            "Income and profit after tax (₹ crore) for each quarter filed, as reported.")
        val q = results.firstOrNull()
        val holding = root.o("shareholdings_patterns")?.o("data")?.let { data -> data.keySet().mapNotNull { k -> Flows.date(k)?.let { it to k } }.maxByOrNull { it.first }?.let { (d, k) ->
            data.getAsJsonArray(k).filter { it.isJsonObject }.map { it.asJsonObject }.firstNotNullOfOrNull { o -> o.keySet().firstOrNull { it.startsWith("Promoter") }?.let { o.d(it) } }?.let { d to it } } }
        card.stats(*listOfNotNull(
            q?.let { (d, o) -> o.d("income")?.let { "Income, quarter to ${Flows.show(d)}" to N.cr(it / 100) } },
            q?.let { (_, o) -> o.d("proLossAftTax")?.let { "Profit after tax" to N.cr(it / 100) } },
            q?.let { (_, o) -> o.d("reDilEPS")?.let { "EPS (diluted)" to "₹" + N.num(it) } },
            holding?.let { (d, v) -> "Promoter holding (${Flows.show(d)})" to N.pct(v, 2).removePrefix("+") }).toTypedArray())
        card.rows("Announcements", news.take(3).map { Card.Row(Flows.clip(it.text, 52), Flows.show(it.date)) })
        card.rows("Corporate actions", actions.take(3).map { Card.Row(Flows.clip(it.text, 52), ahead(it.date) + "Ex-date " + Flows.show(it.date), "", if (it.date.isAfter(today)) "warn" else "") })
        card.rows("Board meetings", meetings.take(2).map { Card.Row(Flows.show(it.date), ahead(it.date) + Flows.clip(it.text, 120), "", if (it.date.isAfter(today)) "warn" else "") })
        val kind = q?.second?.let { o -> listOf(o.s("consolidated"), o.s("audited")).filter { it.isNotBlank() }.joinToString(", ").lowercase() }.orEmpty()
        return card.note("As filed with NSE" + (if (kind.isNotEmpty()) "; result figures are $kind" else "") + ". This card lists what was filed and when — it does not judge the content.")
            .sources("NSE").pills("/announcements $symbol", "/flow $symbol", "/shareholding $symbol", "/insider $symbol")
            .put("symbol", symbol)
            .json("$symbol filings: " + listOfNotNull(
                news.firstOrNull()?.let { "latest announcement ${Flows.show(it.date)} (${Flows.clip(it.text, 60)})" },
                actions.firstOrNull()?.let { "latest corporate action ${Flows.clip(it.text, 60)}, ex-date ${Flows.show(it.date)}" },
                meetings.firstOrNull()?.let { "latest board meeting ${Flows.show(it.date)}" },
                q?.let { (d, o) -> val inc = o.d("income"); val pat = o.d("proLossAftTax"); if (inc == null || pat == null) null else "quarter ended ${Flows.show(d)}: income ${N.cr(inc / 100)}, profit after tax ${N.cr(pat / 100)}" + (if (kind.isNotEmpty()) " ($kind)" else "") },
            ).joinToString("; ") + ". Source: NSE.")
    }
}
