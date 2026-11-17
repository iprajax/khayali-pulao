package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.DerivChain.Companion.contracts
import com.khayali.pulao.ai.term.DerivChain.Companion.date
import com.khayali.pulao.ai.term.DerivChain.Companion.daysText
import com.khayali.pulao.ai.term.DerivChain.Companion.signed
import com.khayali.pulao.ai.term.DerivChain.Companion.strike
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/*
 * The derivatives pack: seven cards drawn from NSE's public option chain. Each feature is a thin
 * run() that fetches through DerivChain.load and a pure build() that turns one parsed chain into a
 * card. Open interest and volume are in contracts, as NSE reports them. Nothing here is a trading
 * call: the cards describe where positions sit and what option prices imply, and say what that
 * does not tell you.
 */

private fun ratio(v: Double?): String = v?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"
private fun iv(v: Double): String = String.format(Locale.US, "%.1f", v) + "%"
private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** /optionchain [symbol] — open interest by strike, calls against puts, around the spot price. */
object DerivOptionChainFeature : Feature {
    override val id = "optionchain"
    override val usage = "/optionchain NIFTY"
    override val help = "Option chain: open interest by strike, calls vs puts, around spot for the nearest expiry"
    override val group = Group.DERIVATIVES
    override val triggers = listOf(Regex("""(?i)\boption ?chain\b"""))
    const val MAX_ROWS = 21

    override suspend fun run(arg: String, f: Fetch): String {
        val l = DerivChain.load(arg, f, usage)
        return l.error?.let(Card::error) ?: build(l.chains.first())
    }

    fun build(symbol: String, json: String): String = DerivChain.card(symbol, json, ::build)

    fun build(c: DerivChain): String {
        val rows = DerivMath.closest(c.near, c.spot, MAX_ROWS)
        val topCall = c.near.maxByOrNull { it.ce.oi }; val topPut = c.near.maxByOrNull { it.pe.oi }
        if (rows.isEmpty() || topCall == null || topPut == null || (topCall.ce.oi <= 0 && topPut.pe.oi <= 0))
            return Card.error("The ${c.symbol} option chain for ${date(c.expiry)} has no open interest near the spot price yet. Try /expiries ${c.symbol}.")
        val pcr = DerivMath.ratio(c.putOi, c.callOi)
        return Card("${c.symbol} · option chain · ${c.expiryText}", "blue")
            .headline("Calls ${strike(topCall.strike)} · Puts ${strike(topPut.strike)}", "Strikes holding the most open interest within 6% of spot ${N.num(c.spot)}")
            .viz("butterfly", 64 + rows.size * 14, mapOf("left" to "Puts", "right" to "Calls", "unit" to "contracts", "spot" to c.spot, "yLabel" to "Strike", "xLabel" to "Open interest (contracts)",
                "rows" to rows.map { mapOf("strike" to it.strike, "left" to it.pe.oi, "right" to it.ce.oi) }),
                "Rows: strike price, the ${rows.size} nearest to spot. Bars: open interest in contracts — puts to the left, calls to the right. Dashed line: spot. Tap a row.")
            .stats("Call OI (all strikes)" to contracts(c.callOi), "Put OI (all strikes)" to contracts(c.putOi), "Put-call ratio (OI)" to ratio(pcr), "Spot" to N.num(c.spot))
            .note("Open interest shows where contracts are outstanding, not who is long or short, and not where the price will go. NSE ${c.asOf}; not live.")
            .sources("NSE").pills("/pcr ${c.symbol}", "/maxpain ${c.symbol}", "/oichange ${c.symbol}", "/ivsmile ${c.symbol}")
            .put("symbol", c.symbol).put("expiry", c.expiry.toString())
            .json("${c.symbol} options, ${c.expiryText}, spot ${N.num(c.spot)}: within 6% of spot the most call open interest is at ${strike(topCall.strike)} (${contracts(topCall.ce.oi)} contracts) " +
                "and the most put open interest at ${strike(topPut.strike)} (${contracts(topPut.pe.oi)} contracts). Source: NSE, ${c.asOf}.")
    }
}

