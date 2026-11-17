package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.FundsText.money
import com.khayali.pulao.ai.term.FundsText.rate
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Shared input checks and wording for the calculators. All of them work offline. */
internal object FundsCalc {
    const val SOURCE = "Calculation on your inputs"
    const val CAVEAT = "This is arithmetic on a constant assumed rate, not a forecast: real returns change from year to year and can be negative."
    fun badAmount(v: Double) = v <= 0 || v > 1e12 || !v.isFinite()
    fun badRate(v: Double) = v < 0 || v > 50 || !v.isFinite()
    fun badYears(v: Double) = v <= 0 || v > 60 || !v.isFinite()
    fun plain(v: Double) = N.num(v, 0).replace(",", "")
    fun yrs(months: Int) = if (months % 12 == 0) "${months / 12}y" else String.format(java.util.Locale.US, "%.1fy", months / 12.0)
    fun span(months: Int) = FundsText.years(months)
    fun example(given: Boolean, usage: String) = if (given) "" else "No figures were given, so this is an example: $usage. "
    fun assumed(items: List<String>) = if (items.isEmpty()) "" else "Assumed ${items.joinToString(" and ")} because the command did not say. "
    fun area(points: List<FundsMath.Point>) = points.map { mapOf("x" to it.x, "a" to it.invested.roundToLong(), "b" to (it.value - it.invested).roundToLong().coerceAtLeast(0)) }
    fun line(points: List<Pair<Double, Double>>) = points.map { listOf(it.first, it.second.roundToLong()) }
}

