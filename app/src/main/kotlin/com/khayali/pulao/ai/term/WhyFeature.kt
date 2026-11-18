package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.data.Filings
import com.khayali.pulao.ai.data.Market
import com.khayali.pulao.ai.data.Sectors
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * /why SYMBOL — why a stock moved today: its move split into market, sector and stock-specific parts, then the
 * evidence ranked (unusual volume, size of the move for this stock, 52-week levels, streaks, filings, news).
 */
object WhyFeature : Feature {
    override val id = "why"; override val usage = "/why INFY"; override val group = Group.STOCK; override val needsWeb = false
    override val requires = "market"
    override val help = "Why a stock moved: market, sector and stock-specific parts, then the evidence (volume, filings, news)"
    override val method = "From your NSE history: the stock's last-day return is split into a market part (its beta to the Nifty 50 over 120 sessions × the Nifty's move), " +
        "a sector part (its NSE sector index's move minus the Nifty's) and what's left — the stock's own move. Evidence is ranked: volume against its 20-day median, " +
        "the move against its own 60-day volatility, new 52-week highs or lows, streaks; with web on, its NSE filings over two days and news stories that name it."
    override val triggers = listOf(Regex("""(?i)^why (is|did|has) @?(?<sym>[A-Za-z][A-Za-z0-9&\-]{1,19}) (moving|move|moved|up|down|rising|falling|fall(ing|en)?|ris(e|en|ing)|jump(ing|ed)?|drop(ping|ped)?|crash(ing|ed)?|rall(y|ying|ied))( today)?\??$"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val q = arg.trim().removePrefix("@").ifBlank { return Card.error("Which stock? Try /why INFY.") }
        val sym = f.resolve(q) ?: q.uppercase()
        val p = f.market(400) ?: return Card.error("This uses your NSE history. Type /data 1y to download it.", "/data 1y")
        val s = p.of(sym) ?: return Card.error("$sym isn't in your NSE history (not listed, or too thinly traded).", "/news $sym")
        val d = p.days - 1
        val r = p.ret[s][d].toDouble().takeIf { !it.isNaN() } ?: return Card.error("$sym didn't trade on ${p.dates[d]}.", "/chart $sym 1m")
        val book = f.sectors()
        val nifty = book?.series?.get(Sectors.BENCHMARK)
        val niftyRet = nifty?.change(Sectors.Period.D1, p.dates[d])
        // beta to the Nifty 50 over 120 sessions
        val beta = nifty?.let { n -> val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            for (t in maxOf(1, d - 119)..d) { val y = p.ret[s][t]; if (y.isNaN()) continue
                val a = n.at(p.dates[t]); val b = n.at(p.dates[t - 1]); if (a == null || b == null || a.first != p.dates[t] || b.second <= 0) continue
                xs += a.second / b.second - 1; ys += y.toDouble() }
            if (xs.size < 60) null else { val mx = xs.average(); val my = ys.average(); val v = xs.sumOf { (it - mx) * (it - mx) }
                if (v == 0.0) null else xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / v } }
        val sector = book?.members?.entries?.firstOrNull { (idx, m) -> sym in m && idx != "Nifty Financial Services" && idx != "Nifty PSE" && idx != "Nifty Commodities" && idx != "Nifty Infrastructure" }?.key
            ?: book?.members?.entries?.firstOrNull { sym in it.value }?.key
        val sectorRet = sector?.let { book?.series?.get(it)?.change(Sectors.Period.D1, p.dates[d]) }
        val market = if (beta != null && niftyRet != null) beta * niftyRet else null
        val sectorPart = if (sectorRet != null && niftyRet != null) sectorRet - niftyRet else null
        val own = r - (market ?: 0.0) - (sectorPart ?: 0.0)

        // evidence, strongest first
        data class E(val title: String, val detail: String, val weight: Double, val tone: String = "")
        val ev = mutableListOf<E>()
        val medVol = (maxOf(0, d - 20) until d).map { p.volume[s][it] }.filter { !it.isNaN() }.sorted().let { if (it.isEmpty()) Float.NaN else it[it.size / 2] }
        val volX = (p.volume[s][d] / medVol).toDouble()
        if (!volX.isNaN() && volX >= 1.5) ev += E("Volume ${"%.1f".format(volX)}× usual", "against its 20-day median — ${if (volX >= 4) "a very unusual day" else "heavier than normal trading"}", volX)
        val sig = (d - 60 until d).map { p.ret[s][it] }.filter { !it.isNaN() }.map { it.toDouble() }.let { xs -> if (xs.size < 30) null else { val m = xs.average(); sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1)) } }
        if (sig != null && sig > 0) { val z = abs(r) / sig; if (z >= 1.5) ev += E("A ${"%.1f".format(z)}-sigma move", "bigger than ${if (z >= 3) "almost every" else "most"} day in the last three months for this stock", z) }
        val hi = (maxOf(0, d - 252) until d).map { p.adj[s][it] }.filter { !it.isNaN() }
        if (hi.size > 120) { if (p.adj[s][d] > hi.max()) ev += E("New 52-week high", "first close above last year's high", 3.0, "up") else if (p.adj[s][d] < hi.min()) ev += E("New 52-week low", "first close below last year's low", 3.0, "down") }
        var k = 0; var dir = 0; for (t in d downTo maxOf(0, d - 15)) { val x = p.ret[s][t]; if (x.isNaN() || x == 0f) break; val sg = if (x > 0) 1 else -1; if (dir == 0) dir = sg; if (sg != dir) break; k++ }
        if (k >= 4) ev += E("${k} ${if (dir > 0) "up" else "down"} days in a row", "a streak", k / 2.0)
        if (sectorPart != null && abs(sectorPart) >= 0.005 && sector != null) ev += E("${sector.removePrefix("Nifty ")} sector ${Sec.p(sectorRet, 2)}", "the sector moved ${Sec.p(sectorPart, 1)} more than the Nifty", abs(sectorPart) * 100)
        if (market != null && abs(market) >= 0.004) ev += E("The market ${Sec.p(niftyRet, 2)}", "with beta ${"%.2f".format(beta)}, worth about ${Sec.p(market, 1)} on its own", abs(market) * 100)
        // with web on: filings over two days and news that names it
        var filed = 0; var told = 0
        if (f.webOn) {
            val dmy = DateTimeFormatter.ofPattern("dd-MM-yyyy")
            val fl = Filings.parseNseApi(runCatching { f.nse("corporate-announcements?index=equities&symbol=$sym&from_date=${f.today.minusDays(2).format(dmy)}&to_date=${f.today.format(dmy)}") }.getOrDefault(""))
            fl.take(3).forEach { x -> filed++; ev += E("Filed: ${x.subject.ifBlank { x.category }}", x.text.take(120).ifBlank { x.category }, 5.0 - filed * 0.1) }
            runCatching { NewsRead.stories(f, 40) }.getOrDefault(emptyList()).filter { sym in it.tickers }.take(3).forEach { st -> told++
                ev += E("News: ${st.item.title}", "${st.item.source} · ${if (st.tone > 0) "positive" else if (st.tone < 0) "negative" else "neutral"}", 4.5 - told * 0.1, if (st.tone > 0) "up" else if (st.tone < 0) "down" else "") }
        }
        val ranked = ev.sortedByDescending { it.weight }
        val main = when {
            market != null && abs(market) > abs(own) && abs(market) > abs(sectorPart ?: 0.0) -> "mostly the market"
            sectorPart != null && abs(sectorPart) > abs(own) -> "mostly its sector"
            else -> "mostly the stock itself"
        }
        val spark = (maxOf(0, d - 29)..d).map { p.adj[s][it] }.filter { !it.isNaN() }.map { it.toDouble() }
        val c = Card("$sym · why it moved · ${p.dates[d]}", if (r >= 0) "green" else "saffron")
            .headline("${Sec.p(r, 2)} — $main", listOfNotNull(sector?.let { "${it.removePrefix("Nifty ")} sector" }, beta?.let { "beta ${"%.2f".format(it)}" }, if (!volX.isNaN()) "volume ${"%.1f".format(volX)}× usual" else null).joinToString(" · "), if (r >= 0) "up" else "down")
            .movers(null, listOf(Card.Mover(sym, listOfNotNull(Market.change(p, s, d, 5)?.let { "1W ${Sec.p(it)}" }, Market.change(p, s, d, 21)?.let { "1M ${Sec.p(it)}" }).joinToString(" · "), p.adj[s][d].toDouble(), r * 100, spark, spark.firstOrNull(), sym in f.holdings.map { it.name.uppercase() })))
            .bars("Where the move came from", listOfNotNull(market?.let { Card.Bar("Market", it * 100, "beta × Nifty") }, sectorPart?.let { Card.Bar("Sector", it * 100, "sector vs Nifty") }, Card.Bar("Own", own * 100, "the stock itself")), "pts")
        if (ranked.isNotEmpty()) c.rows("The evidence, strongest first", ranked.take(7).map { Card.Row(it.title, it.detail, "", it.tone) })
        if (!f.webOn) c.note("Switch Web on to add today's filings and news about $sym.")
        return c.note("A breakdown of what moved, not a forecast or a recommendation.")
            .sources(*listOfNotNull("NSE (your copy)", if (filed > 0) "NSE filings" else null, if (told > 0) "News feeds" else null).toTypedArray())
            .put("asOf", p.dates[d].toString())
            .pills("/news $sym", "/health $sym", "/chart $sym 1m", sector?.let { "/sector ${it.removePrefix("Nifty ")} 1d" } ?: "/sectorboard")
            .json("$sym ${Sec.p(r, 2)} on ${p.dates[d]}, $main: market ${Sec.p(market, 1)}, sector ${Sec.p(sectorPart, 1)}, own ${Sec.p(own, 1)}. " +
                ranked.take(3).joinToString("; ") { it.title } + ". Source: NSE daily data on this phone" + (if (filed + told > 0) ", NSE filings and news feeds." else "."))
    }
}
