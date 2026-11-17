package com.khayali.pulao

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

/**
 * The privacy promise, shown everywhere. Keep in sync with the manifest, PRIVACY.md and
 * OnDeviceOnlyTest: the portfolio never leaves the phone; every use of the internet is optional
 * and switched on by the user.
 */
object Privacy {
    const val BADGE = "🔒 Your portfolio stays on your phone · not advice"
    const val TITLE = "🔒 Your portfolio never leaves your phone"
    val POINTS = listOf(
        "The internet is used for three optional things, each off until you choose it: the one-time AI model download (huggingface.co); Zerodha's official read-only Kite MCP, if you tap Connect Kite; and web lookups, if you switch Web on.",
        "With Web on, only a company name or your search words go out — to DuckDuckGo for search and Yahoo Finance for prices. Amounts, quantities and identifiers are stripped from every query in code. Your holdings are never sent.",
        "The AI chat runs entirely on your phone (Gemma 4, on-device). Your questions and Kite's numbers are never uploaded to any AI service.",
        "Reads Kite's screen only while Kite is open, and only to do the maths. All calculations run on this phone (edge, not cloud).",
        "Stored on this phone only: your settings, the hold periods you type in, the current chat thread and the chat's memory notes. Private files, excluded from cloud backup and phone-to-phone transfer. New chat clears the thread; Memory → Erase clears the notes.",
        "No login, no account, no analytics, no ads, no trackers.",
        "Never taps Buy/Sell, never places orders. Only Kite's navigation tabs pass through.",
        "Uninstall = everything gone.",
    )
}

/** Kite's dark theme, sampled from the app's own pixels (2026-11-16). */
object Pal {
    const val BG = 0xFF17202A.toInt()        // list background
    const val SURFACE = 0xFF1F2937.toInt()   // header / sheet cards
    const val ELEV = 0xFF263241.toInt()      // raised controls
    const val LINE = 0xFF324050.toInt()      // dividers, borders, summary card
    const val TEXT = 0xFFFFFFFF.toInt()
    const val MUTED = 0xFF9B9BAD.toInt()
    const val DIM = 0xFF6E7883.toInt()
    const val BLUE = 0xFF5282EB.toInt()      // Kite accent
    const val GREEN = 0xFF67AD5B.toInt()
    const val RED = 0xFFCF5B52.toInt()
    const val SAFFRON = 0xFFFFB547.toInt()   // our pulao — used sparingly
    const val VIOLET = 0xFF8E7CF0.toInt()

    fun alpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
    fun signed(v: Double) = if (v >= 0) GREEN else RED
}

/** Programmatic view kit so the overlay and the app share Kite's look. */
class Kit(val ctx: Context) {
    private val density = ctx.resources.displayMetrics.density
    fun dp(v: Number): Int = (v.toFloat() * density).toInt()
    fun dpf(v: Number): Float = v.toFloat() * density

    fun face(weight: Int): Typeface = Typeface.create(Typeface.DEFAULT, weight, false)

    fun text(s: CharSequence = "", size: Float = 14f, color: Int = Pal.TEXT, weight: Int = 400) =
        TextView(ctx).apply { style(this, s, size, color, weight) }

    fun style(tv: TextView, s: CharSequence, size: Float, color: Int, weight: Int) = tv.apply {
        text = s; textSize = size; setTextColor(color); typeface = face(weight)
        fontFeatureSettings = "tnum"; includeFontPadding = false
    }

    /** Kite-style muted label ("Invested", "Current"). */
    fun label(s: String, color: Int = Pal.MUTED) = text(s, 13f, color, 400)

    fun caps(s: String, color: Int = Pal.MUTED) = text(s.uppercase(), 11f, color, 500).apply { letterSpacing = 0.08f }

