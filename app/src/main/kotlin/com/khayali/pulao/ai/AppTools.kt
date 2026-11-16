package com.khayali.pulao.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.khayali.pulao.Base
import com.khayali.pulao.Bench
import com.khayali.pulao.Costs
import com.khayali.pulao.Dreams
import com.khayali.pulao.Fmt
import com.khayali.pulao.Projector
import com.khayali.pulao.PulaoMaths
import com.khayali.pulao.QtyMode
import com.khayali.pulao.Stock
import com.khayali.pulao.TaxKind
import kotlin.math.floor

/**
 * Everything the chat model may touch, as a narrow interface. The Android side
 * implements it over Prefs / the overlay; tests implement it with a fake.
 * There is deliberately NO way to reach Kite's Buy/Sell or place orders.
 */
interface ToolHost {
    /** Stock the user tapped (overlay) or picked (demo); null in the plain app chat. */
    val stock: Stock?
    fun setting(name: String): Any?
    /** Returns null on success, or a human-readable error. */
    fun updateSetting(name: String, value: String): String?
    /** Open an app screen / sheet tab. Returns false if unknown. */
    fun open(target: String): Boolean
}

/** One tool call as seen by the UI (for the "✓ Calculated vs FD" chips). */
data class ToolEvent(val name: String, val label: String, val ok: Boolean, val resultJson: String = "{}", val argsJson: String = "{}")

/**
 * The tool catalogue (OpenAPI-style JSON for Gemma 4 function calling) and a pure executor.
 * Every number the model says should come from here, so it can't invent maths.
 */
class AppTools(private val host: ToolHost, private val onEvent: (ToolEvent) -> Unit = {}) {

    private val gson = Gson()

    data class Def(val name: String, val label: String, val description: String, val params: Map<String, Param>, val required: List<String> = emptyList())
    data class Param(val type: String, val description: String, val enum: List<String>? = null)

    val defs: List<Def> = listOf(
        Def("get_current_stock", "Read the stock",
            "Get the stock or portfolio the user is looking at right now (name, quantity, average price, last price, invested, current value, P&L). Call this first whenever the user says 'this', 'my stock' or 'my portfolio'.",
            emptyMap()),
        Def("project_move", "Projected a move",
            "What a holding/stock/portfolio is worth if its price moves by a percentage. Uses the current stock unless ltp is given.",
            mapOf(
                "percent" to Param("number", "Price move in percent, e.g. 10 for +10%, -5 for -5%"),
                "from" to Param("string", "Start from the user's average buy price or today's price", listOf("avg", "ltp")),
                "ltp" to Param("number", "Optional: current price per share if no stock is selected"),
                "qty" to Param("number", "Optional: number of shares if no stock is selected"),
                "avg" to Param("number", "Optional: average buy price if no stock is selected"),
            ), listOf("percent")),
        Def("compare_with_fd", "Compared with FD",
            "Compare the holding/portfolio's real result with a bank FD, PPF, gold, Nifty 50 and inflation over the years it has been held.",
            mapOf("years_held" to Param("number", "How many years the user has held it, e.g. 2 or 0.5")), listOf("years_held")),
        Def("goal_plan", "Planned a goal",
            "How much the current value must grow to reach a target amount in rupees, and roughly how many years at different yearly returns.",
            mapOf("target_rupees" to Param("string", "Goal amount as the user said it, e.g. \"10 lakh\", \"1 crore\", \"2500000\""),
                "current_rupees" to Param("string", "Optional: starting amount, e.g. \"5 lakh\"")), listOf("target_rupees")),
        Def("grow_for_years", "Compounded",
            "Future value of the current value (or a given amount) compounding at a yearly return for some years.",
            mapOf("yearly_return_percent" to Param("number", "e.g. 12"), "years" to Param("number", "e.g. 10"),
                "amount_rupees" to Param("string", "Amount, e.g. \"1 lakh\", \"50000\", \"2.5 crore\"")), listOf("yearly_return_percent", "years")),
        Def("in_hand_after_tax", "Estimated tax",
            "Estimated money in hand if the user sells: gain after Indian equity capital-gains tax (LTCG 12.5% above ₹1.25 lakh, or STCG 20%), STT and charges.",
            mapOf("gain_rupees" to Param("string", "Optional: the profit, e.g. \"1.2 lakh\""),
                "sell_value_rupees" to Param("string", "Optional: total sale value, e.g. \"4 lakh\""),
                "held_over_one_year" to Param("boolean", "Optional: true if held more than 12 months"))),
        Def("regret", "Ran a regret check",
            "'If only I had bought at a lower price': value today of the same shares bought at a past price.",
            mapOf("past_price" to Param("number", "The price per share the user wishes they had bought at")), listOf("past_price")),
        Def("pulao_maths", "Did pulao maths",
            "Express a rupee amount in fun desi units (cutting chais, train tickets, plots in Ballia…). Use only when the user wants something fun.",
            mapOf("amount_rupees" to Param("string", "Amount, e.g. \"1 lakh\", \"50000\", \"2.5 crore\"")), listOf("amount_rupees")),
        Def("get_settings", "Read settings",
            "Read the user's assumptions: FD rate, savings, PPF, gold, Nifty, inflation, monthly expenses, tone, ladder steps.", emptyMap()),
        Def("update_setting", "Changed a setting",
            "Change one app setting when the user explicitly asks.",
            mapOf("name" to Param("string", "Setting to change", SETTINGS),
                "value" to Param("string", "New value, e.g. '7.1' for rates, 'serious'/'light'/'masala' for tone, 'Thar for the farm|1500000' for dream_unit")),
            listOf("name", "value")),
        Def("open_screen", "Opened a screen",
            "Open a part of the app for the user.",
            mapOf("target" to Param("string", "Where to go", TARGETS)), listOf("target")),
    )

