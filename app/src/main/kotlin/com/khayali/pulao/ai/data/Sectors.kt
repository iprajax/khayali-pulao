package com.khayali.pulao.ai.data

import java.time.LocalDate
import kotlin.math.pow

/**
 * NSE's sector indices — official closes from NSE's daily index file, members from NSE Indices'
 * published lists — and returns over any period or date range, with CAGR beyond a year.
 * Pure: the phone downloads and stores the files; this reads them.
 */
object Sectors {
    /** NSE Indices publishes each index's members as a CSV on this host. */
    const val MEMBERS_HOST = "www.niftyindices.com"

    data class Sector(val index: String, val short: String, val file: String, val words: List<String>)

    /** The sector indices, by their names in NSE's daily index file. */
    val ALL = listOf(
        Sector("Nifty Bank", "Bank", "ind_niftybanklist", listOf("bank", "banks", "banking")),
        Sector("Nifty Private Bank", "Private Bank", "ind_nifty_privatebanklist", listOf("private bank", "pvt bank", "private banks")),
        Sector("Nifty PSU Bank", "PSU Bank", "ind_niftypsubanklist", listOf("psu bank", "psu banks", "public bank", "government bank")),
        Sector("Nifty Financial Services", "Financial Services", "ind_niftyfinancelist", listOf("financial", "finance", "financial services", "fin services", "nbfc")),
        Sector("Nifty IT", "IT", "ind_niftyitlist", listOf("it", "tech", "technology", "software")),
        Sector("Nifty Auto", "Auto", "ind_niftyautolist", listOf("auto", "autos", "automobile", "automobiles")),
        Sector("Nifty Pharma", "Pharma", "ind_niftypharmalist", listOf("pharma", "pharmaceuticals", "drugs")),
        Sector("Nifty Healthcare Index", "Healthcare", "ind_niftyhealthcarelist", listOf("healthcare", "health", "hospitals")),
        Sector("Nifty FMCG", "FMCG", "ind_niftyfmcglist", listOf("fmcg", "consumer goods", "staples")),
        Sector("Nifty Consumer Durables", "Consumer Durables", "ind_niftyconsumerdurableslist", listOf("consumer durables", "durables")),
        Sector("Nifty Metal", "Metal", "ind_niftymetallist", listOf("metal", "metals", "steel", "mining")),
        Sector("Nifty Energy", "Energy", "ind_niftyenergylist", listOf("energy", "power")),
        Sector("Nifty Oil & Gas", "Oil & Gas", "ind_niftyoilgaslist", listOf("oil", "gas", "oil & gas", "oil and gas")),
        Sector("Nifty Realty", "Realty", "ind_niftyrealtylist", listOf("realty", "real estate", "property")),
        Sector("Nifty Media", "Media", "ind_niftymedialist", listOf("media", "entertainment")),
        Sector("Nifty Infrastructure", "Infrastructure", "ind_niftyinfralist", listOf("infra", "infrastructure")),
        Sector("Nifty Commodities", "Commodities", "ind_niftycommoditieslist", listOf("commodities", "commodity")),
        Sector("Nifty PSE", "PSE", "ind_niftypselist", listOf("pse", "psu", "public sector")),
    )
    const val BENCHMARK = "Nifty 50"

    fun membersUrl(s: Sector) = "https://$MEMBERS_HOST/IndexConstituent/${s.file}.csv"

    /** "psu bank" → Nifty PSU Bank; longest matching phrase wins ("private bank" over "bank"). */
    fun find(query: String): Sector? {
        val q = query.trim().lowercase().removePrefix("nifty").trim()
        if (q.isEmpty()) return null
        ALL.firstOrNull { it.short.equals(q, true) || it.index.equals(query.trim(), true) }?.let { return it }
        return ALL.flatMap { s -> s.words.filter { w -> Regex("""\b${Regex.escape(w)}\b""").containsMatchIn(q) }.map { s to it.length } }.maxByOrNull { it.second }?.first
    }

    /** Company names for linking news to tickers: NSE Indices' Nifty 500 list (name, industry, symbol). */
    const val NAMES_FILE = "ind_nifty500list"
    fun namesUrl() = "https://$MEMBERS_HOST/IndexConstituent/$NAMES_FILE.csv"
    /** NSE's list of every main-board equity (symbol, name of company, …). */
    fun equityListUrl() = "https://${Bhavcopy.HOST}/content/equities/EQUITY_L.csv"

    /** symbol → company name, from NSE Indices' lists ("Company Name","Symbol") or NSE's EQUITY_L ("SYMBOL","NAME OF COMPANY"). */
    fun parseNames(csv: String): Map<String, String> {
        val lines = csv.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyMap()
        val h = lines[0].split(',').map { it.trim().lowercase() }
        val cs = h.indexOf("symbol"); val cn = h.indexOfFirst { it == "company name" || it == "name of company" }
        if (cs < 0 || cn < 0) return emptyMap()
        return lines.drop(1).mapNotNull { l -> val f = l.split(','); val s = f.getOrNull(cs)?.trim(); val n = f.getOrNull(cn)?.trim()
            if (s.isNullOrEmpty() || n.isNullOrEmpty()) null else s to n }.toMap()
    }