    fun rounded(color: Int, radius: Float = 6f, stroke: Int? = null, strokeDp: Float = 1f) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(radius)
        if (stroke != null) setStroke(dp(strokeDp), stroke)
    }

    fun press(bg: Drawable?, radius: Float = 6f) =
        RippleDrawable(ColorStateList.valueOf(Pal.alpha(Pal.BLUE, 0.22f)), bg, rounded(0xFFFFFFFF.toInt(), radius))

    fun lp(w: Int, h: Int = WRAP_CONTENT, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    fun space(h: Int, w: Int = 1) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(w), dp(h)) }

    fun divider(vMargin: Int = 12) = View(ctx).apply {
        setBackgroundColor(Pal.LINE)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { topMargin = dp(vMargin); bottomMargin = dp(vMargin) }
    }

    fun row(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(ctx).apply { this.gravity = gravity }
    fun column() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    // ---- chips & toggles (Kite filter-chip look) ----------------------------

    fun chip(label: String, onClick: () -> Unit) = text(label, 13f, Pal.TEXT, 400).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnClickListener { onClick() }
        styleChip(this, false)
    }

    fun styleChip(tv: TextView, selected: Boolean, color: Int = Pal.BLUE) {
        tv.background = press(
            if (selected) rounded(Pal.alpha(color, 0.14f), 4f, color)
            else rounded(Pal.BG, 4f, Pal.LINE), 4f,
        )
        tv.setTextColor(if (selected) color else Pal.TEXT)
    }

    /** Equal-width choice chips. */
    fun segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val box = row()
        val views = options.map { s -> chip(s) {}.apply { maxLines = 2 } }
        fun paint(sel: Int) = views.forEachIndexed { i, v -> styleChip(v, i == sel) }
        views.forEachIndexed { i, v ->
            v.setOnClickListener { paint(i); onSelect(i) }
            box.addView(v, lp(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        paint(selected)
        return box
    }

    /** Kite's top tabs: blue text + underline on the selected one ("Holdings | Positions"). */
    fun tabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val box = row()
        val cols = options.map { s ->
            val t = text(s, 15f, Pal.MUTED, 500).apply { gravity = Gravity.CENTER; setPadding(dp(14), dp(10), dp(14), dp(8)) }
            val bar = View(ctx)
            val c = column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(t, lp(WRAP_CONTENT))
                addView(bar, lp(dp(28), dp(2)))
                background = press(null, 6f)
            }
            Triple(c, t, bar)
        }
        fun paint(sel: Int) = cols.forEachIndexed { i, (_, t, bar) ->
            t.setTextColor(if (i == sel) Pal.BLUE else Pal.MUTED)
            bar.background = if (i == sel) rounded(Pal.BLUE, 2f) else null
        }
        cols.forEachIndexed { i, (c, _, _) ->
            c.setOnClickListener { paint(i); onSelect(i) }
            box.addView(c, lp(WRAP_CONTENT))
        }
        paint(selected)
        return android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(box)
            post { cols.getOrNull(selected)?.first?.let { smoothScrollTo((it.left - dp(40)).coerceAtLeast(0), 0) } }
        }
    }

    fun states(on: Int, off: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(on, off),
    )

    fun switchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val r = row().apply { setPadding(0, dp(8), 0, dp(8)) }
        val texts = column()
        texts.addView(text(title, 15f, Pal.TEXT))
        if (sub != null) texts.addView(text(sub, 12f, Pal.MUTED).apply { setPadding(0, dp(3), 0, 0) })
        r.addView(texts, lp(0, WRAP_CONTENT, 1f))
        val sw = Switch(ctx).apply {
            isChecked = checked
            thumbTintList = states(Pal.BLUE, Pal.MUTED)
            trackTintList = states(Pal.alpha(Pal.BLUE, 0.45f), Pal.LINE)
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        return r
    }

    fun input(value: String, hint: String, numeric: Boolean = true, onText: (String) -> Unit): EditText =
        EditText(ctx).apply {
            setText(value); this.hint = hint
            textSize = 15f; setTextColor(Pal.TEXT); setHintTextColor(Pal.DIM)
            fontFeatureSettings = "tnum"
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = rounded(Pal.BG, 4f, Pal.LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isSingleLine = true
            setSelectAllOnFocus(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = onText(s?.toString().orEmpty())
            })
        }

    fun button(label: String, primary: Boolean, onClick: () -> Unit) =
        text(label, 15f, if (primary) Pal.TEXT else Pal.BLUE, 500).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = press(if (primary) rounded(Pal.BLUE, 6f) else rounded(Pal.BG, 6f, Pal.LINE), 6f)
            setOnClickListener { onClick() }
        }

    /** Borderless icon button, like Kite's header icons. */
    fun iconButton(label: String, size: Float = 20f, color: Int = Pal.MUTED, onClick: () -> Unit) =
        text(label, size, color, 400).apply {
            gravity = Gravity.CENTER
            background = press(null, 999f)
            layoutParams = lp(dp(40), dp(40))
            setOnClickListener { onClick() }
        }

    /** Small tag like Kite's "SME" / "NSE" badges. */
    fun tag(label: String, color: Int = Pal.BLUE) = text(label, 10f, color, 500).apply {
        letterSpacing = 0.05f
        background = rounded(Pal.alpha(color, 0.15f), 3f)
        setPadding(dp(6), dp(3), dp(6), dp(3))
    }

    /** Kite-style card. */
    fun card(content: LinearLayout.() -> Unit): LinearLayout = column().apply {
        background = rounded(Pal.SURFACE, 8f)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        content()
    }

    /** Settings section card with a muted title. */
    fun section(title: String, content: LinearLayout.() -> Unit): LinearLayout = card {
        addView(text(title, 13f, Pal.MUTED, 500))
        addView(space(10))
        content()
    }

    /** Two-up "Invested | Current" style figure pair. */
    fun pair(lLabel: String, rLabel: String): Pair<Pair<TextView, TextView>, LinearLayout> {
        val l = column(); val r = column().apply { gravity = Gravity.END }
        val lv = text("", 18f, Pal.TEXT, 400); val rv = text("", 18f, Pal.TEXT, 400).apply { gravity = Gravity.END }
        l.addView(label(lLabel)); l.addView(space(6)); l.addView(lv)
        r.addView(label(rLabel).apply { gravity = Gravity.END }); r.addView(space(6)); r.addView(rv)
        val box = row(Gravity.TOP)
        box.addView(l, lp(0, WRAP_CONTENT, 1f)); box.addView(r, lp(0, WRAP_CONTENT, 1f))
        return (lv to rv) to box
    }
}

