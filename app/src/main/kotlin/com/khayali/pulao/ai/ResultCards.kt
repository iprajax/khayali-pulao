package com.khayali.pulao.ai

import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.khayali.pulao.Fmt
import com.khayali.pulao.Kit
import com.khayali.pulao.Pal
import kotlin.math.abs

/**
 * Visual answer cards — one per kind of result, like an artifact next to the chat.
 * Native header (exact figures from the tool JSON) + an animated D3 chart ([VizView]).
 * Numbers never come from the model's text.
 */
object ResultCards {

    fun build(ctx: Context, k: Kit, tool: String, json: String): View? {
        val o = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        if (o.has("error")) return null
        return when (tool) {
            "grow_for_years" -> growth(ctx, k, o)
            "project_move" -> move(ctx, k, o)
            "compare_with_fd" -> versus(ctx, k, o)
            "goal_plan" -> goal(ctx, k, o)
            "in_hand_after_tax" -> tax(ctx, k, o)
            "regret" -> regret(ctx, k, o)
            "pulao_maths" -> pulao(k, o)
            "get_current_stock" -> stock(k, o)
            "kite_account" -> when {
                o.has("holdings") -> holdings(ctx, k, o)
                o.has("positions") -> positions(ctx, k, o)
                o.has("funds") -> funds(ctx, k, o)
                o.has("orders") || o.has("trades") -> orders(ctx, k, o)
                o.has("net") && o.has("cash") -> margins(ctx, k, o)
                o.has("summary") -> note(k, "Kite", o["summary"].asString)
                else -> null
            }
            "kite_chart" -> history(ctx, k, o)
            "kite_quote" -> quotes(ctx, k, o)
            else -> null
        }?.also { animateIn(it, k) }
    }

    /** Every rupee amount a card shows, for checking the model's text against. */
    fun amounts(json: String): List<Double> {
        val o = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Double>()
        fun walk(e: com.google.gson.JsonElement) {
            when {
                e.isJsonObject -> e.asJsonObject.entrySet().forEach { walk(it.value) }
                e.isJsonArray -> e.asJsonArray.forEach { walk(it) }
                e.isJsonPrimitive && e.asJsonPrimitive.isNumber -> out += e.asDouble
                e.isJsonPrimitive && e.asJsonPrimitive.isString ->
                    Regex("""₹([\d,]+(?:\.\d+)?)""").findAll(e.asString).forEach { m -> m.groupValues[1].replace(",", "").toDoubleOrNull()?.let(out::add) }
            }
        }
        walk(o)
        return out
    }

    // -------------------------------------------------------------- shell

