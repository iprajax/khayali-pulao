package com.khayali.pulao.ai

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View

/**
 * Stock symbols in any output are tappable: tap (or long-press) one for its menu — trend, chart, news,
 * events, quote, ask about it. Detection is pure: a token is a ticker when the phone knows the symbol
 * (market history, Kite holdings, watch list) and it isn't a finance abbreviation.
 */
object Tickers {
    /** Symbols the phone knows; kept current by the chat. */
    @Volatile var known: Set<String> = emptySet()
    /** Opens the symbol menu; set by the chat. */
    @Volatile var onTap: ((String) -> Unit)? = null

    private val TOKEN = Regex("""(?<![A-Za-z0-9@&\-])@?([A-Z][A-Z0-9&\-]{1,14})(?![A-Za-z0-9&\-])""")
    private val SHAPE = Regex("""^[A-Z][A-Z0-9&\-]{1,14}$""")
    val STOP = setOf("NSE", "BSE", "IPO", "GMP", "SIP", "SWP", "EMI", "FD", "PE", "PB", "ROE", "ROCE", "ROIC", "FII", "FPI", "DII", "ETF", "USD", "INR", "CAGR", "XIRR",
        "EPS", "AND", "OR", "THE", "GDP", "CPI", "RBI", "SEBI", "AGM", "NAV", "LTP", "PNL", "YOY", "QOQ", "OK", "AI", "IT", "MF", "ATM", "OI", "PCR", "VIX", "US",
        "UK", "EU", "Q1", "Q2", "Q3", "Q4", "EBITDA", "NOT", "NIFTY", "SENSEX", "MCX", "STT", "LTCG", "STCG", "TDS", "GST", "PPF", "EPF", "NPS", "KYC", "API", "MCP",
        "SMA", "RSI", "MACD", "VWAP", "DMA", "OPM", "PEG", "TTM", "FY", "H1", "H2", "NA", "CEO", "CFO", "MD", "BANK", "AUTO", "PHARMA", "FMCG", "METAL", "REALTY", "MEDIA", "ENERGY")

    /** All-caps words cards use as labels; never tickers. */
    private val WORDS = setOf("TODAY", "TAKEAWAY", "PLAN", "SETUP", "TIMING", "PRIVACY", "STEPS", "WHY", "HOW", "NEW", "ALL", "OTHERS", "MARKET", "STOCK", "STOCKS",
        "VOLATILITY", "RESULTS", "DIVIDEND", "BONUS", "SPLIT", "BUYBACK", "HOLIDAY", "AGM", "OPEN", "CLOSED", "UPCOMING", "LISTED", "LISTING", "BIDDING", "TOTAL",
        "HOLDING", "HOLDINGS", "INVESTED", "CURRENT", "PNL", "DAY", "WEEK", "MONTH", "YEAR", "HIGH", "LOW", "LTP", "QTY", "AVG", "BUY", "SELL", "GAINERS", "LOSERS", "SECTORS", "SECTOR")

    /** A label that is exactly a stock the phone knows (or, before anything is known, has a ticker's shape and isn't a heading word). */
    fun isKnown(s: String): Boolean {
        val t = s.trim().removePrefix("★").trim().removePrefix("@")
        if (!SHAPE.matches(t) || t in STOP || t in WORDS) return false
        return known.isEmpty() || t in known
    }

    /** Does this field (a row title, a mover's symbol) name a stock? Structured fields need only the right shape. */
    fun isSymbol(s: String, structured: Boolean = true): Boolean {
        val t = s.trim().removePrefix("★").trim().removePrefix("@")
        if (!SHAPE.matches(t) || t in STOP) return false
        return if (structured) known.isEmpty() || t in known || t.length >= 3 else t in known
    }

    /** Character ranges of tickers in free text (only symbols the phone knows). */
    fun find(text: CharSequence, known: Set<String> = this.known): List<Pair<IntRange, String>> =
        TOKEN.findAll(text).mapNotNull { m -> val sym = m.groupValues[1]
            if (sym in known && sym !in STOP) m.range to sym else null }.toList()