/** /pcr [symbol] — put-call ratio by open interest and by volume for the nearest expiries. */
object DerivPcrFeature : Feature {
    override val id = "pcr"
    override val usage = "/pcr NIFTY"
    override val help = "Put-call ratio by open interest and by volume for the nearest expiries, and what it means"
    override val group = Group.DERIVATIVES
    override val triggers = listOf(Regex("""(?i)\b(pcr|put[- ]call ratio)\b"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val l = DerivChain.load(arg, f, usage, count = 3)
        return l.error?.let(Card::error) ?: build(l.chains)
    }

    fun build(symbol: String, jsons: List<String>): String {
        val chains = jsons.mapNotNull { DerivChain.parse(symbol, it) }
        return if (chains.isEmpty()) Card.error(DerivChain.problem(symbol, jsons.firstOrNull().orEmpty())) else build(chains)
    }

    fun build(chains: List<DerivChain>): String {
        val c = chains.first()
        val oi = DerivMath.pcrOi(c.all) ?: return Card.error("${c.symbol} has no call open interest for ${date(c.expiry)}, so a put-call ratio can't be worked out.")
        val vol = DerivMath.pcrVolume(c.all)
        val lean = if (oi > 1.0) "more puts than calls are outstanding" else if (oi < 1.0) "more calls than puts are outstanding" else "puts and calls are level"
        return Card("${c.symbol} · put-call ratio · ${c.expiryText}", "violet")
            .headline("PCR " + ratio(oi), "By open interest: $lean · by volume ${ratio(vol)}")
            .viz("groupbars", 210, mapOf("unit" to "contracts", "xLabel" to "Expiry", "series" to listOf("Call OI", "Put OI"),
                "items" to chains.map { mapOf("label" to SHORT_DATE.format(it.expiry), "values" to listOf(it.callOi, it.putOi)) }),
                "Open interest in contracts, calls and puts, for each of the nearest expiries. The put-call ratio is the put bar divided by the call bar.")
            .rows("Put-call ratio by expiry", chains.map { Card.Row(date(it.expiry), "Calls ${contracts(it.callOi)} · puts ${contracts(it.putOi)} contracts", "OI ${ratio(DerivMath.pcrOi(it.all))} · vol ${ratio(DerivMath.pcrVolume(it.all))}") })
            .note("Above 1, more put than call contracts are open; below 1, more calls. The ratio does not show who bought and who wrote them, so on its own it says nothing reliable about direction. NSE ${c.asOf}; not live.")
            .sources("NSE").pills("/optionchain ${c.symbol}", "/maxpain ${c.symbol}", "/straddle ${c.symbol}", "/fiidii")
            .put("symbol", c.symbol).put("expiry", c.expiry.toString())
            .json("${c.symbol} put-call ratio, ${c.expiryText}: ${ratio(oi)} by open interest (${contracts(c.putOi)} put vs ${contracts(c.callOi)} call contracts)" +
                (vol?.let { " and ${ratio(it)} by volume" } ?: "") + " — $lean. Source: NSE, ${c.asOf}.")
    }
}

/** /maxpain [symbol] — the expiry level at which option writers as a group pay out least. */
object DerivMaxPainFeature : Feature {
    override val id = "maxpain"
    override val usage = "/maxpain NIFTY"
    override val help = "Max-pain strike for the nearest expiry, worked out from open interest, with the pain curve"
    override val group = Group.DERIVATIVES
    override val triggers = listOf(Regex("""(?i)\bmax(imum)?[- ]?pain\b"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val l = DerivChain.load(arg, f, usage)
        return l.error?.let(Card::error) ?: build(l.chains.first())
    }

    fun build(symbol: String, json: String): String = DerivChain.card(symbol, json, ::build)

    fun build(c: DerivChain): String {
        val mp = DerivMath.maxPain(c.all) ?: return Card.error("${c.symbol} has no open interest for ${date(c.expiry)} yet, so max pain can't be worked out.")
        val gap = mp - c.spot; val gapPct = gap / c.spot * 100
        val where = if (gap == 0.0) "at spot" else "${N.num(abs(gap), 0)} ${if (c.isIndex) "points" else "rupees"} (${N.pct(abs(gapPct)).removePrefix("+")}) ${if (gap > 0) "above" else "below"} spot"
        val lo = minOf(c.spot * 0.94, mp); val hi = maxOf(c.spot * 1.06, mp)
        val curve = DerivMath.painCurve(c.all).filter { it.first in lo..hi }
        return Card("${c.symbol} · max pain · ${c.expiryText}", "saffron")
            .headline(strike(mp), "Max-pain strike · $where ${N.num(c.spot)}")
            .viz("curve", 230, mapOf("xLabel" to "Expiry level (strike)", "yLabel" to "Writers' pay-out (contracts × points)", "yFmt" to "num",
                "series" to listOf(mapOf("name" to "Pay-out", "color" to "saffron", "points" to curve.map { listOf(it.first, it.second) })),
                "markers" to listOf(mapOf("x" to c.spot, "label" to "Spot"), mapOf("x" to mp, "label" to "Max pain", "color" to "saffron"))),
                "Across: the level ${c.symbol} could expire at. Up: what all open calls and puts would then be worth, in contracts × points. The lowest point is max pain. Tap the curve.")
            .stats("Max pain" to strike(mp), "Spot" to N.num(c.spot), "Call OI" to contracts(c.callOi), "Put OI" to contracts(c.putOi))
            .note("Max pain is arithmetic on today's open interest — the expiry level at which option buyers as a group would collect least. It moves as positions change and is not a forecast. NSE ${c.asOf}; not live.")
            .sources("NSE").pills("/optionchain ${c.symbol}", "/pcr ${c.symbol}", "/straddle ${c.symbol}")
            .put("symbol", c.symbol).put("expiry", c.expiry.toString()).put("maxPain", mp)
            .json("${c.symbol} max pain, ${c.expiryText}: ${strike(mp)}, $where ${N.num(c.spot)}. It is worked out from open interest (${contracts(c.callOi)} call and ${contracts(c.putOi)} put contracts) and is not a forecast. Source: NSE, ${c.asOf}.")
    }
}

/** /ivsmile [symbol] — implied volatility by strike for calls and puts. */
object DerivIvSmileFeature : Feature {
    override val id = "ivsmile"
    override val usage = "/ivsmile NIFTY"
    override val help = "Implied volatility by strike for calls and puts (the volatility smile) for the nearest expiry"
    override val group = Group.DERIVATIVES
    override val triggers = listOf(Regex("""(?i)\b(iv|vol|volatility) ?(smile|skew)\b"""), Regex("""(?i)\bimplied vol(atility)?\b"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val l = DerivChain.load(arg, f, usage)
        return l.error?.let(Card::error) ?: build(l.chains.first())
    }

    fun build(symbol: String, json: String): String = DerivChain.card(symbol, json, ::build)

    fun build(c: DerivChain): String {
        // A quote counts only if the option traded today and NSE published an IV for it. Deep in-the-money options
        // trade thinly and their IV jumps about, so each line runs from 2% inside the money outwards.
        val calls = c.near.filter { it.ce.iv > 0 && it.ce.vol > 0 && it.strike >= c.spot * 0.98 }.map { it.strike to it.ce.iv }
        val puts = c.near.filter { it.pe.iv > 0 && it.pe.vol > 0 && it.strike <= c.spot * 1.02 }.map { it.strike to it.pe.iv }
        val atm = c.near.filter { it.ce.iv > 0 && it.pe.iv > 0 }.minByOrNull { abs(it.strike - c.spot) }
        if (atm == null || calls.size + puts.size < 4) return Card.error("NSE shows no implied volatility for ${c.symbol} options expiring ${date(c.expiry)} right now. IV is blank when options have not traded — try during market hours.")
        val mid = (atm.ce.iv + atm.pe.iv) / 2
        val lowPut = puts.filter { it.first < c.spot }.minByOrNull { abs(it.first - c.spot * 0.97) }
        val highCall = calls.filter { it.first > c.spot }.minByOrNull { abs(it.first - c.spot * 1.03) }
        return Card("${c.symbol} · implied volatility by strike · ${c.expiryText}", "violet")
            .headline(iv(mid), "At-the-money IV at ${strike(atm.strike)} · call ${iv(atm.ce.iv)} · put ${iv(atm.pe.iv)}")
            .viz("curve", 230, mapOf("xLabel" to "Strike", "yLabel" to "Implied volatility (% a year)", "yFmt" to "pct",
                "series" to listOf(mapOf("name" to "Calls", "color" to "blue", "points" to calls.map { listOf(it.first, it.second) }), mapOf("name" to "Puts", "color" to "saffron", "points" to puts.map { listOf(it.first, it.second) })),
                "markers" to listOf(mapOf("x" to c.spot, "label" to "Spot"))),
                "Across: strike price. Up: implied volatility, % a year. Puts are drawn from just above spot downwards and calls from just below spot upwards, where they trade actively. Tap the chart.")
            .stats(*listOfNotNull("ATM call IV" to iv(atm.ce.iv), "ATM put IV" to iv(atm.pe.iv),
                lowPut?.let { "Put IV at ${strike(it.first)}" to iv(it.second) }, highCall?.let { "Call IV at ${strike(it.first)}" to iv(it.second) }).toTypedArray())
            .note("Implied volatility is the yearly swing that option prices assume, as NSE computes it from last traded prices. Far-from-spot strikes trade thinly, so their IV can be erratic. It measures expected size of moves, not direction. NSE ${c.asOf}; not live.")
            .sources("NSE").pills("/straddle ${c.symbol}", "/optionchain ${c.symbol}", "/pcr ${c.symbol}")
            .put("symbol", c.symbol).put("expiry", c.expiry.toString())
            .json("${c.symbol} implied volatility, ${c.expiryText}: about ${iv(mid)} at the money (${strike(atm.strike)} strike: call ${iv(atm.ce.iv)}, put ${iv(atm.pe.iv)})" +
                (lowPut?.let { "; put at ${strike(it.first)} ${iv(it.second)}" } ?: "") + (highCall?.let { "; call at ${strike(it.first)} ${iv(it.second)}" } ?: "") + ". Source: NSE, ${c.asOf}.")
    }
}

/** /straddle [symbol] — the at-the-money straddle price and the move it implies by expiry. */
object DerivStraddleFeature : Feature {
    override val id = "straddle"
    override val usage = "/straddle NIFTY"
    override val help = "At-the-money straddle price and the move, in points and %, that options price in by expiry"
    override val group = Group.DERIVATIVES
    override val triggers = listOf(Regex("""(?i)\bstraddle\b"""), Regex("""(?i)\b(expected|implied) move\b"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val l = DerivChain.load(arg, f, usage)
        return l.error?.let(Card::error) ?: build(l.chains.first(), f.today)
    }

    fun build(symbol: String, json: String, today: LocalDate): String = DerivChain.card(symbol, json) { build(it, today) }

    fun build(c: DerivChain, today: LocalDate): String {
        val m = DerivMath.expectedMove(c.near, c.spot) ?: return Card.error("No at-the-money ${c.symbol} call and put have both traded for ${date(c.expiry)}, so there is no straddle price to read. Try during market hours.")
        val days = DerivMath.days(today, c.expiry).coerceAtLeast(0)
        val left = if (days == 0) "expires today" else "${daysText(days)} to expiry"
        val pctText = N.pct(m.pct, 2).removePrefix("+")
        val pts = c.near.filter { it.ce.ltp > 0 && it.pe.ltp > 0 && it.ce.vol > 0 && it.pe.vol > 0 }.map { listOf(it.strike, it.ce.ltp + it.pe.ltp) }
        val card = Card("${c.symbol} · ATM straddle · ${c.expiryText}", "blue")
            .headline("±" + (if (c.isIndex) N.num(m.points, 0) + " points" else "₹" + N.num(m.points)), "±$pctText of spot ${N.num(c.spot)} · $left")
        if (pts.size >= 3) card.viz("curve", 220, mapOf("xLabel" to "Strike", "yLabel" to "Call + put premium (₹)", "yFmt" to "price",
            "series" to listOf(mapOf("name" to "Straddle", "color" to "blue", "points" to pts)), "markers" to listOf(mapOf("x" to c.spot, "label" to "Spot"))),
            "Across: strike price. Up: last price of the call plus the put at that strike, in rupees. The lowest point sits near spot. Tap the curve.")
        return card.stats("ATM strike" to strike(m.strike), "Call" to "₹" + N.num(m.call), "Put" to "₹" + N.num(m.put), "Straddle" to "₹" + N.num(m.points),
                "Lower break-even" to N.num(m.low), "Upper break-even" to N.num(m.high))
            .note("The straddle price is a rough reading of how far option prices assume ${c.symbol} could move, either way, by expiry. It comes from last traded prices and is indicative — a measure of expected size, not direction, and not a forecast. NSE ${c.asOf}; not live.")
            .sources("NSE").pills("/ivsmile ${c.symbol}", "/optionchain ${c.symbol}", "/maxpain ${c.symbol}", "/expiries ${c.symbol}")
            .put("symbol", c.symbol).put("expiry", c.expiry.toString())
            .json("${c.symbol} ${strike(m.strike)} straddle, ${c.expiryText} ($left): call ${N.num(m.call)} + put ${N.num(m.put)} = ${N.num(m.points)}, " +
                "so options price in a move of about ±${c.points(m.points)} (±$pctText of spot ${N.num(c.spot)}), break-evens ${N.num(m.low)} and ${N.num(m.high)}. Indicative, from last traded prices. Source: NSE, ${c.asOf}.")
    }
}

/** /oichange [symbol] — where open interest was added and where it was unwound today. */
object DerivOiChangeFeature : Feature {
    override val id = "oichange"
    override val usage = "/oichange NIFTY"
    override val help = "Today's biggest additions and unwinding of open interest by strike, calls and puts"
    override val group = Group.DERIVATIVES
    override val triggers = listOf(Regex("""(?i)\b(oi|open interest) (change|build[- ]?up|unwinding|addition)s?\b"""), Regex("""(?i)\bchange in (oi|open interest)\b"""))
    const val CHART_STRIKES = 7

