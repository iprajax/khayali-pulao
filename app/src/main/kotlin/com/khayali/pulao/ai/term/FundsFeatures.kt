package com.khayali.pulao.ai.term

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Shared wording for the fund cards. */
internal object FundsText {
    const val PAST = "These are past NAV changes. They leave out exit loads and tax, and say nothing about what the fund will do next."
    fun iso(d: LocalDate) = d.toString()
    /** "₹50.46 lakh", "₹1.2 crore", "₹45,000" — for headlines and chart labels. */
    fun money(v: Double): String {
        val a = abs(v); fun f(x: Double) = String.format(java.util.Locale.US, "%.2f", x).trimEnd('0').trimEnd('.')
        return (if (v < 0) "−" else "") + when { a >= 1e7 -> "₹" + f(a / 1e7) + " crore"; a >= 1e5 -> "₹" + f(a / 1e5) + " lakh"; else -> "₹" + N.num(a, 0) }
    }
    fun years(months: Int): String { val y = months / 12; val m = months % 12
        return listOfNotNull(if (y > 0) "$y year${if (y == 1) "" else "s"}" else null, if (m > 0 || y == 0) "$m month${if (m == 1) "" else "s"}" else null).joinToString(" ") }
    fun rate(v: Double) = String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.') + "%"
    fun staleNote(s: FundsData.Scheme, today: LocalDate) = if (FundsData.stale(s, today)) "The last NAV on this feed is from ${FundsData.day(s.lastDate)}, so this scheme may have been merged or closed. " else ""
}

