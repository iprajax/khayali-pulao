package com.khayali.pulao.term

import com.khayali.pulao.ai.term.Knowledge
import com.khayali.pulao.ai.term.Terminal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KnowledgeTest {
    private val file = File("../docs/knowledge/screens-v1.kb")
    private val kb by lazy { Knowledge.parse(file.readBytes()) }

    @Test fun publishedFileMatchesWhatTheAppExpects() {
        val spec = com.khayali.pulao.ai.data.KnowledgePack.SPEC
        assertEquals(spec.bytes, file.length())
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("the app only accepts this exact file", spec.sha256, sha)
        assertTrue(spec.url.endsWith("/knowledge/" + file.name))
        assertTrue("the app stays light: the bank is downloaded, not bundled", !File("src/main/assets/knowledge").exists())
    }

    @Test fun loadsTheShippedBank() {
        assertEquals(500, kb.screens.size)
        assertTrue(kb.screens.all { it.query.isNotBlank() && it.url.startsWith("https://www.screener.in/screens/") })
        assertTrue("vectors are normalised", Math.abs(kb.cosine(0, 0) - 1.0) < 1e-3)
        assertEquals("Quality", kb.themes().first().first)
    }

    @Test fun searchFindsByWordsThenByMeaning() {
        val hits = kb.search("debt free")
        assertTrue(hits.take(3).all { it.screen.name.lowercase().contains("debt") || it.screen.query.lowercase().contains("debt") })
        val magic = kb.search("magic formula").first().screen
        assertTrue(magic.name, magic.name.lowercase().contains("magic"))
        assertTrue(kb.search("zzqx").isEmpty())
        val i = kb.screens.indexOfFirst { it.name.lowercase().contains("magic") }
        assertTrue(kb.similar(i).isNotEmpty())
    }

    @Test fun commandsShowTheBank() = runBlocking {
        val f = TermTesting.Fake(knowledge = kb)
        val list = Terminal.find("screens")!!.run("", f); assertNull(TermTesting.error(list))
        val j = Terminal.find("screens")!!.run("quality low debt", f); assertNull(TermTesting.error(j))
        assertTrue(j.contains("screener.in"))
        val l = Terminal.find("learn")!!.run("magic formula", f)
        assertTrue(l.contains("Community screens using this idea"))
        assertTrue(TermTesting.error(Terminal.find("screens")!!.run("x", TermTesting.Fake()))!!.isNotBlank())
    }
}
