package com.khayali.pulao.ai

import android.content.Context

/**
 * Stocks the user has looked at in chat (charts, prices, events, @mentions…), most recent first.
 * A private list on the phone: it powers /watch and the "your stocks" part of /report, and is
 * never sent anywhere — only individual symbols are priced, the same way any /price would be.
 */
class WatchStore private constructor(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("khayali", Context.MODE_PRIVATE)

    val symbols: List<String> get() = prefs.getString(KEY, "")!!.split(',').filter { it.isNotBlank() }

    fun add(symbol: String) {
        val s = symbol.trim().removePrefix("@").uppercase()
        if (!VALID.matches(s) || s in NOT_STOCKS) return
        prefs.edit().putString(KEY, (listOf(s) + symbols.filter { it != s }).take(MAX).joinToString(",")).apply()
    }

    fun remove(symbol: String) = prefs.edit().putString(KEY, symbols.filter { it != symbol.uppercase() }.joinToString(",")).apply()
    fun clear() = prefs.edit().remove(KEY).apply()

    companion object {
        const val MAX = 20
        private const val KEY = "watched"
        private val VALID = Regex("""[A-Z][A-Z0-9&\-]{0,19}""")
        /** Words that follow a command but are not symbols. */
        private val NOT_STOCKS = setOf("NIFTY", "SENSEX", "BANKNIFTY", "WEEK", "TODAY", "TOMORROW", "MONTH", "BANK", "IT", "AUTO", "PHARMA", "FMCG", "METAL", "REALTY", "ENERGY", "MEDIA")
        /** Commands whose argument names one stock. */
        val STOCK_COMMANDS = setOf("chart", "price", "events", "flow", "quote", "shareholding", "mindmap", "scorecard", "ta", "rsi", "macd", "drawdown", "returns", "levels", "volatility", "insider", "announcements", "filings", "news", "results")

        @Volatile private var instance: WatchStore? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: WatchStore(ctx).also { instance = it } }

        /** The stock symbols a message refers to: @mentions, and the argument of a stock command. Pure. */
        fun symbolsIn(text: String): List<String> {
            val t = text.trim()
            val out = Commands.mentions(t).toMutableList()
            if (t.startsWith("/")) {
                val name = t.removePrefix("/").substringBefore(' ').lowercase()
                val arg = t.substringAfter(' ', "").trim().split(Regex("""\s+""")).firstOrNull().orEmpty().removePrefix("@")
                if (name in STOCK_COMMANDS && arg.isNotBlank()) out += arg.uppercase()
            }
            return out.map { it.uppercase() }.filter { VALID.matches(it) && it !in NOT_STOCKS }.distinct()
        }
    }
}
