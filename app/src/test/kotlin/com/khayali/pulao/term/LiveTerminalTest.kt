package com.khayali.pulao.term

import com.khayali.pulao.Stock
import com.khayali.pulao.ai.GmpData
import com.khayali.pulao.ai.MarketData
import com.khayali.pulao.ai.WebData
import com.khayali.pulao.ai.term.Fetch
import com.khayali.pulao.ai.term.Hosts
import com.khayali.pulao.ai.term.Spark
import com.khayali.pulao.ai.term.Sparks
import com.khayali.pulao.ai.term.Terminal
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

/**
 * Runs every terminal command against LIVE public data and writes each card to build/live-cards/<n>.json.
 * Skipped unless KP_LIVE=1 and KP_NSE_FETCH (a script that prints an NSE API reply for a path — NSE only answers
 * a real browser engine) are set. Holdings here are fictional. Example:
 *   KP_LIVE=1 KP_NSE_FETCH=/path/nse.sh ./gradlew :app:testDebugUnitTest --tests '*LiveTerminalTest*'
 */
class LiveTerminalTest {
    private val out = File("build/live-cards").apply { mkdirs() }

    private fun http(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { setRequestProperty("User-Agent", "Mozilla/5.0"); connectTimeout = 15_000; readTimeout = 30_000 }
        return try { (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty() } finally { c.disconnect() }
    }

    private val nseCache = mutableMapOf<String, String>()
    private fun nse(path: String): String = nseCache.getOrPut(path) {
        val p = ProcessBuilder(System.getenv("KP_NSE_FETCH"), path).redirectErrorStream(false).start()
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }

    private val fetch = object : Fetch {
        override val today: LocalDate = LocalDate.now(ZoneId.of("Asia/Kolkata"))
        override val holdings = listOf(Stock.Holding("INFY", 20.0, 1300.0, 26000.0, 1140.0), Stock.Holding("TCS", 8.0, 2600.0, 20800.0, 2410.0),
            Stock.Holding("ITC", 100.0, 380.0, 38000.0, 410.0), Stock.Holding("HDFCBANK", 15.0, 1600.0, 24000.0, 1780.0))
        override val watched = listOf("RELIANCE", "SBIN", "TATASTEEL")
        override suspend fun nse(path: String) = this@LiveTerminalTest.nse(path)
        override suspend fun chart(symbol: String, range: String, interval: String) =
            http("https://${WebData.QUOTE_HOST}/v8/finance/chart/${WebData.encode(symbol)}?range=$range&interval=$interval&events=div%7Csplit")
        override suspend fun get(url: String): String { require(Hosts.allowed(url)) { "blocked $url" }; return http(url) }
        override suspend fun search(query: String) = emptyList<WebData.Result>()
        override suspend fun symbol(query: String): String? {
            val q = query.trim().removePrefix("@"); if (q.isBlank()) return null
            MarketData.symbolOrNull(q)?.takeIf { !q.contains(' ') }?.let { return it }
            return WebData.parseLookup(http(WebData.lookupUrl(q)))?.substringBefore('.')
        }
        override suspend fun spark(symbols: List<String>, range: String, interval: String): Map<String, Spark> =
            symbols.distinct().chunked(20).flatMap { Sparks.parse(http(Sparks.url(it, range, interval))).entries }.associate { it.key to it.value }
    }

    @Test fun everyCommandOnLiveData() {
        assumeTrue(System.getenv("KP_LIVE") == "1" && System.getenv("KP_NSE_FETCH") != null)
        val only = System.getenv("KP_ONLY")?.split(',')?.map { it.trim() }?.toSet()
        val cmds = File(System.getenv("KP_CMDS") ?: error("KP_CMDS")).readLines().filter { it.contains('|') }
        for (line in cmds) {
            val (n, c) = line.split('|', limit = 2)
            if (only != null && n !in only) continue
            val id = c.trim().removePrefix("/").substringBefore(' ')
            val f = Terminal.find(id) ?: run { File(out, "$n.json").writeText("""{"error":"no feature $id"}"""); continue }
            val t0 = System.nanoTime()
            val json = runCatching { runBlocking { f.run(c.trim().substringAfter(' ', "").trim(), fetch) } }.getOrElse { """{"error":"EXCEPTION ${it::class.simpleName}: ${it.message?.replace("\"", "'")?.take(200)}"}""" }
            File(out, "$n.json").writeText(json)
            println("LIVE $n ${c.trim()} ${(System.nanoTime() - t0) / 1_000_000}ms ${if (json.contains("\"error\"")) "ERROR " + json.take(220) else "ok " + (Regex("\"summary\":\"([^\"]{0,160})").find(json)?.groupValues?.get(1) ?: "")}")
        }
    }
}
