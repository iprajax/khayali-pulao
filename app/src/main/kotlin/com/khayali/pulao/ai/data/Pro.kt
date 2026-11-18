package com.khayali.pulao.ai.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.abs

/** NSE's surveillance lists — ASM (long/short term), GSM and ESM: stocks the exchange watches for unusual risk. */
object Surveillance {
    data class Flag(val symbol: String, val list: String, val stage: String, val desc: String)

    private fun JsonObject.s(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun rows(e: JsonElement?): List<JsonObject> = when {
        e == null || e.isJsonNull -> emptyList()
        e.isJsonArray -> e.asJsonArray.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }
        e.isJsonObject -> e.asJsonObject.let { o -> if (o.has("data")) rows(o.get("data")) else o.entrySet().flatMap { rows(it.value) } }
        else -> emptyList()
    }

    /** All three lists → symbol → flags (one per list and stage, duplicates dropped). */
    fun parse(asm: String, gsm: String, esm: String): Map<String, List<Flag>> {
        fun read(json: String, list: (JsonObject) -> String, stage: (JsonObject) -> String) =
            rows(runCatching { JsonParser.parseString(json) }.getOrNull()).mapNotNull { o -> o.s("symbol").ifBlank { null }?.let { Flag(it, list(o), stage(o), o.s("survDesc")) } }
        val all = read(asm, { if (it.s("survCode").startsWith("LT")) "Long-term ASM" else "Short-term ASM" }, { it.s("asmSurvIndicator") }) +
            read(gsm, { "GSM" }, { "Stage " + it.s("gsmStage") }) + read(esm, { "ESM" }, { it.s("esmSurvIndicator") })
        return all.distinctBy { it.symbol + it.list + it.stage }.groupBy { it.symbol }
    }

    /** What a list means, in plain words. */
    fun meaning(list: String) = when {
        list.contains("ASM") -> "Additional Surveillance: higher margins for unusual price or volume moves"
        list == "GSM" -> "Graded Surveillance: fundamentals or disclosures raise concern; trading can be restricted"
        list == "ESM" -> "Enhanced Surveillance (small companies): trade-for-trade settlement and price bands"
        else -> "On an NSE surveillance list"
    }
}

/** NSE's participant-wise open interest (Client, DII, FII, Pro) — who is long or short index futures and options. */
object Positioning {
    data class Row(val who: String, val futIdxLong: Long, val futIdxShort: Long, val callLong: Long, val putLong: Long, val callShort: Long, val putShort: Long) {
        /** Share of index-futures contracts that are long. */
        val longPct get() = if (futIdxLong + futIdxShort == 0L) 0.0 else futIdxLong * 100.0 / (futIdxLong + futIdxShort)
    }

    fun url(d: java.time.LocalDate) = "https://${Bhavcopy.HOST}/content/nsccl/fao_participant_oi_${d.format(java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy"))}.csv"

    fun parse(csv: String): Map<String, Row> {
        val lines = csv.lineSequence().map { it.trim().trimEnd('\r') }.filter { it.isNotBlank() }.toList()
        val h = lines.indexOfFirst { it.startsWith("Client Type") }; if (h < 0) return emptyMap()
        val head = lines[h].split(',').map { it.trim().lowercase() }
        fun col(n: String) = head.indexOfFirst { it.startsWith(n) }
        val c = listOf(col("future index long"), col("future index short"), col("option index call long"), col("option index put long"), col("option index call short"), col("option index put short"))
        if (c.any { it < 0 }) return emptyMap()
        return lines.drop(h + 1).mapNotNull { l -> val f = l.split(',').map { it.trim() }
            val v = c.map { f.getOrNull(it)?.toLongOrNull() ?: return@mapNotNull null }
            f[0] to Row(f[0], v[0], v[1], v[2], v[3], v[4], v[5]) }.toMap()
    }
}

/**
 * The plain-English screener: "liquid stocks above their 200-day average near a 52-week high with a volume spike".
 * Words become checks over each stock's numbers on the latest day; the card shows the checks it understood.
 */
object Screener {
    data class Check(val label: String, val test: (Facts) -> Boolean)
    data class Facts(val sym: String, val px: Double, val r1w: Double?, val r1m: Double?, val r3m: Double?, val r1y: Double?, val vs50: Double?, val vs200: Double?,
                     val sma50: Double?, val sma200: Double?, val fromHigh: Double?, val fromLow: Double?, val rsi: Double?, val volX: Double?, val vol: Double?, val newHigh: Boolean)

