package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.WebData
import com.khayali.pulao.ai.term.J.a
import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.o
import com.khayali.pulao.ai.term.J.s
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Option-chain arithmetic. Pure: no JSON, no dates from the clock, no fetching. */
object DerivMath {
    /** One option at one strike: open interest and today's change in it (contracts), implied volatility (%), last price, volume. */
    data class Leg(val oi: Double = 0.0, val dOi: Double = 0.0, val iv: Double = 0.0, val ltp: Double = 0.0, val vol: Double = 0.0)
    data class Strike(val strike: Double, val ce: Leg, val pe: Leg)
    /** The at-the-money straddle: what one call plus one put at [strike] cost, and the move that pays for them. */
    data class Move(val strike: Double, val call: Double, val put: Double, val spot: Double) {
        val points get() = call + put
        val pct get() = if (spot > 0) points / spot * 100 else 0.0
        val low get() = strike - points
        val high get() = strike + points
    }

    /** Puts ÷ calls, or null when there are no calls to divide by. */
    fun ratio(puts: Double, calls: Double): Double? = if (calls > 0) puts / calls else null
    fun pcrOi(rows: List<Strike>): Double? = ratio(rows.sumOf { it.pe.oi }, rows.sumOf { it.ce.oi })
    fun pcrVolume(rows: List<Strike>): Double? = ratio(rows.sumOf { it.pe.vol }, rows.sumOf { it.ce.vol })

    /** What all open options would be worth (contracts × points) if the underlying expired at [level]: the amount writers pay out. */
    fun pain(rows: List<Strike>, level: Double): Double = rows.sumOf { it.ce.oi * max(0.0, level - it.strike) + it.pe.oi * max(0.0, it.strike - level) }
    /** The pay-out at every listed strike, in strike order. */
    fun painCurve(rows: List<Strike>): List<Pair<Double, Double>> = rows.sortedBy { it.strike }.map { it.strike to pain(rows, it.strike) }
    /** The strike where the pay-out is smallest (the lower strike on a tie). Null when there is no open interest at all. */
    fun maxPain(rows: List<Strike>): Double? = if (rows.none { it.ce.oi > 0 || it.pe.oi > 0 }) null else painCurve(rows).minByOrNull { it.second }?.first

    /** Strikes within [band] (a fraction, 0.06 = 6%) of [spot]. */
    fun near(rows: List<Strike>, spot: Double, band: Double = 0.06): List<Strike> = if (spot <= 0) emptyList() else rows.filter { abs(it.strike / spot - 1) <= band }
    /** The [n] strikes closest to [spot], in strike order. */
    fun closest(rows: List<Strike>, spot: Double, n: Int): List<Strike> = rows.sortedBy { abs(it.strike - spot) }.take(n).sortedBy { it.strike }
    /** The strike nearest [spot] where both the call and the put have a last price. */
    fun atm(rows: List<Strike>, spot: Double): Strike? = rows.filter { it.ce.ltp > 0 && it.pe.ltp > 0 }.minByOrNull { abs(it.strike - spot) }
    fun expectedMove(rows: List<Strike>, spot: Double): Move? = if (spot <= 0) null else atm(rows, spot)?.let { Move(it.strike, it.ce.ltp, it.pe.ltp, spot) }

    fun days(today: LocalDate, expiry: LocalDate): Int = ChronoUnit.DAYS.between(today, expiry).toInt()
}

/** One expiry of one underlying's option chain, as NSE publishes it. */
class DerivChain(val symbol: String, val expiry: LocalDate, val spot: Double, val timestamp: String, val all: List<DerivMath.Strike>) {
    /** The part worth drawing: strikes within about 6% of spot. Totals and max pain use [all]. */
    val near: List<DerivMath.Strike> = DerivMath.near(all, spot)
    val isIndex get() = symbol in INDICES
    val callOi get() = all.sumOf { it.ce.oi }
    val putOi get() = all.sumOf { it.pe.oi }

    /** "expiry 23 Nov 2026". */
    val expiryText: String get() = "expiry " + date(expiry)
    /** "snapshot 16-Nov-2026 15:40:00" — the feed's own time stamp; it is not live. */
    val asOf: String get() = if (timestamp.isBlank()) "delayed snapshot" else "snapshot $timestamp"
    /** A premium or a move: index options are quoted in points, stock options in rupees. */
    fun points(v: Double, digits: Int = 2): String = if (isIndex) N.num(v, digits) + " points" else "₹" + N.num(v, digits)

    /** What [load] hands back: the chains it could read (nearest expiry first) or the reason there are none. */
    class Loaded(val symbol: String, val chains: List<DerivChain>, val error: String?)

    companion object {
        val INDICES = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
        private val FEED_IN = java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-MMM-yyyy").toFormatter(Locale.ENGLISH)
        private val FEED_OUT = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)
        private val SHOW_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