    override suspend fun run(arg: String, f: Fetch): String {
        val l = DerivChain.load(arg, f, usage)
        return l.error?.let(Card::error) ?: build(l.chains.first())
    }

    fun build(symbol: String, json: String): String = DerivChain.card(symbol, json, ::build)

    private class Change(val strike: Double, val type: String, val d: Double, val oi: Double) { val label get() = "${strike(strike)} $type" }

    fun build(c: DerivChain): String {
        val legs = c.near.flatMap { listOf(Change(it.strike, "CE", it.ce.dOi, it.ce.oi), Change(it.strike, "PE", it.pe.dOi, it.pe.oi)) }.filter { it.d != 0.0 }
        if (legs.isEmpty()) return Card.error("NSE shows no change in ${c.symbol} open interest for ${date(c.expiry)} yet. The figures fill in once trading starts.")
        val adds = legs.filter { it.d > 0 }.sortedByDescending { it.d }; val cuts = legs.filter { it.d < 0 }.sortedBy { it.d }
        val chart = c.near.sortedByDescending { maxOf(abs(it.ce.dOi), abs(it.pe.dOi)) }.take(CHART_STRIKES).sortedBy { it.strike }
        val callNet = c.near.sumOf { it.ce.dOi }; val putNet = c.near.sumOf { it.pe.dOi }
        val top = adds.firstOrNull(); val cut = cuts.firstOrNull()
        fun say(x: Change) = "${x.label} ${signed(x.d)}"
        val rows = adds.take(3).map { Card.Row(it.label, "Added · now ${contracts(it.oi)} contracts", signed(it.d), "") } +
            cuts.take(3).map { Card.Row(it.label, "Unwound · now ${contracts(it.oi)} contracts", signed(it.d), "warn") }
        return Card("${c.symbol} · change in open interest · ${c.expiryText}", "saffron")
            .headline(say(top ?: cut!!), (if (top != null) "Biggest addition of contracts" else "Biggest unwinding of contracts") + (if (top != null && cut != null) " · biggest unwinding ${say(cut)}" else "") + " · within 6% of spot ${N.num(c.spot)}")
            .viz("groupbars", 220, mapOf("unit" to "contracts", "xLabel" to "Strike", "series" to listOf("Call OI change", "Put OI change"),
                "items" to chart.map { mapOf("label" to strike(it.strike).replace(",", ""), "values" to listOf(it.ce.dOi, it.pe.dOi)) }),
                "The ${chart.size} strikes with the largest change. Bars above zero: contracts added today; below zero: contracts closed. Tap a bar.")
            .stats("Net change, calls" to signed(callNet), "Net change, puts" to signed(putNet))
            .rows("Largest changes (CE = call, PE = put)", rows)
            .note("A rise in open interest means new contracts were opened; a fall means positions were closed. Neither shows whether buyers or writers took the initiative. Change is against the previous close. NSE ${c.asOf}; not live.")
            .sources("NSE").pills("/optionchain ${c.symbol}", "/pcr ${c.symbol}", "/maxpain ${c.symbol}")
            .put("symbol", c.symbol).put("expiry", c.expiry.toString())
            .json("${c.symbol} open-interest change, ${c.expiryText}, within 6% of spot ${N.num(c.spot)}: " +
                listOfNotNull(top?.let { "biggest addition ${say(it)} contracts" }, cut?.let { "biggest unwinding ${say(it)} contracts" }).joinToString(", ") +
                "; net ${signed(callNet)} in calls and ${signed(putNet)} in puts. Source: NSE, ${c.asOf}.")
    }
}

/** /expiries [symbol] — the upcoming option expiries and how many days away each is. */
object DerivExpiriesFeature : Feature {
    override val id = "expiries"
    override val usage = "/expiries NIFTY"
    override val help = "Upcoming option expiry dates for an index or stock, with days to go"
    override val group = Group.DERIVATIVES
    override val triggers = listOf(Regex("""(?i)\b(expiry dates?|expiries|next expiry)\b"""))
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)
    const val CHART_EXPIRIES = 6

