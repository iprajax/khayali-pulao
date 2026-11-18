package com.khayali.pulao

import com.khayali.pulao.ai.data.Sectors
import com.khayali.pulao.ai.term.Fetch
import com.khayali.pulao.ai.term.Hosts
import com.khayali.pulao.ai.term.Terminal
import com.khayali.pulao.term.TermTesting
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** /news, /sebi and /radar against the real sources. Skipped unless KP_LIVE is set. */
class LiveFeedsTest {
    private fun http(u: String): String = (URL(u).openConnection() as HttpURLConnection).run {
        setRequestProperty("User-Agent", "Mozilla/5.0"); setRequestProperty("Accept-Encoding", "identity"); connectTimeout = 15000; readTimeout = 30000
        try { if (responseCode in 200..299) inputStream.bufferedReader().readText() else "" } finally { disconnect() } }

    private val names = Sectors.parseNames(File("src/test/resources/data/equity_l.csv").readText())
    private val live = object : Fetch by TermTesting.Fake() {
        override val webOn = true
        override suspend fun get(url: String): String { require(Hosts.allowed(url)) { "blocked $url" }; return http(url) }
        override suspend fun nse(path: String) = http("https://www.nseindia.in/api/$path")
        override suspend fun names() = names
    }

    @Test fun realSources() = runBlocking {
        assumeTrue(System.getenv("KP_LIVE") != null)
        for (cmd in (System.getenv("KP_CMDS")?.split(",") ?: listOf("news", "news INFY", "sebi", "radar"))) {
            val t0 = System.currentTimeMillis()
            val j = Terminal.find(cmd.substringBefore(' '))!!.run(cmd.substringAfter(' ', ""), live)
            val o = JsonParser.parseString(j).asJsonObject
            println("LIVE /$cmd (${System.currentTimeMillis() - t0} ms): " + (o["error"]?.asString ?: o["summary"].asString).take(400))
            o.getAsJsonObject("card")?.getAsJsonArray("blocks")?.map { it.asJsonObject }?.forEach { b ->
                println("LIVE    [${b["type"].asString}] ${b["title"]?.takeIf { !it.isJsonNull }?.asString ?: ""} " +
                    (b.getAsJsonArray("rows")?.take(3)?.joinToString(" | ") { r -> r.asJsonObject.let { (it["title"] ?: it["label"] ?: it["symbol"])?.asString.orEmpty().take(70) + " — " + (it["sub"]?.asString ?: "").take(80) } } ?: ""))
            }
        }
    }
}
