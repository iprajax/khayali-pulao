package com.khayali.pulao.ai.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Market news, worked out on the phone: read RSS/Atom feeds, drop duplicates across outlets, tag each headline's
 * tone with a finance word list, and link it to the stocks it names (company names and common short forms).
 * Pure and unit-tested; the phone fetches the feeds (opt-in web) and nothing about the user is sent.
 */
object News {
    data class Item(val title: String, val link: String, val source: String, val published: Instant?, val summary: String)
    data class Story(val item: Item, val also: List<String>, val tone: Int, val tickers: List<String>)

    private fun unescape(s: String) = s.replace("<![CDATA[", "").replace("]]>", "")
        // escaped HTML (&lt;p&gt;…) is decoded first, then every tag is stripped
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
        .replace(Regex("<[^>]+>"), " ").replace("&amp;", "&")
        .replace(Regex("\\s+"), " ").trim()

    private val RFC1123 = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
    private val RFC_NOZONE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss z", Locale.ENGLISH)
    fun date(s: String?): Instant? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { ZonedDateTime.parse(t, RFC1123).toInstant() }.recoverCatching { ZonedDateTime.parse(t, RFC_NOZONE).toInstant() }
            .recoverCatching { Instant.parse(t) }.recoverCatching { ZonedDateTime.parse(t).toInstant() }
            .recoverCatching { java.time.LocalDateTime.parse(t.replace(' ', 'T').take(19)).atZone(ZoneId.of("Asia/Kolkata")).toInstant() }.getOrNull()
    }

    /** RSS 2.0 <item> or Atom <entry>. */
    fun parse(xml: String, source: String): List<Item> {
        fun tag(block: String, name: String) = Regex("<$name(?:\\s[^>]*)?>([\\s\\S]*?)</$name>", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.let(::unescape)
        val rss = Regex("<item(?:\\s[^>]*)?>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE).findAll(xml).map { it.groupValues[1] }.toList()
        if (rss.isNotEmpty()) return rss.mapNotNull { b ->
            val title = tag(b, "title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Item(title, tag(b, "link").orEmpty(), source, date(tag(b, "pubDate") ?: tag(b, "dc:date")), tag(b, "description").orEmpty().take(400))
        }
        return Regex("<entry(?:\\s[^>]*)?>([\\s\\S]*?)</entry>", RegexOption.IGNORE_CASE).findAll(xml).mapNotNull { m -> val b = m.groupValues[1]
            val title = tag(b, "title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val link = Regex("<link[^>]*href=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(b)?.groupValues?.get(1).orEmpty()
            Item(title, link, source, date(tag(b, "updated") ?: tag(b, "published")), (tag(b, "summary") ?: tag(b, "content")).orEmpty().take(400))
        }.toList()
    }

    // ---------------------------------------------------------------- tone

    private val UP = setOf("surge", "surges", "soar", "soars", "jump", "jumps", "rally", "rallies", "gain", "gains", "rise", "rises", "rose", "climb", "climbs", "record", "high",
        "beat", "beats", "upgrade", "upgrades", "upgraded", "profit", "growth", "grows", "strong", "wins", "win", "bags", "order", "orders", "approval", "approves", "approved",
        "buyback", "dividend", "expansion", "expands", "boost", "boosts", "outperform", "bullish", "recovers", "rebound", "rebounds", "positive", "upbeat", "robust", "higher", "best")
    private val DOWN = setOf("plunge", "plunges", "slump", "slumps", "fall", "falls", "fell", "drop", "drops", "slide", "slides", "crash", "crashes", "tank", "tanks", "tumble", "tumbles",
        "loss", "losses", "decline", "declines", "downgrade", "downgrades", "downgraded", "miss", "misses", "weak", "weaker", "probe", "raid", "raids", "penalty", "fine", "fined", "fraud",
        "default", "defaults", "resigns", "resignation", "cut", "cuts", "lower", "lowest", "bearish", "selloff", "sell-off", "slowdown", "warning", "concern", "concerns", "pressure",
        "ban", "bans", "lawsuit", "scam", "worst", "underperform", "negative", "outflow", "outflows", "slips", "slip", "dips", "dip", "falters", "plummets")
    private val NOT = setOf("no", "not", "despite", "without")

    /** +1 positive, −1 negative, 0 neutral — from the headline (and summary start). */
    fun tone(text: String): Int {
        val w = Regex("[a-z\\-]+").findAll(text.lowercase()).map { it.value }.toList()
        var s = 0
        for ((i, x) in w.withIndex()) {
            val sign = if (i > 0 && w[i - 1] in NOT) -1 else 1
            if (x in UP) s += sign else if (x in DOWN) s -= sign
        }
        return s.coerceIn(-1, 1)
    }

    // ---------------------------------------------------------------- tickers

    /** Short forms newsrooms use that don't follow from the company name. */
    val ALIASES = mapOf("ril" to "RELIANCE", "reliance" to "RELIANCE", "sbi" to "SBIN", "l&t" to "LT", "larsen" to "LT", "hul" to "HINDUNILVR", "m&m" to "M&M", "mahindra & mahindra" to "M&M",
        "airtel" to "BHARTIARTL", "bharti airtel" to "BHARTIARTL", "infosys" to "INFY", "tcs" to "TCS", "hcl tech" to "HCLTECH", "hcltech" to "HCLTECH", "wipro" to "WIPRO", "itc" to "ITC",
        "ongc" to "ONGC", "ntpc" to "NTPC", "bel" to "BEL", "hal" to "HAL", "bhel" to "BHEL", "irctc" to "IRCTC", "lic" to "LICI", "zomato" to "ETERNAL", "eternal" to "ETERNAL",
        "paytm" to "PAYTM", "nykaa" to "NYKAA", "maruti" to "MARUTI", "bajaj finance" to "BAJFINANCE", "kotak" to "KOTAKBANK", "icici bank" to "ICICIBANK", "hdfc bank" to "HDFCBANK",
        "axis bank" to "AXISBANK", "adani ports" to "ADANIPORTS", "adani enterprises" to "ADANIENT", "tata steel" to "TATASTEEL", "tata power" to "TATAPOWER", "sun pharma" to "SUNPHARMA",
        "dr reddy's" to "DRREDDY", "asian paints" to "ASIANPAINT", "ultratech" to "ULTRACEMCO", "titan" to "TITAN", "nestle" to "NESTLEIND", "coal india" to "COALINDIA", "vedanta" to "VEDL",
        "hindalco" to "HINDALCO", "jsw steel" to "JSWSTEEL", "power grid" to "POWERGRID", "indigo" to "INDIGO", "interglobe" to "INDIGO", "dmart" to "DMART", "avenue supermarts" to "DMART")

    private val SUFFIX = Regex("""\b(ltd|limited|corporation|corp|company|co|india|industries|inds|the|enterprises)\b\.?""", RegexOption.IGNORE_CASE)
    fun key(name: String) = SUFFIX.replace(name.lowercase().replace(Regex("[.,()]"), " "), " ").replace(Regex("\\s+"), " ").trim()

    /** Phrases to look for → symbol: each company's cleaned name, its first word when no other company shares it, and [ALIASES]. */
    /** Phrases that must appear capitalised to count (single-word company names that are also ordinary words: "Worth", "Bliss"). */
    val PROPER = HashSet<String>()

    fun matcher(names: Map<String, String>): Map<String, String> {
        val out = HashMap<String, String>()
        val firstCount = names.values.map { key(it).substringBefore(' ') }.groupingBy { it }.eachCount()
        for ((sym, name) in names) {
            val k = key(name); if (k.length >= 4) out[k] = sym
            val first = k.substringBefore(' ')
            if (first.length >= 5 && firstCount[first] == 1 && first !in COMMON && out.putIfAbsent(first, sym) == null) PROPER += first
        }
        ALIASES.forEach { (a, s) -> out[a] = s }
        return out
    }
    private val COMMON = setOf("india", "indian", "national", "global", "power", "bharat", "united", "state", "steel", "capital", "finance", "energy", "general",
        "first", "future", "prime", "super", "royal", "metro", "digital", "smart", "green", "modern", "ocean", "shree", "sree", "sri", "jai", "premier", "standard", "supreme",
        "advance", "allied", "asian", "british", "central", "classic", "credit", "crown", "eastern", "western", "northern", "southern", "electronics", "foods", "gold", "healthcare",
        "housing", "hotels", "infra", "insurance", "international", "investment", "media", "mining", "motors", "paper", "pharma", "plastics", "polymers", "power", "projects",
        "real", "rural", "security", "services", "shipping", "software", "solar", "sugar", "systems", "technologies", "textiles", "trading", "transport", "vision", "water", "world")

    /** Stocks named in [text], in order of first mention. */
    fun tickers(text: String, matcher: Map<String, String>, symbols: Set<String>): List<String> {
        val low = " " + text.lowercase().replace(Regex("[^a-z0-9&' ]"), " ").replace(Regex("\\s+"), " ") + " "
        val hits = matcher.entries.mapNotNull { (k, s) ->
            if (k in PROPER) Regex("""\b${Regex.escape(k.replaceFirstChar { it.uppercase() })}\b""").find(text)?.let { it.range.first to s }   // a name, not the word
            else low.indexOf(" $k ").takeIf { it >= 0 }?.let { it to s }
        }.toMutableList()
        Regex("""\b[A-Z][A-Z0-9&]{2,14}\b""").findAll(text).forEach { m -> if (m.value in symbols && m.value !in com.khayali.pulao.ai.Tickers.STOP) hits += m.range.first to m.value }
        return hits.sortedBy { it.first }.map { it.second }.distinct().take(4)
    }

    // ---------------------------------------------------------------- stories

    private fun words(s: String) = Regex("[a-z0-9]{3,}").findAll(s.lowercase()).map { it.value }.filter { it !in STOP }.toSet()
    private val STOP = setOf("the", "and", "for", "with", "from", "after", "over", "into", "amid", "says", "said", "its", "his", "her", "this", "that", "will", "has", "have", "are", "was", "today", "stock", "stocks", "share", "shares", "market", "markets")

    /** Newest first, one story per event (headlines sharing most words across outlets are folded together). */
    fun stories(items: List<Item>, matcher: Map<String, String>, symbols: Set<String>, max: Int = 30): List<Story> {
        val sorted = items.sortedByDescending { it.published ?: Instant.EPOCH }
        val out = mutableListOf<Pair<Item, MutableList<String>>>(); val seen = mutableListOf<Set<String>>()
        for (it in sorted) {
            val w = words(it.title); if (w.isEmpty()) continue
            val j = seen.indexOfFirst { s -> val inter = (s intersect w).size; inter >= 3 && inter.toDouble() / (s union w).size >= 0.45 }
            if (j >= 0) { if (it.source !in out[j].second && it.source != out[j].first.source) out[j].second += it.source; continue }
            out += it to mutableListOf(); seen += w
            if (out.size >= max) break
        }
        // the headline sets the tone; the summary only breaks a tie
        return out.map { (i, also) -> Story(i, also, tone(i.title).takeIf { it != 0 } ?: tone(i.summary.take(160)), tickers(i.title + " " + i.summary.take(200), matcher, symbols)) }
    }

    /** "2 h ago", "35 min ago", "yesterday". */
    fun ago(t: Instant?, now: Instant): String {
        t ?: return ""
        val m = java.time.Duration.between(t, now).toMinutes().coerceAtLeast(0)
        return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 24 * 60 -> "${m / 60} h ago"; m < 48 * 60 -> "yesterday"; else -> "${m / 1440} days ago" }
    }
}
