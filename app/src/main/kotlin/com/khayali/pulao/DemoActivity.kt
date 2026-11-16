package com.khayali.pulao

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Try Khayali Pulao without Kite: a fictional portfolio and watchlist.
 * Every name and number here is made up — safe for screenshots and demos.
 */
class DemoActivity : Activity() {

    private lateinit var k: Kit
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private var sheet: DreamSheet? = null

    private val portfolio = Stock.Portfolio(invested = 3_00_000.0, current = 3_62_500.0)
    private val holding = Stock.Holding("INFRA", qty = 250.0, avg = 1200.0, invested = 3_00_000.0, ltp = 1450.0)
    private val watch = listOf(
        Stock.Quote("BALLIA PORTS", "NSE", 1893.00) to "+1.25%",
        Stock.Quote("JHUMRI POWER", "NSE", 205.29) to "−0.18%",
        Stock.Quote("CHAI CO", "NSE", 658.20) to "+1.77%",
        Stock.Quote("TELAIYA MOTORS", "BSE", 428.25) to "−1.22%",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        k = Kit(this)
        prefs = Prefs(this)
        root = FrameLayout(this).apply { setBackgroundColor(Pal.BG) }

        val col = k.column().apply { setPadding(k.dp(16), k.dp(16), k.dp(16), k.dp(96)) }
        col.addView(k.tag("DEMO · FICTIONAL DATA", Pal.SAFFRON))
        col.addView(k.space(10))
        col.addView(k.text("Tap anything to daydream", 20f, Pal.TEXT, 500))
        col.addView(k.space(4))
        col.addView(k.text("In real use, a bubble does this on top of Kite.", 13f, Pal.MUTED))
        col.addView(k.space(16))

        // Portfolio card
        val (vals, pairBox) = k.pair("Invested", "Current")
        vals.first.text = Fmt.amount(portfolio.invested)
        vals.second.text = Fmt.amount(portfolio.current)
        col.addView(k.card {
            addView(pairBox, k.lp(MATCH_PARENT))
            addView(k.divider(12))
            val r = k.row()
            r.addView(k.label("P&L"), k.lp(0, WRAP_CONTENT, 1f))
            r.addView(k.text("${Fmt.signed(portfolio.pnl)}  (${Fmt.pct1(portfolio.pnlPct)})", 16f, Pal.GREEN))
            addView(r, k.lp(MATCH_PARENT))
            background = k.press(k.rounded(Pal.LINE, 8f), 8f)
            setOnClickListener { open(portfolio) }
        }, k.lp(MATCH_PARENT))
        col.addView(k.space(20))

        col.addView(k.label("Holdings"))
        col.addView(k.space(6))
        col.addView(row(holding.name, "Qty. 250  •  Avg. ${Fmt.price(holding.avg)}",
            Fmt.signed(holding.pnl), "LTP ${Fmt.price(holding.ltp)}", Pal.GREEN) { open(holding) })
        col.addView(k.space(20))

        col.addView(k.label("Watchlist"))
        col.addView(k.space(6))
        for ((q, chg) in watch) {
            col.addView(row(q.name, q.exchange, Fmt.price(q.ltp), chg,
                if (chg.startsWith("+")) Pal.GREEN else Pal.RED) { open(q) })
        }

        root.addView(ScrollView(this).apply { addView(col) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Decorative bubble, like the real overlay.
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.bubble_icon)
            background = k.rounded(Pal.BG, 999f, Pal.BLUE, 3f)
            setPadding(k.dp(3), k.dp(3), k.dp(3), k.dp(3))
            elevation = k.dpf(8)
        }, FrameLayout.LayoutParams(k.dp(58), k.dp(58), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = k.dp(10); bottomMargin = k.dp(28)
        })
        // Edge-to-edge (targetSdk 35): keep content clear of the status and navigation bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(root)
    }

    private fun row(title: String, sub: String, right: String, rightSub: String, color: Int, onTap: () -> Unit): View {
        val r = k.row().apply {
            setPadding(k.dp(4), k.dp(14), k.dp(4), k.dp(14))
            background = k.press(null, 6f)
            setOnClickListener { onTap() }
        }
        val l = k.column()
        l.addView(k.text(title, 16f, Pal.TEXT))
        l.addView(k.text(sub, 12f, Pal.MUTED).apply { setPadding(0, k.dp(4), 0, 0) })
        r.addView(l, k.lp(0, WRAP_CONTENT, 1f))
        val rc = k.column().apply { gravity = Gravity.END }
        rc.addView(k.text(right, 16f, color).apply { gravity = Gravity.END })
        rc.addView(k.text(rightSub, 12f, Pal.MUTED).apply { gravity = Gravity.END; setPadding(0, k.dp(4), 0, 0) })
        r.addView(rc)
        val wrap = k.column()
        wrap.addView(r, k.lp(MATCH_PARENT))
        wrap.addView(View(this).apply { setBackgroundColor(Pal.LINE) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
        return wrap
    }

    private fun open(s: Stock) {
        sheet?.let { it.setStock(s); return }
        val h = ((root.height - root.paddingTop - root.paddingBottom) * 0.80).toInt()
        val sh = DreamSheet(this, prefs, s, startInSettings = false, onClose = ::close, live = false)
        sheet = sh
        root.addView(sh.view, FrameLayout.LayoutParams(MATCH_PARENT, h, Gravity.BOTTOM))
        setSheetBack(true)
        sh.view.translationY = h.toFloat()
        sh.view.animate().translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator(2.5f)).start()
        sh.view.requestFocus()
    }

    private fun close() {
        val s = sheet ?: return
        sheet = null
        s.release()
        setSheetBack(false)
        s.view.animate().translationY(s.view.height.toFloat()).setDuration(200).withEndAction { root.removeView(s.view) }.start()
    }

    /** While a sheet is open, Back closes it (predictive-back API on 33+, legacy below). */
    private val sheetBack: Any? =
        if (android.os.Build.VERSION.SDK_INT >= 33) android.window.OnBackInvokedCallback { close() } else null

    private fun setSheetBack(enabled: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val cb = sheetBack as android.window.OnBackInvokedCallback
        if (enabled) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
    }

    @Deprecated("Legacy back for Android 12 and below")
    override fun onBackPressed() {
        if (sheet != null) close() else super.onBackPressed()
    }
}