    /** Adds tap targets for every command and every known ticker in [text] (commands first; a ticker inside one stays part of it). */
    fun linkify(text: CharSequence): CharSequence {
        val cmds = Actions.find(text.toString()); val hits = find(text)
        if (cmds.isEmpty() && hits.isEmpty()) return text
        val out = if (text is SpannableStringBuilder) text else SpannableStringBuilder(text)
        for ((range, cmd) in cmds) out.setSpan(Actions.span(cmd), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        for ((range, sym) in hits) if (cmds.none { (r, _) -> range.first in r }) out.setSpan(span(sym), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    /** Linkifies a text view's own text and turns on tap handling only when there is something to tap. */
    fun apply(tv: android.widget.TextView) {
        val t = linkify(SpannableStringBuilder(tv.text))
        if (t is Spanned && t.getSpans(0, t.length, ClickableSpan::class.java).isNotEmpty()) { tv.text = t; tv.movementMethod = android.text.method.LinkMovementMethod.getInstance() }
    }

    private fun span(sym: String) = object : ClickableSpan() {
        override fun onClick(widget: View) { onTap?.invoke(sym) }
        override fun updateDrawState(ds: TextPaint) { ds.color = com.khayali.pulao.Pal.BLUE; ds.isUnderlineText = false; ds.isFakeBoldText = true }
    }

    /**
     * Wires every ticker in a finished card — whatever built it (calendar, IPOs, holdings, web results, terminal
     * cards). A text that is just a symbol makes its row a tap target; tickers inside sentences become tap spans.
     * Rows that already do something on tap keep it.
     */
    fun wire(root: View) {
        fun clickableAncestor(v: View): Boolean { var p: android.view.ViewParent? = v.parent
            while (p is View && p !== root.parent) { if (p.hasOnClickListeners()) return true; p = p.parent }; return false }
        fun walk(v: View) {
            if (v is android.view.ViewGroup) { for (i in 0 until v.childCount) walk(v.getChildAt(i)); return }
            val tv = v as? android.widget.TextView ?: return
            val text = tv.text?.toString()?.trim().orEmpty(); if (text.isEmpty()) return
            if ((tv.text as? Spanned)?.getSpans(0, tv.text.length, ClickableSpan::class.java)?.isNotEmpty() == true) return
            val bare = text.removePrefix("★").removePrefix("@").trim().substringBefore(' ').substringBefore('\n')
            if (text.length <= 22 && isKnown(bare) && (text == bare || text.removePrefix("★").trim() == bare) && !tv.hasOnClickListeners() && !clickableAncestor(tv)) {
                val row = (tv.parent as? View)?.takeIf { it !== root && !it.hasOnClickListeners() && (it.parent as? View)?.hasOnClickListeners() != true } ?: tv
                attach(row, bare); return
            }
            if (!tv.hasOnClickListeners() && !clickableAncestor(tv)) apply(tv)
        }
        walk(root)
    }

    /** Makes a view open the symbol menu on tap and on long-press. */
    fun attach(v: View, symbol: String) {
        val sym = symbol.trim().removePrefix("★").trim().removePrefix("@")
        v.setOnClickListener { onTap?.invoke(sym) }
        v.setOnLongClickListener { onTap?.invoke(sym); true }
    }

    /** What the menu offers for a symbol: label → command (or "ask:" text to put in the box). */
    fun menu(sym: String, web: Boolean, market: Boolean): List<Pair<String, String>> = listOfNotNull(
        if (market) "🩺  Trend & risk" to "/health $sym" else null,
        "📈  Chart, 1 year" to "/chart $sym 1y",
        "📰  Latest news" + (if (web) "" else "  (web)") to "/news $sym",
        "📅  Results, dividends & events" + (if (web) "" else "  (web)") to "/events $sym",
        "💹  Full quote" + (if (web) "" else "  (web)") to "/quote $sym",
        "🔬  Deep dive (plan)" to "what's happening with $sym",
        "💬  Ask about $sym…" to "ask:What if @$sym ",
    )
}


/**
 * Every suggested next step is something to tap. Finds app commands in text ("/screen momentum",
 * "/data 3y", "/sip 10000 12% 15y") — an argument counts only when it is a valid choice for that
 * command, a ticker or a number, so ordinary words after a command aren't swallowed — and runs them.
 */
object Actions {
    @Volatile var onRun: ((String) -> Unit)? = null
    val names: Set<String> by lazy { (Commands.ALL.map { it.name } + com.khayali.pulao.ai.term.Terminal.FEATURES.map { it.id }).toSet() }
    /** Things a tap can do that aren't slash commands. */
    val SPECIAL = setOf("Connect Kite", "Allow web access")

    private val NAME = Regex("""(?<![\w/])/([a-z][a-z0-9]{1,14})\b""")
    private val NUMBER = Regex("""^\d[\d,.]*(%|y|m|w|k)?$""")
    private val TICKER = Regex("""^[A-Z][A-Z0-9&\-]{1,14}$""")

    fun isAction(s: String): Boolean { val t = s.trim(); return t in SPECIAL || (t.startsWith("/") && t.removePrefix("/").substringBefore(' ').lowercase() in names) }

    /** Commands in [text] with their character ranges. */
    fun find(text: String): List<Pair<IntRange, String>> = NAME.findAll(text).mapNotNull { m ->
        val name = m.groupValues[1]; if (name !in names) return@mapNotNull null
        val choices = Params.slots(name, emptyList()).flatMap { it.choices }
        var end = m.range.last + 1; val args = mutableListOf<String>()
        while (args.size < 4 && end < text.length && text[end] == ' ') {
            val tokEnd = (end + 1 until text.length).firstOrNull { text[it].isWhitespace() || text[it] in "·,;:()\"'" } ?: text.length
            val tok = text.substring(end + 1, tokEnd).trimEnd('.', '!', '?')
            if (tok.isEmpty()) break
            val joined = (args + tok).joinToString(" ")
            val ok = choices.any { it.startsWith(joined, ignoreCase = true) } || (TICKER.matches(tok) && tok !in Tickers.STOP) || NUMBER.matches(tok)
            if (!ok) break
            args += tok; end = end + 1 + tok.length
        }
        (m.range.first until end) to ("/" + name + if (args.isEmpty()) "" else " " + args.joinToString(" "))
    }.toList()

    fun span(cmd: String) = object : ClickableSpan() {
        override fun onClick(widget: View) { onRun?.invoke(cmd) }
        override fun updateDrawState(ds: TextPaint) { ds.color = com.khayali.pulao.Pal.BLUE; ds.isUnderlineText = false; ds.isFakeBoldText = true }
    }
}
