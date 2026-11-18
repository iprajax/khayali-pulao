package com.khayali.pulao.ai.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Exchange filings (NSE's live announcements feed) and the regulator's latest (SEBI's What's New list), read on
 * the phone. Pure: parsing, the symbol behind each filing, and a fixed set of categories that matter to investors.
 */
object Filings {
    data class Filing(val symbol: String?, val company: String, val subject: String, val text: String, val at: LocalDateTime?, val link: String) {
        val category get() = categorize(subject, text)
    }

    private fun clean(s: String) = s.replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
    private val NSE_TIME = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH)

    /** NSE's Online_announcements.xml. The PDF's file name starts with the symbol (BIOCON_18112026….pdf); names are the fallback. */
    fun parseNse(xml: String, byName: Map<String, String> = emptyMap()): List<Filing> =
        Regex("<item>([\\s\\S]*?)</item>").findAll(xml).mapNotNull { m -> val b = m.groupValues[1]
            fun tag(t: String) = Regex("<$t>([\\s\\S]*?)</$t>").find(b)?.groupValues?.get(1)?.let(::clean).orEmpty()
            val company = tag("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val link = tag("link"); val desc = tag("description")
            val subject = desc.substringAfter("|SUBJECT:", "").trim()
            // mutual-fund NAV declarations fill most of the feed and aren't company news
            if (Regex("(?i)declaration of nav|net asset value").containsMatchIn(subject)) return@mapNotNull null
            val prefix = Regex("^([A-Z0-9&\\-]{2,20})\\s+:").find(desc)?.groupValues?.get(1)
            val text = desc.substringBefore("|SUBJECT:").replace(Regex("^[A-Z0-9&\\-]{2,20}\\s+:\\s*"), "").trim()
            val fromFile = link.substringAfterLast('/').substringBefore('_').uppercase().takeIf { link.isNotBlank() && it.matches(Regex("[A-Z0-9&\\-]{2,20}")) }
            val sym = fromFile ?: prefix ?: byName[News.key(company)]
            Filing(sym, company, subject, text, runCatching { LocalDateTime.parse(tag("pubDate"), NSE_TIME) }.getOrNull(), link)
        }.toList()

    /** NSE's corporate-announcements API (symbol, sm_name, desc, attchmntText, attchmntFile, an_dt). */
    fun parseNseApi(json: String): List<Filing> = runCatching {
        val el = com.google.gson.JsonParser.parseString(json)
        val arr = if (el.isJsonArray) el.asJsonArray else el.asJsonObject.getAsJsonArray("data")
        arr.mapNotNull { e -> val o = e.asJsonObject
            fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val subject = s("desc"); if (Regex("(?i)declaration of nav").containsMatchIn(subject)) return@mapNotNull null
            Filing(s("symbol").ifBlank { null }, s("sm_name"), subject, clean(s("attchmntText")), runCatching { LocalDateTime.parse(s("an_dt"), NSE_TIME) }.getOrNull(), s("attchmntFile"))
        }
    }.getOrDefault(emptyList())

    /** The categories investors scan for, most important first. */
    val ORDER = listOf("Results", "Orders & deals", "Rating", "Management change", "Pledge & takeover", "Insider trading", "Legal & regulatory",
        "Dividend & corporate action", "Fund raising", "Meetings & calls", "Other")

    fun categorize(subject: String, text: String): String {
        val s = (subject + " " + text.take(240)).lowercase()
        return when {
            Regex("financial result|outcome of board meeting.*result|quarterly result|audited|unaudited").containsMatchIn(s) -> "Results"
            Regex("credit rating|rating").containsMatchIn(s) -> "Rating"
            Regex("award|order|contract|bagging|receiving of order|letter of intent|acquisition|merger|amalgamation|joint venture|agreement|stake").containsMatchIn(s) -> "Orders & deals"
            Regex("resignation|appointment|cessation|change in (director|management|kmp|auditor)|managing director|ceo|cfo").containsMatchIn(s) -> "Management change"
            Regex("pledge|encumbrance|sast|takeover|substantial acquisition|open offer").containsMatchIn(s) -> "Pledge & takeover"
            Regex("insider|pit|trading window|disclosure under regulation 7").containsMatchIn(s) -> "Insider trading"
            Regex("litigation|dispute|penalty|show cause|sebi order|court|tribunal|nclt|search|raid|default|fraud").containsMatchIn(s) -> "Legal & regulatory"
            Regex("dividend|record date|bonus|split|buyback|buy back|rights issue|book closure").containsMatchIn(s) -> "Dividend & corporate action"
            Regex("allotment|preferential|qip|ncd|debenture|warrant|esop|fund raising|issue of securities").containsMatchIn(s) -> "Fund raising"
            Regex("analyst|investor meet|con\\.? ?call|conference call|earnings call|board meeting|agm|egm|postal ballot|transcript|audio recording").containsMatchIn(s) -> "Meetings & calls"
            else -> "Other"
        }
    }

    // ---------------------------------------------------------------- SEBI

    data class Notice(val date: LocalDate?, val category: String, val title: String, val link: String)
    private val SEBI_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

    /** SEBI's What's New listing: one table row per item (date, type, linked title). */
    fun parseSebi(html: String): List<Notice> = Regex("<tr[^>]*>([\\s\\S]*?)</tr>").findAll(html).mapNotNull { m ->
        val cells = Regex("<td[^>]*>([\\s\\S]*?)</td>").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
        if (cells.size < 3) return@mapNotNull null
        val a = Regex("<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>").find(cells.last()) ?: return@mapNotNull null
        val title = Regex("title=\"([^\"]+)\"").find(a.value)?.groupValues?.get(1)?.let(::clean)?.substringBefore("Click here")?.trim() ?: clean(a.groupValues[2])
        Notice(runCatching { LocalDate.parse(clean(cells[0]), SEBI_DATE) }.getOrNull(), clean(cells[1]), title, a.groupValues[1])
    }.toList()

    /** Headlines that read as a buy/sell call or target are never relayed (the app doesn't make calls). */
    private val CALL = Regex("""(?i)\b(stocks? to (buy|sell|avoid)|buy or sell|top picks?|target price|price target|buy (call|rating)|sell (call|rating)|should you buy|multibagger|tips?\b|recommend(s|ed|ation)?\b.*\b(buy|sell))|[–-]\s*(buy|sell|hold)\s*$|\b(buy|sell|accumulate|book profit)\b.*\btarget\b""")
    fun isCall(title: String) = CALL.containsMatchIn(title)
}
