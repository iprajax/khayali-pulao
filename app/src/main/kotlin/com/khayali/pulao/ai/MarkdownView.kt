package com.khayali.pulao.ai

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.khayali.pulao.Kit
import com.khayali.pulao.Pal

/** Markdown blocks the chat renders natively. Parsing is pure and unit-tested. */
object Md {
    sealed class Block {
        data class Para(val text: String) : Block()
        data class Heading(val text: String, val level: Int) : Block()
        data class Bullets(val items: List<String>, val ordered: Boolean) : Block()
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block()
        data class Code(val text: String) : Block()
        data class Quote(val text: String) : Block()
    }

    private val HR = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
    private fun cells(line: String) = line.trim().trim('|').split('|').map { it.trim() }

    fun parse(src: String): List<Block> {
        val out = mutableListOf<Block>()
        val lines = src.replace("\r", "").lines()
        var i = 0
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.setLength(0) }
        while (i < lines.size) {
            val l = lines[i]
            val t = l.trim()
            when {
                t.isEmpty() -> { flush(); i++ }
                t.startsWith("```") -> {
                    flush(); val code = StringBuilder(); i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code.appendLine(lines[i]); i++ }
                    i++; out += Block.Code(code.toString().trimEnd())
                }
                t.startsWith("#") -> { flush(); val n = t.takeWhile { it == '#' }.length; out += Block.Heading(t.drop(n).trim(), n.coerceIn(1, 3)); i++ }
                t.startsWith(">") -> { flush(); out += Block.Quote(t.removePrefix(">").trim()); i++ }
                t.startsWith("|") && i + 1 < lines.size && HR.matches(lines[i + 1]) -> {
                    flush(); val header = cells(t); i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i]); i++ }
                    out += Block.Table(header, rows)
                }
                Regex("""^\s*([-*•])\s+""").containsMatchIn(l) || Regex("""^\s*\d+[.)]\s+""").containsMatchIn(l) -> {
                    flush(); val ordered = Regex("""^\s*\d+[.)]\s+""").containsMatchIn(l)
                    val items = mutableListOf<String>()
                    val item = Regex("""^\s*(?:[-*•]|\d+[.)])\s+""")
                    while (i < lines.size) {
                        if (item.containsMatchIn(lines[i])) { items += lines[i].replace(item, ""); i++ }
                        // a blank line between items doesn't end the list ("1. A\n\n2. B" is one list)
                        else if (lines[i].isBlank() && lines.drop(i + 1).firstOrNull { it.isNotBlank() }?.let { item.containsMatchIn(it) } == true) i++
                        else break
                    }
                    out += Block.Bullets(items, ordered)
                }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t); i++ }
            }
        }
        flush()
        return out
    }

    /** Plain text with markers removed (same rules as [inline]); pure, for tests and copy. */
    fun plain(src: String): String {
        var t = src.replace(Regex("""\*\*(.+?)\*\*"""), "$1").replace(Regex("""`([^`]+)`"""), "$1")
            .replace(Regex("""(?<![\w*])\*([^*\s][^*]*)\*"""), "$1")
        t = t.replace("**", "")
        return t
    }

    /** Inline: **bold**, *italic*, `code`. Unfinished markers while streaming are hidden, not shown raw. */
    /** [label](cmd:/command) — written by the planner and the model for next steps. */
    val ACTION = Regex("""\[([^\]\n]{1,80})]\(cmd:([^)\n]{1,120})\)""")

    fun inline(src: String, accent: Int = Pal.TEXT): SpannableStringBuilder {
        val out = SpannableStringBuilder(); var i = 0
        while (i < src.length) {
            when {
                src.startsWith("**", i) -> {
                    val end = src.indexOf("**", i + 2)
                    if (end > i) { val s = out.length; out.append(src, i + 2, end)
                        out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(ForegroundColorSpan(accent), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); i = end + 2 } else i += 2
                }
                // action links: [label](cmd:/command) — a next step the user can tap
                src[i] == '[' && ACTION.find(src, i)?.range?.first == i -> {
                    val m = ACTION.find(src, i)!!; val s = out.length
                    out.append("▸ ").append(m.groupValues[1])
                    out.setSpan(Actions.span(m.groupValues[2].trim()), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); i = m.range.last + 1
                }
                // citation markers like [2] point at the numbered sources card
                src[i] == '[' && Regex("""^\[\d{1,2}]""").containsMatchIn(src.substring(i, minOf(src.length, i + 4))) -> {
                    val end = src.indexOf(']', i); val s = out.length; out.append(src, i, end + 1)
                    out.setSpan(ForegroundColorSpan(Pal.BLUE), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(android.text.style.RelativeSizeSpan(0.85f), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); i = end + 1
                }
                src[i] == '`' -> {
                    val end = src.indexOf('`', i + 1)
                    if (end > i) { val s = out.length; out.append(src, i + 1, end)
                        out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(BackgroundColorSpan(Pal.alpha(Pal.LINE, 0.8f)), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); i = end + 1 } else i++
                }
                src[i] == '*' && i + 1 < src.length && src[i + 1] != ' ' && (i == 0 || !src[i - 1].isLetterOrDigit()) -> {
                    val end = src.indexOf('*', i + 1)
                    if (end > i) { val s = out.length; out.append(src, i + 1, end)
                        out.setSpan(StyleSpan(Typeface.ITALIC), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); i = end + 1 } else i++
                }
                else -> { out.append(src[i]); i++ }
            }
        }
        return Tickers.linkify(out) as SpannableStringBuilder
    }
}

