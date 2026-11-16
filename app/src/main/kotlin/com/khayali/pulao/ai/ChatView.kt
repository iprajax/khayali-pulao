package com.khayali.pulao.ai

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.khayali.pulao.Dreams
import com.khayali.pulao.Fmt
import com.khayali.pulao.Kit
import com.khayali.pulao.Pal
import com.khayali.pulao.Prefs
import com.khayali.pulao.R
import com.khayali.pulao.Stock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Gemini-style on-device chat. Used full-screen (ChatActivity) and inside the Kite
 * overlay sheet (where it already knows the tapped stock).
 *
 * Lifecycle: model card (download → verify) → warm-up → greeting + suggestion pills
 * → streaming answers with tool chips → follow-up pills.
 */
class ChatView(
    ctx: Context,
    private val prefs: Prefs,
    private val stockProvider: () -> Stock?,
    opener: (String) -> Boolean,
    private val compact: Boolean = false,
) : FrameLayout(ctx) {

    private val k = Kit(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mm = ModelManager.get(ctx)
    private val host = PrefsToolHost(prefs, { stockProvider() ?: turnStock ?: kitePortfolio }, opener, { kiteHoldings() })
    /** Real portfolio totals + rows from the last Kite holdings read (memory only). */
    private var kitePortfolio: Stock.Portfolio? = null
    private var kiteRows: List<com.google.gson.JsonObject> = emptyList()
    /** A holding @mentioned in the current message. */
    private var turnStock: Stock? = null
    private var lastPills: List<String> = emptyList()
    private var lastCall: ToolEvent? = null
    private val directTools by lazy { AppTools(host) }
    private val session = ChatSession(ctx.applicationContext, { prefs.personaName }, host, { prefs.tone }, scope)
    private val persona get() = Persona.of(prefs.personaName)

    private val list = k.column().apply { setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(24)) }
    private val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; addView(list) }
    private val pillRow = k.row()
    private val pills = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false; clipToPadding = false
        setPadding(k.dp(16), k.dp(4), k.dp(16), k.dp(8)); addView(pillRow)
    }
    private val glow = GlowBorder(k, 32f)
    private val input = EditText(ctx)
    private val send = TextView(ctx)
    private val inputBar = k.row()
    private var glowAnim: ValueAnimator? = null
    private var messageCount = 0
    private var shownState: String? = null

    private val kite by lazy { KiteMcpClient.get(context) }
    private val kitePill by lazy { k.text("", 12f, Pal.MUTED, 500).apply { setPadding(k.dp(12), k.dp(6), k.dp(12), k.dp(6)) } }
    private val kitePillRow by lazy { k.row(Gravity.CENTER).apply { setPadding(0, k.dp(6), 0, 0); addView(kitePill) } }

    init {
        background = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.ColorDrawable(Pal.BG),
            GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Pal.alpha(Pal.BLUE, 0.28f), Pal.alpha(Gem.VIOLET, 0.08f), 0x00000000)),
        ))
        val col = k.column()
        col.addView(kitePillRow, k.lp(MATCH_PARENT))
        col.addView(scroll, k.lp(MATCH_PARENT, 0, 1f))
        col.addView(pills, k.lp(MATCH_PARENT))
        col.addView(buildInputBar(), k.lp(MATCH_PARENT))
        addView(col, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        scope.launch {
            mm.state.combine(AiEngine.state) { m, e -> m to e }.collect { (m, e) -> onState(m, e) }
        }
        scope.launch { kite.state.collect { renderKitePill(it); preloadKite() } }
        mm.refresh()
    }

    // ---------------------------------------------------- Kite (MCP) pill

    private fun renderKitePill(s: KiteMcpClient.State) {
        val (label, color) = when (s) {
            is KiteMcpClient.State.Connected -> "●  Kite connected" + (s.name?.let { " · $it" } ?: "") to Pal.GREEN
            KiteMcpClient.State.Connecting -> "Connecting to Kite…" to Pal.MUTED
            is KiteMcpClient.State.WaitingForBrowser -> "Finish login on Zerodha's page · tap to reopen" to Pal.SAFFRON
            is KiteMcpClient.State.Failed -> "Kite: ${s.message} · tap to retry" to Pal.RED
            KiteMcpClient.State.Off -> "＋  Connect Kite (read-only)" to Pal.BLUE
        }
        kitePill.text = label; kitePill.setTextColor(color)
        kitePill.background = k.press(k.rounded(Pal.alpha(color, 0.12f), 999f, Pal.alpha(color, 0.5f)), 999f)
        kitePill.setOnClickListener { onKitePill(s) }
        kitePill.alpha = 0f; kitePill.animate().alpha(1f).setDuration(250).start()
        if (s is KiteMcpClient.State.Connected) { session.reset(); if (messageCount == 0) { shownState = null; onState(mm.state.value, AiEngine.state.value) } }
    }

    private fun onKitePill(s: KiteMcpClient.State) {
        when (s) {
            is KiteMcpClient.State.Connected -> android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Kite connected (read-only)")
                .setMessage("The chat can read your holdings, positions, margins and orders to daydream on real numbers. It can never place, modify or cancel orders.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Disconnect") { _, _ -> kite.disconnect(); session.reset() }.show()
            is KiteMcpClient.State.WaitingForBrowser -> openUrl(s.url)
            KiteMcpClient.State.Connecting -> Unit
            else -> android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Connect Zerodha Kite?")
                .setMessage("Uses Zerodha's official Kite MCP (mcp.kite.trade). You sign in on Zerodha's own page in your browser — if you're already logged in to Kite there, it's one tap. The app never sees your password.\n\n• Read-only: holdings, positions, margins, orders\n• Every order/GTT tool is blocked in code\n• Data comes from Zerodha to this phone and is used only by the on-device AI\n• Expires daily; disconnect anytime")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Connect") { _, _ -> scope.launch { kite.connect(::openUrl) } }.show()
        }
    }

    private fun openUrl(url: String) {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun onResumeFromLogin() = Unit

    /** The tapped stock changed (overlay): start a fresh thread with the new context. */
    fun onStockChanged() {
        session.reset(); messageCount = 0; shownState = null
        onState(mm.state.value, AiEngine.state.value)
    }

    fun newChat() = onStockChanged()

    fun release() { session.stop(); scope.cancel() }

    // ================================================================ states

    private fun onState(m: ModelManager.State, e: AiEngine.State) {
        if (m !is ModelManager.State.Ready) {
            setInputEnabled(false, "Download the model to chat")
            showModelCard(m); return
        }
        if (e is AiEngine.State.Failed && messageCount == 0) {
            setInputEnabled(true, "Ask Khayali Pulao"); showOnce("failed") { greeting(error = e.message) }; return
        }
        setInputEnabled(true, "Ask Khayali Pulao")
        if (messageCount == 0) showOnce("greeting") { greeting(null) }
    }

    private fun showOnce(key: String, build: () -> Unit) {
        if (shownState == key) return
        shownState = key
        list.removeAllViews(); build()
    }

    // --------------------------------------------------------------- greeting

    private fun greeting(error: String?) {
        val s = stockProvider()
        list.gravity = Gravity.CENTER
        val spark = ImageView(context).apply { setImageResource(R.drawable.bubble_icon) }
        list.addView(spark, k.lp(k.dp(if (compact) 44 else 56), k.dp(if (compact) 44 else 56)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        spark.alpha = 0f; spark.scaleX = 0.6f; spark.scaleY = 0.6f
        spark.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(500).setInterpolator(OvershootInterpolator(1.6f)).start()
        val hi = FlowingGradientText(context).apply {
            k.style(this, "What shall we cook today?", if (compact) 24f else 30f, Pal.TEXT, 300); gravity = Gravity.CENTER
        }
        list.addView(hi, k.lp(MATCH_PARENT).apply { topMargin = k.dp(16) })
        val sub = when (s) {
            null -> if (kite.connected) "Ask about your portfolio, any stock, or a what-if." else "Ask any what-if — or connect Kite to use your real portfolio."
            is Stock.Portfolio -> "Ask anything about your portfolio."
            else -> "Ask anything about ${s.name}."
        }
        list.addView(k.text(sub, if (compact) 15f else 17f, Pal.MUTED, 400).apply {
            gravity = Gravity.CENTER; setPadding(0, k.dp(6), 0, 0); setLineSpacing(0f, 1.25f)
        }, k.lp(MATCH_PARENT))
        if (error != null) list.addView(k.text("⚠️ $error", 13f, Pal.RED).apply { gravity = Gravity.CENTER; setPadding(0, k.dp(12), 0, 0) }, k.lp(MATCH_PARENT))
        // Persona picker
        val pr = k.row(Gravity.CENTER).apply { setPadding(0, k.dp(18), 0, 0) }
        Persona.entries.forEachIndexed { i, p ->
            val on = p == persona
            pr.addView(k.text("${p.icon}  ${p.title}", 13f, if (on) Pal.BLUE else Pal.MUTED, 500).apply {
                setPadding(k.dp(12), k.dp(8), k.dp(12), k.dp(8))
                background = k.press(k.rounded(if (on) Pal.alpha(Pal.BLUE, 0.14f) else Pal.SURFACE, 999f, if (on) Pal.BLUE else Pal.LINE), 999f)
                setOnClickListener { choosePersona(p) }
            }, k.lp(WRAP_CONTENT).apply { if (i > 0) marginStart = k.dp(6) })
        }
        list.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(pr) }, k.lp(WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        list.addView(k.text(persona.tagline, 12f, Pal.DIM).apply { gravity = Gravity.CENTER; setPadding(0, k.dp(6), 0, 0) }, k.lp(MATCH_PARENT))
        list.addView(k.text("🔒 ${ModelSpec.DISPLAY_NAME} · runs on your phone\n${Persona.DISCLAIMER}", 11f, Pal.DIM).apply {
            gravity = Gravity.CENTER; setPadding(k.dp(8), k.dp(20), k.dp(8), 0); setLineSpacing(0f, 1.3f)
        }, k.lp(MATCH_PARENT))
        for ((n, v) in listOf(hi, list.getChildAt(list.childCount - 2), list.getChildAt(list.childCount - 1)).withIndex()) {
            v.alpha = 0f; v.translationY = k.dpf(12)
            v.animate().alpha(1f).translationY(0f).setStartDelay(150L + n * 90L).setDuration(420).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        setPills(if (s == null) startersNow() else suggestions(s).map { (icon, label) -> "$icon  $label" })
    }

    private fun choosePersona(p: Persona) {
        val apply = { prefs.personaName = p.name; prefs.disclaimerSeen = true; session.reset(); shownState = null; onState(mm.state.value, AiEngine.state.value) }
        if (p != Persona.ANALYST && !prefs.disclaimerSeen) android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("${p.icon}  ${p.title}").setMessage(p.tagline + "\n\n" + Persona.DISCLAIMER + "\n\nIt will never tell you to buy, sell or hold a security.")
            .setNegativeButton("Cancel", null).setPositiveButton("I understand") { _, _ -> apply() }.show()
        else apply()
    }

    private fun suggestions(s: Stock?): List<Pair<String, String>> = if (s == null && persona != Persona.ANALYST)
        persona.starters.map { persona.icon to it }
    else if (s == null && kite.connected) listOf(
        "📊" to "/holdings",
        "📉" to "/chart " + (kite.knownSymbols.firstOrNull() ?: "INFY"),
        "📈" to "How is my portfolio doing?",
        "🧾" to "/fd 3",
        "🎯" to "/goal 1 crore",
        "💰" to "/tax",
    ) else when (s) {
        is Stock.Holding -> listOf(
            "📈" to "What if ${s.name} goes up 20%?",
            "🧾" to "Did I beat an FD? I've held it 2 years",
            "💰" to "How much would I get in hand if I sell today?",
            "🎯" to "When could this become ₹${Fmt.money(Dreams.niceCeil(s.current * 2), true)}?",
        )
        is Stock.Quote -> listOf(
            "📈" to "If I put ₹50,000 in ${s.name} and it goes up 15%?",
            "⏳" to "₹1 lakh in ${s.name} at 15% a year for 10 years?",
            "🤔" to "What if I had bought it 30% cheaper?",
            "🌾" to "Explain the gains in pulao maths",
        )
        is Stock.Portfolio -> listOf(
            "📉" to "What happens to my portfolio if the market falls 20%?",
            "🧾" to "Did my portfolio beat an FD over 3 years?",
            "🎯" to "How long to reach ₹1 crore at 12% a year?",
            "💰" to "Tax if I booked all my profit today?",
        )
        null -> listOf(
            "🔗" to "Connect Kite to chat about your real portfolio",
            "⏳" to "What does ₹1 lakh become at 12% a year for 10 years?",
            "📈" to "A stock at ₹1,450 — I own 250 at ₹1,200. What if it goes up 15%?",
            "🎯" to "How long to turn ₹10 lakh into ₹1 crore?",
            "⚙️" to "Set my FD rate to 7%",
        )
    }

    private fun followUps(): List<String> = when (stockProvider()) {
        is Stock.Holding -> listOf("And at −10%?", "Compare with FD", "Tax if I sell?", "Say it in pulao maths")
        is Stock.Quote -> listOf("What about ₹1 lakh?", "Over 5 years at 12%?", "If it falls 10%?")
        is Stock.Portfolio -> listOf("And +30%?", "Beat an FD over 5 years?", "Goal ₹2 crore?")
        null -> listOf("At 15% instead?", "In today's money?", "Explain simply")
    }

    // ------------------------------------------------------------ model card

    private fun showModelCard(m: ModelManager.State) {
        val key = "model:" + m::class.simpleName
        if (shownState != key) {
            shownState = key
            list.removeAllViews(); messageCount = 0
            list.addView(k.space(if (compact) 8 else 32))
            list.addView(FlowingGradientText(context).apply { k.style(this, "Meet your pulao AI", if (compact) 24f else 30f, Pal.TEXT, 600) })
            list.addView(k.text("A small Google model that runs entirely on your phone. It uses the app's own calculators, so the numbers are exact — and nothing you ask leaves your phone.",
                14f, Pal.MUTED).apply { setLineSpacing(0f, 1.35f); setPadding(0, k.dp(8), 0, k.dp(18)) })
            list.addView(modelCard, k.lp(MATCH_PARENT))
            setPills(emptyList())
        }
        updateModelCard(m)
    }

    private val mcTitle = k.text("", 16f, Pal.TEXT, 500)
    private val mcSub = k.text("", 13f, Pal.MUTED).apply { setLineSpacing(0f, 1.3f) }
    private val mcBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(Pal.BLUE)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Pal.LINE)
    }
    private val mcStats = k.text("", 12f, Pal.MUTED)
    private val mcPrimary = k.button("", primary = true) {}
    private val mcSecondary = k.button("", primary = false) {}
    private val modelCard: LinearLayout = k.card {
        val r = k.row()
        r.addView(ImageView(context).apply { setImageResource(R.drawable.bubble_icon) }, k.lp(k.dp(40), k.dp(40)))
        r.addView(k.space(1, 12))
        val c = k.column(); c.addView(mcTitle); c.addView(k.text("${ModelSpec.DISPLAY_NAME} · ${DownloadText.bytes(ModelSpec.SIZE_BYTES)} · ${ModelSpec.LICENSE} · from Hugging Face", 12f, Pal.DIM).apply { setPadding(0, k.dp(3), 0, 0) })
        r.addView(c, k.lp(0, WRAP_CONTENT, 1f))
        addView(r, k.lp(MATCH_PARENT))
        addView(k.space(12)); addView(mcSub)
        addView(k.space(12)); addView(mcBar, k.lp(MATCH_PARENT, k.dp(6))); addView(k.space(6)); addView(mcStats)
        addView(k.space(14)); addView(mcPrimary, k.lp(MATCH_PARENT)); addView(k.space(8)); addView(mcSecondary, k.lp(MATCH_PARENT))
    }

    private fun updateModelCard(m: ModelManager.State) {
        mcBar.visibility = GONE; mcStats.visibility = GONE; mcSecondary.visibility = GONE; mcPrimary.visibility = VISIBLE
        when (m) {
            is ModelManager.State.Absent -> {
                mcTitle.text = "Download the model once"
                mcSub.text = "One-time download from huggingface.co (about ${DownloadText.bytes(ModelSpec.SIZE_BYTES)}). After that, chat works offline. The download is the only time this app uses the internet."
                mcPrimary.text = "Download on Wi-Fi"; mcPrimary.setOnClickListener { mm.start(wifiOnly = true) }
                mcSecondary.visibility = VISIBLE; mcSecondary.text = "Download now (mobile data OK)"
                mcSecondary.setOnClickListener { mm.start(wifiOnly = false) }
            }
            is ModelManager.State.NotEnoughSpace -> {
                mcTitle.text = "Not enough space"
                mcSub.text = "Needs ${DownloadText.bytes(m.needBytes)} free; you have ${DownloadText.bytes(m.freeBytes)}. Free up some space and try again."
                mcPrimary.text = "Try again"; mcPrimary.setOnClickListener { mm.start(wifiOnly = true) }
            }
            is ModelManager.State.Downloading -> {
                val pct = DownloadText.percent(m.done, m.total)
                mcTitle.text = if (m.paused) "Paused — ${m.reason}" else "Downloading… $pct%"
                mcSub.text = "You can leave this screen — the download continues in the background and resumes if the connection drops."
                mcBar.visibility = VISIBLE; mcBar.isIndeterminate = m.done == 0L
                mcBar.setProgress(((m.done * 1000) / m.total.coerceAtLeast(1)).toInt(), true)
                mcStats.visibility = VISIBLE
                mcStats.text = "${DownloadText.bytes(m.done)} of ${DownloadText.bytes(m.total)}  ·  ${DownloadText.speed(m.bytesPerSec)}  ·  ${if (m.paused) "paused" else DownloadText.eta(m.etaSec)}"
                mcPrimary.text = "Cancel download"; mcPrimary.setOnClickListener { mm.cancel() }
            }
            is ModelManager.State.Verifying -> {
                mcTitle.text = "Checking the download…"
                mcSub.text = "Making sure the file is exactly the official one (SHA-256)."
                mcBar.visibility = VISIBLE; mcBar.isIndeterminate = false
                mcBar.setProgress(((m.done * 1000) / m.total.coerceAtLeast(1)).toInt(), true)
                mcPrimary.visibility = GONE
            }
            is ModelManager.State.Failed -> {
                mcTitle.text = "Download didn't finish"
                mcSub.text = m.message
                mcPrimary.text = "Retry"; mcPrimary.setOnClickListener { mm.start(wifiOnly = false) }
            }
            ModelManager.State.Ready -> Unit
        }
    }

    // ------------------------------------------------------------- input bar

    private fun buildInputBar(): View {
        inputBar.apply {
            background = glow
            setPadding(k.dp(20), k.dp(8), k.dp(8), k.dp(8))
            minimumHeight = k.dp(62)
        }
        input.apply {
            k.style(this, "", 16f, Pal.TEXT, 400)
            hint = "Ask Khayali Pulao"; setHintTextColor(Pal.DIM)
            background = null; maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEND || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) { submit(text.toString()); true } else false
            }
            setOnFocusChangeListener { _, f -> if (!session.busy) glow.glow = if (f) 0.35f else 0f }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(e: android.text.Editable?) = onTyping(e?.toString().orEmpty())
            })
        }
        send.apply {
            gravity = Gravity.CENTER; textSize = 18f; setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.BLUE) }
            setOnClickListener { if (session.busy) stopGenerating() else submit(input.text.toString()) }
        }
        setSendIcon(false)
        inputBar.addView(input, k.lp(0, WRAP_CONTENT, 1f))
        inputBar.addView(send, k.lp(k.dp(44), k.dp(44)))
        val wrap = FrameLayout(context).apply { setPadding(k.dp(12), k.dp(2), k.dp(12), k.dp(12)) }
        wrap.addView(inputBar, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return wrap
    }

    private fun setSendIcon(busy: Boolean) {
        send.text = if (busy) "■" else "↑"
        send.contentDescription = if (busy) "Stop" else "Send"
        send.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80).withEndAction {
            send.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
    }

    private fun setInputEnabled(on: Boolean, hint: String) {
        input.isEnabled = on; send.isEnabled = on; input.hint = hint
        inputBar.alpha = if (on) 1f else 0.5f
    }

    private fun setWorking(on: Boolean) {
        setSendIcon(on)
        glowAnim?.cancel()
        if (on) {
            glow.glow = 1f
            glowAnim = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 1800; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { glow.angle = it.animatedValue as Float }; start()
            }
        } else {
            ValueAnimator.ofFloat(glow.glow, if (input.hasFocus()) 0.35f else 0f).apply {
                duration = 400; addUpdateListener { glow.glow = it.animatedValue as Float }; start()
            }
        }
    }

    private fun setPills(items: List<String>, remember: Boolean = true, onTap: ((String) -> Unit)? = null) {
        if (remember) lastPills = items
        pillRow.removeAllViews()
        pills.visibility = if (items.isEmpty()) GONE else VISIBLE
        items.forEachIndexed { i, s ->
            val p = k.chip(s) { (onTap ?: { x -> submit(x.replace(Regex("^\\S+\\s{2}"), "")) })(s) }.apply {
                background = k.press(k.rounded(Pal.BG, 999f, Pal.LINE), 999f); setPadding(k.dp(14), k.dp(9), k.dp(14), k.dp(9))
                alpha = 0f; translationX = k.dpf(24)
            }
            pillRow.addView(p, k.lp(WRAP_CONTENT).apply { if (i > 0) marginStart = k.dp(8) })
            p.animate().alpha(1f).translationX(0f).setStartDelay(i * 60L).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    // ------------------------------------------------------------- messages

    private fun onTyping(t: String) {
        val mention = Commands.typingMention(t)
        when {
            Commands.isCommand(t) && !t.contains(' ') ->
                setPills(Commands.suggest(t).map { "${it.usage}  ·  ${it.help}" }, remember = false) { pill ->
                    val usage = pill.substringBefore("  ·  ")
                    val name = usage.removePrefix("/").substringBefore(' ')
                    if (usage.contains(' ')) { input.setText("/$name "); input.setSelection(input.text.length) } else submit(usage)
                }
            mention != null -> {
                val syms = Commands.matching(mention, kite.knownSymbols)
                if (syms.isEmpty()) setPills(listOf(if (kite.connected) "No holding matches @$mention" else "Connect Kite to @mention your stocks"), remember = false) { }
                else setPills(syms.map { "@$it" }, remember = false) { pill ->
                    input.setText(Commands.completeMention(input.text.toString(), pill.removePrefix("@"))); input.setSelection(input.text.length)
                }
            }
            else -> setPills(lastPills, remember = false)
        }
    }

    fun showHelp() {
        if (messageCount == 0) { list.removeAllViews(); list.gravity = Gravity.NO_GRAVITY; shownState = "chat" }
        messageCount++
        val bot = BotMessage().also { list.addView(it.root, k.lp(MATCH_PARENT).apply { topMargin = k.dp(14) }) }
        bot.appear()
        bot.done("**Commands**\n" + Commands.ALL.joinToString("\n") { "- **${it.usage}** — ${it.help}" } +
            "\n\n**@mentions** — type **@** to pick one of your Kite holdings, e.g. *What if @INFY goes up 20%?*", null, null)
        scrollToEnd()
    }

    /** /holdings, /positions… — read Kite directly and show the card; no model needed. */
    private fun runKite(section: String, label: String) {
        if (messageCount == 0) { list.removeAllViews(); list.gravity = Gravity.NO_GRAVITY; shownState = "chat" }
        messageCount++
        setPills(emptyList())
        addUser(label)
        val bot = BotMessage().also { list.addView(it.root, k.lp(MATCH_PARENT).apply { topMargin = k.dp(14) }) }
        bot.appear(); bot.thinking(false); scrollToEnd()
        if (!kite.connected) { bot.done("Connect Kite first — tap the **Connect Kite** pill at the top.", null, null); lastShown = null; showFollowUps(useModel = false); return }
        scope.launch {
            val t0 = System.nanoTime()
            val json = kite.account(section)
            val ok = !json.contains("\"error\"")
            rememberKite(section, json)
            bot.tool(ToolEvent("kite_account", "Read Kite $section", ok, json, """{"section":"$section"}""", (System.nanoTime() - t0) / 1_000_000))
            val summary = runCatching { com.google.gson.JsonParser.parseString(json).asJsonObject["summary"]?.asString }.getOrNull()
            val err = runCatching { com.google.gson.JsonParser.parseString(json).asJsonObject["error"]?.asString }.getOrNull()
            bot.done(err?.let { "⚠️ $it" } ?: summary ?: "Here it is.", null, null, question = label)
            lastShown = ToolEvent("kite_account", "", ok, json); lastQuestion = label; lastCall = null
            showFollowUps(useModel = false); scrollToEnd()
        }
    }

    private fun startersNow() = ContextPills.starters(
        kiteHoldings().map { ContextPills.H(it.name, it.current, it.pnlPct) }, persona, kite.connected)

    private fun kiteHoldings(): List<Stock.Holding> = kiteRows.mapNotNull { r ->
        runCatching { val q = r["qty"].asDouble; val a = r["avg_price"].asDouble; Stock.Holding(r["symbol"].asString, q, a, q * a, r["ltp"].asDouble) }.getOrNull()
    }.sortedByDescending { it.current }

    /** Load real holdings in the background so tools can answer "my biggest holding" straight away. */
    private fun preloadKite() {
        if (kite.connected && kiteRows.isEmpty()) scope.launch {
            rememberKite("holdings", kite.account("holdings"))
            if (messageCount == 0 && stockProvider() == null) setPills(startersNow())   // refresh with live holdings
        }
    }

    private fun rememberKite(section: String, json: String) {
        if (section != "holdings") return
        runCatching {
            val o = com.google.gson.JsonParser.parseString(json).asJsonObject
            if (o.has("error")) return
            kitePortfolio = Stock.Portfolio(o["invested"].asDouble, o["current"].asDouble)
            kiteRows = o.getAsJsonArray("holdings").map { it.asJsonObject }
        }
    }

    private fun submit(raw: String) {
        var text = raw.trim()
        if (text.isEmpty() || session.busy) return
        if (text.startsWith("Connect Kite")) { onKitePill(kite.state.value); return }
        if (Commands.isCommand(text)) {
            input.setText("")
            when (val c = Commands.parse(text)) {
                Commands.Parsed.Clear -> { newChat(); return }
                Commands.Parsed.Help -> { showHelp(); return }
                is Commands.Parsed.Kite -> { runKite(c.section, text); return }
                is Commands.Parsed.Chart -> { runChart(c.symbol, c.period, text); return }
                is Commands.Parsed.Unknown -> { showHelp(); return }
                is Commands.Parsed.Prompt -> { sendToModel(display = text, prompt = c.text); return }
            }
        }
        if (!mm.isReady()) { showOnce("model:" + mm.state.value::class.simpleName) {}; return }
        sendToModel(display = text, prompt = text)
    }

    private fun runChart(symbol: String, period: String?, label: String) {
        if (messageCount == 0) { list.removeAllViews(); list.gravity = Gravity.NO_GRAVITY; shownState = "chat" }
        messageCount++
        setPills(emptyList()); addUser(label)
        val bot = BotMessage().also { list.addView(it.root, k.lp(MATCH_PARENT).apply { topMargin = k.dp(14) }) }
        bot.appear(); bot.thinking(false); scrollToEnd()
        if (!kite.connected) { bot.done("Connect Kite first — tap **Connect Kite** at the top.", null, null); lastShown = null; showFollowUps(useModel = false); return }
        scope.launch {
            val t0 = System.nanoTime()
            val json = kite.history(symbol, period)
            val ok = !json.contains("\"error\"")
            bot.tool(ToolEvent("kite_chart", "Charted $symbol", ok, json, """{"symbol":"$symbol","period":"${period ?: "6m"}"}""", (System.nanoTime() - t0) / 1_000_000))
            val o = runCatching { com.google.gson.JsonParser.parseString(json).asJsonObject }.getOrNull()
            bot.done(o?.get("error")?.asString?.let { "⚠️ $it" } ?: o?.get("summary")?.asString ?: "", null, null, question = label)
            lastShown = ToolEvent("kite_chart", "", ok, json); lastQuestion = label; lastCall = null
            showFollowUps(useModel = false); scrollToEnd()
        }
    }

    private fun sendToModel(display: String, prompt: String) {
        val sym = Commands.mentions(display).firstOrNull()
        if (sym != null && kite.connected && kiteRows.none { it["symbol"].asString.equals(sym, true) } && sym in kite.knownSymbols) {
            // Holding details aren't loaded in this session yet: read them first, then continue.
            scope.launch { rememberKite("holdings", kite.account("holdings")); sendToModelNow(display, prompt) }
            return
        }
        sendToModelNow(display, prompt)
    }

    private fun sendToModelNow(display: String, prompt: String) {
        var text = prompt
        lastQuestion = display; lastShown = null; pillJob?.cancel()
        // @mentions: point the tools at that holding, or tell the model which symbol to quote.
        turnStock = null
        val mentioned = Commands.mentions(display)
        mentioned.firstOrNull()?.let { sym ->
            kiteRows.firstOrNull { it["symbol"].asString.equals(sym, true) }?.let { r ->
                val qty = r["qty"].asDouble; val avg = r["avg_price"].asDouble
                turnStock = Stock.Holding(sym, qty, avg, qty * avg, r["ltp"].asDouble)
            }
            text += if (turnStock != null) "\n(@$sym is one of my holdings — call get_current_stock, then the calculators on it.)"
                else "\n(@$sym = ${kite.exchangeOf(sym) ?: "NSE"}:$sym. ${if (kite.connected) "Use kite_quote for its price." else "Kite isn't connected, so ask me for its price."})"
        }
        if (session.busy || !mm.isReady()) return
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        input.setText("")
        if (messageCount == 0) { list.removeAllViews(); list.gravity = Gravity.NO_GRAVITY; shownState = "chat" }
        messageCount++
        setPills(emptyList())
        addUser(display)
        val bot = BotMessage().also { list.addView(it.root, k.lp(MATCH_PARENT).apply { topMargin = k.dp(14) }) }
        bot.appear()
        scrollToEnd()
        setWorking(true)
        session.send(text, object : ChatSession.Listener {
            override fun onThinking() = bot.thinking(AiEngine.state.value !is AiEngine.State.Ready)
            override fun onTool(e: ToolEvent) {
                if (e.ok && FollowUps.from(e.name, e.argsJson).isNotEmpty()) lastCall = e
                lastShown = e
                if (e.name == "kite_account") rememberKite(if (e.resultJson.contains("\"holdings\"")) "holdings" else "", e.resultJson)
                bot.tool(e); scrollToEnd()
            }
            override fun onText(full: String) { bot.text(full); scrollToEnd() }
            override fun onDone(full: String, stats: TurnStats) {
                val sums = stats.tools.filter { it.ok }.mapNotNull { runCatching { com.google.gson.JsonParser.parseString(it.resultJson).asJsonObject["summary"]?.asString }.getOrNull() }
                bot.done(NumberGuard.compose(full, sums), stats, { submit(display) }, question = display); setWorking(false)
                showFollowUps(); scrollToEnd()
            }
            override fun onError(message: String) { bot.error(message) { submit(display) }; setWorking(false) }
        })
    }

    private fun stopGenerating() { session.stop() }

    private var lastShown: ToolEvent? = null
    private var lastQuestion: String? = null
    private var pillJob: kotlinx.coroutines.Job? = null

    /**
     * Pills after an answer, curated from context in three layers:
     * exact re-runs of the last calculation → context pills (real holdings, last result) →
     * model-generated follow-ups that slide in when ready.
     */
    private fun showFollowUps(useModel: Boolean = true) {
        val exact = lastCall?.let { c -> FollowUps.from(c.name, c.argsJson).filter { it.args != c.argsJson && it.label != lastQuestion } }.orEmpty()
        val ctx = ContextPills.build(ContextPills.Ctx(kite.connected, kite.knownSymbols, lastShown?.name, lastShown?.resultJson, lastQuestion))
        val base = exact.map { "↻  " + it.label } + ctx
        fun render(extra: List<String>) = setPills((base + extra.map { "✨  $it" }).distinct().take(8), remember = true) { label ->
            when {
                label.startsWith("↻  ") -> exact.firstOrNull { "↻  " + it.label == label }?.let { runExact(it) }
                label == "Try again" -> lastQuestion?.let { submit(it) }
                label == "Do this with my real portfolio" -> lastQuestion?.let { submit("$it — use my real Kite portfolio") }
                else -> submit(label.removePrefix("✨  "))
            }
        }
        render(emptyList())
        if (!useModel || !mm.isReady()) return
        pillJob?.cancel()
        pillJob = scope.launch {
            val ai = session.suggest()
            if (ai.isNotEmpty() && !session.busy) render(ai)
        }
    }

    private fun runExact(p: FollowUps.Pill) {
        if (session.busy) return
        messageCount++
        setPills(emptyList())
        addUser(p.label)
        val bot = BotMessage().also { list.addView(it.root, k.lp(MATCH_PARENT).apply { topMargin = k.dp(14) }) }
        bot.appear()
        val t0 = System.nanoTime()
        val json = directTools.execute(p.tool, p.args)
        val ev = ToolEvent(p.tool, directTools.defs.firstOrNull { it.name == p.tool }?.label ?: p.tool, !json.contains("\"error\""), json, p.args, (System.nanoTime() - t0) / 1_000_000)
        bot.tool(ev)
        lastShown = ev
        if (ev.ok) lastCall = ev
        val summary = runCatching { com.google.gson.JsonParser.parseString(json).asJsonObject["summary"]?.asString }.getOrNull()
        bot.done(summary ?: "Done.", null, null, question = p.label)
        showFollowUps(useModel = false); scrollToEnd()
    }

    private fun addUser(text: String) {
        val b = k.text(text, 15.5f, Pal.TEXT).apply {
            background = GradientDrawable().apply {
                setColor(Pal.SURFACE); val r = k.dpf(22); cornerRadii = floatArrayOf(r, r, k.dpf(6), k.dpf(6), r, r, r, r)
            }
            setPadding(k.dp(16), k.dp(11), k.dp(16), k.dp(11)); setLineSpacing(0f, 1.3f)
            maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
        }
        val row = k.row(Gravity.END).apply { addView(b) }
        list.addView(row, k.lp(MATCH_PARENT).apply { topMargin = k.dp(14) })
        b.alpha = 0f; b.scaleX = 0.92f; b.scaleY = 0.92f; b.pivotX = 1000f
        b.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    private fun scrollToEnd() = scroll.post { scroll.smoothScrollTo(0, list.bottom) }

    /** One model turn: sparkle avatar, tool chips, shimmer → streaming text → actions. */
    private inner class BotMessage {
        val root = k.row(Gravity.TOP)
        private val avatar = ImageView(context).apply { setImageResource(R.drawable.bubble_icon) }
        val body = k.column()
        private val toolRow = k.row().apply { visibility = GONE }
        private val toolScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(toolRow) }
        private val cards = k.column()
        private val status = FlowingGradientText(context).apply { k.style(this, "", 13f, Pal.TEXT, 500) }
        private val shimmer = ShimmerLines(context, k)
        private val txt = MarkdownView(context, k)
        private val actions = k.row().apply { visibility = GONE }
        private var spin: ValueAnimator? = null

        init {
            root.addView(avatar, k.lp(k.dp(30), k.dp(30)).apply { topMargin = k.dp(2) })
            root.addView(k.space(1, 12))
            body.addView(status)
            body.addView(toolScroll, k.lp(MATCH_PARENT).apply { topMargin = k.dp(6) })
            body.addView(cards, k.lp(MATCH_PARENT))
            body.addView(shimmer, k.lp(MATCH_PARENT).apply { topMargin = k.dp(8) })
            body.addView(txt, k.lp(MATCH_PARENT).apply { topMargin = k.dp(4) })
            body.addView(actions, k.lp(MATCH_PARENT).apply { topMargin = k.dp(8) })
            root.addView(body, k.lp(0, WRAP_CONTENT, 1f))
            txt.visibility = GONE
        }

        fun appear() {
            root.alpha = 0f; root.translationY = k.dpf(10)
            root.animate().alpha(1f).translationY(0f).setDuration(250).start()
        }

        fun thinking(warming: Boolean) {
            status.text = if (warming) "Warming up the model…" else pick(THINKING)
            spin?.cancel()
            spin = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    avatar.rotation = f * 360f
                    val s = 1f + 0.08f * kotlin.math.sin(f * Math.PI * 2).toFloat(); avatar.scaleX = s; avatar.scaleY = s
                }
                start()
            }
        }

        fun tool(e: ToolEvent) {
            localTools += e
            toolRow.visibility = VISIBLE
            status.text = e.label + "…"
            val chip = k.text((if (e.ok) "✓  " else "!  ") + e.label, 12f, if (e.ok) Pal.GREEN else Pal.SAFFRON, 500).apply {
                background = k.rounded(Pal.alpha(if (e.ok) Pal.GREEN else Pal.SAFFRON, 0.12f), 999f)
                setPadding(k.dp(10), k.dp(5), k.dp(10), k.dp(5))
                scaleX = 0.6f; scaleY = 0.6f; alpha = 0f
            }
            toolRow.addView(chip, k.lp(WRAP_CONTENT).apply { if (toolRow.childCount > 0) marginStart = k.dp(6) })
            chip.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(280).setInterpolator(OvershootInterpolator(2.2f)).start()
            if (e.ok) ResultCards.build(context, k, e.name, e.resultJson)?.let { card ->
                cards.removeAllViews()          // newest result replaces older ones in this turn
                cards.addView(card, k.lp(MATCH_PARENT).apply { topMargin = k.dp(10) })
            }
        }

        fun text(full: String) {
            if (txt.visibility != VISIBLE) {
                shimmer.animate().alpha(0f).setDuration(150).withEndAction { shimmer.visibility = GONE }.start()
                txt.visibility = VISIBLE; status.visibility = GONE
            }
            txt.update(full)
        }

        fun done(full: String, stats: TurnStats?, retry: (() -> Unit)?, question: String = "") {
            stopSpin(); shimmer.visibility = GONE; status.visibility = GONE
            if (full.isBlank()) { txt.visibility = VISIBLE; txt.update("(stopped)") }
            else if (txt.visibility != VISIBLE) { txt.visibility = VISIBLE; txt.update(full) }
            actions.removeAllViews(); actions.visibility = VISIBLE
            actions.addView(k.iconButton("⧉", 16f) { copy(full) }.apply { contentDescription = "Copy" })
            if (retry != null) actions.addView(k.iconButton("↻", 18f) { retry() }.apply { contentDescription = "Regenerate" })
            if (question.isNotBlank()) actions.addView(k.iconButton("⤴", 18f) { shareAnswer(body, question) }.apply { contentDescription = "Share" })
            val st = stats ?: TurnStats(localMs(), null, null, null, null, localTools)
            val meta = k.text("⏱ ${secs(st.totalMs)}" + (st.backend?.let { " · $it" } ?: if (st.tools.isNotEmpty() && st.backend == null) " · app" else "") +
                (st.tokensPerSec?.let { " · ${it.toInt()} tok/s" } ?: "") + "  ▾", 11f, Pal.DIM).apply {
                setPadding(k.dp(8), k.dp(6), k.dp(8), k.dp(6)); maxLines = 1
                background = k.press(null, 999f)
                contentDescription = "Response details"
                setOnClickListener { toggleDetails(st, this) }
            }
            actions.addView(meta, k.lp(0, WRAP_CONTENT, 1f))
            if (stats?.backend != null && persona != Persona.ANALYST) body.addView(k.text("Educational only · not investment advice", 10.5f, Pal.DIM).apply { setPadding(k.dp(8), 0, 0, 0) })
            actions.alpha = 0f; actions.animate().alpha(1f).setDuration(300).start()
        }

        private val started = System.nanoTime()
        val localTools = mutableListOf<ToolEvent>()
        fun localMs() = (System.nanoTime() - started) / 1_000_000
        private var details: LinearLayout? = null

        /** Accordion: timing breakdown + every tool call with its inputs, result and time. */
        private fun toggleDetails(st: TurnStats, anchor: TextView) {
            details?.let { d ->
                d.animate().alpha(0f).setDuration(150).withEndAction { body.removeView(d) }.start()
                details = null; anchor.text = anchor.text.toString().replace("▴", "▾"); return
            }
            anchor.text = anchor.text.toString().replace("▾", "▴")
            val d = k.column().apply {
                background = k.rounded(Pal.alpha(Pal.SURFACE, 0.7f), 12f, Pal.LINE)
                setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12))
            }
            fun row(label: String, value: String, color: Int = Pal.TEXT) {
                val r = k.row().apply { setPadding(0, k.dp(3), 0, k.dp(3)) }
                r.addView(k.text(label, 12f, Pal.MUTED), k.lp(0, WRAP_CONTENT, 1f))
                r.addView(k.text(value, 12f, color, 500))
                d.addView(r, k.lp(MATCH_PARENT))
            }
            d.addView(k.caps("Response details", Pal.BLUE)); d.addView(k.space(6))
            row("Total time", secs(st.totalMs))
            st.warmupMs?.let { row("Model warm-up (first use)", secs(it)) }
            st.firstTokenMs?.let { row("Time to first word", secs(it)) }
            st.tokensPerSec?.let { row("Generation speed", "${it.toInt()} tokens/s") }
            row("Runs on", st.backend?.let { "Gemma 4 E2B · $it · on-device" } ?: "App only (no AI needed)")
            if (st.tools.isNotEmpty()) {
                d.addView(k.space(8)); d.addView(k.caps("Steps", Pal.MUTED)); d.addView(k.space(4))
                st.tools.forEachIndexed { i, t ->
                    val step = k.column().apply { setPadding(0, k.dp(6), 0, k.dp(6)) }
                    val h = k.row()
                    h.addView(k.text("${i + 1}. ${if (t.ok) "✓" else "!"} ${t.name}", 12.5f, if (t.ok) Pal.GREEN else Pal.SAFFRON, 500), k.lp(0, WRAP_CONTENT, 1f))
                    h.addView(k.text(secs(t.ms), 12f, Pal.MUTED))
                    step.addView(h, k.lp(MATCH_PARENT))
                    step.addView(k.text("in: " + t.argsJson.take(160), 11f, Pal.DIM).apply { setPadding(0, k.dp(2), 0, 0) })
                    val summary = runCatching { com.google.gson.JsonParser.parseString(t.resultJson).asJsonObject.let { o -> o["summary"]?.asString ?: o["error"]?.asString } }.getOrNull()
                    step.addView(k.text("out: " + (summary ?: t.resultJson.take(160)), 11f, Pal.DIM).apply { setPadding(0, k.dp(2), 0, 0) })
                    d.addView(step, k.lp(MATCH_PARENT))
                }
            }
            d.alpha = 0f; d.translationY = -k.dpf(6)
            body.addView(d, k.lp(MATCH_PARENT).apply { topMargin = k.dp(6) })
            d.animate().alpha(1f).translationY(0f).setDuration(220).start()
            details = d
        }

        fun error(message: String, retry: () -> Unit) {
            stopSpin(); shimmer.visibility = GONE; status.visibility = GONE
            txt.visibility = VISIBLE; txt.update("⚠️ $message")
            actions.removeAllViews(); actions.visibility = VISIBLE
            actions.addView(k.chip("Try again") { retry() })
        }

        private fun stopSpin() {
            spin?.cancel()
            avatar.animate().rotation(0f).scaleX(1f).scaleY(1f).setDuration(300).start()
        }
    }

    private fun shareAnswer(view: View, question: String) {
        val go = { ShareAnswer.share(context, view, question) }
        if (kite.connected) android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Share this answer?")
            .setMessage("The image may show your real portfolio amounts. It goes only to the app you pick.")
            .setNegativeButton("Cancel", null).setPositiveButton("Share") { _, _ -> go() }.show()
        else go()
    }

    private fun copy(s: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Khayali Pulao", s))
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun pick(l: List<String>) = l.random()
    private fun secs(ms: Long) = if (ms < 1000) "${ms} ms" else String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)

    companion object {
        private val THINKING = listOf("Working it out…", "Running the numbers…", "Checking the maths…")
    }
}
