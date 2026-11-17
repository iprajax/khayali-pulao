package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.Money
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The arithmetic behind the fund and planning features. Pure: no clock, no network.
 *
 * Conventions (the ones Indian SIP / EMI calculators use, so figures match what people see elsewhere):
 * a yearly rate r% is applied as r/12 per month; a SIP instalment is paid at the START of each month;
 * an EMI or a withdrawal is paid at the END of each month.
 */
object FundsMath {
    /** Value of one series at a point in time: [x] in years from the start. */
    data class Point(val x: Double, val invested: Double, val value: Double)

    fun monthly(ratePct: Double) = ratePct / 1200.0
    fun months(years: Double) = (years * 12).roundToInt().coerceAtLeast(1)

    /** Future value of [amount] a month for [months] at [ratePct] a year (instalments at the start of each month). */
    fun sipValue(amount: Double, ratePct: Double, months: Int): Double {
        val i = monthly(ratePct)
        return if (i == 0.0) amount * months else amount * ((1 + i).pow(months) - 1) / i * (1 + i)
    }

    /** Future value of a single [amount] left for [months] at [ratePct] a year, compounded monthly. */
    fun lumpValue(amount: Double, ratePct: Double, months: Int) = amount * (1 + monthly(ratePct)).pow(months)

    /**
     * A SIP month by month, reported at the end of every year (and at the end). The instalment rises by
     * [stepUpPct] after every 12 instalments (0 = a flat SIP). The first point is the start: (0, 0, 0).
     */
    fun sipSchedule(amount: Double, ratePct: Double, months: Int, stepUpPct: Double = 0.0): List<Point> {
        val i = monthly(ratePct); var bal = 0.0; var inv = 0.0; var inst = amount
        val out = mutableListOf(Point(0.0, 0.0, 0.0))
        for (m in 1..months) {
            if (m > 1 && (m - 1) % 12 == 0) inst *= 1 + stepUpPct / 100
            inv += inst; bal = (bal + inst) * (1 + i)
            if (m % 12 == 0 || m == months) out += Point(m / 12.0, inv, bal)
        }
        return out
    }

    /** The instalment in the final year of a step-up SIP. */
    fun lastInstalment(amount: Double, stepUpPct: Double, months: Int) = amount * (1 + stepUpPct / 100).pow((months - 1) / 12)

    fun lumpSchedule(amount: Double, ratePct: Double, months: Int): List<Point> =
        (0..months).filter { it % 12 == 0 || it == months }.map { Point(it / 12.0, amount, lumpValue(amount, ratePct, it)) }

    /** Equated monthly instalment for a loan of [principal] over [months] at [ratePct] a year. */
    fun emi(principal: Double, ratePct: Double, months: Int): Double {
        val i = monthly(ratePct)
        return if (i == 0.0) principal / months else principal * i * (1 + i).pow(months) / ((1 + i).pow(months) - 1)
    }

    /** One year of a loan: what was repaid as principal and as interest in that year, and what is still owed at its end. */
    data class LoanYear(val year: Int, val principal: Double, val interest: Double, val balance: Double)

    fun loanSchedule(principal: Double, ratePct: Double, months: Int): List<LoanYear> {
        val i = monthly(ratePct); val e = emi(principal, ratePct, months); var bal = principal
        val out = mutableListOf<LoanYear>(); var p = 0.0; var n = 0.0
        for (m in 1..months) {
            val interest = bal * i; val part = if (m == months) bal else e - interest
            bal -= part; p += part; n += interest
            if (m % 12 == 0 || m == months) { out += LoanYear(ceil(m / 12.0).toInt(), p, n, if (abs(bal) < 1e-6) 0.0 else bal); p = 0.0; n = 0.0 }
        }
        return out
    }

