package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.WebData
import com.khayali.pulao.ai.term.J.a
import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.o
import com.khayali.pulao.ai.term.J.s

/** The quote feed's batch "spark" endpoint: up to 20 symbols' intraday closes in one request. Pure parsing. */
object Sparks {
    fun url(symbols: List<String>, range: String, interval: String) =
        "https://${WebData.QUOTE_HOST}/v7/finance/spark?symbols=" + symbols.take(20).joinToString(",") { WebData.encode(it) } + "&range=$range&interval=$interval"

    fun parse(json: String): Map<String, Spark> = J.obj(json)?.o("spark")?.a("result").orEmpty().mapNotNull { r ->
        val res = r.a("response").firstOrNull() ?: return@mapNotNull null
        val meta = res.o("meta") ?: return@mapNotNull null
        val closes = res.o("indicators")?.a("quote")?.firstOrNull()?.getAsJsonArray("close")?.mapNotNull { if (it.isJsonNull) null else it.asDouble }.orEmpty()
        val last = meta.d("regularMarketPrice") ?: closes.lastOrNull()
        r.s("symbol") to Spark(closes, meta.d("chartPreviousClose") ?: meta.d("previousClose"), last)
    }.toMap()

    /** "INFY" → "INFY.NS"; indices and already-suffixed symbols pass through. */
    fun feed(symbol: String) = if (symbol.startsWith("^") || symbol.contains('.') || symbol.contains('=')) symbol else "$symbol.NS"
}
