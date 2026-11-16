package com.khayali.pulao

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.khayali.pulao.ai.AiEngine
import com.khayali.pulao.ai.ChatActivity
import com.khayali.pulao.ai.DownloadText
import com.khayali.pulao.ai.FlowingGradientText
import com.khayali.pulao.ai.GlowBorder
import com.khayali.pulao.ai.KiteMcpClient
import com.khayali.pulao.ai.ModelManager
import com.khayali.pulao.ai.ModelSpec
import com.khayali.pulao.ai.Persona

/** Home: bento dashboard + left navigation drawer. */
class MainActivity : Activity() {

    private lateinit var k: Kit
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private var drawerOpen = false
    private val tiles = mutableMapOf<String, Pair<TextView, TextView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        k = Kit(this)
        prefs = Prefs(this)
        build()
    }

    private fun build() {
        root = FrameLayout(this).apply { setBackgroundColor(Pal.BG) }
        val page = k.column()

        val bar = k.row().apply { setPadding(k.dp(6), k.dp(6), k.dp(12), k.dp(6)) }
        bar.addView(k.iconButton("☰", 22f, Pal.TEXT) { toggleDrawer(true) }.apply { contentDescription = "Menu" })
        bar.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, k.lp(k.dp(30), k.dp(30)).apply { marginStart = k.dp(4) })
        bar.addView(k.text("Khayali Pulao", 18f, Pal.TEXT, 500).apply { setPadding(k.dp(10), 0, 0, 0) }, k.lp(0, WRAP_CONTENT, 1f))
        page.addView(bar, k.lp(MATCH_PARENT))

        val grid = k.column().apply { setPadding(k.dp(14), k.dp(6), k.dp(14), k.dp(40)) }
        grid.addView(askHero(), k.lp(MATCH_PARENT))
        grid.addView(k.space(12))
        grid.addView(pairRow(
            tile("bubble", "🍚", "Kite bubble") { if (serviceEnabled()) openA11ySettings() else showDisclosure() },
            tile("kite", "🔗", "Kite account") { openChat() },
        ), k.lp(MATCH_PARENT))
        grid.addView(k.space(12))
        grid.addView(pairRow(
            tile("model", "🧠", "On-device AI") { openChat() },
            tile("persona", "🧭", "Persona") { openChat() },
        ), k.lp(MATCH_PARENT))
        grid.addView(k.space(12))
        grid.addView(wide("📈", "Open Kite", "Turn the bubble on and tap any holding") {
            packageManager.getLaunchIntentForPackage(KhayaliService.KITE)?.let(::startActivity)
        }, k.lp(MATCH_PARENT))
        grid.addView(k.space(12))
        grid.addView(pairRow(
            small("🎛️", "Demo", "No Kite needed") { startActivity(Intent(this, DemoActivity::class.java)) },
            small("⚙️", "Settings", "Rates (${Rates.AS_OF}), ladder") { startActivity(Intent(this, SettingsActivity::class.java)) },
        ), k.lp(MATCH_PARENT))
        grid.addView(k.space(12))
        grid.addView(k.card {
            addView(k.text(Privacy.TITLE, 15f, Pal.GREEN, 500))
            addView(k.space(6))
            addView(k.text("No uploads. Network only for the optional AI model download and, if you connect it, Zerodha's read-only Kite MCP. Never places orders.",
                13f, Pal.MUTED).apply { setLineSpacing(0f, 1.3f) })
        }.apply { background = k.rounded(Pal.SURFACE, 16f, Pal.alpha(Pal.GREEN, 0.35f)) }, k.lp(MATCH_PARENT))
        grid.addView(k.space(16))
        grid.addView(k.text("Not affiliated with Zerodha. ${Persona.DISCLAIMER}", 11f, Pal.DIM).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.35f)
        }, k.lp(MATCH_PARENT))

        page.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(grid) }, k.lp(MATCH_PARENT, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        scrim = View(this).apply { setBackgroundColor(0x99000000.toInt()); alpha = 0f; visibility = View.GONE; setOnClickListener { toggleDrawer(false) } }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        drawer = buildDrawer()
        root.addView(drawer, FrameLayout.LayoutParams(k.dp(300), MATCH_PARENT, Gravity.START))
        drawer.translationX = -k.dpf(300)

        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); insets
        }
        setContentView(root)
        refresh()
    }

    private fun askHero(): View {
        val glow = GlowBorder(k, 22f).apply { this.glow = 0.9f }
        val card = k.column().apply { background = glow; setPadding(k.dp(18), k.dp(18), k.dp(18), k.dp(18)); setOnClickListener { openChat() } }
        card.addView(k.text("✨", 26f))
        card.addView(k.space(8))
        card.addView(FlowingGradientText(this).apply { k.style(this, "What shall we cook today?", 22f, Pal.TEXT, 500) })
        card.addView(k.space(6))
        card.addView(k.text("Ask about your portfolio, any stock, or a what-if. Runs on your phone; every answer comes with exact charts.",
            13.5f, Pal.MUTED).apply { setLineSpacing(0f, 1.3f) })
        card.addView(k.space(14))
        card.addView(k.row().apply {
            background = k.rounded(Pal.BG, 999f, Pal.LINE); setPadding(k.dp(16), k.dp(12), k.dp(8), k.dp(12))
            addView(k.text("Ask Khayali Pulao", 15f, Pal.DIM), k.lp(0, WRAP_CONTENT, 1f))
            addView(k.text("↑", 16f, 0xFFFFFFFF.toInt(), 600).apply { gravity = Gravity.CENTER; background = k.rounded(Pal.BLUE, 999f) }, k.lp(k.dp(32), k.dp(32)))
        }, k.lp(MATCH_PARENT))
        ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 6000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { glow.angle = it.animatedValue as Float }; start()
        }
        return card
    }

    private fun pairRow(a: View, b: View) = k.row(Gravity.TOP).apply {
        addView(a, k.lp(0, WRAP_CONTENT, 1f)); addView(k.space(1, 12)); addView(b, k.lp(0, WRAP_CONTENT, 1f))
    }

    private fun tile(id: String, icon: String, title: String, onTap: () -> Unit): View {
        val value = k.text("", 16f, Pal.TEXT, 500).apply { maxLines = 1 }
        val sub = k.text("", 12f, Pal.MUTED).apply { maxLines = 2; setLineSpacing(0f, 1.25f) }
        tiles[id] = value to sub
        return k.column().apply {
            background = k.press(k.rounded(Pal.SURFACE, 16f), 16f)
            setPadding(k.dp(14), k.dp(14), k.dp(14), k.dp(14)); minimumHeight = k.dp(118)
            addView(k.row().apply { addView(k.text(icon, 18f)); addView(k.text("  $title", 12f, Pal.MUTED, 500)) })
            addView(k.space(10)); addView(value); addView(k.space(4)); addView(sub)
            setOnClickListener { onTap() }
        }
    }

    private fun wide(icon: String, title: String, sub: String, onTap: () -> Unit) = k.row().apply {
        background = k.press(k.rounded(Pal.alpha(Pal.BLUE, 0.16f), 16f, Pal.alpha(Pal.BLUE, 0.5f)), 16f)
        setPadding(k.dp(16), k.dp(16), k.dp(16), k.dp(16))
        addView(k.text(icon, 22f), k.lp(k.dp(38)))
        val c = k.column(); c.addView(k.text(title, 16f, Pal.TEXT, 500)); c.addView(k.text(sub, 12.5f, Pal.MUTED).apply { setPadding(0, k.dp(3), 0, 0) })
        addView(c, k.lp(0, WRAP_CONTENT, 1f)); addView(k.text("›", 24f, Pal.MUTED))
        setOnClickListener { onTap() }
    }

    private fun small(icon: String, title: String, sub: String, onTap: () -> Unit) = k.column().apply {
        background = k.press(k.rounded(Pal.SURFACE, 16f), 16f)
        setPadding(k.dp(14), k.dp(14), k.dp(14), k.dp(14))
        addView(k.text(icon, 20f)); addView(k.space(8))
        addView(k.text(title, 15f, Pal.TEXT, 500)); addView(k.text(sub, 12f, Pal.MUTED).apply { setPadding(0, k.dp(2), 0, 0) })
        setOnClickListener { onTap() }
    }

    private fun buildDrawer(): LinearLayout = k.column().apply {
        setBackgroundColor(Pal.SURFACE); elevation = k.dpf(16)
        setPadding(k.dp(12), k.dp(24), k.dp(12), k.dp(24))
        val head = k.row().apply { setPadding(k.dp(8), 0, 0, k.dp(18)) }
        head.addView(ImageView(this@MainActivity).apply { setImageResource(R.mipmap.ic_launcher) }, k.lp(k.dp(40), k.dp(40)))
        val ht = k.column().apply { setPadding(k.dp(12), 0, 0, 0) }
        ht.addView(k.text("Khayali Pulao", 17f, Pal.TEXT, 500)); ht.addView(k.text("v" + BuildConfig.VERSION_NAME, 12f, Pal.DIM))
        head.addView(ht)
        addView(head, k.lp(MATCH_PARENT))
        fun item(icon: String, label: String, onTap: () -> Unit) = addView(k.row().apply {
            background = k.press(null, 12f); setPadding(k.dp(12), k.dp(13), k.dp(12), k.dp(13))
            addView(k.text(icon, 18f), k.lp(k.dp(34))); addView(k.text(label, 15f, Pal.TEXT))
            setOnClickListener { toggleDrawer(false); onTap() }
        }, k.lp(MATCH_PARENT))
        item("🏠", "Home") { }
        item("✨", "Ask Khayali Pulao") { openChat() }
        item("📈", "Open Kite") { packageManager.getLaunchIntentForPackage(KhayaliService.KITE)?.let(::startActivity) }
        item("🎛️", "Demo") { startActivity(Intent(this@MainActivity, DemoActivity::class.java)) }
        addView(k.divider(8))
        item("⚙️", "Settings") { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        item("♿", "Kite bubble (accessibility)") { if (serviceEnabled()) openA11ySettings() else showDisclosure() }
        item("🧠", "AI model") { openChat() }
        addView(k.divider(8))
        item("🔒", "Privacy") { open(Links.PRIVACY) }
        item("🌐", "Website") { open(Links.SITE) }
        item("⭐", "Source on GitHub") { open(Links.REPO) }
        addView(View(this@MainActivity), k.lp(MATCH_PARENT, 0, 1f))
        addView(k.text("Not investment advice · not affiliated with Zerodha", 11f, Pal.DIM).apply { setPadding(k.dp(12), 0, 0, 0) })
    }

    private fun toggleDrawer(open: Boolean) {
        drawerOpen = open
        if (open) scrim.visibility = View.VISIBLE
        scrim.animate().alpha(if (open) 1f else 0f).setDuration(220).withEndAction { if (!open) scrim.visibility = View.GONE }.start()
        drawer.animate().translationX(if (open) 0f else -k.dpf(300)).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    @Deprecated("Back closes the drawer first")
    override fun onBackPressed() { if (drawerOpen) toggleDrawer(false) else super.onBackPressed() }

    private fun open(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    private fun openChat() = startActivity(Intent(this, ChatActivity::class.java))

    override fun onResume() { super.onResume(); if (::root.isInitialized) refresh() }

    private fun refresh() {
        fun set(id: String, v: String, sub: String, color: Int = Pal.TEXT) = tiles[id]?.let { (a, b) -> a.text = v; a.setTextColor(color); b.text = sub }
        if (serviceEnabled()) set("bubble", "On", "Shows over Kite. Tap to manage.", Pal.GREEN)
        else set("bubble", "Off", "Tap to turn on (read the disclosure first)", Pal.SAFFRON)
        when (val s = KiteMcpClient.get(this).state.value) {
            is KiteMcpClient.State.Connected -> set("kite", "Connected", (s.name?.let { "$it · " } ?: "") + "read-only", Pal.GREEN)
            else -> set("kite", "Not connected", "Connect in chat for real-portfolio answers", Pal.MUTED)
        }
        if (ModelManager.get(this).isReady()) set("model", "Ready", "${ModelSpec.DISPLAY_NAME} · ${DownloadText.bytes(ModelSpec.SIZE_BYTES)}" +
            ((AiEngine.state.value as? AiEngine.State.Ready)?.let { " · ${it.backend}" } ?: ""), Pal.GREEN)
        else set("model", "Not downloaded", "One-time ${DownloadText.bytes(ModelSpec.SIZE_BYTES)} download", Pal.SAFFRON)
        val p = Persona.of(prefs.personaName)
        set("persona", "${p.icon} ${p.title}", p.tagline)
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
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(Disclosure.TITLE)
            .setView(ScrollView(this).apply { addView(body) })
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
}