    override suspend fun run(arg: String, f: Fetch): String {
        val sym = DerivChain.symbol(arg, f) ?: return Card.error("Which index or stock? Try $usage.")
        return build(sym, f.nse(DerivChain.infoPath(sym)), f.today)
    }

    /** Pure: contract-info JSON → card. */
    fun build(symbol: String, json: String, today: LocalDate): String {
        if (json.isBlank()) return Card.error("Couldn't reach NSE for $symbol option contracts. Try again in a moment.")
        if (J.obj(json) == null) return Card.error("Couldn't read the $symbol expiry list from NSE.")
        val dates = DerivChain.expiries(json, today)
        if (dates.isEmpty()) return Card.error("NSE lists no upcoming option expiries for $symbol. Options exist for NIFTY, BANKNIFTY, FINNIFTY and F&O stocks — try $usage.")
        // the last listed expiry of a calendar month is that month's main contract; earlier ones in the month are weeklies
        fun kind(d: LocalDate) = if (dates.none { it.year == d.year && it.month == d.month && it.isAfter(d) } && d.dayOfMonth >= 22) "monthly" else "weekly"
        fun left(d: LocalDate) = DerivMath.days(today, d).let { if (it == 0) "today" else "in ${daysText(it)}" }
        val first = dates.first(); val next = dates.getOrNull(1)
        return Card("$symbol · option expiries", "blue")
            .headline(date(first), "Nearest expiry · ${WEEKDAY.format(first)} · ${left(first)}")
            .viz("groupbars", 200, mapOf("unit" to "days", "xLabel" to "Expiry date", "series" to listOf("Days to go"),
                "items" to dates.take(CHART_EXPIRIES).map { mapOf("label" to SHORT_DATE.format(it), "values" to listOf(DerivMath.days(today, it))) }),
                "Across: the next ${minOf(CHART_EXPIRIES, dates.size)} expiry dates. Up: calendar days from today. Tap a bar.")
            .stats("Expiries listed" to dates.size.toString(), "Furthest" to date(dates.last()))
            .rows("Upcoming expiries", dates.take(10).map { Card.Row(date(it), WEEKDAY.format(it) + " · " + kind(it), if (it == today) "today" else daysText(DerivMath.days(today, it))) })
            .note("Dates are the contracts NSE currently lists; an expiry moves earlier when its usual day is a trading holiday. Weekly or monthly is inferred from the dates.")
            .sources("NSE").pills("/optionchain $symbol", "/straddle $symbol", "/pcr $symbol", "/calendar")
            .put("symbol", symbol).put("nearest", first.toString())
            .json("$symbol has ${dates.size} option ${if (dates.size == 1) "expiry" else "expiries"} listed. The nearest is ${date(first)} (${left(first)})" +
                (next?.let { ", then ${date(it)} (${left(it)})" } ?: "") + ". Source: NSE.")
    }
}
