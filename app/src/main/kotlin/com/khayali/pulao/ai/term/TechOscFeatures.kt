package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.TechFeed.Sym
import com.khayali.pulao.ai.term.TechMath.r2
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
private fun signed2(v: Double) = (if (v < 0) "−" else "") + String.format(Locale.US, "%.2f", abs(v))

/** /rsi SYMBOL — 14-day RSI (Wilder) over six months with the 30 and 70 zones, and what the latest reading describes. */
object TechRsiFeature : Feature {
    override val id = "rsi"
    override val usage = "/rsi INFY"
    override val help = "14-day RSI with the 30/70 zones and what the latest reading means in plain words"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\b(rsi|relative strength index|overbought|oversold)\b"""))
    private const val SHOWN = 126

    override suspend fun run(arg: String, f: Fetch): String {
        val sym = TechFeed.resolve(arg, f) ?: return TechFeed.which(usage)
        return build(sym, TechFeed.chart(f, sym, "1y", "1d"))
    }

    /** Short label and a plain description of an RSI reading. Describes; never advises. */
    fun reading(r: Double): Pair<String, String> = when {
        r >= 70 -> "Above 70" to "above 70, the area chartists call overbought: gains have dominated the last 14 sessions"
        r <= 30 -> "Below 30" to "below 30, the area chartists call oversold: losses have dominated the last 14 sessions"
        r >= 50 -> "Upper half of the neutral 30–70 range" to "in the upper half of the neutral 30–70 range: gains have outweighed losses over the last 14 sessions"
        else -> "Lower half of the neutral 30–70 range" to "in the lower half of the neutral 30–70 range: losses have outweighed gains over the last 14 sessions"
    }

    fun build(sym: Sym, json: String): String {
        val b = TechFeed.bars(json) ?: return TechFeed.noData(sym, usage)
        val s = b.s; val rsi = TechMath.rsi(s.close, 14)
        val now = rsi.lastOrNull() ?: return Card.error("${sym.label} has only ${s.size} sessions of history on the quote feed — a 14-day RSI needs at least 15.")
        val from = maxOf(rsi.indexOfFirst { it != null }, s.size - SHOWN); val win = rsi.subList(from, s.size).map { it!! }
        val (short, long) = reading(now); val weekAgo = rsi.getOrNull(s.size - 6)
        val hiI = win.indices.maxByOrNull { win[it] }!!; val loI = win.indices.minByOrNull { win[it] }!!
        return Card("${sym.label} · RSI (14-day)", "violet")
            .headline("RSI " + f1(now), "$short · ${b.whenText}")
            .viz("oscillator", 220, mapOf(
                "t" to s.dates.subList(from, s.size).map { it.toString() }, "line" to r2(win), "name" to "RSI (14)", "min" to 0, "max" to 100, "digits" to 1,
                "zones" to listOf(mapOf("from" to 70, "to" to 100, "color" to "red", "label" to "Above 70"), mapOf("from" to 0, "to" to 30, "color" to "green", "label" to "Below 30")),
                "thresholds" to listOf(mapOf("value" to 70, "label" to "70"), mapOf("value" to 30, "label" to "30")),
                "yLabel" to "RSI (0–100)", "xLabel" to "Date"),
                "14-day RSI (0–100) by date, last ${win.size} sessions. Shaded: above 70 and below 30. Drag to read values.")
            .stats(*listOfNotNull("RSI now" to f1(now), weekAgo?.let { "5 sessions ago" to f1(it) },
                "Highest in view" to "${f1(win[hiI])} · ${TechFeed.day(s.dates[from + hiI])}", "Lowest in view" to "${f1(win[loI])} · ${TechFeed.day(s.dates[from + loI])}",
                "Sessions above 70" to "${win.count { it >= 70 }} of ${win.size}", "Sessions below 30" to "${win.count { it <= 30 }} of ${win.size}").toTypedArray())
            .note("The reading is $long. RSI only measures the balance of recent gains and losses — a share can stay above 70 or below 30 for weeks, and the reading says nothing about value or what comes next. ${TechFeed.DELAY}")
            .sources(TechFeed.SOURCE).pills("/macd ${sym.label}", "/ta ${sym.label}", "/levels ${sym.label}", "/volatility ${sym.label}")
            .put("symbol", sym.label).put("rsi", r2(now)).put("as_of", b.asOf.toString())
            .json("${sym.label}'s 14-day RSI was ${f1(now)} ${b.whenText}, $long" + (weekAgo?.let { "; it was ${f1(it)} five sessions earlier" } ?: "") + ". Source: ${TechFeed.SOURCE}.")
    }
}

/** /macd SYMBOL — MACD (12, 26, 9): the line, its signal line and the histogram over six months. */
object TechMacdFeature : Feature {
    override val id = "macd"
    override val usage = "/macd INFY"
    override val help = "MACD (12, 26, 9): MACD line, signal line and histogram, with the latest crossover"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\bmacd\b"""))
    private const val SHOWN = 126

    override suspend fun run(arg: String, f: Fetch): String {
        val sym = TechFeed.resolve(arg, f) ?: return TechFeed.which(usage)
        return build(sym, TechFeed.chart(f, sym, "1y", "1d"))
    }

    fun build(sym: Sym, json: String): String {
        val b = TechFeed.bars(json) ?: return TechFeed.noData(sym, usage)
        val s = b.s; val m = TechMath.macd(s.close)
        val h = m.hist.lastOrNull() ?: return Card.error("${sym.label} has only ${s.size} sessions of history on the quote feed — MACD (12, 26, 9) needs at least 34.")
        val line = m.line.last()!!; val sig = m.signal.last()!!
        val first = m.hist.indexOfFirst { it != null }; val from = maxOf(first, s.size - SHOWN)
        // the latest session on which the histogram changed sign = the MACD line crossing its signal line
        val cross = (s.size - 1 downTo first + 1).firstOrNull { (m.hist[it]!! >= 0) != (m.hist[it - 1]!! >= 0) }
        val side = if (h >= 0) "above" else "below"; val zero = if (line >= 0) "above" else "below"
        val crossText = cross?.let { "it moved $side the signal line on ${TechFeed.day(s.dates[it])}, ${s.size - 1 - it} sessions ago" }
        val unit = if (sym.index) "points" else "₹"
        return Card("${sym.label} · MACD (12, 26, 9)", "blue")
            .headline("MACD " + signed2(line), "${side.replaceFirstChar { it.uppercase() }} its signal line (${signed2(sig)}) and $zero zero · ${b.whenText}")
            .viz("oscillator", 230, mapOf(
                "t" to s.dates.subList(from, s.size).map { it.toString() }, "line" to r2(m.line.subList(from, s.size)), "name" to "MACD (12, 26)",
                "signal" to r2(m.signal.subList(from, s.size)), "signalName" to "Signal (9)", "hist" to r2(m.hist.subList(from, s.size)), "histName" to "Histogram", "digits" to 2,
                "yLabel" to "MACD ($unit)", "xLabel" to "Date"),
                "MACD in $unit by date, last ${s.size - from} sessions. Blue: 12-day minus 26-day exponential average. Orange: its 9-day average (signal). Bars: the gap between the two.")
            .stats(*listOfNotNull("MACD line" to signed2(line), "Signal line" to signed2(sig), "Histogram" to signed2(h),
                cross?.let { "Latest crossover" to "${if (h >= 0) "Above" else "Below"} signal · ${TechFeed.day(s.dates[it])}" }).toTypedArray())
            .note("MACD compares a faster and a slower average of the price, so it follows the price with a lag. A crossover describes a change in recent momentum; it is not a forecast. ${TechFeed.DELAY}")
            .sources(TechFeed.SOURCE).pills("/rsi ${sym.label}", "/ta ${sym.label}", "/levels ${sym.label}", "/returns ${sym.label}")
            .put("symbol", sym.label).put("as_of", b.asOf.toString())
            .json("${sym.label} MACD (12, 26, 9) ${b.whenText}: MACD line ${signed2(line)}, signal ${signed2(sig)}, histogram ${signed2(h)} — the MACD line is $side its signal line and $zero zero" +
                (crossText?.let { "; $it" } ?: "") + ". Source: ${TechFeed.SOURCE}.")
    }
}

