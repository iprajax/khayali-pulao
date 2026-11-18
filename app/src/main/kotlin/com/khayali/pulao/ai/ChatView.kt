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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import com.khayali.pulao.ai.data.Setup as SU
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.khayali.pulao.ai.ChatFlow.busy
import com.khayali.pulao.ai.ChatFlow.canStop

/**
 * Gemini-style on-device chat. Used full-screen (ChatActivity) and inside the Kite
 * overlay sheet (where it already knows the tapped stock).
 *
 * The view renders one [ChatFlow.Phase] and executes [ChatFlow.Action]s: every way in — typing,
 * pills, /commands, @mentions, retries, the Kite and Web switches — goes through [submit] and the
 * same state machine. Commands need no model; free-form questions use the on-device model.
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
    private val memoryStore = MemoryStore.get(ctx)
    private val web by lazy { WebClient.get(context) }
    private val session = ChatSession(ctx.applicationContext, { prefs.personaName }, host, { prefs.tone }, scope,
        webOn = { prefs.webOn }, memory = { q -> memoryStore.select(q) }, commands = { c -> runForModel(c) })
    private val persona get() = Persona.of(prefs.personaName)

    /** Where the chat is right now; the only source of truth for "busy". */
    private var phase = ChatFlow.Phase.IDLE
    private var turnJob: Job? = null
    /** A command waiting for Kite to connect or Web to be switched on; re-run automatically afterwards. */
    private var pending: String? = null
    private var lastKiteLink: ChatFlow.Kite? = null
    /** The saved thread (full-screen chat only); survives the app being closed or killed. */
    /** Every saved chat (full-screen chat only); the one on screen is [store]. */
    val library = if (compact) null else ChatLibrary.get(ctx)
    private var store = library?.store()
    private val transcript = mutableListOf<ChatStore.Entry>()
    /** Memory bullets behind the previous answer, waiting to hear whether the user accepted it. */
    private var awaitingFeedback: Set<Int> = emptySet()

    private val list = k.column().apply { setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(24)) }
    private val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; addView(list) }
    /** The list above the input while a /command is being typed: each match with its description, or the matched command's usage. */
    private val suggestBox = k.column().apply { visibility = GONE; setPadding(k.dp(12), 0, k.dp(12), k.dp(4)) }
    /** A scroll view that never grows taller than ~40% of the screen, so the chat stays visible above the list. */
    private val suggestScroll = object : ScrollView(ctx) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * 0.4).toInt(), MeasureSpec.AT_MOST))
    }.apply { isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false; isFillViewport = false }
    private val pillRow = k.row()
    private val pills = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false; clipToPadding = false
        setPadding(k.dp(16), k.dp(4), k.dp(16), k.dp(8)); addView(pillRow)
    }
    private val glow = GlowBorder(k, 32f)
    private val input = EditText(ctx)
    private val send = TextView(ctx)
    private val inputBar = k.row()
    private val micBtn by lazy { Icons.button(context, k, "mic", Pal.TEXT, "Dictate", 21, 42) { dictate() } }
    private val liveBtn by lazy {
        Icons.button(context, k, "live", 0xFFFFFFFF.toInt(), "Talk (hands-free voice)", 20, 42) { startLive() }.apply {
            background = k.press(k.rounded(Pal.alpha(Pal.BLUE, 0.85f), 999f), 999f) }
    }
    /** The input bar's wrapper and the chat column, so voice can swap its dock in for the bar. */
    private var inputWrap: View? = null
    private var chatCol: LinearLayout? = null
    private var glowAnim: ValueAnimator? = null
    private var messageCount = 0
    private var shownState: String? = null

    private val kite by lazy { KiteMcpClient.get(context) }
    // declared before init: init -> refreshTickers() reads it (a lazy declared later is still null there)
    private val watch by lazy { WatchStore.get(context) }
    private val refresher by lazy { com.khayali.pulao.ai.data.Refresher(context) }
    private fun topPill() = k.text("", 12f, Pal.MUTED, 500).apply { setPadding(k.dp(12), k.dp(6), k.dp(12), k.dp(6)); maxLines = 1 }
    private val kitePill by lazy { topPill() }
    private val webPill by lazy { topPill() }
    private val kitePillRow by lazy {
        k.row(Gravity.CENTER).apply {
            setPadding(k.dp(12), k.dp(6), k.dp(12), 0)
            addView(kitePill); addView(webPill, k.lp(WRAP_CONTENT).apply { marginStart = k.dp(8) })
        }
    }

    init {
        background = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.ColorDrawable(Pal.BG),
            GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Pal.alpha(Pal.BLUE, 0.28f), Pal.alpha(Gem.VIOLET, 0.08f), 0x00000000)),
        ))
        val col = k.column().also { chatCol = it }
        col.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(kitePillRow) }, k.lp(MATCH_PARENT))
        col.addView(scroll, k.lp(MATCH_PARENT, 0, 1f))
        col.addView(suggestBox, k.lp(MATCH_PARENT))
        col.addView(pills, k.lp(MATCH_PARENT))
        col.addView(buildInputBar().also { inputWrap = it }, k.lp(MATCH_PARENT))
        addView(col, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        refreshTickers()
        restore()
        // the refresh schedule: due work runs now and every 15 minutes while the chat is open (evening data arrives by itself)
        scope.launch { while (true) { autoRefresh(); kotlinx.coroutines.delay(15 * 60_000L) } }
        renderWebPill()
        scope.launch {
            mm.state.combine(AiEngine.state) { m, e -> m to e }.collect { (m, e) -> onState(m, e) }
        }
        scope.launch { kite.state.collect { onKiteState(it) } }
        mm.refresh()
    }

    // ------------------------------------------------------------ state machine

    private fun dispatch(e: ChatFlow.Ev) {
        val before = phase
        phase = ChatFlow.next(phase, e)
        if (phase == before) return
        if (phase.busy != before.busy) setWorking(phase.busy)
        input.hint = hint()
    }

    private fun hint() = when {
        phase == ChatFlow.Phase.NEEDS_MODEL -> "Type / for commands"
        else -> "Ask Khayali Pulao"
    }

    private fun env() = ChatFlow.Env(mm.isReady(), kite.link, prefs.webOn, readyArtifacts())

    /** Artifacts installed on the phone, for the dependency check before a command runs. */
    private fun readyArtifacts(): Set<String> = buildSet {
        if (com.khayali.pulao.ai.data.MarketPack.get(context).state.value.let { it is com.khayali.pulao.ai.data.Artifacts.State.Ready || it is com.khayali.pulao.ai.data.Artifacts.State.Stale || it is com.khayali.pulao.ai.data.Artifacts.State.Updating }) add("market")
        if (com.khayali.pulao.ai.data.KnowledgePack.get(context).ready()) add("knowledge")
    }

    /** A turn ended (answer, error or stop): settle the phase and bring the pills back. */
    private fun finishTurn(ev: ChatFlow.Ev = ChatFlow.Ev.Finished) {
        if (voiceAwaiting) { voiceAwaiting = false; val t = lastDoneText; post { live?.answer(t.ifBlank { "Done — it's in the chat." }) } }
        dispatch(ev)
        if (!mm.isReady()) dispatch(ChatFlow.Ev.ModelMissing)
    }

    /** Called by the host when the screen comes back: a Kite login may have lapsed while away. */
    fun onResume() {
        live?.resume()
        refreshTickers()
        if (refreshedAt > 0 && System.currentTimeMillis() - refreshedAt > 60_000) autoRefresh()
        kite.checkFresh()
        renderWebPill()
    }

    // ---------------------------------------------------- Kite (MCP) pill

    private fun onKiteState(s: KiteMcpClient.State) {
        renderKitePill(s)
        val link = kite.link
        val changed = lastKiteLink != null && lastKiteLink != link
        lastKiteLink = link
        if (changed) session.invalidate()                    // tools and prompt differ; the thread itself is kept
        if (s is KiteMcpClient.State.Connected) {
            preloadKite()
            val p = pending
            if (p != null && ChatFlow.route(p, env()) !is ChatFlow.Action.EnableWeb) { pending = null; post { submit(p) } }
            else if (messageCount == 0) { shownState = null; onState(mm.state.value, AiEngine.state.value) }
        } else if (changed && messageCount == 0) { shownState = null; onState(mm.state.value, AiEngine.state.value) }
    }

    private fun stylePill(pill: TextView, label: String, color: Int, onTap: () -> Unit) {
        pill.text = label; pill.setTextColor(color)
        pill.background = k.press(k.rounded(Pal.alpha(color, 0.12f), 999f, Pal.alpha(color, 0.5f)), 999f)
        pill.setOnClickListener { onTap() }
    }

    private fun renderKitePill(s: KiteMcpClient.State) {
        val (label, color) = when (s) {
            is KiteMcpClient.State.Connected -> "●  Kite connected" + (s.name?.let { " · $it" } ?: "") to Pal.GREEN
            KiteMcpClient.State.Connecting -> "Connecting to Kite…" to Pal.MUTED
            is KiteMcpClient.State.WaitingForBrowser -> "Finish login on Zerodha's page · tap to reopen" to Pal.SAFFRON
            is KiteMcpClient.State.Failed -> "Kite: ${s.message.take(48)} · tap to retry" to Pal.RED
            KiteMcpClient.State.Expired -> "↻  Kite login expired · reconnect" to Pal.SAFFRON
            KiteMcpClient.State.Off -> "＋  Connect Kite (read-only)" to Pal.BLUE
        }
        stylePill(kitePill, label, color) { onKitePill(kite.state.value) }
        kitePill.alpha = 0f; kitePill.animate().alpha(1f).setDuration(250).start()
    }

    private fun renderWebPill() {
        if (prefs.webOn) stylePill(webPill, "🌐  Web on", Pal.GREEN) { onWebPill() }
        else stylePill(webPill, "🌐  Web off", Pal.MUTED) { onWebPill() }
    }

    private fun dialog() = android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)

    /**
     * Web access is asked for once, in the chat itself — like any permission. After "Allow" it is
     * never asked again; the switch lives in Settings.
     */
    private fun onWebPill() {
        if (prefs.webOn) openSettings() else askWeb(null)
    }

    private fun openSettings() {
        runCatching { context.startActivity(android.content.Intent(context, com.khayali.pulao.SettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun askWeb(question: String?) {
        if (phase.busy) return
        startThread(); setPills(emptyList())
        if (question != null) addUser(question)
        newBot().done((if (question != null) "**$question** needs web access.\n\n" else "") + WEB_EXPLAINER, null, null, chips = listOf(
            "Allow web access" to { setWeb(true) },
            "Not now" to { pending = null; setPills(lastPills, remember = false) }))
        scrollToEnd()
    }

    private fun setWeb(on: Boolean) {
        prefs.webOn = on
        session.invalidate()
        renderWebPill()
        val p = pending
        if (on && p != null) { pending = null; submit(p) }
        else if (on) say("", "Web access is on. You can switch it off any time in **Settings → AI chat**.", listOf("Open Settings" to { openSettings() }))
        if (messageCount == 0) { shownState = null; onState(mm.state.value, AiEngine.state.value) }
    }

    private fun onKitePill(s: KiteMcpClient.State) {
        when (s) {
            is KiteMcpClient.State.Connected -> dialog()
                .setTitle("Kite connected (read-only)")
                .setMessage("The chat can read your holdings, positions, margins and orders to daydream on real numbers. It can never place, modify or cancel orders.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Disconnect") { _, _ -> kite.disconnect(); kitePortfolio = null; kiteRows = emptyList() }.show()
            is KiteMcpClient.State.WaitingForBrowser -> openUrl(s.url)
            KiteMcpClient.State.Connecting -> Unit
            KiteMcpClient.State.Expired -> scope.launch { kite.connect(::openUrl) }      // already agreed once: straight to Zerodha's page
            else -> dialog()
                .setTitle("Connect Zerodha Kite?")
                .setMessage("Uses Zerodha's official Kite MCP (mcp.kite.trade). You sign in on Zerodha's own page in your browser — if you're already logged in to Kite there, it's one tap. The app never sees your password.\n\n• Read-only: holdings, positions, margins, orders\n• Every order/GTT tool is blocked in code\n• Data comes from Zerodha to this phone and is used only by the on-device AI\n• Expires daily; reconnect is one tap")
                .setNegativeButton("Not now") { _, _ -> pending = null }
                .setPositiveButton("Connect") { _, _ -> scope.launch { kite.connect(::openUrl) } }.show()
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** The tapped stock changed (overlay): a fresh, unsaved thread. */
    fun onStockChanged() { resetThread(); store?.clear() }

    private fun resetThread() {
        turnJob?.cancel(); pillJobCancel()
        session.reset(); messageCount = 0; shownState = null; pending = null
        lastCall = null; lastShown = null; lastQuestion = null; awaitingFeedback = emptySet()
        transcript.clear()
        dispatch(ChatFlow.Ev.Reset)
        onState(mm.state.value, AiEngine.state.value)
    }

    /** A new chat; the one before stays in the drawer. */
    fun newChat() {
        val had = transcript.isNotEmpty()
        resetThread()
        library?.let { l -> if (had || l.meta(l.current) != null) l.startNew(); store = l.store() }
        setPills(emptyList(), remember = false)
    }

    /** Opens a saved chat from the drawer, exactly as it was. */
    fun openChat(id: String) {
        val l = library ?: return
        if (id == l.current && transcript.isNotEmpty()) return
        resetThread()
        l.current = id; store = l.store(id)
        restore(); scrollToEnd()
    }

    /** Deletes a saved chat (the drawer asks first); deleting the open one starts a new chat. */
    fun deleteChat(id: String) {
        val l = library ?: return
        val open = id == l.current
        l.delete(id)
        if (open) { resetThread(); l.startNew(); store = l.store() }
    }

    /** The open chat's id, for the drawer's highlight. */
    val currentChat get() = library?.current

    fun release() { live?.end(); turnJob?.cancel(); session.close(); glowAnim?.cancel(); scope.cancel() }

    // ================================================================ states

    private fun onState(m: ModelManager.State, e: AiEngine.State) {
        if (m !is ModelManager.State.Ready) {
            dispatch(ChatFlow.Ev.ModelMissing)
            // Commands work without the model, so the input stays usable; an existing thread is never wiped.
            if (messageCount == 0) showModelCard(m) else updateModelCard(m)
            return
        }
        dispatch(ChatFlow.Ev.ModelReady)
        if (messageCount > 0) return
        if (e is AiEngine.State.Failed) showOnce("failed") { greeting(error = e.message) }
        else showOnce("greeting") { greeting(null) }
    }

    private fun showOnce(key: String, build: () -> Unit) {
        if (shownState == key) return
        shownState = key
        list.removeAllViews(); build()
    }

    /** First message of a thread: clear the greeting / model card. */
    private fun startThread() {
        if (messageCount == 0) { list.removeAllViews(); list.gravity = Gravity.NO_GRAVITY; shownState = "chat" }
        messageCount++
    }

    private fun newBot() = BotMessage().also { it.why = turnWhy; list.addView(it.root, k.lp(MATCH_PARENT).apply { topMargin = k.dp(14) }); it.appear() }

    /** How the next request arrived when it wasn't typed ("pill:…", "action", "ticker:…", "starter"); see [Methods.why]. */
    private var via: String? = null
    /** Why the current turn's replies exist, shown in each reply's "How this was made" panel. */
    private var turnWhy: String? = null

    /** Bring back the saved thread, with its cards, exactly as it was. */
    private fun restore() {
        val saved = store?.load().orEmpty()
        if (saved.isEmpty()) return
        list.removeAllViews()
        list.gravity = Gravity.NO_GRAVITY; shownState = "chat"
        for (e in saved) {
            messageCount++
            addUser(e.question, animate = false)
            val bot = BotMessage().also { list.addView(it.root, k.lp(MATCH_PARENT).apply { topMargin = k.dp(14) }) }
            val ev = if (e.tool != null && e.json != null) ToolEvent(e.tool, "", true, e.json) else null
            ev?.let { bot.card(it) }
            bot.done(e.answer, null, null, question = e.question, restored = true)
            session.record(e.question, e.answer, seenByModel = true)
            if (ev != null) { lastShown = ev; if (ev.name == "kite_account") rememberKite(if (e.json!!.contains("\"holdings\"")) "holdings" else "", e.json) }
            lastQuestion = e.question
        }
        transcript += saved
        post { showFollowUps(useModel = false); scroll.post { scroll.scrollTo(0, list.bottom) } }
    }

    /** One finished exchange: tell the session (so the model knows) and save it. */
    private fun remember(question: String, answer: String, event: ToolEvent?, seenByModel: Boolean) {
        session.record(question, answer, seenByModel)
        transcript += ChatStore.Entry(question, answer, event?.takeIf { it.ok }?.name, event?.takeIf { it.ok }?.resultJson)
        while (transcript.size > ChatStore.MAX) transcript.removeAt(0)
        store?.save(transcript)
        library?.let { l -> transcript.firstOrNull()?.let { l.touch(l.current, it.question, transcript.size) } }
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
            null -> when {
                kite.connected -> "Ask about your portfolio, any stock, or a what-if."
                kite.link == ChatFlow.Kite.EXPIRED -> "Your Kite login expired overnight — reconnect to use your real portfolio."
                else -> "Ask any what-if — or connect Kite to use your real portfolio."
            }
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
        // Task-driven starters: tap one and the chat plans and runs it
        if (s == null) {
            val tasks = k.column().apply { setPadding(0, k.dp(18), 0, 0) }
            (listOfNotNull(if (!setupDone()) Asks.Starter("⚡", "Set up everything in one tap — AI, market data, web", "/setup") else null) +
                Asks.starters(kite.knownSymbols.map { it.uppercase() }, watch.symbols.map { it.uppercase() }, kite.connected)).take(6).forEach { t ->
                tasks.addView(k.row().apply {
                    setPadding(k.dp(14), k.dp(11), k.dp(14), k.dp(11))
                    background = k.press(null, 12f)
                    addView(k.text(t.icon, 18f, Pal.TEXT), k.lp(k.dp(34)))
                    addView(k.text(t.text, 14.5f, Pal.TEXT).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, k.lp(0, WRAP_CONTENT, 1f))
                    setOnClickListener { via = "starter"; runAction(t.run) }
                }, k.lp(MATCH_PARENT))
            }
            list.addView(tasks, k.lp(MATCH_PARENT))
        }
        val mem = memoryStore.playbook.bullets.size
        list.addView(k.text("🔒 ${ModelSpec.DISPLAY_NAME} · runs on your phone" + (if (memoryStore.enabled && mem > 0) " · 🧠 $mem remembered" else "") + "\n${Persona.DISCLAIMER}", 11f, Pal.DIM).apply {
            gravity = Gravity.CENTER; setPadding(k.dp(8), k.dp(20), k.dp(8), 0); setLineSpacing(0f, 1.3f)
        }, k.lp(MATCH_PARENT))
        for ((n, v) in listOf(hi, list.getChildAt(list.childCount - 2), list.getChildAt(list.childCount - 1)).withIndex()) {
            v.alpha = 0f; v.translationY = k.dpf(12)
            v.animate().alpha(1f).translationY(0f).setStartDelay(150L + n * 90L).setDuration(420).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        setPills(if (s == null) emptyList() else suggestions(s).map { (icon, label) -> "$icon  $label" })
    }

    private fun choosePersona(p: Persona) {
        val apply = { prefs.personaName = p.name; prefs.disclaimerSeen = true; session.invalidate(); shownState = null; onState(mm.state.value, AiEngine.state.value) }
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

    // ------------------------------------------------------------ model card

    private fun showModelCard(m: ModelManager.State) {
        val key = "model:" + m::class.simpleName
        if (shownState != key) {
            shownState = key
            list.removeAllViews(); list.gravity = Gravity.NO_GRAVITY
            list.addView(k.space(if (compact) 8 else 32))
            list.addView(FlowingGradientText(context).apply { k.style(this, "Meet your pulao AI", if (compact) 24f else 30f, Pal.TEXT, 600) })
            list.addView(k.text("A small Google model that runs entirely on your phone. It uses the app's own calculators, so the numbers are exact — and your questions are answered on the phone, not by a cloud AI.",
                14f, Pal.MUTED).apply { setLineSpacing(0f, 1.35f); setPadding(0, k.dp(8), 0, k.dp(18)) })
            (modelCard.parent as? android.view.ViewGroup)?.removeView(modelCard)
            list.addView(modelCard, k.lp(MATCH_PARENT))
            list.addView(k.text("No model yet? Commands still work: type / for holdings, charts, FD comparison, goals and tax.", 12f, Pal.DIM).apply {
                setPadding(k.dp(4), k.dp(14), k.dp(4), 0); setLineSpacing(0f, 1.3f) })
            setPills(startersNow().filter { it.startsWith("/") || it.startsWith("Connect") })
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
                mcSub.text = "One-time download from huggingface.co (about ${DownloadText.bytes(ModelSpec.SIZE_BYTES)}). After that, chat works offline."
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
            setOnFocusChangeListener { _, f -> if (!phase.busy) glow.glow = if (f) 0.35f else 0f }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(e: android.text.Editable?) { onTyping(e?.toString().orEmpty()); updateInputButtons() }
            })
        }
        send.apply {
            gravity = Gravity.CENTER; textSize = 18f; setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.BLUE) }
            setOnClickListener { if (phase.canStop) stopGenerating() else submit(input.text.toString()) }
        }
        setSendIcon(false)
        inputBar.setPadding(k.dp(6), k.dp(8), k.dp(8), k.dp(8))
        // + : the tools — web, Kite, commands, setup
        inputBar.addView(Icons.button(context, k, "add", Pal.TEXT, "Tools", 22, 42) { tools() }, k.lp(k.dp(42), k.dp(42)))
        inputBar.addView(input, k.lp(0, WRAP_CONTENT, 1f))
        // dictation: speak into the box (on the phone), then send — and Live: hands-free voice
        inputBar.addView(micBtn, k.lp(k.dp(42), k.dp(42)))
        inputBar.addView(liveBtn, k.lp(k.dp(42), k.dp(42)).apply { marginStart = k.dp(2) })
        inputBar.addView(send, k.lp(k.dp(44), k.dp(44)).apply { marginStart = k.dp(4) })
        updateInputButtons()
        val wrap = FrameLayout(context).apply { setPadding(k.dp(12), k.dp(2), k.dp(12), k.dp(12)) }
        wrap.addView(inputBar, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return wrap
    }


    /** Empty box: mic + Live. Something typed (or a reply running): send / stop takes Live's place. */
    private fun updateInputButtons() {
        val typed = input.text.isNotBlank()
        val showSend = typed || phase.busy
        send.visibility = if (showSend) VISIBLE else GONE
        liveBtn.visibility = if (showSend) GONE else VISIBLE
    }

    /** + : the tools, in one sheet, each with what it does. */
    private fun tools() {
        Sheet.show(context, k, "Tools", listOf(
            Sheet.Item("🌐", "Web", "News, filings, prices and search. Only the lookup leaves your phone.", if (prefs.webOn) "On" else "Off") { onWebPill() },
            Sheet.Item("🔗", "Kite", "Your holdings and positions, read-only.", if (kite.connected) "Connected" else "Connect") { onKitePill(kite.state.value) },
            Sheet.Item("🎙", "Talk hands-free", "Speak; answers come in the chat and are read aloud.") { startLive() },
            Sheet.Item("🗣", "Voice", voiceLine(), NeuralVoice.voice(context)?.name?.takeIf { com.khayali.pulao.ai.data.VoicePack.ready(context) } ?: if (com.khayali.pulao.ai.data.VoicePack.ready(context)) "Phone" else null) { voiceSheet() },
            Sheet.Item("/", "Commands", "${com.khayali.pulao.ai.term.Terminal.FEATURES.size}+ exact tools: pulse, why, find, alerts, sectors…") { showHelp() },
            Sheet.Item("@", "Ask about a holding", "Type @ and pick one of your stocks.") { input.append("@"); input.requestFocus() },
            Sheet.Item("🔔", "Price alerts", "Checked on the phone after each close.") { submit("/alert") },
            Sheet.Item("✨", "Setup", "Model, data, Kite and web — ready in one go.") { showSetup() },
        ))
    }

    // ---------------------------------------------------------------- voice

    private fun voiceLine(): String {
        val ctx = context
        com.khayali.pulao.ai.data.VoicePack.progress(ctx)?.let { (d, t) -> return "Downloading the natural voice… ${d * 100 / maxOf(1, t)}%" }
        if (com.khayali.pulao.ai.data.VoicePack.ready(ctx)) return "Natural voice on the phone · pick who reads your answers"
        return "Get a natural, human-sounding voice · ${DownloadText.bytes(com.khayali.pulao.ai.data.VoicePack.BYTES)}, once"
    }

    /** Pick the voice that reads answers; the natural voice is downloaded once (asked first) and runs on the phone. */
    private fun voiceSheet() {
        val ctx = context
        val pack = com.khayali.pulao.ai.data.VoicePack
        if (!pack.ready(ctx)) {
            pack.progress(ctx)?.let { (d, t) -> toast("Downloading… ${d * 100 / maxOf(1, t)}% — it's in your notifications"); return }
            android.app.AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Get the natural voice?")
                .setMessage("Kokoro, an open-source voice (Apache-2.0), reads answers in a natural, human-sounding way. It's ${DownloadText.bytes(pack.BYTES)}, downloaded once from Hugging Face — " +
                    "Wi-Fi is best — and runs entirely on this phone. Nothing you hear or say is sent anywhere.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Download") { _, _ -> pack.install(ctx); toast("Downloading the natural voice — progress is in your notifications") }.show()
            return
        }
        val chosen = NeuralVoice.choice(ctx)
        val items = NeuralVoice.VOICES.map { v ->
            Sheet.Item(if (v.key == chosen) "✓" else "🗣", v.name, v.desc) { NeuralVoice.choose(ctx, v.key); sample(v.name) }
        } + listOf(
            Sheet.Item(if (chosen == "system") "✓" else "📱", "Phone's own voice", "Android's offline voice") { NeuralVoice.choose(ctx, "system"); sample(null) },
            Sheet.Item("🗑", "Remove the natural voice", "Frees ${DownloadText.bytes(pack.BYTES)}; you can get it again any time") {
                android.app.AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert).setMessage("Remove the natural voice from this phone?")
                    .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ -> NeuralVoice.stop(); pack.delete(ctx); toast("Removed") }.show()
            },
        )
        Sheet.show(ctx, k, "Who reads your answers", items)
    }

    private fun sample(name: String?) {
        Speaker.speak(context, (name?.let { "Hi, I'm $it. " } ?: "") + "The Nifty 50 rose 0.38% today, and PSU banks led. I'm reading this on your phone.", owner = this) {}
    }

    // ---------------------------------------------------------------- dictation

    private var dictation: Dictation? = null
    private var dictateAfterPermission = false

    /** Speak into the box: Android's on-device recognizer only; the words land in the input for you to check and send. */
    private fun dictate() {
        dictation?.let { it.stop(); return }
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            dictateAfterPermission = true
            (context as? android.app.Activity)?.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MIC_REQUEST); return
        }
        if (!LiveVoice.available(context)) { toast("This phone has no on-device speech recognition"); return }
        val before = input.text.toString().trimEnd().let { if (it.isEmpty()) "" else "$it " }
        Icons.tint(micBtn, Pal.RED); micBtn.background = k.rounded(Pal.alpha(Pal.RED, 0.14f), 999f)
        dictation = Dictation(context,
            onText = { t -> input.setText(before + t); input.setSelection(input.text.length) },
            onLevel = { l -> micBtn.alpha = 0.7f + l * 0.3f },
            onEnd = { problem -> dictation = null; micBtn.alpha = 1f; Icons.tint(micBtn, Pal.TEXT); micBtn.background = k.press(null, 999f); problem?.let { toast(it) } },
        ).also { it.start() }
    }

    private fun setSendIcon(busy: Boolean) {
        send.text = if (busy) "■" else "↑"
        send.contentDescription = if (busy) "Stop" else "Send"
        send.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80).withEndAction {
            send.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
    }

    private fun setWorking(on: Boolean) {
        setSendIcon(on); updateInputButtons()
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
            val p = k.chip(Labels.of(s)) { if (onTap == null) via = "pill:" + Labels.of(s); (onTap ?: { x -> submit(x.replace(Regex("^\\S+\\s{2}"), "")) })(s) }.apply {
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
            Commands.isCommand(t) && !t.contains(' ') -> {
                setPills(emptyList(), remember = false)
                // a bare "/" lists every command in sections; anything typed lists every match, best first
                if (t.trim() == "/") showSuggestions(emptyList(), sections = Commands.sections()) else showSuggestions(Commands.rank(t))
            }
            Commands.isCommand(t) -> {
                // after the command name: ask for the next parameter, with choices to tap; run when the last one is picked
                val name = t.trimStart().removePrefix("/").substringBefore(' ').lowercase()
                val cmd = (Commands.ALL + Commands.TERMINAL).firstOrNull { it.name == name }
                val step = Params.step(t, tickerChoices())
                showSuggestions(emptyList(), cmd, prompt = step?.takeIf { !it.complete }?.ask)
                if (step == null || step.complete) { setPills(emptyList(), remember = false); return }
                val options = (if (step.canRun) listOf(Params.RUN) else emptyList()) + step.choices
                setPills(options, remember = false) { pick ->
                    if (pick == Params.RUN) { val c = input.text.toString().trim(); input.setText(""); submit(c); return@setPills }
                    val next = Params.pick(input.text.toString(), pick)
                    if (Params.step(next, tickerChoices())?.complete == true) { input.setText(""); submit(next.trim()) }
                    else { input.setText(next); input.setSelection(input.text.length) }
                }
            }
            mention != null -> {
                val syms = Commands.matching(mention, kite.knownSymbols)
                if (syms.isEmpty()) setPills(listOf(if (kite.connected) "No holding matches @$mention" else "Connect Kite to @mention your stocks"), remember = false) { }
                else setPills(syms.map { "@$it" }, remember = false) { pill ->
                    input.setText(Commands.completeMention(input.text.toString(), pill.removePrefix("@"))); input.setSelection(input.text.length)
                }
            }
            else -> {
                showSuggestions(emptyList())
                // plain words: suggest the next questions worth asking, each running one exact command
                val asks = if (t.isBlank()) emptyList() else Asks.suggest(t, readyArtifacts() + listOfNotNull(if (kite.connected) "kite" else null, if (prefs.webOn) "web" else null))
                if (asks.isEmpty()) setPills(lastPills, remember = false)
                else setPills(asks.map { it.text }, remember = false) { pick -> asks.firstOrNull { it.text == pick }?.let { input.setText(""); submit(it.command) } }
            }
        }
        if (mention != null || !Commands.isCommand(t)) showSuggestions(emptyList())
    }


    /** Stocks to offer when a command asks for one: holdings first, then the watch list. */
    private fun tickerChoices(): List<String> = (kiteHoldings().map { it.name } + watch.symbols + kite.knownSymbols).map { it.uppercase() }.distinct()

    private fun showSuggestions(cmds: List<Commands.Cmd>, usageOf: Commands.Cmd? = null, sections: List<Pair<String, List<Commands.Cmd>>> = emptyList(), prompt: String? = null) {
        suggestBox.removeAllViews(); suggestScroll.removeAllViews()
        val card = k.column().apply { setPadding(0, k.dp(4), 0, k.dp(4)) }
        fun row(c: Commands.Cmd) = k.row().apply {
            setPadding(k.dp(14), k.dp(9), k.dp(14), k.dp(9))
            background = k.press(null, 0f)
            addView(k.text("/" + c.name, 14f, Pal.TEXT, 600).apply { typeface = android.graphics.Typeface.MONOSPACE }, k.lp(k.dp(132)))
            addView(k.text(c.help, 12.5f, Pal.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, k.lp(0, WRAP_CONTENT, 1f))
            setOnClickListener {
                // nothing to fill in: run it now; otherwise fill the name and ask for each parameter in turn
                if (Params.slots(c.name, tickerChoices()).isEmpty()) { input.setText(""); submit("/${c.name}") }
                else { input.setText("/${c.name} "); input.setSelection(input.text.length) }
            }
        }
        for ((title, list) in sections) {
            card.addView(k.text(title.uppercase() + "  ·  " + list.size, 11f, Pal.BLUE, 700).apply { letterSpacing = 0.06f; setPadding(k.dp(14), k.dp(10), k.dp(14), k.dp(4)) })
            list.forEach { card.addView(row(it), k.lp(MATCH_PARENT)) }
        }
        if (usageOf != null) {
            card.addView(k.column().apply {
                setPadding(k.dp(14), k.dp(8), k.dp(14), k.dp(8))
                addView(k.text(usageOf.usage, 13.5f, Pal.BLUE, 600).apply { typeface = android.graphics.Typeface.MONOSPACE })
                addView(k.text(usageOf.help, 12f, Pal.MUTED).apply { setPadding(0, k.dp(2), 0, 0); maxLines = 2 })
                if (!prompt.isNullOrBlank()) addView(k.text("Next: $prompt ↓", 13f, Pal.TEXT, 600).apply { setPadding(0, k.dp(6), 0, 0) })
            })
        }
        cmds.forEachIndexed { i, c ->
            if (i > 0) card.addView(View(context).apply { setBackgroundColor(Pal.alpha(Pal.LINE, 0.5f)) }, k.lp(MATCH_PARENT, 1))
            card.addView(row(c), k.lp(MATCH_PARENT))
        }
        if (cmds.isEmpty() && usageOf == null && sections.isEmpty()) { suggestBox.visibility = GONE; return }
        if (cmds.size > 1) card.addView(k.text("${cmds.size} matching commands", 11f, Pal.DIM).apply { setPadding(k.dp(14), k.dp(6), k.dp(14), k.dp(4)) })
        suggestScroll.addView(card, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        suggestScroll.background = k.rounded(Pal.SURFACE, 14f, Pal.LINE)
        suggestBox.addView(suggestScroll, k.lp(MATCH_PARENT))
        suggestScroll.scrollTo(0, 0)
        suggestBox.visibility = VISIBLE
    }

    fun showHelp() {
        startThread()
        val bot = newBot()
        val terminal = com.khayali.pulao.ai.term.Group.entries.mapNotNull { g ->
            com.khayali.pulao.ai.term.Terminal.FEATURES.filter { it.group == g }.takeIf { it.isNotEmpty() }
                ?.let { fs -> "**${g.title}**\n" + fs.joinToString("\n") { "- **${it.usage}** — ${it.help}" } }
        }.joinToString("\n\n")
        bot.done("**Commands** (these work even without the AI model)\n" + Commands.ALL.joinToString("\n") { "- **${it.usage}** — ${it.help}" } +
            (if (terminal.isNotEmpty()) "\n\n$terminal" else "") +
            "\n\n**@mentions** — type **@** to pick one of your Kite holdings, e.g. *What if @INFY goes up 20%?*", null, null)
        scrollToEnd()
    }

    private fun startersNow() = ContextPills.starters(
        kiteHoldings().map { ContextPills.H(it.name, it.current, it.pnlPct) }, persona, kite.connected, prefs.webOn)

    private fun kiteHoldings(): List<Stock.Holding> = kiteRows.mapNotNull { r ->
        runCatching { val q = r["qty"].asDouble; val a = r["avg_price"].asDouble; Stock.Holding(r["symbol"].asString, q, a, q * a, r["ltp"].asDouble) }.getOrNull()
    }.sortedByDescending { it.current }

    /** Load real holdings in the background so tools can answer "my biggest holding" straight away. */
    private fun preloadKite() {
        if (kite.connected && kiteRows.isEmpty()) scope.launch {
            rememberKite("holdings", kite.account("holdings"))
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

    /** What one app-only turn produced. */
    private class Outcome(val text: String, val event: ToolEvent? = null, val chips: List<Pair<String, () -> Unit>> = emptyList())


    /** The single entry point for everything the user can send. */
    private fun submit(raw: String) {
        if (phase.busy) return
        val text = raw.trim()
        if (live != null && text.isNotEmpty()) {
            // voice is open: whatever was asked (said, or a pill tapped) is answered in the chat and read aloud
            voiceAwaiting = true; lastDoneText = ""; live?.thinking(text)
            // things that answer on the spot (help, a download offer) don't run a turn: speak whatever was shown
            post { if (voiceAwaiting && !phase.busy) { voiceAwaiting = false; live?.answer(lastDoneText.ifBlank { "I've put that in the chat for you." }) } }
        }
        showSuggestions(emptyList())
        WatchStore.symbolsIn(text).forEach { watch.add(it) }
        val routed = ChatFlow.route(text, env())
        turnWhy = Methods.why(text, routed, via); via = null
        when (val a = routed) {
            ChatFlow.Action.Ignore -> return
            ChatFlow.Action.Clear -> { input.setText(""); newChat() }
            ChatFlow.Action.Setup -> { input.setText(""); showSetup() }
            ChatFlow.Action.Help -> { input.setText(""); showHelp() }
            is ChatFlow.Action.Unknown -> { input.setText(""); showHelp() }
            is ChatFlow.Action.ConnectKite -> {
                if (a.pending == null) { onKitePill(kite.state.value); return }
                input.setText(""); pending = a.pending
                say(text, if (a.expired) KiteMcpClient.EXPIRED_MESSAGE + " I'll run **${a.pending}** as soon as you're back."
                    else "**${a.pending}** reads your Zerodha account, so Kite needs to be connected first. It's read-only and takes one tap if you're signed in to Kite in your browser — I'll run it as soon as you're connected.",
                    listOf((if (a.expired) "Reconnect Kite" else "Connect Kite") to { onKitePill(kite.state.value) }))
            }
            is ChatFlow.Action.EnableWeb -> {
                input.setText(""); pending = a.pending
                askWeb(text)
            }
            is ChatFlow.Action.KiteRead -> { input.setText(""); runKite(a.section, text) }
            is ChatFlow.Action.Chart -> { input.setText(""); runChart(a.symbol, a.period, a.source, text) }
            is ChatFlow.Action.Tool -> { input.setText(""); runTool(a.name, a.args, text) }
            is ChatFlow.Action.Web -> { input.setText(""); runWeb(a.kind, a.query, text) }
            is ChatFlow.Action.Remember -> {
                input.setText("")
                val added = if (Ace.acceptable(a.text)) { memoryStore.enabled = true; memoryStore.learn(listOf(Ace.Delta(Ace.Section.FACT, a.text))); true } else false
                say(text, if (added) "Saved to memory: *${a.text}*. It stays on this phone — see or delete it with **/memory**."
                    else "I won't store that — it looks like a secret, an ID number or a buy/sell instruction. Memory is for goals, horizon and preferences.",
                    listOf("Open memory" to { openMemory() }))
            }
            ChatFlow.Action.ShowMemory -> { input.setText(""); showMemory(text) }
            ChatFlow.Action.Compact -> { input.setText(""); compactNow(text) }
            is ChatFlow.Action.Term -> { input.setText(""); runTerm(a.id, a.arg, text)
                if (a.id == "alert" && a.arg.isNotBlank()) askNotifications()
                if (a.id == "data") { if (a.arg.trim().startsWith("knowledge", true)) watchKnowledge() else watchDownload() } }
            is ChatFlow.Action.NeedArtifact -> { input.setText(""); offerArtifact(a.artifact, a.pending) }
            is ChatFlow.Action.Plan -> { input.setText(""); runPlan(a.plan, text) }
            ChatFlow.Action.ForgetAll -> {
                input.setText("")
                dialog().setTitle("Erase memory?").setMessage("Deletes everything the chat has remembered about you and the lessons it learned. This can't be undone.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Erase") { _, _ -> memoryStore.clear(); session.invalidate(); say("/forget", "Memory erased.") }.show()
            }
            ChatFlow.Action.NeedModel -> {
                if (messageCount == 0) { performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS); scroll.smoothScrollTo(0, 0); return }
                input.setText("")
                say(text, "Free-form questions need the on-device AI model, which isn't downloaded. Commands work without it — type **/** to see them.",
                    listOf("Download the model" to { newChat() }, "Show commands" to { showHelp() }))
            }
            is ChatFlow.Action.Model -> sendToModel(display = text, prompt = a.prompt)
        }
    }

    /** /memory — what the chat remembers, shown in the thread itself; the full editor is one tap away. */
    private fun showMemory(question: String) {
        val notes = memoryStore.playbook.bullets
        val body = if (notes.isEmpty()) "Nothing remembered yet. Tell me something about your goals, or add a note with **/remember**, for example */remember I invest for 10+ years*."
            else Ace.Section.entries.mapNotNull { sec ->
                notes.filter { it.section == sec }.sortedByDescending { it.score }.takeIf { it.isNotEmpty() }
                    ?.let { rows -> "**${sec.title}**\n" + rows.joinToString("\n") { "- ${it.text}" } }
            }.joinToString("\n\n") + (if (!memoryStore.enabled) "\n\n> Memory is switched off, so these notes are not being used." else "") +
                "\n\nAdd a note with **/remember …**. Notes stay on this phone."
        say(question, body, listOf("Edit memory" to { openMemory() }))
    }

    /** /compact, and automatically when the model's context is nearly full. */
    private fun compactNow(question: String?) {
        val summary = session.compact()
        if (question == null) { if (summary != null) notice("Earlier messages were summarised to keep the model fast. Nothing was deleted from this screen."); return }
        say(question, if (summary == null) "Nothing to compact yet — the chat is still short."
            else "Compacted. The model now carries this summary instead of the full thread (your messages stay on screen):\n\n$summary")
    }

    /** A quiet centred line in the thread, for things the app did on its own. */
    private fun notice(text: String) {
        list.addView(k.text(text, 11.5f, Pal.DIM).apply { gravity = Gravity.CENTER; setPadding(k.dp(24), k.dp(14), k.dp(24), 0); setLineSpacing(0f, 1.3f) }, k.lp(MATCH_PARENT))
    }

    private fun openMemory() {
        runCatching { context.startActivity(android.content.Intent(context, MemoryActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** An instant app reply (no model, no network). */
    private fun say(question: String, answer: String, chips: List<Pair<String, () -> Unit>> = emptyList()) {
        startThread(); setPills(emptyList()); if (question.isNotBlank()) addUser(question)
        newBot().done(answer, null, null, chips = chips)
        scrollToEnd()
    }

    /**
     * Runs app-only work (Kite read, web lookup, calculator) as a turn: FETCHING while it runs,
     * a card + exact summary when it lands, recovery chips when it fails, Stop to cancel.
     */
    private fun appTurn(label: String, status: String, work: suspend () -> Outcome) {
        startThread(); setPills(emptyList()); addUser(label)
        val bot = newBot(); bot.status(status); scrollToEnd()
        pillJobCancel()
        dispatch(ChatFlow.Ev.StartApp)
        turnJob = scope.launch {
            try {
                val out = try { work() } catch (c: CancellationException) { throw c }
                    catch (t: Throwable) { Outcome("⚠️ " + Net.friendly(t, "the service"), chips = listOf("Try again" to { submit(label) })) }
                out.event?.let { e ->
                    bot.tool(e); lastShown = e
                    lastCall = e.takeIf { it.ok && FollowUps.from(it.name, it.argsJson).isNotEmpty() }
                }
                bot.done(out.text, null, null, question = label, chips = out.chips)
                lastQuestion = label
                if (out.event?.ok == true) remember(label, out.text, out.event, seenByModel = false)
                finishTurn()
                showFollowUps(useModel = false); scrollToEnd()
            } catch (c: CancellationException) {
                withContext(NonCancellable) { bot.done("(stopped)", null, null); finishTurn(ChatFlow.Ev.Stopped); setPills(lastPills, remember = false) }
            }
        }
    }

    private fun failure(json: String, label: String): Outcome {
        val message = ToolJson.error(json) ?: "That didn't work."
        val expired = json.contains("\"expired\":true")
        if (expired) pending = label
        val fixes = ToolJson.actions(json).map { cmd -> Labels.of(cmd) to { submit(cmd) } }
        return Outcome("⚠️ $message", chips = when { expired -> listOf("Reconnect Kite" to { onKitePill(kite.state.value) }); fixes.isNotEmpty() -> fixes + ("Try again" to { submit(label) }); else -> listOf("Try again" to { submit(label) }) })
    }

    /** /holdings, /positions… — read Kite directly and show the card; no model needed. */
    private fun runKite(section: String, label: String) = appTurn(label, "Reading your Kite $section…") { kiteOutcome(section, label) }

    private suspend fun kiteOutcome(section: String, label: String): Outcome {
        val t0 = System.nanoTime()
        val json = kite.account(section)
        if (!ToolJson.ok(json)) return failure(json, label)
        rememberKite(section, json)
        return Outcome(ToolJson.summary(json) ?: "Here it is.",
            ToolEvent("kite_account", "Read Kite $section", true, json, """{"section":"$section"}""", (System.nanoTime() - t0) / 1_000_000))
    }

    private fun runChart(symbol: String, period: String?, source: ChatFlow.Source, label: String) = appTurn(label, "Charting $symbol…") { chartOutcome(symbol, period, source, label) }

    private suspend fun chartOutcome(symbol: String, period: String?, source: ChatFlow.Source, label: String): Outcome {
        val t0 = System.nanoTime()
        val args = """{"symbol":"$symbol","period":"${period ?: "6m"}"}"""
        var json = if (source == ChatFlow.Source.KITE) kite.history(symbol, period) else web.quote(symbol, period)
        var tool = if (source == ChatFlow.Source.KITE) "kite_chart" else "web_quote"
        // Kite can't chart everything (indices, unlisted symbols): try the web feed when it's allowed.
        if (!ToolJson.ok(json) && source == ChatFlow.Source.KITE && prefs.webOn && !json.contains("\"expired\":true")) {
            web.quote(symbol, period).takeIf { ToolJson.ok(it) }?.let { json = it; tool = "web_quote" }
        }
        if (!ToolJson.ok(json)) return failure(json, label)
        return Outcome(ToolJson.summary(json).orEmpty(), ToolEvent(tool, "Charted $symbol", true, json, args, (System.nanoTime() - t0) / 1_000_000))
    }

    /** /fd, /goal, /grow, /move, /tax — the calculator itself, on the real portfolio when Kite is connected. */
    private fun runTool(name: String, args: String, label: String) = appTurn(label, "Running the numbers…") { toolOutcome(name, args, label) }

    private suspend fun toolOutcome(name: String, args: String, label: String): Outcome {
        if (kite.connected && kiteRows.isEmpty() && stockProvider() == null) rememberKite("holdings", kite.account("holdings"))
        turnStock = null
        val t0 = System.nanoTime()
        val json = directTools.execute(name, args)
        val ev = ToolEvent(name, directTools.defs.firstOrNull { it.name == name }?.label ?: name, ToolJson.ok(json), json, args, (System.nanoTime() - t0) / 1_000_000)
        if (!ev.ok) {
            val needsPortfolio = host.stock == null
            return Outcome("⚠️ " + (if (needsPortfolio) "This works on a portfolio or a stock, and none is loaded." else ToolJson.error(json) ?: "Couldn't calculate that."),
                chips = when {
                    needsPortfolio && kite.link != ChatFlow.Kite.CONNECTED -> { pending = label; listOf((if (kite.link == ChatFlow.Kite.EXPIRED) "Reconnect Kite" else "Connect Kite") to { onKitePill(kite.state.value) }) }
                    else -> listOf("Try again" to { submit(label) })
                })
        }
        return Outcome(ToolJson.summary(json) ?: "Done.", ev)
    }

    private var downloadJob: Job? = null

    /**
     * A live card for a market-history download: progress bar, days done, time left and Stop, updated
     * from the pack's state until it is ready (then it offers what to run next) or fails (then Retry).
     */
    private fun watchDownload() {
        val pack = com.khayali.pulao.ai.data.MarketPack.get(context)
        watchArtifact("market history", "Straight from NSE's public archive to this phone. You can keep chatting.", "days", pack.state, stop = { pack.cancel() },
            retry = { submit("/data update") }, next = listOf("/brief", "/strategies", "/anomalies"))
    }

    private fun watchKnowledge() {
        val kb = com.khayali.pulao.ai.data.KnowledgePack.get(context)
        watchArtifact("knowledge bank", "From the project's site; checked before use.", "KB", kb.state, stop = { kb.delete() }, retry = { kb.install() }, next = listOf("/screens", "/learn"))
    }

    /** What each artifact is, for the "needs a download" card. */
    private data class Offer(val name: String, val what: String, val buttons: List<Pair<String, () -> Unit>>)

    /**
     * The command needs something that isn't on the phone. Say what and why, offer the download, show live
     * progress, then run the command the user asked for — the dependency step of the chat's life cycle.
     */
    private fun offerArtifact(artifact: String, pending: String) {
        val market = com.khayali.pulao.ai.data.MarketPack.get(context); val kb = com.khayali.pulao.ai.data.KnowledgePack.get(context)
        val then = { scope.launch { kotlinx.coroutines.delay(300); submit(pending) }; Unit }
        val offer = when (artifact) {
            "market" -> Offer("market history", "$pending works on your own copy of NSE's daily prices, downloaded by this phone from NSE's public archive (about 25 MB a year). 3 years gives fair backtests.",
                listOf("Download 3 years" to { market.tier = com.khayali.pulao.ai.data.MarketPack.Tier.Y3; market.install(); watchArtifact("market history", "From NSE's public archive to this phone.", "days", market.state, { market.cancel() }, { market.install() }, emptyList(), then) },
                       "1 year (quicker)" to { market.tier = com.khayali.pulao.ai.data.MarketPack.Tier.Y1; market.install(); watchArtifact("market history", "From NSE's public archive to this phone.", "days", market.state, { market.cancel() }, { market.install() }, emptyList(), then) }))
            "knowledge" -> Offer("strategy knowledge bank", "$pending searches 500 community stock screens with their rules (about ${kb.spec.bytes / 1000} KB, from the project's site, checked by checksum).",
                listOf("Download (${kb.spec.bytes / 1000} KB)" to { kb.install(); watchArtifact("knowledge bank", "From the project's site; checked before use.", "KB", kb.state, { kb.delete() }, { kb.install() }, emptyList(), then) }))
            else -> return
        }
        startThread(); setPills(emptyList()); addUser(pending)
        val card = k.card {
            addView(k.text("This needs the ${offer.name}", 15f, Pal.TEXT, 500)); addView(k.space(6))
            addView(k.text(offer.what, 13f, Pal.MUTED).apply { setLineSpacing(0f, 1.3f) }); addView(k.space(12))
            offer.buttons.forEachIndexed { i, (label, go) -> addView(k.button(label, primary = i == 0) { go() }, k.lp(MATCH_PARENT).apply { topMargin = if (i > 0) k.dp(8) else 0 }) }
            addView(k.text("Manage everything on the phone: menu ⋮ → Models & data", 11.5f, Pal.DIM).apply { setPadding(0, k.dp(10), 0, 0) })
        }
        list.addView(card, k.lp(MATCH_PARENT).apply { topMargin = k.dp(14) }); scrollToEnd()
    }

    /**
     * A live card for any download: progress bar, amount done, time left and Stop, updated from the artifact's
     * state until it is ready (then [onReady], or buttons for [next]) or fails (then Retry).
     */
    private fun watchArtifact(name: String, note0: String, unit: String, flow: kotlinx.coroutines.flow.StateFlow<com.khayali.pulao.ai.data.Artifacts.State>,
                              stop: () -> Unit, retry: () -> Unit, next: List<String>, onReady: (() -> Unit)? = null) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            turnJob?.join()
            val s0 = flow.value
            if (!(s0 is com.khayali.pulao.ai.data.Artifacts.State.Downloading || s0 is com.khayali.pulao.ai.data.Artifacts.State.Updating || s0 is com.khayali.pulao.ai.data.Artifacts.State.Verifying)) {
                if (s0 is com.khayali.pulao.ai.data.Artifacts.State.Ready) onReady?.invoke()
                return@launch
            }
            val title = k.text("Downloading $name…", 15f, Pal.TEXT, 500)
            val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; isIndeterminate = true
                progressTintList = android.content.res.ColorStateList.valueOf(Pal.BLUE)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Pal.LINE)
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Pal.BLUE)
            }
            val stats = k.text("Starting…", 12.5f, Pal.MUTED)
            val note = k.text(note0, 11.5f, Pal.DIM)
            val actions = k.row()
            actions.addView(k.button("Stop", primary = false) { stop() }, k.lp(MATCH_PARENT))
            val card = k.card {
                addView(title); addView(k.space(10)); addView(bar, k.lp(MATCH_PARENT, k.dp(6))); addView(k.space(6)); addView(stats)
                addView(k.space(6)); addView(note); addView(k.space(12)); addView(actions, k.lp(MATCH_PARENT))
            }
            list.addView(card, k.lp(MATCH_PARENT).apply { topMargin = k.dp(14) }); scrollToEnd()
            val t0 = System.currentTimeMillis(); var firstDone = -1L
            flow.collect { s ->
                when (s) {
                    com.khayali.pulao.ai.data.Artifacts.State.Verifying -> { title.text = "Checking the $name…"; bar.isIndeterminate = true }
                    is com.khayali.pulao.ai.data.Artifacts.State.Downloading, is com.khayali.pulao.ai.data.Artifacts.State.Updating -> {
                        val (done, total) = if (s is com.khayali.pulao.ai.data.Artifacts.State.Downloading) s.done to s.total
                            else (s as com.khayali.pulao.ai.data.Artifacts.State.Updating).done.toLong() to s.total.toLong()
                        if (total > 0) {
                            if (firstDone < 0) firstDone = done
                            bar.isIndeterminate = false; bar.progress = (done * 1000 / total).toInt()
                            val secs = (System.currentTimeMillis() - t0) / 1000.0
                            val left = if (done > firstDone && secs > 1) ((total - done) * secs / (done - firstDone)).toLong() else null
                            title.text = "Downloading $name… ${done * 100 / total}%"
                            stats.text = (if (unit == "KB") "${done / 1000} of ${total / 1000} KB" else "$done of $total $unit") + (left?.let { " · about ${if (it >= 60) "${it / 60} min" else "$it s"} left" } ?: "")
                        }
                    }
                    is com.khayali.pulao.ai.data.Artifacts.State.Ready -> {
                        bar.isIndeterminate = false; bar.progress = 1000
                        title.text = "✓ ${name.replaceFirstChar { it.uppercase() }} ready"
                        stats.text = listOf(s.version, s.note, if (s.bytes >= 1_000_000) "${s.bytes / 1_000_000} MB" else "${s.bytes / 1000} KB").filter { it.isNotBlank() }.joinToString(" · ")
                        actions.removeAllViews()
                        if (onReady != null) { note.text = "Running what you asked…"; scrollToEnd(); onReady(); cancel() }
                        note.text = "Try what it powers:"
                        next.forEach { cmd ->
                            actions.addView(k.button(cmd, primary = cmd == "/brief") { submit(cmd) }, k.lp(0, WRAP_CONTENT, 1f).apply { marginEnd = k.dp(6) })
                        }
                        scrollToEnd(); cancel()
                    }
                    is com.khayali.pulao.ai.data.Artifacts.State.Failed -> {
                        bar.visibility = GONE; title.text = "⚠️ Download stopped"; stats.text = s.message
                        note.text = "Anything already downloaded is kept. Tap Retry to try again."
                        actions.removeAllViews(); actions.addView(k.button("Retry", primary = true) { retry() }, k.lp(MATCH_PARENT)); cancel()
                    }
                    is com.khayali.pulao.ai.data.Artifacts.State.Stale, com.khayali.pulao.ai.data.Artifacts.State.NotInstalled -> {
                        bar.visibility = GONE; title.text = "Download stopped"; stats.text = "What was downloaded is kept."
                        actions.removeAllViews(); actions.addView(k.button("Continue", primary = true) { retry() }, k.lp(MATCH_PARENT)); cancel()
                    }
                    else -> Unit
                }
            }
        }
    }

    @Volatile private var refreshedAt = 0L

    /** Runs whatever the refresh schedule says is due; a fetch shows its live card and counts as done once it lands. */
    private fun autoRefresh() {
        refreshedAt = System.currentTimeMillis()
        scope.launch {
            val done = runCatching { refresher.runDue() }.getOrDefault(emptyList())
            val fired: List<com.khayali.pulao.ai.data.Alerts.Hit> = withContext(Dispatchers.IO) {
                runCatching { com.khayali.pulao.ai.data.Alerts.schedule(context); com.khayali.pulao.ai.data.Alerts.run(context) }.getOrDefault(emptyList()) }
            fired.forEach { h ->
                list.addView(k.text("🔔 ${h.text}", 13f, Pal.SAFFRON, 500).apply { setPadding(k.dp(16), k.dp(10), k.dp(16), 0); setOnClickListener { submit("/why ${h.rule.symbol}") } }, k.lp(MATCH_PARENT)); scrollToEnd() }
            for (d in done) {
                if (d.fetching) {
                    if (!phase.busy) watchDownload()
                    val pack = com.khayali.pulao.ai.data.MarketPack.get(context)
                    launch {
                        val end = pack.state.first { it is com.khayali.pulao.ai.data.Artifacts.State.Ready || it is com.khayali.pulao.ai.data.Artifacts.State.Failed }
                        if (end is com.khayali.pulao.ai.data.Artifacts.State.Ready) { refresher.fetched(d.task.id); refreshTickers() }
                    }
                } else if (d.task.every != com.khayali.pulao.ai.data.Refresh.Every.DAILY && d.task.every != com.khayali.pulao.ai.data.Refresh.Every.WEEKLY || !d.note.contains("up to date") && !d.note.contains("No gaps")) {
                    list.addView(k.text("🔄 ${d.note}", 11.5f, Pal.DIM).apply { gravity = Gravity.CENTER; setPadding(k.dp(16), k.dp(10), k.dp(16), 0) }, k.lp(MATCH_PARENT)); scrollToEnd()
                }
            }
        }
    }

    /** Symbols the tap targets recognise: market history, Kite holdings, the watch list. */
    private fun refreshTickers() {
        // the snapshot header is a few KB, so this is quick enough to run before the thread renders
        Tickers.known = com.khayali.pulao.ai.data.MarketPack.get(context).symbols() + kite.knownSymbols.map { it.uppercase() } + watch.symbols.map { it.uppercase() }
        Tickers.onTap = { sym -> symbolMenu(sym) }
        Actions.onRun = { c -> runAction(c) }
    }

    /** A tapped action anywhere in the chat: a command, a fix, or a sentence to finish in the box. */
    private fun runAction(c: String) {
        when {
            c == "Allow web access" -> { pending = lastQuestion; askWeb(null) }
            c.startsWith("https://") -> openUrl(c)                // a filing, SEBI notice or news story: open it in the browser
            c.endsWith("…") -> { input.setText(c.removeSuffix("…").trimEnd() + " "); input.setSelection(input.text.length); input.requestFocus() }
            phase.busy -> android.widget.Toast.makeText(context, "One moment — finishing the current answer", android.widget.Toast.LENGTH_SHORT).show()
            else -> { if (via == null) via = "action"; submit(c) }
        }
    }

    // where the last touch landed, so the ticker wheel opens around the finger
    private var touchX = 0f; private var touchY = 0f
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) { touchX = ev.x; touchY = ev.y }
        return super.dispatchTouchEvent(ev)
    }
    private var wheel: TickerWheel? = null

    /** Tap or long-press a ticker anywhere in the chat: its actions fan out on a wheel around the finger. */
    private fun symbolMenu(sym: String) {
        if (wheel != null) return
        val items = TickerWheel.items(sym, prefs.webOn, "market" in readyArtifacts())
        val w = TickerWheel(context, k, sym, items, touchX, touchY, onPick = { c ->
            if (c.startsWith("ask:")) { input.setText(c.removePrefix("ask:")); input.setSelection(input.text.length); input.requestFocus() }
            else { via = "ticker:$sym"; submit(c) }
        }, onClose = { wheel?.let { removeView(it) }; wheel = null; blurBehind(false) })
        wheel = w
        blurBehind(true)
        // the keyboard would cover the lower half of the ring
        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        addView(w, LayoutParams(MATCH_PARENT, MATCH_PARENT)); w.open()
    }

    /** Softly blurs the chat behind the wheel (Android 12+; older phones get the dim alone). */
    private fun blurBehind(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        for (i in 0 until childCount) { val v = getChildAt(i); if (v !is TickerWheel)
            v.setRenderEffect(if (on) android.graphics.RenderEffect.createBlurEffect(k.dpf(10), k.dpf(10), android.graphics.Shader.TileMode.CLAMP) else null) }
    }

    /** The old list menu, kept for accessibility services that prefer a plain list. */
    @Suppress("unused") private fun symbolList(sym: String) {
        val items = Tickers.menu(sym, prefs.webOn, "market" in readyArtifacts())
        android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(sym)
            .setItems(items.map { it.first }.toTypedArray()) { _, i ->
                val c = items[i].second
                if (c.startsWith("ask:")) { input.setText(c.removePrefix("ask:")); input.setSelection(input.text.length); input.requestFocus() }
                else { via = "ticker:$sym"; submit(c) }
            }.show()
    }

    // ---------------------------------------------------------------- setup agent

    private fun setupPrefs() = context.getSharedPreferences("khayali_setup", Context.MODE_PRIVATE)
    private var setupJob: Job? = null

    /** The model's download state, in the same life cycle as every other artifact. */
    private fun modelArtifact(): com.khayali.pulao.ai.data.Artifacts.State = when (val s = mm.state.value) {
        ModelManager.State.Ready -> com.khayali.pulao.ai.data.Artifacts.State.Ready("Gemma 4 E2B", ModelSpec.SIZE_BYTES, 0)
        is ModelManager.State.Downloading -> if (s.paused) com.khayali.pulao.ai.data.Artifacts.State.Paused(s.reason ?: "waiting", com.khayali.pulao.ai.data.Artifacts.State.Downloading(s.done, s.total)) else com.khayali.pulao.ai.data.Artifacts.State.Downloading(s.done, s.total)
        is ModelManager.State.Verifying -> com.khayali.pulao.ai.data.Artifacts.State.Verifying
        is ModelManager.State.Failed -> com.khayali.pulao.ai.data.Artifacts.State.Failed(s.message)
        is ModelManager.State.NotEnoughSpace -> com.khayali.pulao.ai.data.Artifacts.State.Failed("needs ${DownloadText.bytes(s.needBytes)} free")
        else -> com.khayali.pulao.ai.data.Artifacts.State.NotInstalled
    }

    private fun setupFacts() = com.khayali.pulao.ai.data.Setup.Facts(modelArtifact(), com.khayali.pulao.ai.data.MarketPack.get(context).state.value,
        com.khayali.pulao.ai.data.KnowledgePack.get(context).state.value, prefs.webOn, kite.connected)

    /** True once everything the user chose is set up (the empty chat stops offering setup). */
    private fun setupDone(): Boolean {
        val chosen = com.khayali.pulao.ai.data.Setup.decode(setupPrefs().getString("chosen", null)) ?: return false
        val f = setupFacts(); val started = setupPrefs().getBoolean("started", false)
        return com.khayali.pulao.ai.data.Setup.phase(started, com.khayali.pulao.ai.data.Setup.Step.entries.associateWith { com.khayali.pulao.ai.data.Setup.status(it, it in chosen, started, f) }) == com.khayali.pulao.ai.data.Setup.Phase.DONE
    }

    /**
     * The one-tap setup agent: pick what you want, then everything starts at once — downloads in parallel, web
     * switched on, Kite waiting for your login. Each row shows its live status; choices are kept, so the card
     * picks up where it was after a restart.
     */
    fun showSetup() {
        startThread(); setPills(emptyList())
        val chosen = (SU.decode(setupPrefs().getString("chosen", null)) ?: SU.Step.entries.filter { it.byDefault }.toSet()).toMutableSet()
        var started = setupPrefs().getBoolean("started", false)
        val head = k.text("", 16f, Pal.TEXT, 600); val sub = k.text("", 12.5f, Pal.MUTED).apply { setLineSpacing(0f, 1.3f) }
        val rows = k.column(); val action = k.column()
        val card = k.card {
            addView(k.text("SETUP", 10.5f, Pal.BLUE, 700).apply { letterSpacing = 0.08f })
            addView(head); addView(sub); addView(k.space(8)); addView(rows, k.lp(MATCH_PARENT)); addView(k.space(10)); addView(action, k.lp(MATCH_PARENT))
            addView(k.text("Everything downloads straight to this phone and is checked before use. Change any of it later in ⋮ → Models & data.", 11f, Pal.DIM).apply { setPadding(0, k.dp(10), 0, 0) })
        }
        fun save() = setupPrefs().edit().putString("chosen", SU.encode(chosen)).putBoolean("started", started).apply()
        lateinit var render: () -> Unit
        fun startStep(step: SU.Step) {
            when (step) {
                SU.Step.MODEL -> mm.start(wifiOnly = true)
                SU.Step.MARKET -> com.khayali.pulao.ai.data.MarketPack.get(context).let { it.tier = com.khayali.pulao.ai.data.MarketPack.Tier.Y3; it.install() }
                SU.Step.KNOWLEDGE -> com.khayali.pulao.ai.data.KnowledgePack.get(context).install()
                SU.Step.WEB -> { prefs.webOn = true; session.invalidate(); renderWebPill() }
                SU.Step.KITE -> Unit                       // needs the user's own login on Zerodha's page
            }
        }
        render = {
            val f = setupFacts()
            val states = SU.Step.entries.associateWith { SU.status(it, it in chosen, started, f) }
            val phase = SU.phase(started, states)
            head.text = SU.headline(phase, states)
            sub.text = when { !started -> "Pick what you want; it all starts at once."; phase == SU.Phase.DONE -> "Everything you picked is ready on this phone."
                phase == SU.Phase.NEEDS_YOU -> "Tap the highlighted step to finish."; else -> "You can keep chatting while it works." }
            rows.removeAllViews()
            for (step in SU.Step.entries) {
                val st = states.getValue(step)
                val row = k.row().apply { setPadding(0, k.dp(7), 0, k.dp(7)) }
                val on = step in chosen
                if (!started) row.addView(k.text(if (on) "☑" else "☐", 18f, if (on) Pal.BLUE else Pal.DIM), k.lp(k.dp(30)))
                row.addView(k.text(step.icon, 17f, Pal.TEXT), k.lp(k.dp(30)))
                row.addView(k.column().apply {
                    addView(k.text(step.title, 13.5f, Pal.TEXT, 500))
                    addView(k.text("${step.size} · ${step.why}", 11f, Pal.MUTED).apply { maxLines = 2 })
                    if (st is SU.S.Running && st.pct != null) addView(android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100; progress = st.pct; progressTintList = android.content.res.ColorStateList.valueOf(Pal.BLUE)
                        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Pal.LINE) }, k.lp(MATCH_PARENT, k.dp(4)).apply { topMargin = k.dp(5) })
                }, k.lp(0, WRAP_CONTENT, 1f))
                val (label, color) = when (st) {
                    is SU.S.Done -> "✓ Ready" to Pal.GREEN; is SU.S.Running -> (st.pct?.let { "$it%" } ?: st.note.ifBlank { "working" }) to Pal.BLUE
                    SU.S.Waiting -> (if (started) "queued" else "") to Pal.MUTED; SU.S.Skipped -> (if (started) "skipped" else "") to Pal.DIM
                    is SU.S.NeedsYou -> st.action to Pal.SAFFRON; is SU.S.Failed -> "Retry" to Pal.RED
                }
                row.addView(k.text(label, 12f, color, 600).apply {
                    setPadding(k.dp(8), 0, 0, 0); maxLines = 1
                    when (st) {
                        is SU.S.NeedsYou -> setOnClickListener { onKitePill(kite.state.value) }
                        is SU.S.Failed -> setOnClickListener { startStep(step); render() }
                        else -> Unit
                    }
                })
                if (!started && st !is SU.S.Done) row.setOnClickListener { if (on) chosen.remove(step) else chosen.add(step); save(); render() }
                rows.addView(row, k.lp(MATCH_PARENT))
            }
            action.removeAllViews()
            when (phase) {
                SU.Phase.NOT_STARTED -> action.addView(k.button("⚡ Set up everything", primary = true) {
                    started = true; save()
                    SU.toStart(chosen, SU.Step.entries.associateWith { SU.status(it, it in chosen, true, setupFacts()) }).forEach { startStep(it) }
                    render()
                }, k.lp(MATCH_PARENT))
                SU.Phase.FAILED -> action.addView(k.button("Retry what didn't finish", primary = true) {
                    SU.toStart(chosen, SU.Step.entries.associateWith { SU.status(it, it in chosen, true, setupFacts()) }).forEach { startStep(it) }; render()
                }, k.lp(MATCH_PARENT))
                SU.Phase.DONE -> { setupJob?.cancel(); setPills(listOf("/brief", "/strategies", "/screens", "/learn")) }
                else -> Unit
            }
        }
        list.addView(card, k.lp(MATCH_PARENT).apply { topMargin = k.dp(14) }); render(); scrollToEnd()
        setupJob?.cancel()
        setupJob = scope.launch {
            combine(mm.state, com.khayali.pulao.ai.data.MarketPack.get(context).state, com.khayali.pulao.ai.data.KnowledgePack.get(context).state, kite.state) { _, _, _, _ -> }
                .collect { render() }
        }
    }

    // ---------------------------------------------------------------- live voice

    private var live: LiveVoice? = null
    private var voiceAwaiting = false
    private var lastDoneText = ""

    /** Opens live voice; asks for the microphone first (only when you tap the mic). */
    fun startLive() {
        if (live != null) return
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val a = context as? android.app.Activity
            if (a != null) a.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
            else say("", "Open Khayali Pulao from your home screen once to allow the microphone, then voice works here too.")
            return
        }
        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        val v = LiveVoice(context, k, ask = { text -> voiceAsk(text) }, onInterrupt = { voiceAwaiting = false }, onClose = {
            live?.let { chatCol?.removeView(it) }; live = null; voiceAwaiting = false
            inputWrap?.visibility = VISIBLE; backOff()
        })
        v.bias = { (kiteHoldings().map { it.name } + watch.symbols).distinct() }
        live = v
        // the dock takes the input bar's place; the conversation stays on screen above it
        inputWrap?.visibility = GONE
        chatCol?.addView(v, k.lp(MATCH_PARENT)); v.open(); backOn(); scrollToEnd()
    }

    /** What you said in voice mode. Like talking over someone, a new question replaces one still being answered. */
    private fun voiceAsk(text: String, waited: Int = 0) {
        if (live == null) return
        if (phase.busy) {
            if (waited == 0) { voiceAwaiting = false; stopGenerating() }
            if (waited < 30) { postDelayed({ voiceAsk(text, waited + 1) }, 100); return }
            live?.answer("One moment, I'm still finishing the last question."); return
        }
        via = "voice"
        submit(text)
    }

    /** Back ends voice first (Android 13+; on 12, back closes the chat, which ends voice too). */
    private var backCb: Any? = null
    private fun backOn() {
        if (android.os.Build.VERSION.SDK_INT < 33 || backCb != null) return
        val a = context as? android.app.Activity ?: return
        val cb = android.window.OnBackInvokedCallback { live?.end() }
        a.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb); backCb = cb
    }
    private fun backOff() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val cb = backCb as? android.window.OnBackInvokedCallback ?: return
        (context as? android.app.Activity)?.onBackInvokedDispatcher?.unregisterOnBackInvokedCallback(cb); backCb = null
    }

    /** The app went to the background: voice pauses (the microphone closes) until you're back. */
    fun onPause() { live?.hold() }

    /** Alerts notify after each close; Android 13+ asks once, the first time an alert is set. */
    private fun askNotifications() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        (context as? android.app.Activity)?.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFY_REQUEST)
    }

    /** From the activity after the microphone permission prompt. */
    fun onMicPermission(granted: Boolean) {
        if (granted && dictateAfterPermission) { dictateAfterPermission = false; dictate(); return }
        dictateAfterPermission = false
        if (granted) startLive()
        else say("", "Voice needs the microphone. Your speech is recognised on the phone by Android and never leaves it. You can allow it any time in Settings → Apps → Khayali Pulao → Permissions.")
    }

    /** Whether a terminal step used the network (for the privacy line). */
    private fun Terminal_needsWeb(name: String) = name.startsWith("term_") && com.khayali.pulao.ai.term.Terminal.find(name.removePrefix("term_"))?.needsWeb == true

    /** Whether a plan step's need is met right now, or why not. */
    private fun unmet(need: String?): Pair<String, () -> Unit>? = when (need) {
        "kite" -> if (kite.connected) null else ("needs Kite connected (read-only)" to { onKitePill(kite.state.value) })
        "web" -> if (prefs.webOn) null else ("needs web access" to { pending = lastQuestion; askWeb(null) })
        "market" -> if ("market" in readyArtifacts()) null else ("needs market history" to { submit("/data 3y") })
        "knowledge" -> if ("knowledge" in readyArtifacts()) null else ("needs the knowledge bank" to { submit("/data knowledge") })
        else -> null
    }

    /**
     * Runs a plan as one turn: a plan card whose steps tick off as they run, each step's own card as it lands,
     * then a wrap-up of what it shows and what to do next. Steps that can't run say why and offer the fix.
     */
    private fun runPlan(plan: Planner.Plan, label: String) {
        startThread(); setPills(emptyList()); addUser(label)
        pillJobCancel()
        dispatch(ChatFlow.Ev.StartApp)
        val marks = plan.steps.map { k.text("○", 14f, Pal.DIM, 600) }
        val card = k.card {
            addView(k.text("PLAN", 10.5f, Pal.BLUE, 700).apply { letterSpacing = 0.08f })
            addView(k.text(plan.goal, 15.5f, Pal.TEXT, 600).apply { setPadding(0, k.dp(2), 0, k.dp(8)) })
            plan.steps.forEachIndexed { i, st ->
                addView(k.row().apply {
                    setPadding(0, k.dp(5), 0, k.dp(5))
                    addView(marks[i], k.lp(k.dp(26)))
                    addView(k.text("${i + 1}. ${st.title}", 13.5f, Pal.TEXT), k.lp(0, WRAP_CONTENT, 1f))
                }, k.lp(MATCH_PARENT))
            }
        }
        list.addView(card, k.lp(MATCH_PARENT).apply { topMargin = k.dp(14) }); scrollToEnd()
        fun mark(i: Int, s: String, c: Int) { marks[i].text = s; marks[i].setTextColor(c) }
        turnJob = scope.launch {
            val results = mutableListOf<String>(); val done = mutableListOf<Planner.Done>(); val ran = mutableSetOf<String>()
            try {
                for ((i, st) in plan.steps.withIndex()) {
                    unmet(st.needs)?.let { (why, _) -> mark(i, "–", Pal.DIM); done += Planner.Done(st, null, null, why); continue }
                    val cmd = Planner.resolve(st.command, results)
                    if (cmd == null) { mark(i, "–", Pal.DIM); done += Planner.Done(st, null, null, "nothing to look up"); continue }
                    mark(i, "◔", Pal.BLUE)
                    val e = runForModel(cmd)
                    if (e.ok) {
                        mark(i, "✓", Pal.GREEN); results += e.resultJson; ran += cmd
                        val bot = newBot(); bot.why = "Step ${i + 1} of ${plan.steps.size} in the plan “${plan.goal}”: ${st.title.replaceFirstChar { it.lowercase() }} — it ran $cmd." +
                            (if (cmd != st.command) " (${st.command} filled in from what earlier steps found.)" else "")
                        bot.tool(e); bot.done(ToolJson.summary(e.resultJson).orEmpty(), null, null, question = cmd)
                        remember(cmd, ToolJson.summary(e.resultJson).orEmpty(), e, seenByModel = false)
                        done += Planner.Done(st, cmd, ToolJson.summary(e.resultJson).orEmpty(), null)
                    } else {
                        mark(i, "⚠", Pal.SAFFRON); done += Planner.Done(st, cmd, null, ToolJson.error(e.resultJson) ?: "didn't work")
                    }
                    scrollToEnd()
                }
                val next = Planner.next(results, ran)
                // next steps and fixes are action links inside the wrap-up itself
                val wrap = newBot(); wrap.why = "The plan's wrap-up: one line from each step's own summary, steps that couldn't run with their fix, and next steps taken from the cards' suggestions."
                wrap.done(Planner.wrapUp(plan, done, next), null, null, question = label)
                remember(label, Planner.wrapUp(plan, done, next), null, seenByModel = false)
                lastQuestion = label
                finishTurn()
                setPills(next); scrollToEnd()
            } catch (c: CancellationException) {
                withContext(NonCancellable) { finishTurn(ChatFlow.Ev.Stopped); setPills(lastPills, remember = false) }
            }
        }
    }

    /** A terminal feature: fetch, build its card, show it. Same turn life cycle as every other app-only command. */
    private fun runTerm(id: String, arg: String, label: String) {
        val feature = com.khayali.pulao.ai.term.Terminal.find(id) ?: return showHelp()
        appTurn(label, "${feature.help.substringBefore(':').substringBefore(" — ").take(40)}…") { termOutcome(id, arg, label) }
    }

    private suspend fun termOutcome(id: String, arg: String, label: String): Outcome {
        val feature = com.khayali.pulao.ai.term.Terminal.find(id) ?: return Outcome("⚠️ Unknown command /$id")
        run {
            if (kite.connected && kiteRows.isEmpty()) rememberKite("holdings", kite.account("holdings"))
            val t0 = System.nanoTime()
            val json = web.term(feature, arg, kiteHoldings(), watch.symbols)
            if (!ToolJson.ok(json)) return failure(json, label)
            return Outcome(ToolJson.summary(json).orEmpty(), ToolEvent("term_$id", feature.help.take(28), true, json,
                com.google.gson.JsonObject().apply { addProperty("command", "/$id $arg".trim()) }.toString(), (System.nanoTime() - t0) / 1_000_000))
        }
    }

    private fun runWeb(kind: ChatFlow.WebKind, query: String, label: String) {
        if (kind == ChatFlow.WebKind.SEARCH && mm.isReady()) { webAnswer(query, label); return }
        appWeb(kind, query, label)
    }

    /** Value first: the phone searches, the on-device model writes the answer from those results only, the sources sit under it as short citations. */
    private fun webAnswer(query: String, label: String) {
        val args = com.google.gson.JsonObject().apply { addProperty("query", query) }.toString()
        sendToModel(label, label, ChatSession.Prep("Searching the web…") {
            val t0 = System.nanoTime()
            val (forModel, forCard) = web.search(query)
            val ev = ToolEvent("web_search", "Searched the web", ToolJson.ok(forCard), forCard, args, (System.nanoTime() - t0) / 1_000_000)
            (if (ToolJson.ok(forCard)) WEB_ANSWER + forModel else "") to listOf(ev)
        })
    }

    private fun appWeb(kind: ChatFlow.WebKind, query: String, label: String) = appTurn(label, when (kind) {
        ChatFlow.WebKind.SEARCH -> "Searching the web…"; ChatFlow.WebKind.PRICE -> "Getting the latest price…"; ChatFlow.WebKind.EVENTS -> "Looking up company events…"
        ChatFlow.WebKind.CALENDAR -> "Reading the market calendar…"; ChatFlow.WebKind.IPO -> "Checking IPOs…"; ChatFlow.WebKind.GMP -> "Reading the grey-market premium…"
    }) { webOutcome(kind, query, label) }

    private suspend fun webOutcome(kind: ChatFlow.WebKind, query: String, label: String): Outcome {
        val t0 = System.nanoTime()
        val args = com.google.gson.JsonObject().apply { addProperty("query", query) }.toString()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        return when (kind) {
            ChatFlow.WebKind.SEARCH -> {
                val json = web.search(query).second
                if (!ToolJson.ok(json)) return failure(json, label)
                Outcome(WebData.extract(WebData.resultsOf(json)), ToolEvent("web_search", "Searched the web", true, json, args, ms()))
            }
            ChatFlow.WebKind.PRICE -> {
                val json = web.quote(query, "6m")
                if (!ToolJson.ok(json)) return failure(json, label)
                Outcome(ToolJson.summary(json).orEmpty(), ToolEvent("web_quote", "Got the price", true, json, args, ms()))
            }
            ChatFlow.WebKind.CALENDAR -> {
                if (kite.connected && kiteRows.isEmpty()) rememberKite("holdings", kite.account("holdings"))      // so your own stocks can be marked
                val json = web.calendar(query, (kiteHoldings().map { it.name } + kite.knownSymbols).map { it.uppercase() }.toSet())
                if (!ToolJson.ok(json)) return failure(json, label)
                Outcome(ToolJson.summary(json).orEmpty(), ToolEvent("web_calendar", "Read the calendar", true, json, args, ms()))
            }
            ChatFlow.WebKind.GMP -> {
                val json = web.gmp(query)
                if (!ToolJson.ok(json)) return failure(json, label)
                Outcome(ToolJson.summary(json).orEmpty(), ToolEvent("web_gmp", "Read the grey market", true, json, args, ms()))
            }
            ChatFlow.WebKind.IPO -> {
                val json = web.ipo()
                if (!ToolJson.ok(json)) return failure(json, label)
                Outcome(ToolJson.summary(json).orEmpty(), ToolEvent("web_ipo", "Checked IPOs", true, json, args, ms()))
            }
            ChatFlow.WebKind.EVENTS -> {
                val json = web.events(query)
                if (!ToolJson.ok(json)) return failure(json, label)
                Outcome(ToolJson.summary(json).orEmpty(), ToolEvent("web_events", "Found events", true, json, args, ms()))
            }
        }
    }

    /**
     * Runs any data command for the AI model's run_command tool, through the same code as a typed command,
     * and returns its result (the card is drawn from it). Commands that need the user — connecting Kite,
     * allowing web access — come back as an error the model can relay.
     */
    suspend fun runForModel(command: String): ToolEvent {
        val cmd = command.trim().let { if (it.startsWith("/")) it else "/$it" }
        fun fail(msg: String) = ToolEvent("run_command", cmd, false, com.google.gson.JsonObject().apply { addProperty("error", msg) }.toString(),
            com.google.gson.JsonObject().apply { addProperty("command", cmd) }.toString())
        val outcome = when (val a = ChatFlow.route(cmd, env())) {
            is ChatFlow.Action.KiteRead -> kiteOutcome(a.section, cmd)
            is ChatFlow.Action.Chart -> chartOutcome(a.symbol, a.period, a.source, cmd)
            is ChatFlow.Action.Tool -> toolOutcome(a.name, a.args, cmd)
            is ChatFlow.Action.Web -> webOutcome(a.kind, a.query, cmd)
            is ChatFlow.Action.Term -> termOutcome(a.id, a.arg, cmd)
            is ChatFlow.Action.ConnectKite -> return fail(if (a.expired) "The user's Kite login has expired; ask them to tap Reconnect Kite." else "Kite is not connected; ask the user to tap Connect Kite.")
            is ChatFlow.Action.EnableWeb -> return fail("Web access is off; ask the user to turn it on with the Web pill.")
            is ChatFlow.Action.Plan -> return fail("That's a broad question; run its parts as separate commands.")
            ChatFlow.Action.Setup -> return fail("Setup is for the user to run: tell them to type /setup.")
            is ChatFlow.Action.NeedArtifact -> return fail("That needs the ${a.artifact} download, which isn't on the phone yet. Tell the user to type ${if (a.artifact == "market") "/data 3y" else "/screens"} to get it.")
            else -> return fail("$cmd is not a data command. Use one of the commands listed in the tool description.")
        }
        WatchStore.symbolsIn(cmd).forEach { watch.add(it) }
        return outcome.event ?: fail(outcome.text.removePrefix("⚠️ ").trim())
    }

    private fun sendToModel(display: String, prompt: String, prep: ChatSession.Prep? = null) {
        if (phase.busy) return
        val sym = Commands.mentions(display).firstOrNull()
        if (sym != null && kite.connected && kiteRows.none { it["symbol"].asString.equals(sym, true) } && sym in kite.knownSymbols) {
            // Holding details aren't loaded in this session yet: read them first, then continue.
            dispatch(ChatFlow.Ev.StartApp)
            turnJob = scope.launch {
                try { rememberKite("holdings", kite.account("holdings")) } finally { dispatch(ChatFlow.Ev.Finished) }
                sendToModelNow(display, prompt, prep)
            }
            return
        }
        sendToModelNow(display, prompt, prep)
    }

    private fun sendToModelNow(display: String, prompt: String, prep: ChatSession.Prep? = null) {
        if (phase.busy || !mm.isReady()) return
        if (session.needsCompaction) compactNow(null)          // the context is nearly full: fold the thread before this turn
        var text = prompt
        // The user moved on without asking for a redo, so the memory behind the last answer helped.
        if (awaitingFeedback.isNotEmpty()) { memoryStore.feedback(awaitingFeedback, good = true); awaitingFeedback = emptySet() }
        lastQuestion = display; lastShown = null; pillJobCancel()
        // @mentions: point the tools at that holding, or tell the model which symbol to quote.
        turnStock = null
        Commands.mentions(display).firstOrNull()?.let { sym ->
            kiteRows.firstOrNull { it["symbol"].asString.equals(sym, true) }?.let { r ->
                val qty = r["qty"].asDouble; val avg = r["avg_price"].asDouble
                turnStock = Stock.Holding(sym, qty, avg, qty * avg, r["ltp"].asDouble)
            }
            text += when {
                turnStock != null -> "\n(@$sym is one of my holdings — call get_current_stock, then the calculators on it.)"
                kite.connected -> "\n(@$sym = ${kite.exchangeOf(sym) ?: "NSE"}:$sym. Use kite_quote for its price.)"
                prefs.webOn -> "\n(@$sym is an NSE symbol. Use web with action=price for its price.)"
                else -> "\n(@$sym is an NSE symbol. You have no live price for it, so ask me for the price.)"
            }
        }
        // "my biggest / best / worst holding" is resolved by the app, not guessed by the model.
        if (turnStock == null && stockProvider() == null) ContextPills.pick(display, kiteHoldings())?.let { (what, h) ->
            turnStock = h
            text += "\n(My $what holding is ${h.name}; it is the current stock. Call the calculator without a symbol.)"
        }
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        input.setText("")
        startThread()
        setPills(emptyList())
        addUser(display)
        val bot = newBot()
        scrollToEnd()
        dispatch(ChatFlow.Ev.StartModel(AiEngine.state.value is AiEngine.State.Ready))
        session.send(text, prep = prep, listener = object : ChatSession.Listener {
            override fun onThinking(warming: Boolean) = bot.thinking(warming)
            override fun onEngineReady() { dispatch(ChatFlow.Ev.EngineReady); bot.thinking(false) }
            override fun onToolStart(label: String) { dispatch(ChatFlow.Ev.ToolCall); bot.status(label) }
            override fun onTool(e: ToolEvent) {
                dispatch(ChatFlow.Ev.ToolCall); dispatch(ChatFlow.Ev.ToolResult)
                if (e.ok && FollowUps.from(e.name, e.argsJson).isNotEmpty()) lastCall = e
                lastShown = e
                if (e.name == "kite_account") rememberKite(if (e.resultJson.contains("\"holdings\"")) "holdings" else "", e.resultJson)
                bot.tool(e); scrollToEnd()
            }
            override fun onText(full: String) {
                dispatch(ChatFlow.Ev.Token)
                // With a data card on screen, raw streaming text could show a figure before it is checked: wait for the final, checked answer.
                if (bot.hasDataCard) bot.status("Writing the takeaway…") else bot.text(full)
                if (voiceAwaiting && !bot.hasDataCard) live?.stream(full)          // live voice: speak whole sentences as they arrive
                scrollToEnd()
            }
            override fun onDone(full: String, stats: TurnStats) {
                val answer = composeAnswer(display, full, stats.tools)
                val redo = { memoryStore.feedback(session.lastMemoryIds, good = false); awaitingFeedback = emptySet(); submit(display) }
                bot.done(answer.text, stats, redo, question = display, chips = answer.chips)
                awaitingFeedback = session.lastMemoryIds
                reflect(display, full, stats.tools, answer.ungrounded, bot)
                if (answer.text.isNotBlank()) remember(display, answer.text, stats.tools.lastOrNull { it.ok && ResultCards.hasCard(it.name) }, seenByModel = true)
                finishTurn()
                showFollowUps(reflectOn = display, bot = bot); scrollToEnd()
            }
            override fun onCompacted(summary: String?, folded: List<ChatSession.Turn>) {
                // before the earlier chat leaves the model's view, what it said about you is kept in memory
                val facts = if (memoryStore.enabled) folded.flatMap { Ace.reflectRules(it.user) }.distinctBy { it.text } else emptyList()
                val learned = if (facts.isNotEmpty()) memoryStore.learn(facts) else 0
                if (voiceAwaiting) live?.redo()
                bot.restart("Folding the earlier chat into a summary…")
                bot.note("📎 The chat got long, so the earlier part was folded into a summary" + (if (learned > 0) " and $learned thing${if (learned > 1) "s" else ""} saved to memory" else "") +
                    " — then answered. Nothing was removed from this screen.")
            }
            override fun onError(message: String) {
                bot.error(message) { submit(display) }
                finishTurn(ChatFlow.Ev.Failed); setPills(lastPills, remember = false)
            }
        })
    }

    private class Answer(val text: String, val ungrounded: Boolean, val chips: List<Pair<String, () -> Unit>>)

    private val WEB_ANSWER = "(Answer from these web results only. Start with the answer itself in 2–4 plain sentences — no preamble such as " +
        "\"according to the results\". Add up to 3 short bullets only if they add something. Put the result's number after each fact, like [1]. " +
        "Don't list the links — the app shows them under your answer. If the results don't answer the question, say so in one sentence.)\n\nWeb results:\n"

    /**
     * What the user reads. Calculator turns: the tools' exact summaries plus number-free insight from
     * the model. Web turns: the model's sentences, minus any whose figures aren't in the sources —
     * and, if nothing survives, the snippets themselves with citations.
     */
    private fun composeAnswer(question: String, raw: String, tools: List<ToolEvent>): Answer {
        // the model's next-step links: repaired when the command exists, dropped when it doesn't
        val full = NumberGuard.tidyLinks(raw) { cmd -> cmd.startsWith("/") && !Regex("""(?i)\b(symbol|ticker|stock_name|xxx)\b""").containsMatchIn(cmd) &&
            Commands.parse(cmd).let { it !is Commands.Parsed.Unknown && it !is Commands.Parsed.Help } }
        val search = tools.lastOrNull { it.name == "web_search" && it.ok }
        // Terminal cards show their own summary as the takeaway, so it is not repeated in the text.
        val sums = tools.filter { it.ok && it.name != "web_search" && !it.name.startsWith("term_") }.mapNotNull { ToolJson.summary(it.resultJson) }
        val chips = mutableListOf<Pair<String, () -> Unit>>()
        tools.lastOrNull { !it.ok }?.let { failed ->
            if (failed.resultJson.contains("\"expired\":true")) { pending = question; chips += "Reconnect Kite" to { onKitePill(kite.state.value) } }
        }
        if (search != null) {
            val (kept, dropped) = NumberGuard.grounded(full, tools.joinToString(" ") { it.resultJson })
            val body = kept.ifBlank { WebData.extract(WebData.resultsOf(search.resultJson)) }
            return Answer((sums.distinct() + body).joinToString("\n\n").trim(), dropped, chips)
        }
        if (sums.isEmpty() && tools.any { it.ok && it.name.startsWith("term_") }) return Answer(NumberGuard.compose(full, listOf("")).trim(), false, chips)
        var text = NumberGuard.compose(full, sums)
        if (text.isBlank()) text = tools.lastOrNull { !it.ok }?.let { ToolJson.error(it.resultJson) }?.let { "⚠️ $it" }.orEmpty()
        // Figures with no calculator behind them are the model's own arithmetic: say so plainly.
        if (tools.isEmpty() && ownFigures(question, full)) text += "\n\n> These figures were not checked by a calculator. Ask again with the amounts and I'll run the exact maths."
        return Answer(text, false, chips)
    }

    private val AMOUNT = Regex("""₹\s?[\d,]{3,}""")
    private fun ownFigures(question: String, answer: String): Boolean {
        val asked = AMOUNT.findAll(question).map { it.value.filter(Char::isDigit) }.toSet()
        return AMOUNT.findAll(answer).any { it.value.filter(Char::isDigit) !in asked }
    }

    /** Reflect → curate: turn what just happened into playbook deltas (rules now; the model's pass follows the pills). */
    private fun reflect(question: String, full: String, tools: List<ToolEvent>, ungrounded: Boolean, bot: BotMessage) {
        if (!memoryStore.enabled) return
        val signals = Ace.Signals(
            percentCorrected = session.percentCorrected,
            toolErrors = tools.filter { !it.ok }.mapNotNull { ToolJson.error(it.resultJson) },
            numbersWithoutTool = tools.isEmpty() && ownFigures(question, full),
            ungroundedWebNumbers = ungrounded,
            askedForFetchableData = tools.isEmpty() && kite.connected &&
                Regex("""(?i)(which stock|which holding|how many shares|what(?:'s| is) (?:the|your) (?:price|quantity|average)|please (?:provide|share|tell))""").containsMatchIn(full),
        )
        memoryStore.learn(Ace.lessons(signals))
        val facts = Ace.reflectRules(question)
        if (memoryStore.learn(facts) > 0) bot.note("🧠 Remembered: " + facts.joinToString(" ") { it.text })
    }

    private fun stopGenerating() {
        if (session.busy) session.stop() else turnJob?.cancel()
    }

    private var lastShown: ToolEvent? = null
    private var lastQuestion: String? = null
    private fun pillJobCancel() { /* background pill + reflection work is owned by the session and cancelled by the next send */ }

    /**
     * Pills after an answer, curated from context in three layers:
     * exact re-runs of the last calculation → context pills (real holdings, last result) →
     * model-generated follow-ups that slide in when ready.
     */
    private fun showFollowUps(useModel: Boolean = true, reflectOn: String? = null, bot: BotMessage? = null) {
        val exact = lastCall?.let { c -> FollowUps.from(c.name, c.argsJson).filter { it.args != c.argsJson && it.label != lastQuestion } }.orEmpty()
        val goal = memoryStore.takeIf { it.enabled }?.playbook?.bullets?.firstOrNull { it.text.startsWith("Goal:") }?.text?.removePrefix("Goal:")?.trim()?.trimEnd('.')
        val ctx = ContextPills.build(ContextPills.Ctx(kite.connected, kite.knownSymbols, lastShown?.name, lastShown?.resultJson, lastQuestion, prefs.webOn, goal))
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
        session.afterTurn(reflectOn.orEmpty(),
            onPills = { ai -> if (!phase.busy) render(ai) },
            onReflection = { deltas -> if (memoryStore.learn(deltas) > 0) bot?.note("🧠 Remembered: " + deltas.joinToString(" ") { it.text }) },
            reflect = reflectOn != null && memoryStore.enabled)
    }

    private fun runExact(p: FollowUps.Pill) {
        if (phase.busy) return
        runTool(p.tool, p.args, p.label)
    }

    private fun addUser(text: String, animate: Boolean = true) {
        val b = k.text(text, 15.5f, Pal.TEXT).apply {
            background = GradientDrawable().apply {
                setColor(Pal.SURFACE); val r = k.dpf(22); cornerRadii = floatArrayOf(r, r, k.dpf(6), k.dpf(6), r, r, r, r)
            }
            setPadding(k.dp(16), k.dp(11), k.dp(16), k.dp(11)); setLineSpacing(0f, 1.3f)
            maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
        }
        b.setOnLongClickListener {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val items = arrayOf("Copy", "Edit and ask again")
            android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert).setItems(items) { _, i ->
                if (i == 0) copy(text) else if (live == null) { input.setText(text); input.setSelection(input.text.length); input.requestFocus()
                    context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(input, 0) }
            }.show(); true
        }
        val row = k.row(Gravity.END).apply { addView(b) }
        list.addView(row, k.lp(MATCH_PARENT).apply { topMargin = k.dp(14) })
        if (!animate) return
        b.alpha = 0f; b.scaleX = 0.92f; b.scaleY = 0.92f; b.pivotX = 1000f
        b.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    private fun scrollToEnd() = scroll.post { scroll.smoothScrollTo(0, list.bottom) }

    /** "Shorter / Longer / Simpler" on an answer: a new turn that rewrites the last one. */
    private fun modify(display: String, prompt: String) {
        if (phase.busy || !mm.isReady()) return
        via = "modify"; sendToModel(display, prompt)
    }

    private fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()

    /** One model turn: sparkle avatar, tool chips, shimmer → streaming text → actions. */
    private inner class BotMessage {
        val root = k.row(Gravity.TOP)
        private val avatar = ImageView(context).apply { setImageResource(R.drawable.bubble_icon) }
        val body = k.column()
        /** The steps it took (tools, lookups): listed live while it works, folded into one line after. */
        private val steps = k.column().apply { visibility = GONE; setPadding(0, k.dp(4), 0, 0) }
        private val stepsLabel = k.text("", 12.5f, Pal.MUTED, 500)
        private val stepsChevron = ImageView(context).apply { setImageResource(Icons.res("expand")); Icons.tint(this, Pal.MUTED) }
        private val stepsToggle = k.row().apply {
            visibility = GONE; gravity = Gravity.CENTER_VERTICAL; background = k.press(null, 999f); setPadding(0, k.dp(3), k.dp(10), k.dp(3))
            addView(stepsLabel); addView(stepsChevron, k.lp(k.dp(18), k.dp(18)).apply { marginStart = k.dp(2) })
            contentDescription = "Show the steps"
            setOnClickListener { val open = steps.visibility != VISIBLE; steps.visibility = if (open) VISIBLE else GONE; stepsChevron.animate().rotation(if (open) 180f else 0f).setDuration(180).start() }
        }
        private val cards = k.column()
        private val status = FlowingGradientText(context).apply { k.style(this, "", 13f, Pal.TEXT, 500) }
        private val shimmer = ShimmerLines(context, k)
        private val txt = MarkdownView(context, k)
        private val actions = k.row().apply { visibility = GONE }
        private val sources = k.column()
        private var spin: ValueAnimator? = null

        init {
            // Avatar and status share the top line; everything below uses the full width of the chat,
            // so cards and charts are not squeezed into a narrow column.
            root.orientation = LinearLayout.VERTICAL
            val head = k.row().apply {
                addView(avatar, k.lp(k.dp(26), k.dp(26)))
                addView(k.column().apply {
                    setPadding(k.dp(10), 0, 0, 0)
                    addView(status)
                    addView(stepsToggle, k.lp(WRAP_CONTENT).apply { topMargin = k.dp(2) })
                    addView(steps, k.lp(MATCH_PARENT))
                }, k.lp(0, WRAP_CONTENT, 1f))
            }
            root.addView(head, k.lp(MATCH_PARENT))
            body.addView(cards, k.lp(MATCH_PARENT))
            body.addView(shimmer, k.lp(MATCH_PARENT).apply { topMargin = k.dp(8) })
            body.addView(txt, k.lp(MATCH_PARENT).apply { topMargin = k.dp(4) })
            body.addView(sources, k.lp(MATCH_PARENT))             // web sources sit under the answer, as short citations
            body.addView(actions, k.lp(MATCH_PARENT).apply { topMargin = k.dp(8) })
            root.addView(body, k.lp(MATCH_PARENT))
            txt.visibility = GONE
        }

        fun appear() {
            root.alpha = 0f; root.translationY = k.dpf(10)
            root.animate().alpha(1f).translationY(0f).setDuration(250).start()
        }

        fun thinking(warming: Boolean) = status(if (warming) "Warming up the model…" else pick(THINKING))

        /** What is happening right now, with the avatar spinning. */
        fun status(label: String) {
            status.text = label
            if (spin?.isRunning == true) return
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
            status.text = pick(THINKING)
            val r = k.row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, k.dp(3), 0, k.dp(3)); alpha = 0f; translationY = k.dpf(4) }
            r.addView(ImageView(context).apply { setImageResource(Icons.res(if (e.ok) "check" else "close")); Icons.tint(this, if (e.ok) Pal.GREEN else Pal.SAFFRON) }, k.lp(k.dp(15), k.dp(15)))
            r.addView(k.text(e.label + (if (e.ms > 0) "  ·  ${secs(e.ms)}" else ""), 12.5f, Pal.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, k.lp(0, WRAP_CONTENT, 1f).apply { marginStart = k.dp(8) })
            steps.addView(r, k.lp(MATCH_PARENT)); steps.visibility = VISIBLE
            r.animate().alpha(1f).translationY(0f).setDuration(220).start()
            if (e.ok) card(e)
        }

        /** True once a card that carries its own summary is on screen; the chat then doesn't repeat that summary as text. */
        var cardHasSummary = false
        val hasDataCard get() = localTools.any { it.ok && (it.name.startsWith("term_") || it.name.startsWith("web_") || it.name.startsWith("kite_")) }

        fun card(e: ToolEvent) {
            if (e.name == "web_search") {          // value first: the answer, then its sources as short citations under it
                ResultCards.citations(k, WebData.resultsOf(e.resultJson), ::openUrl)?.let { sources.removeAllViews(); sources.addView(it, k.lp(MATCH_PARENT)) }
                return
            }
            cardHasSummary = e.name.startsWith("term_")
            ResultCards.build(context, k, e.name, e.resultJson, ::openUrl)?.let { card ->
                cards.removeAllViews()          // newest result replaces older ones in this turn
                cards.addView(card, k.lp(MATCH_PARENT).apply { topMargin = k.dp(10) })
                Tickers.wire(card)              // every ticker in any card opens the wheel
            }
        }

        /** The same question is being answered again (after folding a long chat): clear what this turn had shown. */
        fun restart(label: String) {
            localTools.clear(); steps.removeAllViews(); steps.visibility = GONE; stepsToggle.visibility = GONE
            cards.removeAllViews(); sources.removeAllViews(); cardHasSummary = false
            txt.visibility = GONE; shimmer.alpha = 1f; shimmer.visibility = VISIBLE; status.visibility = VISIBLE
            status(label)
        }

        /** A quiet line under the answer, e.g. what was just saved to memory. */
        fun note(text: String) {
            body.addView(k.text(text, 11.5f, Pal.DIM).apply { setPadding(k.dp(8), k.dp(4), k.dp(8), 0); setLineSpacing(0f, 1.3f); alpha = 0f; animate().alpha(1f).setDuration(300).start() })
        }

        fun text(full: String) {
            if (txt.visibility != VISIBLE) {
                shimmer.animate().alpha(0f).setDuration(150).withEndAction { shimmer.visibility = GONE }.start()
                txt.visibility = VISIBLE; status.visibility = GONE
            }
            txt.update(full)
        }

        /** Why this reply exists (see [Methods.why]); shown in its "How this was made" panel. */
        var why: String? = null

        fun done(full: String, stats: TurnStats?, retry: (() -> Unit)?, question: String = "", chips: List<Pair<String, () -> Unit>> = emptyList(), restored: Boolean = false) {
            if (!restored) lastDoneText = full
            stopSpin(); shimmer.visibility = GONE; status.visibility = GONE
            // Always show the final, checked text — what streamed may have contained figures that were since removed.
            // An app-only answer whose card already shows the summary adds nothing as a paragraph.
            val repeat = cardHasSummary && stats == null
            // nothing left to say beside a card (every sentence was unverifiable): the card speaks, and voice reads its summary
            val cardOnly = full.isBlank() && localTools.any { it.ok && ResultCards.hasCard(it.name) }
            if (cardOnly && !restored) lastDoneText = localTools.lastOrNull { it.ok }?.let { ToolJson.summary(it.resultJson) }.orEmpty()
            txt.visibility = if (repeat || cardOnly) GONE else VISIBLE; if (!repeat && !cardOnly) txt.update(full.ifBlank { "(stopped)" })
            if (chips.isNotEmpty()) {
                val row = k.row().apply { setPadding(0, k.dp(8), 0, 0) }
                chips.forEachIndexed { i, (label, action) ->
                    row.addView(k.text(label, 13f, Pal.BLUE, 500).apply {
                        setPadding(k.dp(14), k.dp(8), k.dp(14), k.dp(8))
                        background = k.press(k.rounded(Pal.alpha(Pal.BLUE, 0.12f), 999f, Pal.alpha(Pal.BLUE, 0.5f)), 999f)
                        // a choice is made once: the row goes away so it can't be tapped twice
                        setOnClickListener { if (!phase.busy) { (row.parent as? View)?.visibility = GONE; action() } }
                    }, k.lp(WRAP_CONTENT).apply { if (i > 0) marginStart = k.dp(8) })
                }
                body.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) }, body.indexOfChild(actions))
            }
            // the steps fold into one line ("✓ 2 steps · 1.4 s ⌄"); tap to see them again
            if (localTools.isNotEmpty()) {
                val n = localTools.size
                stepsLabel.text = (if (localTools.all { it.ok }) "✓ " else "! ") + "$n step${if (n > 1) "s" else ""} · ${secs(stats?.totalMs ?: localMs())}"
                stepsToggle.visibility = VISIBLE; steps.visibility = GONE; stepsChevron.rotation = 0f
            }
            val st = stats ?: TurnStats(localMs(), null, null, null, null, localTools)
            actions.removeAllViews(); actions.visibility = VISIBLE
            actions.gravity = Gravity.CENTER_VERTICAL
            val dim = Pal.alpha(Pal.TEXT, 0.62f)
            fun act(icon: String, desc: String, go: (ImageView) -> Unit) = Icons.button(context, k, icon, dim, desc, 19, 38, go).also { actions.addView(it, k.lp(k.dp(38), k.dp(38))) }
            val memIds = if (stats?.backend != null) session.lastMemoryIds else emptySet()
            if (!restored && question.isNotBlank()) {
                var up: ImageView? = null; var down: ImageView? = null
                up = act("thumb_up", "Good answer") { rate(true, it, down, memIds) }
                down = act("thumb_down", "Bad answer") { rate(false, it, up, memIds) }
            }
            if (retry != null) act("refresh", "Regenerate") { if (!phase.busy) retry() }
            act("copy", "Copy") { copy(full.ifBlank { lastDoneText }) }
            if (question.isNotBlank()) act("share", "Share") { shareAnswer(body, question) }
            val spoken = full.ifBlank { localTools.lastOrNull { it.ok }?.let { ToolJson.summary(it.resultJson) }.orEmpty() }
            if (spoken.isNotBlank()) act("volume", "Read aloud") { listen(spoken, it) }
            val meta = k.text("⏱ ${secs(st.totalMs)}" + (st.backend?.let { " · $it" } ?: if (st.tools.isNotEmpty() && st.backend == null) " · app" else "") +
                (st.tokensPerSec?.let { " · ${it.toInt()} tok/s" } ?: "") + " · how this was made  ▾", 11f, Pal.DIM).apply {
                setPadding(k.dp(8), k.dp(6), k.dp(8), k.dp(6)); maxLines = 1
                background = k.press(null, 999f)
                contentDescription = "Response details"
                setOnClickListener { toggleDetails(st, this) }
            }
            act("more", "More") { more(st, meta, question, stats?.backend != null, full) }
            if (!restored) { (meta.parent as? android.view.ViewGroup)?.removeView(meta); body.addView(meta, body.indexOfChild(actions) + 1, k.lp(WRAP_CONTENT)) }
            if (localTools.any { it.name.startsWith("web_") && it.ok }) body.addView(k.text("From the web · check the sources before relying on it", 10.5f, Pal.DIM).apply { setPadding(k.dp(8), 0, 0, 0) })
            if (stats?.backend != null && persona != Persona.ANALYST) body.addView(k.text("Educational only · not investment advice", 10.5f, Pal.DIM).apply { setPadding(k.dp(8), 0, 0, 0) })
            if (!restored) { actions.alpha = 0f; actions.animate().alpha(1f).setDuration(300).start() }
        }

        private var rated: Boolean? = null

        /** 👍 / 👎: kept on the phone; it tunes which memory notes are used, and a 👎 asks what went wrong. */
        private fun rate(good: Boolean, v: ImageView, other: ImageView?, memIds: Set<Int>) {
            if (rated == good) return
            rated = good
            Icons.tint(v, Pal.BLUE); other?.let { Icons.tint(it, Pal.alpha(Pal.TEXT, 0.62f)) }
            v.animate().scaleX(1.25f).scaleY(1.25f).setDuration(110).withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(160).start() }.start()
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            memoryStore.feedback(memIds, good)
            if (good) { toast("Thanks — noted on this phone"); return }
            val why = arrayOf("Wrong or made-up numbers", "Not what I asked", "Too long", "Too short", "Hard to follow")
            val lesson = arrayOf("Never state a figure the tools didn't produce; say what's missing instead.", "Answer exactly what was asked before adding anything else.",
                "Keep answers short — two to four sentences.", "Give a little more explanation when asked a why or how question.", "Use plain words and short sentences.")
            android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert).setTitle("What went wrong?").setItems(why) { _, i ->
                val learned = memoryStore.learn(listOf(Ace.Delta(Ace.Section.LESSON, lesson[i])))
                toast(if (learned > 0) "Thanks — the chat will remember that" else "Thanks — noted on this phone")
            }.setNegativeButton("Skip", null).show()
        }

        /** 🔊 reads this answer aloud with the best voice on the phone; tap again to stop. */
        private fun listen(text: String, v: ImageView) {
            if (Speaker.speakingFor(this)) { Speaker.stop(); return }
            Icons.tint(v, Pal.BLUE)
            Speaker.speak(context, VoiceFlow.speakable(text, 1500), owner = this) { post { Icons.tint(v, Pal.alpha(Pal.TEXT, 0.62f)) } }
        }

        /** ⋮: change the answer (shorter, longer, simpler) or open how it was made. */
        private fun more(st: TurnStats, meta: TextView, question: String, model: Boolean, full: String) {
            val items = buildList {
                if (model && question.isNotBlank()) { add("Shorter"); add("Longer"); add("Simpler") }
                add("How this was made"); add("Copy as plain text")
            }
            android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert).setItems(items.toTypedArray()) { _, i ->
                when (items[i]) {
                    "Shorter" -> modify("Shorter, please", "Rewrite your previous answer so it is much shorter: two or three sentences. Keep every figure exactly as it was.")
                    "Longer" -> modify("A bit more detail, please", "Expand your previous answer with a little more explanation. Keep every figure exactly as it was; add no new figures.")
                    "Simpler" -> modify("Simpler, please", "Rewrite your previous answer in plain, simple words for a beginner. Keep every figure exactly as it was.")
                    "How this was made" -> toggleDetails(st, meta)
                    else -> copy(Md.plain(full.ifBlank { lastDoneText }))
                }
            }.show()
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
            d.addView(k.caps("How this was made", Pal.BLUE)); d.addView(k.space(6))
            fun para(label: String, text: String) {
                d.addView(k.text(label, 11f, Pal.MUTED, 600).apply { letterSpacing = 0.04f; setPadding(0, k.dp(6), 0, k.dp(2)) })
                d.addView(k.text(text, 12.5f, Pal.TEXT).apply { setLineSpacing(0f, 1.3f) }, k.lp(MATCH_PARENT))
            }
            why?.let { para("WHY YOU'RE SEEING THIS", it) }
            st.tools.filter { it.ok }.distinctBy { it.name + it.argsJson }.forEach { t ->
                val m = Methods.of(t)
                para("HOW · ${m.title.uppercase()}", "Data: ${m.data}" + (m.asOf?.let { " (as of $it)" } ?: "") + "\nMethod: ${m.how}\nLimits: ${m.limits}")
            }
            if (st.backend != null) Methods.model(st.backend).let { m -> para("HOW · ${m.title.uppercase()}", "Data: ${m.data}\nMethod: ${m.how}\nLimits: ${m.limits}") }
            para("PRIVACY", if (st.tools.any { it.ok && (it.name.startsWith("web_") || Terminal_needsWeb(it.name)) }) "Worked out on this phone. The only thing sent was the lookup itself (topic words or a symbol) — never your holdings, amounts or chat." else "Worked out entirely on this phone. Nothing was sent anywhere.")
            d.addView(k.space(8)); d.addView(k.caps("Timing", Pal.MUTED)); d.addView(k.space(2))
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
            actions.addView(k.chip("Try again") { if (!phase.busy) retry() })
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
        performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun pick(l: List<String>) = l.random()
    private fun secs(ms: Long) = if (ms < 1000) "${ms} ms" else String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)

    companion object {
        private val THINKING = listOf("Working it out…", "Running the numbers…", "Checking the maths…")
        const val MIC_REQUEST = 7301
        const val NOTIFY_REQUEST = 7302
        const val WEB_EXPLAINER = "Allow Khayali Pulao to use the web?\n- Search DuckDuckGo for news, results and dates, and show its sources\n- Read the latest share price from Yahoo Finance (may be delayed)\n\nOnly the company name or search words are sent. Your holdings, quantities and amounts are stripped from every query and never leave the phone. You're asked once; the switch is in Settings."
    }

}
