package com.khayali.pulao

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/** The khayali-pulao maths beyond a single % move: goals, compounding years, regret. Pure & tested. */
object Dreams {

    /** Years to grow [now] into [goal] at [ratePct] a year. 0 if already there, null if it never gets there. */
    fun yearsToGoal(now: Double, goal: Double, ratePct: Double): Double? {
        if (now <= 0) return null
        if (goal <= now) return 0.0
        if (ratePct <= 0) return null
        return ln(goal / now) / ln(1 + ratePct / 100)
    }

    fun compound(now: Double, ratePct: Double, years: Double) = now * (1 + ratePct / 100).pow(years)

    fun doublingYears(ratePct: Double): Double? = if (ratePct <= 0) null else ln(2.0) / ln(1 + ratePct / 100)

    /** % move needed for [now] to become [goal]. */
    fun pctToGoal(now: Double, goal: Double) = if (now > 0) (goal / now - 1) * 100 else 0.0

    /** Round up to a "nice" rupee figure: 1, 1.5, 2, 2.5, 3, 4, 5, 7.5 × 10^k. */
    fun niceCeil(v: Double): Double {
        if (v <= 0) return 0.0
        val mag = 10.0.pow(floor(log10(v)))
        val steps = listOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 7.5, 10.0)
        val m = steps.first { it * mag >= v - 1e-6 }
        return m * mag
    }

    /** Goal suggestions above today's value. */
    fun goalChips(now: Double): List<Double> =
        listOf(1.25, 1.5, 2.0, 3.0, 5.0, 10.0).map { niceCeil(now * it) }.distinct().filter { it > now }

    /** "Agar tab liya hota" — what [qty] bought at [pastPrice] is worth at [ltp]. */
    data class Regret(val qty: Double, val paid: Double, val now: Double) {
        val gain get() = now - paid
        val gainPct get() = if (paid > 0) gain / paid * 100 else 0.0
    }

    fun regret(qty: Double, pastPrice: Double, ltp: Double) = Regret(qty, qty * pastPrice, qty * ltp)

    fun years(v: Double): String = when {
        v < 1.0 / 12 -> "under a month"
        v < 1 -> "${ceil(v * 12).toInt()} months"
        else -> String.format(Locale.US, "%.1f years", v).replace(".0 years", " years")
    }
}

/** Turns a gain into desi daydream units — the "pulao maths". No brands, only dreams. */
object PulaoMaths {
    data class Item(val one: String, val many: String, val emoji: String, val price: Double)

    val ITEMS = listOf(
        Item("cutting chai", "cutting chais", "☕", 15.0),
        Item("plate of biryani", "plates of biryani", "🍛", 300.0),
        Item("Ballia → Sealdah ticket on Bhrigu Express", "Ballia → Sealdah tickets on Bhrigu Express", "🚆", 1_200.0),
        Item("family trip to Ballia on Pawan Express (6 people, 3AC)", "family trips to Ballia on Pawan Express (6 people, 3AC)", "🚂", 18_000.0),
        Item("bhains for the village", "bhainses for the village", "🐃", 90_000.0),
        Item("plot in Ballia", "plots in Ballia", "🌾", 6_00_000.0),
        Item("plot in Jhumri Telaiya", "plots in Jhumri Telaiya", "🏡", 12_00_000.0),
        Item("big fat Indian wedding", "big fat Indian weddings", "💍", 50_00_000.0),
        Item("Mumbai 1BHK (Virar side)", "Mumbai 1BHKs (Virar side)", "🏢", 1_20_00_000.0),
        Item("private island (khayali)", "private islands (khayali)", "🏝️", 50_00_00_000.0),
    )

    /** Built-in items plus the user's own dream (if set), cheapest first. */
    fun items(custom: Item?) = (ITEMS + listOfNotNull(custom)).sortedBy { it.price }

    private fun count(n: Long, item: Item) = "${Fmt.amount(n.toDouble())} ${if (n == 1L) item.one else item.many}"

    /** "≈ 1 plot in Ballia 🌾  ·  1,61,383 cutting chais ☕", or null if it's less than a chai. */
    fun describe(amount: Double, custom: Item? = null): String? {
        val a = abs(amount)
        val all = items(custom)
        val base = all.first()
        if (a < base.price) return null
        val big = all.drop(1).filter { it.price <= a }.maxByOrNull { it.price }
        val parts = buildList {
            if (big != null) add("${count((a / big.price).toLong(), big)} ${big.emoji}")
            add("${count((a / base.price).toLong(), base)} ${base.emoji}")
        }
        return "≈ " + parts.joinToString("  ·  ") + if (amount < 0) " less" else ""
    }

    /** One-liner for the result in the chosen [tone]; null in SERIOUS. [pct] is profit % on cost. */
    fun quip(gain: Double, pct: Double, tone: Tone): String? = when (tone) {
        Tone.SERIOUS -> null
        Tone.LIGHT -> light(gain, pct)
        Tone.MASALA -> masala(gain, pct)
    }

