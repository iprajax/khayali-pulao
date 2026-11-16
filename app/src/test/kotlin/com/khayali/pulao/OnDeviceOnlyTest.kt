package com.khayali.pulao

import com.khayali.pulao.ai.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the privacy promise: the only network use is the one-time model download
 * from huggingface.co through Android's DownloadManager. Nothing is ever uploaded.
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

    @Test fun noNetworkingCodeAtAll() {
        // The download is done by the system DownloadManager; the app itself never opens a connection.
        val banned = listOf("java.net.", "HttpURLConnection", "okhttp", "Socket(", "WebView", "retrofit", "ktor", "URLConnection")
        // Exception: the opt-in Kite MCP client talks to Zerodha's official server only.
        sources.filter { it.name != "KiteMcpClient.kt" }.forEach { f -> banned.forEach { b -> assertFalse("${f.name} uses $b", f.readText().contains(b)) } }
    }

    @Test fun onlyHuggingFaceIsEverAddressed() {
        val urls = sources.flatMap { f -> Regex("""https?://[^\s"')$\\]+""").findAll(f.readText()).map { f.name to it.value }.toList() }
            .filterNot { (f, u) -> f == "KiteData.kt" && u.startsWith("https://kite") }   // regex that recognises Zerodha's login link; not a request
            .filterNot { (f, u) -> f == "KiteData.kt" && u == "https://iprajax.github.io/khayali-pulao/callback.html" }   // OAuth redirect page (static, on our own site)
            .filterNot { (f, u) -> f == "ShareAnswer.kt" && u.startsWith("https://iprajax.github.io/khayali-pulao") }   // text in a share, not a request
        val hosts = sources.filter { it.readText().contains("https://" + "\$" + "HOST") }   // ModelSpec + KiteData
        assertTrue("model URL must be built from ModelSpec.HOST", hosts.isNotEmpty())
        urls.forEach { (file, u) -> fail("$file hard-codes $u — use ModelSpec") }
        assertEquals("huggingface.co", ModelSpec.HOST)
        assertEquals("mcp.kite.trade", com.khayali.pulao.ai.KiteData.HOST)
        assertTrue(ModelSpec.URL.startsWith("https://huggingface.co/"))
        assertEquals(64, ModelSpec.SHA256.length)
        assertTrue("only ModelManager may use DownloadManager",
            sources.filter { it.readText().contains("DownloadManager.Request") }.map { it.name } == listOf("ModelManager.kt"))
    }

    private fun fail(msg: String): Nothing = throw AssertionError(msg)
}