/** /fund NAME — one mutual fund: latest NAV, returns over 1 month / 1, 3 and 5 years, and the NAV line. */
object FundsFundFeature : Feature {
    override val id = "fund"
    override val usage = "/fund parag flexi"
    override val help = "A mutual fund by name: latest NAV, 1-month to 5-year returns and the NAV chart"
    override val group = Group.FUNDS
    override val triggers = listOf(Regex("""(?i)\bnav (of|for)\b"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val l = FundsData.load(f, arg, 5)
        return l.scheme?.let { build(it, f.today, arg.trim()) } ?: Card.error(l.error ?: "No fund data.")
    }

    /** Change from the last NAV on or before (latest − period) to the latest NAV: total %, and % a year when longer than a year. Null if the history is shorter. */
    fun change(s: FundsData.Scheme, months: Int): Pair<Double, Double>? {
        val target = s.lastDate.minusMonths(months.toLong())
        val i = FundsMath.atOrBefore(s.dates, target) ?: return null
        if (ChronoUnit.DAYS.between(s.dates[i], target) > 10) return null          // a gap in the feed: don't stretch the period
        val total = (s.last / s.navs[i] - 1) * 100
        return total to (if (months > 12) Quant.cagr(s.navs[i], s.last, months / 12.0) else total)
    }

    /** Pure: parsed NAV history → card. [query] is what the user typed, reused in the follow-up commands. */
    fun build(s: FundsData.Scheme, today: LocalDate, query: String = ""): String {
        val m1 = change(s, 1); val y1 = change(s, 12); val y3 = change(s, 36); val y5 = change(s, 60)
        val idx = FundsData.sample(s)
        val hi = s.navs.max(); val lo = s.navs.min()
        val q = query.ifBlank { s.short.lowercase() }
        val span = ChronoUnit.DAYS.between(s.dates.first(), s.lastDate) / 365.25
        val returns = listOfNotNull(m1?.let { "1 month ${N.pct(it.first, 2)}" }, y1?.let { "1 year ${N.pct(it.first, 2)}" },
            y3?.let { "3 years ${N.pct(it.second, 2)} a year" }, y5?.let { "5 years ${N.pct(it.second, 2)} a year" })
        val tone = N.tone((y1 ?: m1)?.first ?: 0.0)
        return Card(listOf("Mutual fund", s.house, s.plan).filter { it.isNotBlank() }.joinToString(" · "), if (tone == "down") "red" else "green")
            .headline("NAV ₹" + N.num(s.last), s.short + " · " + FundsData.day(s.lastDate) + (y1?.let { " · 1 year ${N.pct(it.first)}" } ?: ""), tone)
            .viz("balance", 220, mapOf("x" to "date", "unit" to "nav", "xLabel" to "Date", "yLabel" to "NAV (₹)",
                "series" to listOf(mapOf("name" to "NAV", "color" to (if (s.last >= s.navs[idx.first()]) "green" else "red"), "points" to idx.map { listOf(FundsText.iso(s.dates[it]), s.navs[it]) }))),
                "NAV in ₹ by date, ${FundsData.day(s.dates[idx.first()])} to ${FundsData.day(s.lastDate)}. Tap or drag to read a value.")
            .stats(*listOfNotNull(
                m1?.let { "1 month" to N.pct(it.first, 2) }, y1?.let { "1 year" to N.pct(it.first, 2) },
                y3?.let { "3 years (a year)" to N.pct(it.second, 2) }, y5?.let { "5 years (a year)" to N.pct(it.second, 2) },
                "High in this period" to "₹" + N.num(hi), "Low in this period" to "₹" + N.num(lo)).toTypedArray())
            .note(FundsText.staleNote(s, today) + (if (s.category.isNotBlank()) "Category: ${s.category}. " else "") + "Returns beyond one year are compound yearly rates (CAGR). " + FundsText.PAST)
            .sources(FundsData.SOURCE)
            .pills("/sipreal $q 10000 5y", "/fundcompare $q vs nifty 50 index", "/sip 10000 12% 15y")
            .put("scheme", s.name).put("scheme_code", s.code).put("nav", s.last).put("nav_date", s.lastDate.toString())
            .json("${s.name}: NAV ₹${N.num(s.last)} on ${FundsData.day(s.lastDate)}" + (if (returns.isEmpty()) "; the feed holds ${N.num(span, 2)} years of history, too little for period returns" else "; " + returns.joinToString(", ")) +
                ". Past returns only. Source: ${FundsData.SOURCE}.")
    }
}

/** /fundcompare A vs B — two funds rebased to 100 over the last three years (or the longest period both have). */
object FundsCompareFeature : Feature {
    override val id = "fundcompare"
    override val usage = "/fundcompare parag flexi vs nifty 50 index"
    override val help = "Two mutual funds side by side, both rebased to 100 over three years"
    override val group = Group.FUNDS

    fun split(arg: String): Pair<String, String>? {
        val p = arg.split(Regex("""(?i)\s+(?:vs\.?|versus|against)\s+|\s*,\s*|\s+and\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        return if (p.size == 2) p[0] to p[1] else null
    }

    override suspend fun run(arg: String, f: Fetch): String {
        val (qa, qb) = split(arg) ?: return Card.error("Name two funds, e.g. /fundcompare parag flexi vs nifty 50 index.")
        val a = FundsData.load(f, qa, 3); val sa = a.scheme ?: return Card.error(a.error ?: "No fund data.")
        val b = FundsData.load(f, qb, 3); val sb = b.scheme ?: return Card.error(b.error ?: "No fund data.")
        return build(sa, sb, f.today)
    }

    /** Pure: two NAV histories → card. Both start at 100 on the first day both have a NAV within the last three years. */
    fun build(a: FundsData.Scheme, b: FundsData.Scheme, today: LocalDate): String {
        if (a.code == b.code && a.name == b.name) return Card.error("Both names matched the same scheme (${a.name}). Try more specific names.")
        val end = minOf(a.lastDate, b.lastDate)
        val start = maxOf(a.dates.first(), b.dates.first(), end.minusYears(3))
        val days = ChronoUnit.DAYS.between(start, end)
        if (days < 30) return Card.error("${a.short} and ${b.short} have less than a month of NAV history in common, too little to compare.")
        class Leg(val s: FundsData.Scheme) {
            val from = s.dates.binarySearch(start).let { if (it >= 0) it else -it - 1 }
            val to = FundsMath.atOrBefore(s.dates, end)!!
            val base = s.navs[from]
            val idx = FundsData.sample(FundsData.Scheme(s.code, s.name, s.house, s.category, s.dates.subList(0, to + 1), s.navs.subList(0, to + 1)), from, 110)
            val points = idx.map { listOf(FundsText.iso(s.dates[it]), Math.round(s.navs[it] / base * 10000) / 100.0) }
            val endValue = s.navs[to] / base * 100
            val total = endValue - 100
            val years = ChronoUnit.DAYS.between(s.dates[from], s.dates[to]) / 365.25
            val cagr = Quant.cagr(base, s.navs[to], years)
            val worst = Quant.drawdown(s.navs.subList(from, to + 1)).min()
        }
        val la = Leg(a); val lb = Leg(b)
        val yrs = days / 365.25; val period = if (yrs >= 2.95) "3 years" else String.format(java.util.Locale.US, "%.1f years", yrs)
        val yearly = yrs > 1.0
        val lead = if (la.total >= lb.total) la else lb; val lag = if (lead === la) lb else la
        val nameA = a.short; val nameB = if (b.short == a.short) b.name else b.short
        fun nm(l: Leg) = if (l === la) nameA else nameB
        val gap = lead.endValue - lag.endValue
        return Card("Fund comparison · rebased to 100 · $period", "violet")
            .headline("${N.num(la.endValue, 2)} vs ${N.num(lb.endValue, 2)}", "₹100 in each on ${FundsData.day(start)} · $nameA ${N.pct(la.total)} · $nameB ${N.pct(lb.total)}")
            .viz("balance", 230, mapOf("x" to "date", "unit" to "num", "xLabel" to "Date", "yLabel" to "Value of ₹100",
                "series" to listOf(mapOf("name" to nameA.take(44), "color" to "blue", "points" to la.points), mapOf("name" to nameB.take(44), "color" to "saffron", "points" to lb.points)),
                "marker" to mapOf("value" to 100, "label" to "Start = 100")),
                "Both NAVs rebased to 100 on ${FundsData.day(start)}; the line shows what ₹100 became, by date. Tap or drag to read values.")
            .rows("Over $period to ${FundsData.day(end)}", listOf(la, lb).map { l ->
                Card.Row(nm(l), (if (yearly) "${N.pct(l.cagr, 2)} a year · " else "") + "deepest fall from a peak ${N.pct(l.worst)}", N.pct(l.total), N.tone(l.total)) })
            .note(FundsText.staleNote(a, today) + FundsText.staleNote(b, today) + (if (yrs < 2.95) "The two funds share only $period of NAV history, so that is the period compared. " else "") +
                "Compares the plans named above; different categories carry different risks. " + FundsText.PAST)
            .sources(FundsData.SOURCE)
            .pills("/fund ${nameA.lowercase()}", "/fund ${nameB.lowercase()}", "/sipreal ${nameA.lowercase()} 10000 3y")
            .json("Over $period to ${FundsData.day(end)}, ₹100 in ${a.name} became ₹${N.num(la.endValue)} (${N.pct(la.total, 2)}" + (if (yearly) ", ${N.pct(la.cagr, 2)} a year" else "") +
                ") and ₹100 in ${b.name} became ₹${N.num(lb.endValue)} (${N.pct(lb.total, 2)}" + (if (yearly) ", ${N.pct(lb.cagr, 2)} a year" else "") +
                "); ${nm(lead)} ended ${N.num(gap)} points ahead. Past returns only. Source: ${FundsData.SOURCE}.")
    }
}

/** /sipreal NAME AMOUNT Ny — a monthly SIP run through the fund's actual NAV history: invested vs value today, and XIRR. */
object FundsSipRealFeature : Feature {
    override val id = "sipreal"
    override val usage = "/sipreal parag flexi 10000 5y"
    override val help = "What a monthly SIP in a fund would be worth today, from its actual NAV history, with XIRR"
    override val group = Group.FUNDS