    private fun num(s: String) = s.replace(",", "").toDoubleOrNull()

    /** The checks found in [q] and any words it didn't understand (shown so the user can rephrase). */
    fun parse(q: String): List<Check> {
        val t = " " + q.lowercase().replace(Regex("[^a-z0-9.% ]"), " ").replace(Regex("\\s+"), " ") + " "
        val out = mutableListOf<Check>()
        fun dma(n: Int) = Regex("""\b$n( ?-?day| ?dma| ?d| day moving average| sma| ema| average)""")
        if (Regex("above (their |its |the )?" + dma(200).pattern).containsMatchIn(t)) out += Check("Above 200-day average") { (it.vs200 ?: -1.0) > 0 }
        if (Regex("below (their |its |the )?" + dma(200).pattern).containsMatchIn(t)) out += Check("Below 200-day average") { (it.vs200 ?: 1.0) < 0 }
        if (Regex("above (their |its |the )?" + dma(50).pattern).containsMatchIn(t)) out += Check("Above 50-day average") { (it.vs50 ?: -1.0) > 0 }
        if (Regex("below (their |its |the )?" + dma(50).pattern).containsMatchIn(t)) out += Check("Below 50-day average") { (it.vs50 ?: 1.0) < 0 }
        if (t.contains("golden cross") || t.contains("uptrend")) out += Check("50-day above 200-day (uptrend)") { (it.sma50 ?: 0.0) > (it.sma200 ?: Double.MAX_VALUE) }
        if (t.contains("death cross") || t.contains("downtrend")) out += Check("50-day below 200-day (downtrend)") { (it.sma50 ?: Double.MAX_VALUE) < (it.sma200 ?: 0.0) }
        if (Regex("new (52|fifty two)( ?-?week)? high").containsMatchIn(t)) out += Check("New 52-week high") { it.newHigh }
        else if (Regex("near (a |the |their |its )?(52|fifty two)( ?-?week)? high|near high").containsMatchIn(t)) out += Check("Within 5% of 52-week high") { (it.fromHigh ?: -1.0) >= -0.05 }
        if (Regex("near (a |the |their |its )?(52|fifty two)( ?-?week)? low|near low").containsMatchIn(t)) out += Check("Within 10% of 52-week low") { (it.fromLow ?: 1.0) <= 0.10 }
        Regex("""(down|fallen|off|below) (more than |over |at least )?(\d+)%? from (the |their |its )?(52 ?week )?high""").find(t)?.let { m -> val v = num(m.groupValues[3])!! / 100
            out += Check("${m.groupValues[3]}%+ below 52-week high") { (it.fromHigh ?: 0.0) <= -v } }
        if (t.contains("oversold")) out += Check("RSI below 30 (oversold)") { (it.rsi ?: 50.0) < 30 }
        if (t.contains("overbought")) out += Check("RSI above 70 (overbought)") { (it.rsi ?: 50.0) > 70 }
        Regex("""rsi (below|under|less than|<) (\d+)""").find(t)?.let { m -> val v = num(m.groupValues[2])!!; out += Check("RSI below ${v.toInt()}") { (it.rsi ?: 100.0) < v } }
        Regex("""rsi (above|over|more than|>) (\d+)""").find(t)?.let { m -> val v = num(m.groupValues[2])!!; out += Check("RSI above ${v.toInt()}") { (it.rsi ?: 0.0) > v } }
        Regex("""volume (above|over|more than|at least)? ?(\d+(\.\d+)?) ?x""").find(t)?.let { m -> val v = num(m.groupValues[2])!!; out += Check("Volume ${m.groupValues[2]}× usual or more") { (it.volX ?: 0.0) >= v } }
            ?: run { if (Regex("volume (spike|surge|breakout|jump)|high volume|heavy volume|unusual volume").containsMatchIn(t)) out += Check("Volume 2× usual or more") { (it.volX ?: 0.0) >= 2 } }
        if (Regex("low (volatility|risk)|calm|steady|stable").containsMatchIn(t)) out += Check("Volatility under 25%") { (it.vol ?: 1.0) < 0.25 }
        if (Regex("high volatility|volatile").containsMatchIn(t)) out += Check("Volatility over 50%") { (it.vol ?: 0.0) > 0.5 }
        Regex("""(up|gained|risen|rose|rallied) (more than |over |at least |by )?(\d+)%? (in|over) (a |the last |last |past |the past )?(week|month|quarter|3 months|year|12 months)""").find(t)?.let { m ->
            val v = num(m.groupValues[3])!! / 100; val per = m.groupValues[6]
            out += Check("Up ${m.groupValues[3]}%+ in a ${per.replace("3 months", "quarter").replace("12 months", "year")}") { f -> (when { per.startsWith("week") -> f.r1w; per.startsWith("month") -> f.r1m; per.startsWith("quarter") || per.startsWith("3") -> f.r3m; else -> f.r1y } ?: -1.0) >= v } }
        Regex("""(down|fallen|fell|dropped|lost) (more than |over |at least |by )?(\d+)%? (in|over) (a |the last |last |past |the past )?(week|month|quarter|3 months|year|12 months)""").find(t)?.let { m ->
            val v = num(m.groupValues[3])!! / 100; val per = m.groupValues[6]
            out += Check("Down ${m.groupValues[3]}%+ in a ${per.replace("3 months", "quarter").replace("12 months", "year")}") { f -> (when { per.startsWith("week") -> f.r1w; per.startsWith("month") -> f.r1m; per.startsWith("quarter") || per.startsWith("3") -> f.r3m; else -> f.r1y } ?: 1.0) <= -v } }
        if (Regex("momentum|strong (stocks|performers)|leaders|winners").containsMatchIn(t) && out.none { it.label.startsWith("Up") }) out += Check("Up 20%+ in a year") { (it.r1y ?: -1.0) >= 0.2 }
        Regex("""(price )?(above|over|more than) (rs |₹)?(\d+) ?(rupees|rs)?\b(?! ?(day|dma|x|%))""").find(t)?.takeIf { it.groupValues[1].isNotEmpty() || it.groupValues[3].isNotEmpty() || it.groupValues[5].isNotEmpty() }?.let { m ->
            val v = num(m.groupValues[4])!!; out += Check("Price above ₹${m.groupValues[4]}") { it.px > v } }
        return out
    }

