package com.khayali.pulao.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import com.khayali.pulao.Kit
import com.khayali.pulao.Pal

/** The AI accent: Kite blue → violet → pink → pulao saffron. */
object Gem {
    const val VIOLET = 0xFF8E7CF0.toInt()
    const val PINK = 0xFFFF6FA5.toInt()
    val COLORS = intArrayOf(Pal.BLUE, VIOLET, PINK, Pal.SAFFRON, Pal.BLUE)
}

/** Text whose gradient slowly slides — the "Namaste" greeting and the thinking label. */
class FlowingGradientText(ctx: Context) : TextView(ctx) {
    private val m = Matrix()
    private var shift = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { shift = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat().coerceAtLeast(1f)
        val g = LinearGradient(0f, 0f, w * 2, 0f, Gem.COLORS, null, Shader.TileMode.MIRROR)
        m.setTranslate(-shift * w * 2, 0f); g.setLocalMatrix(m)
        paint.shader = g
        super.onDraw(canvas)
    }
}

/** Rounded border made of a rotating sweep gradient — glows around the input while the model is working. */
class GlowBorder(private val k: Kit, private val radiusDp: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = k.dpf(2f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.SURFACE }
    private val rect = RectF()
    private val m = Matrix()
    var angle = 0f; set(v) { field = v; invalidateSelf() }
    var glow = 0f; set(v) { field = v; invalidateSelf() }

    override fun draw(c: Canvas) {
        rect.set(bounds); rect.inset(k.dpf(1f), k.dpf(1f))
        val r = k.dpf(radiusDp)
        c.drawRoundRect(rect, r, r, fill)
        if (glow <= 0f) {
            p.shader = null; p.color = Pal.LINE; p.alpha = 255
        } else {
            val sg = SweepGradient(rect.centerX(), rect.centerY(), Gem.COLORS, null)
            m.setRotate(angle, rect.centerX(), rect.centerY()); sg.setLocalMatrix(m)
            p.shader = sg; p.alpha = (255 * glow).toInt()
        }
        c.drawRoundRect(rect, r, r, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Three shimmering skeleton lines, shown until the first token arrives. */
class ShimmerLines(ctx: Context, private val k: Kit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), k.dp(58))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val x = -w + t * w * 3
        p.shader = LinearGradient(x, 0f, x + w, 0f,
            intArrayOf(Pal.alpha(Pal.BLUE, 0.18f), Pal.alpha(Gem.PINK, 0.45f), Pal.alpha(Pal.BLUE, 0.18f)), null, Shader.TileMode.CLAMP)
        val h = k.dpf(12); val gap = k.dpf(8); val r = h / 2
        for ((i, frac) in listOf(1f, 0.92f, 0.6f).withIndex()) {
            val top = i * (h + gap)
            c.drawRoundRect(0f, top, w * frac, top + h, r, r, p)
        }
    }
}

/**
 * Renders streamed markdown and fades in each new piece of text (~250 ms),
 * so answers "bloom" word by word instead of popping.
 */
class StreamingText(ctx: Context, private val k: Kit) : TextView(ctx) {
    private var full = ""
    private var revealedAt = LongArray(0)
    private var fader: ValueAnimator? = null

    init { k.style(this, "", 15.5f, Pal.TEXT, 400); setLineSpacing(0f, 1.35f); setTextIsSelectable(false) }

    fun update(text: String) {
        val now = System.currentTimeMillis()
        val old = revealedAt
        val keep = full.commonPrefixWith(text).length.coerceAtMost(old.size)
        revealedAt = LongArray(text.length) { i -> if (i < keep) old[i] else now }
        full = text
        render()
        if (fader?.isRunning != true) fader = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300; addUpdateListener { render() }; start()
        }
    }

    fun finish() { revealedAt = LongArray(full.length); render(); setTextIsSelectable(true) }

    private fun render() {
        val sb = Markdown.render(full, k)
        val now = System.currentTimeMillis()
        // Fade freshly arrived characters (cheap: one span per recent run).
        var i = 0
        while (i < full.length && i < sb.length) {
            val age = now - revealedAt.getOrElse(i) { 0 }
            if (age < 260) {
                var j = i
                while (j < full.length && j < sb.length && (now - revealedAt.getOrElse(j) { 0 }) < 260) j++
                val a = (age / 260f).coerceIn(0.15f, 1f)
                sb.setSpan(ForegroundColorSpan(Pal.alpha(Pal.TEXT, a)), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = j
            } else i++
        }
        text = sb
    }
}

/** Tiny markdown: **bold**, *italic*, `- ` bullets and `#` headings — enough for chat answers. */
object Markdown {
    fun render(src: String, k: Kit): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        src.lines().forEachIndexed { li, raw ->
            if (li > 0) out.append('\n')
            var line = raw
            val heading = line.trimStart().startsWith("#")
            if (heading) line = line.trimStart().trimStart('#').trimStart()
            val bullet = Regex("""^\s*([-*•])\s+""").find(line)
            if (bullet != null) { out.append("  •  "); line = line.substring(bullet.range.last + 1) }
            val lineStart = out.length
            var i = 0
            while (i < line.length) {
                if (line.startsWith("**", i)) {
                    val end = line.indexOf("**", i + 2)
                    if (end > i) {
                        val s = out.length; out.append(line, i + 2, end)
                        out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2; continue
                    } else { i += 2; continue }   // unfinished bold while streaming: hide the stars
                }
                if (line[i] == '*' && i + 1 < line.length && line[i + 1] != ' ') {
                    val end = line.indexOf('*', i + 1)
                    if (end > i) {
                        val s = out.length; out.append(line, i + 1, end)
                        out.setSpan(StyleSpan(Typeface.ITALIC), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1; continue
                    }
                }
                out.append(line[i]); i++
            }
            if (heading) out.setSpan(StyleSpan(Typeface.BOLD), lineStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }
}
