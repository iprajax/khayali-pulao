package com.khayali.pulao.ai

import com.google.gson.Gson
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Grey-market premium (GMP) for IPOs, read from a public tracker page. GMP is what an IPO share
 * changes hands for, unofficially, before it lists: issue price + GMP = the listing price the
 * grey market currently implies. It is unregulated, thinly traded and can be wrong or moved on
 * purpose, so everything built from it is labelled as an indication, never a forecast.
 * Pure parsing; unit-tested.
 */
object GmpData {
    const val HOST = "www.investorgain.com"
    const val NAME = "InvestorGain"
    val LIST_URL get() = "https://$HOST/report/live-ipo-gmp/331/"
    fun detailUrl(path: String) = "https://$HOST" + path

    const val CAUTION = "GMP is an unofficial, unregulated grey-market indication. It changes daily, can be wrong or manipulated, and is not a forecast or a recommendation to apply."

    data class Gmp(
        val name: String, val board: String, val gmp: Double?, val percent: Double?, val low: Double?, val high: Double?,
        val price: Double?, val size: String, val lot: Int?, val subscribed: String,
        val opens: LocalDate?, val closes: LocalDate?, val allotment: LocalDate?, val listing: LocalDate?, val updated: String, val path: String,
    ) {
        val expectedListing get() = if (gmp != null && price != null) price + gmp else null
        /** Indicative gain on one retail lot at the implied listing price. */
        val perLot get() = if (gmp != null && lot != null) gmp * lot else null

        /** Where the issue is in its life: bidding → allotment → listing. */
        fun stage(today: LocalDate) = when {
            listing != null && today >= listing -> "Listed"
            allotment != null && today >= allotment -> "Allotted, awaiting listing"
            closes != null && today > closes -> "Closed, awaiting allotment"
            opens != null && today >= opens -> "Open for bids"
            else -> "Upcoming"
        }
    }

    data class Point(val date: LocalDate, val gmp: Double, val expected: Double?)

    private val gson = Gson()
    private val SHOW = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private fun num(s: String?) = s?.replace(",", "")?.let { Regex("""-?\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }

    /** "18-Nov" → a date near [today] (the page prints no year). */
    fun day(text: String, today: LocalDate): LocalDate? {
        val m = Regex("""(\d{1,2})[- ]([A-Za-z]{3})""").find(text) ?: return null
        val month = MONTHS.indexOf(m.groupValues[2].lowercase()) + 1
        if (month == 0) return null
        val d = runCatching { LocalDate.of(today.year, month, m.groupValues[1].toInt()) }.getOrNull() ?: return null
        return when { d > today.plusDays(200) -> d.minusYears(1); d < today.minusDays(200) -> d.plusYears(1); else -> d }
    }

    private fun cells(row: String): Map<String, String> =
        Regex("""<td[^>]*data-label="([^"]*)"[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL).findAll(row)
            .associate { WebData.text(WebData.text(it.groupValues[1])) to it.groupValues[2] }