/**
 * Native renderer for streamed markdown: paragraphs, headings, lists, real tables (grid with
 * dividers, horizontally scrollable), code and quotes. Re-renders only changed blocks; new
 * blocks fade in.
 */
class MarkdownView(ctx: Context, private val k: Kit) : LinearLayout(ctx) {
    private var blocks: List<Md.Block> = emptyList()
    private var full = ""

    init { orientation = VERTICAL }

    fun update(text: String) {
        if (text == full) return
        full = text
        val next = Md.parse(text)
        var same = 0
        while (same < blocks.size && same < next.size && blocks[same] == next[same]) same++
        // keep unchanged blocks; rebuild from the first changed one
        while (childCount > same) removeViewAt(childCount - 1)
        for (b in next.drop(same)) {
            val v = view(b)
            addView(v, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = if (childCount == 0) 0 else k.dp(10) })
            if (blocks.size <= next.indexOf(b)) { v.alpha = 0f; v.animate().alpha(1f).setDuration(220).start() }
        }
        blocks = next
    }

    val plain: String get() = full

    private fun tv(s: CharSequence, size: Float = 15.5f, color: Int = Pal.TEXT, weight: Int = 400) =
        k.text(s, size, color, weight).apply {
            setLineSpacing(0f, 1.38f)
            // tickers are tap targets; text with them uses link handling, the rest stays selectable
            if (s is Spanned && s.getSpans(0, s.length, android.text.style.ClickableSpan::class.java).isNotEmpty()) movementMethod = android.text.method.LinkMovementMethod.getInstance()
            else setTextIsSelectable(true)
        }

    private fun view(b: Md.Block): View = when (b) {
        is Md.Block.Para -> tv(Md.inline(b.text))
        is Md.Block.Heading -> tv(Md.inline(b.text), if (b.level == 1) 19f else 17f, Pal.TEXT, 600)
        is Md.Block.Quote -> tv(Md.inline(b.text), 14.5f, Pal.MUTED).apply {
            background = k.rounded(Pal.alpha(Pal.BLUE, 0.08f), 8f); setPadding(k.dp(12), k.dp(8), k.dp(12), k.dp(8))
        }
        is Md.Block.Code -> HorizontalScrollView(context).apply {
            background = k.rounded(Pal.BG, 10f, Pal.LINE)
            addView(k.text(b.text, 13f, Pal.TEXT).apply { typeface = Typeface.MONOSPACE; setPadding(k.dp(12), k.dp(10), k.dp(12), k.dp(10)) })
        }
        is Md.Block.Bullets -> k.column().apply {
            b.items.forEachIndexed { i, item ->
                val r = k.row(Gravity.TOP).apply { setPadding(0, k.dp(3), 0, k.dp(3)) }
                r.addView(k.text(if (b.ordered) "${i + 1}." else "•", 15.5f, Pal.BLUE, 600).apply { setLineSpacing(0f, 1.38f) }, k.lp(k.dp(24)))
                r.addView(tv(Md.inline(item)), k.lp(0, WRAP_CONTENT, 1f))
                addView(r, k.lp(MATCH_PARENT))
            }
        }
        is Md.Block.Table -> table(b)
    }

    private fun table(t: Md.Block.Table): View {
        val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
        val grid = k.column().apply { background = k.rounded(Pal.SURFACE, 12f, Pal.LINE) }
        fun rowView(cells: List<String>, header: Boolean, zebra: Boolean): View {
            val r = k.row(Gravity.TOP).apply {
                setPadding(k.dp(4), 0, k.dp(4), 0)
                if (header) background = k.rounded(Pal.alpha(Pal.BLUE, 0.10f), 12f) else if (zebra) setBackgroundColor(Pal.alpha(Pal.TEXT, 0.03f))
            }
            for (c in 0 until cols) {
                val txt = cells.getOrElse(c) { "" }
                val numeric = Regex("""^[−\-+]?₹?[\d,]+(\.\d+)?%?$""").matches(txt.replace("*", "").trim())
                r.addView(k.text(Md.inline(txt), 13.5f, if (header) Pal.MUTED else Pal.TEXT, if (header) 500 else 400).apply {
                    setPadding(k.dp(10), k.dp(9), k.dp(10), k.dp(9)); setLineSpacing(0f, 1.25f)
                    if (numeric && !header) gravity = Gravity.END
                }, k.lp(k.dp(if (c == 0) 130 else 116)))
            }
            return r
        }
        grid.addView(rowView(t.header, true, false))
        t.rows.forEachIndexed { i, row ->
            grid.addView(View(context).apply { setBackgroundColor(Pal.LINE) }, k.lp(MATCH_PARENT, 1))
            grid.addView(rowView(row, false, i % 2 == 1))
        }
        return HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(grid) }
    }
}
