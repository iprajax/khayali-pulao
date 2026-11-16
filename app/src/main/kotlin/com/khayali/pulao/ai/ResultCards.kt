package com.khayali.pulao.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.khayali.pulao.Fmt
import com.khayali.pulao.Kit
import com.khayali.pulao.Pal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Visual answer cards, one per kind of result — like an artifact next to the chat.
 * Numbers come straight from the tool's JSON, never from the model's text.
 */
object ResultCards {

    /** Returns a card for tools that produce a figure, or null for quiet tools (settings, open screen…). */
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
            "pulao_maths" -> pulao(ctx, k, o)
            "get_current_stock" -> stock(ctx, k, o)
            "kite_account" -> if (o.has("holdings")) holdings(ctx, k, o) else if (o.has("summary")) note(ctx, k, "Kite", o["summary"].asString) else null
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

    // ------------------------------------------------------------- shells

    private fun shell(ctx: Context, k: Kit, kicker: String, accent: Int, body: LinearLayout.() -> Unit): LinearLayout =
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

    private fun big(k: Kit, s: String, color: Int = Pal.TEXT) = k.text(s, 28f, color, 500)
    private fun line(k: Kit, s: String, color: Int = Pal.MUTED, size: Float = 13f) = k.text(s, size, color).apply { setLineSpacing(0f, 1.25f) }
    private fun inr(v: Double) = "₹" + Fmt.amount(v)
    private fun signed(v: Double) = (if (v < 0) "−₹" else "+₹") + Fmt.amount(abs(v))
    private fun JsonObject.d(k: String) = get(k)?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0

