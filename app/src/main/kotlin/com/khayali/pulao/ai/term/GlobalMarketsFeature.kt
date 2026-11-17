package com.khayali.pulao.ai.term

/** /global — ten world indices at a glance: last level, change on the latest session and a 1-month sparkline each. */
object GlobalMarketsFeature : Feature {
    override val id = "global"
    override val usage = "/global"
    override val help = "World indices at a glance: last level, day change and a 1-month sparkline for ten markets"
    override val group = Group.MACRO
    override val triggers = listOf(Regex("""(?i)\b(global|world|asian|european) (markets?|indices|indexes)\b"""), Regex("""(?i)\b(dow jones|nasdaq|s&p 500|nikkei|hang seng)\b"""))

    val INDICES = listOf("^GSPC" to "S&P 500", "^IXIC" to "Nasdaq", "^DJI" to "Dow Jones", "^FTSE" to "FTSE 100", "^GDAXI" to "DAX",
        "^N225" to "Nikkei 225", "^HSI" to "Hang Seng", "000001.SS" to "Shanghai", "^NSEI" to "Nifty 50", "^BSESN" to "Sensex")

    override suspend fun run(arg: String, f: Fetch): String = build(GlobalKit.charts(f, INDICES.map { it.first }, "1mo"))

    /** Pure: chart JSON by feed symbol → card. Markets whose feed is missing are left out and named. */
    fun build(charts: Map<String, String>): String {
        val qs = INDICES.mapNotNull { (sym, name) -> GlobalKit.quote(charts, sym, name) }
        if (qs.isEmpty()) return Card.error("Couldn't load world indices from the quote feed just now. Try again in a minute, or /indices for Indian indices.")
        val up = qs.count { it.day > 0 }
        val best = qs.maxBy { it.day }; val worst = qs.minBy { it.day }
        val missing = INDICES.map { it.second } - qs.map { it.label }.toSet()
        val newest = qs.maxOf { it.date }
        fun fig(q: GlobalKit.Quote) = "${q.label} ${GlobalKit.num(q.last)} (${N.pct(q.day, 2)})"
        val key = listOf("S&P 500", "Nikkei 225", "Nifty 50").mapNotNull { k -> qs.firstOrNull { it.label == k } }.ifEmpty { qs.take(3) }
        return Card("World indices · latest session · ${qs.size} markets", if (up * 2 >= qs.size) "green" else "red")
            .headline("$up of ${qs.size} up", "Best ${best.label} ${N.pct(best.day, 2)} · weakest ${worst.label} ${N.pct(worst.day, 2)}", if (up * 2 > qs.size) "up" else if (up * 2 < qs.size) "down" else "")
            .viz("sparkgrid", GlobalKit.gridHeight(qs.size, 2, 76), mapOf("cols" to 2, "yLabel" to "Index level · low & high per panel", "xLabel" to "Date", "items" to qs.map { GlobalKit.panel(it) }),
                "Each panel: daily closes over about one month (index points, own scale), the last level and the change on the latest session. Tap a line for a day's close.")
            .rows("Over the past month", qs.sortedByDescending { it.period }.map { Card.Row(it.label, "last session ${GlobalKit.day(it.date)} · day ${N.pct(it.day, 2)}", N.pct(it.period), N.tone(it.period)) })
            .note("Each market shows its own latest session (newest: ${GlobalKit.date(newest)}). Asia, Europe and the US close at different times, so the figures are not simultaneous, and quotes may be delayed." +
                if (missing.isEmpty()) "" else " No data just now for: ${missing.joinToString(", ")}.")
            .sources(GlobalKit.SOURCE).pills("/fx", "/commodities", "/yields", "/indices")
            .put("up", up).put("markets", qs.size)
            .json("Latest session: $up of ${qs.size} world indices up; best ${best.label} ${N.pct(best.day, 2)}, weakest ${worst.label} ${N.pct(worst.day, 2)}. " +
                key.joinToString(", ") { fig(it) } + ". Source: ${GlobalKit.SOURCE}.")
    }
}
