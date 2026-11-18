package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.data.Harvest
import com.khayali.pulao.ai.data.HealthScore
import com.khayali.pulao.ai.data.Market
import com.khayali.pulao.ai.data.Positioning
import com.khayali.pulao.ai.data.Screener
import com.khayali.pulao.ai.data.Sectors
import com.khayali.pulao.ai.data.Surveillance
import java.time.DayOfWeek
import kotlinx.coroutines.async
import java.time.format.DateTimeFormatter

internal object Pro {
    suspend fun flags(f: Fetch): Map<String, List<Surveillance.Flag>> =
        Surveillance.parse(runCatching { f.nse("reportASM") }.getOrDefault(""), runCatching { f.nse("reportGSM") }.getOrDefault(""), runCatching { f.nse("reportESM") }.getOrDefault(""))
    fun rupees(v: Double) = "₹" + com.khayali.pulao.Fmt.amount(v)
}

/** /redflags [SYMBOL] — NSE surveillance lists (ASM, GSM, ESM) checked against your holdings and watched stocks. */
object RedFlagsFeature : Feature {
    override val id = "redflags"; override val usage = "/redflags"; override val group = Group.PORTFOLIO
    override val help = "Red flags: your holdings and watched stocks on NSE's surveillance lists (ASM, GSM, ESM)"
    override val method = "NSE publishes which stocks are under Additional (ASM, long and short term), Graded (GSM) and Enhanced (ESM) Surveillance — higher margins, " +
        "trade-for-trade settlement or restrictions for unusual moves or weak fundamentals. The phone reads NSE's three lists and checks them against your holdings and " +
        "watched stocks, matched on the phone."
    override val triggers = listOf(Regex("""(?i)^((any |show )?(red flags?|surveillance|asm|gsm|esm)( stocks| list| on my (stocks|holdings|portfolio))?|are any of my stocks (flagged|under surveillance))\??$"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val flags = Pro.flags(f)
        if (flags.isEmpty()) return Card.error("Couldn't read NSE's surveillance lists just now.", "/redflags")
        val one = arg.trim().removePrefix("@").uppercase().takeIf { it.isNotBlank() }
        val mine = (f.holdings.map { it.name.uppercase() } + f.watched.map { it.uppercase() }).toSet()
        val byList = flags.values.flatten().groupingBy { it.list }.eachCount()
        if (one != null) {
            val fl = flags[one]
            return Card("$one · surveillance", if (fl == null) "green" else "saffron")
                .headline(if (fl == null) "Not on any NSE surveillance list" else "On ${fl.size} surveillance list${if (fl.size > 1) "s" else ""}", fl?.joinToString(" · ") { "${it.list} ${it.stage}" } ?: "ASM, GSM and ESM checked")
                .rows(null, fl.orEmpty().map { Card.Row(it.list + " · " + it.stage, it.desc + "\n" + Surveillance.meaning(it.list), "", "warn") })
                .sources("NSE surveillance").pills("/why $one", "/redflags").json(if (fl == null) "$one is not on NSE's ASM, GSM or ESM lists." else "$one is on: " + fl.joinToString("; ") { "${it.list} ${it.stage} (${it.desc})" } + ". Source: NSE.")
        }
        val hit = flags.filterKeys { it in mine }
        val c = Card("Red flags · NSE surveillance", if (hit.isEmpty()) "green" else "saffron")
            .headline(if (mine.isEmpty()) "Connect Kite or watch stocks to check yours" else if (hit.isEmpty()) "None of your stocks are flagged" else "${hit.size} of your stocks flagged",
                "${flags.size} stocks on the lists today · " + byList.entries.joinToString(" · ") { "${it.value} ${it.key}" })
            .bars("Stocks on each list", byList.entries.sortedByDescending { it.value }.map { Card.Bar(it.key, it.value.toDouble(), "${it.value}") }, "")
        if (hit.isNotEmpty()) c.rows("Your stocks", hit.map { (s, fl) -> Card.Row("★ $s", fl.joinToString("; ") { "${it.list} ${it.stage}" } + "\n" + Surveillance.meaning(fl.first().list), "", "warn") })
        return c.note("Being on a list isn't a verdict on the company — it means the exchange has tightened trading rules. Read NSE's notice before acting.")
            .sources("NSE surveillance").pills(*listOfNotNull(hit.keys.firstOrNull()?.let { "/why $it" }, "/healthscore", "/radar mine").toTypedArray())
            .json(if (mine.isEmpty()) "${flags.size} stocks are on NSE's surveillance lists today (" + byList.entries.joinToString(", ") { "${it.value} ${it.key}" } + "). Connect Kite or look at stocks in the chat to check yours."
                else if (hit.isEmpty()) "None of your ${mine.size} stocks are on NSE's surveillance lists (${flags.size} stocks listed today)." else "Flagged: " + hit.entries.joinToString("; ") { (s, fl) -> "$s — " + fl.joinToString(", ") { "${it.list} ${it.stage}" } } + ". Source: NSE.")
    }
}

/** /positioning — who's long and short index futures (FII, DII, Pro, Client), over the last few weeks. */
object PositioningFeature : Feature {
    override val id = "positioning"; override val usage = "/positioning"; override val group = Group.DERIVATIVES
    override val help = "FII, DII, Pro and Client positioning in index futures and options over recent weeks"
    override val method = "NSE's daily participant-wise open interest file (fao_participant_oi) for each recent trading day, read by this phone. Long % is index-futures long " +
        "contracts ÷ (long + short) for each participant; options show net call and put positions (long − short)."
    override val triggers = listOf(Regex("""(?i)^((fii|dii|pro|client) (positioning|long short|longs?|shorts?|position)s?|who('s| is) (long|short)( the market| nifty)?|fii (index )?futures?|long short ratio)\??$"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val days = generateSequence(f.today) { it.minusDays(1) }.filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }.take(30).toList()
        val got = kotlinx.coroutines.coroutineScope { days.map { d -> async { d to runCatching { Positioning.parse(f.get(Positioning.url(d))) }.getOrDefault(emptyMap()) } }.map { it.await() } }
            .filter { it.second.isNotEmpty() }.take(20).reversed()
        if (got.isEmpty()) return Card.error("Couldn't read NSE's positioning files just now.", "/positioning")
        val (day, now) = got.last(); val prev = got.getOrNull(got.size - 2)?.second
        val who = listOf("FII", "DII", "Pro", "Client")
        val fii = now["FII"]
        fun ch(w: String) = prev?.get(w)?.let { (now[w]?.longPct ?: 0.0) - it.longPct }
        val mood = fii?.longPct?.let { when { it < 25 -> "heavily short"; it < 40 -> "net short"; it <= 60 -> "balanced"; it <= 75 -> "net long"; else -> "heavily long" } } ?: "—"
        return Card("Positioning · index futures & options · ${Sec.d(day)}", "violet")
            .headline("FIIs are $mood index futures", "FII long ${fii?.longPct?.let { "%.0f%%".format(it) } ?: "—"}" + (ch("FII")?.let { " (${"%+.1f".format(it)} pts on the day)" } ?: ""))
            .kpis(who.mapNotNull { w -> now[w]?.let { r -> Card.Kpi("$w · LONG %", "%.0f%%".format(r.longPct), ch(w), "index futures") } })
            .viz("multiline", 200, mapOf("t" to got.map { it.first.toString() }, "yLabel" to "Long % of index futures", "xLabel" to "Date",
                "series" to who.mapIndexed { i, w -> mapOf("name" to w, "color" to listOf("blue", "green", "saffron", "violet")[i], "v" to got.map { it.second[w]?.longPct ?: Double.NaN }) }),
                "Share of each participant's index-futures contracts that are long, last ${got.size} sessions.")
            .bars("Net index options (long − short, contracts)", who.flatMap { w -> now[w]?.let { r -> listOf(Card.Bar("$w calls", (r.callLong - r.callShort).toDouble()), Card.Bar("$w puts", (r.putLong - r.putShort).toDouble())) }.orEmpty() }, "")
            .note("Positioning shows who holds which side; it can stay one-sided for weeks. Not a forecast.")
            .sources("NSE").pills("/fiidii", "/pcr NIFTY", "/mood")
            .json("On ${Sec.d(day)} FIIs held ${fii?.longPct?.let { "%.0f%%".format(it) } ?: "—"} of their index-futures contracts long ($mood); " +
                who.drop(1).mapNotNull { w -> now[w]?.let { "$w ${"%.0f".format(it.longPct)}%" } }.joinToString(", ") + ". Source: NSE participant-wise open interest.")
    }
}

/** /find <words> — a plain-English screener over your NSE history. */
object FindFeature : Feature {
    override val id = "find"; override val usage = "/find above 200 day near 52 week high volume spike"; override val group = Group.STOCK; override val needsWeb = false
    override val requires = "market"
    override val help = "Screener in plain words: e.g. above 200-day, near 52-week high, volume spike, oversold, up 20% in a year"
    override val method = "Your words are turned into checks the app understands (shown on the card): price against the 50- and 200-day averages, golden or death cross, " +
        "new or near 52-week highs and lows, distance from the high, RSI (14-day), volume against the 20-day median, 60-day volatility, returns over a week to a year, price. " +
        "Every liquid NSE stock (median daily value ≥ ₹5 crore) is checked on the latest day; add a sector name to narrow it. Matches are sorted by 1-month return."
    override val triggers = listOf(Regex("""(?i)^(find|screen for|show me|list) (stocks|shares|companies) (that are |which are |that |which |with )?.{6,}$"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val q = arg.replace(Regex("""(?i)^(stocks|shares|companies) (that are |which are |that |which |with )?"""), "").trim()
        val checks = Screener.parse(q)
        if (checks.isEmpty()) return Card.error("Try words like: above 200 day, near 52 week high, volume spike, oversold, low volatility, up 20% in a year, golden cross.", "/find above 200 day near 52 week high", "/find oversold above 200 day")
        val p = f.market(400) ?: return Card.error("This screens your NSE history. Type /data 1y to download it.", "/data 1y")
        val d = p.days - 1
        val sector = Sectors.find(q)?.takeIf { s -> s.words.any { w -> Regex("""\b${Regex.escape(w)}\b""").containsMatchIn(q.lowercase()) } }
        val members = sector?.let { f.sectors()?.members?.get(it.index)?.toSet() }
        val universe = Market.liquid(p, d).filter { members == null || p.symbols[it] in members }
        val hits = universe.mapNotNull { s -> Screener.facts(p, s, d)?.takeIf { fx -> checks.all { it.test(fx) } }?.let { s to it } }.sortedByDescending { it.second.r1m ?: -9.0 }
        val mine = f.holdings.map { it.name.uppercase() }.toSet()
        val c = Card("Screener · ${hits.size} of ${universe.size} stocks · ${p.dates[d]}", "blue")
            .headline(if (hits.isEmpty()) "Nothing matches all of these" else "${hits.size} match", checks.joinToString(" · ") { it.label } + (sector?.let { " · ${it.short} sector" } ?: ""))
            .rows("What I checked", checks.map { Card.Row("✓ " + it.label, "") })
        if (hits.isNotEmpty()) c.movers("Matches · by 1-month return", hits.take(15).map { (s, fx) ->
            val spark = (maxOf(0, d - 29)..d).map { p.adj[s][it] }.filter { !it.isNaN() }.map { it.toDouble() }
            Card.Mover(fx.sym, listOfNotNull(fx.r1m?.let { "1M ${Sec.p(it)}" }, fx.rsi?.let { "RSI ${it.toInt()}" }, fx.volX?.let { "vol ${"%.1f".format(it)}×" }).joinToString(" · "), fx.px, fx.r1m?.times(100), spark, spark.firstOrNull(), fx.sym in mine) })
        return c.note("A screen narrows the field; it isn't a list of buys. Tap any stock for why it moved, its trend and news.")
            .sources("NSE (your copy)").put("asOf", p.dates[d].toString())
            .pills(*listOfNotNull(hits.firstOrNull()?.second?.sym?.let { "/why $it" }, "/strategies", "/screens").toTypedArray())
            .json("${hits.size} of ${universe.size} liquid stocks match (${checks.joinToString(", ") { it.label.lowercase() }})" + (if (hits.isNotEmpty()) ": " + hits.take(8).joinToString(", ") { it.second.sym } else "") + ". Source: NSE daily data on this phone.")
    }
}

/** /healthscore — the portfolio's health, 0–100: diversification, concentration, sectors, beta, volatility, drawdown, vs Nifty, red flags. */
object HealthScoreFeature : Feature {
    override val id = "healthscore"; override val usage = "/healthscore"; override val group = Group.PORTFOLIO; override val needsWeb = false
    override val requires = "market"
    override val help = "Portfolio health score 0–100: diversification, concentration, sectors, beta, volatility, drawdown, vs Nifty"
    override val method = "From your Kite holdings (matched on the phone) and your NSE history: diversification (how many equal stocks the portfolio behaves like, from the " +
        "Herfindahl index), the largest holding's weight, the largest sector's weight (NSE Indices members), weighted beta to the Nifty 50 (120 sessions), weighted 60-day " +
        "volatility, weighted distance from 52-week highs and the 1-year return against the Nifty; with web on, the share in stocks on NSE surveillance lists. Each part " +
        "scores 0–100 and the score is their average. It describes risk and spread, not the quality of the companies."
    override val triggers = listOf(Regex("""(?i)^((my |portfolio )?(health ?score|portfolio score|portfolio health)|how healthy is my portfolio|rate my portfolio)\??$"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val h = f.holdings.filter { it.current > 0 }
        if (h.isEmpty()) return Card.error("This scores your own portfolio. Connect Kite (read-only) first.", "Connect Kite")
        val p = f.market(400) ?: return Card.error("This uses your NSE history. Type /data 1y to download it.", "/data 1y")
        val d = p.days - 1; val total = h.sumOf { it.current }
        val weights = h.groupBy { it.name.uppercase() }.mapValues { e -> e.value.sumOf { it.current } / total }
        val book = f.sectors()
        val sectorOf = weights.keys.associateWith { s -> book?.members?.entries?.firstOrNull { (idx, m) -> s in m && idx !in setOf("Nifty Financial Services", "Nifty PSE", "Nifty Commodities", "Nifty Infrastructure") }?.key ?: "Other" }
        val nifty = book?.series?.get(Sectors.BENCHMARK)
        val beta = HashMap<String, Double>(); val vol = HashMap<String, Double>(); val fromHigh = HashMap<String, Double>(); val r1y = HashMap<String, Double>()
        for (sym in weights.keys) { val s = p.of(sym) ?: continue
            Market.vol(p, s, d, 60)?.let { vol[sym] = it }; Market.change(p, s, d, 252)?.let { r1y[sym] = it }
            (maxOf(0, d - 251)..d).map { p.adj[s][it] }.filter { !it.isNaN() }.maxOrNull()?.let { fromHigh[sym] = p.adj[s][d] / it - 1.0 }
            if (nifty != null) { val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
                for (t in maxOf(1, d - 119)..d) { val y = p.ret[s][t]; if (y.isNaN()) continue; val a = nifty.at(p.dates[t]); val b = nifty.at(p.dates[t - 1])
                    if (a == null || b == null || a.first != p.dates[t]) continue; xs += a.second / b.second - 1; ys += y.toDouble() }
                if (xs.size >= 60) { val mx = xs.average(); val my = ys.average(); val v = xs.sumOf { (it - mx) * (it - mx) }; if (v > 0) beta[sym] = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / v } } }
        val flagged = if (f.webOn) Pro.flags(f).keys.filter { it in weights }.toSet() else emptySet()
        val r = HealthScore.compute(weights, sectorOf, beta, vol, fromHigh, r1y, nifty?.change(Sectors.Period.Y1), flagged)
        val label = when { r.score >= 75 -> "Healthy"; r.score >= 55 -> "Fair"; r.score >= 40 -> "Needs attention"; else -> "Risky" }
        val weakest = r.parts.minBy { it.score }
        return Card("Portfolio health · ${h.size} holdings · ${p.dates[d]}", if (r.score >= 55) "green" else "saffron")
            .gauge("Health score", r.score, label, "weakest part: ${weakest.name.lowercase()} — ${weakest.detail}", "Risky", "Healthy")
            .bars("The parts · each 0–100 (50 = middling)", r.parts.map { Card.Bar(it.name, it.score - 50, "${it.score.toInt()} · ${it.detail}") }, "pts")
            .bars("By sector", weights.entries.groupBy { sectorOf[it.key] ?: "Other" }.mapValues { e -> e.value.sumOf { it.value } }.entries.sortedByDescending { it.value }
                .map { Card.Bar(it.key.removePrefix("Nifty "), it.value * 100, "${(it.value * 100).toInt()}%") }, "%")
            .note("A description of spread and risk, not a rating of your stocks or advice to change them.")
            .sources("Your Kite holdings (on this phone)", "NSE (your copy)").put("asOf", p.dates[d].toString())
            .pills("/xray", "/stress", "/redflags", "/harvest")
            .json("Portfolio health ${r.score.toInt()} of 100 ($label). Weakest: ${weakest.name.lowercase()} (${weakest.detail}); strongest: ${r.parts.maxBy { it.score }.name.lowercase()}. Source: your holdings and NSE data on this phone.")
    }
}

/** /harvest [gains booked this year] — losses that could offset gains already booked, the tax that could save, and days left in the year. */
object HarvestFeature : Feature {
    override val id = "harvest"; override val usage = "/harvest 80000"; override val group = Group.PORTFOLIO; override val needsWeb = false
    override val help = "Tax-loss harvesting: holdings in loss that could offset gains booked this year, and the tax that could save"
    override val method = "Unrealised gain or loss per holding from your Kite holdings (quantity × (price − average)), matched on the phone. If you give the gains you've already " +
        "booked this financial year, the offset is the smaller of that and your total unrealised loss; the saving shown is at 20% (short-term) and at 12.5% on long-term " +
        "gains above the ₹1.25 lakh exemption. Kite doesn't give purchase dates, so whether a loss is short- or long-term isn't known here."
    override val triggers = listOf(Regex("""(?i)^((tax[- ]?loss )?harvest(ing)?|tax harvesting|save tax on (my )?(gains|stocks|shares)|which (stocks|holdings) are in loss)\??$"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val h = f.holdings
        if (h.isEmpty()) return Card.error("This uses your own holdings. Connect Kite (read-only) first.", "Connect Kite")
        val realised = com.khayali.pulao.ai.Money.parse(arg.trim()) ?: 0.0
        val plan = Harvest.plan(h.map { Harvest.Lot(it.name.uppercase(), it.invested, it.current) }, realised, f.today)
        val c = Card("Tax-loss harvesting · ${plan.daysLeft} days to 31 March", if (plan.totalLoss > 0) "blue" else "green")
            .headline(if (plan.totalLoss <= 0) "No holdings in loss" else "${Pro.rupees(plan.totalLoss)} of unrealised losses",
                if (realised > 0) "Against ${Pro.rupees(realised)} booked: up to ${Pro.rupees(plan.saveStcg)} less tax at 20% (short-term)" else "Add the gains you've booked this year, e.g. /harvest 80000, to see the tax it could save")
        if (realised > 0) c.kpis(listOf(Card.Kpi("CAN OFFSET", Pro.rupees(plan.offset)), Card.Kpi("SAVES · STCG 20%", Pro.rupees(plan.saveStcg)), Card.Kpi("SAVES · LTCG 12.5%", Pro.rupees(plan.saveLtcg), null, "above ₹1.25 L")))
        if (plan.losses.isNotEmpty()) c.bars("Holdings in loss (₹)", plan.losses.take(10).map { Card.Bar(it.symbol, it.pnl, "${"%.1f".format(it.pnl / it.invested * 100)}%") }, "₹")
        if (plan.gains.isNotEmpty()) c.bars("Holdings in gain (₹)", plan.gains.take(6).map { Card.Bar(it.symbol, it.pnl, "${"%.1f".format(it.pnl / it.invested * 100)}%") }, "₹")
        return c.note("Estimates only — not tax advice. Short- or long-term depends on when you bought (not in Kite's holdings). Selling and buying back the same stock may be questioned by the tax department; check with a tax adviser.")
            .sources("Your Kite holdings (on this phone)").pills("/tax", "/healthscore", "/xray")
            .json(if (plan.totalLoss <= 0) "None of your holdings are in loss." else "${plan.losses.size} holdings carry ${Pro.rupees(plan.totalLoss)} of unrealised losses" +
                (if (realised > 0) "; offsetting ${Pro.rupees(plan.offset)} of booked gains could save up to ${Pro.rupees(plan.saveStcg)} (short-term) or ${Pro.rupees(plan.saveLtcg)} (long-term)." else ".") + " ${plan.daysLeft} days to 31 March.")
    }
}

/** /concall SYMBOL — the latest earnings-call transcript, audio and investor material a company filed, with NSE's own summary. */
object ConcallFeature : Feature {
    override val id = "concall"; override val usage = "/concall INFY"; override val group = Group.STOCK
    override val help = "Earnings calls: a company's latest call transcript, audio and presentation filings, with NSE's summary"
    override val method = "Listed companies must file earnings-call audio and transcripts with the exchange (SEBI LODR). The phone reads the company's NSE announcements for the " +
        "last four months and keeps those about calls, transcripts, audio recordings, investor presentations and analyst meets, with NSE's own one-line summary where given. " +
        "Tap one to open the document or audio."
    override val triggers = listOf(Regex("""(?i)^(concall|earnings call|conference call|con call)( transcript| summary| audio)? (of|for) @?([A-Za-z][A-Za-z0-9&\-]{1,19})\??$"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val q = arg.replace(Regex("""(?i)^.*\b(of|for)\s+"""), "").trim().removePrefix("@")
        val sym = f.resolve(q) ?: return Card.error("Which company? Try /concall INFY.")
        val dmy = DateTimeFormatter.ofPattern("dd-MM-yyyy")
        val raw = runCatching { f.nse("corporate-announcements?index=equities&symbol=$sym&from_date=${f.today.minusDays(120).format(dmy)}&to_date=${f.today.format(dmy)}") }.getOrDefault("")
        val arr = runCatching { com.google.gson.JsonParser.parseString(raw).let { if (it.isJsonArray) it.asJsonArray else it.asJsonObject.getAsJsonArray("data") } }.getOrNull()
        val rx = Regex("(?i)transcript|audio recording|earnings call|conference call|con\\.? ?call|analyst|investor (meet|presentation|call)")
        val calls = arr?.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }?.mapNotNull { o ->
            fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val text = s("attchmntText"); val desc = s("desc")
            // generic subjects ("Updates") say less than the filing's own words ("…regarding 'Earnings Call Transcript'")
            val what = Regex("""regarding ['"‘’]?([^'"‘’.]{4,60})""").find(text)?.groupValues?.get(1)?.trim()
            val title = if (desc.isBlank() || desc.equals("Updates", true)) what ?: desc else desc
            if (!rx.containsMatchIn(desc + " " + text)) null else Triple(s("an_dt"), title, Pair(s("voiceSummary").ifBlank { text }.take(260), s("attchmntFile")))
        }.orEmpty()
        if (calls.isEmpty()) return Card.error("No call transcripts, audio or presentations filed by $sym in the last four months.", "/radar", "/results $sym")
        val first = calls.first()
        return Card("$sym · earnings calls · ${calls.size} filings", "violet")
            .headline(first.second.ifBlank { "Latest call filing" }, "filed ${first.first.substringBefore(' ')}")
            .rows(null, calls.take(10).map { (at, desc, x) -> Card.Row(desc.ifBlank { "Filing" }, at.substringBefore(' ') + "\n" + x.first, "", "", action = x.second) })
            .note("Straight from the company's filings with NSE. Tap one to open the transcript, audio or slides.")
            .sources("NSE filings").put("links", calls.take(10).map { it.third.second })
            .pills("/why $sym", "/results $sym", "/flow $sym", "/news $sym")
            .json("$sym's latest call filings: " + calls.take(3).joinToString("; ") { "${it.second} (${it.first.substringBefore(' ')})" } + ". " + first.third.first + " Source: NSE filings.")
    }
}


/** /alert INFY above 1800 · /alert TCS below 3000 · /alert SBIN moves 5% · /alert (list) · /alert remove 2 · /alert clear */
object AlertFeature : Feature {
    override val id = "alert"; override val usage = "/alert INFY above 1800"; override val group = Group.TOOLS; override val needsWeb = false
    override val help = "Price alerts checked on the phone after each close: above, below, or a big move — with a notification"
    override val method = "Your alerts are kept on the phone. After each trading day's close (7 pm, Indian time) the phone fetches NSE's daily file — while the chat is " +
        "open, and in the background with Android's job scheduler every few hours — and checks each alert against the stock's close and day move. An alert fires once " +
        "per day it's true and shows as a notification and in the chat. Prices are end-of-day closes, not live ticks."
    override val triggers = listOf(Regex("""(?i)^((my |show )?(price )?alerts?|alert me when @?[A-Za-z][A-Za-z0-9&\-]{1,19} .+)$"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val store = f.alerts ?: return Card.error("Alerts are only available on the phone.")
        val a = arg.trim().replace(Regex("""(?i)^(me )?when\s+"""), "")
        val p = f.market(30)
        when {
            a.equals("clear", true) -> { store.clear(); return Card("Price alerts", "blue").headline("All alerts cleared", "").sources("This phone").json("All price alerts cleared.") }
            a.lowercase().startsWith("remove") -> {
                val n = a.filter { it.isDigit() }.toIntOrNull() ?: return Card.error("Which one? e.g. /alert remove 2", "/alert")
                val r = store.remove(n - 1) ?: return Card.error("There's no alert number $n.", "/alert")
                return Card("Price alerts", "blue").headline("Removed: ${r.symbol} ${r.kind.name.lowercase()} ${r.value}", "").pills("/alert").sources("This phone").json("Removed the alert on ${r.symbol}.")
            }
            a.isNotBlank() -> {
                val r = com.khayali.pulao.ai.data.Alerts.parse(a, store.nextId(), f.today) ?: return Card.error("Try: /alert INFY above 1800, /alert TCS below 3000, or /alert SBIN moves 5%.")
                if (p != null && p.of(r.symbol) == null) return Card.error("${r.symbol} isn't in your NSE history, so it can't be checked.", "/data update")
                store.add(r)
            }
        }
        val rules = store.rules()
        if (rules.isEmpty()) return Card("Price alerts", "blue").headline("No alerts yet", "Set one: /alert INFY above 1800 · /alert TCS below 3000 · /alert SBIN moves 5%")
            .note(method).sources("This phone").pills("/alert INFY above 1800").json("No price alerts set.")
        val d = p?.let { it.days - 1 }
        val rows = rules.mapIndexed { i, r ->
            val s = p?.of(r.symbol); val close = if (s != null && d != null) p.adj[s][d].toDouble() else Double.NaN; val mv = if (s != null && d != null) p.ret[s][d].toDouble() else 0.0
            val txt = when (r.kind) { com.khayali.pulao.ai.data.Alerts.Kind.ABOVE -> "closes above ₹${"%,.2f".format(r.value)}"; com.khayali.pulao.ai.data.Alerts.Kind.BELOW -> "closes below ₹${"%,.2f".format(r.value)}"
                com.khayali.pulao.ai.data.Alerts.Kind.MOVE -> "moves ±${r.value}% in a day" }
            Card.Row("${i + 1}. ${r.symbol}", txt + (if (!close.isNaN()) " · last close ₹${"%,.2f".format(close)} · " + com.khayali.pulao.ai.data.Alerts.distance(r, close, mv) else ""), "", "") }
        return Card("Price alerts · ${rules.size}", "blue")
            .headline(if (a.isNotBlank()) "Alert set" else "${rules.size} alert${if (rules.size > 1) "s" else ""}", "Checked on the phone after each close; you'll get a notification")
            .rows(null, rows).note("End-of-day closes from NSE, checked on the phone. Remove one with /alert remove 2, or all with /alert clear.")
            .sources("This phone", "NSE (your copy)").pills("/alert remove ${rules.size}", "/alert clear", "/watch")
            .json("${rules.size} price alerts: " + rules.joinToString("; ") { "${it.symbol} ${it.kind.name.lowercase()} ${it.value}" } + ".")
    }
}