    fun light(gain: Double, pct: Double): String = when {
        gain < 0 && pct <= -20 -> "A deep dip — but it's a paper loss until you sell."
        gain < 0 -> "A dip, not a verdict. Prices move both ways."
        gain < 500 -> "A small start. Small gains compound."
        pct < 5 -> "Steady — still close to fixed-deposit territory."
        pct < 15 -> "Solid — ahead of most savings options."
        pct < 30 -> "A strong move. Good time to revisit your plan, calmly."
        pct < 100 -> "An excellent run. This is where a written plan helps most."
        else -> "Doubled — remember, it's notional until you sell."
    }

    fun fdLine(beatFd: Boolean, tone: Tone): String? = when (tone) {
        Tone.SERIOUS -> null
        Tone.LIGHT -> if (beatFd) "Ahead of a fixed deposit — the extra risk has paid off so far."
            else "A fixed deposit would have done better over this period. Worth knowing."
        Tone.MASALA -> fdQuip(beatFd)
    }

    /** Masala: a gentle taunt. */
    fun masala(gain: Double, pct: Double): String = when {
        gain < 0 && pct <= -20 -> "Notional loss hai, dil pe mat lo. Dil bhi notional hai. 💔"
        gain < 0 -> "Relax — it's only a loss if you sell. Or open Kite. 🙈"
        gain < 500 -> "Itne mein ek cutting chai aur do Parle-G. Party kab hai? ☕"
        pct < 5 -> "FD wale uncle abhi bhi has rahe hain. 👴"
        pct < 15 -> "Sharma ji ke bete se thoda kam, par chalega. 📉📈"
        pct < 30 -> "Ab Ballia mein plot ki baat shuru karo. 🌾"
        pct < 100 -> "Rishtedaar ab tips maangne aayenge. Phone silent pe rakho. 📵"
        else -> "Paisa double! Warren Buffett is taking notes. (He isn't.) 🧓"
    }

    fun fdQuip(beatFd: Boolean) =
        if (beatFd) "FD wale uncle chupchaap apni passbook dekh rahe hain. 👴📒"
        else "FD wale uncle: \"Maine kaha tha na?\" 👴☝️"
}

/** Where else the same money could have gone. Rates are editable assumptions, not quotes. */
object Bench {
    data class B(val name: String, val emoji: String, val ratePct: Double, val note: String)

    fun list(
        fdRate: Double, inflation: Double,
        savings: Double = com.khayali.pulao.Rates.SAVINGS, ppf: Double = com.khayali.pulao.Rates.PPF,
        gold: Double = com.khayali.pulao.Rates.GOLD, nifty: Double = com.khayali.pulao.Rates.NIFTY,
    ) = listOf(
        B("Savings account", "🏦", savings, "Settings"),
        B("Bank FD", "🧾", fdRate, "Settings"),
        B("PPF", "📮", ppf, "Settings"),
        B("Gold", "🪙", gold, "assumed — Settings"),
        B("Nifty 50", "📈", nifty, "assumed — Settings"),
        B("Inflation", "🛒", inflation, "Settings"),
    )

    /** Compound annual growth from [start] to [end] over [years]. */
    fun cagr(start: Double, end: Double, years: Double): Double? =
        if (start <= 0 || end <= 0 || years <= 0) null else ((end / start).pow(1 / years) - 1) * 100

    /** [value] in today's money after [years] of [inflationPct]. */
    fun real(value: Double, inflationPct: Double, years: Double) = value / (1 + inflationPct / 100).pow(years)
}

enum class TaxKind { LTCG, STCG, SLAB }

/** How chatty the daydream is. */
enum class Tone { SERIOUS, LIGHT, MASALA }

/** "Book it?" — what actually reaches your bank after selling. Approximate (Zerodha delivery). */
object Costs {
    const val LTCG_EXEMPT = 1_25_000.0

    /** Delivery sell-side: STT 0.1%, NSE txn ~0.00297%, SEBI ₹10/cr, 18% GST on those two, DP ₹15.34. */
    fun sellCharges(sellValue: Double): Double {
        if (sellValue <= 0) return 0.0
        val stt = sellValue * 0.001
        val txn = sellValue * 0.0000297
        val sebi = sellValue * 10 / 1e7
        val gst = (txn + sebi) * 0.18
        return stt + txn + sebi + gst + 15.34
    }

    fun tax(gain: Double, kind: TaxKind): Double = when {
        gain <= 0 -> 0.0
        kind == TaxKind.LTCG -> (gain - LTCG_EXEMPT).coerceAtLeast(0.0) * 0.125
        kind == TaxKind.STCG -> gain * 0.20
        else -> gain * 0.30
    }

    /** Gain left after charges and tax (charges reduce the taxable gain). */
    fun inHand(gain: Double, sellValue: Double, kind: TaxKind): Double {
        val ch = sellCharges(sellValue)
        val taxable = gain - ch
        return taxable - tax(taxable, kind)
    }

    fun kindFor(heldYears: Double?, fallback: TaxKind) =
        if (heldYears == null) fallback else if (heldYears >= 1.0) TaxKind.LTCG else TaxKind.STCG
}
