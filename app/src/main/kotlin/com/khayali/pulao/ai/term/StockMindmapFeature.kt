package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.J.a
import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.o
import com.khayali.pulao.ai.term.J.s
import com.khayali.pulao.ai.term.StockFeeds.price
import com.khayali.pulao.ai.term.StockFeeds.share
import java.time.LocalDate

/**
 * /mindmap SYMBOL — one company as a mind map. Centre: the company. Branches: Price (last, day change, 52-week range),
 * Results (latest reported quarter), Ownership (promoters, public), Events (next and last dated events) and Sector peers.
 * Every leaf is a figure from an NSE feed; a branch whose feed is empty is left out.
 */
object StockMindmapFeature : Feature {
    override val id = "mindmap"
    override val usage = "/mindmap INFY"
    override val help = "Mind map of one company: price, latest results, ownership, events and sector peers"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\bmind ?map\b"""))

    class Leaf(val name: String, val value: String, val tone: String = "", val detail: String = "")
    class Branch(val name: String, val color: String, val leaves: List<Leaf>)
    /** The latest reported quarter, in ₹ lakh as filed. [incomeLabel] says which top line the feed gave. */
    class Quarter(val end: LocalDate, val incomeLabel: String, val income: Double, val profit: Double, val eps: String, val basis: String)
    class Event(val date: LocalDate, val title: String, val detail: String)

    override suspend fun run(arg: String, f: Fetch): String {
        val sym = f.symbol(arg) ?: return Card.error("Which company? Try /mindmap INFY.")
        val quote = f.nse(StockFeeds.quotePath(sym))
        val index = StockFeeds.quote(quote)?.let { StockFeeds.sectorIndex(it) }
        return build(sym, quote, f.nse(StockFeeds.holdingPath(sym)), f.nse(StockFeeds.resultsPath(sym)), f.nse(StockFeeds.corpPath(sym)),
            index, if (index != null) f.nse(StockFeeds.peersPath(index)) else "", f.today)
    }

    /** The newer of: top-corp-info's financial results (total income, profit after tax) and results-comparison (net sales, net profit). */
    fun quarter(resultsJson: String, corpJson: String): Quarter? {
        val a = J.obj(corpJson)?.o("financial_results")?.a("data").orEmpty().mapNotNull { o ->
            Quarter(StockFeeds.date(o.s("to_date")) ?: return@mapNotNull null, "Total income", o.d("income") ?: return@mapNotNull null, o.d("proLossAftTax") ?: return@mapNotNull null,
                o.s("reDilEPS"), if (o.s("consolidated").startsWith("Non", true)) "standalone" else if (o.s("consolidated").isBlank()) "" else "consolidated")
        }.maxByOrNull { it.end }
        val b = J.arr(resultsJson, "resCmpData").mapNotNull { o ->
            val sales = o.d("re_net_sale")?.takeIf { it > 0 }; val total = o.d("re_tot_inc") ?: o.d("re_total_inc")
            Quarter(StockFeeds.date(o.s("re_to_dt")) ?: return@mapNotNull null, if (sales != null) "Sales" else "Total income", sales ?: total ?: return@mapNotNull null, o.d("re_net_profit") ?: return@mapNotNull null,
                o.s("re_dilut_eps_for_cont_dic_opr").ifBlank { o.s("re_diluted_eps") }, "standalone")
        }.maxByOrNull { it.end }
        return listOfNotNull(a, b).maxByOrNull { it.end }      // on a tie the first (top-corp-info) wins
    }

    /** Dated corporate actions and board meetings, oldest first, one per (date, title). */
    fun events(corpJson: String): List<Event> {
        val root = J.obj(corpJson) ?: return emptyList()
        val actions = root.o("corporate_actions")?.a("data").orEmpty().mapNotNull { o -> Event(StockFeeds.date(o.s("exdate")) ?: return@mapNotNull null, o.s("purpose").ifBlank { "Corporate action" }, "Ex-date " + o.s("purpose")) }
        val meetings = root.o("borad_meeting")?.a("data").orEmpty().mapNotNull { o -> Event(StockFeeds.date(o.s("meetingdate")) ?: return@mapNotNull null, "Board meeting", o.s("purpose")) }
        return (actions + meetings.distinctBy { it.date }).sortedBy { it.date }
    }

    private fun quarterName(end: LocalDate): String {
        val fy = if (end.monthValue >= 4) end.year + 1 else end.year
        return "Q${when (end.monthValue) { in 4..6 -> 1; in 7..9 -> 2; in 10..12 -> 3; else -> 4 }} FY${fy % 100}"
    }
    private fun clip(s: String, n: Int) = if (s.length <= n) s else s.take(n - 1).trimEnd() + "…"

    /** Pure: the five feed replies → card. Any of them may be empty; at least one branch must have data. */
    fun build(symbol: String, quoteJson: String, holdingJson: String, resultsJson: String, corpJson: String, index: String?, peersJson: String, today: LocalDate): String {
        val q = StockFeeds.quote(quoteJson)
        val own = StockFeeds.holdings(holdingJson).lastOrNull()
        val res = quarter(resultsJson, corpJson)
        val ev = events(corpJson)
        val peers = StockFeeds.peers(peersJson).filter { !it.symbol.equals(symbol, true) }
        val branches = mutableListOf<Branch>()
        if (q != null) branches += Branch("Price", "blue", listOfNotNull(
            Leaf("Last", price(q.last) + if (q.preOpen) " (pre-open)" else "", detail = StockFeeds.stamp(q.asOf).let { if (it.isBlank()) "" else "As of $it" }),
            q.pChange?.let { Leaf("Day change", N.pct(it, 2), N.tone(it), q.prevClose?.let { p -> "Previous close ${price(p)}" } ?: "") },
            if (q.yearLow != null && q.yearHigh != null) Leaf("52-week range", "${price(q.yearLow)} – ${price(q.yearHigh)}", detail = q.position?.let { "Last price is ${StockFeeds.fix(it, 0)}% of the way from the low to the high" } ?: "") else null,
            q.symbolPe?.let { Leaf("P/E", StockFeeds.fix(it) + (q.sectorPe?.let { s -> " · sector ${StockFeeds.fix(s)}" } ?: ""), detail = q.sectorIndex.let { s -> if (s.isBlank()) "" else "Sector P/E is for $s" }) }))
        if (res != null) branches += Branch("Results", "green", listOfNotNull(
            Leaf("Quarter", "${quarterName(res.end)} · to ${StockFeeds.day(res.end)}", detail = if (res.basis.isBlank()) "" else "As filed with NSE, ${res.basis}"),
            Leaf(res.incomeLabel, N.cr(res.income / 100)), Leaf("Net profit", N.cr(res.profit / 100), N.tone(res.profit)),
            res.eps.toDoubleOrNull()?.let { Leaf("Diluted EPS", "₹" + StockFeeds.fix(it)) }))
        if (own != null) branches += Branch("Ownership", "violet", listOfNotNull(
            Leaf("Promoters", share(own.promoter)), Leaf("Public", share(own.public)), if (own.trusts > 0) Leaf("Employee trusts", share(own.trusts)) else null,
            Leaf("Reported for", StockFeeds.day(own.date))))
        val next = ev.filter { !it.date.isBefore(today) }.take(2); val past = ev.filter { it.date.isBefore(today) }.takeLast(if (next.isEmpty()) 3 else 2).reversed()
        if (next.isNotEmpty() || past.isNotEmpty()) branches += Branch("Events", "saffron",
            next.map { Leaf(StockFeeds.day(it.date), "Next: " + clip(it.title, 44), "warn", clip(it.detail, 150)) } + past.map { Leaf(StockFeeds.day(it.date), clip(it.title, 44), detail = clip(it.detail, 150)) })
        if (peers.isNotEmpty()) branches += Branch("Sector peers", "pink", (if (peers.all { it.value > 0 }) peers.sortedByDescending { it.value } else peers).take(4).map {
            Leaf(it.symbol, "${price(it.price)} · ${N.pct(it.pChange, 2)}", N.tone(it.pChange), index.orEmpty()) })
        if (branches.isEmpty()) return Card.error("NSE returned nothing for $symbol. Check the symbol, or try /quote $symbol.")
        val name = q?.name.orEmpty()
        val leaves = branches.sumOf { it.leaves.size }
        val tree = mapOf("name" to symbol, "sub" to name, "children" to branches.map { b -> mapOf("name" to b.name, "color" to b.color,
            "children" to b.leaves.map { mapOf("name" to it.name, "value" to it.value, "tone" to it.tone, "detail" to it.detail) }) })
        val card = Card(listOf(symbol, name, "mind map").filter { it.isNotBlank() }.joinToString(" · "), "violet")
        if (q != null) card.headline(price(q.last), listOfNotNull(q.pChange?.let { N.pct(it, 2) + " on the day" }, q.industry.takeIf { it.isNotBlank() }, "${branches.size} branches · $leaves facts").joinToString(" · "), N.tone(q.pChange ?: 0.0))
        else card.headline(symbol, "Price feed unavailable · ${branches.size} branches · $leaves facts")
        val missing = listOf("Price", "Results", "Ownership", "Events", "Sector peers") - branches.map { it.name }.toSet()
        return card.viz("mindmap", maxOf(220, 40 + 19 * leaves + 8 * branches.size), tree, "Left to right: the company, its topics, and the figures under each. Tap a topic to fold or unfold it; tap a figure for its detail.")
            .note((if (missing.isNotEmpty()) "Left out because NSE returned nothing for them: ${missing.joinToString(", ")}. " else "") +
                "Results are the latest quarter NSE has on this feed, in ₹ crore" + (res?.basis?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                "; ownership is as last reported. A map of facts is not a view on the company: it does not say whether the price is fair.")
            .sources(StockFeeds.SOURCE).pills("/quote $symbol", "/flow $symbol", "/shareholding $symbol", if (index != null) "/peers ${index.removePrefix("NIFTY ")}" else "/events $symbol")
            .put("symbol", symbol).put("branches", branches.map { it.name })
            .json(symbol + (if (name.isNotBlank()) " ($name)" else "") + ": " + listOfNotNull(
                q?.let { "last ${price(it.last)}" + (it.pChange?.let { p -> " (${N.pct(p, 2)} on the day)" } ?: "") + (if (it.yearLow != null && it.yearHigh != null) ", 52-week range ${price(it.yearLow)}–${price(it.yearHigh)}" else "") },
                res?.let { "${quarterName(it.end)} ${it.incomeLabel.lowercase()} ${N.cr(it.income / 100)} and net profit ${N.cr(it.profit / 100)}" },
                own?.let { "promoters ${share(it.promoter)}, public ${share(it.public)} as of ${StockFeeds.day(it.date)}" },
                next.firstOrNull()?.let { "next event ${StockFeeds.day(it.date)} (${clip(it.title, 44)})" } ?: past.firstOrNull()?.let { "last event ${StockFeeds.day(it.date)} (${clip(it.title, 44)})" }).joinToString("; ") + ". Source: NSE.")
    }
}
