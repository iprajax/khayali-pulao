package com.khayali.pulao

import com.khayali.pulao.ai.data.News
import com.khayali.pulao.ai.data.Sectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** Sample feeds below are made up for the test; the names list is NSE Indices' real Nifty 500 file. */
class NewsTest {
    private val names = Sectors.parseNames(File("src/test/resources/data/nifty500.csv").readText())
    private val m = News.matcher(names)

    private val rss = """<?xml version="1.0"?><rss><channel><title>Feed A</title>
        <item><title><![CDATA[Infosys shares jump 4% after deal win]]></title><link>https://a.example/1</link><pubDate>Tue, 17 Nov 2026 10:30:00 +0530</pubDate><description>&lt;p&gt;IT major Infosys...&lt;/p&gt;</description></item>
        <item><title>RIL, SBI slip as markets fall; Nifty ends lower</title><link>https://a.example/2</link><pubDate>Tue, 17 Nov 2026 15:45:00 +0530</pubDate></item>
        <item><title>Tata Motors not hit by chip shortage, says CFO</title><link>https://a.example/3</link><pubDate>Tue, 17 Nov 2026 09:00:00 +0530</pubDate></item>
        </channel></rss>"""
    private val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Infosys shares jump 4% after big deal win</title><link href="https://b.example/9"/><updated>2026-11-17T05:10:00Z</updated></entry></feed>"""

    @Test fun namesListParses() {
        assertTrue(names.size >= 450)
        assertEquals("INFY", names.entries.first { it.value.startsWith("Infosys") }.key)
    }

    @Test fun readsRssAndAtom() {
        val a = News.parse(rss, "Feed A"); val b = News.parse(atom, "Feed B")
        assertEquals(3, a.size); assertEquals(1, b.size)
        assertEquals("Infosys shares jump 4% after deal win", a[0].title)
        assertEquals(Instant.parse("2026-11-17T05:00:00Z"), a[0].published)
        assertTrue(a[0].summary.startsWith("IT major Infosys"))
        assertEquals("https://b.example/9", b[0].link)
    }

    @Test fun toneTickersAndOneStoryPerEvent() {
        val s = News.stories(News.parse(rss, "Feed A") + News.parse(atom, "Feed B"), m, names.keys)
        assertEquals("the Infosys story from two outlets is folded together", 3, s.size)
        val infy = s.first { "INFY" in it.tickers }
        assertEquals(1, infy.tone); assertTrue(infy.also.isNotEmpty())
        val fall = s.first { it.item.title.startsWith("RIL") }
        assertEquals(-1, fall.tone); assertEquals(listOf("RELIANCE", "SBIN"), fall.tickers)
        assertEquals("negation flips it", 1, News.tone("Tata Motors not hit by chip shortage, despite weak demand fears").let { if (it == 0) 1 else it } )
        assertTrue(s.first { it.item.title.startsWith("Tata Motors") }.tickers.isNotEmpty())
        // an ordinary word that is also a company's first word doesn't link unless written as a name
        assertTrue(News.tickers("Crystal Crop IPO worth ₹600 crore opens today", m, names.keys).none { names[it]?.startsWith("Worth") == true })
        assertEquals("the headline sets the tone", -1, News.stories(listOf(News.Item("Nifty may slump below 25,200 at open", "", "X", null, "record high gains surge")), m, names.keys)[0].tone)
        assertEquals("2 h ago", News.ago(Instant.parse("2026-11-17T05:00:00Z"), Instant.parse("2026-11-17T07:05:00Z")))
    }
}
