package com.khayali.pulao.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.khayali.pulao.Kit
import com.khayali.pulao.Pal
import com.khayali.pulao.ai.data.Artifacts
import com.khayali.pulao.ai.data.KnowledgePack
import com.khayali.pulao.ai.data.MarketPack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Models & data: everything the app downloads, in one place — the AI model, market history and the
 * knowledge bank — each with its state, size, what needs it, live progress and its actions. The app
 * itself stays small; these arrive only when asked for.
 */
class ArtifactsActivity : Activity() {
    private lateinit var k: Kit
    private lateinit var col: LinearLayout
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        k = Kit(this)
        col = k.column().apply { setPadding(k.dp(14), k.dp(4), k.dp(14), k.dp(48)) }
        val bar = k.row().apply { setPadding(k.dp(4), k.dp(6), k.dp(8), k.dp(6)) }
        bar.addView(k.iconButton("←", 22f, Pal.TEXT) { finish() }.apply { contentDescription = "Back" })
        bar.addView(k.text("Models & data", 18f, Pal.TEXT, 500).apply { setPadding(k.dp(8), 0, 0, 0) }, k.lp(0, WRAP_CONTENT, 1f))
        val root = k.column().apply { setBackgroundColor(Pal.BG) }
        root.addView(bar, k.lp(MATCH_PARENT))
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }, k.lp(MATCH_PARENT, 0, 1f))
        root.setOnApplyWindowInsetsListener { v, insets -> com.khayali.pulao.Insets.pad(v, insets); insets }
        setContentView(root)
        val market = MarketPack.get(this); val kb = KnowledgePack.get(this); val model = ModelManager.get(this)
        scope.launch { combine(market.state, kb.state, model.state) { _, _, _ -> }.collect { render() } }
    }

    override fun onResume() { super.onResume(); MarketPack.get(this).refresh(); KnowledgePack.get(this).refresh(); ModelManager.get(this).refresh() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun render() {
        col.removeAllViews()
        col.addView(k.text("The app stays small: these are downloaded only when you ask, straight to this phone, and checked before use. Nothing here is uploaded.",
            13f, Pal.MUTED).apply { setLineSpacing(0f, 1.35f); setPadding(k.dp(4), k.dp(4), k.dp(4), k.dp(12)) })
        val hub = com.khayali.pulao.ai.data.AndroidArtifactHub(this)
        for (it in hub.items()) {
            col.addView(k.card {
                val head = k.row()
                head.addView(k.text(it.name, 15f, Pal.TEXT, 500), k.lp(0, WRAP_CONTENT, 1f))
                val tone = when (it.state) { is Artifacts.State.Ready -> Pal.GREEN; is Artifacts.State.Failed -> Pal.RED; is Artifacts.State.Stale -> Pal.SAFFRON; else -> Pal.BLUE }
                head.addView(k.tag(Artifacts.short(it.state), tone))
                addView(head, k.lp(MATCH_PARENT)); addView(k.space(6))
                addView(k.text(it.detail, 12.5f, Pal.MUTED).apply { setLineSpacing(0f, 1.3f) })
                addView(k.space(6))
                addView(k.text(Artifacts.describe(it.state) + (if (it.bytes >= 1_000_000) " · ${it.bytes / 1_000_000} MB" else if (it.bytes > 0) " · ${it.bytes / 1000} KB" else ""), 12.5f, Pal.TEXT))
                val p = progress(it.state)
                if (p != null) {
                    addView(k.space(8))
                    addView(ProgressBar(this@ArtifactsActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 1000; isIndeterminate = p < 0; if (p >= 0) progress = p
                        progressTintList = ColorStateList.valueOf(Pal.BLUE); indeterminateTintList = ColorStateList.valueOf(Pal.BLUE)
                        progressBackgroundTintList = ColorStateList.valueOf(Pal.LINE)
                    }, k.lp(MATCH_PARENT, k.dp(6)))
                }
                if (it.neededBy.isNotEmpty()) addView(k.text("Needed by: " + it.neededBy.joinToString("  "), 11.5f, Pal.DIM).apply { setPadding(0, k.dp(8), 0, 0) })
                val buttons = actions(it.id, it.state)
                if (buttons.isNotEmpty()) {
                    addView(k.space(10))
                    buttons.forEachIndexed { i, (label, go) -> addView(k.button(label, primary = i == 0) { go() }, k.lp(MATCH_PARENT).apply { topMargin = if (i > 0) k.dp(6) else 0 }) }
                }
            }, k.lp(MATCH_PARENT).apply { bottomMargin = k.dp(12) })
        }
    }

    private fun progress(s: Artifacts.State): Int? = when (s) {
        is Artifacts.State.Downloading -> if (s.total > 0) (s.done * 1000 / s.total).toInt() else -1
        is Artifacts.State.Updating -> if (s.total > 0) s.done * 1000 / s.total else -1
        is Artifacts.State.Building -> -1; Artifacts.State.Verifying -> -1
        else -> null
    }

    private fun confirm(msg: String, go: () -> Unit) = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setMessage(msg).setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> go(); render() }.show()

    private fun actions(id: String, s: Artifacts.State): List<Pair<String, () -> Unit>> {
        val busy = s is Artifacts.State.Downloading || s is Artifacts.State.Updating || s is Artifacts.State.Verifying || s is Artifacts.State.Building
        return when (id) {
            "market" -> { val m = MarketPack.get(this)
                when {
                    busy -> listOf("Stop" to { m.cancel() })
                    s is Artifacts.State.NotInstalled || s is Artifacts.State.Failed -> MarketPack.Tier.entries.map { t -> "Download ${t.label} (~${t.approxMb} MB)" to { m.tier = t; m.install(); Unit } }
                    else -> listOfNotNull(
                        (if (s is Artifacts.State.Stale) "Update to the latest day" else "Check for new days") to { m.install(); Unit },
                        if (m.tier != MarketPack.Tier.Y5) "Extend to 5 years (better backtests)" to { m.tier = MarketPack.Tier.Y5; m.install(); Unit } else null,
                        "Delete" to { confirm("Delete your market history? You can download it again.") { m.delete() } })
                } }
            "knowledge" -> { val kb = KnowledgePack.get(this)
                when { busy -> emptyList(); kb.ready() -> listOf("Delete" to { confirm("Delete the knowledge bank?") { kb.delete() } }); else -> listOf("Download (${kb.spec.bytes / 1000} KB)" to { kb.install() }) } }
            "model" -> listOf("Open model settings" to { finish(); runCatching { startActivity(android.content.Intent(this, com.khayali.pulao.SettingsActivity::class.java)) }; Unit })
            "memory" -> listOf("Open memory" to { startActivity(android.content.Intent(this, MemoryActivity::class.java)) })
            else -> emptyList()
        }
    }
}