    /**
     * Systematic withdrawal: the balance earns the monthly rate, then [withdrawal] is taken at the end of the month.
     * [lastsMonths] = how many full withdrawals the money pays for, or null when it is still positive after
     * [horizonMonths]. [balance] is reported at the end of every year (x in years), ending at 0 if the money runs out.
     */
    class Swp(val lastsMonths: Int?, val withdrawn: Double, val earned: Double, val endBalance: Double, val balance: List<Pair<Double, Double>>)

    fun swp(corpus: Double, withdrawal: Double, ratePct: Double, horizonMonths: Int = 600): Swp {
        val i = monthly(ratePct); var bal = corpus; var out = 0.0; var earned = 0.0
        val pts = mutableListOf(0.0 to corpus)
        for (m in 1..horizonMonths) {
            val interest = bal * i
            if (bal + interest < withdrawal) {            // not enough for a full withdrawal: the remainder is paid out and the money is gone
                earned += interest; out += bal + interest
                pts += m / 12.0 to 0.0
                return Swp(m - 1, out, earned, 0.0, pts)
            }
            earned += interest; bal += interest - withdrawal; out += withdrawal
            if (m % 12 == 0 || m == horizonMonths) pts += m / 12.0 to bal
        }
        return Swp(null, out, earned, bal, pts)
    }

    /**
     * Retirement arithmetic. Expenses grow with inflation until and through retirement; each retirement year's
     * spending is set aside at the start of that year and the rest of the corpus earns [returnAfterPct].
     */
    class Retirement(val expenseAtRetirement: Double, val corpus: Double, val sip: Double, val invested: Double, val path: List<Pair<Double, Double>>)

    fun retirement(expenseNow: Double, yearsToRetire: Int, yearsRetired: Int, inflationPct: Double, returnBeforePct: Double, returnAfterPct: Double): Retirement {
        val f = 1 + inflationPct / 100; val g = 1 + returnAfterPct / 100
        val monthlyThen = expenseNow * f.pow(yearsToRetire); val firstYear = monthlyThen * 12
        val q = f / g
        val corpus = if (abs(q - 1) < 1e-12) firstYear * yearsRetired else firstYear * (1 - q.pow(yearsRetired)) / (1 - q)
        val n = yearsToRetire * 12
        val sip = if (n > 0) corpus / sipValue(1.0, returnBeforePct, n) else 0.0
        val path = mutableListOf<Pair<Double, Double>>()
        if (n > 0) sipSchedule(sip, returnBeforePct, n).forEach { path += it.x to it.value } else path += 0.0 to corpus
        var bal = corpus
        for (k in 0 until yearsRetired) { bal = (bal - firstYear * f.pow(k)) * g; path += (yearsToRetire + k + 1).toDouble() to (if (abs(bal) < corpus * 1e-9) 0.0 else bal) }
        return Retirement(monthlyThen, corpus, sip, sip * n, path)
    }

    /**
     * Yearly internal rate of return of dated cash flows (money paid in is negative, money received positive),
     * in percent, by bisection. Null when the flows never change sign or no rate between −99.9% and +10,000% fits.
     */
    fun xirr(flows: List<Pair<LocalDate, Double>>): Double? {
        if (flows.size < 2 || flows.none { it.second > 0 } || flows.none { it.second < 0 }) return null
        val t0 = flows.minOf { it.first }
        val ts = flows.map { ChronoUnit.DAYS.between(t0, it.first) / 365.0 to it.second }
        fun npv(r: Double) = ts.sumOf { (t, a) -> a / (1 + r).pow(t) }
        var lo = -0.999; var hi = 100.0
        var flo = npv(lo); val fhi = npv(hi)
        if (flo.isNaN() || fhi.isNaN() || flo * fhi > 0) return null
        repeat(200) {
            val mid = (lo + hi) / 2; val fm = npv(mid)
            if (fm == 0.0) return mid * 100
            if (flo * fm < 0) hi = mid else { lo = mid; flo = fm }
        }
        return (lo + hi) / 2 * 100
    }

