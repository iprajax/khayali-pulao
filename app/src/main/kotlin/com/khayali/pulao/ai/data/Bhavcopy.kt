package com.khayali.pulao.ai.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * NSE's daily "bhavcopy": the closing prices of every listed security. Two formats — the original
 * (to 5 Jul 2024) and the newer exchange-wide "UDiFF" one (from 8 Jul 2024). Pure: builds the address
 * for a day and reads either format into [Bar]s. The data is fetched by each user's own device from
 * NSE's public archive and never redistributed by this project.
 */
object Bhavcopy {
    const val HOST = "nsearchives.nseindia.com"
    val NEW_FROM: LocalDate = LocalDate.of(2024, 7, 8)
    val FIRST: LocalDate = LocalDate.of(2000, 1, 3)

    /** One equity's day. [prev] is NSE's previous close, already adjusted for splits and bonuses, so close/prev is the true daily return. */
    data class Bar(val symbol: String, val series: String, val open: Double, val high: Double, val low: Double, val close: Double, val prev: Double,
                   val volume: Long, val value: Double, val trades: Long, val isin: String) {
        val ret get() = if (prev > 0) close / prev - 1 else 0.0
    }

    fun url(day: LocalDate): String {
        if (!day.isBefore(NEW_FROM)) return "https://$HOST/content/cm/BhavCopy_NSE_CM_0_0_0_${day.format(DateTimeFormatter.BASIC_ISO_DATE)}_F_0000.csv.zip"
        val mon = day.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)).uppercase()
        return "https://$HOST/content/historical/EQUITIES/${day.year}/$mon/cm${"%02d".format(day.dayOfMonth)}$mon${day.year}bhav.csv.zip"
    }

    /** Index closes with P/E, P/B and dividend yield (available from 2016). */
    fun indexUrl(day: LocalDate) = "https://$HOST/content/indices/ind_close_all_${day.format(DateTimeFormatter.ofPattern("ddMMyyyy"))}.csv"

    /** Weekdays from [from] to [to]; holidays are discovered by a missing file. */
    fun tradingDayCandidates(from: LocalDate, to: LocalDate): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.filter { it.dayOfWeek.value <= 5 }.toList()

    /** Equity series kept for analysis: normal (EQ), trade-for-trade (BE, BZ) and SME (SM, ST). Bonds, ETFs and the like are left out. */
    val EQUITY = setOf("EQ", "BE", "BZ", "SM", "ST")

    private fun split(line: String) = line.split(',').map { it.trim() }

    private val FUND_NAME = Regex("""(BEES|ETF|IETF|LIQUID|GILT|NIFTY|SENSEX|GOLD|SILVER|NV20|BANKBEES|MOM\d|CPSE|BHARAT22)""")
    /** A company's shares, not an ETF or fund unit (fund units carry an INF… ISIN; older files have no ISIN, so the name is checked too). */
    fun isCompany(symbol: String, isin: String): Boolean = when {
        isin.startsWith("INF") || isin.startsWith("IN0") -> false
        isin.startsWith("INE") -> true
        else -> !FUND_NAME.containsMatchIn(symbol.uppercase())
    }

    fun parse(csv: String): List<Bar> {
        val lines = csv.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val h = split(lines[0]).map { it.uppercase() }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> h.indexOf(n).takeIf { it >= 0 } } ?: -1
        val udiff = "TCKRSYMB" in h
        val cSym = col("TCKRSYMB", "SYMBOL"); val cSer = col("SCTYSRS", "SERIES")
        val cO = col("OPNPRIC", "OPEN"); val cH = col("HGHPRIC", "HIGH"); val cL = col("LWPRIC", "LOW"); val cC = col("CLSPRIC", "CLOSE")
        val cP = col("PRVSCLSGPRIC", "PREVCLOSE"); val cV = col("TTLTRADGVOL", "TOTTRDQTY"); val cVal = col("TTLTRFVAL", "TOTTRDVAL")
        val cT = col("TTLNBOFTXSEXCTD", "TOTALTRADES"); val cIsin = col("ISIN"); val cTp = col("FININSTRMTP")
        if (cSym < 0 || cC < 0) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val f = split(line)
            fun d(i: Int) = if (i in f.indices) f[i].toDoubleOrNull() ?: 0.0 else 0.0
            fun l(i: Int) = if (i in f.indices) f[i].toDoubleOrNull()?.toLong() ?: 0L else 0L
            val series = f.getOrElse(cSer) { "" }
            if (series !in EQUITY) return@mapNotNull null
            if (udiff && cTp >= 0 && f.getOrElse(cTp) { "" } != "STK") return@mapNotNull null
            val close = d(cC).takeIf { it > 0 } ?: return@mapNotNull null
            val isin = f.getOrElse(cIsin) { "" }
            if (!isCompany(f[cSym], isin)) return@mapNotNull null
            Bar(f[cSym], series, d(cO), d(cH), d(cL), close, d(cP), l(cV), d(cVal), l(cT), isin)
        }
    }

    /** ind_close_all: index name → (close, change %, P/E, P/B, dividend yield). */
    data class IndexClose(val name: String, val close: Double, val change: Double, val pe: Double?, val pb: Double?, val dy: Double?)
    fun parseIndices(csv: String): List<IndexClose> {
        val lines = csv.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyList()
        val h = split(lines[0]).map { it.lowercase() }
        fun col(p: String) = h.indexOfFirst { it.startsWith(p) }
        val cN = col("index name"); val cC = col("closing"); val cCh = col("change(%"); val cPe = col("p/e"); val cPb = col("p/b"); val cDy = col("div")
        return lines.drop(1).mapNotNull { line ->
            val f = split(line)
            val close = f.getOrNull(cC)?.toDoubleOrNull() ?: return@mapNotNull null
            IndexClose(f[cN], close, f.getOrNull(cCh)?.toDoubleOrNull() ?: 0.0, f.getOrNull(cPe)?.toDoubleOrNull(), f.getOrNull(cPb)?.toDoubleOrNull(), f.getOrNull(cDy)?.toDoubleOrNull())
        }
    }
}