    /** OpenAPI function JSON for one tool. */
    fun schemaJson(d: Def): String {
        val props = JsonObject()
        for ((k, p) in d.params) {
            props.add(k, JsonObject().apply {
                addProperty("type", p.type); addProperty("description", p.description)
                p.enum?.let { e -> add("enum", gson.toJsonTree(e)) }
            })
        }
        return JsonObject().apply {
            addProperty("name", d.name)
            addProperty("description", d.description)
            add("parameters", JsonObject().apply {
                addProperty("type", "object"); add("properties", props)
                add("required", gson.toJsonTree(d.required))
            })
        }.toString()
    }

    /** Run a tool. Always returns JSON (result or {"error": …}); never throws. */
    fun execute(name: String, argsJson: String): String {
        val def = defs.firstOrNull { it.name == name }
        val result = runCatching {
            val a = if (argsJson.isBlank()) JsonObject() else JsonParser.parseString(argsJson).asJsonObject
            when (name) {
                "get_current_stock" -> currentStock()
                "project_move" -> projectMove(a)
                "compare_with_fd" -> compareFd(a)
                "goal_plan" -> goalPlan(a)
                "grow_for_years" -> grow(a)
                "in_hand_after_tax" -> inHand(a)
                "regret" -> regret(a)
                "pulao_maths" -> mapOf("text" to (PulaoMaths.describe(a.num("amount_rupees")!!) ?: "less than a cutting chai"))
                "get_settings" -> SETTINGS.associateWith { host.setting(it) }
                "update_setting" -> {
                    val n = a.str("name")!!; val v = a.str("value")!!
                    host.updateSetting(n, v)?.let { mapOf("error" to it) } ?: mapOf("ok" to true, "name" to n, "value" to v)
                }
                "open_screen" -> {
                    val t = a.str("target")!!
                    if (host.open(t)) mapOf("ok" to true, "opened" to t) else mapOf("error" to "Unknown screen '$t'. Use one of $TARGETS")
                }
                else -> mapOf("error" to "Unknown tool '$name'")
            }
        }.getOrElse { mapOf("error" to (it.message ?: "Couldn't calculate that")) }
        val ok = !(result is Map<*, *> && result.containsKey("error"))
        val json = gson.toJson(result)
        onEvent(ToolEvent(name, def?.label ?: name, ok, json, argsJson.ifBlank { "{}" }))
        return json
    }

