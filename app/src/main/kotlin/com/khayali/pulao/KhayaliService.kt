package com.khayali.pulao

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.WindowManager.LayoutParams.MATCH_PARENT
import android.view.WindowManager.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Draws Khayali Pulao on top of Zerodha Kite.
 *
 * Bubble OFF: Kite works normally.
 * Bubble ON : a dim layer covers Kite. Taps never reach Kite (no accidental orders); we read the
 *             row under the finger and open the dream sheet. Vertical swipes scroll Kite's list —
 *             the only action this app ever sends to Kite.
 */
class KhayaliService : AccessibilityService() {

    companion object {
        const val KITE = "com.zerodha.kite3"
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var k: Kit
    private val main = Handler(Looper.getMainLooper())
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    private var bubble: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var trash: TextView? = null
    private var catcher: HighlightView? = null
    private var sheet: DreamSheet? = null
    private var pill: TextView? = null
    private var active = false
    private var dismissed = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if ((key == "bubbleSize" || key == null) && bubble != null) {
            removeBubble(); bubbleParams = null; showBubble()
        }
    }

    override fun onServiceConnected() {
        wm = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
        k = Kit(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Content-change events are subscribed only so Android keeps its node cache fresh
        // (otherwise taps after switching Kite tabs read the *previous* screen). Nothing to do.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        // Debounce bursts of window events, then look at which app is really on top.
        main.removeCallbacks(foregroundCheck)
        main.postDelayed(foregroundCheck, 120)
    }

    private val foregroundCheck = Runnable {
        val pkg = foregroundApp() ?: return@Runnable
        if (pkg == KITE) showBubble() else { dismissed = false; hideAll() }
    }

    /** Package of the top-most app window (ignores keyboards, system UI and our own overlays). */
    private fun foregroundApp(): String? {
        val apps = runCatching { windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val w = apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive } ?: apps.firstOrNull()
        return w?.root?.packageName?.toString()
    }

    override fun onInterrupt() = Unit

    /** Fold, unfold or rotate: every overlay was laid out for the old screen, so rebuild them. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (bubble == null && sheet == null) return
        hideAll()
        main.removeCallbacks(foregroundCheck); main.postDelayed(foregroundCheck, 200)
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        hideAll()
        super.onDestroy()
    }

    private fun haptic(v: View?, type: Int = HapticFeedbackConstants.KEYBOARD_TAP) {
        if (prefs.haptics) v?.performHapticFeedback(type)
    }

    // ---------------------------------------------------------------- windows

    private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun safeRemove(v: View?) {
        if (v?.isAttachedToWindow == true) runCatching { wm.removeView(v) }
    }

    private fun hideAll() {
        stopLive()
        sheet?.let { it.release(); safeRemove(it.view) }; sheet = null      // release() frees the chat's model session
        bubbleParams = null                                                // next time, start from the saved spot, not the parked one
        safeRemove(catcher); catcher = null
        safeRemove(pill); pill = null
        removeBubble()
        active = false
    }

    private fun removeBubble() {
        safeRemove(bubble); bubble = null
        safeRemove(trash); trash = null
    }

    // ----------------------------------------------------------------- bubble

    private fun bubbleSizePx() = k.dp(when (prefs.bubbleSize) { "S" -> 48; "L" -> 68; else -> 58 })

    private fun showBubble() {
        if (bubble != null || dismissed) return
        val size = bubbleSizePx()

        // Trash target is added first so it always sits *under* the bubble.
        val t = TextView(this).apply {
            text = "✕"; textSize = 22f; setTextColor(Pal.TEXT); gravity = Gravity.CENTER
            visibility = View.GONE
        }
        styleTrash(t, hot = false)
        val ts = k.dp(64)
        wm.addView(t, overlayParams(ts, ts, FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_IN_SCREEN).apply {
            x = (screenW - ts) / 2; y = screenH - ts - k.dp(80)
        })
        trash = t

        val p = bubbleParams ?: overlayParams(size, size, FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN).apply {
            x = prefs.bubbleX.takeIf { it >= 0 } ?: (screenW - size - k.dp(6))
            y = prefs.bubbleY.takeIf { it >= 0 } ?: (screenH * 0.62).toInt()
        }
        bubbleParams = p
        val b = ImageView(this).apply {
            setImageResource(R.drawable.bubble_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            elevation = k.dpf(10)
            scaleX = 0f; scaleY = 0f
        }
        styleBubble(b)
        b.setOnTouchListener(BubbleTouch(p))
        bubble = b
        wm.addView(b, p)
        b.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
    }

    /** Icon in a ring: thin grey ring when off, thick Kite-blue ring when on. */
    private fun styleBubble(b: ImageView) {
        val ring = if (active) k.dp(3) else k.dp(1.5f)
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Pal.BG)
            setStroke(ring, if (active) Pal.BLUE else Pal.LINE)
        }
        b.setPadding(ring, ring, ring, ring)
        b.alpha = if (active) 1f else 0.92f
    }

    private fun styleTrash(t: TextView, hot: Boolean) {
        t.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Pal.alpha(Pal.RED, if (hot) 0.55f else 0.22f))
            setStroke(k.dp(2), Pal.RED)
        }
    }

    /** Keeps the bubble above the catcher and the sheet. */
    private fun bubbleToFront() {
        val b = bubble ?: return
        safeRemove(b)
        wm.addView(b, bubbleParams)
    }

    private inner class BubbleTouch(val p: WindowManager.LayoutParams) : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var dragging = false
        private var longPressed = false
        private var hot = false
        private var v: View? = null
        private val slop = ViewConfiguration.get(this@KhayaliService).scaledTouchSlop
        private val longPress = Runnable {
            longPressed = true
            haptic(v, HapticFeedbackConstants.LONG_PRESS)
            openSheet(null, settings = true)
        }

        override fun onTouch(view: View, e: MotionEvent): Boolean {
            v = view
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y
                    dragging = false; longPressed = false; hot = false
                    view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                    main.postDelayed(longPress, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop && !longPressed) {
                        dragging = true
                        main.removeCallbacks(longPress)
                        trash?.apply { visibility = View.VISIBLE; alpha = 0f; animate().alpha(1f).setDuration(150).start() }
                    }
                    if (dragging) {
                        p.x = startX + dx.toInt(); p.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(view, p) }
                        val nowHot = overTrash(p)
                        if (nowHot != hot) {
                            hot = nowHot
                            trash?.let { styleTrash(it, hot); it.animate().scaleX(if (hot) 1.25f else 1f).scaleY(if (hot) 1.25f else 1f).setDuration(120).start() }
                            if (hot) haptic(view, HapticFeedbackConstants.CONTEXT_CLICK)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    trash?.visibility = View.GONE
                    when {
                        dragging && hot -> main.post { dismissBubble() }
                        dragging -> snap(view, p)
                        !longPressed && e.actionMasked == MotionEvent.ACTION_UP -> main.post { toggle() }
                    }
                }
            }
            return true
        }
    }

    private fun overTrash(p: WindowManager.LayoutParams): Boolean {
        val size = bubbleSizePx(); val ts = k.dp(64)
        val tx = screenW / 2f; val ty = screenH - ts / 2f - k.dp(80)
        return hypot(p.x + size / 2f - tx, p.y + size / 2f - ty) < k.dp(80)
    }

    private fun snap(v: View, p: WindowManager.LayoutParams) {
        val size = bubbleSizePx()
        val tx = if (p.x + size / 2 < screenW / 2) k.dp(6) else screenW - size - k.dp(6)
        p.y = p.y.coerceIn(k.dp(40), screenH - size - k.dp(60))
        ValueAnimator.ofInt(p.x, tx).apply {
            duration = 260
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { p.x = it.animatedValue as Int; runCatching { wm.updateViewLayout(v, p) } }
            start()
        }
        prefs.bubbleX = tx; prefs.bubbleY = p.y
    }

    private fun dismissBubble() {
        hideAll()
        dismissed = true
        showPill("🍚 hidden — reopen Kite to bring it back")
    }

    private fun toggle() {
        active = !active
        bubble?.let { haptic(it, HapticFeedbackConstants.CONTEXT_CLICK); styleBubble(it) }
        if (active) {
            addCatcher()
            bubbleToFront()
            showPill("Tap any stock to daydream  ·  swipe to scroll")
        } else {
            closeSheet()
            safeRemove(catcher); catcher = null
            showPill("Back to normal Kite")
        }
    }

    // ---------------------------------------------------------------- catcher

    private fun addCatcher() {
        if (catcher != null) return
        val v = HighlightView(this, k)
        v.setOnTouchListener(CatcherTouch())
        catcher = v
        wm.addView(v, overlayParams(MATCH_PARENT, MATCH_PARENT, FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN))
        // A sheet opened earlier (long-press → settings) must stay above the dim layer.
        sheet?.view?.let { sv ->
            val lp = sv.layoutParams
            safeRemove(sv); wm.addView(sv, lp)
        }
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(180).start()
    }

    private inner class CatcherTouch : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f
        private val slop = ViewConfiguration.get(this@KhayaliService).scaledTouchSlop * 3

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY }
                MotionEvent.ACTION_UP -> {
                    val dy = e.rawY - downY
                    if (abs(dy) > slop && abs(dy) > abs(e.rawX - downX)) {
                        scrollKite(downX.toInt(), downY.toInt(), forward = dy < 0)
                    } else {
                        onRowTap(e.rawX.toInt(), e.rawY.toInt())
                    }
                }
            }
            return true
        }
    }

    // ------------------------------------------------------- reading Kite

    private fun kiteRoot(): AccessibilityNodeInfo? =
        (windows.firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName == KITE } }
            ?: rootInActiveWindow?.takeIf { it.packageName == KITE })
            ?.also { it.refresh() }

    private inline fun walk(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(node) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            visit(n)
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let(stack::add)   // reverse push = document order
        }
    }

    private fun parseNode(n: AccessibilityNodeInfo) = KiteParser.parse(n.contentDescription) ?: KiteParser.parse(n.text)

    /** Smallest node under (x,y) whose description parses as a stock row. */
    private fun stockAt(x: Int, y: Int): Pair<Stock, Rect>? {
        val root = kiteRoot() ?: return null
        var best: Pair<Stock, Rect>? = null
        var bestArea = Long.MAX_VALUE
        walk(root) { n ->
            val r = Rect()
            n.getBoundsInScreen(r)
            if (!r.contains(x, y)) return@walk
            val s = parseNode(n) ?: return@walk
            val area = r.width().toLong() * r.height()
            if (area < bestArea) { best = s to r; bestArea = area }
        }
        return best
    }

    /**
     * While the bubble is on, Kite's *navigation tabs* still work: the bottom bar
     * ("Portfolio, tab"), Holdings/Positions ("Holdings\nTab 1 of 2") and watchlist groups
     * ("MW 7\nTab 7 of 7"). Nothing else is ever clicked — no Buy/Sell, no orders.
     */
    private fun passThroughNavigation(x: Int, y: Int): Boolean {
        val root = kiteRoot() ?: return false
        var tab: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        walk(root) { n ->
            val d = n.contentDescription?.toString() ?: return@walk
            if (!KiteParser.isNavigationTab(d)) return@walk
            val r = Rect(); n.getBoundsInScreen(r)
            val area = r.width().toLong() * r.height()
            if (r.contains(x, y) && area < bestArea) { tab = n; bestArea = area }
        }
        val t = tab ?: return false
        var clickable: AccessibilityNodeInfo? = t
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        val ok = (clickable ?: t).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (ok) { haptic(catcher); closeSheet() }
        return ok
    }

    /** All (description, bounds) pairs in Kite's tree, in order. */
    private fun descs(): List<Pair<String, Rect>> {
        val root = kiteRoot() ?: return emptyList()
        val out = mutableListOf<Pair<String, Rect>>()
        walk(root) { n ->
            val d = (n.contentDescription ?: n.text)?.toString() ?: return@walk
            val r = Rect(); n.getBoundsInScreen(r)
            out.add(d to r)
        }
        return out
    }

    /** Tap inside Kite's Invested / Current / P&L summary card → whole-portfolio dream. */
    private fun portfolioAt(x: Int, y: Int): Pair<Stock, Rect>? {
        val all = descs()
        val p = KiteParser.portfolio(all.map { it.first }) ?: return null
        val box = Rect()
        all.filter { (d, _) -> d == "Invested" || d == "Current" || d.startsWith("P&L") }.forEach { box.union(it.second) }
        if (box.isEmpty) return null
        val padded = Rect(box).apply { inset(-k.dp(16), -k.dp(16)) }
        return if (padded.contains(x, y)) p to padded else null
    }

    /** Same instrument, fresh numbers (for live updates). */
    private fun findStock(cur: Stock): Stock? {
        if (cur is Stock.Portfolio) return KiteParser.portfolio(descs().map { it.first })
        val root = kiteRoot() ?: return null
        var found: Stock? = null
        walk(root) { n ->
            if (found != null) return@walk
            val s = parseNode(n) ?: return@walk
            if (s.sameAs(cur)) found = s
        }
        return found
    }

    private fun scrollKite(x: Int, y: Int, forward: Boolean) {
        val root = kiteRoot() ?: return
        var target: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val r = Rect()
        walk(root) { n ->
            if (!n.isScrollable) return@walk
            n.getBoundsInScreen(r)
            val area = r.width().toLong() * r.height()
            if (r.contains(x, y) && area < bestArea) { target = n; bestArea = area }
        }
        target?.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
        )
    }

    private fun debugDump(x: Int, y: Int) {
        val ws = windows
        android.util.Log.d("Khayali", "tap $x,$y windows=${ws.size} " + ws.joinToString { "${it.type}:${it.root?.packageName}" } + " active=${rootInActiveWindow?.packageName}")
        val root = kiteRoot() ?: run { android.util.Log.d("Khayali", "no kite root"); return }
        var n = 0
        walk(root) { node ->
            n++
            val r = Rect(); node.getBoundsInScreen(r)
            val d = node.contentDescription ?: node.text
            if (r.contains(x, y) && d != null) android.util.Log.d("Khayali", "hit $r parsed=${KiteParser.parse(d)} raw=[" + d.toString().map { c -> if (c.code < 32 || c.code > 126) "\\u%04x".format(c.code) else c.toString() }.joinToString("") + "]")
        }
        android.util.Log.d("Khayali", "nodes=$n")
    }

    // ------------------------------------------------------------------ sheet

    private fun onRowTap(x: Int, y: Int) {
        val hit = stockAt(x, y) ?: portfolioAt(x, y)
        if (BuildConfig.DEBUG) android.util.Log.d("Khayali", "tap $x,$y -> ${hit?.first}")
        if (hit == null) {
            if (passThroughNavigation(x, y)) return
            catcher?.highlight(null)
            showPill("Not a stock — tap a holding, a watchlist stock or the Invested card")
            return
        }
        haptic(catcher)
        catcher?.highlight(hit.second)
        openSheet(hit.first, settings = false)
    }

    private fun openSheet(stock: Stock?, settings: Boolean) {
        sheet?.let { s ->
            if (stock != null) s.setStock(stock)
            if (settings) s.openSettings()
            return
        }
        val s = DreamSheet(this, prefs, stock, settings, onClose = ::closeSheet)
        sheet = s
        val h = (screenH * 0.76).toInt()
        wm.addView(s.view, overlayParams(MATCH_PARENT, h, FLAG_NOT_TOUCH_MODAL).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        })
        s.view.translationY = h.toFloat()
        s.view.animate().translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator(2.5f)).start()
        bubbleToFront()
        parkBubble(sheetTop = screenH - h)
        startLive()
    }

    /** Glide the bubble to just above the sheet so it never covers the numbers. */
    private fun parkBubble(sheetTop: Int) {
        val size = bubbleSizePx()
        moveBubble(screenW - size - k.dp(10), (sheetTop - size - k.dp(14)).coerceAtLeast(k.dp(40)))
    }

    private fun moveBubble(tx: Int, ty: Int) {
        val b = bubble ?: return
        val p = bubbleParams ?: return
        val sx = p.x; val sy = p.y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                val f = it.animatedValue as Float
                p.x = (sx + (tx - sx) * f).toInt(); p.y = (sy + (ty - sy) * f).toInt()
                runCatching { wm.updateViewLayout(b, p) }
            }
            start()
        }
    }

    private fun closeSheet() {
        val s = sheet ?: return
        sheet = null
        s.release()
        stopLive()
        catcher?.highlight(null)
        // Return the bubble to where the user left it.
        val size = bubbleSizePx()
        moveBubble(
            prefs.bubbleX.takeIf { it >= 0 } ?: (screenW - size - k.dp(6)),
            prefs.bubbleY.takeIf { it >= 0 } ?: (screenH * 0.62).toInt(),
        )
        s.view.animate().translationY(s.view.height.toFloat()).setDuration(200)
            .withEndAction { safeRemove(s.view) }.start()
    }

    // ------------------------------------------------------------------- live

    private val liveTick = object : Runnable {
        override fun run() {
            val s = sheet ?: return
            val cur = s.stock
            if (prefs.live && cur != null) findStock(cur)?.let { if (it != cur) s.setStock(it, fromLive = true) }
            main.postDelayed(this, 1500)
        }
    }

    private fun startLive() { main.removeCallbacks(liveTick); main.postDelayed(liveTick, 1500) }
    private fun stopLive() = main.removeCallbacks(liveTick)

    // ------------------------------------------------------------------- pill

    private fun showPill(msg: String) {
        safeRemove(pill)
        val t = k.text(msg, 14f, Pal.TEXT, 400).apply {
            background = k.rounded(Pal.LINE, 6f)
            setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(12))
            elevation = k.dpf(8)
            alpha = 0f; translationY = -k.dpf(12)
        }
        wm.addView(t, overlayParams(WRAP_CONTENT, WRAP_CONTENT, FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_IN_SCREEN).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = k.dp(120)
        })
        pill = t
        t.animate().alpha(1f).translationY(0f).setDuration(180).start()
        main.postDelayed({
            if (pill === t) t.animate().alpha(0f).setDuration(250).withEndAction {
                safeRemove(t); if (pill === t) pill = null
            }.start()
        }, 2400)
    }
}