    class Ask(val fund: String, val amount: Double, val years: Int, val assumed: List<String>)

    /** "parag flexi 10000 5y" → fund, amount, years. Numbers are read from the end, so "nifty 50 index 5000 3y" keeps its 50. */
    fun ask(arg: String): Ask {
        val t = arg.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.toMutableList()
        var amount: Double? = null; var years: Int? = null
        while (t.size > 1) {
            val last = t.last(); val two = if (t.size > 2) t[t.size - 2] + " " + last else null
            val y = Regex("""(?i)^(\d{1,2})\s*(y|yr|yrs|year|years)$""").find(last)?.groupValues?.get(1)?.toIntOrNull()
            if (y != null && years == null) { years = y; t.removeAt(t.lastIndex); continue }
            if (amount == null) {
                val unit = Regex("""(?i)^(lakhs?|lacs?|crores?|cr|k|thousand)$""").matches(last)
                val m2 = if (unit && two != null) com.khayali.pulao.ai.Money.parse(two) else null
                if (m2 != null && m2 >= 100) { amount = m2; t.removeAt(t.lastIndex); t.removeAt(t.lastIndex); continue }
                val m1 = if (last.any { it.isDigit() }) com.khayali.pulao.ai.Money.parse(last) else null
                if (m1 != null && m1 >= 100) { amount = m1; t.removeAt(t.lastIndex); continue }
            }
            break
        }
        return Ask(t.joinToString(" "), amount ?: 10000.0, (years ?: 5).coerceIn(1, 30), listOfNotNull(if (amount == null) "₹10,000 a month" else null, if (years == null) "5 years" else null))
    }

