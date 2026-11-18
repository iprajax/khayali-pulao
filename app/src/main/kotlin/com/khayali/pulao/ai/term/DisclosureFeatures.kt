package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.data.Filings
import java.time.format.DateTimeFormatter

private val DMY = DateTimeFormatter.ofPattern("dd-MM-yyyy")

/** /sebi [circulars|orders|press|consultation] — the regulator's latest: circulars, orders, press releases, consultation papers. */
object SebiFeature : Feature {
    override val id = "sebi"; override val usage = "/sebi"; override val group = Group.MARKET
    override val help = "SEBI's latest: circulars, orders, press releases and consultation papers, by type"
    override val method = "Read from SEBI's own What's New list on sebi.gov.in (the 25 newest items: date, type, title, link), fetched by this phone. " +
        "Nothing about you is sent. Tap an item to read it on SEBI's site."
    override val triggers = listOf(Regex("""(?i)^((latest |new )?sebi( news| updates?| circulars?| orders?| rules?)?|what('s| is) new (at|from) sebi|regulator updates?)\??$"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val all = runCatching { Filings.parseSebi(f.get(Feeds.SEBI.url)) }.getOrDefault(emptyList())
        if (all.isEmpty()) return Card.error("Couldn't read SEBI's What's New list just now.", "/sebi")
        val want = arg.trim().lowercase()
        val list = if (want.isBlank()) all else all.filter { it.category.lowercase().startsWith(want.take(5)) }.ifEmpty { all }
        val counts = all.groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }
        val newest = all.mapNotNull { it.date }.maxOrNull()
        return Card("SEBI · what's new" + (newest?.let { " · ${Sec.d(it)}" } ?: ""), "violet")
            .headline("${all.size} latest from the regulator", counts.take(4).joinToString(" · ") { "${it.value} ${it.key.lowercase()}" })
            .bars("By type", counts.map { Card.Bar(it.key, it.value.toDouble(), "${it.value}") }, "")
            .rows(null, list.take(14).map { n -> Card.Row(n.title, listOfNotNull(n.date?.let(Sec::d), n.category).joinToString(" · "), "", "", action = n.link) })
            .note("Straight from sebi.gov.in. Circulars and orders change rules and name parties; read the full text before drawing conclusions.")
            .sources("SEBI").put("links", list.take(14).map { it.link })
            .pills(*(counts.take(3).map { "/sebi ${it.key.lowercase().substringBefore(' ')}" } + "/radar").toTypedArray())
            .json("SEBI's latest ${all.size}: " + counts.joinToString(", ") { "${it.value} ${it.key.lowercase()}" } + ". Newest: " + all.take(3).joinToString("; ") { it.title } + ". Source: sebi.gov.in.")
    }
}

/** /radar [mine] — exchange filings in the last two days, sorted into what matters, your stocks first; plus takeover/pledge and rating actions. */
object RadarFeature : Feature {
    override val id = "radar"; override val usage = "/radar"; override val group = Group.MARKET
    override val help = "Filings radar: the last two days' exchange filings sorted by what matters, your stocks first"
    override val method = "Company filings come from NSE's corporate-announcements list for the last two days (or NSE's live announcements feed if that's unavailable), " +
        "read by this phone; fund NAV notices are dropped. Each filing is sorted into one category by its subject and first lines (results, orders and deals, ratings, " +
        "management changes, pledge and takeover, insider trading, legal and regulatory, dividends and corporate actions, fund raising, meetings). Takeover (SAST Reg 29) " +
        "and credit-rating actions come from NSE's own disclosure lists. Your holdings and watched stocks are matched on the phone and shown first."
    override val triggers = listOf(Regex("""(?i)^((latest |today'?s |new )?(filings?|disclosures?|corporate announcements?|exchange announcements?)( today)?|filings? radar|any filings( today)?)\??$"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val to = f.today; val from = to.minusDays(2)
        val range = "from_date=${from.format(DMY)}&to_date=${to.format(DMY)}"
        var all = Filings.parseNseApi(runCatching { f.nse("corporate-announcements?index=equities&$range") }.getOrDefault(""))
        var src = "NSE announcements"
        if (all.isEmpty()) { all = runCatching { Filings.parseNse(f.get(Feeds.NSE_FILINGS.url)) }.getOrDefault(emptyList()); src = "NSE live feed" }
        if (all.isEmpty()) return Card.error("Couldn't read NSE's filings just now.", "/radar")
        val mine = (f.holdings.map { it.name.uppercase() } + f.watched.map { it.uppercase() }).toSet()
        val onlyMine = arg.trim().equals("mine", true)
        val shown = (if (onlyMine) all.filter { it.symbol in mine } else all).sortedWith(compareBy<Filings.Filing>({ it.symbol !in mine }, { Filings.ORDER.indexOf(it.category) }).thenByDescending { it.at })
        val counts = all.groupingBy { it.category }.eachCount().entries.sortedBy { Filings.ORDER.indexOf(it.key) }
        fun row(x: Filings.Filing) = Card.Row((if (x.symbol in mine) "★ " else "") + (x.symbol ?: x.company),
            listOfNotNull(x.category, x.subject.takeIf { it.isNotBlank() && it != x.category }, x.at?.let { "${it.dayOfMonth} ${it.month.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }} ${"%02d:%02d".format(it.hour, it.minute)}" }).joinToString(" · ") +
                (if (x.text.isNotBlank()) "\n" + x.text.take(160) else ""), "", if (x.category == "Legal & regulatory" || x.category == "Pledge & takeover") "warn" else "", action = x.link)
        val c = Card("Filings radar · ${all.size} filings · ${Sec.d(from)} – ${Sec.d(to)}", "blue")
            .headline("${shown.count { it.symbol in mine }} about your stocks", counts.take(4).joinToString(" · ") { "${it.value} ${it.key.lowercase()}" })
            .bars("Filings by type", counts.map { Card.Bar(it.key, it.value.toDouble(), "${it.value}") }, "")
        val yours = shown.filter { it.symbol in mine }
        if (yours.isNotEmpty()) c.rows("Your stocks", yours.take(8).map(::row))
        val key = shown.filter { it.symbol !in mine && it.category in Filings.ORDER.take(7) }
        if (key.isNotEmpty() && !onlyMine) c.rows("What matters", key.take(12).map(::row))
        // takeover and pledge disclosures, and credit-rating actions, from NSE's own lists
        val sast = runCatching { com.google.gson.JsonParser.parseString(f.nse("corporate-sast-reg29?index=equities&$range")).let { if (it.isJsonArray) it.asJsonArray else it.asJsonObject.getAsJsonArray("data") } }.getOrNull()
        fun com.google.gson.JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        sast?.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }?.take(6)?.takeIf { it.isNotEmpty() }?.let { rows -> c.rows("Takeover disclosures (SAST)", rows.map { o ->
            fun s(k: String) = o.str(k)
            Card.Row((if (s("symbol") in mine) "★ " else "") + s("symbol").ifBlank { s("company") }, listOf(s("acquirerName"), s("acqSaleType"), s("regType")).filter { it.isNotBlank() }.joinToString(" · "), "", "warn") }) }
        val ratings = runCatching { com.google.gson.JsonParser.parseString(f.nse("corporate-credit-rating?index=equities&$range")).let { if (it.isJsonArray) it.asJsonArray else it.asJsonObject.getAsJsonArray("data") } }.getOrNull()
        ratings?.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }?.filter { it.str("Symbol").let { s -> s.isNotBlank() && s != "NOTLISTED" } }?.take(6)?.takeIf { it.isNotEmpty() }?.let { rows -> c.rows("Credit rating actions", rows.map { o ->
            fun s(k: String) = o.str(k)
            val down = s("RatingAction").contains("down", true)
            Card.Row((if (s("Symbol") in mine) "★ " else "") + s("Symbol"), listOf(s("NameOfCRAgency"), s("RatingAction"), s("CreditRating"), s("Outlook")).filter { it.isNotBlank() }.joinToString(" · "), "", if (down) "down" else "") }) }
        return c.note("Filings are as companies submitted them to the exchange. Tap one to open the document; tap a stock for its wheel.")
            .sources(src).put("links", shown.take(12).map { it.link })
            .pills(*listOfNotNull(yours.firstOrNull()?.symbol?.let { "/why $it" }, if (!onlyMine) "/radar mine" else null, "/sebi", "/news").toTypedArray())
            .json("${all.size} filings over two days: " + counts.joinToString(", ") { "${it.value} ${it.key.lowercase()}" } + ". " +
                (if (yours.isNotEmpty()) "About your stocks: " + yours.take(3).joinToString("; ") { "${it.symbol} — ${it.subject.ifBlank { it.category }}" } + ". " else "None about your stocks. ") + "Source: $src.")
    }
}

