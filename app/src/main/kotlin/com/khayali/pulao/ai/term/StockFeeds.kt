package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.WebData
import com.khayali.pulao.ai.term.J.a
import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.o
import com.khayali.pulao.ai.term.J.s
import java.time.LocalDate
import java.util.Locale

/** What the single-stock features share: NSE feed paths, the quote parser, date and number wording. Pure. */
object StockFeeds {
    const val SOURCE = "NSE"
    private const val QUOTE_API = "NextApi/apiClient/GetQuoteApi?functionName="

    fun enc(s: String) = WebData.encode(s).replace("+", "%20")
    fun quotePath(symbol: String, series: String = "EQ") = QUOTE_API + "getSymbolData&marketType=N&series=${enc(series)}&symbol=${enc(symbol)}"
    fun metaPath(symbol: String) = QUOTE_API + "getMetaData&symbol=${enc(symbol)}"
    fun historyPath(symbol: String, from: LocalDate, to: LocalDate) = QUOTE_API + "getHistoricalTradeData&symbol=${enc(symbol)}&series=EQ&fromDate=${dmy(from)}&toDate=${dmy(to)}"
    fun holdingPath(symbol: String) = "corporate-share-holdings-master?index=equities&symbol=${enc(symbol)}"
    fun resultsPath(symbol: String) = "results-comparision?symbol=${enc(symbol)}"
    fun corpPath(symbol: String) = "top-corp-info?symbol=${enc(symbol)}&market=equities"
    fun peersPath(index: String) = "heatmap-symbols?type=Sectoral%20Indices&indices=${enc(index)}"

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private val DATE = Regex("""(\d{1,2})[- ]([A-Za-z]{3})[A-Za-z]*[- ](\d{4})""")
    private val TIME = Regex("""\b(\d{1,2}:\d{2})(:\d{2})?\b""")

    /** "30-SEP-2026", "16-Nov-2026 16:00:28" or "30 Sep 2026" → date; null for anything else. */
    fun date(text: String): LocalDate? {
        val m = DATE.find(text) ?: return null
        val month = MONTHS.indexOf(m.groupValues[2].uppercase()) + 1
        return if (month == 0) null else runCatching { LocalDate.of(m.groupValues[3].toInt(), month, m.groupValues[1].toInt()) }.getOrNull()
    }
    fun month(m: Int) = MONTHS[m - 1].let { it.take(1) + it.drop(1).lowercase() }
    /** 2026-11-16 → "16 Nov 2026". */
    fun day(d: LocalDate) = "${d.dayOfMonth} ${month(d.monthValue)} ${d.year}"
    /** 2026-11-16 → "16 Nov". */
    fun dayShort(d: LocalDate) = "${d.dayOfMonth} ${month(d.monthValue)}"
    fun monthYear(d: LocalDate) = "${month(d.monthValue)} ${d.year}"
    /** The request format of the history feed: DD-MM-YYYY. */
    fun dmy(d: LocalDate) = String.format(Locale.US, "%02d-%02d-%04d", d.dayOfMonth, d.monthValue, d.year)
    /** "16-Nov-2026 16:00:28" → "16 Nov 2026, 16:00". */
    fun stamp(text: String): String = date(text)?.let { d -> day(d) + (TIME.find(text.substringAfter(' ', ""))?.let { ", " + it.groupValues[1] } ?: "") } ?: text

    fun fix(v: Double, digits: Int = 2) = String.format(Locale.US, "%.${digits}f", v)
    /** A share of something, unsigned: 13.9 → "13.90%". */
    fun share(v: Double, digits: Int = 2) = fix(v, digits) + "%"
    /** A change in percentage points: 0.08 → "+0.08 pts". */
    fun points(v: Double) = (if (v > 0.004) "+" else if (v < -0.004) "−" else "") + fix(kotlin.math.abs(v)) + " pts"
    fun price(v: Double) = "₹" + N.num(v)
    fun count(v: Double) = N.num(v, 0)
    /** Rupees → "₹556 Cr". */
    fun crore(rupees: Double) = N.cr(rupees / 1e7)

