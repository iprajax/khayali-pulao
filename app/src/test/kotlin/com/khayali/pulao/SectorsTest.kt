package com.khayali.pulao

import com.khayali.pulao.ai.data.Bhavcopy
import com.khayali.pulao.ai.data.MarketPack
import com.khayali.pulao.ai.data.Panel
import com.khayali.pulao.ai.data.Sectors
import com.khayali.pulao.ai.data.Sectors.Period
import com.khayali.pulao.ai.term.Sec
import com.khayali.pulao.ai.term.SectorBoardFeature
import com.khayali.pulao.ai.term.SectorFeature
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.pow

class SectorsTest {
    /** A weekday series growing [yearly] a year from 2021-01-04 to 2026-11-17. */
    private fun series(yearly: Double, from: LocalDate = LocalDate.of(2021, 1, 4)): Sectors.Series {
        val days = Bhavcopy.tradingDayCandidates(from, LocalDate.of(2026, 11, 17))
        return Sectors.Series(days.map { d -> d to 1000 * (1 + yearly).pow((d.toEpochDay() - from.toEpochDay()) / 365.25) })
    }

    @Test fun namesMatchHowPeopleSayThem() {
        assertEquals("Nifty Bank", Sectors.find("bank")!!.index)
        assertEquals("Nifty Private Bank", Sectors.find("private bank")!!.index)
        assertEquals("Nifty PSU Bank", Sectors.find("PSU Bank")!!.index)
        assertEquals("Nifty IT", Sectors.find("nifty it")!!.index)
        assertEquals("Nifty Oil & Gas", Sectors.find("oil and gas")!!.index)
        assertEquals("Nifty Healthcare Index", Sectors.find("Healthcare")!!.index)
        assertNull(Sectors.find("cricket"))
        assertEquals(18, Sectors.ALL.size)
        assertTrue(Sectors.membersUrl(Sectors.ALL[0]).startsWith("https://www.niftyindices.com/IndexConstituent/"))
    }

    @Test fun membersParseFromTheRealList() {
        val m = Sectors.parseMembers(File("src/test/resources/data/members_auto.csv").readText())
        assertTrue(m.size >= 10); assertTrue(m.all { it.matches(Regex("[A-Z0-9&\\-]+")) })
    }

    @Test fun periodsCagrAndRanges() {
        val s = series(0.10)
        val asOf = LocalDate.of(2026, 11, 17)
        assertEquals(0.10, s.cagr(Period.Y5, asOf)!!, 0.002)                 // ten percent a year, compounded
        assertEquals(0.10, s.change(Period.Y1, asOf)!!, 0.002)
        assertEquals(1.1.pow(5) - 1, s.change(Period.Y5, asOf)!!, 0.01)
        assertNull("history doesn't reach 5 years", series(0.1, LocalDate.of(2024, 1, 1)).change(Period.Y5, asOf))
        // a 5-year download starts on the next trading day: it still counts as 5 years
        assertTrue(series(0.1, LocalDate.of(2021, 11, 18)).change(Period.Y5, asOf) != null)
        assertTrue(s.change(Period.D1, asOf)!! in 0.0..0.002)
        assertEquals(LocalDate.of(2025, 12, 31), Period.YTD.start(asOf))
        assertEquals(Period.W1, Sectors.period("5d")); assertEquals(Period.YTD, Sectors.period("ytd"))
        assertEquals(LocalDate.of(2025, 1, 1) to LocalDate.of(2025, 6, 30), Sectors.range("from 2025-06-30 to 2025-01-01"))
        // the close on a weekend date is Friday's
        assertEquals(LocalDate.of(2026, 11, 13), s.at(LocalDate.of(2026, 11, 15))!!.first)
    }

    private fun book(): MarketPack.SectorBook {
        val series = mapOf("Nifty Bank" to series(0.12), "Nifty IT" to series(-0.05), "Nifty Auto" to series(0.20), Sectors.BENCHMARK to series(0.10))
        return MarketPack.SectorBook(series, mapOf("Nifty Bank" to Triple(15.2, 2.4, 0.9)), mapOf("Nifty Auto" to listOf("RISER", "FADER", "GONE")))
    }

    @Test fun theBoardRanksEverySectorAndEachRowOpensIt() {
        val j = SectorBoardFeature.build(book(), Sec.window("1y", Period.D1))
        val o = JsonParser.parseString(j).asJsonObject
        assertTrue(o["summary"].asString, o["summary"].asString.startsWith("Over 1Y") && o["summary"].asString.contains("Auto led"))
        val rows = o.getAsJsonObject("card").getAsJsonArray("blocks").map { it.asJsonObject }.first { it["type"].asString == "rows" }.getAsJsonArray("rows").map { it.asJsonObject }
        assertEquals(listOf("Auto", "Bank", "IT"), rows.map { it["title"].asString })
        assertEquals("/sector Auto", rows[0]["action"].asString)
        assertTrue(rows[1]["sub"].asString.contains("P/E 15.2"))
        // a custom date range
        val r = JsonParser.parseString(SectorBoardFeature.build(book(), Sec.window("2025-01-01 to 2025-12-31", Period.D1))).asJsonObject
        assertTrue(r["summary"].asString, r["summary"].asString.startsWith("Over 1 Jan 2025 – 31 Dec 2025"))
    }

    @Test fun aBoardWithoutEnoughHistorySaysSo() {
        val short = MarketPack.SectorBook(mapOf("Nifty Bank" to series(0.1, LocalDate.of(2025, 1, 6))), emptyMap(), emptyMap())
        val j = SectorBoardFeature.build(short, Sec.window("5y", Period.D1))
        assertTrue(j, j.contains("reach back") && j.contains("/data 5y"))
    }

    @Test fun aSectorShowsItsPeriodsAndItsMovers() {
        val days = Bhavcopy.tradingDayCandidates(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 11, 17))
        val px = mutableMapOf("RISER" to 100.0, "FADER" to 100.0)
        val panel = Panel.build(days.map { d -> d to px.keys.map { s -> val prev = px[s]!!; val c = prev * (if (s == "RISER") 1.004 else 0.997); px[s] = c
            Bhavcopy.Bar(s, "EQ", prev, c, prev, c, prev, 1_000_000, c * 1e6, 10, "") } })
        val j = SectorFeature.build(book(), panel, Sectors.find("auto")!!, Sec.window("1m", Period.M1), mine = setOf("RISER"))
        val blocks = JsonParser.parseString(j).asJsonObject.getAsJsonObject("card").getAsJsonArray("blocks").map { it.asJsonObject }
        val kpis = blocks.first { it["type"].asString == "kpis" }.getAsJsonArray("items").map { it.asJsonObject["label"].asString }
        assertTrue(kpis.containsAll(listOf("1D", "1W", "1M", "3M", "6M", "YTD", "1Y", "3Y CAGR", "5Y CAGR")))
        val movers = blocks.filter { it["type"].asString == "movers" }
        assertEquals("RISER", movers.first { it["title"].asString.startsWith("Gainers") }.getAsJsonArray("rows")[0].asJsonObject["symbol"].asString)
        assertEquals("FADER", movers.first { it["title"].asString.startsWith("Losers") }.getAsJsonArray("rows")[0].asJsonObject["symbol"].asString)
        assertTrue(j.contains("1 member not in your price history"))          // GONE
        assertTrue(blocks.any { it["type"].asString == "viz" })
    }
}