    /** The live GMP table: one row per IPO. */
    fun parseList(html: String, today: LocalDate): List<Gmp> = Regex("""<tr>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL).findAll(html).mapNotNull { r ->
        val c = cells(r.groupValues[1])
        val nameCell = c["Name"] ?: return@mapNotNull null
        val name = Regex("""title="([^"]+)"""").find(nameCell)?.groupValues?.get(1)?.let(WebData::text) ?: return@mapNotNull null
        val gmpText = WebData.text((c["GMP"] ?: "").replace(Regex("""<br\s*/?>"""), " | "))
        val head = gmpText.substringBefore("|")
        val range = Regex("""(-?[\d.]+)\s*↓\s*/\s*(-?[\d.]+)\s*↑""").find(gmpText)
        fun t(k: String) = WebData.text(c[k] ?: "")
        Gmp(name, if (nameCell.contains("SME")) "SME" else "Main board",
            gmp = if (head.contains("--")) null else num(head.substringBefore("(")),
            percent = Regex("""\((-?[\d.]+)%\)""").find(head)?.groupValues?.get(1)?.toDoubleOrNull(),
            low = range?.groupValues?.get(1)?.toDoubleOrNull(), high = range?.groupValues?.get(2)?.toDoubleOrNull(),
            price = num(t("Price (₹)")), size = t("IPO Size"), lot = num(t("Lot"))?.toInt(), subscribed = t("Sub").takeIf { it != "-" }.orEmpty(),
            opens = day(t("Open"), today), closes = day(t("Close"), today), allotment = day(t("BoA Dt"), today), listing = day(t("Listing"), today),
            updated = t("Updated-On"), path = Regex("""href="(/gmp/[^"]+)"""").find(nameCell)?.groupValues?.get(1).orEmpty())
    }.distinctBy { it.name }.toList()

    /** Day-by-day GMP for one IPO (first table of its page), oldest first, one point per day. */
    fun parseTrend(html: String, today: LocalDate): List<Point> {
        val table = Regex("""<table.*?</table>""", RegexOption.DOT_MATCHES_ALL).findAll(html).firstOrNull { it.value.contains("GMP Date") }?.value ?: return emptyList()
        return Regex("""<tr.*?</tr>""", RegexOption.DOT_MATCHES_ALL).findAll(table).mapNotNull { r ->
            val td = Regex("""<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL).findAll(r.value).map { WebData.text(it.groupValues[1]) }.toList()
            if (td.size < 3) return@mapNotNull null
            Point(day(td[0], today) ?: return@mapNotNull null, num(td[1].substringBefore("(")) ?: return@mapNotNull null, num(td[2]))
        }.toList().groupBy { it.date }.map { it.value.first() }.sortedBy { it.date }      // rows are newest first: keep each day's latest
    }

    private fun key(name: String) = name.lowercase().replace(Regex("""\b(limited|ltd|ipo|india|the)\b|[^a-z0-9 ]"""), " ").trim().split(Regex("""\s+""")).take(2).joinToString(" ")
    /** Matches a tracker row to an exchange name: "Crystal Crop Protection" ↔ "Crystal Crop Protection Limited". */
    fun find(list: List<Gmp>, name: String): Gmp? {
        val k = key(name); if (k.isBlank()) return null
        return list.firstOrNull { key(it.name) == k } ?: list.firstOrNull { key(it.name).startsWith(k.substringBefore(' ')) && k.length > 3 && key(it.name).substringBefore(' ') == k.substringBefore(' ') }
    }

    private fun inr(v: Double) = "₹" + com.khayali.pulao.Fmt.qty(Math.round(v * 100) / 100.0)
    private fun row(g: Gmp, today: LocalDate) = mapOf("name" to g.name, "board" to g.board, "gmp" to g.gmp, "gmp_percent" to g.percent, "gmp_low" to g.low, "gmp_high" to g.high,
        "issue_price" to g.price, "expected_listing" to g.expectedListing, "lot" to g.lot, "per_lot" to g.perLot, "size" to g.size, "subscribed" to g.subscribed,
        "opens" to g.opens?.toString(), "closes" to g.closes?.toString(), "allotment" to g.allotment?.toString(), "listing" to g.listing?.toString(),
        "stage" to g.stage(today), "updated" to g.updated)

    /** Issues that are still in play: upcoming, open, or not yet listed. */
    fun live(list: List<Gmp>, today: LocalDate) = list.filter { it.stage(today) != "Listed" }.sortedBy { it.opens ?: LocalDate.MAX }

    /** /gmp — every live issue with its premium. */
    fun listJson(list: List<Gmp>, today: LocalDate): String {
        val rows = live(list, today)
        if (rows.isEmpty()) return gson.toJson(mapOf("error" to "No upcoming or open IPO has a grey-market premium listed on $NAME right now."))
        val quoted = rows.filter { it.gmp != null }
        val summary = (if (quoted.isEmpty()) "No grey-market premium is quoted yet for the ${rows.size} IPOs in play. "
            else "Grey-market premium now: " + quoted.sortedByDescending { it.percent ?: 0.0 }.take(5).joinToString("; ") {
                "${it.name} ${inr(it.gmp!!)} (${com.khayali.pulao.Fmt.pct1(it.percent ?: 0.0)}), implied listing ${it.expectedListing?.let(::inr) ?: "—"}" } + ". ") +
            "Source: $NAME. $CAUTION"
        return gson.toJson(mapOf("summary" to summary, "today" to today.toString(), "source" to NAME, "ipos" to rows.map { row(it, today) }))
    }

    /** /gmp NAME — one issue: its premium, the range it has traded in, the trend, and what listing price that implies. */
    fun detailJson(g: Gmp, trend: List<Point>, today: LocalDate): String {
        val first = trend.firstOrNull(); val last = trend.lastOrNull()
        val direction = when { first == null || last == null || trend.size < 2 -> null; last.gmp > first.gmp -> "risen"; last.gmp < first.gmp -> "fallen"; else -> "held steady" }
        val summary = buildString {
            append("${g.name} (${g.board}, ${g.stage(today).lowercase()}): ")
            if (g.gmp == null) append("no grey-market premium is quoted yet. ")
            else {
                append("grey-market premium ${inr(g.gmp)} (${com.khayali.pulao.Fmt.pct1(g.percent ?: 0.0)}) on an issue price of ${g.price?.let(::inr) ?: "—"}, which implies a listing near ${g.expectedListing?.let(::inr) ?: "—"}")
                g.perLot?.let { append(", about ${inr(it)} on one lot of ${g.lot}") }
                append(". ")
                if (direction != null) append("It has $direction from ${inr(first!!.gmp)} on ${first.date.format(SHOW)} to ${inr(last!!.gmp)} on ${last.date.format(SHOW)}. ")
            }
            g.listing?.let { append("Listing is expected on ${it.format(SHOW)}") }; g.allotment?.let { append(", allotment on ${it.format(SHOW)}") }
            append(". Source: $NAME. $CAUTION")
        }
        return gson.toJson(row(g, today) + mapOf("summary" to summary, "today" to today.toString(), "source" to NAME,
            "trend" to trend.map { mapOf("t" to it.date.toString(), "c" to it.gmp, "expected" to it.expected) }))
    }
}