    override suspend fun run(arg: String, f: Fetch): String {
        val a = ask(arg)
        if (a.fund.isBlank()) return Card.error("Which fund? Try /sipreal parag flexi 10000 5y.")
        if (a.amount > 1e9) return Card.error("That monthly amount is too large for this calculator. Try /sipreal parag flexi 10000 5y.")
        val l = FundsData.load(f, a.fund, a.years)
        return l.scheme?.let { build(it, a.amount, a.years, f.today, a.fund, a.assumed) } ?: Card.error(l.error ?: "No fund data.")
    }

    /** Pure: NAV history + SIP → card. */
    fun build(s: FundsData.Scheme, amount: Double, years: Int, today: LocalDate, query: String = "", assumed: List<String> = emptyList()): String {
        val r = FundsMath.sipReal(s.dates, s.navs, amount, years) ?: return Card.error("${s.short} has too little NAV history on this feed to run a SIP through it.")
        val gain = r.value - r.invested; val gainPct = gain / r.invested * 100
        val q = query.ifBlank { s.short.lowercase() }
        val short = r.instalments < years * 12 - 1
        val step = Math.ceil(r.path.size / 80.0).toInt().coerceAtLeast(1)
        val pts = r.path.filterIndexed { i, _ -> i % step == 0 || i == r.path.size - 1 }
        val xirr = r.xirr?.let { N.pct(it, 2) + " a year" }
        return Card("SIP in ${s.short} · actual NAVs", if (gain >= 0) "green" else "red")
            .headline(N.inr(r.value), "from ${N.inr(r.invested)} invested · ${if (gain >= 0) "gain" else "loss"} ${N.inr(abs(gain))} (${N.pct(gainPct)})" + (xirr?.let { " · XIRR $it" } ?: ""), N.tone(gain))
            .viz("balance", 230, mapOf("x" to "date", "unit" to "inr", "xLabel" to "Date", "yLabel" to "Amount (₹)",
                "series" to listOf(mapOf("name" to "Value", "color" to (if (gain >= 0) "green" else "red"), "points" to pts.map { listOf(FundsText.iso(it.first), Math.round(it.third)) }),
                    mapOf("name" to "Invested", "color" to "muted", "dashed" to true, "points" to pts.map { listOf(FundsText.iso(it.first), Math.round(it.second)) }))),
                "Value of the units bought (line) against the money put in (dashed), in ₹ by date. Tap or drag to read values.")
            .stats("Monthly SIP" to N.inr(amount), "Instalments" to r.instalments.toString(), "Invested" to N.inr(r.invested), "Value on ${FundsData.day(r.last)}" to N.inr(r.value),
                (if (gain >= 0) "Gain" else "Loss") to N.inr(abs(gain)), "XIRR" to (xirr ?: "n/a"))
            .note(FundsText.staleNote(s, today) + (if (assumed.isNotEmpty()) "Assumed ${assumed.joinToString(" and ")} because the command did not say. " else "") +
                (if (short) "The feed's history for this scheme starts on ${FundsData.day(s.dates.first())}, so the SIP runs for ${r.instalments} instalments instead of ${years * 12}. " else "") +
                "Each instalment buys units at the first NAV on or after the same day of every month, starting ${FundsData.day(r.first)}. XIRR is the yearly return that explains these dated cash flows. " +
                "Stamp duty, exit load and tax are left out. " + FundsText.PAST)
            .sources(FundsData.SOURCE)
            .pills("/fund $q", "/sip ${N.num(amount, 0).replace(",", "")} 12% ${years}y", "/fundcompare $q vs nifty 50 index")
            .put("scheme", s.name).put("invested", Math.round(r.invested)).put("value", Math.round(r.value)).put("xirr_pct", r.xirr?.let { Math.round(it * 100) / 100.0 })
            .json("A SIP of ${N.inr(amount)} a month in ${s.name} from ${FundsData.day(r.first)} (${r.instalments} instalments, ${N.inr(r.invested)} invested) was worth ${N.inr(r.value)} on ${FundsData.day(r.last)}" +
                (xirr?.let { ", an XIRR of $it" } ?: "") + ". Past returns only. Source: ${FundsData.SOURCE}.")
    }
}
