package com.khayali.pulao.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.gson.JsonObject
import com.khayali.pulao.Kit
import com.khayali.pulao.Pal
import java.util.Locale
import kotlin.math.abs

/**
 * Native building blocks for market cards: drawn by the app (crisp at any size, colour-coded),
 * used where a web chart would make small text hard to read — ticker tapes, mover rows with
 * sparklines, sector bars, stat tiles and breadth bars.
 */
object NativeBlocks {
    private fun JsonObject.s(k: String) = get(k)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
    private fun JsonObject.dn(k: String) = get(k)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() }
    private fun JsonObject.list(k: String) = getAsJsonArray(k)?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()

    fun toneColor(v: Double?) = when { v == null -> Pal.MUTED; v > 0.005 -> Pal.GREEN; v < -0.005 -> Pal.RED; else -> Pal.MUTED }
    fun pct(v: Double) = (if (v > 0) "+" else if (v < 0) "−" else "") + String.format(Locale.US, "%.2f", abs(v)) + "%"
    fun price(v: Double) = if (v >= 1000) com.khayali.pulao.Fmt.price(v) else String.format(Locale.US, "%.2f", v)

    /** A filled, colour-coded change pill: "+1.24%". */
    fun pill(k: Kit, change: Double?, size: Float = 12.5f): TextView = k.text(change?.let { pct(it) } ?: "—", size, if (change == null) Pal.MUTED else 0xFFFFFFFF.toInt(), 600).apply {
        gravity = Gravity.CENTER
        background = k.rounded(if (change == null) Pal.alpha(Pal.MUTED, 0.15f) else Pal.alpha(toneColor(change), 0.85f), 6f)
        setPadding(k.dp(6), k.dp(3), k.dp(6), k.dp(3)); minWidth = k.dp(60)
    }

    fun section(k: Kit, title: String, tone: String): View = k.row().apply {
        setPadding(0, k.dp(16), 0, k.dp(6))
        val c = when (tone) { "up" -> Pal.GREEN; "down" -> Pal.RED; "warn" -> Pal.SAFFRON; else -> Pal.BLUE }
        addView(View(context).apply { background = k.rounded(c, 2f) }, k.lp(k.dp(4), k.dp(16)))
        addView(k.text(title.uppercase(), 12f, Pal.TEXT, 700).apply { letterSpacing = 0.06f; setPadding(k.dp(8), 0, 0, 0) })
    }

    /** Sparkline: green/red by direction, soft fill, dashed previous-close line, dot on the last point. */
    class Sparkline(ctx: Context, private val values: List<Double>, private val base: Double?) : View(ctx) {
        private val up = values.isNotEmpty() && values.last() >= (base ?: values.first())
        private val col = if (up) Pal.GREEN else Pal.RED
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 1.6f; color = col; strokeJoin = Paint.Join.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density; color = Pal.alpha(Pal.MUTED, 0.6f); pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f) }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
        override fun onDraw(c: Canvas) {
            if (values.size < 2) return
            val pad = resources.displayMetrics.density * 3
            val lo = minOf(values.min(), base ?: values.min()); val hi = maxOf(values.max(), base ?: values.max()); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
            fun x(i: Int) = pad + (width - 2 * pad) * i / (values.size - 1).toFloat()
            fun y(v: Double) = (pad + (height - 2 * pad) * (1 - (v - lo) / span)).toFloat()
            base?.let { c.drawLine(pad, y(it), width - pad, y(it), dash) }
            val p = Path().apply { moveTo(x(0), y(values[0])); for (i in 1 until values.size) lineTo(x(i), y(values[i])) }
            val area = Path(p).apply { lineTo(x(values.size - 1), height.toFloat()); lineTo(x(0), height.toFloat()); close() }
            fill.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Pal.alpha(col, 0.28f), Pal.alpha(col, 0f), Shader.TileMode.CLAMP)
            c.drawPath(area, fill); c.drawPath(p, line)
            c.drawCircle(x(values.size - 1), y(values.last()), resources.displayMetrics.density * 2.4f, dot)
        }
    }

    /** Ticker tape that scrolls by itself (pauses while touched). */
    fun tape(k: Kit, b: JsonObject): View {
        val items = b.list("items")
        val row = k.row()
        fun chip(i: JsonObject) = k.row().apply {
            setPadding(k.dp(10), k.dp(6), k.dp(10), k.dp(6))
            background = k.rounded(Pal.alpha(Pal.TEXT, 0.05f), 8f)
            addView(k.text(i.s("label"), 12.5f, Pal.TEXT, 700))
            addView(k.text("  " + i.s("value"), 12.5f, Pal.MUTED, 500))
            val ch = i.dn("change")
            addView(k.text("  " + (ch?.let { (if (it >= 0) "▲ " else "▼ ") + pct(it).trimStart('+', '−') } ?: ""), 12.5f, toneColor(ch), 700))
        }
        repeat(2) { items.forEach { row.addView(chip(it), k.lp(WRAP_CONTENT).apply { marginEnd = k.dp(6) }) } }       // twice, so it can loop seamlessly
        val sv = object : HorizontalScrollView(k.ctx) {
            var anim: ValueAnimator? = null
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                post {
                    val half = row.width / 2; if (half <= width) return@post
                    anim = ValueAnimator.ofInt(0, half).apply {
                        duration = (half / k.dpf(1) * 28).toLong().coerceAtLeast(6000); repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                        addUpdateListener { scrollTo(it.animatedValue as Int, 0) }; start()
                    }
                }
            }
            override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (ev.action == MotionEvent.ACTION_DOWN) anim?.pause() else if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) anim?.resume()
                return super.onTouchEvent(ev)
            }
        }
        sv.isHorizontalScrollBarEnabled = false; sv.addView(row)
        return sv.apply { setPadding(0, k.dp(10), 0, k.dp(2)) }
    }

    /** Mover rows: symbol + note, sparkline, price, colour-coded change. */
    fun movers(k: Kit, b: JsonObject): View = k.column().apply {
        if (b.s("title").isNotBlank()) addView(section(k, b.s("title"), b.s("tone")))
        b.list("rows").forEachIndexed { i, r ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(Pal.alpha(Pal.LINE, 0.5f)) }, k.lp(MATCH_PARENT, 1))
            val mine = r.get("mine")?.asBoolean == true
            addView(k.row().apply {
                setPadding(0, k.dp(8), 0, k.dp(8))
                if (Tickers.isSymbol(r.s("symbol"))) { Tickers.attach(this, r.s("symbol")); background = k.press(null, 6f) }
                addView(k.column().apply {
                    // NSE symbols are up to ~12 characters: give them room and shrink long ones rather than cut them
                    val sym = (if (mine) "★ " else "") + r.s("symbol")
                    addView(k.text(sym, if (sym.length > 10) 12f else 13.5f, if (mine) Pal.SAFFRON else Pal.TEXT, 700).apply { maxLines = 1 })
                    if (r.s("sub").isNotBlank()) addView(k.text(r.s("sub"), 10.5f, Pal.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                }, k.lp(0, WRAP_CONTENT, 1.6f))
                val sp = r.getAsJsonArray("spark")?.mapNotNull { e -> runCatching { e.asDouble }.getOrNull() }.orEmpty()
                if (sp.size >= 2) addView(Sparkline(context, sp, r.dn("base")), k.lp(0, k.dp(28), 0.9f).apply { marginStart = k.dp(4); marginEnd = k.dp(6) })
                addView(k.text(r.dn("price")?.let { price(it) } ?: "", 13f, Pal.TEXT, 500).apply { gravity = Gravity.END; maxLines = 1 }, k.lp(k.dp(70)))
                addView(pill(k, r.dn("change"), 12f), k.lp(WRAP_CONTENT).apply { marginStart = k.dp(6) })
            }, k.lp(MATCH_PARENT))
        }
    }

    /** Diverging bars: label left, bar from a centre line, value right — all native text. */
    fun bars(k: Kit, b: JsonObject): View = k.column().apply {
        if (b.s("title").isNotBlank()) addView(section(k, b.s("title"), ""))
        val rows = b.list("rows"); val unit = b.s("unit")
        val max = rows.maxOfOrNull { abs(it.dn("value") ?: 0.0) }?.takeIf { it > 0 } ?: 1.0
        rows.forEach { r ->
            val v = r.dn("value") ?: 0.0
            addView(k.row().apply {
                setPadding(0, k.dp(4), 0, k.dp(4))
                if (!r.s("label").contains(' ') && Tickers.isSymbol(r.s("label"))) { Tickers.attach(this, r.s("label")); background = k.press(null, 6f) }
                addView(k.column().apply {
                    addView(k.text(r.s("label"), 12.5f, Pal.TEXT, 500).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    if (r.s("sub").isNotBlank()) addView(k.text(r.s("sub"), 10.5f, Pal.DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                }, k.lp(0, WRAP_CONTENT, 1.1f))
                addView(object : View(context) {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG); val axis = Paint().apply { color = Pal.LINE; strokeWidth = resources.displayMetrics.density }
                    override fun onDraw(c: Canvas) {
                        val mid = width / 2f; val w = (width / 2f - 2) * (abs(v) / max).toFloat(); val h = height * 0.5f; val top = (height - h) / 2
                        p.color = toneColor(v); val r = resources.displayMetrics.density * 3
                        if (v >= 0) c.drawRoundRect(mid, top, mid + w, top + h, r, r, p) else c.drawRoundRect(mid - w, top, mid, top + h, r, r, p)
                        c.drawLine(mid, 0f, mid, height.toFloat(), axis)
                    }
                }, k.lp(0, k.dp(22), 1.3f))
                addView(k.text(if (unit == "%") pct(v) else com.khayali.pulao.Fmt.amount(v) + (if (unit.isNotBlank()) " $unit" else ""), 12.5f, toneColor(v), 700).apply { gravity = Gravity.END }, k.lp(k.dp(70)))
            }, k.lp(MATCH_PARENT))
        }
    }

    /** Stat tiles with a tinted background by direction. */
    fun kpis(k: Kit, b: JsonObject): View = k.column().apply {
        val items = b.list("items"); val per = if (items.size % 3 == 0 || items.size > 4) 3 else 2
        items.chunked(per).forEach { chunk ->
            addView(k.row(Gravity.TOP).apply {
                setPadding(0, k.dp(4), 0, k.dp(4))
                chunk.forEachIndexed { i, it ->
                    val ch = it.dn("change")
                    addView(k.column().apply {
                        background = k.rounded(Pal.alpha(toneColor(ch), if (ch == null) 0.07f else 0.13f), 10f)
                        setPadding(k.dp(10), k.dp(8), k.dp(8), k.dp(8))
                        addView(k.text(it.s("label"), 10.5f, Pal.MUTED, 600).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; letterSpacing = 0.03f })
                        addView(k.text(it.s("value"), if (per == 3) 15f else 17f, Pal.TEXT, 700).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                        if (ch != null) addView(k.text((if (ch >= 0) "▲ " else "▼ ") + pct(ch).trimStart('+', '−'), 12f, toneColor(ch), 700))
                        if (it.s("sub").isNotBlank()) addView(k.text(it.s("sub"), 10.5f, Pal.DIM).apply { maxLines = 2 })
                    }, k.lp(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = k.dp(6) })
                }
                repeat(per - chunk.size) { addView(View(context), k.lp(0, 1, 1f).apply { marginStart = k.dp(6) }) }
            }, k.lp(MATCH_PARENT))
        }
    }

    /** One bar split green / grey / red with counts. */
    fun breadth(k: Kit, b: JsonObject): View = k.column().apply {
        val up = b.dn("up")?.toInt() ?: 0; val down = b.dn("down")?.toInt() ?: 0; val flat = b.dn("flat")?.toInt() ?: 0
        val total = (up + down + flat).coerceAtLeast(1)
        addView(section(k, b.s("title"), if (up >= down) "up" else "down"))
        addView(k.row().apply {
            listOf(up to Pal.GREEN, flat to Pal.alpha(Pal.MUTED, 0.6f), down to Pal.RED).filter { it.first > 0 }.forEachIndexed { i, (n, c) ->
                addView(View(context).apply { background = k.rounded(c, 3f) }, k.lp(0, k.dp(12), n.toFloat() / total).apply { if (i > 0) marginStart = k.dp(2) })
            }
        }, k.lp(MATCH_PARENT))
        addView(k.row().apply {
            setPadding(0, k.dp(6), 0, 0)
            addView(k.text("▲ $up advancing", 12.5f, Pal.GREEN, 700), k.lp(0, WRAP_CONTENT, 1f))
            if (flat > 0) addView(k.text("$flat flat", 12f, Pal.MUTED, 500).apply { gravity = Gravity.CENTER }, k.lp(0, WRAP_CONTENT, 0.7f))
            addView(k.text("$down declining ▼", 12.5f, Pal.RED, 700).apply { gravity = Gravity.END }, k.lp(0, WRAP_CONTENT, 1f))
        }, k.lp(MATCH_PARENT))
        val ratio = if (down > 0) up.toDouble() / down else null
        addView(k.text("Advance/decline ratio ${ratio?.let { String.format(Locale.US, "%.2f", it) } ?: "—"} · ${if (up > down) "more stocks rising than falling" else if (down > up) "more stocks falling than rising" else "evenly split"}", 11.5f, Pal.DIM).apply { setPadding(0, k.dp(3), 0, 0) })
    }
}
