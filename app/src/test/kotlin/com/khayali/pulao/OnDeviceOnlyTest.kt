package com.khayali.pulao

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the "nothing leaves your phone" promise shown in the app. */
class OnDeviceOnlyTest {
    private val main = File("src/main")
    private val manifest = File(main, "AndroidManifest.xml").readText()

    @Test fun noNetworkPermissionEverRequested() {
        val perms = Regex("""<uses-permission[^>]*>""").findAll(manifest).map { it.value }.toList()
        // Every permission line must be a removal.
        perms.forEach { assertTrue("unexpected permission: $it", it.contains("""tools:node="remove"""")) }
        assertTrue(perms.any { it.contains("android.permission.INTERNET") })
    }

    @Test fun backupAndTransferOff() {
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        val rules = File(main, "res/xml/data_extraction_rules.xml").readText()
        assertTrue(rules.contains("<cloud-backup>") && rules.contains("<device-transfer>"))
    }

    @Test fun noNetworkingCode() {
        val banned = listOf("java.net.", "HttpURLConnection", "okhttp", "Socket(", "WebView", "retrofit", "ktor")
        File(main, "kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val src = f.readText()
            banned.forEach { b -> assertFalse("${f.name} uses $b", src.contains(b)) }
        }
    }
}
