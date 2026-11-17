package com.khayali.pulao

import com.google.gson.JsonParser
import com.khayali.pulao.ai.ToolJson
import com.khayali.pulao.ai.WebData
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the real quote feed with a public symbol. Skipped unless KP_LIVE=1, so normal test
 * runs and CI stay offline:  KP_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*LiveWebTest'
 */
class LiveWebTest {
    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { setRequestProperty("User-Agent", "Mozilla/5.0"); connectTimeout = 10_000; readTimeout = 15_000 }
        return (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
    }

    @Test fun quoteFeedStillHasTheShapeWeParse() {
        assumeTrue(System.getenv("KP_LIVE") == "1")
        for ((input, period) in listOf("INFY" to "1m", "NIFTY" to "5y", "SENSEX" to "1w")) {
            val out = WebData.parseChart(get(WebData.chartUrl(WebData.candidates(input).first(), period)), period)
            assertTrue("$input: $out", ToolJson.ok(out))
            val o = JsonParser.parseString(out).asJsonObject
            assertTrue(o["price"].asDouble > 0 && o.getAsJsonArray("points").size() > 3)
            println("LIVE $input $period → ${o["summary"].asString} [${o.getAsJsonArray("points").size()} points, ${o.getAsJsonArray("events").size()} events]")
        }
        val sym = WebData.parseLookup(get(WebData.lookupUrl("Infosys")))
        println("LIVE lookup Infosys → $sym")
        assertTrue(sym == "INFY.NS" || sym == "INFY.BO")
        assertTrue(ToolJson.error(WebData.parseChart(get(WebData.chartUrl("NOSUCHSYMBOLXYZ.NS", "1m")), "1m")) != null)
    }
}
