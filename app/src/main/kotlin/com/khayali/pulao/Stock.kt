package com.khayali.pulao

import java.util.Locale
import kotlin.math.abs

/** A row read off Kite's screen. */
sealed class Stock {
    abstract val name: String
    abstract val ltp: Double

    /** Portfolio → Holdings row. */
    data class Holding(
        override val name: String,
        val qty: Double,
        val avg: Double,
        val invested: Double,
        override val ltp: Double,
    ) : Stock() {
        val current get() = qty * ltp
        val pnl get() = current - invested
        val pnlPct get() = if (invested != 0.0) pnl / invested * 100 else 0.0
    }

    /** Watchlist row (no quantity owned). */
    data class Quote(
        override val name: String,
        val exchange: String,
        override val ltp: Double,
    ) : Stock()

    /** Portfolio → Holdings summary card (Invested / Current). */
    data class Portfolio(val invested: Double, val current: Double) : Stock() {
        override val name get() = "My portfolio"
        override val ltp get() = current
        val pnl get() = current - invested
        val pnlPct get() = if (invested != 0.0) pnl / invested * 100 else 0.0
    }

    /** Same instrument (ignoring price changes)? */
    fun sameAs(other: Stock?) = other != null && other::class == this::class && other.name == name
}

/** Parses Kite's accessibility descriptions (content-desc) into [Stock]s. */
object KiteParser {
    // "INFRA, quantity 250, average price 1,200.00, P N L ..., invested money 3,00,000.00, last traded price 1,450.00, change 1.20%"
    private val HOLDING = Regex(
        """^(.+?), quantity (-?[\d,]+(?:\.\d+)?), average price ([\d,.]+),.*?invested money ([\d,.]+), last traded price ([\d,.]+)""",
        RegexOption.DOT_MATCHES_ALL,
    )

    // "JHUMRI POWER\nlast traded price\n205.29\nexchange\nNSE\nAbsolute change\n..."
    private val QUOTE = Regex(
        """^(.+?)\s*\n\s*last traded price\s*\n\s*([\d,.]+)\s*\n\s*exchange\s*\n\s*(\S+)""",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun parse(desc: CharSequence?): Stock? {
        val s = desc?.toString()?.trim() ?: return null
        HOLDING.find(s)?.let { m ->
            val (name, qty, avg, inv, ltp) = m.destructured
            return Stock.Holding(name.trim(), qty.num() ?: return null, avg.num() ?: return null,
                inv.num() ?: return null, ltp.num() ?: return null)
        }
        QUOTE.find(s)?.let { m ->
            val (name, ltp, ex) = m.destructured
            return Stock.Quote(name.trim(), ex, ltp.num() ?: return null)
        }
        return null
    }

    private val NAV_TAB = Regex("""(, tab$)|(\nTab \d+ of \d+$)|(\ntab, contains \d+ items\.\nTab \d+ of \d+$)""")

    /** Kite navigation tabs only (bottom bar, Holdings/Positions, watchlist groups). */
    fun isNavigationTab(desc: String) = NAV_TAB.containsMatchIn(desc.trim())

        private val NUMBER = Regex("""^[+-]?[\d,]+(?:\.\d+)?$""")

    /**
     * Kite's summary card exposes separate nodes in this order:
     * "Invested", "Current", "3,00,000", "3,62,500", "P&L\n+62,500\n+20.83%".
     */
    fun portfolio(descs: List<String>): Stock.Portfolio? {
        val i = descs.indexOf("Invested")
        val c = descs.indexOf("Current")
        if (i < 0 || c < 0) return null
        val nums = descs.drop(maxOf(i, c) + 1).filter { NUMBER.matches(it.trim()) }.take(2)
        if (nums.size < 2) return null
        val inv = nums[0].num() ?: return null
        val cur = nums[1].num() ?: return null
        return Stock.Portfolio(inv, cur)
    }

    private fun String.num() = trim().replace(",", "").toDoubleOrNull()
}

/** Indian-style number formatting (1,23,45,678) — done by hand so it's identical on every device. */
object Fmt {
    private fun group(intPart: String): String {
        if (intPart.length <= 3) return intPart
        val last3 = intPart.takeLast(3)
        val rest = intPart.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        return "$rest,$last3"
    }

    private fun format(v: Double, decimals: Int): String {
        val s = String.format(Locale.US, "%.${decimals}f", abs(v))
        val int = s.substringBefore('.')
        val frac = if (decimals > 0) "." + s.substringAfter('.') else ""
        return (if (v < 0 && s.any { it in '1'..'9' }) "-" else "") + group(int) + frac
    }

    private fun sign(v: Double) = if (v < 0) "−" else "+"

    fun price(v: Double): String = format(v, 2)
    fun amount(v: Double): String = format(v, 0)
    fun signed(v: Double): String = sign(v) + format(abs(v), 0)
    fun signedPrice(v: Double): String = sign(v) + format(abs(v), 2)

    /** Full (1,23,45,678) or short (1.23 Cr / 5.12 L) rupee amount. */
    fun money(v: Double, compact: Boolean): String {
        val a = abs(v)
        if (!compact || a < 1e5) return format(v, 0)
        val s = (if (a >= 1e7) String.format(Locale.US, "%.2f Cr", a / 1e7)
        else String.format(Locale.US, "%.2f L", a / 1e5)).replace(".00 ", " ")  // "5 L", not "5.00 L"
        return (if (v < 0) "-" else "") + s
    }

    fun signedMoney(v: Double, compact: Boolean) = sign(v) + money(abs(v), compact)

    /** "+10%", "−2.5%", "0%". */
    fun pct(p: Double): String {
        val a = abs(p)
        val body = if (a % 1.0 == 0.0) a.toLong().toString() else String.format(Locale.US, "%.1f", a)
        return (if (p < 0) "−" else if (p > 0) "+" else "") + body + "%"
    }

    /** Always one decimal: "+16.2%". */
    fun pct1(p: Double) = sign(p) + String.format(Locale.US, "%.1f", abs(p)) + "%"

    fun qty(v: Double): String =
        if (v % 1.0 == 0.0) format(v, 0)
        else String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
}
