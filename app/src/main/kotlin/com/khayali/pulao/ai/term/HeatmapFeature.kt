package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.WebData
import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.s

/** /heatmap [index] — every stock of an index as a tile: size = traded value, colour = today's move. */
object HeatmapFeature : Feature {
    override val id = "heatmap"
    override val usage = "/heatmap NIFTY BANK"
    override val help = "Index heatmap: every constituent sized by traded value, coloured by today's move"
    override val group = Group.MARKET
    override val triggers = listOf(Regex("""(?i)\b(heat ?map|market map)\b"""))

    /** "bank" → "NIFTY BANK", "" → "NIFTY 50", "nifty it" → "NIFTY IT". */
    fun index(arg: String): String {
        val a = arg.trim().uppercase().replace(Regex("""\s+"""), " ")
        return when {
            a.isEmpty() || a == "NIFTY" || a == "NIFTY50" -> "NIFTY 50"
            a.startsWith("NIFTY") -> a
            else -> "NIFTY $a"
        }
    }

    override suspend fun run(arg: String, f: Fetch): String {
        val index = index(arg)
        return build(index, f.nse("NextApi/apiClient/indexTrackerApi?functionName=getIndicesHeatMap&&index=" + WebData.encode(index).replace("+", "%20")), f.holdings.map { it.name.uppercase() }.toSet())
    }

    /** Pure: feed JSON → card. [mine] marks the user's own holdings (matched on the phone). */
    fun build(index: String, json: String, mine: Set<String> = emptySet()): String {
        val rows = J.arr(json, "data").mapNotNull { o -> val p = o.d("pchange") ?: return@mapNotNull null; Triple(o.s("symbol"), p, o.d("tradedValue") ?: 1.0) to (o.d("lastPrice") ?: 0.0) }
        if (rows.isEmpty()) return Card.error("No heatmap data for $index. Try /heatmap NIFTY BANK or /indices for the list.")
        val up = rows.count { it.first.second > 0 }; val down = rows.count { it.first.second < 0 }
        val sorted = rows.sortedByDescending { it.first.second }
        val best = sorted.first().first; val worst = sorted.last().first
        val avg = rows.map { it.first.second }.average()
        return Card("$index · heatmap · ${rows.size} stocks", if (up >= down) "green" else "red")
            .headline("$up up · $down down", "Average move ${N.pct(avg, 2)} · best ${best.first} ${N.pct(best.second)} · worst ${worst.first} ${N.pct(worst.second)}", N.tone(avg))
            .viz("treemap", 300, mapOf("items" to rows.map { (t, price) -> mapOf("label" to t.first, "value" to t.third, "change" to t.second, "price" to price, "mine" to (t.first.uppercase() in mine)) }),
                "Tile size: today's traded value. Colour: today's change (%). ★ marks your holdings. Tap a tile.")
            .rows("Biggest movers", (sorted.take(3) + sorted.takeLast(3)).map { (t, price) -> Card.Row(t.first, "₹" + N.num(price), N.pct(t.second, 2), N.tone(t.second)) })
            .sources("NSE").pills("/movers", "/sectors", "/breadth", if (index == "NIFTY 50") "/heatmap NIFTY BANK" else "/heatmap")
            .json("$index today: $up stocks up and $down down, average move ${N.pct(avg, 2)}. Best ${best.first} ${N.pct(best.second, 2)}, worst ${worst.first} ${N.pct(worst.second, 2)}. Source: NSE.")
    }
}
