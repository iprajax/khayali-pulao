package com.khayali.pulao

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView

/** All settings on their own screen (opened from the drawer / home). */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val k = Kit(this)
        val prefs = Prefs(this)
        val col = k.column().apply { setPadding(k.dp(14), k.dp(4), k.dp(14), k.dp(48)) }
        val bar = k.row().apply { setPadding(k.dp(4), k.dp(6), k.dp(8), k.dp(6)) }
        bar.addView(k.iconButton("←", 22f, Pal.TEXT) { finish() }.apply { contentDescription = "Back" })
        bar.addView(k.text("Settings", 18f, Pal.TEXT, 500).apply { setPadding(k.dp(8), 0, 0, 0) }, k.lp(0, WRAP_CONTENT, 1f))
        fun render() { col.removeAllViews(); col.addView(SettingsPanel.build(this, prefs, onChange = {}, onReset = { recreate() }), k.lp(MATCH_PARENT)) }
        render()
        val root = k.column().apply { setBackgroundColor(Pal.BG) }
        root.addView(bar, k.lp(MATCH_PARENT))
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }, k.lp(MATCH_PARENT, 0, 1f))
        root.setOnApplyWindowInsetsListener { v, insets ->
            com.khayali.pulao.Insets.pad(v, insets); insets
        }
        setContentView(root)
    }
}
