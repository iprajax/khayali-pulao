package com.khayali.pulao.ai

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import com.khayali.pulao.Insets
import com.khayali.pulao.Kit
import com.khayali.pulao.Pal

/**
 * Everything the chat remembers, in the open: each note with how often it helped, a delete
 * button per note, a master switch, and "erase all". Nothing here ever leaves the phone.
 */
class MemoryActivity : Activity() {
    private lateinit var k: Kit
    private lateinit var col: LinearLayout
    private val store by lazy { MemoryStore.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        k = Kit(this)
        col = k.column().apply { setPadding(k.dp(14), k.dp(4), k.dp(14), k.dp(48)) }
        val bar = k.row().apply { setPadding(k.dp(4), k.dp(6), k.dp(8), k.dp(6)) }
        bar.addView(k.iconButton("←", 22f, Pal.TEXT) { finish() }.apply { contentDescription = "Back" })
        bar.addView(k.text("Memory", 18f, Pal.TEXT, 500).apply { setPadding(k.dp(8), 0, 0, 0) }, k.lp(0, WRAP_CONTENT, 1f))
        val root = k.column().apply { setBackgroundColor(Pal.BG) }
        root.addView(bar, k.lp(MATCH_PARENT))
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }, k.lp(MATCH_PARENT, 0, 1f))
        root.setOnApplyWindowInsetsListener { v, insets -> com.khayali.pulao.Insets.pad(v, insets); insets }
        setContentView(root)
        render()
    }

    private fun render() {
        col.removeAllViews()
        col.addView(k.section("How memory works") {
            addView(k.text("After each answer the chat reflects on what happened and keeps short notes: things you told it about yourself " +
                "(goal, horizon, how you like answers) and lessons from its own mistakes. Notes that keep proving useful rise; ones that " +
                "lead to a redo fade out. Notes stay in a private file on this phone — never uploaded, never part of a web search.", 13f, Pal.MUTED).apply { setLineSpacing(0f, 1.35f) })
            addView(k.space(8))
            addView(k.switchRow("Remember across chats", "Off: nothing new is saved and nothing is used", store.enabled) { store.enabled = it })
            addView(k.space(10))
            addView(k.text("Add a note", 13f, Pal.MUTED, 500)); addView(k.space(6))
            var draft = ""
            addView(k.input("", "e.g. I invest for 10+ years", numeric = false) { draft = it }, k.lp(MATCH_PARENT))
            addView(k.space(8))
            addView(k.button("Remember this", primary = true) {
                val note = draft.trim()
                if (!Ace.acceptable(note)) android.widget.Toast.makeText(this@MemoryActivity, "Not saved: too short, or it looks like a secret, an ID number or a buy/sell instruction.", android.widget.Toast.LENGTH_LONG).show()
                else { store.playbook = Ace.curate(store.playbook, listOf(Ace.Delta(Ace.Section.FACT, note)), System.currentTimeMillis()); render() }
            }, k.lp(MATCH_PARENT))
            addView(k.text("In chat: /remember adds a note, /memory shows them, /forget erases them.", 12f, Pal.DIM).apply { setPadding(0, k.dp(8), 0, 0) })
        })
        val bullets = store.playbook.bullets
        for (section in Ace.Section.entries) {
            val rows = bullets.filter { it.section == section }.sortedByDescending { it.score }
            if (rows.isEmpty()) continue
            col.addView(k.space(12))
            col.addView(k.section("${section.title} · ${rows.size}") {
                rows.forEachIndexed { i, b ->
                    if (i > 0) addView(k.divider(8))
                    val r = k.row(Gravity.TOP)
                    val c = k.column()
                    c.addView(k.text(b.text, 14f, Pal.TEXT).apply { setLineSpacing(0f, 1.3f) })
                    c.addView(k.text("helped ${b.helpful}×" + (if (b.harmful > 0) " · led to a redo ${b.harmful}×" else ""), 11f, Pal.DIM).apply { setPadding(0, k.dp(3), 0, 0) })
                    r.addView(c, k.lp(0, WRAP_CONTENT, 1f))
                    r.addView(k.iconButton("✕", 16f, Pal.MUTED) { store.remove(b.id); render() }.apply { contentDescription = "Delete this note" })
                    addView(r, k.lp(MATCH_PARENT))
                }
            })
        }
        col.addView(k.space(12))
        if (bullets.isEmpty()) col.addView(k.text("Nothing remembered yet.", 14f, Pal.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, k.dp(24), 0, k.dp(24)) }, k.lp(MATCH_PARENT))
        else col.addView(k.button("Erase all memory", primary = false) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setMessage("Erase all ${bullets.size} notes? This can't be undone.")
                .setNegativeButton("Cancel", null).setPositiveButton("Erase") { _, _ -> store.clear(); render() }.show()
        }, k.lp(MATCH_PARENT))
    }
}
