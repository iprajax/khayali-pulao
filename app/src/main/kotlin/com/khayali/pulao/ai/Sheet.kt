package com.khayali.pulao.ai

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import com.khayali.pulao.Kit
import com.khayali.pulao.Pal

/** A bottom sheet of choices, each with a one-line description (the + tools, Gemini-style). */
object Sheet {
    data class Item(val icon: String, val title: String, val desc: String, val trailing: String? = null, val go: () -> Unit)

    fun show(ctx: Context, k: Kit, title: String?, items: List<Item>) {
        val d = Dialog(ctx)
        val col = k.column().apply {
            background = GradientDrawable().apply { setColor(Pal.SURFACE); val r = k.dpf(28f); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            setPadding(k.dp(10), k.dp(10), k.dp(10), k.dp(22))
        }
        col.addView(android.view.View(ctx).apply { background = k.rounded(Pal.alpha(0xFFFFFFFF.toInt(), 0.25f), 999f) }, k.lp(k.dp(36), k.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = k.dp(10) })
        title?.let { col.addView(k.text(it, 13f, Pal.MUTED, 600).apply { setPadding(k.dp(14), 0, 0, k.dp(6)) }) }
        items.forEach { it ->
            val row = k.row().apply {
                gravity = Gravity.CENTER_VERTICAL; background = k.press(null, 18f)
                setPadding(k.dp(12), k.dp(11), k.dp(12), k.dp(11))
                setOnClickListener { _ -> d.dismiss(); it.go() }
            }
            row.addView(k.text(it.icon, 18f, Pal.TEXT).apply { gravity = Gravity.CENTER; background = k.rounded(Pal.alpha(0xFFFFFFFF.toInt(), 0.06f), 999f) }, k.lp(k.dp(40), k.dp(40)))
            row.addView(k.column().apply {
                setPadding(k.dp(14), 0, k.dp(8), 0)
                addView(k.text(it.title, 15f, Pal.TEXT, 500))
                addView(k.text(it.desc, 12.5f, Pal.MUTED).apply { setLineSpacing(0f, 1.2f) })
            }, k.lp(0, WRAP_CONTENT, 1f))
            it.trailing?.let { t -> row.addView(k.text(t, 12f, if (t.startsWith("On") || t.startsWith("Connected")) Pal.GREEN else Pal.DIM, 600)) }
            col.addView(row, k.lp(MATCH_PARENT))
        }
        d.setContentView(col)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH_PARENT, WRAP_CONTENT); setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.45f)
        }
        d.show()
    }
}
