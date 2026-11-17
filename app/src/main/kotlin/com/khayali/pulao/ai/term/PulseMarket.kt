package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.J.a
import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.o
import com.khayali.pulao.ai.term.J.s

/** /breadth — advances, declines and unchanged across all NSE stocks traded today. */
object PulseBreadthFeature : Feature {
    override val id = "breadth"
    override val usage = "/breadth"
    override val help = "Market breadth: how many NSE stocks are up, down and unchanged today, and the advance/decline ratio"
    override val group = Group.MARKET
    override val triggers = listOf(Regex("""(?i)\b(market breadth|advances?[ /-](and |vs )?declines?|a/d ratio)\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(f.nse("live-analysis-advance"))

    /** Pure: live-analysis-advance JSON → card. */
    fun build(json: String): String {
        val adv = J.obj(json)?.o("advance"); val c = adv?.o("count") ?: return Card.error("NSE's advance/decline count is unavailable right now. Try again in a minute.")
        val up = c.d("Advances") ?: 0.0; val down = c.d("Declines") ?: 0.0; val flat = c.d("Unchange") ?: c.d("Unchanged") ?: 0.0
        val total = up + down + flat
        if (up + down <= 0) return Card.error("NSE shows no advances or declines yet — the market may not have traded today.")
        val ratio = if (down > 0) up / down else null
        val asOf = Pulse.asOf(Pulse.stamp(json))
        val big = adv.a("data").mapNotNull { o -> val cap = o.d("totalMarketCapcsv") ?: return@mapNotNull null; val p = o.d("pchange") ?: return@mapNotNull null; Triple(o, cap, p) }.sortedByDescending { it.second }.take(5)
        fun share(v: Double) = Pulse.fixed(v / total * 100, 1) + "%"
        return Card("NSE market breadth$asOf", if (up >= down) "green" else "red")
            .headline("${Pulse.int(up)} up · ${Pulse.int(down)} down", "${Pulse.int(flat)} unchanged of ${Pulse.int(total)} stocks" + (ratio?.let { " · ${Pulse.fixed(it, 2)} advances for every decline" } ?: ""), N.tone(up - down))
            .viz("donut", 170, Pulse.donut(ratio?.let { Pulse.fixed(it, 2) } ?: Pulse.int(up), if (ratio != null) "adv per decline" else "advances", "Direction", "Stocks",
                listOf(Pulse.slice("Advances", up, "green"), Pulse.slice("Declines", down, "red"), Pulse.slice("Unchanged", flat, "muted"))),
                "Ring: number of NSE stocks that rose, fell or were unchanged today. Centre: advances ÷ declines. Tap a slice.")
            .stats(*listOfNotNull("Advancing" to share(up), "Declining" to share(down), ratio?.let { "Advance/decline ratio" to Pulse.fixed(it, 2) }, "Stocks counted" to Pulse.int(total)).toTypedArray())
            .rows("Largest advancing stocks by market value", big.map { (o, cap, p) -> Card.Row(o.s("symbol"), listOfNotNull(o.d("lastPrice")?.let { "₹" + N.num(it) }, "market value " + N.cr(cap)).joinToString(" · "), N.pct(p, 2), N.tone(p)) })
            .note("Breadth counts stocks, not their size: a few large companies can move an index the other way. ${Pulse.DELAY}")
            .sources("NSE").pills("/movers", "/sectors", "/highs", "/heatmap")
            .json("NSE breadth$asOf: ${Pulse.int(up)} advances, ${Pulse.int(down)} declines and ${Pulse.int(flat)} unchanged of ${Pulse.int(total)} stocks" +
                (ratio?.let { " — ${Pulse.fixed(it, 2)} advances per decline" } ?: "") + ". Source: NSE.")
    }
}

/** /status — is the market open, each segment's state as NSE words it, and the previous session's turnover by segment. */
object PulseStatusFeature : Feature {
    override val id = "status"
    override val usage = "/status"
    override val help = "Is the market open? Each NSE segment's state, plus the previous session's turnover by segment"
    override val group = Group.MARKET
    override val triggers = listOf(Regex("""(?i)\b(is|are) (the )?(stock )?(market|markets|nse) (open|closed|shut)\b"""), Regex("""(?i)\bmarket status\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(f.nse("marketStatus"), f.nse("market-turnover"))

    /**
     * NSE's own message is the reliable field: the short status can still read "Open" on a trading day after the close.
     * "Normal Market has Closed" → Closed; "Pre-Open Session Closed – Normal Market will begin shortly" → Pre-open.
     */
    fun state(status: String, message: String): String { val m = message.lowercase(); val s = status.lowercase(); return when {
        m.contains("pre-open") || m.contains("pre open") || m.contains("preopen") -> "Pre-open"
        m.contains("close") -> "Closed"; m.contains("open") -> "Open"
        s.startsWith("clos") -> "Closed"; s.contains("pre") -> "Pre-open"; s.startsWith("open") -> "Open"
        else -> status.ifEmpty { "Unknown" } } }

    private val NAMES = mapOf("capital market" to "Equities (capital market)", "currency" to "Currency", "commodity" to "Commodity", "debt" to "Debt", "currencyfuture" to "Currency futures")

    /** Pure: marketStatus + market-turnover JSON → card. Turnover is optional. */
    fun build(statusJson: String, turnoverJson: String): String {
        val root = J.obj(statusJson); val states = root?.a("marketState").orEmpty().filter { it.s("market").isNotEmpty() }
        if (states.isEmpty()) return Card.error("NSE's market status is unavailable right now. Try again in a minute.")
        val cm = states.firstOrNull { it.s("market").equals("Capital Market", true) } ?: states.first()
        val st = state(cm.s("marketStatus"), cm.s("marketStatusMessage")); val msg = cm.s("marketStatusMessage")
        val last = cm.d("last"); val ch = cm.d("percentChange"); val idx = cm.s("index")
        val level = if (last != null && idx.isNotEmpty()) "$idx ${N.num(last)}" + (ch?.let { " (${N.pct(it, 2)})" } ?: "") else null
        // previous session's turnover, rupees → ₹ crore; small segments are folded into "Other"
        val turn = J.arr(turnoverJson, "data").mapNotNull { o -> val v = o.o("yesterday")?.d("value") ?: return@mapNotNull null; o.s("name") to v / 1e7 }
        val total = turn.firstOrNull { it.first.equals("Total", true) }?.second ?: turn.sumOf { it.second }
        val parts = turn.filter { !it.first.equals("Total", true) && it.first.isNotEmpty() && it.second > 0 }.sortedByDescending { it.second }
        val top = parts.take(5); val other = total - top.sumOf { it.second }
        val equities = parts.firstOrNull { it.first.equals("Equities", true) }?.second
        val colors = listOf("blue", "saffron", "violet", "green", "pink")
        val cap = root?.o("marketcap"); val gift = root?.o("giftnifty")
        val card = Card("NSE market status" + (cm.s("tradeDate").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), when (st) { "Open" -> "green"; "Pre-open" -> "saffron"; else -> "blue" })
            .headline(st, listOfNotNull(msg.takeIf { it.isNotEmpty() }?.let { "NSE: \"$it\"" }, level).joinToString(" · "), if (st == "Open") "up" else "")
        if (total > 0 && top.isNotEmpty()) card.viz("donut", 190, Pulse.donut("₹" + Pulse.fixed(total / 1e5, 2) + " L Cr", "total", "Segment", "Turnover",
            top.mapIndexed { i, (n, v) -> Pulse.slice(n, v, colors[i]) } + (if (other > 0.5) listOf(Pulse.slice("Other", other, "muted")) else emptyList()), "₹ Cr"),
            "Ring: previous trading session's turnover by segment, in ₹ crore, as reported by NSE. Tap a slice.")
        card.stats(*listOfNotNull(
            equities?.let { "Equities turnover (previous session)" to N.cr(it) },
            cap?.d("marketCapinLACCRRupees")?.let { "Market value of NSE-listed companies" to "₹" + Pulse.fixed(it, 2) + " L Cr" },
            gift?.d("LASTPRICE")?.let { "GIFT Nifty (indicative)" to N.num(it) + (gift.d("PERCHANGE")?.let { p -> " (${N.pct(p, 2)})" } ?: "") },
            states.firstOrNull { it.s("market") == "currencyfuture" }?.let { c -> c.d("last")?.let { (c.s("underlying").ifEmpty { "USDINR" } + " futures") to "₹" + N.num(it) } }).toTypedArray())
            .rows("Segments", states.map { o -> val s = state(o.s("marketStatus"), o.s("marketStatusMessage"))
                Card.Row(NAMES[o.s("market").lowercase()] ?: o.s("market"), listOf(o.s("marketStatusMessage"), o.s("tradeDate")).filter { it.isNotEmpty() }.joinToString(" · "), s, when (s) { "Open" -> "up"; "Pre-open" -> "warn"; else -> "" }) })
            .note("Status is NSE's own wording at the time of the request. Turnover is for the previous trading session; GIFT Nifty is an indicative futures price, not the index.")
            .sources("NSE").pills("/indices", "/breadth", "/movers", "/calendar")
        return card.put("state", st).json("NSE equity market: $st" + (msg.takeIf { it.isNotEmpty() }?.let { " (NSE says \"$it\")" } ?: "") + (level?.let { "; $it" } ?: "") + "." +
            (if (total > 0) " Previous session turnover ${N.cr(total)} across segments" + (equities?.let { ", equities ${N.cr(it)}" } ?: "") + "." else "") + " Source: NSE.")
    }
}
