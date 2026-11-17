package com.khayali.pulao.ai.term

import java.time.temporal.ChronoUnit

/** /crypto — bitcoin and ether in dollars and rupees, with day change and 1-month lines. */
object GlobalCryptoFeature : Feature {
    override val id = "crypto"
    override val usage = "/crypto"
    override val help = "Bitcoin and ether in dollars and rupees with day change and a 1-month line"
    override val group = Group.MACRO
    override val triggers = listOf(Regex("""(?i)\b(bitcoin|btc|ethereum|crypto(currency|currencies)?)\b"""))

    private class Coin(val symbol: String, val name: String, val prefix: String, val digits: Int)
    private val COINS = listOf(Coin("BTC-USD", "Bitcoin (US$)", "$", 2), Coin("ETH-USD", "Ether (US$)", "$", 2), Coin("BTC-INR", "Bitcoin (₹)", "₹", 0), Coin("ETH-INR", "Ether (₹)", "₹", 0))
    val SYMBOLS get() = COINS.map { it.symbol }
    const val RISK = "Crypto prices swing sharply, trade round the clock and carry no exchange or deposit protection; treat every figure here as indicative."

    override suspend fun run(arg: String, f: Fetch): String = build(GlobalKit.charts(f, SYMBOLS, "1mo"))

    /** Pure: 1-month daily chart JSON by feed symbol → card. */
    fun build(charts: Map<String, String>): String {
        val qs = COINS.mapNotNull { c -> GlobalKit.quote(charts, c.symbol, c.name)?.let { c to it } }
        if (qs.isEmpty()) return Card.error("Couldn't load crypto prices from the quote feed just now. Try again in a minute.")
        fun price(p: Pair<Coin, GlobalKit.Quote>) = if (p.first.prefix == "₹") N.inr(p.second.last) else GlobalKit.usd(p.second.last, p.first.digits)
        val head = qs.first(); val q = head.second
        // the feed sometimes skips a daily bar, so say which close the change is measured from
        val since = if (ChronoUnit.DAYS.between(q.prevDate, q.date) > 1) "since the ${GlobalKit.day(q.prevDate)} close" else "on the day"
        return Card("Crypto · ${GlobalKit.date(q.date)} · indicative", "violet")
            .headline(price(head) + " " + head.first.name.substringBefore(" (").lowercase(), "${N.pct(q.day, 2)} $since · 1 month ${N.pct(q.period)}", N.tone(q.day))
            .viz("sparkgrid", GlobalKit.gridHeight(qs.size, 2, 76), mapOf("cols" to 2, "yLabel" to "Price · low & high per panel", "xLabel" to "Date",
                "items" to qs.map { (c, x) -> GlobalKit.panel(x, digits = c.digits, prefix = c.prefix, changeLabel = "") }),
                "Each panel: daily closes over about one month (own scale and currency), the last price and the latest change. Tap a line for a day's close.")
            .rows("Last price · latest change", qs.map { p -> Card.Row(p.second.label, "1 month ${N.pct(p.second.period)}", "${price(p)} · ${N.pct(p.second.day, 2)}", N.tone(p.second.day)) })
            .note("$RISK These are global quote-feed prices, not the price on any Indian exchange, and they do not include fees or Indian taxes.")
            .sources(GlobalKit.SOURCE).pills("/gold", "/fx", "/global")
            .json("Crypto on ${GlobalKit.date(q.date)}: " + qs.joinToString(", ") { p -> "${p.second.label} ${price(p)} (${N.pct(p.second.day, 2)} $since, 1 month ${N.pct(p.second.period)})" } +
                ". Indicative prices; crypto is highly volatile. Source: ${GlobalKit.SOURCE}.")
    }
}