    /** Members from an NSE Indices CSV (Company Name, Industry, Symbol, Series, ISIN). */
    fun parseMembers(csv: String): List<String> {
        val lines = csv.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val h = lines[0].split(',').map { it.trim().lowercase() }
        val c = h.indexOf("symbol"); if (c < 0) return emptyList()
        return lines.drop(1).mapNotNull { it.split(',').getOrNull(c)?.trim()?.takeIf { s -> s.isNotEmpty() } }
    }

    // ---------------------------------------------------------------- periods

    enum class Period(val label: String, val years: Double?) {
        D1("1D", null), W1("1W", null), M1("1M", null), M3("3M", null), M6("6M", null), YTD("YTD", null), Y1("1Y", 1.0), Y3("3Y", 3.0), Y5("5Y", 5.0);
        fun start(asOf: LocalDate): LocalDate = when (this) {
            D1 -> asOf.minusDays(1); W1 -> asOf.minusWeeks(1); M1 -> asOf.minusMonths(1); M3 -> asOf.minusMonths(3); M6 -> asOf.minusMonths(6)
            YTD -> LocalDate.of(asOf.year - 1, 12, 31); Y1 -> asOf.minusYears(1); Y3 -> asOf.minusYears(3); Y5 -> asOf.minusYears(5)
        }
    }

    fun period(arg: String): Period? = when (arg.trim().lowercase()) {
        "1d", "d", "day", "today" -> Period.D1; "1w", "5d", "w", "week" -> Period.W1; "1m", "m", "month" -> Period.M1
        "3m", "quarter" -> Period.M3; "6m" -> Period.M6; "ytd" -> Period.YTD; "1y", "y", "year" -> Period.Y1; "3y" -> Period.Y3; "5y" -> Period.Y5
        else -> null
    }

    /** "2025-01-01 to 2025-12-31", "2025-01-01..2025-12-31" or "from 2025-04-01" (to the latest day). */
    fun range(arg: String): Pair<LocalDate, LocalDate?>? {
        val d = Regex("""\d{4}-\d{2}-\d{2}""").findAll(arg).map { runCatching { LocalDate.parse(it.value) }.getOrNull() }.filterNotNull().toList()
        return when (d.size) { 0 -> null; 1 -> d[0] to null; else -> minOf(d[0], d[1]) to maxOf(d[0], d[1]) }
    }

    /** A series of (date, close), oldest first. */
    data class Series(val points: List<Pair<LocalDate, Double>>) {
        val last get() = points.lastOrNull()
        /** The close on [d] or the last one before it. */
        fun at(d: LocalDate): Pair<LocalDate, Double>? {
            var lo = 0; var hi = points.size - 1; var ans: Pair<LocalDate, Double>? = null
            while (lo <= hi) { val m = (lo + hi) / 2; if (points[m].first <= d) { ans = points[m]; lo = m + 1 } else hi = m - 1 }
            return ans
        }
        /** Change from [from] to [to] (inclusive ends, nearest earlier closes); null when history doesn't reach. */
        fun change(from: LocalDate, to: LocalDate): Double? {
            // a history that starts within a week of [from] counts from its first close (a "5 years" download starts on the nearest trading day)
            val first = points.firstOrNull() ?: return null
            val a = at(from) ?: first.takeIf { !it.first.isAfter(from.plusDays(7)) } ?: return null
            val b = at(to) ?: return null
            if (a.second <= 0 || !b.first.isAfter(a.first)) return null
            return b.second / a.second - 1
        }
        /** The period's change; 1D is the last close against the one before it. */
        fun change(p: Period, asOf: LocalDate = last!!.first): Double? {
            if (p == Period.D1) { val i = points.indexOfLast { it.first <= asOf }; return if (i < 1) null else points[i].second / points[i - 1].second - 1 }
            return change(p.start(asOf), asOf)
        }
        /** Yearly rate for periods of more than a year. */
        fun cagr(p: Period, asOf: LocalDate = last!!.first): Double? = p.years?.takeIf { it > 1 }?.let { y -> change(p, asOf)?.let { (1 + it).pow(1 / y) - 1 } }
    }

    data class Row(val sector: Sector, val close: Double, val asOf: LocalDate, val changes: Map<Period, Double?>, val cagr3: Double?, val cagr5: Double?,
                   val pe: Double?, val pb: Double?, val dy: Double?, val range: Double? = null)

    /** Every sector with all periods (and an optional custom range), from their index series. */
    fun board(series: Map<String, Series>, meta: Map<String, Triple<Double?, Double?, Double?>>, range: Pair<LocalDate, LocalDate?>? = null): List<Row> = ALL.mapNotNull { s ->
        val ser = series[s.index]?.takeIf { it.points.size >= 2 } ?: return@mapNotNull null
        val (d, c) = ser.last!!
        val m = meta[s.index]
        Row(s, c, d, Period.entries.associateWith { ser.change(it, d) }, ser.cagr(Period.Y3, d), ser.cagr(Period.Y5, d), m?.first, m?.second, m?.third,
            range?.let { (a, b) -> ser.change(a, b ?: d) })
    }
}