/** /sip AMOUNT RATE% YEARSy — what a monthly SIP grows to at a constant assumed return. */
object FundsSipFeature : Feature {
    override val id = "sip"
    override val usage = "/sip 10000 12% 15y"
    override val help = "SIP calculator: monthly amount, yearly return and years → corpus, invested and growth"
    override val group = Group.TOOLS
    override val needsWeb = false
    override val triggers = listOf(Regex("""(?i)(?<!step.up )(?<!stepup )\bsip (calculator|calc)\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(arg)

    fun build(arg: String): String {
        val a = FundsMath.Args(arg); val given = !a.isEmpty; val miss = mutableListOf<String>()
        val amount = a.amount() ?: 10000.0
        val r = a.pct() ?: 12.0.also { if (given) miss += "a 12% yearly return" }
        val y = a.years() ?: (if (given) 10.0 else 15.0).also { if (given) miss += "10 years" }
        if (FundsCalc.badAmount(amount) || FundsCalc.badRate(r) || FundsCalc.badYears(y)) return Card.error("Use a monthly amount, a yearly return up to 50% and up to 60 years, e.g. $usage.")
        val n = FundsMath.months(y); val sched = FundsMath.sipSchedule(amount, r, n)
        val value = sched.last().value; val invested = sched.last().invested; val growth = value - invested
        return Card("SIP calculator · ${N.inr(amount)} a month · ${rate(r)} a year · ${FundsCalc.span(n)}", "green")
            .headline(N.inr(value), "Invested ${N.inr(invested)} · growth ${N.inr(growth)} · ${N.num(value / invested, 2)}× the money put in", "up")
            .viz("stackarea", 230, mapOf("series" to listOf("Invested", "Growth"), "xLabel" to "Years from start", "yLabel" to "Value (₹)", "points" to FundsCalc.area(sched)),
                "Money put in (blue) and growth on it (green), in ₹, by year. Tap or drag to read a year.")
            .stats("Monthly SIP" to N.inr(amount), "Assumed return" to rate(r) + " a year", "Period" to FundsCalc.span(n), "Invested" to N.inr(invested), "Growth" to N.inr(growth), "Corpus" to N.inr(value))
            .note(FundsCalc.example(given, usage) + FundsCalc.assumed(miss) + "Instalments are paid at the start of each month and the yearly rate is applied monthly (rate ÷ 12). " + FundsCalc.CAVEAT)
            .sources(FundsCalc.SOURCE)
            .pills("/stepup ${FundsCalc.plain(amount)} 10% ${rate(r)} ${FundsCalc.yrs(n)}", "/lumpsum ${FundsCalc.plain(invested)} ${rate(r)} ${FundsCalc.yrs(n)}", "/sipreal parag flexi ${FundsCalc.plain(amount)} 5y", "/retire 50000 25y")
            .put("corpus", value.roundToLong()).put("invested", invested.roundToLong())
            .json("A SIP of ${N.inr(amount)} a month for ${FundsCalc.span(n)} at an assumed ${rate(r)} a year grows to ${N.inr(value)}: ${N.inr(invested)} invested and ${N.inr(growth)} growth. Illustrative, not a forecast. Source: calculation on the inputs given.")
    }
}

/** /stepup AMOUNT STEP% RATE% YEARSy — a SIP that rises every year, against the same SIP kept flat. */
object FundsStepUpFeature : Feature {
    override val id = "stepup"
    override val usage = "/stepup 10000 10% 12% 15y"
    override val help = "Step-up SIP: a SIP raised by X% every year, compared with keeping it flat"
    override val group = Group.TOOLS
    override val needsWeb = false
    override val triggers = listOf(Regex("""(?i)\b(step[- ]?up sip|sip step[- ]?up)\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(arg)

    fun build(arg: String): String {
        val a = FundsMath.Args(arg); val given = !a.isEmpty; val miss = mutableListOf<String>()
        val amount = a.amount() ?: 10000.0
        val step = a.pct() ?: 10.0.also { if (given) miss += "a 10% yearly step-up" }
        val r = a.pct() ?: 12.0.also { if (given) miss += "a 12% yearly return" }
        val y = a.years() ?: (if (given) 10.0 else 15.0).also { if (given) miss += "10 years" }
        if (FundsCalc.badAmount(amount) || FundsCalc.badRate(r) || step < 0 || step > 100 || FundsCalc.badYears(y)) return Card.error("Use a monthly amount, a yearly step-up up to 100%, a return up to 50% and up to 60 years, e.g. $usage.")
        val n = FundsMath.months(y)
        val up = FundsMath.sipSchedule(amount, r, n, step); val flat = FundsMath.sipSchedule(amount, r, n)
        val v = up.last().value; val inv = up.last().invested; val fv = flat.last().value; val finv = flat.last().invested
        val lastInst = FundsMath.lastInstalment(amount, step, n)
        return Card("Step-up SIP · ${N.inr(amount)} a month, +${rate(step)} a year · ${rate(r)} return · ${FundsCalc.span(n)}", "green")
            .headline(N.inr(v), "against ${N.inr(fv)} with a flat SIP · ${N.inr(v - fv)} more, from ${N.inr(inv - finv)} more invested", "up")
            .viz("balance", 220, mapOf("x" to "year", "unit" to "inr", "xLabel" to "Years from start", "yLabel" to "Corpus (₹)",
                "series" to listOf(mapOf("name" to "Step-up SIP", "color" to "green", "points" to FundsCalc.line(up.map { it.x to it.value })),
                    mapOf("name" to "Flat SIP", "color" to "blue", "points" to FundsCalc.line(flat.map { it.x to it.value })))),
                "Corpus in ₹ by year: the SIP raised ${rate(step)} every year against the same SIP kept flat. Tap or drag to read values.")
            .viz("stackarea", 200, mapOf("series" to listOf("Invested", "Growth"), "xLabel" to "Years from start", "yLabel" to "Value (₹)", "points" to FundsCalc.area(up)),
                "Step-up SIP only: money put in (blue) and growth on it (green), in ₹, by year.")
            .stats("Step-up: invested" to N.inr(inv), "Step-up: corpus" to N.inr(v), "Flat: invested" to N.inr(finv), "Flat: corpus" to N.inr(fv), "SIP in the last year" to N.inr(lastInst) + " a month", "Assumed return" to rate(r) + " a year")
            .note(FundsCalc.example(given, usage) + FundsCalc.assumed(miss) + "The instalment rises once every 12 months; most of the extra corpus is extra money put in. " + FundsCalc.CAVEAT)
            .sources(FundsCalc.SOURCE)
            .pills("/sip ${FundsCalc.plain(amount)} ${rate(r)} ${FundsCalc.yrs(n)}", "/lumpsum ${FundsCalc.plain(inv)} ${rate(r)} ${FundsCalc.yrs(n)}", "/retire 50000 25y")
            .put("corpus", v.roundToLong()).put("flat_corpus", fv.roundToLong())
            .json("A SIP of ${N.inr(amount)} a month raised ${rate(step)} every year for ${FundsCalc.span(n)} at an assumed ${rate(r)} a year grows to ${N.inr(v)} on ${N.inr(inv)} invested; kept flat it grows to ${N.inr(fv)} on ${N.inr(finv)} invested. Illustrative, not a forecast. Source: calculation on the inputs given.")
    }
}

/** /swp CORPUS WITHDRAWAL RATE% — how long a corpus lasts under a fixed monthly withdrawal. */
object FundsSwpFeature : Feature {
    override val id = "swp"
    override val usage = "/swp 1 crore 80000 8%"
    override val help = "Systematic withdrawal: how long a corpus lasts under a fixed monthly withdrawal"
    override val group = Group.TOOLS
    override val needsWeb = false
    override val triggers = listOf(Regex("""(?i)\b(swp|systematic withdrawal)\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(arg)

    fun build(arg: String): String {
        val a = FundsMath.Args(arg); val given = !a.isEmpty; val miss = mutableListOf<String>()
        val corpus = a.amount() ?: 1e7
        val w = a.amount() ?: (if (given) return Card.error("How much is withdrawn each month? Try $usage.") else 80000.0)
        val r = a.pct() ?: 8.0.also { if (given) miss += "an 8% yearly return" }
        if (FundsCalc.badAmount(corpus) || FundsCalc.badAmount(w) || FundsCalc.badRate(r)) return Card.error("Use a corpus, a monthly withdrawal and a yearly return up to 50%, e.g. $usage.")
        if (w > corpus) return Card.error("The monthly withdrawal (${N.inr(w)}) is larger than the corpus (${N.inr(corpus)}). Try $usage.")
        val s = FundsMath.swp(corpus, w, r); val covered = corpus * FundsMath.monthly(r)
        val lasts = s.lastsMonths
        val view = if (lasts != null) s.balance else s.balance.filter { it.first <= 30.0 }            // when it never runs out, 30 years is enough to show the shape
        val kicker = "Systematic withdrawal · ${N.inr(w)} a month from ${money(corpus)} · ${rate(r)} a year"
        val chart = mapOf("x" to "year", "unit" to "inr", "xLabel" to "Years from start", "yLabel" to "Balance (₹)",
            "series" to listOf(mapOf("name" to "Balance", "color" to (if (lasts != null) "saffron" else "green"), "points" to FundsCalc.line(view))),
            "marker" to (if (lasts != null) mapOf("value" to 0, "label" to "Runs out: " + FundsCalc.span(lasts)) else mapOf("value" to corpus.roundToLong(), "label" to "Starting corpus")))
        val card = if (lasts != null)
            Card(kicker, "saffron").headline("Lasts " + FundsCalc.span(lasts), "${lasts} full withdrawals · ${N.inr(s.withdrawn)} paid out in all, of which ${N.inr(s.earned)} is return earned along the way", "")
                .viz("balance", 220, chart, "Balance left in ₹ by year, down to zero. Tap or drag to read a year.")
        else
            Card(kicker, "green").headline("Does not run out", "The assumed return (${N.inr(covered)} in the first month) covers the ${N.inr(w)} withdrawal · balance after 30 years ${N.inr(view.last().second)}", "up")
                .viz("balance", 220, chart, "Balance in ₹ by year over 30 years, against the starting corpus. Tap or drag to read a year.")
        return card
            .stats("Corpus" to N.inr(corpus), "Monthly withdrawal" to N.inr(w), "Withdrawn a year" to rate(w * 12 / corpus * 100) + " of corpus", "Assumed return" to rate(r) + " a year",
                "Return in month 1" to N.inr(covered), if (lasts != null) "Total paid out" to N.inr(s.withdrawn) else "Balance after 30 years" to N.inr(view.last().second))
            .note(FundsCalc.example(given, usage) + FundsCalc.assumed(miss) + "The balance earns the monthly rate and the withdrawal is taken at the end of each month; the withdrawal stays fixed and is not raised for inflation, and tax on withdrawals is left out. " + FundsCalc.CAVEAT)
            .sources(FundsCalc.SOURCE)
            .pills("/retire ${FundsCalc.plain(w)} 20y 25y", "/swp ${FundsCalc.plain(corpus)} ${FundsCalc.plain(w)} ${rate(if (r >= 2) r - 2 else r + 2)}", "/sip 10000 12% 15y")
            .put("lasts_months", lasts)
            .json(if (lasts != null) "Withdrawing ${N.inr(w)} a month from ${N.inr(corpus)} at an assumed ${rate(r)} a year lasts ${FundsCalc.span(lasts)} (${lasts} full withdrawals, ${N.inr(s.withdrawn)} paid out in all). Illustrative, not a forecast. Source: calculation on the inputs given."
                else "Withdrawing ${N.inr(w)} a month from ${N.inr(corpus)} at an assumed ${rate(r)} a year does not run the money out: the first month's return is ${N.inr(covered)} and the balance after 30 years is ${N.inr(view.last().second)}. Illustrative, not a forecast. Source: calculation on the inputs given.")
    }
}

/** /emi AMOUNT RATE% YEARSy — loan instalment, total interest and how each year's payments split. */
object FundsEmiFeature : Feature {
    override val id = "emi"
    override val usage = "/emi 50 lakh 8.5% 20y"
    override val help = "Loan EMI: amount, rate and years → EMI, total interest and the yearly principal-interest split"
    override val group = Group.TOOLS
    override val needsWeb = false
    override val triggers = listOf(Regex("""(?i)\b(emi calculator|loan emi|emi (on|for) a)\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(arg)

    fun build(arg: String): String {
        val a = FundsMath.Args(arg); val given = !a.isEmpty; val miss = mutableListOf<String>()
        val p = a.amount() ?: 5e6
        val r = a.pct() ?: 8.5.also { if (given) miss += "8.5% yearly interest" }
        val y = a.years() ?: 20.0.also { if (given) miss += "20 years" }
        if (FundsCalc.badAmount(p) || FundsCalc.badRate(r) || y <= 0 || y > 40) return Card.error("Use a loan amount, a yearly rate up to 50% and up to 40 years, e.g. $usage.")
        val n = FundsMath.months(y); val emi = FundsMath.emi(p, r, n); val sched = FundsMath.loanSchedule(p, r, n)
        val interest = sched.sumOf { it.interest }; val total = p + interest
        val cross = sched.firstOrNull { it.principal > it.interest }?.year
        val half = sched.firstOrNull { it.balance <= p / 2 }?.year
        return Card("Loan EMI · ${money(p)} · ${rate(r)} a year · ${FundsCalc.span(n)}", "blue")
            .headline(N.inr(emi) + " a month", "Total interest ${N.inr(interest)} · total repaid ${N.inr(total)} · interest is ${rate(interest / p * 100)} of the loan", "")
            .viz("stackarea", 220, mapOf("series" to listOf("Principal", "Interest"), "colors" to listOf("blue", "saffron"), "step" to true, "xLabel" to "Year of the loan", "yLabel" to "Paid in the year (₹)",
                "points" to sched.map { mapOf("x" to it.year, "a" to it.principal.roundToLong(), "b" to it.interest.roundToLong()) }),
                "Each year's payments in ₹, split into principal repaid (blue) and interest (saffron). Tap a year.")
            .viz("balance", 190, mapOf("x" to "year", "unit" to "inr", "xLabel" to "Years from start", "yLabel" to "Loan outstanding (₹)",
                "series" to listOf(mapOf("name" to "Outstanding", "color" to "red", "points" to FundsCalc.line(listOf(0.0 to p) + sched.mapIndexed { i, l -> (if (i == sched.size - 1) n / 12.0 else l.year.toDouble()) to l.balance }))),
                "marker" to mapOf("value" to 0, "label" to "Paid off")),
                "Loan still owed in ₹ at the end of each year. Tap or drag to read a year.")
            .stats(*listOfNotNull("EMI" to N.inr(emi), "Total interest" to N.inr(interest), "Total repaid" to N.inr(total), "Interest in year 1" to N.inr(sched.first().interest),
                cross?.let { "Principal overtakes interest" to "year $it" }, half?.let { "Half the loan repaid" to "by end of year $it" }).toTypedArray())
            .note(FundsCalc.example(given, usage) + FundsCalc.assumed(miss) + "Assumes a fixed rate for the whole term, monthly rests and no prepayment. Floating-rate loans change with the lender's rate; fees and insurance are left out.")
            .sources(FundsCalc.SOURCE)
            .pills("/emi ${FundsCalc.plain(p)} ${rate(r)} ${FundsCalc.yrs((n - 60).coerceAtLeast(12))}", "/emi ${FundsCalc.plain(p)} ${rate((r - 0.5).coerceAtLeast(0.0))} ${FundsCalc.yrs(n)}", "/sip ${FundsCalc.plain(Math.round(emi / 1000.0) * 1000.0)} 12% ${FundsCalc.yrs(n)}")
            .put("emi", emi.roundToLong()).put("total_interest", interest.roundToLong())
            .json("A loan of ${N.inr(p)} at ${rate(r)} a year for ${FundsCalc.span(n)} has an EMI of ${N.inr(emi)}; total interest is ${N.inr(interest)} and total repaid ${N.inr(total)}. Fixed-rate arithmetic. Source: calculation on the inputs given.")
    }
}

/** /retire EXPENSES TO_RETIRE y IN_RETIREMENT y [inflation% return% return-after%] — corpus needed and the SIP that reaches it. */
object FundsRetireFeature : Feature {
    override val id = "retire"
    override val usage = "/retire 50000 25y 25y 6% 12% 8%"
    override val help = "Retirement corpus: expenses today, years to and in retirement → corpus and the SIP needed"
    override val group = Group.TOOLS
    override val needsWeb = false
    override val triggers = listOf(Regex("""(?i)\bretirement (corpus|calculator|plan(ner|ning)?)\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(arg)

    fun build(arg: String): String {
        val a = FundsMath.Args(arg); val given = !a.isEmpty; val miss = mutableListOf<String>()
        val e = a.amount() ?: 50000.0
        val to = (a.years() ?: 25.0.also { if (given) miss += "25 years to retirement" }).roundToInt()
        val inRet = (a.years() ?: 25.0.also { if (given) miss += "25 years in retirement" }).roundToInt()
        val infl = a.pct() ?: 6.0.also { if (given) miss += "6% inflation" }
        val before = a.pct() ?: 12.0.also { if (given) miss += "a 12% return before retirement" }
        val after = a.pct() ?: 8.0.also { if (given) miss += "an 8% return after it" }
        if (FundsCalc.badAmount(e) || to < 0 || to > 50 || inRet < 1 || inRet > 50 || infl < 0 || infl > 20 || FundsCalc.badRate(before) || FundsCalc.badRate(after))
            return Card.error("Use monthly expenses, up to 50 years to retirement, 1–50 years in retirement, inflation up to 20% and returns up to 50%, e.g. $usage.")
        val r = FundsMath.retirement(e, to, inRet, infl, before, after)
        val sub = if (to > 0) "needed in $to years · SIP of ${N.inr(r.sip)} a month from now gets there at ${rate(before)} a year" else "needed now to fund $inRet years of spending"
        return Card("Retirement corpus · ${N.inr(e)} a month today · $to years away · $inRet years retired", "violet")
            .headline(money(r.corpus), sub, "")
            .viz("balance", 230, mapOf("x" to "year", "unit" to "inr", "xLabel" to "Years from today", "yLabel" to "Corpus (₹)",
                "series" to listOf(mapOf("name" to "Corpus", "color" to "violet", "points" to FundsCalc.line(r.path))),
                "marker" to mapOf("value" to r.corpus.roundToLong(), "label" to "Needed " + money(r.corpus)),
                "marks" to (if (to > 0) listOf(mapOf("x" to to, "label" to "Retire")) else emptyList())),
                "Corpus in ₹ by year: built up by the SIP until retirement, then drawn down by inflation-adjusted spending to zero. Tap or drag to read a year.")
            .stats(*listOfNotNull("Expenses today" to N.inr(e) + " a month", "Expenses at retirement" to N.inr(r.expenseAtRetirement) + " a month", "Corpus needed" to N.inr(r.corpus),
                if (to > 0) "SIP needed" to N.inr(r.sip) + " a month" else null, if (to > 0) "Total put in by SIP" to N.inr(r.invested) else null,
                "Inflation · return before · after" to "${rate(infl)} · ${rate(before)} · ${rate(after)}").toTypedArray())
            .note(FundsCalc.example(given, usage) + FundsCalc.assumed(miss) + "Spending rises with inflation every year; each retirement year's spending is set aside at the start of that year and the rest earns the after-retirement return, reaching zero at the end. " +
                "Existing savings, pensions, EPF/NPS and tax are not counted. Small changes in the assumed rates move the answer a lot. " + FundsCalc.CAVEAT)
            .sources(FundsCalc.SOURCE)
            .pills(*listOfNotNull(if (to > 0) "/sip ${FundsCalc.plain(Math.ceil(r.sip / 500) * 500)} ${rate(before)} ${to}y" else null, "/swp ${FundsCalc.plain(Math.round(r.corpus / 1e5) * 1e5)} ${FundsCalc.plain(Math.round(r.expenseAtRetirement / 1000) * 1000.0)} ${rate(after)}",
                if (to > 0) "/stepup ${FundsCalc.plain(Math.ceil(r.sip / 2 / 500) * 500)} 10% ${rate(before)} ${to}y" else null).toTypedArray())
            .put("corpus", r.corpus.roundToLong()).put("sip", r.sip.roundToLong())
            .json("Spending of ${N.inr(e)} a month today becomes ${N.inr(r.expenseAtRetirement)} a month in $to years at ${rate(infl)} inflation; funding $inRet years of it needs a corpus of ${N.inr(r.corpus)} at an assumed ${rate(after)} return in retirement" +
                (if (to > 0) ", which a SIP of ${N.inr(r.sip)} a month reaches at an assumed ${rate(before)} a year" else "") + ". Illustrative, not a forecast. Source: calculation on the inputs given.")
    }
}

/** /lumpsum TOTAL RATE% YEARSy — the same total invested at once, or spread as a monthly SIP. */
object FundsLumpsumFeature : Feature {
    override val id = "lumpsum"
    override val usage = "/lumpsum 12 lakh 12% 10y"
    override val help = "Lump sum vs SIP: the same total invested at once or monthly, at a given return"
    override val group = Group.TOOLS
    override val needsWeb = false
    override val triggers = listOf(Regex("""(?i)\blump ?sum (vs\.?|or|versus) (a )?sip\b|\bsip (vs\.?|or|versus) (a )?lump ?sum\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(arg)

    fun build(arg: String): String {
        val a = FundsMath.Args(arg); val given = !a.isEmpty; val miss = mutableListOf<String>()
        val total = a.amount() ?: 1.2e6
        val r = a.pct() ?: 12.0.also { if (given) miss += "a 12% yearly return" }
        val y = a.years() ?: 10.0.also { if (given) miss += "10 years" }
        if (FundsCalc.badAmount(total) || FundsCalc.badRate(r) || FundsCalc.badYears(y)) return Card.error("Use a total amount, a yearly return up to 50% and up to 60 years, e.g. $usage.")
        val n = FundsMath.months(y); val inst = total / n
        val lump = FundsMath.lumpSchedule(total, r, n); val sip = FundsMath.sipSchedule(inst, r, n)
        val lv = lump.last().value; val sv = sip.last().value
        return Card("Lump sum vs SIP · ${money(total)} in all · ${rate(r)} a year · ${FundsCalc.span(n)}", "blue")
            .headline("${money(lv)} vs ${money(sv)}", "Lump sum ${N.inr(lv)} · SIP of ${N.inr(inst)} a month ${N.inr(sv)} · difference ${N.inr(lv - sv)}", "")
            .viz("balance", 230, mapOf("x" to "year", "unit" to "inr", "xLabel" to "Years from start", "yLabel" to "Value (₹)",
                "series" to listOf(mapOf("name" to "Lump sum", "color" to "blue", "points" to FundsCalc.line(lump.map { it.x to it.value })),
                    mapOf("name" to "Monthly SIP", "color" to "green", "points" to FundsCalc.line(sip.map { it.x to it.value }))),
                "marker" to mapOf("value" to total.roundToLong(), "label" to "Total invested " + money(total))),
                "Value in ₹ by year of the same total invested at once (blue) or month by month (green). Tap or drag to read values.")
            .stats("Total invested" to N.inr(total), "SIP instalment" to N.inr(inst) + " a month", "Lump sum grows to" to N.inr(lv), "SIP grows to" to N.inr(sv),
                "Lump sum multiple" to N.num(lv / total, 2) + "×", "SIP multiple" to N.num(sv / total, 2) + "×")
            .note(FundsCalc.example(given, usage) + FundsCalc.assumed(miss) + "At a constant positive rate the lump sum always ends higher, simply because all the money is invested for the whole period; the SIP figure counts nothing earned on money waiting to be invested. " +
                "Actual markets rise and fall unevenly, which changes the outcome either way and which this arithmetic does not show. Both use the yearly rate applied monthly.")
            .sources(FundsCalc.SOURCE)
            .pills("/sip ${FundsCalc.plain(inst)} ${rate(r)} ${FundsCalc.yrs(n)}", "/stepup ${FundsCalc.plain(inst)} 10% ${rate(r)} ${FundsCalc.yrs(n)}", "/sipreal parag flexi ${FundsCalc.plain(inst)} 5y")
            .put("lump_value", lv.roundToLong()).put("sip_value", sv.roundToLong())
            .json("${N.inr(total)} invested at once for ${FundsCalc.span(n)} at an assumed ${rate(r)} a year grows to ${N.inr(lv)}; the same total as a SIP of ${N.inr(inst)} a month grows to ${N.inr(sv)}. Illustrative, not a forecast. Source: calculation on the inputs given.")
    }
}
