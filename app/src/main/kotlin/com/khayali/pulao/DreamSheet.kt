package com.khayali.pulao

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Kite-style bottom sheet. Four ways to cook a khayali pulao:
 *  Move   — "what if it moves X%?"  (ladder, slider, target price)
 *  Goal   — "I want ₹2 Cr. What does it take?"
 *  Years  — "If it compounds 12% a year for 10 years…"
 *  Regret — "Agar tab liya hota" — if only I'd bought at ₹X.
 */
class DreamSheet(
    private val ctx: Context,
    private val prefs: Prefs,
    initial: Stock?,
    startInSettings: Boolean,
    private val onClose: () -> Unit,
    /** False in the demo: there is no live Kite screen to follow. */
    private val live: Boolean = true,
) {
    private val k = Kit(ctx)

    var stock: Stock? = initial
        private set
    private var pct = prefs.lastPct
    private var settingsOpen = startInSettings
    private var mute = false               // suppresses text watchers while we set text ourselves

    private val body = k.column()
    private val tabBody = k.column()
    private lateinit var liveDot: TextView
    private lateinit var gear: TextView
    private var meta: TextView? = null
    private var metaPnl: TextView? = null
    private var qtyInput: EditText? = null
    private var quickBox: LinearLayout? = null

    /** Updates the current tab's numbers without touching its inputs (used for live ticks). */
    private var tabRefresh: () -> Unit = {}
    private var moveSetter: (Double, Boolean, Boolean) -> Unit = { _, _, _ -> }

    // Regret tab state
    private var regretPrice: Double? = null

    private enum class Tab(val label: String) {
        MOVE("Move %"), VERSUS("vs FD"), SPEND("Spend it"), GOAL("Goal"), YEARS("Years"), REGRET("Regret")
    }

    private fun onBack() = when {
        chatOpen -> toggleChat()
        settingsOpen && stock != null -> toggleSettings()
        else -> onClose()
    }

    // ---- "Ask ✨": on-device chat that already knows the tapped stock ----
    private lateinit var bodyScroll: ScrollView
    private val chatHolder = FrameLayout(ctx)
    private var chat: com.khayali.pulao.ai.ChatView? = null
    private var chatOpen = false
    private lateinit var askBtn: TextView

    private fun toggleChat() {
        chatOpen = !chatOpen
        if (chatOpen && chat == null) {
            chat = com.khayali.pulao.ai.ChatView(ctx, prefs, { stock }, ::openFromChat, compact = true)
            chatHolder.addView(chat, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        if (!chatOpen) chat?.hideKeyboard()
        bodyScroll.visibility = if (chatOpen) View.GONE else View.VISIBLE
        chatHolder.visibility = if (chatOpen) View.VISIBLE else View.GONE
        askBtn.setTextColor(if (chatOpen) Pal.BLUE else Pal.MUTED)
        if (chatOpen) { chatHolder.alpha = 0f; chatHolder.animate().alpha(1f).setDuration(200).start() }
    }

    /** Tool "open_screen" from chat: jump to a tab of this sheet. */
    private fun openFromChat(target: String): Boolean {
        val tab = when (target) {
            "move" -> Tab.MOVE; "vs_fd" -> Tab.VERSUS; "spend" -> Tab.SPEND
            "goal" -> Tab.GOAL; "years" -> Tab.YEARS; "regret" -> Tab.REGRET
            "settings" -> null
            else -> return false
        }
        view.post {
            if (chatOpen) toggleChat()
            if (tab == null) openSettings() else { prefs.tab = tab.name; settingsOpen = false; render() }
        }
        return true
    }

    /** Release the model session when the sheet goes away. */
    fun release() { chat?.release(); chat = null }

    /** Predictive-back callback (API 33+); targetSdk 36 stops delivering KEYCODE_BACK. */
    private val backCallback: Any? =
        if (android.os.Build.VERSION.SDK_INT >= 33) android.window.OnBackInvokedCallback { onBack() } else null

    val view: FrameLayout = object : FrameLayout(ctx) {
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (android.os.Build.VERSION.SDK_INT >= 33) findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback as android.window.OnBackInvokedCallback,
            )
        }

        override fun onDetachedFromWindow() {
            if (android.os.Build.VERSION.SDK_INT >= 33) findOnBackInvokedDispatcher()
                ?.unregisterOnBackInvokedCallback(backCallback as android.window.OnBackInvokedCallback)
            super.onDetachedFromWindow()
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    init {
        val r = k.dpf(12)
        view.background = GradientDrawable().apply {
            setColor(Pal.BG)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        view.elevation = k.dpf(24)
        view.isFocusableInTouchMode = true

        val col = k.column()
        col.addView(handle(), k.lp(MATCH_PARENT))
        col.addView(header(), k.lp(MATCH_PARENT))
        col.addView(View(ctx).apply { setBackgroundColor(Pal.LINE) }, k.lp(MATCH_PARENT, 1))
        bodyScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(body.apply { setPadding(k.dp(16), k.dp(14), k.dp(16), k.dp(48)) })
        }
        col.addView(bodyScroll, k.lp(MATCH_PARENT, 0, 1f))
        col.addView(chatHolder.apply { visibility = View.GONE }, k.lp(MATCH_PARENT, 0, 1f))
        view.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        render()
    }

    // ------------------------------------------------------------ public API

    /** [fromLive] = a price tick for the stock already shown, not a new tap. */
    fun setStock(s: Stock, fromLive: Boolean = false) {
        val old = stock
        stock = s
        if (!s.sameAs(old)) { chat?.onStockChanged(); regretPrice = null }
        if (settingsOpen && fromLive) return                 // a ticking price must not close Settings mid-edit
        if (settingsOpen) { settingsOpen = false; render(); return }
        if (s.sameAs(old)) {
            if (s != old) { refreshMeta(); tabRefresh(); blink() }
        } else {
            regretPrice = null
            render()
        }
    }

    fun openSettings() { if (!settingsOpen) toggleSettings() }

    // ---------------------------------------------------------------- chrome

    private fun handle(): View {
        val box = FrameLayout(ctx).apply { setPadding(0, k.dp(8), 0, k.dp(4)) }
        box.addView(View(ctx).apply { background = k.rounded(Pal.LINE, 999f) },
            FrameLayout.LayoutParams(k.dp(36), k.dp(4), Gravity.CENTER))
        var startY = 0f
        box.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> startY = e.rawY
                MotionEvent.ACTION_MOVE -> view.translationY = max(0f, e.rawY - startY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (view.translationY > view.height * 0.2f) onClose()
                    else view.animate().translationY(0f).setDuration(180).start()
            }
            true
        }
        return box
    }

    private fun header(): View {
        val r = k.row().apply { setPadding(k.dp(16), 0, k.dp(6), k.dp(4)) }
        r.addView(ImageView(ctx).apply { setImageResource(R.drawable.bubble_icon) }, k.lp(k.dp(26), k.dp(26)))
        r.addView(k.space(1, 10))
        r.addView(k.text("Khayali Pulao", 16f, Pal.TEXT, 500))
        r.addView(k.space(1, 10))
        liveDot = k.tag("● LIVE", Pal.GREEN)
        r.addView(liveDot)
        r.addView(View(ctx), k.lp(0, 1, 1f))
        askBtn = k.iconButton("✨", 18f) { toggleChat() }.apply { contentDescription = "Ask Khayali Pulao" }
        if (live) r.addView(askBtn)   // no AI chat on the fictional demo
        gear = k.iconButton("⚙") { if (chatOpen) toggleChat(); toggleSettings() }
        r.addView(gear)
        r.addView(k.iconButton("✕") { onClose() })
        return r
    }

    private fun toggleSettings() { settingsOpen = !settingsOpen; render() }

    private fun haptic(v: View, type: Int = HapticFeedbackConstants.KEYBOARD_TAP) {
        if (prefs.haptics) v.performHapticFeedback(type)
    }

    private fun blink() {
        liveDot.animate().alpha(0.25f).setDuration(120).withEndAction {
            liveDot.animate().alpha(1f).setDuration(240).start()
        }.start()
    }

    private fun render() {
        body.removeAllViews(); tabBody.removeAllViews()
        meta = null; metaPnl = null; qtyInput = null; quickBox = null
        tabRefresh = {}; moveSetter = { _, _, _ -> }
        liveDot.visibility = if (live && prefs.live && stock != null && !settingsOpen) View.VISIBLE else View.GONE
        gear.setTextColor(if (settingsOpen) Pal.BLUE else Pal.MUTED)
        when {
            settingsOpen -> buildSettings()
            stock == null -> buildEmpty()
            else -> buildDream(stock!!)
        }
    }

    private fun buildSettings() {
        body.gravity = Gravity.NO_GRAVITY
        val top = k.row()
        top.addView(k.text("Settings", 20f, Pal.TEXT, 500), k.lp(0, WRAP_CONTENT, 1f))
        if (stock != null) top.addView(k.chip("← Back") { toggleSettings() })
        body.addView(top)
        body.addView(k.space(14))
        body.addView(SettingsPanel.build(ctx, prefs, onChange = {}, onReset = { render() }))
    }

    private fun buildEmpty() {
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.addView(ImageView(ctx).apply { setImageResource(R.drawable.empty_dream) }, k.lp(k.dp(180), k.dp(180)))
        body.addView(k.space(16))
        body.addView(k.text("Tap a stock to daydream", 18f, Pal.TEXT, 500).apply { gravity = Gravity.CENTER })
        body.addView(k.space(8))
        body.addView(k.text("Turn 🍚 on, then tap a holding, a watchlist stock, or the Invested / Current card for your whole portfolio.",
            14f, Pal.MUTED).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f) })
        body.addView(k.space(20))
        body.addView(k.button("Settings", primary = false) { toggleSettings() }, k.lp(MATCH_PARENT))
    }

    // ------------------------------------------------------------- helpers

    private fun money(v: Double) = Fmt.money(v, prefs.compact)
    private fun signedMoney(v: Double) = Fmt.signedMoney(v, prefs.compact)
    private fun rupees(v: Double) = "₹" + money(v)
    private fun x2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)

    private fun dream(p: Double): Dream =
        Projector.project(stock!!, p, prefs.base, prefs.watchMode, prefs.watchInput)

    /** Today's value of what we're dreaming about. */
    private fun nowValue() = dream(0.0).now
    private fun costValue() = dream(0.0).cost

    private fun funLine(amount: Double): String? = if (prefs.showPulao) PulaoMaths.describe(amount, prefs.customItem) else null
    private fun quip(gain: Double, pct: Double): String? = PulaoMaths.quip(gain, pct, prefs.tone)
    private fun emoji(e: String) = if (prefs.tone == Tone.SERIOUS) "" else " $e"

    private fun taxKind(): TaxKind = Costs.kindFor(stock?.let { prefs.held(it.name) }, prefs.taxKind)

    private fun inHandLine(gain: Double, sellValue: Double): String {
        val kind = taxKind()
        val net = Costs.inHand(gain, sellValue, kind)
        val label = when (kind) { TaxKind.LTCG -> "LTCG 12.5% over ₹1.25L"; TaxKind.STCG -> "STCG 20%"; TaxKind.SLAB -> "30% slab" }
        return "≈ ${signedMoney(net)} in hand if sold — after $label, STT & charges"
    }

    private fun TextView.show(s: String?) {
        visibility = if (s == null) View.GONE else View.VISIBLE
        if (s != null) text = s
    }

    // ---------------------------------------------------------- the dream

    private fun buildDream(s: Stock) {
        body.gravity = Gravity.NO_GRAVITY

        // Kite holding-row style header
        val title = k.row()
        title.addView(k.text(s.name, 20f, Pal.TEXT, 500).apply { maxLines = 1 }, k.lp(0, WRAP_CONTENT, 1f))
        title.addView(when (s) {
            is Stock.Holding -> k.tag("HOLDING")
            is Stock.Quote -> k.tag(s.exchange)
            is Stock.Portfolio -> k.tag("PORTFOLIO")
        })
        body.addView(title)
        body.addView(k.space(8))
        val metaRow = k.row()
        meta = k.text("", 13f, Pal.MUTED).also { metaRow.addView(it, k.lp(0, WRAP_CONTENT, 1f)) }
        metaPnl = k.text("", 13f, Pal.GREEN).also { metaRow.addView(it) }
        body.addView(metaRow, k.lp(MATCH_PARENT))
        refreshMeta()

        if (s is Stock.Quote) { body.addView(k.space(14)); buildQtyControls() }

        body.addView(k.space(8))
        val tabs = Tab.entries.filter { !(it == Tab.REGRET && s is Stock.Portfolio) }
        val current = tabs.firstOrNull { it.name == prefs.tab } ?: Tab.MOVE
        body.addView(k.tabs(tabs.map { it.label }, tabs.indexOf(current)) { i ->
            prefs.tab = tabs[i].name; haptic(body); buildTab(tabs[i])
        }, k.lp(MATCH_PARENT))
        body.addView(View(ctx).apply { setBackgroundColor(Pal.LINE) }, k.lp(MATCH_PARENT, 1))
        body.addView(k.space(14))
        body.addView(tabBody, k.lp(MATCH_PARENT))
        body.addView(k.space(24))
        body.addView(k.text("${Privacy.BADGE}\nKhayali pulao — sapne dekhna free hai, aur data bhi phone se bahar nahi jaata. 🍚", 11f, Pal.DIM).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.4f)
        }, k.lp(MATCH_PARENT))
        buildTab(current)
    }

    private fun refreshMeta() {
        val s = stock ?: return
        when (s) {
            is Stock.Holding -> {
                meta?.text = "Qty. ${Fmt.qty(s.qty)}  •  Avg. ${Fmt.price(s.avg)}  •  LTP ${Fmt.price(s.ltp)}"
                metaPnl?.text = "${signedMoney(s.pnl)} (${Fmt.pct1(s.pnlPct)})"
                metaPnl?.setTextColor(Pal.signed(s.pnl))
            }
            is Stock.Quote -> {
                meta?.text = "LTP ${Fmt.price(s.ltp)}"
                metaPnl?.text = ""
            }
            is Stock.Portfolio -> {
                meta?.text = "Invested ${money(s.invested)}  •  Current ${money(s.current)}"
                metaPnl?.text = "${signedMoney(s.pnl)} (${Fmt.pct1(s.pnlPct)})"
                metaPnl?.setTextColor(Pal.signed(s.pnl))
            }
        }
    }

    private fun buildQtyControls() {
        body.addView(k.segmented(listOf("Invest ₹ amount", "Buy N shares"), prefs.watchMode.ordinal) {
            prefs.watchMode = QtyMode.entries[it]; haptic(body)
            mute = true; qtyInput?.setText(Fmt.qty(prefs.watchInput)); mute = false
            fillQuick(); tabRefresh()
        })
        body.addView(k.space(10))
        val r = k.row()
        qtyInput = k.input(Fmt.qty(prefs.watchInput), "amount") { t ->
            if (mute) return@input
            val v = Fmt.parse(t)?.takeIf { it > 0 } ?: return@input
            if (prefs.watchMode == QtyMode.AMOUNT) prefs.watchAmount = v else prefs.watchShares = v
            tabRefresh()
        }
        r.addView(qtyInput, k.lp(k.dp(120)))
        r.addView(k.space(1, 8))
        quickBox = k.row()
        r.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(quickBox) },
            k.lp(0, WRAP_CONTENT, 1f))
        body.addView(r, k.lp(MATCH_PARENT))
        fillQuick()
    }

    private fun fillQuick() {
        val box = quickBox ?: return
        box.removeAllViews()
        val (labels, values) = if (prefs.watchMode == QtyMode.AMOUNT)
            listOf("₹10K", "₹50K", "₹1L", "₹5L") to listOf(1e4, 5e4, 1e5, 5e5)
        else listOf("1", "10", "50", "100") to listOf(1.0, 10.0, 50.0, 100.0)
        labels.forEachIndexed { i, l ->
            if (i > 0) box.addView(k.space(1, 6))
            box.addView(k.chip(l) { qtyInput?.setText(Fmt.qty(values[i])); haptic(box) })
        }
    }

    private fun buildTab(t: Tab) {
        tabBody.removeAllViews()
        tabRefresh = {}; moveSetter = { _, _, _ -> }
        when (t) {
            Tab.MOVE -> buildMove()
            Tab.GOAL -> buildGoal()
            Tab.YEARS -> buildYears()
            Tab.REGRET -> buildRegret()
            Tab.VERSUS -> buildVersus()
            Tab.SPEND -> buildSpend()
        }
        tabRefresh()
    }

    // ================================================================ MOVE

    private fun setMove(p: Double, seekFrom: Boolean, fromTarget: Boolean = false) = moveSetter(p, seekFrom, fromTarget)

    /** −/+ walk the user's steps (0.5, 1, 1.5 … 5, 7, 10, 12, 15 …). */
    private fun stepTo(up: Boolean): Double {
        val losses = if (prefs.showLosses) Levels.parse(prefs.lossesText) else emptyList()
        val gains = Levels.parse(prefs.gainsText).ifEmpty { Levels.parse(Levels.DEFAULT_GAINS) }
        return Levels.next(gains, losses, pct, up).coerceIn(-50.0, prefs.sliderMax.toDouble())
    }

    private fun buildMove() {
        val s = stock!!
        val chips = mutableListOf<Pair<Double, TextView>>()
        val rows = mutableListOf<Triple<Double, View, BarView>>()

        val target = k.input("", "e.g. 2500") { t ->
            if (mute) return@input
            val v = Fmt.parse(t)?.takeIf { it > 0 } ?: return@input
            setMove(Projector.pctForTarget(v, dream(0.0).basePrice), seekFrom = false, fromTarget = true)
        }

        if (s !is Stock.Quote) {
            val labels = if (s is Stock.Portfolio) listOf("From invested", "From current") else listOf("From my avg", "From LTP")
            tabBody.addView(k.segmented(labels, prefs.base.ordinal) {
                prefs.base = Base.entries[it]; haptic(tabBody)
                mute = true; target.setText(""); mute = false
                tabRefresh()
            })
            tabBody.addView(k.space(12))
        }

        // Hero card
        val pctTv = k.text("", 34f, Pal.GREEN, 400)
        val priceTv = k.text("", 13f, Pal.MUTED)
        val (vals, pairBox) = k.pair("Would be worth", "P&L")
        val vsNow = k.text("", 13f, Pal.MUTED)
        val tax = k.text("", 13f, Pal.MUTED)
        val fun1 = k.text("", 13f, Pal.SAFFRON)
        val joke = k.text("", 13f, Pal.MUTED).apply { setTypeface(typeface, android.graphics.Typeface.ITALIC) }
        tabBody.addView(k.card {
            addView(k.label("If it moves"))
            addView(k.space(4))
            addView(pctTv)
            addView(k.space(4))
            addView(priceTv)
            addView(k.divider(14))
            addView(pairBox, k.lp(MATCH_PARENT))
            addView(k.space(10)); addView(vsNow); addView(k.space(4)); addView(tax); addView(k.space(6)); addView(fun1)
            addView(k.space(6)); addView(joke)
        }, k.lp(MATCH_PARENT))
        tabBody.addView(k.space(16))

        // Slider with step buttons
        val min = -50
        val maxP = prefs.sliderMax
        val seek = SeekBar(ctx).apply {
            max = (maxP - min) * 2
            progressTintList = android.content.res.ColorStateList.valueOf(Pal.BLUE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Pal.BLUE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Pal.LINE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val np = min + p / 2.0
                    if (np != pct) {
                        setMove(np, seekFrom = true)
                        if (np % 5.0 == 0.0) haptic(sb, HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        val sr = k.row()
        sr.addView(k.iconButton("−", 22f, Pal.BLUE) { setMove(stepTo(false), seekFrom = false); haptic(tabBody) })
        sr.addView(seek, k.lp(0, WRAP_CONTENT, 1f))
        sr.addView(k.iconButton("+", 22f, Pal.BLUE) { setMove(stepTo(true), seekFrom = false); haptic(tabBody) })
        tabBody.addView(sr, k.lp(MATCH_PARENT))
        val sl = k.row().apply { setPadding(k.dp(40), 0, k.dp(40), 0) }
        sl.addView(k.text("−50%", 11f, Pal.DIM), k.lp(0, WRAP_CONTENT, 1f))
        sl.addView(k.text("+$maxP%", 11f, Pal.DIM))
        tabBody.addView(sl, k.lp(MATCH_PARENT))
        tabBody.addView(k.space(12))

        // Target
        val tr = k.row()
        tr.addView(k.text(if (s is Stock.Portfolio) "Target value  ₹" else "Target price  ₹", 14f, Pal.TEXT), k.lp(0, WRAP_CONTENT, 1f))
        tr.addView(target, k.lp(k.dp(140)))
        tabBody.addView(tr, k.lp(MATCH_PARENT))
        tabBody.addView(k.space(14))

        // Chips
        val chipRow = k.row()
        (prefs.ladder() + listOf(50.0, 100.0)).distinct().sorted().forEachIndexed { i, l ->
            val c = k.chip(if (l == 100.0) "2×" else Fmt.pct(l)) { setMove(l, seekFrom = false); haptic(tabBody) }
            chips.add(l to c)
            if (i > 0) chipRow.addView(k.space(1, 6))
            chipRow.addView(c)
        }
        tabBody.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })
        tabBody.addView(k.space(18))

        // Ladder
        val ladder = k.column()
        tabBody.addView(ladder, k.lp(MATCH_PARENT))

        fun select() {
            for ((l, v, bar) in rows) {
                val sel = abs(l - pct) < 1e-9
                v.background = k.press(if (sel) k.rounded(Pal.SURFACE, 4f) else null, 4f)
                bar.picked = sel
            }
            for ((l, c) in chips) k.styleChip(c, abs(l - pct) < 1e-9, if (l < 0) Pal.RED else Pal.BLUE)
        }

        fun hero() {
            val d = dream(pct)
            pctTv.text = Fmt.pct(pct)
            pctTv.setTextColor(Pal.signed(pct))
            priceTv.text = when (s) {
                is Stock.Portfolio -> "from ${if (prefs.base == Base.AVG) "invested" else "current"} ${rupees(d.basePrice)}"
                is Stock.Holding -> "₹${Fmt.price(d.price)} per share  •  from ${if (prefs.base == Base.AVG) "avg" else "LTP"} ${Fmt.price(d.basePrice)}"
                is Stock.Quote -> "₹${Fmt.price(d.price)} per share  •  ${Fmt.qty(d.qty)} shares"
            }
            vals.first.text = rupees(d.value)
            vals.second.text = "${signedMoney(d.profit)} (${Fmt.pct1(d.profitPct)})"
            vals.second.setTextColor(Pal.signed(d.profit))
            vsNow.show(
                if (s is Stock.Quote) (if (d.qty <= 0) "₹${money(prefs.watchAmount)} can't buy 1 share — raise the amount" else null)
                else "${signedMoney(d.vsNow)} vs today's ${rupees(d.now)}",
            )
            vsNow.setTextColor(if (s !is Stock.Quote && d.vsNow < 0) Pal.RED else Pal.MUTED)
            tax.show(if (prefs.taxOn && d.profit > 0) inHandLine(d.profit, d.value) else null)
            fun1.show(funLine(if (s is Stock.Quote) d.profit else d.vsNow))
            joke.show(quip(d.profit, d.profitPct))
        }

        fun buildLadder() {
            ladder.removeAllViews(); rows.clear()
            val levels = prefs.ladder()
            val maxAbs = levels.maxOf { abs(it) }
            val vsToday = s !is Stock.Quote && prefs.base == Base.LTP
            val showPrice = s !is Stock.Portfolio
            val head = k.row().apply { setPadding(k.dp(8), 0, k.dp(8), k.dp(8)) }
            head.addView(k.label("Move"), k.lp(0, WRAP_CONTENT, 0.75f))
            if (showPrice) head.addView(k.label("Price"), k.lp(0, WRAP_CONTENT, 1f))
            head.addView(View(ctx), k.lp(0, 1, 0.8f))
            head.addView(k.label(if (vsToday) "Value • vs today" else "Value • P&L").apply { gravity = Gravity.END },
                k.lp(0, WRAP_CONTENT, 1.5f))
            ladder.addView(head, k.lp(MATCH_PARENT))
            ladder.addView(View(ctx).apply { setBackgroundColor(Pal.LINE) }, k.lp(MATCH_PARENT, 1))
            for (l in levels) {
                val d = dream(l)
                val gain = if (vsToday) d.vsNow else d.profit
                val row = k.row().apply {
                    setPadding(k.dp(8), k.dp(11), k.dp(8), k.dp(11))
                    setOnClickListener { setMove(l, seekFrom = false); haptic(this) }
                }
                row.addView(k.text(Fmt.pct(l), 14f, Pal.signed(l)), k.lp(0, WRAP_CONTENT, 0.75f))
                if (showPrice) row.addView(k.text(Fmt.price(d.price), 14f, Pal.TEXT), k.lp(0, WRAP_CONTENT, 1f))
                val bar = BarView(ctx, k).apply { this.pct = l; this.maxAbs = maxAbs }
                row.addView(bar, k.lp(0, k.dp(14), 0.8f))
                val right = k.column().apply { gravity = Gravity.END }
                right.addView(k.text(money(d.value), 14f, Pal.TEXT).apply { gravity = Gravity.END })
                right.addView(k.text(signedMoney(gain), 12f, Pal.signed(gain)).apply { gravity = Gravity.END; setPadding(0, k.dp(3), 0, 0) })
                row.addView(right, k.lp(0, WRAP_CONTENT, 1.5f))
                rows.add(Triple(l, row, bar))
                ladder.addView(row, k.lp(MATCH_PARENT))
                ladder.addView(View(ctx).apply { setBackgroundColor(Pal.alpha(Pal.LINE, 0.6f)) }, k.lp(MATCH_PARENT, 1))
            }
        }

        moveSetter = { p, seekFrom, fromTarget ->
            pct = p; prefs.lastPct = p
            if (!seekFrom) seek.progress = ((p + 50) * 2).roundToInt().coerceIn(0, seek.max)
            if (!fromTarget) { mute = true; target.setText(""); mute = false }
            hero(); select()
        }
        tabRefresh = r@{
            seek.progress = ((pct + 50) * 2).roundToInt().coerceIn(0, seek.max)
            hero(); buildLadder(); select()
        }
    }

    // ================================================================ GOAL

    private fun buildGoal() {
        val s = stock!!
        var goal = Dreams.niceCeil(nowValue() * 2)
        val chipRow = k.row()
        lateinit var goalInput: EditText

        tabBody.addView(k.label("My dream amount"))
        tabBody.addView(k.space(8))
        goalInput = k.input(Fmt.qty(goal), "₹ goal") { t ->
            if (mute) return@input
            Fmt.parse(t)?.takeIf { it > 0 }?.let { goal = it; tabRefresh() }
        }
        tabBody.addView(goalInput, k.lp(MATCH_PARENT))
        tabBody.addView(k.space(10))
        tabBody.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })
        tabBody.addView(k.space(14))

        val headline = k.label("")
        val bigPct = k.text("", 34f, Pal.GREEN)
        val needLine = k.text("", 13f, Pal.MUTED)
        val avgLine = k.text("", 13f, Pal.MUTED)
        val fun1 = k.text("", 13f, Pal.SAFFRON)
        val yearsTitle = k.label("How long at a steady yearly return")
        val yearsBox = k.column()
        tabBody.addView(k.card {
            addView(headline); addView(k.space(4)); addView(bigPct); addView(k.space(6))
            addView(needLine); addView(k.space(4)); addView(avgLine); addView(k.space(6)); addView(fun1)
            addView(k.divider(14))
            addView(yearsTitle)
            addView(k.space(6))
            addView(yearsBox, k.lp(MATCH_PARENT))
        }, k.lp(MATCH_PARENT))

        tabRefresh = r@{
            @Suppress("NAME_SHADOWING") val s = stock ?: return@r        // live ticks replace the stock; never use the one captured at build time
            val now = nowValue()
            chipRow.removeAllViews()
            Dreams.goalChips(now).forEachIndexed { i, g ->
                if (i > 0) chipRow.addView(k.space(1, 6))
                chipRow.addView(k.chip("₹" + Fmt.money(g, true)) {
                    goal = g; mute = true; goalInput.setText(Fmt.qty(g)); mute = false; haptic(chipRow); tabRefresh()
                }.also { k.styleChip(it, abs(g - goal) < 0.5) })
            }
            yearsBox.removeAllViews()
            if (now <= 0) {
                headline.text = "Nothing to grow yet"
                bigPct.text = "—"
                needLine.text = "That amount can't buy one share at this price. Raise the amount to plan a goal."
                avgLine.show(null); fun1.show(null); yearsTitle.visibility = View.GONE
                return@r
            }
            headline.text = "To turn ${rupees(now)} into ${rupees(goal)}"
            if (goal <= now) {
                bigPct.text = "Already there 🎉"
                needLine.text = "Your pulao is cooked — ${rupees(now - goal)} above this dream."
                avgLine.show(null); fun1.show(null); yearsTitle.visibility = View.GONE
            } else {
                yearsTitle.visibility = View.VISIBLE
                val mult = goal / now
                bigPct.text = Fmt.pct1(Dreams.pctToGoal(now, goal))
                needLine.text = when (s) {
                    is Stock.Portfolio -> "Your portfolio has to grow ${x2(mult)}×"
                    else -> "LTP must reach ₹${Fmt.price(s.ltp * mult)}  •  ${x2(mult)}× from today"
                }
                avgLine.show(when (s) {
                    is Stock.Holding -> "That's ${Fmt.pct1(Dreams.pctToGoal(s.invested, goal))} over what you invested"
                    is Stock.Portfolio -> "That's ${Fmt.pct1(Dreams.pctToGoal(s.invested, goal))} over what you invested"
                    is Stock.Quote -> null
                })
                fun1.show(funLine(goal - now))
                for (r in listOf(8.0, 12.0, 15.0, 20.0, 25.0)) {
                    val y = Dreams.yearsToGoal(now, goal, r)
                    val mine = abs(r - prefs.cagr) < 1e-9
                    val c = if (mine) Pal.BLUE else Pal.TEXT
                    val row = k.row().apply { setPadding(0, k.dp(7), 0, k.dp(7)) }
                    row.addView(k.text("${Fmt.pct(r).removePrefix("+")} a year", 14f, c), k.lp(0, WRAP_CONTENT, 1f))
                    row.addView(k.text(if (y == null) "—" else "~" + Dreams.years(y), 14f, c))
                    yearsBox.addView(row, k.lp(MATCH_PARENT))
                }
            }
        }
    }

    // =============================================================== YEARS

    private fun buildYears() {
        val s = stock!!
        val rates = listOf(8.0, 10.0, 12.0, 15.0, 18.0, 20.0, 25.0, 30.0)
        val rateChips = mutableListOf<Pair<Double, TextView>>()

        tabBody.addView(k.label("Yearly return"))
        tabBody.addView(k.space(8))
        val rc = k.row()
        rates.forEachIndexed { i, r ->
            val c = k.chip(Fmt.pct(r).removePrefix("+")) { prefs.cagr = r; haptic(rc); tabRefresh() }
            rateChips.add(r to c)
            if (i > 0) rc.addView(k.space(1, 6))
            rc.addView(c)
        }
        tabBody.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(rc) })
        tabBody.addView(k.space(14))

        val yr = k.row()
        yr.addView(k.label("Years"), k.lp(0, WRAP_CONTENT, 1f))
        val yearsLabel = k.text("", 14f, Pal.BLUE, 500)
        yr.addView(yearsLabel)
        tabBody.addView(yr, k.lp(MATCH_PARENT))
        val seek = SeekBar(ctx).apply {
            max = 29
            progress = (prefs.years - 1).coerceIn(0, 29)
            progressTintList = android.content.res.ColorStateList.valueOf(Pal.BLUE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Pal.BLUE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Pal.LINE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) { prefs.years = p + 1; haptic(sb, HapticFeedbackConstants.CLOCK_TICK); tabRefresh() }
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        tabBody.addView(seek, k.lp(MATCH_PARENT))
        tabBody.addView(k.space(14))

        val headline = k.label("").apply { setLineSpacing(0f, 1.2f) }
        val big = k.text("", 30f, Pal.TEXT)
        val multLine = k.text("", 14f, Pal.GREEN).apply { setLineSpacing(0f, 1.2f) }
        val dbl = k.text("", 13f, Pal.MUTED)
        val fun1 = k.text("", 13f, Pal.SAFFRON)
        val table = k.column()
        tabBody.addView(k.card {
            addView(headline); addView(k.space(6)); addView(big); addView(k.space(6))
            addView(multLine); addView(k.space(4)); addView(dbl); addView(k.space(6)); addView(fun1)
            addView(k.divider(14))
            addView(table, k.lp(MATCH_PARENT))
        }, k.lp(MATCH_PARENT))

        tabRefresh = r@{
            val r = prefs.cagr; val n = prefs.years
            for ((rv, c) in rateChips) k.styleChip(c, abs(rv - r) < 1e-9)
            yearsLabel.text = if (n == 1) "1 year" else "$n years"
            seek.progress = n - 1
            val now = nowValue(); val cost = costValue()
            val fv = Dreams.compound(now, r, n.toDouble())
            val what = when (s) {
                is Stock.Portfolio -> "your portfolio"
                is Stock.Holding -> "your ${Fmt.qty(s.qty)} shares"
                is Stock.Quote -> "${Fmt.qty(dream(0.0).qty)} shares"
            }
            headline.text = "In $n ${if (n == 1) "year" else "years"} at ${Fmt.pct(r).removePrefix("+")} a year, $what could be worth"
            big.text = rupees(fv)
            val safeNow = now.coerceAtLeast(1e-9)
            multLine.text = "${x2(fv / safeNow)}×  •  ${signedMoney(fv - now)} vs today" +
                (if (s !is Stock.Quote) "\n${signedMoney(fv - cost)} vs what you invested" else "")
            dbl.text = Dreams.doublingYears(r)?.let { "Doubles roughly every ${Dreams.years(it)}" } ?: ""
            fun1.show(funLine(fv - now))
            table.removeAllViews()
            val head = k.row().apply { setPadding(k.dp(6), 0, k.dp(6), k.dp(6)) }
            head.addView(k.label("After"), k.lp(0, WRAP_CONTENT, 1f))
            head.addView(k.label("Multiple"), k.lp(0, WRAP_CONTENT, 1f))
            head.addView(k.label("Value").apply { gravity = Gravity.END }, k.lp(0, WRAP_CONTENT, 1.4f))
            table.addView(head, k.lp(MATCH_PARENT))
            for (y in listOf(1, 3, 5, 10, 15, 20, 25, 30)) {
                val v = Dreams.compound(now, r, y.toDouble())
                val sel = y == n
                val c = if (sel) Pal.BLUE else Pal.TEXT
                val row = k.row().apply {
                    setPadding(k.dp(6), k.dp(9), k.dp(6), k.dp(9))
                    background = k.press(if (sel) k.rounded(Pal.BG, 4f, Pal.LINE) else null, 4f)
                    setOnClickListener { prefs.years = y; haptic(this); tabRefresh() }
                }
                row.addView(k.text("$y yr", 14f, c), k.lp(0, WRAP_CONTENT, 1f))
                row.addView(k.text("${x2(v / safeNow)}×", 14f, Pal.GREEN), k.lp(0, WRAP_CONTENT, 1f))
                row.addView(k.text(money(v), 14f, c).apply { gravity = Gravity.END }, k.lp(0, WRAP_CONTENT, 1.4f))
                table.addView(row, k.lp(MATCH_PARENT))
            }
        }
    }

    // ============================================================== REGRET

    private fun buildRegret() {
        val s = stock!!
        val ref = if (s is Stock.Holding) s.avg else s.ltp
        if (regretPrice == null) regretPrice = (ref * 0.8 * 100).roundToInt() / 100.0
        fun plain(p: Double) = String.format(java.util.Locale.US, "%.2f", p)

        tabBody.addView(k.text("Agar tab liya hota…", 16f, Pal.TEXT, 500))
        tabBody.addView(k.space(4))
        tabBody.addView(k.label(
            if (s is Stock.Holding) "If I had bought my ${Fmt.qty(s.qty)} shares cheaper than ₹${Fmt.price(s.avg)}"
            else "If I had bought earlier, at a lower price",
        ).apply { setLineSpacing(0f, 1.2f) })
        tabBody.addView(k.space(12))
        val r = k.row()
        r.addView(k.text("I could have bought at  ₹", 14f, Pal.TEXT), k.lp(0, WRAP_CONTENT, 1f))
        val input = k.input(plain(regretPrice!!), "price") { t ->
            if (mute) return@input
            Fmt.parse(t)?.takeIf { it > 0 }?.let { regretPrice = it; tabRefresh() }
        }
        r.addView(input, k.lp(k.dp(130)))
        tabBody.addView(r, k.lp(MATCH_PARENT))
        tabBody.addView(k.space(10))
        val cr = k.row()
        listOf(10, 20, 30, 50).forEachIndexed { i, off ->
            val p = ref * (1 - off / 100.0)
            if (i > 0) cr.addView(k.space(1, 6))
            cr.addView(k.chip("${Fmt.price(p)} (−$off%)") {
                regretPrice = p; mute = true; input.setText(plain(p)); mute = false; haptic(cr); tabRefresh()
            })
        }
        tabBody.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(cr) })
        tabBody.addView(k.space(14))

        val headline = k.label("").apply { setLineSpacing(0f, 1.2f) }
        val (vals, pairBox) = k.pair("Would have paid", "Worth today")
        val gainLine = k.text("", 15f, Pal.GREEN)
        val extra = k.text("", 13f, Pal.MUTED)
        val fun1 = k.text("", 13f, Pal.SAFFRON)
        tabBody.addView(k.card {
            addView(headline); addView(k.space(12)); addView(pairBox, k.lp(MATCH_PARENT))
            addView(k.divider(12)); addView(gainLine); addView(k.space(4)); addView(extra); addView(k.space(6)); addView(fun1)
        }, k.lp(MATCH_PARENT))

        tabRefresh = r@{
            @Suppress("NAME_SHADOWING") val s = stock ?: return@r
            val past = regretPrice ?: ref
            val qty = when (s) {
                is Stock.Holding -> s.qty
                is Stock.Quote -> if (prefs.watchMode == QtyMode.AMOUNT) floor(prefs.watchAmount / past) else prefs.watchShares
                is Stock.Portfolio -> 0.0
            }
            val rg = Dreams.regret(qty, past, s.ltp)
            headline.text = "${Fmt.qty(qty)} shares at ₹${Fmt.price(past)}, valued at today's LTP ₹${Fmt.price(s.ltp)}"
            vals.first.text = rupees(rg.paid)
            vals.second.text = rupees(rg.now)
            gainLine.text = "${signedMoney(rg.gain)} (${Fmt.pct1(rg.gainPct)})"
            gainLine.setTextColor(Pal.signed(rg.gain))
            extra.show(if (s is Stock.Holding) "${signedMoney(rg.gain - s.pnl)} more than your real P&L of ${signedMoney(s.pnl)}" else null)
            fun1.show(funLine(if (s is Stock.Holding) rg.gain - s.pnl else rg.gain))
        }
    }


    // ============================================================== VERSUS

    private fun heldLabel(y: Double) = when {
        y < 1 -> "${(y * 12).roundToInt()} mo"
        y % 1.0 == 0.0 -> "${y.toInt()} yr"
        else -> "${x1(y)} yr"
    }

    /** Rates as typed: 7.25 → "7.25", 6.5 → "6.5", 12 → "12". */
    private fun x1(v: Double) = String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun buildVersus() {
        val s = stock!!
        if (s is Stock.Quote) { buildVersusFuture(s); return }

        val invested = if (s is Stock.Holding) s.invested else (s as Stock.Portfolio).invested
        val current = if (s is Stock.Holding) s.current else (s as Stock.Portfolio).current
        val options = listOf(0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0)
        val chips = mutableListOf<Pair<Double, TextView>>()

        tabBody.addView(k.label("How long have you held ${if (s is Stock.Portfolio) "this portfolio (roughly)" else "it"}?"))
        tabBody.addView(k.space(8))
        val cr = k.row()
        options.forEachIndexed { i, y ->
            val c = k.chip(heldLabel(y)) { prefs.setHeld(s.name, y); haptic(cr); tabRefresh() }
            chips.add(y to c)
            if (i > 0) cr.addView(k.space(1, 6))
            cr.addView(c)
        }
        tabBody.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(cr) })
        tabBody.addView(k.space(14))

        val verdict = k.text("", 18f, Pal.TEXT, 500).apply { setLineSpacing(0f, 1.2f) }
        val mine = k.text("", 13f, Pal.MUTED).apply { setLineSpacing(0f, 1.3f) }
        val fdJoke = k.text("", 13f, Pal.SAFFRON)
        val table = k.column()
        tabBody.addView(k.card {
            addView(verdict); addView(k.space(6)); addView(fdJoke); addView(k.space(6)); addView(mine)
            addView(k.divider(14)); addView(table, k.lp(MATCH_PARENT))
        }, k.lp(MATCH_PARENT))
        tabBody.addView(k.space(8))
        tabBody.addView(k.text("Every rate here is yours to change in ⚙ Settings → Compare against. Gold & Nifty are assumed long-run averages, not quotes.",
            11f, Pal.DIM).apply { setLineSpacing(0f, 1.3f) })

        tabRefresh = r@{
            @Suppress("NAME_SHADOWING") val s = stock?.takeIf { it !is Stock.Quote } ?: return@r
            @Suppress("NAME_SHADOWING") val invested = if (s is Stock.Holding) s.invested else (s as Stock.Portfolio).invested
            @Suppress("NAME_SHADOWING") val current = if (s is Stock.Holding) s.current else (s as Stock.Portfolio).current
            val held = prefs.held(s.name)
            for ((y, c) in chips) k.styleChip(c, held != null && abs(y - held) < 1e-9)
            table.removeAllViews()
            if (held == null) {
                fdJoke.show(null)
                verdict.text = "Pick how long you've held it ☝️"
                mine.text = "Then see if your ${rupees(invested)} beat a bank FD, PPF, gold, the Nifty — and inflation."
            } else {
                val fd = Dreams.compound(invested, prefs.fdRate, held)
                val diff = current - fd
                verdict.text = if (diff >= 0) "You beat a ${x1(prefs.fdRate)}% FD by ${rupees(diff)}${emoji("🎉")}"
                    else "A ${x1(prefs.fdRate)}% FD would have given ${rupees(-diff)} more${emoji("😬")}"
                verdict.setTextColor(Pal.signed(diff))
                fdJoke.show(PulaoMaths.fdLine(diff >= 0, prefs.tone))
                val cagr = Bench.cagr(invested, current, held)
                val real = Bench.real(current, prefs.inflation, held)
                mine.text = "Your ${rupees(invested)} → ${rupees(current)} in ${heldLabel(held)}" +
                    (cagr?.let { "  •  ${Fmt.pct1(it)} a year" } ?: "") +
                    "\nIn today's money (after ${x1(prefs.inflation)}% inflation): ${rupees(real)}  (${signedMoney(real - invested)} real gain)"

                val head = k.row().apply { setPadding(0, 0, 0, k.dp(6)) }
                head.addView(k.label("Same ${rupees(invested)} in"), k.lp(0, WRAP_CONTENT, 1.5f))
                head.addView(k.label("Would be").apply { gravity = Gravity.END }, k.lp(0, WRAP_CONTENT, 1.1f))
                head.addView(k.label("You vs it").apply { gravity = Gravity.END }, k.lp(0, WRAP_CONTENT, 1.1f))
                table.addView(head, k.lp(MATCH_PARENT))
                for (b in prefs.benches()) {
                    val v = Dreams.compound(invested, b.ratePct, held)
                    val d = current - v
                    val row = k.row().apply { setPadding(0, k.dp(8), 0, k.dp(8)) }
                    val name = k.column()
                    name.addView(k.text("${b.emoji}  ${b.name}", 14f, Pal.TEXT))
                    name.addView(k.text("${x1(b.ratePct)}% a year", 11f, Pal.DIM).apply { setPadding(0, k.dp(2), 0, 0) })
                    row.addView(name, k.lp(0, WRAP_CONTENT, 1.5f))
                    row.addView(k.text(money(v), 14f, Pal.TEXT).apply { gravity = Gravity.END }, k.lp(0, WRAP_CONTENT, 1.1f))
                    row.addView(k.text(signedMoney(d), 14f, Pal.signed(d)).apply { gravity = Gravity.END }, k.lp(0, WRAP_CONTENT, 1.1f))
                    table.addView(row, k.lp(MATCH_PARENT))
                }
            }
        }
    }

    /** Watchlist: the future race — this stock at your dream return vs FD & friends. */
    private fun buildVersusFuture(q: Stock.Quote) {
        val years = listOf(1, 3, 5, 10, 20)
        val rates = listOf(8.0, 12.0, 15.0, 20.0, 25.0)
        val yChips = mutableListOf<Pair<Int, TextView>>()
        val rChips = mutableListOf<Pair<Double, TextView>>()
        tabBody.addView(k.label("If ${q.name} grows at"))
        tabBody.addView(k.space(8))
        val rr = k.row()
        rates.forEachIndexed { i, r ->
            val c = k.chip("${x1(r).removeSuffix(".0")}% / yr") { prefs.cagr = r; haptic(rr); tabRefresh() }
            rChips.add(r to c); if (i > 0) rr.addView(k.space(1, 6)); rr.addView(c)
        }
        tabBody.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(rr) })
        tabBody.addView(k.space(10))
        tabBody.addView(k.label("for"))
        tabBody.addView(k.space(8))
        val yr = k.row()
        years.forEachIndexed { i, y ->
            val c = k.chip("$y yr") { prefs.years = y; haptic(yr); tabRefresh() }
            yChips.add(y to c); if (i > 0) yr.addView(k.space(1, 6)); yr.addView(c)
        }
        tabBody.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(yr) })
        tabBody.addView(k.space(14))
        val verdict = k.text("", 18f, Pal.TEXT, 500).apply { setLineSpacing(0f, 1.2f) }
        val table = k.column()
        tabBody.addView(k.card { addView(verdict); addView(k.divider(14)); addView(table, k.lp(MATCH_PARENT)) }, k.lp(MATCH_PARENT))

        tabRefresh = r@{
            val r = prefs.cagr; val n = prefs.years
            for ((v, c) in rChips) k.styleChip(c, abs(v - r) < 1e-9)
            for ((v, c) in yChips) k.styleChip(c, v == n)
            val amt = dream(0.0).cost
            table.removeAllViews()
            if (amt <= 0) { verdict.text = "Set an amount above to compare"; return@r }
            val mineV = Dreams.compound(amt, r, n.toDouble())
            val fd = Dreams.compound(amt, prefs.fdRate, n.toDouble())
            verdict.text = "${rupees(amt)} → ${rupees(mineV)} in $n yr\n" +
                (if (mineV >= fd) "That's ${rupees(mineV - fd)} more than a ${x1(prefs.fdRate)}% FD" else "An FD would win by ${rupees(fd - mineV)}")
            verdict.setTextColor(if (mineV >= fd) Pal.GREEN else Pal.RED)
            for (b in prefs.benches().filter { it.name != "Inflation" }) {
                val v = Dreams.compound(amt, b.ratePct, n.toDouble())
                val row = k.row().apply { setPadding(0, k.dp(8), 0, k.dp(8)) }
                row.addView(k.text("${b.emoji}  ${b.name}  ${x1(b.ratePct)}%", 14f, Pal.TEXT), k.lp(0, WRAP_CONTENT, 1.6f))
                row.addView(k.text(money(v), 14f, Pal.TEXT).apply { gravity = Gravity.END }, k.lp(0, WRAP_CONTENT, 1f))
                row.addView(k.text(signedMoney(mineV - v), 13f, Pal.signed(mineV - v)).apply { gravity = Gravity.END }, k.lp(0, WRAP_CONTENT, 1f))
                table.addView(row, k.lp(MATCH_PARENT))
            }
            val real = Bench.real(mineV, prefs.inflation, n.toDouble())
            table.addView(k.text("In today's money (${x1(prefs.inflation)}% inflation) ${rupees(mineV)} feels like ${rupees(real)}", 12f, Pal.MUTED).apply {
                setPadding(0, k.dp(8), 0, 0)
            })
        }
    }

    // =============================================================== SPEND

    private var spendSrc = 0

    private fun buildSpend() {
        val s = stock!!
        val sources = if (s is Stock.Quote) listOf("Dream profit", "Whole value") else listOf("Today's profit", "Dream profit", "Whole value")
        spendSrc = spendSrc.coerceIn(0, sources.size - 1)
        val dreamLbl = k.label("")

        tabBody.addView(k.label("What can this money do?"))
        tabBody.addView(k.space(8))
        tabBody.addView(k.segmented(sources, spendSrc) { spendSrc = it; haptic(tabBody); tabRefresh() })
        tabBody.addView(k.space(6))
        tabBody.addView(dreamLbl)
        tabBody.addView(k.space(12))

        val amountTv = k.text("", 30f, Pal.TEXT)
        val inHand = k.text("", 13f, Pal.MUTED).apply { setLineSpacing(0f, 1.3f) }
        val ideas = k.column()
        tabBody.addView(k.card {
            addView(k.label("Amount")); addView(k.space(4)); addView(amountTv); addView(k.space(6)); addView(inHand)
            addView(k.divider(14)); addView(ideas, k.lp(MATCH_PARENT))
        }, k.lp(MATCH_PARENT))
        tabBody.addView(k.space(14))
        val er = k.row()
        er.addView(k.text("My monthly expenses  ₹", 14f, Pal.TEXT), k.lp(0, WRAP_CONTENT, 1f))
        er.addView(k.input(Fmt.qty(prefs.monthlyExpense), "50000") { t ->
            Fmt.parse(t)?.takeIf { it > 0 }?.let { prefs.monthlyExpense = it; tabRefresh() }
        }, k.lp(k.dp(130)))
        tabBody.addView(er, k.lp(MATCH_PARENT))

        fun idea(emoji: String, title: String, value: String, color: Int = Pal.TEXT) {
            val r = k.row(Gravity.TOP).apply { setPadding(0, k.dp(9), 0, k.dp(9)) }
            r.addView(k.text(emoji, 18f), k.lp(k.dp(34)))
            val c = k.column()
            c.addView(k.text(value, 16f, color, 500))
            c.addView(k.text(title, 12f, Pal.MUTED).apply { setPadding(0, k.dp(3), 0, 0); setLineSpacing(0f, 1.25f) })
            r.addView(c, k.lp(0, WRAP_CONTENT, 1f))
            ideas.addView(r, k.lp(MATCH_PARENT))
        }

        tabRefresh = r@{
            val d = dream(pct)
            val src = sources[spendSrc]
            dreamLbl.text = if (src == "Dream profit") "Dream profit uses your Move % pick: ${Fmt.pct(pct)}" else ""
            dreamLbl.visibility = if (src == "Dream profit") View.VISIBLE else View.GONE
            val (gain, sellValue) = when (src) {
                "Today's profit" -> d.now - d.cost to d.now
                "Dream profit" -> d.profit to d.value
                else -> d.value to d.value
            }
            val isProfit = src != "Whole value"
            val net = if (isProfit && gain > 0) Costs.inHand(gain, sellValue, taxKind()) else gain
            amountTv.text = (if (isProfit) signedMoney(gain) else rupees(gain))
            amountTv.setTextColor(if (isProfit) Pal.signed(gain) else Pal.TEXT)
            inHand.show(if (isProfit && gain > 0) inHandLine(gain, sellValue).removePrefix("≈ ") else null)
            ideas.removeAllViews()
            if (net <= 0) {
                idea("—", if (prefs.tone == Tone.MASALA) "Nothing to spend yet — the pulao is still raw. Try Dream profit or a bigger Move %."
                    else "Nothing to spend yet. Try Dream profit or a bigger Move %.", "₹0")
                return@r
            }
            (if (isProfit) quip(gain, if (d.cost > 0) gain / d.cost * 100 else 0.0) else null)?.let {
                idea(if (prefs.tone == Tone.MASALA) "🤭" else "💬", if (prefs.tone == Tone.MASALA) "says the khayali pulao" else "a thought", it, Pal.SAFFRON)
            }
            val m = prefs.monthlyExpense
            idea("🧾", "interest if parked in a ${x1(prefs.fdRate)}% FD (before tax on interest)", rupees(net * prefs.fdRate / 100 / 12) + " / month", Pal.GREEN)
            idea("🛋️", "of your ₹${money(m)} / month expenses covered" + if (prefs.tone == Tone.MASALA) " — resign letter draft ready?" else "", Dreams.years(net / m / 1.0 / 12).let { if (net / m < 12) "${x1(net / m)} months" else it })
            idea("🌴", "for life with the 4% rule — spend 4% a year, let the rest grow", rupees(net * 0.04 / 12) + " / month")
            idea("📈", "if left to compound at ${x1(prefs.cagr)}% for ${prefs.years} years", rupees(Dreams.compound(net, prefs.cagr, prefs.years.toDouble())))
            idea("🎯", "of a ₹1 Cr retirement corpus", Fmt.pct1(net / 1e7 * 100).removePrefix("+"))
            if (prefs.showPulao) for (it in PulaoMaths.items(prefs.customItem).reversed()) {
                val n = (net / it.price).toLong()
                if (n >= 1) idea(it.emoji, if (n == 1L) it.one else it.many, Fmt.amount(n.toDouble()))
            }
        }
    }
}