    /** One stock's numbers on day [d] (null when it didn't trade). */
    fun facts(p: Panel, s: Int, d: Int): Facts? {
        val px = p.adj[s][d].toDouble(); if (px.isNaN()) return null
        val sma50 = Market.sma(p, s, d, 50); val sma200 = Market.sma(p, s, d, 200)
        val w = (maxOf(0, d - 251)..d).map { p.adj[s][it] }.filter { !it.isNaN() }
        val prevHi = (maxOf(0, d - 251) until d).map { p.adj[s][it] }.filter { !it.isNaN() }.maxOrNull()
        val med = (maxOf(0, d - 20) until d).map { p.volume[s][it] }.filter { !it.isNaN() }.sorted().let { if (it.isEmpty()) null else it[it.size / 2].toDouble() }
        return Facts(p.symbols[s], px, Market.change(p, s, d, 5), Market.change(p, s, d, 21), Market.change(p, s, d, 63), Market.change(p, s, d, 252),
            sma50?.let { px / it - 1 }, sma200?.let { px / it - 1 }, sma50, sma200, w.maxOrNull()?.let { px / it - 1 }, w.minOrNull()?.let { px / it - 1 },
            Market.rsi(p, s, d), med?.let { m -> p.volume[s][d].toDouble().takeIf { !it.isNaN() && m > 0 }?.div(m) }, Market.vol(p, s, d, 60),
            prevHi != null && w.size > 120 && px > prevHi)
    }
}

/** A portfolio's health, 0–100, from six parts — a description of risk and diversification, not a rating of the stocks. */
object HealthScore {
    data class Part(val name: String, val score: Double, val detail: String)
    data class Result(val score: Double, val parts: List<Part>)
    private fun clamp(x: Double) = x.coerceIn(0.0, 100.0)

