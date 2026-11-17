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

    private val CARDS = setOf("grow_for_years", "project_move", "compare_with_fd", "goal_plan", "in_hand_after_tax", "regret", "get_current_stock",
        "kite_account", "kite_chart", "kite_quote", "web_search", "web_quote", "web_events", "web_calendar", "web_ipo", "web_gmp")
    fun hasCard(tool: String) = tool in CARDS || tool.startsWith("term_")

    private fun accent(name: String) = when (name) { "green" -> Pal.GREEN; "red" -> Pal.RED; "saffron" -> Pal.SAFFRON; "violet" -> Pal.VIOLET; "teal" -> 0xFF4FC3C8.toInt(); "pink" -> 0xFFFF6FA5.toInt(); else -> Pal.BLUE }
    private fun tone(name: String, fallback: Int = Pal.TEXT) = when (name) { "up" -> Pal.GREEN; "down" -> Pal.RED; "warn" -> Pal.SAFFRON; else -> fallback }

    /** Draws any terminal feature's card from its JSON description (see ai/term/Card). */
    private fun generic(ctx: Context, k: Kit, o: JsonObject): LinearLayout? {
        val c = o.getAsJsonObject("card") ?: return null
        val sources = o.getAsJsonArray("sources")?.joinToString(" + ") { it.asString }.orEmpty()
        return shell(k, c.s("kicker") + if (sources.isNotBlank()) " · $sources" else "", accent(c.s("accent"))) {
            if (c.s("headline").isNotBlank()) addView(k.text(c.s("headline"), 24f, Pal.TEXT, 700).apply { setLineSpacing(0f, 1.1f) })
            if (c.s("sub").isNotBlank()) addView(k.text(c.s("sub"), 13.5f, tone(c.s("tone"), Pal.MUTED), 500).apply { setLineSpacing(0f, 1.25f) })
            for (b in c.rows("blocks")) when (b.s("type")) {
                "viz" -> {
                    viz(ctx, k, b.s("viz"), b.get("data") ?: JsonObject(), b.d("height").toInt().coerceIn(60, 520))
                    if (b.s("caption").isNotBlank()) addView(line(k, b.s("caption"), Pal.DIM, 11f).apply { setPadding(0, k.dp(4), 0, 0) })
                }
                "stats" -> {
                    addView(k.space(10))
                    b.rows("items").chunked(2).forEach { pair ->
                        addView(k.row(android.view.Gravity.TOP).apply {
                            setPadding(0, k.dp(4), 0, k.dp(4))
                            pair.forEach { addView(k.column().apply { addView(k.caps(it.s("label"), Pal.DIM)); addView(k.text(it.s("value"), 14f, Pal.TEXT, 500)) }, k.lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
                            if (pair.size == 1) addView(View(context), k.lp(0, 1, 1f))
                        }, k.lp(MATCH_PARENT))
                    }
                }
                "rows" -> {
                    addView(k.space(10))
                    if (b.s("title").isNotBlank()) { addView(k.caps(b.s("title"), Pal.MUTED)); addView(k.space(4)) }
                    b.rows("rows").take(12).forEachIndexed { i, r ->
                        if (i > 0) addView(View(context).apply { setBackgroundColor(Pal.alpha(Pal.LINE, 0.6f)) }, k.lp(MATCH_PARENT, 1))
                        addView(k.row().apply {
                            setPadding(0, k.dp(7), 0, k.dp(7))
                            addView(k.column().apply {
                                addView(k.text(r.s("title"), 13.5f, Pal.TEXT, 500).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                                if (r.s("sub").isNotBlank()) addView(line(k, r.s("sub"), Pal.MUTED, 12f).apply { text = arrows(text); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
                            }, k.lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                            // A bare percentage reads best as a colour pill, the same as the native mover rows.
                            val pctValue = Regex("""^([+\-−]?)(\d+(?:\.\d+)?)%$""").find(r.s("value").trim())
                            if (pctValue != null) addView(NativeBlocks.pill(k, pctValue.groupValues[2].toDouble() * (if (pctValue.groupValues[1] == "-" || pctValue.groupValues[1] == "−") -1 else 1), 12f), k.lp(android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = k.dp(10) })
                            else if (r.s("value").isNotBlank()) addView(k.text(r.s("value"), 13.5f, tone(r.s("tone")), 600).apply { setPadding(k.dp(10), 0, 0, 0) })
                        }, k.lp(MATCH_PARENT))
                    }
                }
                "note" -> addView(line(k, b.s("text"), Pal.DIM, 11.5f).apply { setPadding(0, k.dp(10), 0, 0) })
                "tape" -> addView(NativeBlocks.tape(k, b), k.lp(MATCH_PARENT))
                "movers" -> addView(NativeBlocks.movers(k, b), k.lp(MATCH_PARENT))
                "bars" -> addView(NativeBlocks.bars(k, b), k.lp(MATCH_PARENT))
                "kpis" -> { addView(k.space(8)); addView(NativeBlocks.kpis(k, b), k.lp(MATCH_PARENT)) }
                "breadth" -> addView(NativeBlocks.breadth(k, b), k.lp(MATCH_PARENT))
                "section" -> addView(NativeBlocks.section(k, b.s("title"), b.s("tone")), k.lp(MATCH_PARENT))
            }
            // The takeaway: the exact summary, inside the card, so the chat doesn't repeat it as a paragraph.
            o.get("summary")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let { t ->
                addView(takeaway(k, t), k.lp(MATCH_PARENT).apply { topMargin = k.dp(12) })
            }
        }
    }

    private val SIGNED = Regex("""[+\u2212-]\s?\d[\d,]*(\.\d+)?\s?%""")

    /** Up/down figures in green/red, the rest plain — the same colour code as the blocks above. */
    fun colourNumbers(t: String): CharSequence = android.text.SpannableString(t).apply {
        SIGNED.findAll(t).forEach { m ->
            val up = m.value.trimStart().startsWith("+")
            setSpan(android.text.style.ForegroundColorSpan(if (up) Pal.GREEN else Pal.RED), m.range.first, m.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), m.range.first, m.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** "▲ beat … · rest" → the ▲ part in green, "▼ lagged …" in red; signed percentages coloured too. */
    fun arrows(t: CharSequence): CharSequence {
        val out = android.text.SpannableString(colourNumbers(t.toString()))
        Regex("""[▲▼][^·]*""").findAll(t).forEach { m ->
            out.setSpan(android.text.style.ForegroundColorSpan(if (m.value.startsWith("▲")) Pal.GREEN else Pal.RED), m.range.first, m.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    /** The first sentence or two; the whole read-out stays one tap away (and is what the AI reads). */
    fun brief(t: String, max: Int = 200): String {
        val sentences = Regex("""(?<=[.!?])\s+(?=[A-Z0-9])""").split(t.trim())
        val out = StringBuilder()
        for (x in sentences) { if (out.isNotEmpty() && out.length + x.length > max) break; if (out.isNotEmpty()) out.append(' '); out.append(x) }
        return out.toString().let { if (it.length > max + 80) it.take(max).substringBeforeLast(' ') + "…" else it }
    }

    /** "TAKEAWAY" with an accent bar, short by default, numbers colour-coded; tap to read the full text. */
    fun takeaway(k: Kit, t: String): View {
        val short = brief(t); val long = short.length < t.length - 3
        val body = k.text(colourNumbers(short), 14f, Pal.TEXT).apply { setLineSpacing(0f, 1.35f) }
        val more = k.text("Show full read-out", 12f, Pal.BLUE, 500).apply { setPadding(0, k.dp(6), 0, 0) }
        return k.column().apply {
            background = k.rounded(Pal.alpha(Pal.BLUE, 0.08f), 10f); setPadding(k.dp(14), k.dp(10), k.dp(12), k.dp(10))
            addView(k.text("TAKEAWAY", 10.5f, Pal.BLUE, 600).apply { letterSpacing = 0.08f; setPadding(0, 0, 0, k.dp(4)) })
            addView(body, k.lp(MATCH_PARENT))
            if (long) {
                addView(more)
                var open = false
                val toggle = View.OnClickListener { open = !open; body.text = colourNumbers(if (open) t else short); more.text = if (open) "Show less" else "Show full read-out" }
                setOnClickListener(toggle); more.setOnClickListener(toggle)
            }
        }
    }

    fun build(ctx: Context, k: Kit, tool: String, json: String, open: (String) -> Unit = {}): View? {
        val o = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        if (o.has("error")) return null
        if (tool.startsWith("term_")) return generic(ctx, k, o)?.also { animateIn(it, k) }
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
            "web_search" -> sources(k, "Web results · ${WebData.SEARCH_NAME}", WebData.resultsOf(json), open)
            "web_quote" -> webQuote(ctx, k, o)
            "web_events" -> events(ctx, k, o, json, open)
            "web_calendar" -> calendar(ctx, k, o)
            "web_ipo" -> ipos(ctx, k, o)
            "web_gmp" -> if (o.has("ipos")) gmpList(ctx, k, o) else gmpOne(ctx, k, o)
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

    private fun JsonObject.d(k: String, fallback: Double) = get(k)?.takeIf { !it.isJsonNull }?.asDouble ?: fallback

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
        viz(ctx, k, "growth", mapOf("start" to start, "rate" to rate, "years" to yrs, "inflation" to infl), 200)
        addView(k.space(6))
        addView(line(k, "In today's money: ${inr(real)} (dashed)" +
            (o.get("doubles_every_years")?.takeIf { !it.isJsonNull }?.let { " · doubles every ~${it.asDouble} yrs" } ?: "")))
    }

    private fun move(ctx: Context, k: Kit, o: JsonObject) = shell(k, "If it moves ${Fmt.pct(o.d("move_percent"))}", if (o.d("move_percent") >= 0) Pal.GREEN else Pal.RED) {
        val profit = o.d("profit_vs_cost")
        addView(line(k, if (o.d("shares") == 1.0 && o.s("stock").contains("portfolio", true)) o.s("stock") else "${o.s("stock")} · ₹${Fmt.price(o.d("price_after"))} a share", Pal.MUTED, 14f))
        addView(big(k, inr(o.d("value_after"))))
        addView(k.text("${signed(profit)} (${Fmt.pct1(o.d("profit_percent"))}) on ${inr(o.d("cost"))}", 15f, Pal.signed(profit), 500))
        val today = o.d("value_after") - o.d("change_vs_today")
        viz(ctx, k, "scenario", mapOf("today" to today, "cost" to o.d("cost"), "move" to o.d("move_percent"), "value" to o.d("value_after"),
            "from_avg" to (o.s("from") == "avg")), 190)
        addView(line(k, "Value (₹) at each price move (%). Dashed line: what you invested.", Pal.DIM, 11f))
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
        if (o.get("already_there")?.asBoolean != true && now > 0) {
            viz(ctx, k, "paths", mapOf("now" to now, "goal" to goal, "rates" to listOf(8, 12, 15, 20)), 200)
            addView(line(k, "Value (₹) by year at each yearly return. Dashed line: your goal.", Pal.DIM, 11f))
        }
    }

    private fun tax(ctx: Context, k: Kit, o: JsonObject) = shell(k, "In hand if you sell · ${o.s("tax_type")}", Pal.SAFFRON) {
        addView(big(k, inr(o.d("in_hand")), Pal.GREEN))
        addView(line(k, "of ${inr(o.d("gain"))} profit"))
        viz(ctx, k, "waterfall", mapOf("gain" to o.d("gain"), "in_hand" to o.d("in_hand"), "tax" to o.d("tax"), "charges" to o.d("charges")), 190)
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
        if (rows.size > 1) {
            addView(k.space(6)); addView(k.caps("Allocation · share of portfolio value", Pal.MUTED))
            viz(ctx, k, "allocation", mapOf("items" to rows.map { mapOf("label" to it.s("symbol"), "value" to it.d("current")) }), 170)
        }
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
            val pts = o.rows("points")
            val candles = pts.size in 8..70 && pts.first().has("o") && o.s("period") in setOf("1m", "3m")
            viz(ctx, k, if (candles) "candles" else "history", mapOf("points" to pts.map {
                mapOf("t" to it.s("t"), "c" to it.d("c")) + if (it.has("o")) mapOf("o" to it.d("o"), "h" to it.d("h"), "l" to it.d("l"), "v" to it.d("v")) else emptyMap()
            }), if (candles) 230 else 210)
            addView(line(k, "Price (₹) by date. Touch the chart to read a day.", Pal.DIM, 11f))
        }
    }

    // ------------------------------------------------------------------ web

    /** Numbered, tappable sources — the citations [1], [2] in the answer point here. */
    private fun LinearLayout.sourceRows(k: Kit, results: List<WebData.Result>, open: (String) -> Unit) {
        results.forEachIndexed { i, r ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(Pal.LINE) }, k.lp(MATCH_PARENT, 1).apply { topMargin = k.dp(8); bottomMargin = k.dp(8) })
            val row = k.row(android.view.Gravity.TOP).apply {
                background = k.press(null, 10f); contentDescription = "Open source ${r.n}: ${r.site}"
                setOnClickListener { if (r.url.isNotEmpty()) open(r.url) }
            }
            row.addView(k.text("${r.n}", 11f, Pal.BLUE, 600).apply {
                gravity = android.view.Gravity.CENTER; background = k.rounded(Pal.alpha(Pal.BLUE, 0.16f), 999f)
            }, k.lp(k.dp(22), k.dp(22)).apply { topMargin = k.dp(1) })
            val c = k.column().apply { setPadding(k.dp(10), 0, 0, 0) }
            c.addView(k.text(r.title, 13.5f, Pal.TEXT, 500).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            c.addView(k.text(r.site + "  ↗", 11.5f, Pal.BLUE).apply { setPadding(0, k.dp(2), 0, 0) })
            if (r.snippet.isNotBlank()) c.addView(line(k, r.snippet, Pal.MUTED, 12f).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, k.dp(3), 0, 0) })
            row.addView(c, k.lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(row, k.lp(MATCH_PARENT))
        }
    }

    private fun sources(k: Kit, kicker: String, results: List<WebData.Result>, open: (String) -> Unit): LinearLayout? =
        if (results.isEmpty()) null else shell(k, kicker, Pal.VIOLET) { sourceRows(k, results, open) }

    private fun webQuote(ctx: Context, k: Kit, o: JsonObject): LinearLayout {
        val day = o.get("day_change_percent")?.takeIf { !it.isJsonNull }?.asDouble
        val chg = o.d("change_percent")
        return shell(k, "${o.s("symbol")} · ${o.s("period").uppercase()} · ${WebData.QUOTE_NAME}", if ((day ?: chg) >= 0) Pal.GREEN else Pal.RED) {
            addView(k.text(o.s("name"), 14f, Pal.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            val unit = if (o.has("unit")) o.s("unit") else "₹"
            addView(big(k, unit + Fmt.price(o.d("price"))))
            if (day != null) addView(k.text("${Fmt.pct1(day)} today" + (o.s("as_of").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), 13.5f, Pal.signed(day), 500))
            val pts = o.rows("points")
            val candles = pts.size in 8..70 && o.s("period") in setOf("1m", "3m")
            viz(ctx, k, if (candles) "candles" else "history", mapOf("points" to pts.map {
                mapOf("t" to it.s("t"), "o" to it.d("o"), "h" to it.d("h"), "l" to it.d("l"), "c" to it.d("c"), "v" to it.d("v"))
            }), if (candles) 230 else 210)
            fun stat(label: String, value: String) = k.column().apply { addView(k.caps(label, Pal.DIM)); addView(k.text(value, 13f, Pal.TEXT, 500)) }
            fun p(key: String) = o.get(key)?.takeIf { !it.isJsonNull }?.asDouble?.let { unit + Fmt.price(it) } ?: "—"
            addView(k.space(8))
            addView(k.row().apply {
                addView(stat("Day range", "${p("day_low")} – ${p("day_high")}"), k.lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(stat("52-week range", "${p("low_52w")} – ${p("high_52w")}"), k.lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, k.lp(MATCH_PARENT))
            addView(line(k, "${Fmt.pct1(chg)} over the period · prices may be delayed · touch the chart to read a date", Pal.DIM, 11f).apply { setPadding(0, k.dp(8), 0, 0) })
        }
    }

    private fun events(ctx: Context, k: Kit, o: JsonObject, json: String, open: (String) -> Unit) = shell(k, "${o.s("company")} · company events", Pal.SAFFRON) {
        val ev = o.rows("events")
        if (ev.isEmpty()) addView(line(k, "No dated events found — see the sources below.", Pal.MUTED, 14f))
        else viz(ctx, k, "calendar", mapOf("today" to o.s("today"), "events" to ev.map {
            mapOf("date" to it.s("date"), "kind" to it.s("kind"), "title" to it.s("title"), "source" to it.d("source"), "upcoming" to (it.get("upcoming")?.asBoolean == true))
        }), 74 + 40 * minOf(8, ev.size))
        val results = WebData.resultsOf(json)
        if (results.isNotEmpty()) {
            addView(k.space(10)); addView(k.caps("Sources", Pal.MUTED)); addView(k.space(8))
            sourceRows(k, results.take(4), open)
        }
        addView(line(k, "Dates are read from public pages and can be wrong or out of date. Confirm on NSE/BSE or the company's investor page.", Pal.DIM, 11f).apply { setPadding(0, k.dp(10), 0, 0) })
    }

    private val DAY = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH)
    private fun day(iso: String) = runCatching { java.time.LocalDate.parse(iso).format(DAY) }.getOrDefault(iso)
    private fun kindColor(kind: String) = when {
        kind.startsWith("Result") -> Pal.BLUE; kind.startsWith("Dividend") -> Pal.GREEN; kind.startsWith("AGM") -> Pal.VIOLET
        kind.startsWith("Bonus") || kind.startsWith("Stock split") -> 0xFFFF6FA5.toInt(); kind.startsWith("IPO") || kind.startsWith("New listing") -> 0xFF4FC3C8.toInt()
        kind.startsWith("Market holiday") -> Pal.RED; else -> Pal.SAFFRON
    }

    /** Market or company calendar: a chart of events per day by kind, then the agenda day by day (your own stocks first, starred). */
    private fun calendar(ctx: Context, k: Kit, o: JsonObject) = shell(k, "${o.s("title")} · ${o.d("total").toInt()} events · ${o.getAsJsonArray("sources")?.joinToString(" + ") { it.asString } ?: "NSE"}", Pal.SAFFRON) {
        val ev = o.rows("events")
        val single = o.s("title") != "Market calendar"
        if (single) viz(ctx, k, "calendar", mapOf("today" to o.s("today"), "events" to ev.map {
            mapOf("date" to it.s("date"), "kind" to it.s("kind"), "title" to it.s("title"), "source" to 0, "upcoming" to (it.s("phase") != "done"))
        }), 74 + 40 * minOf(8, ev.size))
        else {
            viz(ctx, k, "agenda", mapOf("from" to o.s("from"), "to" to o.s("to"), "today" to o.s("today"),
                "events" to ev.map { mapOf("date" to it.s("date"), "kind" to it.s("kind")) }), 190)
            addView(line(k, "Events per day, by kind. The list below is day by day; ★ marks your holdings.", Pal.DIM, 11f))
            val shown = ev.filter { it.get("mine")?.asBoolean == true } .plus(ev.filter { it.get("mine")?.asBoolean != true && it.s("kind") != "Board meeting" && it.s("kind") != "Other" })
                .plus(ev.filter { it.get("mine")?.asBoolean != true && (it.s("kind") == "Board meeting" || it.s("kind") == "Other") }).take(18).sortedBy { it.s("date") }
            var last = ""
            for (e in shown) {
                if (e.s("date") != last) {
                    last = e.s("date")
                    addView(k.text(day(last) + if (e.s("phase") == "today") "  ·  today" else "", 12f, if (e.s("phase") == "today") Pal.TEXT else Pal.MUTED, 600).apply { setPadding(0, k.dp(12), 0, k.dp(4)); letterSpacing = 0.04f })
                }
                val mine = e.get("mine")?.asBoolean == true
                val r = k.row(android.view.Gravity.TOP).apply { setPadding(0, k.dp(4), 0, k.dp(4)) }
                r.addView(View(context).apply { background = k.rounded(kindColor(e.s("kind")), 2f) }, k.lp(k.dp(3), k.dp(30)).apply { topMargin = k.dp(2) })
                val c = k.column().apply { setPadding(k.dp(10), 0, 0, 0) }
                c.addView(k.text((if (mine) "★ " else "") + (e.s("symbol").ifBlank { e.s("company") }.ifBlank { e.s("title") }), 13.5f, if (mine) Pal.SAFFRON else Pal.TEXT, 500).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                val detail = e.s("title").removePrefix("Ex-date: ")
                val same = detail.replace(" ", "").equals(e.s("kind").replace(" ", ""), true) || detail.equals("Buy Back", true)
                c.addView(line(k, e.s("kind") + (if (!same && (e.s("symbol").isNotBlank() || e.s("company").isNotBlank())) " · $detail" else ""), Pal.MUTED, 12f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                r.addView(c, k.lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(r, k.lp(MATCH_PARENT))
            }
            if (ev.size > shown.size) addView(line(k, "+ ${ev.size - shown.size} more, mostly routine board meetings. Narrow it with /calendar today or /events SYMBOL.", Pal.DIM, 11.5f).apply { setPadding(0, k.dp(10), 0, 0) })
        }
        addView(line(k, "Dates as published by the exchange; companies can change them. Not a recommendation.", Pal.DIM, 11f).apply { setPadding(0, k.dp(10), 0, 0) })
    }

    /** IPO life cycle: Forthcoming → Open → Closed, each issue's bid window on a date axis, then recent listings. */
    private fun ipos(ctx: Context, k: Kit, o: JsonObject) = shell(k, "IPOs · NSE", 0xFF4FC3C8.toInt()) {
        val rows = o.rows("ipos")
        if (rows.isEmpty()) addView(line(k, "No open or upcoming IPOs listed by NSE right now.", Pal.MUTED, 14f))
        else viz(ctx, k, "ipo", mapOf("today" to o.s("today"), "ipos" to rows.map {
            mapOf("name" to it.s("name"), "opens" to it.s("opens"), "closes" to it.s("closes"), "status" to it.s("status"))
        }), 70 + 34 * minOf(6, rows.size))
        for (i in rows) {
            addView(View(context).apply { setBackgroundColor(Pal.LINE) }, k.lp(MATCH_PARENT, 1).apply { topMargin = k.dp(10); bottomMargin = k.dp(10) })
            val head = k.row()
            head.addView(k.text(i.s("name"), 14.5f, Pal.TEXT, 500).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, k.lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val status = i.s("status"); val col = when (status) { "Open" -> Pal.GREEN; "Forthcoming" -> Pal.SAFFRON; else -> Pal.MUTED }
            head.addView(k.text(status, 11f, col, 600).apply { background = k.rounded(Pal.alpha(col, 0.14f), 999f); setPadding(k.dp(9), k.dp(3), k.dp(9), k.dp(3)) })
            addView(head, k.lp(MATCH_PARENT))
            addView(line(k, "${day(i.s("opens"))} → ${day(i.s("closes"))} · ${i.s("price")} · ${i.s("board")}", Pal.MUTED, 12.5f).apply { setPadding(0, k.dp(3), 0, 0) })
            if (i.has("gmp") && !i.get("gmp").isJsonNull) {
                val g = i.d("gmp")
                addView(k.text("GMP ${rupee(g)} (${Fmt.pct1(i.d("gmp_percent"))}) → implied listing ${rupee(i.d("expected_listing"))}", 12.5f, Pal.signed(g), 500).apply { setPadding(0, k.dp(3), 0, 0) })
            }
            val dates = listOfNotNull(i.s("allotment").takeIf { it.isNotBlank() }?.let { "allotment ${day(it)}" }, i.s("listing").takeIf { it.isNotBlank() }?.let { "listing ${day(it)}" })
            if (dates.isNotEmpty()) addView(line(k, dates.joinToString(" · ").replaceFirstChar { it.uppercase() }, Pal.DIM, 12f).apply { setPadding(0, k.dp(2), 0, 0) })
        }
        if (o.get("has_gmp")?.asBoolean == true) addView(line(k, GmpData.CAUTION, Pal.DIM, 11f).apply { setPadding(0, k.dp(10), 0, 0) })
        val listed = o.rows("listings")
        if (listed.isNotEmpty()) {
            addView(k.space(12)); addView(k.caps("Recently listed", Pal.MUTED)); addView(k.space(4))
            for (l in listed.take(5)) addView(line(k, "${day(l.s("date"))} · ${l.s("symbol")} · ${l.s("company")}", Pal.MUTED, 12.5f).apply { setPadding(0, k.dp(3), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        }
        addView(line(k, "Bid windows and price bands as published by NSE. Not a recommendation to apply.", Pal.DIM, 11f).apply { setPadding(0, k.dp(10), 0, 0) })
    }

    private fun rupee(v: Double) = (if (v < 0) "−₹" else "₹") + Fmt.qty(Math.round(abs(v) * 100) / 100.0)

    /** The five steps every IPO goes through, with the current one lit. */
    private fun LinearLayout.stages(k: Kit, o: JsonObject) {
        val steps = listOf("Upcoming" to o.s("opens"), "Open for bids" to o.s("opens"), "Closed, awaiting allotment" to o.s("closes"), "Allotted, awaiting listing" to o.s("allotment"), "Listed" to o.s("listing"))
        val at = steps.indexOfFirst { it.first == o.s("stage") }.coerceAtLeast(0)
        val labels = listOf("Upcoming", "Bidding", "Closed", "Allotment", "Listing")
        val row = k.row(android.view.Gravity.TOP)
        steps.forEachIndexed { i, (_, date) ->
            val col = k.column().apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
            val on = i <= at
            col.addView(View(context).apply { background = k.rounded(if (i == at) 0xFF4FC3C8.toInt() else if (on) Pal.alpha(0xFF4FC3C8.toInt(), 0.45f) else Pal.LINE, 999f) }, k.lp(k.dp(if (i == at) 12 else 8), k.dp(if (i == at) 12 else 8)).apply { topMargin = k.dp(if (i == at) 0 else 2) })
            col.addView(k.text(labels[i], 10.5f, if (i == at) Pal.TEXT else Pal.MUTED, if (i == at) 600 else 400).apply { gravity = android.view.Gravity.CENTER; setPadding(0, k.dp(5), 0, 0) })
            if (i > 0 && date.isNotBlank()) col.addView(k.text(day(date).substringAfter(' '), 10f, Pal.DIM).apply { gravity = android.view.Gravity.CENTER })
            row.addView(col, k.lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(row, k.lp(MATCH_PARENT))
    }

    /** One IPO: where it is in its life, its grey-market premium, the trend, and the listing price that implies. */
    private fun gmpOne(ctx: Context, k: Kit, o: JsonObject): LinearLayout {
        val has = o.has("gmp") && !o.get("gmp").isJsonNull
        val g = o.d("gmp")
        return shell(k, "${o.s("name")} · ${o.s("board")} IPO", if (!has) Pal.MUTED else if (g >= 0) Pal.GREEN else Pal.RED) {
            stages(k, o)
            addView(k.space(12))
            if (!has) addView(line(k, "No grey-market premium is quoted for this issue yet.", Pal.MUTED, 14f))
            else {
                addView(k.caps("Grey-market premium · unofficial", Pal.MUTED))
                addView(big(k, rupee(g) + "  " + Fmt.pct1(o.d("gmp_percent")), Pal.signed(g)))
                addView(line(k, "Traded between ${rupee(o.d("gmp_low"))} and ${rupee(o.d("gmp_high"))} so far · updated ${o.s("updated")}", Pal.MUTED, 12.5f))
                val trend = o.rows("trend")
                if (trend.size >= 2) {
                    viz(ctx, k, "history", mapOf("yLabel" to "GMP (₹ a share)", "points" to trend.map { mapOf("t" to it.s("t"), "c" to it.d("c")) }), 190)
                    addView(line(k, "Premium (₹) by date. Touch the chart to read a day.", Pal.DIM, 11f))
                }
                addView(k.space(8)); addView(k.caps("What that implies at listing", Pal.MUTED))
                viz(ctx, k, "listing", mapOf("issue" to o.d("issue_price"), "expected" to o.d("expected_listing"), "low" to o.d("issue_price") + o.d("gmp_low"), "high" to o.d("issue_price") + o.d("gmp_high")), 130)
                val lot = o.d("lot").toInt()
                if (lot > 0) addView(line(k, "One lot = $lot shares (${rupee(o.d("issue_price") * lot)}). At today's premium that is about ${rupee(o.d("per_lot"))} on a lot — only if you are allotted, and only if the premium holds.", Pal.MUTED, 12.5f).apply { setPadding(0, k.dp(6), 0, 0) })
            }
            val facts = listOfNotNull(o.s("size").takeIf { it.isNotBlank() }?.let { "Size $it" }, o.s("subscribed").takeIf { it.isNotBlank() }?.let { "Subscribed $it" })
            if (facts.isNotEmpty()) addView(line(k, facts.joinToString(" · "), Pal.MUTED, 12.5f).apply { setPadding(0, k.dp(6), 0, 0) })
            addView(line(k, GmpData.CAUTION, Pal.DIM, 11f).apply { setPadding(0, k.dp(10), 0, 0) })
        }
    }

    /** Every IPO in play, ranked by premium. */
    private fun gmpList(ctx: Context, k: Kit, o: JsonObject) = shell(k, "IPO grey-market premium · ${o.s("source")}", 0xFF4FC3C8.toInt()) {
        val rows = o.rows("ipos")
        val quoted = rows.filter { it.has("gmp") && !it.get("gmp").isJsonNull }.sortedByDescending { it.d("gmp_percent") }
        if (quoted.isNotEmpty()) {
            viz(ctx, k, "percentbars", mapOf("label" to "Premium over issue price (%)", "items" to quoted.take(8).map { mapOf("label" to it.s("name").take(16), "value" to it.d("gmp_percent")) }), 34 * minOf(8, quoted.size) + 30)
        }
        for (i in rows.take(10)) {
            addView(View(context).apply { setBackgroundColor(Pal.LINE) }, k.lp(MATCH_PARENT, 1).apply { topMargin = k.dp(9); bottomMargin = k.dp(9) })
            val head = k.row()
            head.addView(k.text(i.s("name"), 14f, Pal.TEXT, 500).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, k.lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val has = i.has("gmp") && !i.get("gmp").isJsonNull
            head.addView(k.text(if (has) "${rupee(i.d("gmp"))} · ${Fmt.pct1(i.d("gmp_percent"))}" else "no quote", 13f, if (has) Pal.signed(i.d("gmp")) else Pal.DIM, 500))
            addView(head, k.lp(MATCH_PARENT))
            addView(line(k, listOfNotNull(i.s("stage"), i.s("board"), i.s("listing").takeIf { it.isNotBlank() }?.let { "lists ${day(it)}" },
                if (has) "implied ${rupee(i.d("expected_listing"))}" else null).joinToString(" · "), Pal.MUTED, 12f).apply { setPadding(0, k.dp(2), 0, 0) })
        }
        addView(line(k, GmpData.CAUTION, Pal.DIM, 11f).apply { setPadding(0, k.dp(10), 0, 0) })
    }

    private fun quotes(ctx: Context, k: Kit, o: JsonObject) = shell(k, "Live price · Kite", Pal.BLUE) {
        val q = o.rows("quotes")
        if (q.size == 1) { addView(k.text(q[0].s("symbol"), 16f, Pal.MUTED)); addView(big(k, "₹" + Fmt.price(q[0].d("ltp")))) }
        else viz(ctx, k, "bars", mapOf("items" to q.map { mapOf("label" to it.s("symbol"), "value" to it.d("ltp")) }), 30 * q.size + 6)
    }
}