    private fun animateIn(v: View, k: Kit) {
        v.alpha = 0f; v.translationY = k.dpf(14); v.scaleX = 0.97f; v.scaleY = 0.97f
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    // -------------------------------------------------------------- cards

    private fun growth(ctx: Context, k: Kit, o: JsonObject) = shell(ctx, k, "Compounding", Pal.GREEN) {
        val start = o.d("start"); val fv = o.d("future_value"); val rate = o.d("yearly_return_percent"); val yrs = o.d("years")
        addView(line(k, "${inr(start)}  →", Pal.MUTED, 15f))
        addView(big(k, inr(fv), Pal.GREEN))
        addView(line(k, "${Fmt.pct(rate).removePrefix("+")} a year · ${Fmt.qty(yrs)} years · ${o.d("multiple")}×"))
        addView(k.space(10))
        addView(GrowthChart(ctx, k, start, rate, yrs), k.lp(MATCH_PARENT, k.dp(130)))
        addView(k.space(8))
        addView(line(k, "In today's money: ${inr(o.d("in_todays_money"))}" +
            (o.get("doubles_every_years")?.takeIf { !it.isJsonNull }?.let { " · doubles every ~${it.asDouble} yrs" } ?: "")))
    }

    private fun move(ctx: Context, k: Kit, o: JsonObject) = shell(ctx, k, "If it moves ${Fmt.pct(o.d("move_percent"))}", if (o.d("move_percent") >= 0) Pal.GREEN else Pal.RED) {
        val profit = o.d("profit_vs_cost")
        addView(line(k, "${o.get("stock")?.asString ?: ""} · ₹${Fmt.price(o.d("price_after"))} a share", Pal.MUTED, 14f))
        addView(big(k, inr(o.d("value_after"))))
        addView(k.text("${signed(profit)} (${Fmt.pct1(o.d("profit_percent"))}) on ${inr(o.d("cost"))}", 15f, Pal.signed(profit), 500))
        addView(k.space(10))
        addView(Bars(ctx, k, listOf("Cost" to o.d("cost"), "Today" to (o.d("value_after") - o.d("change_vs_today")), "After" to o.d("value_after")), highlight = 2),
            k.lp(MATCH_PARENT, k.dp(96)))
    }

    private fun versus(ctx: Context, k: Kit, o: JsonObject): LinearLayout {
        val beat = o.get("beat_fd")?.asBoolean == true
        return shell(ctx, k, "You vs the alternatives", if (beat) Pal.GREEN else Pal.SAFFRON) {
            val cur = o.d("current")
            addView(line(k, "${inr(o.d("invested"))} → ${inr(cur)} in ${Fmt.qty(o.d("years"))} yrs", Pal.MUTED, 14f))
            addView(big(k, (o.get("your_yearly_return_percent")?.takeIf { !it.isJsonNull }?.asDouble?.let { Fmt.pct1(it) } ?: "—") + " a year",
                if (beat) Pal.GREEN else Pal.SAFFRON))
            addView(line(k, if (beat) "Ahead of a fixed deposit" else "A fixed deposit did better"))
            addView(k.space(10))
            val rows = o.getAsJsonArray("comparison").map { it.asJsonObject }
            val items = listOf("You" to cur) + rows.map { it["option"].asString to it.d("would_be") }
            addView(Bars(ctx, k, items, highlight = 0, horizontal = true), k.lp(MATCH_PARENT, k.dp(26 * items.size + 8)))
            addView(k.space(6))
            addView(line(k, "In today's money: ${inr(o.d("real_value_after_inflation"))}", Pal.DIM, 12f))
        }
    }

    private fun goal(ctx: Context, k: Kit, o: JsonObject) = shell(ctx, k, "Goal", Pal.BLUE) {
        val now = o.d("now"); val goal = o.d("goal")
        val r = k.row()
        r.addView(Ring(ctx, k, (now / goal).coerceIn(0.0, 1.0).toFloat()), k.lp(k.dp(92), k.dp(92)))
        r.addView(k.space(1, 14))
        val c = k.column()
        c.addView(line(k, "${inr(now)} of ${inr(goal)}", Pal.MUTED, 14f))
        c.addView(k.text(if (o.get("already_there")?.asBoolean == true) "Already there" else Fmt.pct1(o.d("growth_needed_percent")) + " to go", 22f, Pal.BLUE, 500).apply { maxLines = 1 })
        c.addView(line(k, "${o.d("multiple_needed")}× from today"))
        r.addView(c, k.lp(0, WRAP_CONTENT, 1f))
        addView(r, k.lp(MATCH_PARENT))
        o.getAsJsonObject("years_at")?.let { y ->
            addView(k.space(10))
            val g = k.row()
            y.entrySet().forEach { (rate, v) ->
                val col = k.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
                col.addView(k.text(if (v.isJsonNull) "—" else "${v.asDouble}y", 15f, Pal.TEXT, 500))
                col.addView(k.text("@$rate", 11f, Pal.DIM))
                g.addView(col, k.lp(0, WRAP_CONTENT, 1f))
            }
            addView(g, k.lp(MATCH_PARENT))
        }
    }

    private fun tax(ctx: Context, k: Kit, o: JsonObject) = shell(ctx, k, "In hand if you sell · ${o.get("tax_type")?.asString}", Pal.SAFFRON) {
        val gain = o.d("gain"); val t = o.d("tax"); val ch = o.d("charges"); val net = o.d("in_hand")
        addView(big(k, inr(net), Pal.GREEN))
        addView(line(k, "of ${inr(gain)} profit"))
        addView(k.space(10))
        addView(Stack(ctx, k, listOf(net to Pal.GREEN, t to Pal.RED, ch to Pal.SAFFRON)), k.lp(MATCH_PARENT, k.dp(14)))
        addView(k.space(8))
        val leg = k.row()
        for ((label, v, c) in listOf(Triple("In hand", net, Pal.GREEN), Triple("Tax", t, Pal.RED), Triple("Charges", ch, Pal.SAFFRON))) {
            leg.addView(k.text("● ", 12f, c)); leg.addView(k.text("$label ${inr(v)}   ", 12f, Pal.MUTED))
        }
        addView(leg)
        if (o.get("assumed")?.asBoolean == true) addView(line(k, "Assumed held over a year. Estimate only.", Pal.DIM, 11f).apply { setPadding(0, k.dp(6), 0, 0) })
    }

    private fun regret(ctx: Context, k: Kit, o: JsonObject) = shell(ctx, k, "Agar tab liya hota…", Pal.SAFFRON) {
        val gain = o.d("gain")
        addView(big(k, signed(gain), Pal.signed(gain)))
        addView(line(k, "${Fmt.qty(o.d("shares"))} shares of ${o.get("stock")?.asString} · ${Fmt.pct1(o.d("gain_percent"))}"))
        addView(k.space(10))
        addView(Bars(ctx, k, listOf("Would have paid" to o.d("would_have_paid"), "Worth today" to o.d("worth_today")), highlight = 1, horizontal = true),
            k.lp(MATCH_PARENT, k.dp(60)))
    }

    private fun note(ctx: Context, k: Kit, kicker: String, text: String) = shell(ctx, k, kicker, Pal.BLUE) {
        addView(k.text(text, 15f, Pal.TEXT).apply { setLineSpacing(0f, 1.3f) })
    }

    private fun holdings(ctx: Context, k: Kit, o: JsonObject) = shell(ctx, k, "Your Kite holdings · ${o.d("count").toInt()}", Pal.BLUE) {
        val pnl = o.d("pnl")
        addView(big(k, inr(o.d("current"))))
        addView(k.text("${signed(pnl)} (${Fmt.pct1(o.d("pnl_percent"))}) on ${inr(o.d("invested"))}", 14f, Pal.signed(pnl), 500))
        addView(k.space(10))
        val rows = o.getAsJsonArray("holdings").map { it.asJsonObject }.take(6)
        addView(Bars(ctx, k, rows.map { it["symbol"].asString to it.d("current") }, highlight = 0, horizontal = true),
            k.lp(MATCH_PARENT, k.dp(26 * rows.size + 8)))
        if (o.d("largest_weight_percent") >= 40) addView(line(k, "Largest holding is ${o.d("largest_weight_percent")}% of the portfolio.", Pal.SAFFRON, 12f).apply { setPadding(0, k.dp(6), 0, 0) })
    }

    private fun pulao(ctx: Context, k: Kit, o: JsonObject) = shell(ctx, k, "Pulao maths", Pal.SAFFRON) {
        addView(k.text(o.get("text")?.asString?.removePrefix("≈ ") ?: "", 17f, Pal.TEXT, 500).apply { setLineSpacing(0f, 1.3f) })
    }

    private fun stock(ctx: Context, k: Kit, o: JsonObject): LinearLayout? {
        val type = o.get("type")?.asString ?: return null
        return shell(ctx, k, type.uppercase(), Pal.BLUE) {
            addView(k.text(o.get("name")?.asString ?: "My portfolio", 18f, Pal.TEXT, 500))
            if (o.has("current_value")) {
                addView(big(k, inr(o.d("current_value"))))
                val p = o.d("pnl")
                addView(k.text("${signed(p)} (${Fmt.pct1(o.d("pnl_percent"))}) on ${inr(o.d("invested"))}", 14f, Pal.signed(p)))
            } else addView(line(k, "LTP ₹${Fmt.price(o.d("ltp"))}", Pal.MUTED, 15f))
        }
    }

    // -------------------------------------------------------------- charts

    /** Compounding curve that draws itself left→right with a gradient fill. */
    private class GrowthChart(ctx: Context, private val k: Kit, private val start: Double, private val rate: Double, private val years: Double) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = k.dpf(2.5f); color = Pal.GREEN; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.alpha(Pal.LINE, 0.7f); strokeWidth = 1f }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.DIM; textSize = k.dpf(10) }
        private var t = 0f
        init {
            ValueAnimator.ofFloat(0f, 1f).apply { duration = 900; interpolator = DecelerateInterpolator(1.5f); addUpdateListener { t = it.animatedValue as Float; invalidate() }; start() }
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val pad = k.dpf(16)
            val n = max(1, years.toInt())
            val end = start * (1 + rate / 100).pow(years)
            fun x(i: Double) = (i / years * w).toFloat()
            fun y(v: Double) = (h - pad - (v - start) / max(1.0, end - start) * (h - pad * 2)).toFloat()
            for (g in 0..3) c.drawLine(0f, pad + g * (h - pad * 2) / 3, w, pad + g * (h - pad * 2) / 3, grid)
            val path = Path(); val area = Path()
            val steps = n * 8
            val upto = (steps * t).toInt()
            for (s in 0..upto) {
                val yr = years * s / steps
                val v = start * (1 + rate / 100).pow(yr)
                if (s == 0) { path.moveTo(x(yr), y(v)); area.moveTo(x(yr), h) ; area.lineTo(x(yr), y(v)) } else { path.lineTo(x(yr), y(v)); area.lineTo(x(yr), y(v)) }
            }
            area.lineTo(x(years * upto / steps), h); area.close()
            fill.shader = LinearGradient(0f, 0f, 0f, h, Pal.alpha(Pal.GREEN, 0.35f), Pal.alpha(Pal.GREEN, 0f), Shader.TileMode.CLAMP)
            c.drawPath(area, fill); c.drawPath(path, p)
            if (t > 0.98f) {
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.GREEN }
                c.drawCircle(x(years) - k.dpf(3), y(end), k.dpf(4), dot)
            }
            c.drawText("Yr 0", 0f, h - 2, txt)
            val lbl = "Yr ${Fmt.qty(years)}"; c.drawText(lbl, w - txt.measureText(lbl), h - 2, txt)
        }
    }

    /** Animated bars. Vertical (labels below) or horizontal (label · bar · value). */
    private class Bars(ctx: Context, private val k: Kit, private val items: List<Pair<String, Double>>, private val highlight: Int, private val horizontal: Boolean = false) : View(ctx) {
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.MUTED; textSize = k.dpf(11) }
        private val val_ = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.TEXT; textSize = k.dpf(11) }
        private var t = 0f
        init { ValueAnimator.ofFloat(0f, 1f).apply { duration = 800; interpolator = OvershootInterpolator(0.8f); addUpdateListener { t = it.animatedValue as Float; invalidate() }; start() } }

        override fun onDraw(c: Canvas) {
            val mx = items.maxOf { it.second }.coerceAtLeast(1.0)
            val r = k.dpf(5)
            if (horizontal) {
                val rowH = height.toFloat() / items.size; val labelW = k.dpf(92); val valueW = k.dpf(78)
                items.forEachIndexed { i, (label, v) ->
                    val cy = rowH * i + rowH / 2
                    c.drawText(label, 0f, cy + k.dpf(4), txt)
                    val bw = ((width - labelW - valueW) * (v / mx) * t).toFloat()
                    bar.color = if (i == highlight) Pal.GREEN else Pal.alpha(Pal.BLUE, 0.55f)
                    c.drawRoundRect(RectF(labelW, cy - k.dpf(7), labelW + bw, cy + k.dpf(7)), r, r, bar)
                    val s = "₹" + Fmt.money(v, true); c.drawText(s, width - val_.measureText(s), cy + k.dpf(4), val_)
                }
            } else {
                val colW = width.toFloat() / items.size; val base = height - k.dpf(16)
                items.forEachIndexed { i, (label, v) ->
                    val bh = ((base - k.dpf(14)) * (v / mx) * t).toFloat()
                    val left = colW * i + colW * 0.25f; val right = colW * (i + 1) - colW * 0.25f
                    bar.color = if (i == highlight) Pal.GREEN else Pal.alpha(Pal.BLUE, 0.5f)
                    c.drawRoundRect(RectF(left, base - bh, right, base), r, r, bar)
                    c.drawText(label, colW * i + (colW - txt.measureText(label)) / 2, height - 2f, txt)
                    val s = "₹" + Fmt.money(v, true); c.drawText(s, colW * i + (colW - val_.measureText(s)) / 2, base - bh - k.dpf(4), val_)
                }
            }
        }
    }

    /** Progress ring that sweeps in. */
    private class Ring(ctx: Context, private val k: Kit, private val frac: Float) : View(ctx) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = k.dpf(9); color = Pal.LINE; strokeCap = Paint.Cap.ROUND }
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = k.dpf(9); strokeCap = Paint.Cap.ROUND }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.TEXT; textSize = k.dpf(16); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        private var t = 0f
        init { ValueAnimator.ofFloat(0f, 1f).apply { duration = 1000; interpolator = DecelerateInterpolator(2f); addUpdateListener { t = it.animatedValue as Float; invalidate() }; start() } }
        override fun onDraw(c: Canvas) {
            val s = k.dpf(9); val rect = RectF(s, s, width - s, height - s)
            c.drawArc(rect, 0f, 360f, false, bg)
            fg.shader = android.graphics.SweepGradient(width / 2f, height / 2f, intArrayOf(Pal.BLUE, Gem.VIOLET, Gem.PINK, Pal.BLUE), null)
            c.drawArc(rect, -90f, 360f * frac * t, false, fg)
            c.drawText("${(frac * 100 * t).toInt()}%", width / 2f, height / 2f + k.dpf(6), txt)
        }
    }

    /** One horizontal bar split into coloured parts. */
    private class Stack(ctx: Context, private val k: Kit, private val parts: List<Pair<Double, Int>>) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var t = 0f
        init { ValueAnimator.ofFloat(0f, 1f).apply { duration = 800; interpolator = DecelerateInterpolator(); addUpdateListener { t = it.animatedValue as Float; invalidate() }; start() } }
        override fun onDraw(c: Canvas) {
            val total = parts.sumOf { max(0.0, it.first) }.coerceAtLeast(1.0)
            var x = 0f; val r = height / 2f
            c.save(); c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, width * t, height.toFloat()), r, r, Path.Direction.CW) })
            for ((v, col) in parts) {
                val w = (width * (max(0.0, v) / total)).toFloat()
                p.color = col; c.drawRect(x, 0f, x + w, height.toFloat(), p); x += w
            }
            c.restore()
        }
    }
}
