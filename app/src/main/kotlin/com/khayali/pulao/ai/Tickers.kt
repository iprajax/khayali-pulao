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
