package com.khayali.pulao.ai.term

import com.google.gson.JsonObject
import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.s
import java.util.Locale

/** Shared pieces for the "market pulse" features (NSE live-analysis feeds): chart inputs and number wording. Pure. */
internal object Pulse {
    /** One bar for the "divbars" chart. [tone] = "up" | "down" | "flat" overrides colouring by sign. */
    fun bar(label: String, value: Double, tone: String? = null, note: String? = null): Map<String, Any?> =
        mapOf("label" to label, "value" to value, "tone" to tone, "note" to note)

    /** Input for the "divbars" chart: horizontal bars from a zero line, both axes named. */
    fun divbars(unit: String, xLabel: String, yLabel: String, items: List<Map<String, Any?>>, legend: List<String> = listOf("Rose", "Fell"), digits: Int? = null): Map<String, Any?> =
        mapOf("unit" to unit, "xLabel" to xLabel, "yLabel" to yLabel, "legend" to legend, "digits" to digits, "items" to items)

    fun slice(label: String, value: Double, color: String): Map<String, Any?> = mapOf("label" to label, "value" to value, "color" to color)

    /** Input for the "donut" chart. */
    fun donut(centre: String, centreLabel: String, nameLabel: String, valueLabel: String, items: List<Map<String, Any?>>, unit: String = ""): Map<String, Any?> =
        mapOf("centre" to centre, "centreLabel" to centreLabel, "nameLabel" to nameLabel, "valueLabel" to valueLabel, "unit" to unit, "items" to items)

    /** Chart height for [n] horizontal bars. */
    fun height(n: Int) = maxOf(120, 60 + n * (if (n > 14) 16 else 20))

    /** Whole number with Indian digit grouping: 1260056.4 → "12,60,056". */
    fun int(v: Double) = N.num(v, 0)
    fun fixed(v: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", v)
    /** A count of shares in Indian units: 282492773 → "28.25 Cr", 539834 → "5.40 L". */
    fun qty(v: Double) = when { v >= 1e7 -> fixed(v / 1e7, 2) + " Cr"; v >= 1e5 -> fixed(v / 1e5, 2) + " L"; v >= 1e3 -> fixed(v / 1e3, 1) + " K"; else -> fixed(v, 0) }
    /** Rupees → "₹1,10,780 Cr" (or lakh for small amounts). */
    fun rupees(v: Double) = when { v >= 1e7 -> N.cr(v / 1e7); v >= 1e5 -> "₹" + fixed(v / 1e5, 2) + " L"; else -> N.inr(v) }
    /** "NIFTY PVT BANK" → "PVT BANK"; "NIFTY 50" and "NIFTY500 HEALTH" stay as they are. */
    fun short(name: String) = name.removePrefix("NIFTY ").takeIf { it != name && it.firstOrNull()?.isLetter() == true } ?: name
    /** " as of 16-Nov-2026 16:00" for a feed timestamp ("16-Nov-2026 16:00:00"), or "" when the feed has none. */
    fun asOf(stamp: String) = stamp.trim().replace(Regex("""(\d{2}:\d{2}):\d{2}$"""), "$1").let { if (it.isEmpty()) "" else " as of $it" }
    fun stamp(json: String) = J.obj(json)?.s("timestamp").orEmpty()
    /** Percent change of a row, from the first of [keys] that holds a number. */
    fun JsonObject.pct(vararg keys: String): Double? = keys.firstNotNullOfOrNull { d(it) }
    const val DELAY = "NSE's website figures can lag the live market by a few minutes."
}
