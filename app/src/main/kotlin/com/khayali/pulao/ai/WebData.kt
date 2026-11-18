package com.khayali.pulao.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.khayali.pulao.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure helpers for the opt-in web tools: building and cleaning queries, reading DuckDuckGo's
 * result page, reading the public quote feed, and finding dated company events in snippets.
 * No networking here — that lives in WebClient, the only web file allowed to connect.
 */
object WebData {
    const val SEARCH_HOST = "html.duckduckgo.com"
    const val QUOTE_HOST = "query1.finance.yahoo.com"
    const val SEARCH_NAME = "DuckDuckGo"
    const val QUOTE_NAME = "Yahoo Finance"

    data class Result(val n: Int, val title: String, val url: String, val site: String, val snippet: String)

    private val gson = Gson()
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    // ----------------------------------------------------------------- queries

    /**
     * What may leave the phone in a search: topic words only. Rupee amounts, long digit runs
     * (quantities, account or phone numbers) and e-mail addresses are removed, whoever wrote the query.
     */
    fun sanitize(query: String): String = query
        .replace(Regex("""[\w.+-]+@[\w-]+\.[\w.]+"""), " ")
        .replace(Regex("""(?i)(₹|\brs\.?|\binr)\s*[\d,]+(\.\d+)?\s*(lakhs?|lacs?|crores?|cr|k)?\b"""), " ")
        .replace(Regex("""\b[\d,]*\d{5,}[\d,]*\b"""), " ")
        .replace(Regex("""\b\d+(\.\d+)?\s*(lakhs?|lacs?|crores?)\b""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""[\u0000-\u001f"<>]"""), " ")
        .replace(Regex("""\s+"""), " ").trim().take(120)

