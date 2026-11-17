package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.WebData
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** Shared by the "money flows, deals and disclosures" features: NSE's date spellings, rupee wording and a cheap way to read the head of a huge reply. Pure. */
object Flows {
    private val DATE = Regex("""(\d{1,2})[- ]([A-Za-z]{3})[- ](\d{4})""")
    private val TIME = Regex("""\b(\d{1,2}:\d{2})""")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** "16-Nov-2026", "12-Nov-2026 11:19:58", "30 Sep 2026" → date; null for anything else ("-", "", "19-SEP-17"). */
    fun date(s: String): LocalDate? = DATE.find(s)?.let { m ->
        val month = MONTHS.indexOf(m.groupValues[2].lowercase()) + 1
        if (month == 0) null else runCatching { LocalDate.of(m.groupValues[3].toInt(), month, m.groupValues[1].toInt()) }.getOrNull()
    }
    private fun mon(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    /** 2026-11-16 → "16 Nov 2026". */
    fun show(d: LocalDate) = String.format(Locale.US, "%02d %s %d", d.dayOfMonth, mon(d), d.year)
    /** 2026-11-16 → "Nov '26" (a chart label). */
    fun month(d: LocalDate) = mon(d) + " '" + String.format(Locale.US, "%02d", d.year % 100)
    /** "16-Nov-2026 15:40:15" → "16 Nov 2026 15:40"; text NSE spelt some other way is returned as it came. */
    fun stamp(s: String): String { val d = date(s) ?: return s.trim(); return show(d) + (TIME.find(s.substringAfter(DATE.find(s)!!.value))?.let { " " + it.groupValues[1] } ?: "") }

    /** Rupees in the largest sensible unit: 19053000000 → "₹1,905.30 Cr", 2632737 → "₹26.33 L", 31743 → "₹31,743". */
    fun rupees(v: Double): String {
        val a = abs(v); val sign = if (v < 0) "−" else ""
        return sign + "₹" + when { a >= 1e7 -> N.num(a / 1e7) + " Cr"; a >= 1e5 -> N.num(a / 1e5) + " L"; else -> N.num(a, 0) }
    }
    /** ₹ crore with an explicit sign: "+₹4,865 Cr" / "−₹3,519 Cr". */
    fun signedCr(v: Double) = (if (v > 0) "+" else "") + N.cr(v)
    fun count(v: Double) = N.num(v, 0)
    fun clip(s: String, n: Int): String { val t = s.replace(Regex("""\s+"""), " ").trim(); return if (t.length <= n) t else t.take(n - 1).trimEnd() + "…" }
    fun round(v: Double, digits: Int = 2): Double { val k = Math.pow(10.0, digits.toDouble()); return Math.round(v * k) / k }

    /**
     * The first [n] objects of a top-level JSON array, as a complete array — so a 2–3 MB reply is never parsed whole.
     * Text that is not an array (or has fewer entries) is returned unchanged.
     */
    fun head(json: String, n: Int): String {
        val start = json.indexOfFirst { !it.isWhitespace() }
        if (start < 0 || json[start] != '[') return json
        var depth = 0; var inString = false; var escaped = false; var seen = 0
        for (i in start until json.length) {
            val c = json[i]
            if (inString) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false; continue }
            when (c) {
                '"' -> inString = true
                '[', '{' -> depth++
                ']', '}' -> { depth--; if (depth == 1 && c == '}' && ++seen == n) return json.substring(start, i + 1) + "]" }
            }
        }
        return json
    }

    /** "Infosys Limited has informed the Exchange regarding 'X'." → "X": the filing's own words without the boilerplate lead-in. */
    fun gist(text: String): String = text.replace(Regex("""\s+"""), " ").trim()
        .replace(Regex("""^.{0,90}?\bhas (?:informed|submitted to) the Exchange (?:regarding|about|that|a copy of)?\s*""", RegexOption.IGNORE_CASE), "")
        .trim().trim('\'', '"').trimEnd('.').trim().trim('\'', '"').replaceFirstChar { it.uppercase() }

    /** The NSE symbol an argument means, or null when it is blank or unknown. */
    suspend fun symbol(arg: String, f: Fetch): String? = arg.trim().takeIf { it.isNotEmpty() }?.let { f.symbol(it) }?.uppercase()
    fun q(symbol: String) = WebData.encode(symbol)
}