/** Text painted with a horizontal gradient. */
class GradientText(ctx: Context) : TextView(ctx) {
    var colors: IntArray? = null
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val c = colors
        paint.shader = if (c == null) null else {
            val left = totalPaddingLeft.toFloat()
            LinearGradient(left, 0f, left + paint.measureText(text.toString()), 0f, c, null, Shader.TileMode.CLAMP)
        }
        super.onDraw(canvas)
    }
}

/** Diverging bar: green grows right of centre for gains, red grows left for losses. */
class BarView(ctx: Context, private val k: Kit) : View(ctx) {
    var pct = 0.0
    var maxAbs = 20.0
    var picked = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val h = k.dpf(if (picked) 6 else 4)
        val cy = height / 2f
        val mid = width / 2f
        val r = h / 2
        p.color = Pal.alpha(Pal.LINE, 0.8f)
        c.drawRoundRect(0f, cy - h / 2, width.toFloat(), cy + h / 2, r, r, p)
        p.color = Pal.DIM
        c.drawRect(mid - 1, cy - h, mid + 1, cy + h, p)
        val len = (min(1.0, abs(pct) / maxAbs) * mid).toFloat().coerceAtLeast(r * 2)
        p.color = Pal.signed(pct)
        p.alpha = if (picked) 255 else 190
        if (pct >= 0) c.drawRoundRect(mid, cy - h / 2, mid + len, cy + h / 2, r, r, p)
        else c.drawRoundRect(mid - len, cy - h / 2, mid, cy + h / 2, r, r, p)
    }
}

/** Full-screen layer while the bubble is ON: dims Kite and outlines the tapped row in Kite blue. */
class HighlightView(ctx: Context, private val k: Kit) : View(ctx) {
    private var rect: RectF? = null
    private val loc = IntArray(2)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.alpha(Pal.BLUE, 0.10f) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = k.dpf(1.5f); color = Pal.BLUE
    }

    init { setBackgroundColor(Pal.alpha(0xFF000000.toInt(), 0.28f)) }

    fun highlight(r: Rect?) {
        getLocationOnScreen(loc)
        rect = r?.let {
            RectF(
                (it.left - loc[0]).toFloat(), (it.top - loc[1]).toFloat(),
                (it.right - loc[0]).toFloat(), (it.bottom - loc[1]).toFloat(),
            ).apply { inset(k.dpf(4), k.dpf(2)) }
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        rect?.let {
            c.drawRoundRect(it, k.dpf(6), k.dpf(6), fill)
            c.drawRoundRect(it, k.dpf(6), k.dpf(6), stroke)
        }
    }
}