    fun encode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xff).toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-_.~") append(c) else if (c == ' ') append('+') else append('%').append(String.format(Locale.US, "%02X", b.toInt() and 0xff))
        }
    }

    fun decode(s: String): String {
        val out = java.io.ByteArrayOutputStream(); var i = 0
        while (i < s.length) {
            val c = s[i]
            val hex = if (c == '%' && i + 3 <= s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) { out.write(hex); i += 3 }
            else if (c == '+') { out.write(' '.code); i++ }
            else { out.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
        }
        return out.toString("UTF-8")
    }

    fun searchUrl(query: String) = "https://$SEARCH_HOST/html/?kl=in-en&q=" + encode(sanitize(query))

    // ------------------------------------------------------- DuckDuckGo results

    private val LINK = Regex("""<a\b[^>]*class="[^"]*\bresult__a\b[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val SNIPPET = Regex("""class="[^"]*\bresult__snippet\b[^"]*"[^>]*>(.*?)</(?:a|div|span)>""", RegexOption.DOT_MATCHES_ALL)
    private val HREF = Regex("""href="([^"]+)"""")

    /** DuckDuckGo sometimes shows a "prove you're human" page instead of results. */
    fun isChallenge(html: String) = html.contains("anomaly-modal") || html.contains("bots use DuckDuckGo") || html.contains("challenge-form")

    fun parseSearch(html: String, max: Int = 5): List<Result> {
        val links = LINK.findAll(html).toList()
        val out = mutableListOf<Result>()
        for ((i, m) in links.withIndex()) {
            val url = unwrap(HREF.find(m.value)?.groupValues?.get(1) ?: continue) ?: continue
            val site = site(url)
            if (site.isEmpty() || site.endsWith("duckduckgo.com")) continue          // ads and internal links
            val region = html.substring(m.range.last + 1, links.getOrNull(i + 1)?.range?.first ?: html.length)
            val snippet = SNIPPET.find(region)?.groupValues?.get(1)?.let(::text).orEmpty()
            val title = text(m.groupValues[1])
            if (title.isEmpty() || out.any { it.url == url }) continue
            out += Result(out.size + 1, title.take(110), url, site, snippet.take(300))
            if (out.size == max) break
        }
        return out
    }

    /** Result links are wrapped in a redirect ("…/l/?uddg=<encoded target>"): returns the target. Only http(s) links survive. */
    fun unwrap(href: String): String? {
        val h = href.replace("&amp;", "&")
        val target = Regex("""[?&]uddg=([^&]+)""").find(h)?.groupValues?.get(1)?.let(::decode) ?: if (h.startsWith("//")) "https:$h" else h
        return target.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    fun site(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.").lowercase()

    fun text(fragment: String): String = fragment.replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("""&#(\d+);""")) { it.groupValues[1].toIntOrNull()?.takeIf { c -> c in 32..0xFFFF }?.toChar()?.toString() ?: " " }
        .replace(Regex("""\s+"""), " ").trim()

    /** Tool result. [withUrls] = false is the compact form the model reads; the card gets the links. */
    fun searchJson(query: String, results: List<Result>, withUrls: Boolean): String {
        if (results.isEmpty()) return gson.toJson(mapOf("error" to "No web results for \"$query\". Try different words."))
        return gson.toJson(mapOf(
            "summary" to "Web results for \"$query\" from $SEARCH_NAME.",
            "query" to query,
            "instructions" to "Answer only from these snippets. Copy figures and dates exactly. Cite like [1], [2]. If they don't answer the question, say so.",
            "results" to results.map { r ->
                mapOf("n" to r.n, "title" to r.title, "site" to r.site, "snippet" to r.snippet) + if (withUrls) mapOf("url" to r.url) else emptyMap()
            }))
    }

    /** The results inside a search or events tool result (for the sources card and cited fallbacks). */
    fun resultsOf(json: String): List<Result> = runCatching {
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("results").map { it.asJsonObject }.map { r ->
            Result(r["n"].asInt, r["title"].asString, r["url"]?.asString.orEmpty(), r["site"].asString, r["snippet"].asString)
        }
    }.getOrDefault(emptyList())

    /** A cited answer built only from snippets — used when no model is loaded or its text can't be trusted. */
    fun extract(results: List<Result>, max: Int = 3): String {
        val good = results.filter { it.snippet.length > 30 }.take(max)
        if (good.isEmpty()) return results.take(max).joinToString("\n") { "- ${it.title} [${it.n}]" }
        // the best snippet leads as the answer; the others follow as points — sources are listed under it by the app
        return (listOf("${good.first().snippet.trimEnd(' ', '.', '…')}. [${good.first().n}]") + good.drop(1).map { "- ${it.snippet} [${it.n}]" }).joinToString("\n")
    }

    // -------------------------------------------------------------- quote feed

    private val INDEX = mapOf("NIFTY" to "^NSEI", "NIFTY50" to "^NSEI", "NIFTY 50" to "^NSEI", "SENSEX" to "^BSESN",
        "BANKNIFTY" to "^NSEBANK", "NIFTY BANK" to "^NSEBANK", "BANK NIFTY" to "^NSEBANK")

    /** Feed symbols to try for what the user typed: an index, a ticker on NSE then BSE, or nothing (search by name first). */
    fun candidates(input: String): List<String> {
        val s = input.trim().uppercase().removePrefix("NSE:").removePrefix("BSE:")
        INDEX[s]?.let { return listOf(it) }
        if (s.startsWith("^") || s.endsWith(".NS") || s.endsWith(".BO")) return listOf(s)
        return if (Regex("""[A-Z0-9&\-]{1,20}""").matches(s)) listOf("$s.NS", "$s.BO") else emptyList()
    }

    /** period → (range, interval) for the feed. */
    fun range(period: String?): Pair<String, String> = when (period?.lowercase()?.trim()) {
        "1w" -> "5d" to "30m"
        "1m" -> "1mo" to "1d"
        "3m" -> "3mo" to "1d"
        "1y" -> "1y" to "1d"
        "5y" -> "5y" to "1wk"
        else -> "6mo" to "1d"
    }

    fun chartUrl(symbol: String, period: String?): String {
        val (r, i) = range(period)
        return "https://$QUOTE_HOST/v8/finance/chart/${encode(symbol)}?range=$r&interval=$i&events=div%7Csplit"
    }

    fun lookupUrl(name: String) = "https://$QUOTE_HOST/v1/finance/search?quotesCount=8&newsCount=0&q=" + encode(sanitize(name))

    /** First Indian listing in a name-search reply. */
    fun parseLookup(json: String): String? = runCatching {
        val quotes = JsonParser.parseString(json).asJsonObject.getAsJsonArray("quotes").map { it.asJsonObject }
        (quotes.firstOrNull { it["exchange"]?.asString == "NSI" } ?: quotes.firstOrNull { it["exchange"]?.asString == "BSE" })?.get("symbol")?.asString
    }.getOrNull()

    private fun JsonObject.d(k: String) = get(k)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() }

    /**
     * Quote + price history from one chart reply, in the same shape the Kite chart uses
     * (summary, last, high, low, change_percent, points) plus the day's quote and past dividends.
     */
    fun parseChart(json: String, period: String?): String {
        val root = runCatching { JsonParser.parseString(json).asJsonObject.getAsJsonObject("chart") }.getOrNull()
            ?: return gson.toJson(mapOf("error" to "Unreadable reply from the quote feed"))
        val res = root.get("result")?.takeIf { it.isJsonArray && it.asJsonArray.size() > 0 }?.asJsonArray?.get(0)?.asJsonObject
            ?: return gson.toJson(mapOf("error" to (root.getAsJsonObject("error")?.get("description")?.asString ?: "No data for that symbol")))
        val meta = res.getAsJsonObject("meta")
        val ts = res.getAsJsonArray("timestamp")?.map { it.asLong } ?: emptyList()
        val q = res.getAsJsonObject("indicators")?.getAsJsonArray("quote")?.get(0)?.asJsonObject
        fun col(k: String) = q?.getAsJsonArray(k)?.map { if (it.isJsonNull) null else it.asDouble } ?: emptyList()
        val o = col("open"); val h = col("high"); val l = col("low"); val c = col("close"); val v = col("volume")
        val intraday = range(period).second.endsWith("m")
        val pts = ts.indices.filter { c.getOrNull(it) != null }.map { i ->
            val t = Instant.ofEpochSecond(ts[i]).atZone(IST)
            mapOf("t" to if (intraday) t.toLocalDateTime().toString() else t.toLocalDate().toString(),
                "o" to (o.getOrNull(i) ?: c[i]!!), "h" to (h.getOrNull(i) ?: c[i]!!), "l" to (l.getOrNull(i) ?: c[i]!!), "c" to c[i]!!, "v" to (v.getOrNull(i) ?: 0.0))
        }
        val price = meta.d("regularMarketPrice") ?: pts.lastOrNull()?.get("c") as? Double ?: return gson.toJson(mapOf("error" to "No price available"))
        // Yesterday's close: the last bar from before the latest session.
        val lastDay = meta.get("regularMarketTime")?.asLong?.let { Instant.ofEpochSecond(it).atZone(IST).toLocalDate() }
        val prev = ts.indices.lastOrNull { c.getOrNull(it) != null && Instant.ofEpochSecond(ts[it]).atZone(IST).toLocalDate() != lastDay }?.let { c[it] }
            ?: meta.d("chartPreviousClose")
        val dayChg = if (prev != null && prev > 0) Math.round((price - prev) / prev * 1000) / 10.0 else null
        val first = (pts.firstOrNull()?.get("c") as? Double) ?: price
        val hi = pts.maxOfOrNull { it["h"] as Double } ?: price; val lo = pts.minOfOrNull { it["l"] as Double } ?: price
        val chg = if (first > 0) Math.round((price - first) / first * 1000) / 10.0 else 0.0
        val sym = meta.get("symbol")?.asString.orEmpty().removeSuffix(".NS").removeSuffix(".BO")
        val name = (meta.get("longName") ?: meta.get("shortName"))?.takeIf { !it.isJsonNull }?.asString ?: sym
        val asOf = meta.get("regularMarketTime")?.asLong?.let { Instant.ofEpochSecond(it).atZone(IST).format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)) }
        val index = meta.get("symbol")?.asString.orEmpty().startsWith("^")      // index levels are points, not rupees
        val unit = if (index) "" else "₹"
        fun p(x: Double) = unit + Fmt.price(x)
        val events = res.getAsJsonObject("events")?.let { ev ->
            (ev.getAsJsonObject("dividends")?.entrySet()?.map { (_, e) -> e.asJsonObject.let { d -> Event(day(d["date"].asLong), "Dividend", "Dividend ₹${Fmt.qty(d["amount"].asDouble)} a share", 0) } } ?: emptyList()) +
                (ev.getAsJsonObject("splits")?.entrySet()?.map { (_, e) -> e.asJsonObject.let { d -> Event(day(d["date"].asLong), "Split", "Split ${d["splitRatio"]?.asString ?: ""}".trim(), 0) } } ?: emptyList())
        }.orEmpty().sortedBy { it.date }
        return gson.toJson(mapOf(
            "summary" to "$name ($sym) last traded at ${p(price)}" + (dayChg?.let { ", ${if (it >= 0) "+" else "−"}${kotlin.math.abs(it)}% on the day" } ?: "") +
                (asOf?.let { " (as of $it IST)" } ?: "") + ". 52-week range ${meta.d("fiftyTwoWeekLow")?.let(::p) ?: "—"} to ${meta.d("fiftyTwoWeekHigh")?.let(::p) ?: "—"}. " +
                "Over ${label(period)}: ${if (chg >= 0) "+" else "−"}${kotlin.math.abs(chg)}%, high ${p(hi)}, low ${p(lo)}. Source: $QUOTE_NAME, may be delayed.",
            "symbol" to sym, "name" to name, "exchange" to (meta.get("fullExchangeName")?.asString ?: ""), "price" to price, "prev_close" to prev,
            "day_change_percent" to dayChg, "day_high" to meta.d("regularMarketDayHigh"), "day_low" to meta.d("regularMarketDayLow"),
            "high_52w" to meta.d("fiftyTwoWeekHigh"), "low_52w" to meta.d("fiftyTwoWeekLow"), "as_of" to asOf, "source" to QUOTE_NAME, "unit" to unit,
            "period" to (period ?: "6m"), "first" to first, "last" to price, "change_percent" to chg, "high" to hi, "low" to lo,
            "points" to pts, "events" to events.map { mapOf("date" to it.date.toString(), "kind" to it.kind, "title" to it.title) }))
    }

    private fun day(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(IST).toLocalDate()
    private fun label(p: String?) = when (p?.lowercase()) { "1w" -> "1 week"; "1m" -> "1 month"; "3m" -> "3 months"; "1y" -> "1 year"; "5y" -> "5 years"; else -> "6 months" }

    // ------------------------------------------------------------------ events

    /** [source] is the citation number of the search result it came from (0 = the quote feed). */
    data class Event(val date: LocalDate, val kind: String, val title: String, val source: Int)

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MON = """(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\.?"""
    private val DMY = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?(?:\s*[-–]\s*\d{1,2})?\s+$MON,?\s+(\d{4})\b""", RegexOption.IGNORE_CASE)
    private val MDY = Regex("""\b$MON\s+(\d{1,2})(?:st|nd|rd|th)?(?:\s*[-–]\s*\d{1,2})?,?\s+(\d{4})\b""", RegexOption.IGNORE_CASE)
    private val KINDS = listOf(
        "AGM" to Regex("""(?i)\b(agm|annual general meeting)\b"""),
        "Dividend" to Regex("""(?i)\b(dividend|record date|ex-date|ex-dividend)\b"""),
        "Bonus / split" to Regex("""(?i)\b(bonus|stock split|split)\b"""),
        "Results" to Regex("""(?i)\b(results?|earnings|q[1-4]\b|quarter(ly)?)"""),
        "Board meeting" to Regex("""(?i)\bboard\b.*\bmeeting\b|\bmeeting\b.*\bboard\b"""),
    )

    private fun date(d: String, mon: String, y: String): LocalDate? = runCatching {
        LocalDate.of(y.toInt(), MONTHS.indexOf(mon.lowercase().take(3)) + 1, d.toInt())
    }.getOrNull()?.takeIf { it.year in 2000..2100 }

    /** Dated company events mentioned in search snippets. Only dates that sit next to an event word are kept. */
    fun events(results: List<Result>): List<Event> {
        val out = mutableListOf<Event>()
        for (r in results) for (sentence in (r.title + ". " + r.snippet).split(Regex("""(?<=[.!?|])\s+"""))) {
            val kind = KINDS.firstOrNull { it.second.containsMatchIn(sentence) }?.first ?: continue
            val dates = DMY.findAll(sentence).mapNotNull { date(it.groupValues[1], it.groupValues[2], it.groupValues[3]) } +
                MDY.findAll(sentence).mapNotNull { date(it.groupValues[2], it.groupValues[1], it.groupValues[3]) }
            for (d in dates) if (out.none { it.date == d && it.kind == kind }) out += Event(d, kind, sentence.trim().take(120), r.n)
        }
        return out.sortedBy { it.date }
    }

    fun eventsJson(company: String, events: List<Event>, results: List<Result>, today: LocalDate = LocalDate.now(IST)): String {
        if (events.isEmpty() && results.isEmpty()) return gson.toJson(mapOf("error" to "Couldn't find events for $company on the web."))
        val fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        val recent = events.filter { it.date >= today.minusYears(2) }.takeLast(12)
        val next = recent.firstOrNull { it.date >= today }
        val last = recent.lastOrNull { it.date < today }
        val summary = buildString {
            append(company.uppercase()).append(": ")
            append(next?.let { "next dated event found is ${it.kind} on ${it.date.format(fmt)}" } ?: "no confirmed upcoming date was found on the web")
            last?.let { append("; most recent is ${it.kind} on ${it.date.format(fmt)}") }
            append(". Dates come from $QUOTE_NAME and web snippets — confirm on the exchange or company site.")
        }
        return gson.toJson(mapOf("summary" to summary, "company" to company.uppercase(), "today" to today.toString(),
            "events" to recent.map { mapOf("date" to it.date.toString(), "kind" to it.kind, "title" to it.title, "source" to it.source, "upcoming" to (it.date >= today)) },
            "results" to results.map { mapOf("n" to it.n, "title" to it.title, "site" to it.site, "snippet" to it.snippet, "url" to it.url) }))
    }
}