    data class Quote(
        val symbol: String, val name: String, val last: Double, val prevClose: Double?, val change: Double?, val pChange: Double?,
        val open: Double?, val high: Double?, val low: Double?, val vwap: Double?, val volume: Double?, val value: Double?,
        val yearHigh: Double?, val yearLow: Double?, val yearHighDate: LocalDate?, val yearLowDate: LocalDate?,
        val deliveryPct: Double?, val deliveryDate: LocalDate?, val sectorIndex: String, val sectorPe: Double?, val symbolPe: Double?,
        val industry: String, val indexList: List<String>, val marketCap: Double?, val preOpen: Boolean, val asOf: String,
    ) {
        /** Where the last price sits between the 52-week low (0) and high (100); null when the range is unusable. */
        val position: Double? get() = if (yearHigh != null && yearLow != null && yearHigh > yearLow) ((last - yearLow) / (yearHigh - yearLow) * 100).coerceIn(0.0, 100.0) else null
        val hasDayRange: Boolean get() = high != null && low != null && high > low
    }

    /** Parses NSE getSymbolData. Null when the reply carries no priced equity (unknown symbol, error body, HTML). */
    fun quote(json: String): Quote? {
        val q = J.arr(json, "equityResponse").firstOrNull() ?: return null
        val meta = q.o("metaData") ?: return null
        val trade = q.o("tradeInfo"); val info = q.o("priceInfo"); val sec = q.o("secInfo")
        fun pos(v: Double?) = v?.takeIf { it > 0 }
        val last = pos(q.o("orderBook")?.d("lastPrice")) ?: pos(trade?.d("lastPrice")) ?: pos(meta.d("closePrice")) ?: return null
        return Quote(
            symbol = meta.s("symbol"), name = meta.s("companyName"), last = last, prevClose = pos(meta.d("previousClose")), change = meta.d("change"), pChange = meta.d("pChange"),
            open = pos(meta.d("open")), high = pos(meta.d("dayHigh")), low = pos(meta.d("dayLow")), vwap = pos(meta.d("averagePrice")),
            volume = pos(trade?.d("totalTradedVolume")), value = pos(trade?.d("totalTradedValue")),
            yearHigh = pos(info?.d("yearHigh")), yearLow = pos(info?.d("yearLow")), yearHighDate = info?.s("yearHightDt")?.let(::date), yearLowDate = info?.s("yearLowDt")?.let(::date),
            deliveryPct = pos(trade?.d("deliveryToTradedQuantity")), deliveryDate = trade?.s("secwisedelposdate")?.let(::date),
            sectorIndex = sec?.s("pdSectorInd").orEmpty(), sectorPe = pos(sec?.d("pdSectorPe")), symbolPe = pos(sec?.d("pdSymbolPe")),
            industry = sec?.s("basicIndustry").orEmpty().ifBlank { sec?.s("industryInfo").orEmpty() },
            indexList = sec?.get("indexList")?.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonPrimitive }?.map { it.asString }.orEmpty(),
            marketCap = pos(trade?.d("totalMarketCap")), preOpen = meta.s("symbolStatus") == "PO", asOf = q.s("lastUpdateTime"))
    }

    /** One filing of the shareholding-pattern master: percentages of the company's shares. */
    data class Holding(val date: LocalDate, val promoter: Double, val public: Double, val trusts: Double)

    /** Shareholding filings, oldest first, one per reporting date (the latest submission for a date wins). */
    fun holdings(json: String): List<Holding> = J.arr(json).mapNotNull { o ->
        Holding(date(o.s("date")) ?: return@mapNotNull null, o.d("pr_and_prgrp") ?: return@mapNotNull null, o.d("public_val") ?: return@mapNotNull null, o.d("employeeTrusts") ?: 0.0)
    }.distinctBy { it.date }.sortedBy { it.date }

    /** A row of the sector-index constituents feed. [value] is the day's traded value in rupees (0 before trading starts). */
    data class Peer(val symbol: String, val price: Double, val pChange: Double, val value: Double)
    fun peers(json: String): List<Peer> = J.arr(json).mapNotNull { o ->
        Peer(o.s("symbol").ifBlank { return@mapNotNull null }, o.d("lastPrice")?.takeIf { it > 0 } ?: return@mapNotNull null, o.d("pChange") ?: return@mapNotNull null, o.d("quantityTraded") ?: 0.0)
    }
    fun peersAsOf(json: String): String = J.arr(json).firstOrNull()?.s("lastUpdatedTime").orEmpty()

    /** Sector words → the index name the constituents feed answers to (all checked against the live feed). */
    private val SECTORS = linkedMapOf(
        "IT" to "NIFTY IT", "BANK" to "NIFTY BANK", "AUTO" to "NIFTY AUTO", "PHARMA" to "NIFTY PHARMA", "FMCG" to "NIFTY FMCG", "METAL" to "NIFTY METAL",
        "REALTY" to "NIFTY REALTY", "MEDIA" to "NIFTY MEDIA", "ENERGY" to "NIFTY ENERGY", "PSU BANK" to "NIFTY PSU BANK", "PVT BANK" to "NIFTY PVT BANK",
        "FIN SERVICE" to "NIFTY FIN SERVICE", "OIL AND GAS" to "NIFTY OIL AND GAS", "HEALTHCARE" to "NIFTY HEALTHCARE", "CONSR DURBL" to "NIFTY CONSR DURBL",
        "INFRA" to "NIFTY INFRA", "CHEMICALS" to "NIFTY CHEMICALS")
    private val ALIASES = mapOf(
        "TECH" to "IT", "SOFTWARE" to "IT", "BANKS" to "BANK", "BANKING" to "BANK", "AUTOS" to "AUTO", "AUTOMOBILE" to "AUTO", "PHARMACEUTICALS" to "PHARMA", "METALS" to "METAL",
        "REAL ESTATE" to "REALTY", "PSU BANKS" to "PSU BANK", "PSU" to "PSU BANK", "PRIVATE BANK" to "PVT BANK", "PRIVATE BANKS" to "PVT BANK",
        "FINANCIAL SERVICES" to "FIN SERVICE", "FINANCE" to "FIN SERVICE", "FINANCIALS" to "FIN SERVICE", "FINSERV" to "FIN SERVICE", "NBFC" to "FIN SERVICE",
        "OIL & GAS" to "OIL AND GAS", "OIL GAS" to "OIL AND GAS", "OIL" to "OIL AND GAS", "HEALTH" to "HEALTHCARE", "HEALTHCARE INDEX" to "HEALTHCARE",
        "CONSUMER DURABLES" to "CONSR DURBL", "DURABLES" to "CONSR DURBL", "INFRASTRUCTURE" to "INFRA", "CHEMICAL" to "CHEMICALS")
    val SECTOR_HINT = "IT, BANK, AUTO, PHARMA, FMCG, METAL, ENERGY, REALTY"

    /** "it" / "nifty bank" / "Financial Services" → "NIFTY IT" / "NIFTY BANK" / "NIFTY FIN SERVICE"; null when it is not a sector. */
    fun sectorIndex(text: String): String? {
        val a = text.trim().uppercase().replace(Regex("""\s+"""), " ").removePrefix("NIFTY ").removeSuffix(" SECTOR").trim()
        return SECTORS[a] ?: SECTORS[ALIASES[a]]
    }
    /** The sector index of a quoted stock: the one NSE compares its P/E with, else the first sector index it belongs to. */
    fun sectorIndex(q: Quote): String? = sectorIndex(q.sectorIndex) ?: q.indexList.firstNotNullOfOrNull { sectorIndex(it) }
}