    // ------------------------------------------------------------------ tools

    private fun currentStock(): Any = when (val s = host.stock) {
        null -> mapOf("error" to "No stock is selected. Ask the user which stock and its price/quantity, or to tap one in Kite.")
        is Stock.Holding -> mapOf("type" to "holding", "name" to s.name, "qty" to s.qty, "avg_price" to s.avg, "ltp" to s.ltp,
            "invested" to r(s.invested), "current_value" to r(s.current), "pnl" to r(s.pnl), "pnl_percent" to r1(s.pnlPct))
        is Stock.Quote -> mapOf("type" to "watchlist", "name" to s.name, "exchange" to s.exchange, "ltp" to s.ltp,
            "note" to "Not owned. Assume an amount (default ₹${Fmt.amount(host.setting("watch_amount") as? Double ?: 10000.0)}) unless the user says.")
        is Stock.Portfolio -> mapOf("type" to "portfolio", "invested" to r(s.invested), "current_value" to r(s.current),
            "pnl" to r(s.pnl), "pnl_percent" to r1(s.pnlPct))
    }

    private fun stockOrArgs(a: JsonObject): Stock {
        a.num("ltp")?.let { ltp ->
            val qty = a.num("qty"); val avg = a.num("avg")
            return if (qty != null && avg != null) Stock.Holding("Your stock", qty, avg, qty * avg, ltp) else Stock.Quote("Stock", "NSE", ltp)
        }
        return host.stock ?: error("No stock selected — ask the user for the price (and quantity/average if they own it).")
    }

    private fun projectMove(a: JsonObject): Any {
        val pct = a.num("percent")!!
        val s = stockOrArgs(a)
        val base = if (a.str("from") == "avg") Base.AVG else Base.LTP
        val amt = host.setting("watch_amount") as? Double ?: 10000.0
        val d = Projector.project(s, pct, base, QtyMode.AMOUNT, amt)
        return mapOf("summary" to "If ${s.name} moves ${Fmt.pct(pct)} (from ${if (base == Base.AVG) "your average" else "today's"} price), it becomes ${inr(d.price, 2)} a share; ${sh(d.qty)} worth ${inr(d.value)}, ${signed(d.profit)} (${Fmt.pct1(d.profitPct)}) on ${inr(d.cost)} invested, ${signed(d.vsNow)} vs today.",
            "stock" to s.name, "move_percent" to pct, "from" to if (base == Base.AVG) "avg" else "ltp",
            "price_after" to r2(d.price), "shares" to d.qty, "value_after" to r(d.value), "cost" to r(d.cost),
            "profit_vs_cost" to r(d.profit), "profit_percent" to r1(d.profitPct), "change_vs_today" to r(d.vsNow))
    }

    private fun investedCurrent(): Pair<Double, Double> = when (val s = host.stock) {
        is Stock.Holding -> s.invested to s.current
        is Stock.Portfolio -> s.invested to s.current
        else -> error("Comparing with FD needs a holding or the portfolio. Ask the user to tap one, or to give invested and current values.")
    }

