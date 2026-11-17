package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.s

/** /etfs [word] — exchange-traded funds on NSE, most traded first, with price and the day's change. */
object FlowsEtfsFeature : Feature {
    override val id = "etfs"
    override val usage = "/etfs gold"
    override val help = "Exchange-traded funds: the most traded, with price and day change — add a word to filter"
    override val group = Group.FUNDS
    override val triggers = listOf(Regex("""(?i)\b(top|most traded|most active|active|list of) etfs?\b"""), Regex("""(?i)\betfs? (list|today)\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(f.nse("etf"), arg)

    private class Etf(val symbol: String, val about: String, val price: Double, val change: Double, val traded: Double, val units: Double)

    /** Pure: feed JSON → card. [filter] keeps ETFs whose symbol or underlying mentions the word ("gold", "bank", "liquid"). */
    fun build(json: String, filter: String = ""): String {
        val root = J.obj(json)
        val all = J.arr(json, "data").mapNotNull { o -> val p = o.d("ltP"); val c = o.d("per"); if (p == null || c == null || o.s("symbol").isEmpty()) null
            else Etf(o.s("symbol"), o.s("assets"), p, c, o.d("trdVal") ?: 0.0, o.d("qty") ?: 0.0) }
        if (all.isEmpty()) return Card.error("No ETF data from NSE right now. Try again in a minute.")
        val word = filter.trim().removePrefix("@").lowercase()
        val list = (if (word.isEmpty()) all else all.filter { it.symbol.lowercase().contains(word) || it.about.lowercase().contains(word) }).sortedWith(compareByDescending<Etf> { it.traded }.thenByDescending { it.units })
        if (list.isEmpty()) return Card.error("No ETF on NSE matches \"${filter.trim()}\". Try /etfs gold, /etfs bank or /etfs for all ${all.size}.")
        val asOf = Flows.stamp(root?.s("timestamp").orEmpty())
        val up = list.count { it.change > 0 }; val down = list.count { it.change < 0 }
        val top = list.take(8); val first = top.first(); val total = list.sumOf { it.traded }
        fun cr(v: Double) = Flows.round(v / 1e7)
        fun about(e: Etf) = "${Flows.clip(e.about, 60)} · ₹${N.num(e.price)}"
        val card = Card(listOfNotNull("ETFs on NSE", word.takeIf { it.isNotEmpty() }?.let { "\"$it\"" }, asOf.takeIf { it.isNotEmpty() }).joinToString(" · "), "blue")
            .headline("${first.symbol} · ${Flows.rupees(first.traded)}", "most traded of ${list.size} ETFs · $up up, $down down · all together ${Flows.rupees(total)} traded")
            .viz("netbars", 60 + top.size * 26, mapOf("unit" to "₹ Cr", "horizontal" to true, "color" to "blue", "yLabel" to "ETF", "xLabel" to "Traded value (₹ Cr)",
                "items" to top.map { mapOf("label" to it.symbol, "value" to cr(it.traded), "note" to about(it)) }),
                "The ${top.size} most traded ETFs by value traded, ₹ crore. Tap a bar.")
            .viz("netbars", 60 + top.size * 26, mapOf("unit" to "%", "signed" to true, "horizontal" to true, "yLabel" to "ETF", "xLabel" to "Day change (%)",
                "items" to top.map { mapOf("label" to it.symbol, "value" to it.change, "note" to about(it)) }),
                "Price change against the previous close for the same ETFs, in %.")
            .rows("Most traded", top.map { Card.Row(it.symbol, Flows.clip(it.about, 44) + " · " + Flows.rupees(it.traded), "₹${N.num(it.price)} · ${N.pct(it.change, 2)}", N.tone(it.change)) })
            .note("Prices are as of the time shown and may be delayed. An ETF's traded price can differ from the value of what it holds (its NAV), most of all when few units trade.")
            .sources("NSE").pills(*(if (word.isEmpty()) arrayOf("/etfs gold", "/sgb", "/gold", "/indices") else arrayOf("/etfs", "/sgb", "/gold", "/fund")))
            .put("as_of", asOf)
        val second = top.getOrNull(1)
        fun say(e: Etf) = "${e.symbol} (${Flows.rupees(e.traded)} traded, ₹${N.num(e.price)}, ${N.pct(e.change, 2)})"
        return card.json("ETFs on NSE" + (if (word.isNotEmpty()) " matching \"$word\"" else "") + (if (asOf.isNotEmpty()) " as of $asOf" else "") + ": most traded ${say(first)}" +
            (second?.let { ", then ${say(it)}" } ?: "") + "; $up of ${list.size} rose and $down fell. Source: NSE.")
    }
}

/** /sgb — sovereign gold bonds traded on NSE: last price against the issue price. */
object FlowsSgbFeature : Feature {
    override val id = "sgb"
    override val usage = "/sgb"
    override val help = "Sovereign gold bonds on NSE: last traded price against issue price, most traded first"
    override val group = Group.FUNDS
    override val triggers = listOf(Regex("""(?i)\b(sgbs?|sovereign gold bonds?|gold bonds?)\b"""))

    override suspend fun run(arg: String, f: Fetch): String = build(f.nse("sovereign-gold-bonds"))

    private class Bond(val symbol: String, val price: Double, val issue: Double, val change: Double, val units: Double, val traded: Double) { val gain get() = (price / issue - 1) * 100 }

    /** Pure: feed JSON → card. */
    fun build(json: String): String {
        val root = J.obj(json)
        val all = J.arr(json, "data").mapNotNull { o -> val p = o.d("ltP"); val i = o.d("issue_price"); if (p == null || i == null || i <= 0 || o.s("symbol").isEmpty()) null
            else Bond(o.s("symbol"), p, i, o.d("per") ?: 0.0, o.d("qty") ?: 0.0, o.d("trdVal") ?: 0.0) }.sortedByDescending { it.traded }
        if (all.isEmpty()) return Card.error("No sovereign gold bond data from NSE right now. Try again in a minute.")
        val asOf = Flows.stamp(root?.s("timestamp").orEmpty())
        val traded = all.filter { it.units > 0 }
        val top = traded.ifEmpty { all }.take(8); val first = top.first()
        val up = all.count { it.change > 0 }; val down = all.count { it.change < 0 }
        fun short(b: Bond) = b.symbol.removePrefix("SGB")
        fun vsIssue(b: Bond) = N.pct(kotlin.math.abs(b.gain)).removePrefix("+") + if (b.gain >= 0) " above" else " below"
        return Card("Sovereign gold bonds on NSE" + (if (asOf.isNotEmpty()) " · $asOf" else ""), "saffron")
            .headline("${first.symbol} ₹${N.num(first.price)}", "most traded · ${vsIssue(first)} its issue price of ₹${N.num(first.issue, 0)} · ${N.pct(first.change, 2)} today", N.tone(first.change))
            .viz("groupbars", 210, mapOf("unit" to "₹ per gram", "xLabel" to "Bond (SGB…)", "series" to listOf("Issue price", "Last price"),
                "items" to top.take(5).map { mapOf("label" to short(it), "values" to listOf(it.issue, it.price)) }),
                "Issue price and last traded price, ₹ per gram (one unit = one gram of gold), for the ${top.take(5).size} most traded bonds.")
            .viz("netbars", 60 + top.size * 26, mapOf("unit" to "%", "signed" to true, "horizontal" to true, "yLabel" to "Bond", "xLabel" to "Last price vs issue price (%)",
                "items" to top.map { mapOf("label" to it.symbol, "value" to Flows.round(it.gain, 1), "note" to "Issue ₹${N.num(it.issue, 0)} · last ₹${N.num(it.price)}") }),
                "How far each bond's last price is above or below its issue price, in %. Tap a bar.")
            .rows("Most traded", top.take(6).map { Card.Row(it.symbol, "Issue ₹${N.num(it.issue, 0)} · ${Flows.count(it.units)} units traded", "₹${N.num(it.price)} · ${N.pct(it.change, 2)}", N.tone(it.change)) })
            .stats("Bonds listed" to all.size.toString(), "Traded in this session" to traded.size.toString(), "Up / down" to "$up / $down", "Value traded" to Flows.rupees(all.sumOf { it.traded }))
            .note("The comparison with issue price leaves out the interest these bonds pay (2.5% a year on most series). Few units trade, so exchange prices can sit above or below the gold price. Prices are as of the time shown and may be delayed.")
            .sources("NSE").pills("/gold", "/etfs gold", "/fx", "/fund")
            .put("as_of", asOf)
            .json("${all.size} sovereign gold bonds on NSE" + (if (asOf.isNotEmpty()) " as of $asOf" else "") + "; most traded ${first.symbol} at ₹${N.num(first.price)} (${N.pct(first.change, 2)} today), " +
                "${vsIssue(first)} its ₹${N.num(first.issue, 0)} issue price. $up rose and $down fell. Source: NSE.")
    }
}
