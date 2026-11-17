package com.khayali.pulao.ai

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The market calendar: what is happening on the exchange, by date. Pure parsing of the public
 * feeds the opt-in web tools read — NSE's own calendar, corporate-action, IPO, listing and
 * holiday feeds, and Moneycontrol's results calendar. No networking here (see WebClient).
 *
 * One life cycle for every kind of event: it is UPCOMING until its date, TODAY on it, then DONE;
 * an IPO goes Forthcoming → Open → Closed → Listed.
 */
object MarketData {
    const val NSE_HOST = "www.nseindia.in"
    const val NSE_NAME = "NSE"
    const val MC_HOST = "www.moneycontrol.com"
    const val MC_NAME = "Moneycontrol"

    private val gson = Gson()
    private val NSE_DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)
    private val QUERY_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy")
    private val SHOW = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    enum class Kind(val label: String) {
        RESULTS("Results"), DIVIDEND("Dividend"), BONUS("Bonus"), SPLIT("Stock split"), BUYBACK("Buyback"), RIGHTS("Rights issue"),
        AGM("AGM / EGM"), MEETING("Board meeting"), IPO_OPEN("IPO opens"), IPO_CLOSE("IPO closes"), LISTING("New listing"),
        HOLIDAY("Market holiday"), OTHER("Other")
    }

    data class Item(val date: LocalDate, val kind: Kind, val symbol: String, val company: String, val title: String, val source: String = NSE_NAME)

    // -------------------------------------------------------------------- urls

    private fun range(from: LocalDate, to: LocalDate) = "from_date=${from.format(QUERY_DATE)}&to_date=${to.format(QUERY_DATE)}"
    private fun sym(symbol: String) = "symbol=" + WebData.encode(symbol.uppercase())
    private fun api(path: String) = "https://$NSE_HOST/api/$path"

    fun eventsUrl(from: LocalDate, to: LocalDate) = api("event-calendar?index=equities&" + range(from, to))
    fun eventsUrl(symbol: String) = api("event-calendar?index=equities&" + sym(symbol))
    fun actionsUrl(from: LocalDate, to: LocalDate) = api("corporates-corporateActions?index=equities&" + range(from, to))
    fun actionsUrl(symbol: String) = api("corporates-corporateActions?index=equities&" + sym(symbol))
    val ipoUpcomingUrl get() = api("all-upcoming-issues?category=ipo")
    val ipoCurrentUrl get() = api("ipo-current-issue")
    val listingsUrl get() = api("new-listing-today?index=RecentListing")
    val holidaysUrl get() = api("holiday-master?type=trading")
    fun resultsUrl(date: LocalDate) = "https://$MC_HOST/markets/earnings/results-calendar/?activeDate=$date"

    /** A valid NSE trading symbol (letters, digits, & and -), or null for anything else such as a company name. */
    fun symbolOrNull(text: String): String? = text.trim().removePrefix("@").uppercase().takeIf { Regex("""[A-Z0-9&\-]{1,20}""").matches(it) }

    /** "today", "tomorrow", "week", "month", "2026-11-18", "18 Nov" → a date range starting at or after [today]. */
    fun window(text: String, today: LocalDate): Pair<LocalDate, LocalDate>? {
        val t = text.trim().lowercase()
        runCatching { LocalDate.parse(t) }.getOrNull()?.let { return it to it }
        Regex("""^(\d{1,2})\s*([a-z]{3})[a-z]*$""").find(t)?.let { m ->
            val month = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(m.groupValues[2]) + 1
            if (month > 0) runCatching { LocalDate.of(today.year, month, m.groupValues[1].toInt()) }.getOrNull()?.let { d ->
                val day = if (d < today.minusDays(180)) d.plusYears(1) else d
                return day to day
            }
        }
        return when (t) {
            "", "week", "this week", "next week", "7d", "1w" -> today to today.plusDays(7)
            "today" -> today to today
            "tomorrow" -> today.plusDays(1) to today.plusDays(1)
            "month", "this month", "next month", "30d", "1m" -> today to today.plusDays(30)
            else -> null
        }
    }

    // ----------------------------------------------------------------- parsing

    private fun arr(json: String, key: String? = null): List<JsonObject> = runCatching {
        val el: JsonElement = JsonParser.parseString(json)
        val a = when { el.isJsonArray -> el.asJsonArray; key != null && el.isJsonObject -> el.asJsonObject.getAsJsonArray(key); else -> null }
        a?.filter { it.isJsonObject }?.map { it.asJsonObject }
    }.getOrNull().orEmpty()

    private fun JsonObject.s(k: String) = get(k)?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
    private fun date(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim(), NSE_DATE) }.getOrNull()
    private fun tidy(s: String) = s.replace("&amp;", "&").replace(Regex("""\s+"""), " ").trim()

    /** What kind of event a purpose / subject line describes. Order matters: the most specific first. */
    fun kindOf(text: String): Kind {
        val t = text.lowercase()
        return when {
            "split" in t || "sub-division" in t || "subdivision" in t -> Kind.SPLIT
            "bonus" in t -> Kind.BONUS
            "buy back" in t || "buyback" in t -> Kind.BUYBACK
            "rights" in t -> Kind.RIGHTS
            "annual general" in t || Regex("""\b(agm|egm)\b""").containsMatchIn(t) || "extraordinary general" in t -> Kind.AGM
            "result" in t -> Kind.RESULTS
            "dividend" in t || "distribution" in t -> Kind.DIVIDEND
            "board" in t || "fund rais" in t || "business matters" in t -> Kind.MEETING
            else -> Kind.OTHER
        }
    }

    /** NSE event calendar: board meetings and what they will consider (results, dividend, bonus…). */
    fun parseEvents(json: String): List<Item> = arr(json).mapNotNull { o ->
        val d = date(o.s("date")) ?: return@mapNotNull null
        val purpose = tidy(o.s("purpose"))
        Item(d, kindOf(purpose).let { if (it == Kind.OTHER) Kind.MEETING else it }, o.s("symbol"), tidy(o.s("company")), purpose.ifBlank { "Board meeting" })
    }

    /** NSE corporate actions, dated by ex-date: dividends, bonus, splits, buybacks, rights, meetings. */
    fun parseActions(json: String): List<Item> = arr(json).mapNotNull { o ->
        val d = date(o.s("exDate")) ?: return@mapNotNull null
        val subject = tidy(o.s("subject"))
        if (subject.startsWith("Interest Payment", true)) return@mapNotNull null          // bond coupons are noise for equity investors
        Item(d, kindOf(subject), o.s("symbol"), tidy(o.s("comp")), "Ex-date: " + subject.take(90))
    }

    data class Ipo(val name: String, val symbol: String, val opens: LocalDate?, val closes: LocalDate?, val price: String, val shares: String, val series: String) {
        fun status(today: LocalDate) = when {
            opens != null && today < opens -> "Forthcoming"
            closes != null && today > closes -> "Closed"
            opens != null -> "Open"
            else -> "Announced"
        }
    }

    fun parseIpos(json: String): List<Ipo> = arr(json).map { o ->
        Ipo(tidy(o.s("companyName")), o.s("symbol"), date(o.s("issueStartDate")), date(o.s("issueEndDate")),
            o.s("issuePrice").replace("Rs.", "₹").replace(" to ", " – "), o.s("issueSize"), o.s("series").ifBlank { "Equity" })
    }.filter { it.name.isNotBlank() }

    /** Recent equity listings (bonds and other instruments are left out). */
    fun parseListings(json: String): List<Item> = arr(json, "data").mapNotNull { o ->
        if (!o.s("instrument").contains("equit", true) && o.s("series") !in setOf("EQ", "BE", "SM", "ST")) return@mapNotNull null
        if (o.s("symbol").contains("-RE") || o.s("symbol").endsWith("ETF") || o.s("name").contains("Mutual Fund", true)) return@mapNotNull null     // rights entitlements and funds are not company listings
        val d = date(o.s("listing_date")) ?: return@mapNotNull null
        Item(d, Kind.LISTING, o.s("symbol"), tidy(o.s("name")), "Listed on NSE")
    }

    /** Equity-segment trading holidays. */
    fun parseHolidays(json: String): List<Item> = runCatching {
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("CM").map { it.asJsonObject }.mapNotNull { o ->
            Item(date(o.s("tradingDate")) ?: return@mapNotNull null, Kind.HOLIDAY, "", "", tidy(o.s("description")))
        }
    }.getOrDefault(emptyList())

    /** Moneycontrol's results calendar for one day (the page embeds its data as JSON). */
    fun parseResultsPage(html: String, day: LocalDate): List<Item> = runCatching {
        val data = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1]
        JsonParser.parseString(data).asJsonObject.getAsJsonObject("props").getAsJsonObject("pageProps").getAsJsonObject("resultCalendarData")
            .getAsJsonObject("tableData").getAsJsonArray("list").map { it.asJsonObject }.mapNotNull { o ->
                val name = tidy(o.s("stockName")).ifBlank { return@mapNotNull null }
                Item(day, Kind.RESULTS, "", name, listOf(o.s("resultType"), o.s("time").takeIf { !it.contains("Not Available", true) }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Results" }, MC_NAME)
            }
    }.getOrDefault(emptyList())

    fun ipoItems(ipos: List<Ipo>): List<Item> = ipos.flatMap { i ->
        listOfNotNull(i.opens?.let { Item(it, Kind.IPO_OPEN, i.symbol, i.name, "${i.name} · ${i.price}".trim(' ', '·')) },
            i.closes?.let { Item(it, Kind.IPO_CLOSE, i.symbol, i.name, "${i.name} · last day to bid") })
    }

    // ------------------------------------------------------------------ output

    /** The same company and kind on the same day, reported by two feeds, is one event. */
    fun merge(items: List<Item>): List<Item> {
        val out = LinkedHashMap<String, Item>()
        for (i in items.sortedWith(compareBy({ it.date }, { it.kind.ordinal }, { it.company }))) {
            val key = "${i.date}|${i.kind}|${(i.symbol.ifBlank { i.company }).lowercase().take(14)}"
            if (key !in out) out[key] = i
        }
        return out.values.toList()
    }

    /**
     * Calendar tool result. [mine] = the user's own symbols; they are matched here on the phone
     * (never sent anywhere) so their events can be listed first and highlighted.
     */
    fun calendarJson(title: String, items: List<Item>, from: LocalDate, to: LocalDate, today: LocalDate, mine: Set<String> = emptySet(), sources: List<String> = listOf(NSE_NAME)): String {
        val all = merge(items).filter { it.date in from..to }
        if (all.isEmpty()) return gson.toJson(mapOf("error" to "Nothing is scheduled for $title in that period, according to ${sources.joinToString(" and ")}."))
        val own = all.filter { it.symbol.isNotBlank() && it.symbol.uppercase() in mine }
        val counts = all.groupingBy { it.kind }.eachCount().entries.sortedByDescending { it.value }
        val summary = buildString {
            fun low(k: Kind) = k.label.lowercase().replace("ipo", "IPO").replace("agm / egm", "AGM/EGM")
            val company = title != "Market calendar"
            if (company) {
                val next = all.firstOrNull { it.date >= today }; val last = all.lastOrNull { it.date < today }
                append(title).append(": ")
                append(next?.let { "next is ${low(it.kind)} on ${it.date.format(SHOW)} (${it.title.removePrefix("Ex-date: ")})" } ?: "nothing is scheduled ahead yet")
                last?.let { append("; most recent was ${low(it.kind)} on ${it.date.format(SHOW)}") }
                append(". ")
            } else {
                append(title).append(", ").append(if (from == to) from.format(SHOW) else "${from.format(SHOW)} to ${to.format(SHOW)}").append(": ")
                append(counts.joinToString(", ") { "${it.value} ${low(it.key)}" }).append(". ")
            }
            if (own.isNotEmpty() && title == "Market calendar") append("In your holdings: ").append(own.take(4).joinToString("; ") { "${it.symbol} ${it.kind.label.lowercase()} on ${it.date.format(SHOW)}" }).append(". ")
            all.firstOrNull { it.kind == Kind.HOLIDAY }?.let { append("Market closed on ${it.date.format(SHOW)} (${it.title}). ") }
            append("Source: ${sources.joinToString(", ")}.")
        }
        // The user's own events first within each day, then the weightier kinds.
        val ordered = all.sortedWith(compareBy({ it.date }, { if (it.symbol.uppercase() in mine) 0 else 1 }, { it.kind.ordinal }))
        return gson.toJson(mapOf("summary" to summary, "title" to title, "from" to from.toString(), "to" to to.toString(), "today" to today.toString(),
            "total" to all.size, "sources" to sources,
            "counts" to counts.associate { it.key.label to it.value },
            "events" to ordered.map { mapOf("date" to it.date.toString(), "kind" to it.kind.label, "symbol" to it.symbol, "company" to it.company,
                "title" to it.title, "mine" to (it.symbol.isNotBlank() && it.symbol.uppercase() in mine), "phase" to phase(it.date, today), "source" to it.source) }))
    }

    fun phase(date: LocalDate, today: LocalDate) = when { date > today -> "upcoming"; date == today -> "today"; else -> "done" }

    /**
     * IPO overview. NSE is the authority for bid windows and price bands; [gmp] (grey-market tracker) adds the
     * premium, lot size and the allotment / listing dates, and any issue NSE's feed doesn't carry (BSE-only).
     */
    fun ipoJson(ipos: List<Ipo>, listings: List<Item>, today: LocalDate, gmp: List<GmpData.Gmp> = emptyList()): String {
        val extra = GmpData.live(gmp, today).filter { g -> ipos.none { GmpData.find(listOf(g), it.name) != null } }
            .map { Ipo(it.name, "", it.opens, it.closes, it.price?.let { p -> "₹" + com.khayali.pulao.Fmt.qty(p) }.orEmpty(), "", if (it.board == "SME") "SME" else "Equity") }
        @Suppress("NAME_SHADOWING") val ipos = ipos + extra
        if (ipos.isEmpty() && listings.isEmpty()) return gson.toJson(mapOf("error" to "NSE lists no open or upcoming IPOs right now."))
        val sorted = ipos.sortedBy { it.opens ?: LocalDate.MAX }
        val open = sorted.filter { it.status(today) == "Open" }; val next = sorted.filter { it.status(today) == "Forthcoming" }
        val summary = buildString {
            append(if (open.isEmpty()) "No IPO is open for bids today. " else "Open now: " + open.joinToString("; ") { "${it.name} (closes ${it.closes?.format(SHOW)}, ${it.price})" } + ". ")
            if (next.isNotEmpty()) append("Coming up: " + next.joinToString("; ") { "${it.name} opens ${it.opens?.format(SHOW)} and closes ${it.closes?.format(SHOW)}, ${it.price}" } + ". ")
            val premiums = sorted.mapNotNull { i -> GmpData.find(gmp, i.name)?.takeIf { it.gmp != null }?.let { g -> "${i.name.removeSuffix(" Limited")} ₹${com.khayali.pulao.Fmt.qty(g.gmp!!)} (${com.khayali.pulao.Fmt.pct1(g.percent ?: 0.0)})" } }
            if (premiums.isNotEmpty()) append("Grey-market premium (unofficial): " + premiums.joinToString("; ") + ". ")
            if (listings.isNotEmpty()) append("Recently listed: " + listings.sortedByDescending { it.date }.take(3).joinToString(", ") { "${it.symbol} (${it.date.format(SHOW)})" } + ". ")
            append("Source: $NSE_NAME" + (if (gmp.isNotEmpty()) " and ${GmpData.NAME}" else "") + ".")
        }
        return gson.toJson(mapOf("summary" to summary, "today" to today.toString(), "has_gmp" to gmp.isNotEmpty(),
            "ipos" to sorted.map { val g = GmpData.find(gmp, it.name); mapOf("gmp" to g?.gmp, "gmp_percent" to g?.percent, "expected_listing" to g?.expectedListing, "lot" to g?.lot,
                "allotment" to g?.allotment?.toString(), "listing" to g?.listing?.toString(), "stage" to (g?.stage(today) ?: it.status(today)), "subscribed" to g?.subscribed.orEmpty(), "name" to it.name, "symbol" to it.symbol, "opens" to it.opens?.toString(), "closes" to it.closes?.toString(),
                "price" to it.price, "shares" to it.shares, "board" to if (it.series.equals("SME", true)) "SME" else "Main board", "status" to it.status(today)) },
            "listings" to listings.sortedByDescending { it.date }.take(8).map { mapOf("date" to it.date.toString(), "symbol" to it.symbol, "company" to it.company) }))
    }
}
