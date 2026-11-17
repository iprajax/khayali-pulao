package com.khayali.pulao

import com.khayali.pulao.ai.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the privacy promise: the portfolio never leaves the phone, and every use of the internet
 * is optional, switched on by the user, and limited to a named host:
 *  - huggingface.co  — one-time model download (Android's DownloadManager)
 *  - mcp.kite.trade  — read-only Kite MCP, only after "Connect Kite"
 *  - html.duckduckgo.com, query1.finance.yahoo.com, www.nseindia.in, www.moneycontrol.com — web lookups
 *    (search, quotes, market calendar), only after the user allows Web
 */
class OnDeviceOnlyTest {
    private val main = File("src/main")
    private val manifest = File(main, "AndroidManifest.xml").readText()
    private val sources = File(main, "kotlin").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test fun onlyInternetPermission() {
        val requested = Regex("""<uses-permission[^>]*>""").findAll(manifest).map { it.value }
            .filterNot { it.contains("""tools:node="remove"""") }.toList()
        assertEquals(listOf("android.permission.INTERNET"), requested.map { Regex("""android:name="([^"]+)"""").find(it)!!.groupValues[1] })
    }

    @Test fun backupAndTransferOff() {
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        val rules = File(main, "res/xml/data_extraction_rules.xml").readText()
        assertTrue(rules.contains("<cloud-backup>") && rules.contains("<device-transfer>"))
    }

    /** The only files allowed to open a connection. Everything else is pure, or draws local assets. */
    private val networkFiles = setOf("KiteMcpClient.kt", "WebClient.kt")

    @Test fun networkingLivesInTwoFilesOnly() {
        val banned = listOf("java.net.", "HttpURLConnection", "okhttp", "Socket(", "WebView", "retrofit", "ktor", "URLConnection")
        sources.filter { it.name !in networkFiles }.forEach { f ->
            banned.filter { !(f.name == "VizView.kt" && it == "WebView") }   // local-only chart view, checked below
                .forEach { b -> assertFalse("${f.name} uses $b", f.readText().contains(b)) }
        }
        // The chart WebView must block every network load and only serve bundled assets.
        val viz = sources.first { it.name == "VizView.kt" }.readText()
        assertTrue(viz.contains("blockNetworkLoads = true") && viz.contains("file:///android_asset/viz/") && viz.contains("allowFileAccess = false"))
        assertFalse("charts must not load remote scripts", File(main, "assets/viz/viz.html").readText().contains("http"))
    }

    @Test fun webAccessIsOptInAndLockedDown() {
        // Off until the user turns it on.
        assertTrue(File(main, "kotlin/com/khayali/pulao/Prefs.kt").readText().contains("""var webOn by bool("web_on", false)"""))
        val session = sources.first { it.name == "ChatSession.kt" }.readText()
        assertTrue("the web tool must not exist while Web is off", session.contains("if (!webOn()) return emptyList()"))
        val web = sources.first { it.name == "WebClient.kt" }.readText()
        // The hidden search view may load the results page and nothing else; cookies off; generic user agent.
        // The hidden browser view may load pages from the one host it was opened for, over https, and nothing else.
        assertTrue(web.contains("req.url.host == host") && web.contains("""req.url.scheme == "https""""))
        val pageCalls = Regex("""page\(([^)]*)\)""").findAll(web).map { it.groupValues[1] }.filter { it.contains(",") && !it.contains("url: String") }.toList()
        assertTrue(pageCalls.toString(), pageCalls.isNotEmpty() && pageCalls.all { it.contains("WebData.SEARCH_HOST") || it.contains("MarketData.NSE_HOST") })
        // Cookies: never for search; for NSE only while a page loads, then wiped.
        assertTrue(web.contains("WebData.SEARCH_HOST, text = false, cookies = false") && web.contains("removeAllCookies(null)"))
        assertTrue(web.contains("genericUserAgent") && web.contains("allowFileAccess = false"))
        assertEquals("www.nseindia.in", com.khayali.pulao.ai.MarketData.NSE_HOST)
        assertEquals("www.moneycontrol.com", com.khayali.pulao.ai.MarketData.MC_HOST)
        // Calendar requests carry a date range or one symbol — the user's holdings are matched on the phone.
        val market = sources.first { it.name == "MarketData.kt" }.readText()
        assertFalse(market.contains("java.net") || market.contains("WebView"))
        assertTrue(web.contains("mine: Set<String>") && !Regex("""nse\([^)]*mine""").containsMatchIn(web))
        // Every outgoing query passes through the sanitizer (amounts and identifiers stripped).
        assertTrue(web.contains("WebData.sanitize(query)"))
        val data = sources.first { it.name == "WebData.kt" }.readText()
        assertTrue(Regex("""fun searchUrl\(query: String\) = .*sanitize\(query\)""").containsMatchIn(data))
        assertTrue(Regex("""fun lookupUrl\(name: String\) = .*sanitize\(name\)""").containsMatchIn(data))
        assertEquals("html.duckduckgo.com", com.khayali.pulao.ai.WebData.SEARCH_HOST)
        assertEquals("query1.finance.yahoo.com", com.khayali.pulao.ai.WebData.QUOTE_HOST)
        // The generic user agent carries no device model.
        val ua = com.khayali.pulao.ai.WebClient.genericUserAgent("Mozilla/5.0 (Linux; Android 17; SM-X000 Build/ABC; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.1 Mobile Safari/537.36")
        assertFalse(ua.contains("SM-X000") || ua.contains("Build/") || ua.contains("wv"))
        assertTrue(ua.contains("Chrome/141.0.0.0"))
    }

    @Test fun memoryAndChatAreLocalFilesOnly() {
        val stores = sources.first { it.name == "Stores.kt" }.readText()
        assertTrue(stores.contains("filesDir") && !stores.contains("getExternalFilesDir") && !stores.contains("http"))
    }

    @Test fun everyHostIsANamedConstant() {
        val urls = sources.flatMap { f -> Regex("""https?://[^\s"')$\\]+""").findAll(f.readText()).map { f.name to it.value }.toList() }
            .filterNot { (f, u) -> f == "KiteData.kt" && u.startsWith("https://kite") }   // regex that recognises Zerodha's login link; not a request
            .filterNot { (f, u) -> f == "KiteData.kt" && u == "https://iprajax.github.io/khayali-pulao/callback.html" }   // OAuth redirect page (static, on our own site)
            .filterNot { (f, u) -> f == "ShareAnswer.kt" && u.startsWith("https://iprajax.github.io/khayali-pulao") }   // text in a share, not a request
            .filterNot { (f, u) -> f == "Links.kt" && (u.startsWith("https://iprajax.github.io/khayali-pulao") || u.startsWith("https://github.com/iprajax/khayali-pulao")) }   // opened in browser
        val hosts = sources.filter { it.readText().contains("https://" + "\$" + "HOST") }   // ModelSpec + KiteData (WebData builds from SEARCH_HOST / QUOTE_HOST)
        assertTrue("model URL must be built from ModelSpec.HOST", hosts.isNotEmpty())
        urls.forEach { (file, u) -> fail("$file hard-codes $u — use ModelSpec") }
        assertEquals("huggingface.co", ModelSpec.HOST)
        assertEquals("mcp.kite.trade", com.khayali.pulao.ai.KiteData.HOST)
        assertTrue(ModelSpec.URL.startsWith("https://huggingface.co/"))
        assertEquals(64, ModelSpec.SHA256.length)
        assertEquals("only the model and the pack downloader may use DownloadManager", setOf("ModelManager.kt", "FilePack.kt"),
            sources.filter { it.readText().contains("DownloadManager.Request") }.map { it.name }.toSet())
        assertEquals("packs come only from the project's own site", "iprajax.github.io", java.net.URI(com.khayali.pulao.ai.data.KnowledgePack.SPEC.url).host)
    }

    private fun fail(msg: String): Nothing = throw AssertionError(msg)
}