    /** A monthly SIP run through a fund's actual NAV history. */
    class SipReal(val instalments: Int, val invested: Double, val units: Double, val value: Double, val xirr: Double?, val first: LocalDate, val last: LocalDate,
                  /** Month by month: date, money put in so far, what the units were worth that day. */
                  val path: List<Triple<LocalDate, Double, Double>>)

    /**
     * [dates]/[navs] ascending. Buys [amount] on the same day of every month for [years] years ending at the latest NAV
     * (each purchase at the first NAV on or after its date). If the history is shorter, starts at the first NAV instead.
     * Null when there is not at least one instalment before the latest NAV.
     */
    fun sipReal(dates: List<LocalDate>, navs: List<Double>, amount: Double, years: Int): SipReal? {
        if (dates.size < 2 || dates.size != navs.size) return null
        val end = dates.last(); val start = maxOf(end.minusYears(years.toLong()), dates.first())
        var units = 0.0; var invested = 0.0; var n = 0
        val flows = mutableListOf<Pair<LocalDate, Double>>(); val path = mutableListOf<Triple<LocalDate, Double, Double>>()
        var k = 0L
        while (true) {
            val due = start.plusMonths(k++); if (!due.isBefore(end)) break
            val i = dates.binarySearch(due).let { if (it >= 0) it else -it - 1 }
            if (i >= dates.size - 1 || navs[i] <= 0) break
            units += amount / navs[i]; invested += amount; n++
            flows += dates[i] to -amount
            path += Triple(dates[i], invested, units * navs[i])
        }
        if (n == 0) return null
        val value = units * navs.last()
        path += Triple(end, invested, value)
        return SipReal(n, invested, units, value, xirr(flows + (end to value)), flows.first().first, end, path)
    }

    /** Index of the last date on or before [day], or null. */
    fun atOrBefore(dates: List<LocalDate>, day: LocalDate): Int? = dates.binarySearch(day).let { if (it >= 0) it else -it - 2 }.takeIf { it >= 0 }

    // ---------- reading "10000 12% 15y", "50 lakh 8.5 20", "₹1.5 crore" ----------

    /**
     * The numbers in a calculator command, by kind: amounts (written with ₹ or a unit such as lakh / crore / k),
     * percentages, years, and bare numbers. A bare number fills whichever slot is still missing, in the order asked.
     */
    class Args(text: String) {
        private val amounts = ArrayDeque<Double>(); private val pcts = ArrayDeque<Double>(); private val years = ArrayDeque<Double>(); private val bare = ArrayDeque<Double>()
        val isEmpty get() = amounts.isEmpty() && pcts.isEmpty() && years.isEmpty() && bare.isEmpty()

        init {
            for (m in RX.findAll(text)) {
                val rupee = m.groupValues[1].isNotEmpty(); val num = m.groupValues[2]; val unit = m.groupValues[3].lowercase()
                val n = num.replace(",", "").toDoubleOrNull() ?: continue
                when {
                    unit == "%" || unit.startsWith("p") -> pcts += n
                    unit.startsWith("y") -> years += n
                    unit.isNotEmpty() -> Money.parse("$num $unit")?.let { amounts += it }
                    rupee -> Money.parse(num)?.let { amounts += it }
                    else -> bare += n
                }
            }
        }

        fun amount(): Double? = amounts.removeFirstOrNull() ?: bare.removeFirstOrNull()
        fun pct(): Double? = pcts.removeFirstOrNull() ?: bare.removeFirstOrNull()
        fun years(): Double? = years.removeFirstOrNull() ?: bare.removeFirstOrNull()

        private companion object {
            val RX = Regex("""(?i)(₹|\brs\.?\s*|\binr\s*)?((?:\d[\d,]*)(?:\.\d+)?|\.\d+)\s*(%|percent|pct|years?|yrs?|y|lakhs?|lacs?|l|crores?|cr|k|thousand)?(?![a-z])""")
        }
    }
}