    private fun compareFd(a: JsonObject): Any {
        val years = a.num("years_held")!!
        require(years > 0) { "years_held must be more than 0" }
        val (inv, cur) = investedCurrent()
        val rates = rates()
        val rows = Bench.list(rates["fd"]!!, rates["inflation"]!!, rates["savings"]!!, rates["ppf"]!!, rates["gold"]!!, rates["nifty"]!!)
            .map { b -> val v = Dreams.compound(inv, b.ratePct, years); mapOf("option" to b.name, "rate" to b.ratePct, "would_be" to r(v), "you_vs_it" to r(cur - v)) }
        val fdV = Dreams.compound(inv, rates["fd"]!!, years)
        val cagr = Bench.cagr(inv, cur, years)
        return mapOf("summary" to "${inr(inv)} became ${inr(cur)} in ${r1(years)} years" + (cagr?.let { " (${Fmt.pct1(it)} a year)" } ?: "") +
            ". A ${rates["fd"]}% FD would have made it ${inr(fdV)}, so you are ${signed(cur - fdV)} vs the FD. In today's money it is ${inr(Bench.real(cur, rates["inflation"]!!, years))}.",
            "invested" to r(inv), "current" to r(cur), "years" to years,
            "your_yearly_return_percent" to Bench.cagr(inv, cur, years)?.let { r1(it) },
            "real_value_after_inflation" to r(Bench.real(cur, rates["inflation"]!!, years)),
            "beat_fd" to (cur >= Dreams.compound(inv, rates["fd"]!!, years)), "comparison" to rows)
    }

    private fun nowValue(a: JsonObject, key: String): Double = a.num(key) ?: when (val s = host.stock) {
        is Stock.Holding -> s.current
        is Stock.Portfolio -> s.current
        is Stock.Quote -> floor((host.setting("watch_amount") as? Double ?: 10000.0) / s.ltp) * s.ltp
        null -> error("No amount — ask the user how much money we're talking about.")
    }

    private fun goalPlan(a: JsonObject): Any {
        val goal = a.num("target_rupees")!!
        val now = nowValue(a, "current_rupees")
        val y12 = Dreams.yearsToGoal(now, goal, 12.0)
        return mapOf("summary" to if (goal <= now) "You already have ${inr(now)}, above the goal of ${inr(goal)}."
            else "To grow ${inr(now)} into ${inr(goal)} it must rise ${Fmt.pct1(Dreams.pctToGoal(now, goal))} (${r2(goal / now)}×); at 12% a year that takes about ${y12?.let { Dreams.years(it) }}.",
            "now" to r(now), "goal" to r(goal), "growth_needed_percent" to r1(Dreams.pctToGoal(now, goal)),
            "multiple_needed" to r2(goal / now), "already_there" to (goal <= now),
            "years_at" to listOf(8.0, 12.0, 15.0, 20.0, 25.0).associate { "${it.toInt()}%" to Dreams.yearsToGoal(now, goal, it)?.let { y -> r1(y) } })
    }

    private fun grow(a: JsonObject): Any {
        val rate = a.num("yearly_return_percent")!!; val years = a.num("years")!!
        val now = nowValue(a, "amount_rupees")
        val fv = Dreams.compound(now, rate, years)
        val real = Bench.real(fv, rates()["inflation"]!!, years)
        return mapOf("summary" to "${inr(now)} at ${r1(rate)}% a year for ${r1(years)} years becomes ${inr(fv)} (${r2(fv / now)}×). In today's money (after ${rates()["inflation"]}% inflation) that is ${inr(real)}.",
            "start" to r(now), "yearly_return_percent" to rate, "years" to years, "future_value" to r(fv),
            "multiple" to r2(fv / now), "doubles_every_years" to Dreams.doublingYears(rate)?.let { r1(it) },
            "in_todays_money" to r(Bench.real(fv, rates()["inflation"]!!, years)))
    }

