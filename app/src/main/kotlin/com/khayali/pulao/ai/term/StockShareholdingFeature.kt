package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.StockFeeds.share

/** /shareholding SYMBOL — promoter, public and employee-trust holding over the reported quarters, as a streamgraph. */
object StockShareholdingFeature : Feature {
    override val id = "shareholding"
    override val usage = "/shareholding INFY"
    override val help = "Who owns the company: promoter, public and employee-trust holding by quarter (streamgraph)"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\b(share ?holding|promoter (holding|stake)s?|ownership pattern)\b"""))

    const val QUARTERS = 12

    override suspend fun run(arg: String, f: Fetch): String {
        val sym = f.symbol(arg) ?: return Card.error("Which company? Try /shareholding INFY.")
        return build(sym, f.nse(StockFeeds.holdingPath(sym)))
    }

    /** Pure: shareholding-pattern master JSON → card. */
    fun build(symbol: String, json: String): String {
        val all = StockFeeds.holdings(json)
        val rows = all.takeLast(QUARTERS)
        val now = rows.lastOrNull() ?: return Card.error("NSE has no shareholding pattern on file for $symbol.")
        val prev = rows.getOrNull(rows.size - 2)
        val yearAgo = all.lastOrNull { it.date <= now.date.minusYears(1) && it.date >= now.date.minusYears(1).minusDays(10) }
        val hi = rows.maxByOrNull { it.promoter }!!; val lo = rows.minByOrNull { it.promoter }!!
        val dPrev = prev?.let { now.promoter - it.promoter }
        val card = Card("$symbol · shareholding pattern · ${rows.size} filing${if (rows.size == 1) "" else "s"}", "violet")
            .headline("Promoters ${share(now.promoter)}", "Public ${share(now.public)}" + (if (now.trusts > 0) " · employee trusts ${share(now.trusts)}" else "") + " · as of ${StockFeeds.day(now.date)}")
            .viz("stream", 250, mapOf("unit" to "%", "yLabel" to "Share of company (%)", "xLabel" to "Filing date", "series" to listOf("Promoters", "Public", "Employee trusts"),
                "points" to rows.map { mapOf("t" to it.date.toString(), "label" to StockFeeds.day(it.date), "values" to listOf(it.promoter, it.public, it.trusts)) }),
                "Band thickness: share of the company's equity (%). Horizontal axis: the date each pattern was reported for. Tap for the figures.")
            .stats(*listOfNotNull(
                dPrev?.let { "Promoters vs previous filing" to StockFeeds.points(it) },
                yearAgo?.let { "Promoters vs a year ago" to StockFeeds.points(now.promoter - it.promoter) },
                if (rows.size > 1) "Promoter high · ${StockFeeds.monthYear(hi.date)}" to share(hi.promoter) else null,
                if (rows.size > 1) "Promoter low · ${StockFeeds.monthYear(lo.date)}" to share(lo.promoter) else null).toTypedArray())
            .rows("Latest filings", rows.takeLast(5).reversed().map { Card.Row(StockFeeds.day(it.date), "Public ${share(it.public)}" + (if (it.trusts > 0) " · trusts ${share(it.trusts)}" else ""), "Promoters ${share(it.promoter)}") })
            .note("\"Public\" here is everyone who is not a promoter: funds, foreign investors, institutions and individuals together. This feed does not split them, and it does not show pledged promoter shares. Companies report once a quarter (and after some corporate actions), so the picture can be weeks old.")
            .sources(StockFeeds.SOURCE).pills("/mindmap $symbol", "/quote $symbol", "/flow $symbol", "/deals")
            .put("symbol", symbol).put("promoter", now.promoter)
        return card.json("$symbol shareholding as of ${StockFeeds.day(now.date)}: promoters ${share(now.promoter)}, public ${share(now.public)}, employee trusts ${share(now.trusts)}" +
            (if (prev != null && dPrev != null) "; promoter holding ${StockFeeds.points(dPrev)} against the previous filing (${StockFeeds.day(prev.date)})" else "") + ". Source: NSE.")
    }
}