/** /volatility SYMBOL — rolling 30-day annualised volatility over a year, and where today's reading sits in that range. */
object TechVolatilityFeature : Feature {
    override val id = "volatility"
    override val usage = "/volatility INFY"
    override val help = "Rolling 30-day annualised volatility over a year, and the current reading against its own range"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\b((historical|realised|realized|rolling) volatility|how volatile)\b"""))
    private const val WINDOW = 30
    private const val YEAR = 252

    override suspend fun run(arg: String, f: Fetch): String {
        val sym = TechFeed.resolve(arg, f) ?: return TechFeed.which(usage)
        return build(sym, TechFeed.chart(f, sym, "2y", "1d"))      // two years, so a full year of 30-day windows exists
    }

    fun build(sym: Sym, json: String): String {
        val b = TechFeed.bars(json) ?: return TechFeed.noData(sym, usage)
        val s = b.s; val vol = TechMath.rollingVol(s.close, WINDOW)
        val now = vol.lastOrNull() ?: return Card.error("${sym.label} has only ${s.size} sessions of history on the quote feed — 30-day volatility needs at least 31.")
        val from = maxOf(WINDOW, s.size - YEAR); val win = vol.subList(from, s.size).map { it!! }
        val loI = win.indices.minByOrNull { win[it] }!!; val hiI = win.indices.maxByOrNull { win[it] }!!; val lo = win[loI]; val hi = win[hiI]; val med = TechMath.median(win)
        val pos = TechMath.position(now, lo, hi)
        val third = when { pos < 100.0 / 3 -> "lower third"; pos <= 200.0 / 3 -> "middle third"; else -> "upper third" }
        val range = if (win.size >= YEAR - 2) "1-year range" else "range over the last ${win.size} sessions"
        fun p(v: Double) = f1(v) + "%"
        return Card("${sym.label} · 30-day volatility (annualised)", "saffron")
            .headline(p(now) + " a year", "In the $third of its $range (${p(lo)} to ${p(hi)}) · ${b.whenText}")
            .viz("oscillator", 220, mapOf(
                "t" to s.dates.subList(from, s.size).map { it.toString() }, "line" to r2(win), "name" to "30-day volatility", "color" to "saffron", "suffix" to "%", "digits" to 1,
                "thresholds" to listOf(mapOf("value" to r2(hi), "label" to "High ${p(hi)}"), mapOf("value" to r2(med), "label" to "Median ${p(med)}"), mapOf("value" to r2(lo), "label" to "Low ${p(lo)}")),
                "yLabel" to "Volatility (% a year)", "xLabel" to "Date"),
                "Annualised volatility (%) of the previous 30 daily returns, by date, last ${win.size} sessions. Dashed: the high, median and low of that period.")
            .stats("Now" to p(now), "Median" to p(med), "Low" to "${p(lo)} · ${TechFeed.day(s.dates[from + loI])}", "High" to "${p(hi)} · ${TechFeed.day(s.dates[from + hiI])}",
                "Position in range" to "${Math.round(pos)}% (0 = low, 100 = high)", "Typical daily move" to "±" + String.format(Locale.US, "%.2f", now / sqrt(252.0)) + "%")
            .note("Volatility measures how much the price has been moving, not in which direction. This is historical (realised) volatility from daily closes, not the implied volatility of options. ${TechFeed.DELAY}")
            .sources(TechFeed.SOURCE).pills("/drawdown ${sym.label}", "/ta ${sym.label}", "/returns ${sym.label}", "/compare ${sym.label} NIFTY")
            .put("symbol", sym.label).put("as_of", b.asOf.toString())
            .json("${sym.label}'s 30-day annualised volatility was ${p(now)} ${b.whenText}, in the $third of its $range of ${p(lo)} to ${p(hi)} (median ${p(med)}). Source: ${TechFeed.SOURCE}.")
    }
}
