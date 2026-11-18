package com.khayali.pulao.ai

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.khayali.pulao.Kit
import com.khayali.pulao.Pal

/**
 * The side drawer, Gemini-style: new chat, search, your chats (pinned first, then recent), and the app's own places
 * (commands, memory, setup, data) at the bottom. Long-press a chat to pin, rename or delete it — delete asks first.
 * Everything listed lives only on this phone.
 */
@SuppressLint("ViewConstructor")
class ChatDrawer(
    ctx: Context, private val k: Kit, private val library: ChatLibrary,
    private val currentId: () -> String?,
    private val onNew: () -> Unit,
    private val onOpen: (String) -> Unit,
    private val onDelete: (String) -> Unit,
    private val places: List<Pair<String, () -> Unit>>,
) : FrameLayout(ctx) {

    private val scrim = View(ctx).apply { setBackgroundColor(0xFF000000.toInt()); alpha = 0f; setOnClickListener { close() } }
    private val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; isClickable = true
        background = k.rounded(Pal.SURFACE, 0f).also { (it as? android.graphics.drawable.GradientDrawable)?.cornerRadii = floatArrayOf(0f, 0f, k.dpf(24f), k.dpf(24f), k.dpf(24f), k.dpf(24f), 0f, 0f) }
        setPadding(k.dp(14), k.dp(18), k.dp(14), k.dp(14))
    }
    private val chats = k.column()
    private val search = EditText(ctx)
    private var shown = false
    private val panelW get() = minOf(resources.displayMetrics.widthPixels * 84 / 100, k.dp(340))

    init {
        visibility = GONE
        addView(scrim, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // header
        panel.addView(k.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(k.text("Khayali Pulao", 20f, Pal.TEXT, 600), k.lp(0, WRAP_CONTENT, 1f))
            addView(Icons.button(ctx, k, "close", Pal.MUTED, "Close", 20, 40) { close() })
        }, k.lp(MATCH_PARENT).apply { marginStart = k.dp(6) })
        panel.addView(k.text("🔒 Your chats stay on this phone", 11.5f, Pal.DIM).apply { setPadding(k.dp(6), 0, 0, k.dp(12)) })
        // search
        panel.addView(k.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = k.rounded(Pal.alpha(0xFFFFFFFF.toInt(), 0.06f), 999f)
            setPadding(k.dp(12), 0, k.dp(8), 0)
            addView(android.widget.ImageView(ctx).apply { setImageResource(Icons.res("search")); Icons.tint(this, Pal.MUTED) }, k.lp(k.dp(18), k.dp(18)))
            addView(search.apply {
                k.style(this, "", 14.5f, Pal.TEXT, 400); hint = "Search chats"; setHintTextColor(Pal.DIM); background = null
                inputType = InputType.TYPE_CLASS_TEXT; maxLines = 1; setPadding(k.dp(10), k.dp(10), k.dp(6), k.dp(10))
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(e: android.text.Editable?) = fill()
                })
            }, k.lp(0, WRAP_CONTENT, 1f))
        }, k.lp(MATCH_PARENT, k.dp(44)))
        panel.addView(k.space(8))
        panel.addView(row("edit", "New chat", Pal.TEXT) { close(); onNew() }, k.lp(MATCH_PARENT))
        // chats
        panel.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(chats) }, k.lp(MATCH_PARENT, 0, 1f))
        // places
        panel.addView(View(ctx).apply { setBackgroundColor(Pal.LINE) }, k.lp(MATCH_PARENT, 1).apply { topMargin = k.dp(6); bottomMargin = k.dp(6) })
        places.forEach { (label, go) -> panel.addView(row(null, label, Pal.MUTED) { close(); go() }, k.lp(MATCH_PARENT)) }
        addView(panel, LayoutParams(panelW, MATCH_PARENT))
        // a swipe to the left closes it
        var x0 = 0f
        panel.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> x0 = e.rawX
                MotionEvent.ACTION_MOVE -> panel.translationX = (e.rawX - x0).coerceAtMost(0f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (e.rawX - x0 < -k.dp(70)) close() else panel.animate().translationX(0f).setDuration(150).start()
            }
            false
        }
    }

    val isOpen get() = shown
    /** Called whenever the drawer closes, however it was closed. */
    var onClosed: () -> Unit = {}

    fun open() {
        if (shown) return
        shown = true; visibility = VISIBLE; search.setText(""); fill()
        panel.translationX = -panelW.toFloat()
        panel.animate().translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        scrim.animate().alpha(0.5f).setDuration(260).start()
    }

    fun close() {
        if (!shown) return
        shown = false; onClosed()
        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
        panel.animate().translationX(-panelW.toFloat()).setDuration(200).setInterpolator(DecelerateInterpolator()).withEndAction { visibility = GONE }.start()
        scrim.animate().alpha(0f).setDuration(200).start()
    }

    private fun row(icon: String?, label: String, color: Int, onClick: () -> Unit) = k.row().apply {
        gravity = Gravity.CENTER_VERTICAL
        background = k.press(null, 999f)
        setPadding(k.dp(12), k.dp(11), k.dp(12), k.dp(11))
        if (icon != null) addView(android.widget.ImageView(context).apply { setImageResource(Icons.res(icon)); Icons.tint(this, color) }, k.lp(k.dp(20), k.dp(20)).apply { marginEnd = k.dp(14) })
        addView(k.text(label, 15f, color, 500).apply { maxLines = 1 }, k.lp(0, WRAP_CONTENT, 1f))
        setOnClickListener { onClick() }
    }

    /** Lists the chats (pinned, then recent), filtered by the search words. */
    private fun fill() {
        chats.removeAllViews()
        val q = search.text.toString()
        val list = library.search(q)
        if (list.isEmpty()) {
            chats.addView(k.text(if (q.isBlank()) "Your chats will show up here." else "No chats match “$q”.", 13f, Pal.DIM).apply { setPadding(k.dp(12), k.dp(16), k.dp(12), 0) })
            return
        }
        val pinned = list.filter { it.pinned }; val recent = list.filterNot { it.pinned }
        if (pinned.isNotEmpty()) { header("Pinned"); pinned.forEach(::chat) }
        if (recent.isNotEmpty()) { header("Recent"); recent.forEach(::chat) }
    }

    private fun header(t: String) = chats.addView(k.text(t, 12.5f, Pal.MUTED, 600).apply { setPadding(k.dp(12), k.dp(14), 0, k.dp(4)) })

    private fun chat(m: ChatLibrary.Meta) {
        val on = m.id == currentId()
        val r = k.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = k.press(if (on) k.rounded(Pal.alpha(Pal.BLUE, 0.16f), 999f) else null, 999f)
            setPadding(k.dp(12), k.dp(10), k.dp(12), k.dp(10))
            if (m.pinned) addView(android.widget.ImageView(context).apply { setImageResource(Icons.res("pin")); Icons.tint(this, Pal.DIM) }, k.lp(k.dp(14), k.dp(14)).apply { marginEnd = k.dp(8) })
            addView(k.text(m.title, 14.5f, if (on) Pal.TEXT else Pal.alpha(Pal.TEXT, 0.88f), if (on) 600 else 400).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, k.lp(0, WRAP_CONTENT, 1f))
            addView(k.text(ago(m.updated), 11f, Pal.DIM).apply { setPadding(k.dp(8), 0, 0, 0) })
            contentDescription = "Open chat: ${m.title}"
            setOnClickListener { close(); onOpen(m.id) }
            setOnLongClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); options(m); true }
        }
        chats.addView(r, k.lp(MATCH_PARENT))
    }

    private fun options(m: ChatLibrary.Meta) {
        val items = arrayOf(if (m.pinned) "Unpin" else "Pin", "Rename", "Delete")
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert).setTitle(m.title).setItems(items) { _, i ->
            when (i) {
                0 -> { library.pin(m.id, !m.pinned); fill() }
                1 -> rename(m)
                2 -> AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                    .setMessage("Delete “${m.title}”? It's removed from this phone and can't be brought back.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ -> onDelete(m.id); fill() }.show()
            }
        }.show()
    }

    private fun rename(m: ChatLibrary.Meta) {
        val field = EditText(context).apply { setText(m.title); setSelection(text.length); setTextColor(Pal.TEXT); setPadding(k.dp(20), k.dp(12), k.dp(20), k.dp(12)) }
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert).setTitle("Rename chat").setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> library.rename(m.id, field.text.toString()); fill() }.show()
    }

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000
        return when { m < 1 -> "now"; m < 60 -> "${m}m"; m < 60 * 24 -> "${m / 60}h"; m < 60 * 24 * 7 -> "${m / 1440}d"
            else -> java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).toLocalDate().let { "${it.dayOfMonth} ${it.month.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }}" } }
    }
}
