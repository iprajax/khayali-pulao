package com.khayali.pulao.ai.term

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shared pieces of the global-markets pack: parallel chart fetches, international number wording, sparkline panels. */
internal object GlobalKit {
    const val SOURCE = "Yahoo Finance"
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val FULL = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    fun day(d: LocalDate): String = d.format(DAY)
    fun date(d: LocalDate): String = d.format(FULL)
    /** 7416.59 → "7,416.59" (international grouping, for dollar prices and index levels). */
    fun num(v: Double, digits: Int = 2): String = (if (v < 0) "−" else "") + String.format(Locale.US, "%,.${digits}f", kotlin.math.abs(v))
    fun usd(v: Double, digits: Int = 2) = (if (v < 0) "−$" else "$") + num(kotlin.math.abs(v), digits)
    /** A level that is itself a percentage (inflation, a yield): 2.4 → "2.4%", −5.8 → "−5.8%". No plus sign. */
    fun level(v: Double, digits: Int = 1) = num(v, digits) + "%"

    /** Daily chart JSON for several feed symbols at once, keyed by symbol ("" where the feed gave nothing). */
    suspend fun charts(f: Fetch, symbols: List<String>, range: String): Map<String, String> = coroutineScope {
        symbols.map { s -> async { s to f.chart(s, range, "1d") } }.awaitAll().toMap()
    }

    /** One instrument's latest close against the close before it, and against the first close of the fetched range. */
    class Quote(val symbol: String, val label: String, val s: Series, val scale: Double = 1.0) {
        val last = s.last * scale
        val prev = s.close[s.size - 2] * scale
        /** Change of the latest bar against the previous available daily close, in percent. */
        val day = if (prev != 0.0) (last / prev - 1) * 100 else 0.0
        /** Change from the first to the last close of the range, in percent. */
        val period = s.change()
        val date: LocalDate = s.dates.last()
        val prevDate: LocalDate = s.dates[s.size - 2]
        val high = s.close.max() * scale
        val low = s.close.min() * scale
        val highDate: LocalDate = s.dates[s.close.indexOf(s.close.max())]
        val lowDate: LocalDate = s.dates[s.close.indexOf(s.close.min())]
    }

    fun quote(charts: Map<String, String>, symbol: String, label: String, scale: Double = 1.0): Quote? =
        Series.parse(charts[symbol].orEmpty())?.let { Quote(symbol, label, it, scale) }

    /** One panel for the "sparkgrid" chart. [change] colours the tag; [changeText] replaces the default "+1.2%" wording. */
    fun panel(q: Quote, digits: Int = 2, prefix: String = "", suffix: String = "", change: Double? = q.day, changeLabel: String = "1d", changeText: String? = null, color: String? = null): Map<String, Any?> = mapOf(
        "label" to q.label, "t" to q.s.dates.map { it.toString() }, "v" to q.s.close.map { it * q.scale }, "digits" to digits, "prefix" to prefix, "suffix" to suffix,
        "change" to change, "changeText" to changeText, "changeLabel" to changeLabel, "color" to color)

    /** Height that gives each sparkgrid panel [per] px. */
    fun gridHeight(items: Int, cols: Int, per: Int) = 38 + ((items + cols - 1) / cols) * per
}
