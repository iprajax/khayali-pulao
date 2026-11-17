package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.s

/**
 * /flow SYMBOL — a company's latest quarter as a Sankey: where each rupee of income went
 * (staff, materials, other costs, depreciation, interest, tax) and what was left as profit,
 * plus the trend of sales and profit over the last five reported quarters.
 */
object ResultsFlowFeature : Feature {
    override val id = "flow"
    override val usage = "/flow INFY"
    override val help = "Quarterly results as a money-flow (Sankey): income → costs → tax → profit, with a 5-quarter trend"
    override val group = Group.STOCK
    override val triggers = listOf(Regex("""(?i)\b(sankey|money flow|income statement|where .* (revenue|money) go)"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val sym = f.symbol(arg) ?: return Card.error("Which company? Try /flow INFY.")
        // Latest filings first: the company snapshot lists each quarter's results and links its XBRL filing.
        val snapshot = f.nse("top-corp-info?symbol=" + com.khayali.pulao.ai.WebData.encode(sym) + "&market=equities")
        val quarters = quarters(snapshot)
        val latest = quarters.firstOrNull()
        if (latest != null && Hosts.allowed(latest.xbrl)) {
            Xbrl.parse(runCatching { f.get(latest.xbrl) }.getOrDefault(""))?.let { return fromFiling(sym, it, quarters) }
        }
        // Older companies / failures: NSE's results-comparison feed (stops at the old filing format).
        return build(sym, f.nse("results-comparision?symbol=" + com.khayali.pulao.ai.WebData.encode(sym)))
    }

    /** One quarter from the snapshot: period end, income, profit after tax (₹ crore), link to its filing. */
    data class Quarter(val to: java.time.LocalDate, val income: Double, val profit: Double, val consolidated: Boolean, val xbrl: String)

    fun quarters(json: String): List<Quarter> {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)
        val all = J.obj(json)?.let { J.obj(it.toString())?.getAsJsonObject("financial_results")?.let { fr -> J.arr(fr.toString(), "data") } }.orEmpty().mapNotNull { o ->
            val to = runCatching { java.time.LocalDate.parse(o.s("to_date"), fmt) }.getOrNull() ?: return@mapNotNull null
            Quarter(to, (o.d("income") ?: return@mapNotNull null) / 100.0, (o.d("proLossAftTax") ?: 0.0) / 100.0, !o.s("consolidated").startsWith("Non", true), o.s("xbrl_attachment"))
        }
        // one basis throughout (whichever the latest filing used), newest first
        val basis = all.maxByOrNull { it.to }?.consolidated ?: return emptyList()
        return all.filter { it.consolidated == basis }.sortedByDescending { it.to }.distinctBy { it.to }
    }

    private fun quarterOf(d: java.time.LocalDate) = quarter(d.format(java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH)).uppercase())

    /** Pure: a parsed filing (+ the snapshot's quarter list for the trend) → card. */
    fun fromFiling(symbol: String, p: Xbrl.Pnl, quarters: List<Quarter>): String {
        val costs = listOf("Staff" to p.staff, "Materials" to p.materials, "Depreciation" to p.depreciation, "Interest" to p.finance)
        val (big, small) = costs.filter { it.second > 0 }.partition { it.second >= p.income * 0.03 }
        val flows = big + ("Other exp." to p.otherExpenses + small.sumOf { it.second })
        val label = runCatching { quarterOf(java.time.LocalDate.parse(p.to)) }.getOrDefault(p.to)
        val prev = quarters.getOrNull(1); val yoy = quarters.getOrNull(4)
        fun g(now: Double, then: Double?) = then?.takeIf { it > 0 }?.let { (now / it - 1) * 100 }
        return card(symbol, label, p.revenue, p.otherIncome, flows, p.beforeTax, p.tax, p.profit, p.eps?.let { "₹" + N.num(it) },
            listOfNotNull(g(p.income, prev?.income)?.let { "Income, q/q" to N.pct(it) }, g(p.income, yoy?.income)?.let { "Income, y/y" to N.pct(it) },
                g(p.profit, prev?.profit)?.let { "Profit, q/q" to N.pct(it) }, g(p.profit, yoy?.profit)?.let { "Profit, y/y" to N.pct(it) }),
            quarters.take(5).reversed().map { quarterOf(it.to) to listOf(it.income, it.profit) }, "Income and net profit",
            "${if (p.standalone) "Standalone" else "Consolidated"} figures from the company's XBRL filing with NSE",
            g(p.profit, yoy?.profit))
    }

    private fun quarter(to: String): String {        // "31-DEC-2024" → "Q3 FY25"
        val p = to.split('-'); if (p.size != 3) return to
        val month = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC").indexOf(p[1].uppercase()) + 1
        val year = p[2].toIntOrNull() ?: return to
        val fy = if (month >= 4) year + 1 else year
        return "Q${when (month) { in 4..6 -> 1; in 7..9 -> 2; in 10..12 -> 3; else -> 4 }} FY${fy % 100}"
    }

    /** Pure: NSE results-comparison JSON (figures in ₹ lakh) → card (figures in ₹ crore). */
    fun build(symbol: String, json: String): String {
        val root = J.obj(json) ?: return Card.error("Couldn't read results for $symbol from NSE.")
        if (root.s("bankNonBnking") == "B") return Card.error("$symbol reports in the banking format, which this money-flow view doesn't cover yet. Try /results $symbol.")
        val qs = J.arr(json, "resCmpData").filter { (it.d("re_net_sale") ?: 0.0) > 0 }
        val q = qs.firstOrNull() ?: return Card.error("NSE has no quarterly results on file for $symbol.")
        fun cr(k: String, o: com.google.gson.JsonObject = q) = (o.d(k) ?: 0.0) / 100.0            // lakh → crore
        val sales = cr("re_net_sale"); val other = cr("re_oth_inc_new"); val income = sales + other
        val staff = cr("re_staff_cost"); val material = cr("re_rawmat_consump") + cr("re_pur_trd_goods"); val dep = cr("re_depr_und_exp"); val interest = cr("re_int_new")
        val otherExp = cr("re_oth_exp"); val tax = cr("re_tax"); val profit = cr("re_net_profit"); val pbt = cr("re_pro_loss_bef_tax")
        // Cost lines under 3% of income are folded into "Other costs" so the chart stays readable on a phone.
        val raw = listOf("Staff" to staff, "Materials" to material, "Depreciation" to dep, "Interest" to interest).filter { it.second > 0 }
        val (big, small) = raw.partition { it.second >= income * 0.03 }
        val costs = (big + ("Other exp." to otherExp + small.sumOf { it.second })).filter { it.second > 0 }
        val label = quarter(q.s("re_to_dt"))
        val prev = qs.getOrNull(1); val yoy = qs.getOrNull(4)
        fun growth(o: com.google.gson.JsonObject?, k: String) = o?.let { val b = cr(k, it); if (b > 0) (cr(k) / b - 1) * 100 else null }
        return card(symbol, label, sales, other, costs, pbt, tax, profit, q.s("re_basic_eps_for_cont_dic_opr").takeIf { it.isNotBlank() }?.let { "₹$it" },
            listOfNotNull(growth(prev, "re_net_sale")?.let { "Sales, q/q" to N.pct(it) }, growth(yoy, "re_net_sale")?.let { "Sales, y/y" to N.pct(it) },
                growth(prev, "re_net_profit")?.let { "Profit, q/q" to N.pct(it) }, growth(yoy, "re_net_profit")?.let { "Profit, y/y" to N.pct(it) }),
            qs.take(5).reversed().map { o -> quarter(o.s("re_to_dt")) to listOf(cr("re_net_sale", o), cr("re_net_profit", o)) }, "Sales and net profit",
            "Standalone figures as filed with NSE (${if (q.s("re_res_type") == "A") "audited" else "unaudited"}). This older feed's latest quarter is $label — newer filings were not available",
            growth(yoy, "re_net_profit"))
    }

    private fun card(symbol: String, label: String, sales: Double, other: Double, costs: List<Pair<String, Double>>, pbt: Double, tax: Double, profit: Double,
                     eps: String?, growth: List<Pair<String, String>>, trend: List<Pair<String, List<Double>>>, trendTitle: String, basis: String, yoy: Double?): String {
        val income = sales + other
        val nodes = mutableListOf<Map<String, Any>>(); val links = mutableListOf<Map<String, Any>>()
        fun node(id: String, label: String, col: Int, color: String) { nodes += mapOf("id" to id, "label" to label, "col" to col, "color" to color) }
        fun link(a: String, b: String, v: Double) { if (v > 0) links += mapOf("from" to a, "to" to b, "value" to v) }
        node("sales", "Sales", 0, "blue"); if (other > 0) node("other", "Other inc.", 0, "violet")
        node("income", "Income", 1, "blue"); link("sales", "income", sales); link("other", "income", other)
        costs.filter { it.second > 0 }.forEach { (k, v) -> node(k, k, 2, "saffron"); link("income", k, v) }
        if (pbt > 0) { node("pbt", "Pre-tax", 2, "green"); link("income", "pbt", pbt) }
        if (tax > 0) { node("tax", "Tax", 3, "red"); link("pbt", "tax", tax) }
        if (profit > 0) { node("profit", "Net profit", 3, "green"); link("pbt", "profit", profit) }
        val margin = if (sales > 0) profit / sales * 100 else 0.0
        val c = Card("$symbol · $label results · money flow", if (profit >= 0) "green" else "red")
            .headline(N.cr(profit) + " net profit", "on sales of ${N.cr(sales)} · net margin ${N.pct(margin).removePrefix("+")}", N.tone(profit))
            .viz("sankey", 300, mapOf("unit" to "₹ Cr", "nodes" to nodes, "links" to links), "Band widths and figures in ₹ crore. Left to right: income, where it went (staff, other expenses, depreciation, interest), tax, and the profit left.")
            .stats(*(growth + listOfNotNull(eps?.let { "EPS (basic)" to it })).toTypedArray())
        if (trend.size >= 2) c.viz("groupbars", 200, mapOf("unit" to "₹ Cr", "series" to trendTitle.split(" and ").map { it.replaceFirstChar { ch -> ch.uppercase() } },
            "items" to trend.map { (q, v) -> mapOf("label" to q, "values" to v) }), "$trendTitle (₹ crore) by quarter.")
        return c.note("$basis. Latest quarter: $label.").sources("NSE").pills("/shareholding $symbol", "/events $symbol", "/price $symbol", "/news $symbol")
            .put("symbol", symbol).put("quarter", label)
            .json("$symbol $label: sales ${N.cr(sales)}, net profit ${N.cr(profit)} (net margin ${N.pct(margin).removePrefix("+")}), tax ${N.cr(tax)}" +
                (yoy?.let { "; profit ${N.pct(it)} vs a year ago" } ?: "") + ". ${basis.substringBefore(" (")}.")
    }
}
