package com.khayali.pulao

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Home screen in Kite's look: status, what you can dream, and every setting. */
class MainActivity : Activity() {

    private lateinit var k: Kit
    private lateinit var prefs: Prefs
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var statusBtn: TextView
    private lateinit var statusCard: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        k = Kit(this)
        prefs = Prefs(this)
        build()
    }

    private fun build() {
        val col = k.column().apply { setPadding(k.dp(16), k.dp(52), k.dp(16), k.dp(64)) }

        // Header — like Kite's account screen
        val head = k.row()
        head.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, k.lp(k.dp(56), k.dp(56)))
        head.addView(k.space(1, 14))
        val titles = k.column()
        titles.addView(k.text("Khayali Pulao", 22f, Pal.TEXT, 500))
        titles.addView(k.space(4))
        titles.addView(k.text("Daydream calculator for Kite", 13f, Pal.MUTED))
        head.addView(titles, k.lp(0, WRAP_CONTENT, 1f))
        col.addView(head, k.lp(MATCH_PARENT))
        col.addView(k.space(18))
        col.addView(k.text(
            "Khayali pulao (ख़याली पुलाव) — literally “imaginary pulao”: rice you cook only in your head. " +
                "The Hindi way of saying daydreaming. This is that, for your portfolio.",
            14f, Pal.MUTED,
        ).apply { setLineSpacing(0f, 1.35f) })
        col.addView(k.space(20))

        // Status
        statusCard = k.column().apply { setPadding(k.dp(16), k.dp(14), k.dp(16), k.dp(16)) }
        statusTitle = k.text("", 16f, Pal.TEXT, 500)
        statusSub = k.text("", 13f, Pal.MUTED).apply { setPadding(0, k.dp(6), 0, k.dp(14)); setLineSpacing(0f, 1.3f) }
        statusBtn = k.button("", primary = false) { if (serviceEnabled()) openA11ySettings() else showDisclosure() }
        statusCard.addView(statusTitle); statusCard.addView(statusSub); statusCard.addView(statusBtn, k.lp(MATCH_PARENT))
        col.addView(statusCard, k.lp(MATCH_PARENT))
        col.addView(k.space(12))
        col.addView(k.button("Open Kite", primary = true) {
            packageManager.getLaunchIntentForPackage(KhayaliService.KITE)?.let(::startActivity)
        }, k.lp(MATCH_PARENT))
        col.addView(k.space(10))
        col.addView(askCard(), k.lp(MATCH_PARENT))
        col.addView(k.space(10))
        col.addView(k.button("Try the demo — no Kite needed", primary = false) {
            startActivity(Intent(this, DemoActivity::class.java))
        }, k.lp(MATCH_PARENT))
        col.addView(k.space(16))

        // Privacy promise
        col.addView(k.card {
            addView(k.text(Privacy.TITLE, 16f, Pal.GREEN, 500))
            addView(k.space(8))
            for (pt in Privacy.POINTS) {
                val r = k.row(Gravity.TOP).apply { setPadding(0, k.dp(5), 0, k.dp(5)) }
                r.addView(k.text("•", 14f, Pal.GREEN), k.lp(k.dp(16)))
                r.addView(k.text(pt, 13f, Pal.MUTED).apply { setLineSpacing(0f, 1.3f) }, k.lp(0, WRAP_CONTENT, 1f))
                addView(r, k.lp(MATCH_PARENT))
            }
        }.apply { background = k.rounded(Pal.SURFACE, 8f, Pal.alpha(Pal.GREEN, 0.4f)) }, k.lp(MATCH_PARENT))
        col.addView(k.space(24))

        // What you can dream
        col.addView(k.text("Ways to daydream", 13f, Pal.MUTED, 500).apply { setPadding(k.dp(2), 0, 0, k.dp(10)) })
        col.addView(k.card {
            feature(this, "vs FD & Spend it", "Did you beat an FD, gold, Nifty, inflation? What your profit buys, in hand after tax."); addView(k.divider(4)); feature(this, "Move %", "What if it goes +0.5%, +5%, +15%, 2×? Ladder, slider, target price.")
            addView(k.divider(4))
            feature(this, "Goal", "“I want ₹2 Cr.” How much it must rise, and how many years at 8–25% a year.")
            addView(k.divider(4))
            feature(this, "Years", "Compounding: what today's value becomes in 1 to 30 years.")
            addView(k.divider(4))
            feature(this, "Regret", "Agar tab liya hota — what if you'd bought cheaper?")
            addView(k.divider(4))
            feature(this, "Whole portfolio", "Tap Kite's Invested / Current card to daydream everything at once.")
            addView(k.divider(4))
            feature(this, "Pulao maths & masala", "Gains in cutting chais, bhainses and plots in Ballia & Jhumri Telaiya — with FD wale uncle's commentary.")
        }, k.lp(MATCH_PARENT))
        col.addView(k.space(16))
        col.addView(k.card {
            addView(k.text("How to use", 13f, Pal.MUTED, 500)); addView(k.space(8))
            for ((i, s) in listOf(
                "Open Kite — the bubble floats on top. Drag it anywhere.",
                "Tap the bubble: blue ring = on. Kite dims slightly.",
                "Tap a holding, a watchlist stock, or the Invested card.",
                "Tap the bubble again for normal Kite. Long-press for settings, drag onto ✕ to hide.",
            ).withIndex()) {
                val r = k.row(Gravity.TOP).apply { setPadding(0, k.dp(6), 0, k.dp(6)) }
                r.addView(k.text("${i + 1}", 14f, Pal.BLUE, 500), k.lp(k.dp(22)))
                r.addView(k.text(s, 14f, Pal.TEXT).apply { setLineSpacing(0f, 1.3f) }, k.lp(0, WRAP_CONTENT, 1f))
                addView(r, k.lp(MATCH_PARENT))
            }
            addView(k.space(6))
            addView(k.text("While the bubble is on, taps never reach Kite — no accidental orders. It only reads Kite's screen.",
                12f, Pal.DIM).apply { setLineSpacing(0f, 1.3f) })
        }, k.lp(MATCH_PARENT))
        col.addView(k.space(24))

        col.addView(k.text("Settings", 13f, Pal.MUTED, 500).apply { setPadding(k.dp(2), 0, 0, k.dp(10)) })
        col.addView(SettingsPanel.build(this, prefs, onChange = {}, onReset = { build() }), k.lp(MATCH_PARENT))
        col.addView(k.space(24))
        col.addView(k.text("${Privacy.BADGE}\nNot affiliated with Zerodha. A daydream, not investment advice.", 11f, Pal.DIM).apply {
            setLineSpacing(0f, 1.4f)
            gravity = Gravity.CENTER
        }, k.lp(MATCH_PARENT))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Pal.BG)
            isVerticalScrollBarEnabled = false
            addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        updateStatus()
    }

    /** Gemini-style entry card for the on-device chat. */
    private fun askCard(): android.view.View {
        val glow = com.khayali.pulao.ai.GlowBorder(k, 20f).apply { this.glow = 0.9f }
        val card = k.row().apply {
            background = glow
            setPadding(k.dp(16), k.dp(16), k.dp(16), k.dp(16))
            setOnClickListener { startActivity(Intent(this@MainActivity, com.khayali.pulao.ai.ChatActivity::class.java)) }
        }
        card.addView(k.text("✨", 24f), k.lp(k.dp(40)))
        val c = k.column()
        c.addView(com.khayali.pulao.ai.FlowingGradientText(this).apply { k.style(this, "Ask Khayali Pulao", 17f, Pal.TEXT, 600) })
        c.addView(k.text("On-device AI chat · Gemma 4 · uses the app's calculators", 12f, Pal.MUTED).apply { setPadding(0, k.dp(3), 0, 0) })
        card.addView(c, k.lp(0, WRAP_CONTENT, 1f))
        card.addView(k.text("›", 24f, Pal.MUTED))
        android.animation.ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 6000; repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { glow.angle = it.animatedValue as Float }; start()
        }
        return card
    }

    private fun feature(parent: LinearLayout, title: String, sub: String) {
        val c = k.column().apply { setPadding(0, k.dp(8), 0, k.dp(8)) }
        c.addView(k.text(title, 15f, Pal.TEXT, 500))
        c.addView(k.text(sub, 13f, Pal.MUTED).apply { setPadding(0, k.dp(4), 0, 0); setLineSpacing(0f, 1.3f) })
        parent.addView(c, k.lp(MATCH_PARENT))
    }

    private fun openA11ySettings() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /**
     * Google Play "prominent disclosure" for a non-accessibility-tool use of the Accessibility API:
     * shown in-app, before the user is sent to enable the service, with explicit consent.
     */
    private fun showDisclosure() {
        val body = k.column().apply { setPadding(k.dp(24), k.dp(8), k.dp(24), k.dp(4)) }
        fun para(title: String, text: String) {
            body.addView(k.text(title, 15f, Pal.TEXT, 500).apply { setPadding(0, k.dp(12), 0, k.dp(4)) })
            body.addView(k.text(text, 14f, Pal.MUTED).apply { setLineSpacing(0f, 1.3f) })
        }
        body.addView(k.text(Disclosure.INTRO, 14f, Pal.MUTED).apply { setLineSpacing(0f, 1.3f) })
        for ((t, x) in Disclosure.SECTIONS) para(t, x)
        val scroll = ScrollView(this).apply { addView(body) }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(Disclosure.TITLE)
            .setView(scroll)
            .setCancelable(true)
            .setNegativeButton("No thanks") { d, _ ->
                d.dismiss()
                android.widget.Toast.makeText(this, "Without it, the bubble can't work on Kite. You can still try the demo.", android.widget.Toast.LENGTH_LONG).show()
            }
            .setPositiveButton("Agree & open settings") { _, _ -> openA11ySettings() }
            .show()
    }

    private fun serviceEnabled(): Boolean {
        val me = ComponentName(this, KhayaliService::class.java).flattenToString()
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { it.equals(me, ignoreCase = true) } == true
    }

    override fun onResume() {
        super.onResume()
        if (::statusTitle.isInitialized) updateStatus()
    }

    private fun updateStatus() {
        val enabled = serviceEnabled()
        val c = if (enabled) Pal.GREEN else Pal.SAFFRON
        statusCard.background = k.rounded(Pal.SURFACE, 8f, Pal.alpha(c, 0.5f))
        statusTitle.text = if (enabled) "● Ready" else "● One step left"
        statusTitle.setTextColor(c)
        statusSub.text = if (enabled) "The bubble appears whenever Kite is open."
        else "Turn on Khayali Pulao under Accessibility → Installed apps. It only reads Kite's screen."
        statusBtn.text = if (enabled) "Accessibility settings" else "Turn it on"
    }
}