        fun date(d: LocalDate): String = SHOW_DATE.format(d)
        fun parseDate(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim(), FEED_IN) }.getOrNull()
        /** A strike without needless decimals: 25300 → "25,300", 1012.5 → "1,012.50". */
        fun strike(v: Double): String = if (v % 1.0 == 0.0) N.num(v, 0) else N.num(v, 2)
        fun contracts(v: Double): String = N.num(v, 0)
        fun signed(v: Double): String = (if (v > 0) "+" else if (v < 0) "−" else "") + N.num(abs(v), 0)
        fun daysText(n: Int): String = when (n) { 0 -> "today"; 1 -> "1 day"; else -> "$n days" }

        /** "" / "nifty 50" → NIFTY, "bank nifty" → BANKNIFTY …; null when [arg] is not one of the index names. */
        fun index(arg: String): String? = when (arg.uppercase().replace(Regex("""[^A-Z0-9]"""), "")) {
            "", "NIFTY", "NIFTY50" -> "NIFTY"
            "BANKNIFTY", "NIFTYBANK", "BANK" -> "BANKNIFTY"
            "FINNIFTY", "NIFTYFIN", "NIFTYFINSERVICE", "NIFTYFINANCIALSERVICES" -> "FINNIFTY"
            "MIDCPNIFTY", "MIDCAPNIFTY", "NIFTYMIDCAPSELECT", "NIFTYMIDSELECT" -> "MIDCPNIFTY"
            else -> null
        }

        fun infoPath(symbol: String) = "option-chain-contract-info?symbol=" + WebData.encode(symbol)
        fun chainPath(symbol: String, expiry: LocalDate) =
            "option-chain-v3?type=" + (if (symbol in INDICES) "Indices" else "Equity") + "&symbol=" + WebData.encode(symbol) + "&expiry=" + FEED_OUT.format(expiry)

        /** Expiry dates in a contract-info (or chain) reply that have not passed, soonest first. */
        fun expiries(json: String, today: LocalDate): List<LocalDate> {
            val root = J.obj(json) ?: return emptyList()
            val arr = (root.get("expiryDates") ?: root.o("records")?.get("expiryDates"))?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            return arr.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.let(::parseDate) }.filter { !it.isBefore(today) }.distinct().sorted()
        }

        /** Chain JSON → chain, or null when the reply is unreadable or carries no strikes (NSE answers that way for a wrong expiry and sometimes off-hours). */
        fun parse(symbol: String, json: String): DerivChain? {
            val rec = J.obj(json)?.o("records") ?: return null
            val data = rec.a("data")
            fun leg(o: com.google.gson.JsonObject?) = if (o == null) DerivMath.Leg() else
                DerivMath.Leg(o.d("openInterest") ?: 0.0, o.d("changeinOpenInterest") ?: 0.0, o.d("impliedVolatility") ?: 0.0, o.d("lastPrice") ?: 0.0, o.d("totalTradedVolume") ?: 0.0)
            val rows = data.mapNotNull { r -> val k = r.d("strikePrice") ?: return@mapNotNull null; if (k <= 0) null else DerivMath.Strike(k, leg(r.o("CE")), leg(r.o("PE"))) }.sortedBy { it.strike }
            val spot = rec.d("underlyingValue")?.takeIf { it > 0 } ?: data.firstNotNullOfOrNull { (it.o("CE") ?: it.o("PE"))?.d("underlyingValue")?.takeIf { v -> v > 0 } } ?: return null
            val expiry = data.firstNotNullOfOrNull { parseDate(it.s("expiryDates")) } ?: return null
            if (rows.isEmpty()) return null
            return DerivChain(symbol, expiry, spot, rec.s("timestamp"), rows)
        }

        /** Why a chain reply could not be used, in words for the user. */
        fun problem(symbol: String, json: String): String = when {
            json.isBlank() -> "Couldn't reach NSE for the $symbol option chain. Try again in a moment."
            J.obj(json)?.o("records") == null -> "Couldn't read the $symbol option chain from NSE."
            else -> "NSE returned an empty option chain for $symbol. This feed can be empty outside market hours or for an expiry with no contracts — try again later, or /expiries $symbol."
        }

        /** Pure text → card plumbing shared by the features: parse, or explain. */
        inline fun card(symbol: String, json: String, build: (DerivChain) -> String): String = parse(symbol, json)?.let(build) ?: Card.error(problem(symbol, json))

        /** NIFTY for a blank argument, an index name, or whatever [Fetch.symbol] makes of a company name. */
        suspend fun symbol(arg: String, f: Fetch): String? = index(arg) ?: f.symbol(arg)?.uppercase()

        /**
         * Fetches the contract list, then the chains of the nearest [count] expiries that have data
         * (looking one expiry further if one comes back empty). Never throws for "no data".
         */
        suspend fun load(arg: String, f: Fetch, usage: String, count: Int = 1): Loaded {
            val sym = symbol(arg, f) ?: return Loaded("", emptyList(), "Which index or stock? Try $usage.")
            val info = f.nse(infoPath(sym))
            if (info.isBlank()) return Loaded(sym, emptyList(), "Couldn't reach NSE for $sym option contracts. Try again in a moment.")
            val dates = expiries(info, f.today)
            if (dates.isEmpty()) return Loaded(sym, emptyList(), "NSE lists no option contracts for $sym. Options exist for NIFTY, BANKNIFTY, FINNIFTY and F&O stocks — try $usage.")
            val chains = mutableListOf<DerivChain>(); var last = ""
            for (d in dates.take(count + 1)) {
                if (chains.size == count) break
                last = f.nse(chainPath(sym, d))
                parse(sym, last)?.let { chains += it }
            }
            return if (chains.isEmpty()) Loaded(sym, chains, problem(sym, last)) else Loaded(sym, chains, null)
        }
    }
}
