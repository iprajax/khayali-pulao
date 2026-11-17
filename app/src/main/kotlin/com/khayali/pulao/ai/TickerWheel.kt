package com.khayali.pulao.ai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.khayali.pulao.Kit
import com.khayali.pulao.Pal
import kotlin.math.cos
import kotlin.math.sin

/**
 * The ticker wheel: tap or long-press any stock and its actions open on a ring around your finger.
 * Quiet by design — frosted disc with a hairline edge, thin line icons in light circles, the symbol in a
 * glass centre, a calm fade-and-scale, a light haptic tick. Tap the centre or outside to close.
 */
@SuppressLint("ViewConstructor")
class TickerWheel(
    ctx: Context, private val k: Kit, private val symbol: String,
    /** (icon key, label, command) */
    private val items: List<Triple<String, String, String>>,
    touchX: Float, touchY: Float, private val onPick: (String) -> Unit, private val onClose: () -> Unit,
) : FrameLayout(ctx) {

    private val radius = k.dpf(86)
    private var cx = touchX; private var cy = touchY
    private val white = 0xFFFFFFFF.toInt()

    private val disc = object : View(ctx) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = k.dpf(0.75f); color = Pal.alpha(white, 0.16f) }
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = k.dpf(0.75f); color = Pal.alpha(white, 0.07f) }
        override fun onDraw(c: Canvas) {
            val r = width / 2f
            fill.shader = RadialGradient(r * 0.7f, r * 0.55f, r * 1.4f, intArrayOf(Pal.alpha(0xFF2A3647.toInt(), 0.92f), Pal.alpha(0xFF141C26.toInt(), 0.94f)), null, Shader.TileMode.CLAMP)
            c.drawCircle(r, r, r - k.dpf(1), fill); c.drawCircle(r, r, r - k.dpf(1), edge)
            c.drawCircle(r, r, k.dpf(38), inner)
        }
    }
    private val centre = k.column().apply {
        gravity = Gravity.CENTER
        background = k.rounded(Pal.alpha(white, 0.08f), 999f, Pal.alpha(white, 0.22f))
        addView(k.text(symbol, if (symbol.length > 8) 9.5f else 11f, Pal.alpha(white, 0.95f), 600).apply { gravity = Gravity.CENTER; maxLines = 1; letterSpacing = 0.02f })
        setOnClickListener { close() }
        contentDescription = "Close $symbol actions"
    }
    private val buttons = items.map { (icon, label, cmd) ->
        k.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(LineIcon(ctx, icon, k).apply { background = k.rounded(Pal.alpha(white, 0.05f), 999f, Pal.alpha(white, 0.16f)) }, k.lp(k.dp(38), k.dp(38)))
            addView(k.text(label, 9.5f, Pal.alpha(white, 0.7f), 500).apply { gravity = Gravity.CENTER; setPadding(0, k.dp(3), 0, 0); maxLines = 1; letterSpacing = 0.04f }, k.lp(k.dp(64), WRAP_CONTENT))
            background = k.press(null, 14f)
            contentDescription = "$label for $symbol"
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); close { onPick(cmd) } }
        }
    }

    init {
        setBackgroundColor(Pal.alpha(0xFF000000.toInt(), 0.38f))
        isClickable = true; setOnClickListener { close() }
        val d = (radius * 2 + k.dpf(66)).toInt()
        addView(disc, LayoutParams(d, d))
        addView(centre, LayoutParams(k.dp(54), k.dp(54)))
        buttons.forEach { addView(it, LayoutParams(k.dp(64), WRAP_CONTENT)) }
        alpha = 0f
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val m = radius + k.dpf(36)
        cx = cx.coerceIn(m, (width - m).coerceAtLeast(m)); cy = cy.coerceIn(m + k.dpf(16), (height - m).coerceAtLeast(m + k.dpf(16)))
        fun place(v: View, x: Float, y: Float) = v.layout((x - v.measuredWidth / 2f).toInt(), (y - v.measuredHeight / 2f).toInt(), (x + v.measuredWidth / 2f).toInt(), (y + v.measuredHeight / 2f).toInt())
        place(disc, cx, cy); place(centre, cx, cy)
        buttons.forEachIndexed { i, v ->
            val a = Math.toRadians(-90.0 + i * 360.0 / buttons.size)
            // the icon (not the label) sits on the ring
            val x = cx + radius * cos(a).toFloat(); val y = cy + radius * sin(a).toFloat() + (v.measuredHeight / 2f - k.dpf(19))
            place(v, x, y)
        }
    }

    fun open() {
        post {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val ease = DecelerateInterpolator(2.2f)
            animate().alpha(1f).setDuration(180).setInterpolator(ease).start()
            disc.scaleX = 0.92f; disc.scaleY = 0.92f; disc.alpha = 0f
            disc.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).setInterpolator(ease).start()
            centre.scaleX = 0.85f; centre.scaleY = 0.85f; centre.alpha = 0f
            centre.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(40).setDuration(240).setInterpolator(ease).start()
            buttons.forEachIndexed { i, v ->
                val dx = (cx - (v.left + v.width / 2f)) * 0.28f; val dy = (cy - (v.top + v.height / 2f)) * 0.28f
                v.translationX = dx; v.translationY = dy; v.scaleX = 0.85f; v.scaleY = 0.85f; v.alpha = 0f
                v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                    .setStartDelay(70L + i * 22L).setDuration(260).setInterpolator(ease).start()
            }
        }
    }

    private var closing = false
    fun close(then: (() -> Unit)? = null) {
        if (closing) return; closing = true
        buttons.forEach { v -> v.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setStartDelay(0).setDuration(120).start() }
        disc.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(150).start()
        animate().alpha(0f).setDuration(160).withEndAction { onClose(); then?.invoke() }.start()
    }

    /** Thin, rounded line icons drawn in code — no emoji, one weight, one colour. */
    @SuppressLint("ViewConstructor")
    class LineIcon(ctx: Context, private val kind: String, private val k: Kit) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = k.dpf(1.6f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            color = Pal.alpha(0xFFFFFFFF.toInt(), 0.9f)
        }
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.alpha(0xFFFFFFFF.toInt(), 0.9f); textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        override fun onDraw(c: Canvas) {
            val s = k.dpf(16); val ox = (width - s) / 2f; val oy = (height - s) / 2f
            fun x(f: Float) = ox + f * s
            fun y(f: Float) = oy + f * s
            val path = Path()
            when (kind) {
                "trend" -> { path.moveTo(x(0f), y(0.8f)); path.lineTo(x(0.35f), y(0.45f)); path.lineTo(x(0.58f), y(0.65f)); path.lineTo(x(1f), y(0.2f))
                    path.moveTo(x(0.72f), y(0.2f)); path.lineTo(x(1f), y(0.2f)); path.lineTo(x(1f), y(0.48f)); c.drawPath(path, p) }
                "chart" -> { c.drawLine(x(0.15f), y(1f), x(0.15f), y(0.55f), p); c.drawLine(x(0.5f), y(1f), x(0.5f), y(0.15f), p); c.drawLine(x(0.85f), y(1f), x(0.85f), y(0.4f), p) }
                "news" -> { c.drawRoundRect(RectF(x(0.05f), y(0.05f), x(0.95f), y(0.95f)), k.dpf(2.5f), k.dpf(2.5f), p)
                    c.drawLine(x(0.25f), y(0.32f), x(0.75f), y(0.32f), p); c.drawLine(x(0.25f), y(0.52f), x(0.75f), y(0.52f), p); c.drawLine(x(0.25f), y(0.72f), x(0.55f), y(0.72f), p) }
                "events" -> { c.drawRoundRect(RectF(x(0.05f), y(0.15f), x(0.95f), y(0.95f)), k.dpf(2.5f), k.dpf(2.5f), p)
                    c.drawLine(x(0.05f), y(0.4f), x(0.95f), y(0.4f), p); c.drawLine(x(0.3f), y(0.02f), x(0.3f), y(0.25f), p); c.drawLine(x(0.7f), y(0.02f), x(0.7f), y(0.25f), p) }
                "quote" -> { t.textSize = s * 1.15f; c.drawText("₹", width / 2f, height / 2f - (t.descent() + t.ascent()) / 2f, t) }
                "dive" -> { c.drawCircle(x(0.42f), y(0.42f), s * 0.32f, p); c.drawLine(x(0.66f), y(0.66f), x(0.98f), y(0.98f), p) }
                "ask" -> { val r = RectF(x(0.02f), y(0.08f), x(0.98f), y(0.72f)); c.drawRoundRect(r, k.dpf(5f), k.dpf(5f), p)
                    path.moveTo(x(0.3f), y(0.72f)); path.lineTo(x(0.22f), y(0.95f)); path.lineTo(x(0.5f), y(0.72f)); c.drawPath(path, p) }
                else -> c.drawCircle(width / 2f, height / 2f, s * 0.3f, p)
            }
        }
    }

    companion object {
        /** The wheel's actions for a symbol — the same choices as [Tickers.menu], as (icon, label, command). */
        fun items(sym: String, web: Boolean, market: Boolean): List<Triple<String, String, String>> = Tickers.menu(sym, web, market).map { (label, cmd) ->
            val text = label.substringAfter(' ').trim()
            when {
                text.startsWith("Trend") -> Triple("trend", "Trend", cmd); text.startsWith("Chart") -> Triple("chart", "Chart", cmd)
                text.startsWith("Latest news") -> Triple("news", "News", cmd); text.startsWith("Results") -> Triple("events", "Events", cmd)
                text.startsWith("Full quote") -> Triple("quote", "Quote", cmd); text.startsWith("Deep dive") -> Triple("dive", "Deep dive", cmd)
                text.startsWith("Ask") -> Triple("ask", "Ask", cmd); else -> Triple("dot", text.take(10), cmd)
            }
        }
    }
}