    private fun inHand(a: JsonObject): Any {
        val s = host.stock
        val (gain, sell) = when {
            a.num("gain_rupees") != null -> a.num("gain_rupees")!! to (a.num("sell_value_rupees") ?: a.num("gain_rupees")!!)
            s is Stock.Holding -> s.pnl to s.current
            s is Stock.Portfolio -> s.pnl to s.current
            else -> error("Tell me the profit (and sale value) to estimate tax.")
        }
        val kind = when (a.get("held_over_one_year")?.takeIf { !it.isJsonNull }?.asBoolean) {
            true -> TaxKind.LTCG; false -> TaxKind.STCG; null -> TaxKind.LTCG
        }
        val charges = Costs.sellCharges(sell)
        val taxable = gain - charges
        return mapOf("summary" to "On a profit of ${inr(gain)}, estimated ${kind.name} tax is ${inr(Costs.tax(taxable, kind))} and charges ${inr(charges)}, leaving about ${inr(Costs.inHand(gain, sell, kind))} in hand.",
            "gain" to r(gain), "sale_value" to r(sell), "tax_type" to kind.name,
            "assumed" to (a.get("held_over_one_year") == null), "charges" to r(charges),
            "tax" to r(Costs.tax(taxable, kind)), "in_hand" to r(Costs.inHand(gain, sell, kind)),
            "note" to "Estimate only. LTCG: 12.5% above ₹1.25 lakh a year; STCG: 20%.")
    }

    private fun regret(a: JsonObject): Any {
        val past = a.num("past_price")!!
        val s = host.stock ?: error("Which stock? Ask the user to tap one.")
        val qty = when (s) {
            is Stock.Holding -> s.qty
            is Stock.Quote -> floor((host.setting("watch_amount") as? Double ?: 10000.0) / past)
            is Stock.Portfolio -> error("Regret works per stock, not for the whole portfolio.")
        }
        val rg = Dreams.regret(qty, past, s.ltp)
        return mapOf("summary" to "${sh(qty)} of ${s.name} bought at ${inr(past, 2)} would have cost ${inr(rg.paid)} and be worth ${inr(rg.now)} today: ${signed(rg.gain)} (${Fmt.pct1(rg.gainPct)}).",
            "stock" to s.name, "shares" to qty, "would_have_paid" to r(rg.paid), "worth_today" to r(rg.now),
            "gain" to r(rg.gain), "gain_percent" to r1(rg.gainPct),
            "extra_vs_real_pnl" to (s as? Stock.Holding)?.let { r(rg.gain - it.pnl) })
    }

    @Suppress("UNCHECKED_CAST")
    private fun rates(): Map<String, Double> =
        listOf("fd", "savings", "ppf", "gold", "nifty", "inflation").associateWith { (host.setting("${it}_rate") as? Double) ?: DEFAULTS[it]!! }

    // ---------------------------------------------------------------- helpers

    private fun JsonObject.num(k: String): Double? = get(k)?.takeIf { !it.isJsonNull }?.let { e ->
        if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) e.asDouble else Money.parse(e.asString)
    }
    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { !it.isJsonNull }?.asString?.trim()
    private fun r(v: Double) = Math.round(v).toDouble()
    private fun inr(v: Double, decimals: Int = 0) = "₹" + if (decimals == 0) Fmt.amount(v) else Fmt.price(v)
    private fun signed(v: Double) = (if (v < 0) "−₹" else "+₹") + Fmt.amount(kotlin.math.abs(v))
    private fun sh(q: Double) = "${Fmt.qty(q)} shares"
    private fun r1(v: Double) = Math.round(v * 10) / 10.0
    private fun r2(v: Double) = Math.round(v * 100) / 100.0

    companion object {
        val SETTINGS = listOf("fd_rate", "savings_rate", "ppf_rate", "gold_rate", "nifty_rate", "inflation_rate",
            "monthly_expense", "watch_amount", "tone", "compact_numbers", "show_in_hand", "dream_unit")
        val TARGETS = listOf("move", "vs_fd", "spend", "goal", "years", "regret", "settings", "demo", "home")
        private val DEFAULTS = mapOf("fd" to 6.5, "savings" to 2.7, "ppf" to 7.1, "gold" to 10.0, "nifty" to 12.0, "inflation" to 5.0)
    }
}