    /**
     * [weights] symbol → share of the portfolio (sums to 1); [sectorOf] symbol → sector; [beta], [vol] (annualised),
     * [fromHigh] (≤ 0) and [r1y] per symbol where known; [nifty1y] the benchmark's 1-year change; [flagged] symbols on a surveillance list.
     */
    fun compute(weights: Map<String, Double>, sectorOf: Map<String, String>, beta: Map<String, Double>, vol: Map<String, Double>, fromHigh: Map<String, Double>,
                r1y: Map<String, Double>, nifty1y: Double?, flagged: Set<String> = emptySet()): Result {
        fun avg(m: Map<String, Double>) = weights.entries.filter { it.key in m }.let { e -> val w = e.sumOf { it.value }; if (w == 0.0) null else e.sumOf { it.value * m.getValue(it.key) } / w }
        val hhi = weights.values.sumOf { it * it }; val effN = if (hhi > 0) 1 / hhi else 0.0
        val top = weights.maxByOrNull { it.value }
        val bySector = weights.entries.groupBy { sectorOf[it.key] ?: "Other" }.mapValues { e -> e.value.sumOf { it.value } }
        val topSec = bySector.maxByOrNull { it.value }
        val b = avg(beta); val v = avg(vol); val dd = avg(fromHigh); val ret = avg(r1y)
        val flagShare = weights.filterKeys { it in flagged }.values.sum()
        val parts = listOf(
            Part("Diversification", clamp(effN / 15 * 100), "like owning ${"%.1f".format(effN)} equal stocks (${weights.size} held)"),
            Part("Largest holding", clamp((0.4 - (top?.value ?: 0.0)) / 0.35 * 100), "${top?.key ?: "—"} is ${((top?.value ?: 0.0) * 100).toInt()}% of the portfolio"),
            Part("Sector spread", clamp((0.6 - (topSec?.value ?: 0.0)) / 0.45 * 100), "${topSec?.key?.removePrefix("Nifty ") ?: "—"} is ${((topSec?.value ?: 0.0) * 100).toInt()}%"),
            Part("Market sensitivity", b?.let { clamp((1.6 - it) / 0.9 * 100) } ?: 50.0, b?.let { "beta ${"%.2f".format(it)} to the Nifty 50" } ?: "beta unknown"),
            Part("Calm", v?.let { clamp((0.6 - it) / 0.4 * 100) } ?: 50.0, v?.let { "average volatility ${(it * 100).toInt()}% a year" } ?: "volatility unknown"),
            Part("Distance from highs", dd?.let { clamp((it + 0.4) / 0.4 * 100) } ?: 50.0, dd?.let { "on average ${(abs(it) * 100).toInt()}% below 52-week highs" } ?: "unknown"),
        ) + listOfNotNull(
            if (ret != null && nifty1y != null) Part("Against the Nifty", clamp(50 + (ret - nifty1y) * 250), "1 year ${"%+.1f".format(ret * 100)}% vs Nifty ${"%+.1f".format(nifty1y * 100)}%") else null,
            if (flagged.isNotEmpty() || flagShare > 0) Part("Surveillance", clamp(100 - flagShare * 300), "${(flagShare * 100).toInt()}% in stocks on NSE surveillance lists") else null,
        )
        return Result(parts.map { it.score }.average(), parts)
    }
}

/** Tax-loss harvesting maths: unrealised losses that could offset gains already booked this financial year. */
object Harvest {
    data class Lot(val symbol: String, val invested: Double, val current: Double) { val pnl get() = current - invested }
    data class Plan(val losses: List<Lot>, val gains: List<Lot>, val totalLoss: Double, val offset: Double, val saveStcg: Double, val saveLtcg: Double, val daysLeft: Long)

    /** [realised] gains already booked this year (₹); STCG at 20%, LTCG at 12.5% above the ₹1.25 lakh exemption. */
    fun plan(lots: List<Lot>, realised: Double, today: java.time.LocalDate): Plan {
        val losses = lots.filter { it.pnl < 0 }.sortedBy { it.pnl }; val gains = lots.filter { it.pnl > 0 }.sortedByDescending { it.pnl }
        val total = -losses.sumOf { it.pnl }; val offset = minOf(total, realised.coerceAtLeast(0.0))
        val fyEnd = java.time.LocalDate.of(if (today.monthValue > 3) today.year + 1 else today.year, 3, 31)
        val ltcgTaxable = (realised - 125_000).coerceAtLeast(0.0)
        return Plan(losses, gains, total, offset, offset * 0.20, minOf(offset, ltcgTaxable) * 0.125, java.time.temporal.ChronoUnit.DAYS.between(today, fyEnd))
    }
}