    private fun shell(k: Kit, kicker: String, accent: Int, body: LinearLayout.() -> Unit): LinearLayout =
        k.column().apply {
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                k.rounded(Pal.SURFACE, 18f, Pal.alpha(accent, 0.35f)),
                android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Pal.alpha(accent, 0.10f), 0x00000000)).apply { cornerRadius = k.dpf(18) },
            ))
            setPadding(k.dp(16), k.dp(14), k.dp(16), k.dp(14))
            addView(k.caps(kicker, accent))
            addView(k.space(8))
            body()
        }

    private fun LinearLayout.viz(ctx: Context, k: Kit, type: String, data: Any, h: Int) {
        addView(k.space(10))
        addView(VizView(ctx, type, data, h), k.lp(MATCH_PARENT, k.dp(h)))
    }

    private fun big(k: Kit, s: String, color: Int = Pal.TEXT) = k.text(s, 28f, color, 500)
    private fun line(k: Kit, s: String, color: Int = Pal.MUTED, size: Float = 13f) = k.text(s, size, color).apply { setLineSpacing(0f, 1.25f) }
    private fun inr(v: Double) = "₹" + Fmt.amount(v)
    private fun signed(v: Double) = (if (v < 0) "−₹" else "+₹") + Fmt.amount(abs(v))
    private fun JsonObject.d(k: String) = get(k)?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
    private fun JsonObject.s(k: String) = get(k)?.takeIf { !it.isJsonNull }?.asString ?: ""
    private fun JsonObject.rows(k: String): List<JsonObject> = getAsJsonArray(k)?.map { it.asJsonObject } ?: emptyList()

    private fun animateIn(v: View, k: Kit) {
        v.alpha = 0f; v.translationY = k.dpf(14); v.scaleX = 0.97f; v.scaleY = 0.97f
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    // ------------------------------------------------------ calculators

    private fun growth(ctx: Context, k: Kit, o: JsonObject) = shell(k, "Compounding", Pal.GREEN) {
        val start = o.d("start"); val fv = o.d("future_value"); val rate = o.d("yearly_return_percent"); val yrs = o.d("years")
        addView(line(k, "${inr(start)}  →", Pal.MUTED, 15f))
        addView(big(k, inr(fv), Pal.GREEN))
        addView(line(k, "${Fmt.pct(rate).removePrefix("+")} a year · ${Fmt.qty(yrs)} years · ${o.d("multiple")}×"))
        val real = o.d("in_todays_money")
        val infl = if (real > 0 && fv > 0 && yrs > 0) (Math.pow(fv / real, 1 / yrs) - 1) * 100 else 0.0
        viz(ctx, k, "growth", mapOf("start" to start, "rate" to rate, "years" to yrs, "inflation" to infl), 160)
        addView(k.space(6))
        addView(line(k, "In today's money: ${inr(real)} (dashed)" +
            (o.get("doubles_every_years")?.takeIf { !it.isJsonNull }?.let { " · doubles every ~${it.asDouble} yrs" } ?: "")))
    }

    private fun move(ctx: Context, k: Kit, o: JsonObject) = shell(k, "If it moves ${Fmt.pct(o.d("move_percent"))}", if (o.d("move_percent") >= 0) Pal.GREEN else Pal.RED) {
        val profit = o.d("profit_vs_cost")
        addView(line(k, "${o.s("stock")} · ₹${Fmt.price(o.d("price_after"))} a share", Pal.MUTED, 14f))
        addView(big(k, inr(o.d("value_after"))))
        addView(k.text("${signed(profit)} (${Fmt.pct1(o.d("profit_percent"))}) on ${inr(o.d("cost"))}", 15f, Pal.signed(profit), 500))
        val today = o.d("value_after") - o.d("change_vs_today")
        viz(ctx, k, "versus", mapOf("items" to listOf(
            mapOf("label" to "After move", "value" to o.d("value_after")), mapOf("label" to "Today", "value" to today),
            mapOf("label" to "Invested", "value" to o.d("cost")))), 96)
    }

    private fun versus(ctx: Context, k: Kit, o: JsonObject): LinearLayout {
        val beat = o.get("beat_fd")?.asBoolean == true
        return shell(k, "You vs the alternatives", if (beat) Pal.GREEN else Pal.SAFFRON) {
            val cur = o.d("current")
            addView(line(k, "${inr(o.d("invested"))} → ${inr(cur)} in ${Fmt.qty(o.d("years"))} yrs", Pal.MUTED, 14f))
            addView(big(k, (o.get("your_yearly_return_percent")?.takeIf { !it.isJsonNull }?.asDouble?.let { Fmt.pct1(it) } ?: "—") + " a year",
                if (beat) Pal.GREEN else Pal.SAFFRON))
            addView(line(k, if (beat) "Ahead of a fixed deposit" else "A fixed deposit did better"))
            val items = listOf(mapOf("label" to "You", "value" to cur)) + o.rows("comparison").map { mapOf("label" to it.s("option"), "value" to it.d("would_be")) }
            viz(ctx, k, "versus", mapOf("items" to items), 30 * items.size + 6)
            addView(line(k, "In today's money: ${inr(o.d("real_value_after_inflation"))}", Pal.DIM, 12f))
        }
    }

    private fun goal(ctx: Context, k: Kit, o: JsonObject) = shell(k, "Goal", Pal.BLUE) {
        val now = o.d("now"); val goal = o.d("goal")
        addView(line(k, "${inr(now)} of ${inr(goal)}", Pal.MUTED, 14f))
        addView(k.text(if (o.get("already_there")?.asBoolean == true) "Already there" else Fmt.pct1(o.d("growth_needed_percent")) + " to go",
            24f, Pal.BLUE, 500).apply { maxLines = 1 })
        addView(line(k, "${o.d("multiple_needed")}× from today"))
        val years = o.getAsJsonObject("years_at")?.entrySet()?.associate { (r, v) -> r to (if (v.isJsonNull) null else v.asDouble) } ?: emptyMap()
        viz(ctx, k, "goal", mapOf("now" to now, "goal" to goal, "years" to years), 140)
    }

    private fun tax(ctx: Context, k: Kit, o: JsonObject) = shell(k, "In hand if you sell · ${o.s("tax_type")}", Pal.SAFFRON) {
        addView(big(k, inr(o.d("in_hand")), Pal.GREEN))
        addView(line(k, "of ${inr(o.d("gain"))} profit"))
        viz(ctx, k, "tax", mapOf("in_hand" to o.d("in_hand"), "tax" to o.d("tax"), "charges" to o.d("charges")), 116)
        if (o.get("assumed")?.asBoolean == true) addView(line(k, "Assumed held over a year. Estimate only.", Pal.DIM, 11f))
    }

    private fun regret(ctx: Context, k: Kit, o: JsonObject) = shell(k, "If you had bought cheaper", Pal.SAFFRON) {
        val gain = o.d("gain")
        addView(big(k, signed(gain), Pal.signed(gain)))
        addView(line(k, "${Fmt.qty(o.d("shares"))} shares of ${o.s("stock")} · ${Fmt.pct1(o.d("gain_percent"))}"))
        viz(ctx, k, "versus", mapOf("items" to listOf(
            mapOf("label" to "Worth today", "value" to o.d("worth_today")), mapOf("label" to "Would have paid", "value" to o.d("would_have_paid")))), 66)
    }

    private fun pulao(k: Kit, o: JsonObject) = shell(k, "Pulao maths", Pal.SAFFRON) {
        addView(k.text(o.s("text").removePrefix("≈ "), 17f, Pal.TEXT, 500).apply { setLineSpacing(0f, 1.3f) })
    }

    private fun stock(k: Kit, o: JsonObject): LinearLayout? {
        val type = o.s("type").ifBlank { return null }
        return shell(k, type.uppercase(), Pal.BLUE) {
            addView(k.text(o.s("name").ifBlank { "My portfolio" }, 18f, Pal.TEXT, 500))
            if (o.has("current_value")) {
                addView(big(k, inr(o.d("current_value"))))
                val p = o.d("pnl")
                addView(k.text("${signed(p)} (${Fmt.pct1(o.d("pnl_percent"))}) on ${inr(o.d("invested"))}", 14f, Pal.signed(p)))
            } else addView(line(k, "LTP ₹${Fmt.price(o.d("ltp"))}", Pal.MUTED, 15f))
        }
    }

    // ----------------------------------------------------------- Kite (MCP)

    private fun note(k: Kit, kicker: String, text: String) = shell(k, kicker, Pal.BLUE) {
        addView(k.text(text, 15f, Pal.TEXT).apply { setLineSpacing(0f, 1.3f) })
    }

    private fun holdings(ctx: Context, k: Kit, o: JsonObject) = shell(k, "Your Kite holdings · ${o.d("count").toInt()}", Pal.BLUE) {
        val pnl = o.d("pnl")
        addView(big(k, inr(o.d("current"))))
        addView(k.text("${signed(pnl)} (${Fmt.pct1(o.d("pnl_percent"))}) on ${inr(o.d("invested"))}", 14f, Pal.signed(pnl), 500))
        val rows = o.rows("holdings")
        viz(ctx, k, "holdings", mapOf("holdings" to rows.map {
            mapOf("symbol" to it.s("symbol"), "current" to it.d("current"), "pnl" to it.d("pnl"), "pnl_percent" to it.d("pnl_percent"))
        }), 170 + 28 * minOf(6, rows.size))
        if (o.d("largest_weight_percent") >= 40) addView(line(k, "Largest holding is ${o.d("largest_weight_percent")}% of the portfolio.", Pal.SAFFRON, 12f))
    }

    private fun positions(ctx: Context, k: Kit, o: JsonObject) = shell(k, "Positions · ${o.d("count").toInt()}", Pal.BLUE) {
        val pnl = o.d("pnl")
        addView(big(k, signed(pnl), Pal.signed(pnl)))
        addView(line(k, "P&L across open positions"))
        val rows = o.rows("positions")
        viz(ctx, k, "positions", mapOf("positions" to rows.map { mapOf("symbol" to it.s("symbol"), "pnl" to it.d("pnl")) }), maxOf(60, 34 * minOf(10, rows.size)))
    }

    private fun margins(ctx: Context, k: Kit, o: JsonObject) = shell(k, "Margins · equity", Pal.GREEN) {
        addView(big(k, inr(o.d("net")), Pal.GREEN))
        addView(line(k, "available to trade"))
        viz(ctx, k, "margins", mapOf("net" to o.d("net"), "used" to o.d("used"), "cash" to o.d("cash")), 150)
    }

    private fun funds(ctx: Context, k: Kit, o: JsonObject) = shell(k, "Mutual funds", Pal.VIOLET) {
        addView(big(k, inr(o.d("current"))))
        val p = o.d("current") - o.d("invested")
        addView(k.text("${signed(p)} on ${inr(o.d("invested"))}", 14f, Pal.signed(p), 500))
        val rows = o.rows("funds")
        if (rows.isEmpty()) addView(line(k, "No mutual fund holdings on Coin.", Pal.MUTED, 14f))
        else viz(ctx, k, "mutual_funds", mapOf("funds" to rows.map { mapOf("fund" to it.s("fund"), "current" to it.d("current"), "pnl" to it.d("pnl")) }),
            30 * minOf(8, rows.size) + 6)
    }

    private fun orders(ctx: Context, k: Kit, o: JsonObject): LinearLayout {
        val key = if (o.has("orders")) "orders" else "trades"
        val rows = o.rows(key)
        return shell(k, "${key.replaceFirstChar { it.uppercase() }} today · ${rows.size}", Pal.BLUE) {
            if (rows.isEmpty()) { addView(line(k, "Nothing today.", Pal.MUTED, 15f)); return@shell }
            viz(ctx, k, key, mapOf(key to rows.map {
                mapOf("symbol" to it.s("symbol"), "side" to it.s("side"), "qty" to it.d("qty"), "price" to it.d("price"),
                    "status" to it.s("status"), "time" to it.s("time"))
            }), 50 * minOf(8, rows.size))
        }
    }

    private fun history(ctx: Context, k: Kit, o: JsonObject): LinearLayout {
        val chg = o.d("change_percent")
        return shell(k, "${o.s("symbol")} · ${o.s("period").uppercase()}", if (chg >= 0) Pal.GREEN else Pal.RED) {
            addView(big(k, "₹" + Fmt.price(o.d("last"))))
            addView(k.text("${Fmt.pct1(chg)} · high ₹${Fmt.price(o.d("high"))} · low ₹${Fmt.price(o.d("low"))}", 14f, Pal.signed(chg), 500))
            viz(ctx, k, "history", mapOf("points" to o.rows("points").map { mapOf("t" to it.s("t"), "c" to it.d("c")) }), 180)
            addView(line(k, "Touch the chart to read a date's close.", Pal.DIM, 11f))
        }
    }

    private fun quotes(ctx: Context, k: Kit, o: JsonObject) = shell(k, "Live price · Kite", Pal.BLUE) {
        val q = o.rows("quotes")
        if (q.size == 1) { addView(k.text(q[0].s("symbol"), 16f, Pal.MUTED)); addView(big(k, "₹" + Fmt.price(q[0].d("ltp")))) }
        else viz(ctx, k, "bars", mapOf("items" to q.map { mapOf("label" to it.s("symbol"), "value" to it.d("ltp")) }), 30 * q.size + 6)
    }
}
