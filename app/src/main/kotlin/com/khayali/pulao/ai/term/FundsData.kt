package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.WebData
import com.khayali.pulao.ai.term.J.a
import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.o
import com.khayali.pulao.ai.term.J.s
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Mutual-fund NAV history from mfapi.in (a free mirror of AMFI's daily NAV file): search by name, then one scheme's NAVs. */
object FundsData {
    const val HOST = "api.mfapi.in"
    const val SOURCE = "AMFI NAVs via mfapi.in"
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val FEED_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    data class Hit(val code: Int, val name: String)

    /** One scheme's NAV history, oldest first. */
    class Scheme(val code: Int, val name: String, val house: String, val category: String, val dates: List<LocalDate>, val navs: List<Double>) {
        val last get() = navs.last()
        val lastDate: LocalDate get() = dates.last()
        /** "Parag Parikh Flexi Cap Fund - Direct Plan - Growth" → "Parag Parikh Flexi Cap Fund". */
        val short: String get() = short(name)
        val plan: String get() = listOfNotNull(
            if (name.contains("direct", true)) "Direct plan" else if (name.contains("regular", true)) "Regular plan" else null,
            if (INCOME.containsMatchIn(name)) "IDCW" else if (name.contains("growth", true)) "Growth" else null).joinToString(" · ")
    }

    private val INCOME = Regex("""(?i)\b(idcw|dividend|payout|reinvest\w*|withdrawal|bonus)\b""")
    private val NOISE = setOf("fund", "plan", "direct", "growth", "regular", "option", "scheme", "the")
    private fun tokens(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    fun day(d: LocalDate): String = d.format(DAY)
    fun short(name: String): String = name.replace(Regex("""(?i)\s*[-–(]?\s*\b(direct|regular)(\s+plan)?\b\s*\)?"""), " ").replace(Regex("""(?i)\s*[-–(]?\s*\bgrowth(\s+(option|plan))?\s*\)?\s*$"""), " ")
        .replace(Regex("""\s*[-–]\s*$"""), "").replace(Regex("""\s+"""), " ").trim().trimEnd('-', '–').trim().ifEmpty { name }

    fun searchUrl(query: String) = "https://$HOST/mf/search?q=" + WebData.encode(query.trim())
    fun navUrl(code: Int, from: LocalDate, to: LocalDate) = "https://$HOST/mf/$code?startDate=$from&endDate=$to"

    fun hits(searchJson: String): List<Hit> = J.arr(searchJson).mapNotNull { o -> val c = o.d("schemeCode")?.toInt() ?: return@mapNotNull null; Hit(c, o.s("schemeName").ifEmpty { return@mapNotNull null }) }

    /**
     * Best match first. Unless the query asks otherwise, "Direct Plan - Growth" wins; then the name with the fewest
     * words the user did not type (so "nifty 50 index" beats "nifty next 50 index"); then the older scheme (longer history).
     */
    fun rank(hits: List<Hit>, query: String): List<Hit> {
        val q = tokens(query).toSet(); val wantsRegular = "regular" in q; val wantsIncome = INCOME.containsMatchIn(query)
        fun score(h: Hit): Int {
            val t = tokens(h.name); val direct = "direct" in t; val income = INCOME.containsMatchIn(h.name)
            val matched = q.count { w -> t.any { it == w } }
            val extra = t.toSet().count { it !in q && it !in NOISE }
            return matched * 3 - extra + (if (direct != wantsRegular) 8 else 0) + (if (income == wantsIncome) 6 else 0) + (if (!wantsIncome && "growth" in t) 2 else 0)
        }
        return hits.distinctBy { it.code }.sortedWith(compareByDescending<Hit> { score(it) }.thenBy { it.code })
    }

    /** Scheme JSON → history (oldest first, bad rows dropped). Null when there are fewer than two NAVs. */
    fun parse(navJson: String): Scheme? {
        val root = J.obj(navJson) ?: return null
        val meta = root.o("meta") ?: return null
        val rows = root.a("data").mapNotNull { r ->
            val day = runCatching { LocalDate.parse(r.s("date"), FEED_DAY) }.getOrNull() ?: return@mapNotNull null
            val nav = r.d("nav")?.takeIf { it > 0 && it.isFinite() } ?: return@mapNotNull null
            day to nav
        }.distinctBy { it.first }.sortedBy { it.first }
        if (rows.size < 2) return null
        return Scheme(meta.d("scheme_code")?.toInt() ?: 0, meta.s("scheme_name").ifEmpty { "Fund" }, meta.s("fund_house"), meta.s("scheme_category"), rows.map { it.first }, rows.map { it.second })
    }

    /** True when the latest NAV is too old to be a live scheme (merged or wound-up funds stay in the feed with their last NAV). */
    fun stale(s: Scheme, today: LocalDate) = ChronoUnit.DAYS.between(s.lastDate, today) > 45

    class Loaded(val scheme: Scheme?, val error: String?)

    private suspend fun get(f: Fetch, url: String) = runCatching { f.get(url) }.getOrDefault("")

    /** Finds the scheme that best matches [query] and reads its NAVs for the last [years] years. Skips dead schemes when a live one also matches. */
    suspend fun load(f: Fetch, query: String, years: Int): Loaded {
        val q = query.trim().removePrefix("@")
        if (q.isBlank()) return Loaded(null, "Which fund? Type part of its name, e.g. /fund parag flexi.")
        var found = hits(get(f, searchUrl(q)))
        val asksPlan = Regex("""(?i)\b(regular|direct)\b""").containsMatchIn(q) || INCOME.containsMatchIn(q)
        if (!asksPlan && found.none { it.name.contains("direct", true) && it.name.contains("growth", true) }) found = found + hits(get(f, searchUrl("$q direct growth")))
        val ranked = rank(found, q)
        if (ranked.isEmpty()) return Loaded(null, "No mutual fund found for “$q”. Try a shorter part of the name, e.g. /fund parag flexi.")
        val from = f.today.minusYears(years.toLong()).minusDays(20)
        var fallback: Scheme? = null
        for (h in ranked.take(4)) {
            val s = parse(get(f, navUrl(h.code, from, f.today))) ?: continue
            if (!stale(s, f.today)) return Loaded(s, null)
            if (fallback == null) fallback = s
        }
        return fallback?.let { Loaded(it, null) } ?: Loaded(null, "Couldn't read NAV history for ${ranked.first().name} from mfapi.in. Try again in a minute.")
    }

    /** About [max] points, evenly spaced, always starting on the first day asked for and ending on the latest NAV — enough for a phone-width line. */
    fun sample(s: Scheme, from: Int = 0, max: Int = 140): List<Int> {
        val n = s.dates.size - from; if (n <= max) return (from until s.dates.size).toList()
        val step = Math.ceil(n / max.toDouble()).toInt()
        return (s.dates.size - 1 downTo from step step).toList().reversed().let { if (it.first() != from) listOf(from) + it else it }
    }
}
