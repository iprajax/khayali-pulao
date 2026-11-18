package com.khayali.pulao

import com.khayali.pulao.ai.data.Alerts
import com.khayali.pulao.ai.data.Bhavcopy
import com.khayali.pulao.ai.data.Harvest
import com.khayali.pulao.ai.data.HealthScore
import com.khayali.pulao.ai.data.Panel
import com.khayali.pulao.ai.data.Positioning
import com.khayali.pulao.ai.data.Screener
import com.khayali.pulao.ai.data.Surveillance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Surveillance and positioning use real NSE files saved on 18 Nov 2026; the rest use made-up numbers. */
class ProTest {
    private fun res(n: String) = File("src/test/resources/data/$n").readText()

    @Test fun surveillanceListsParse() {
        val f = Surveillance.parse(res("nse_asm.json"), res("nse_gsm.json"), res("nse_esm.json"))
        assertTrue(f.size > 300)
        assertEquals("Long-term ASM", f.getValue("AAATECH").first().list)
        assertTrue(f.values.flatten().any { it.list == "GSM" } && f.values.flatten().any { it.list == "ESM" } && f.values.flatten().any { it.list == "Short-term ASM" })
        assertTrue(Surveillance.parse("", "not json", "[]").isEmpty())
    }

    @Test fun positioningParses() {
        val r = Positioning.parse(res("fao_17112026.csv"))
        assertTrue(r.keys.containsAll(listOf("Client", "DII", "FII", "Pro")))
        val c = r.getValue("Client"); assertEquals(170907L, c.futIdxLong); assertEquals(115613L, c.futIdxShort)
        assertEquals(170907.0 * 100 / (170907 + 115613), c.longPct, 1e-9)
        assertTrue(Positioning.url(LocalDate.of(2026, 11, 17)).endsWith("/content/nsccl/fao_participant_oi_17112026.csv"))
    }

    @Test fun screenerUnderstandsPlainWords() {
        fun labels(q: String) = Screener.parse(q).map { it.label }
        assertEquals(listOf("Above 200-day average", "Within 5% of 52-week high", "Volume 2× usual or more"), labels("above 200 day near 52 week high with a volume spike"))
        assertEquals(listOf("RSI below 30 (oversold)"), labels("oversold stocks"))
        assertTrue(labels("up 20% in a year golden cross").containsAll(listOf("Up 20%+ in a year", "50-day above 200-day (uptrend)")))
        assertTrue(labels("down 30% from high").contains("30%+ below 52-week high"))
        assertTrue(labels("hello there").isEmpty())
        // the checks run on real numbers
        val days = Bhavcopy.tradingDayCandidates(LocalDate.of(2025, 6, 2), LocalDate.of(2026, 11, 13))
        var a = 100.0; var b = 100.0
        val p = Panel.build(days.mapIndexed { i, d -> val pa = a; val pb = b; a *= 1.003; b *= 0.998
            d to listOf(Bhavcopy.Bar("UPCO", "EQ", pa, a, pa, a, pa, if (i == days.size - 1) 5_000_000 else 1_000_000, a * 1e6, 1, ""), Bhavcopy.Bar("DOWNCO", "EQ", pb, pb, b, b, pb, 1_000_000, b * 1e6, 1, "")) })
        val up = Screener.facts(p, p.of("UPCO")!!, p.days - 1)!!; val dn = Screener.facts(p, p.of("DOWNCO")!!, p.days - 1)!!
        val checks = Screener.parse("above 200 day near 52 week high volume spike")
        assertTrue(checks.all { it.test(up) }); assertTrue(checks.none { it.test(dn) })
    }

    @Test fun healthScoreRewardsSpreadAndPunishesConcentration() {
        val spread = (1..15).associate { "S$it" to 1.0 / 15 }
        val good = HealthScore.compute(spread, spread.keys.associateWith { "Sector${it.drop(1).toInt() % 5}" }, spread.mapValues { 0.9 }, spread.mapValues { 0.2 }, spread.mapValues { -0.05 }, spread.mapValues { 0.15 }, 0.10)
        val bad = HealthScore.compute(mapOf("ONE" to 0.8, "TWO" to 0.2), mapOf("ONE" to "IT", "TWO" to "IT"), mapOf("ONE" to 1.5), mapOf("ONE" to 0.55), mapOf("ONE" to -0.35), mapOf("ONE" to -0.2), 0.10, setOf("ONE"))
        assertTrue("${good.score} vs ${bad.score}", good.score > 70 && bad.score < 25)
        assertTrue(bad.parts.any { it.name == "Surveillance" })
    }

    @Test fun harvestMaths() {
        val p = Harvest.plan(listOf(Harvest.Lot("A", 100_000.0, 70_000.0), Harvest.Lot("B", 50_000.0, 45_000.0), Harvest.Lot("C", 10_000.0, 20_000.0)), 200_000.0, LocalDate.of(2026, 11, 18))
        assertEquals(35_000.0, p.totalLoss, 1e-6); assertEquals(35_000.0, p.offset, 1e-6)
        assertEquals(7_000.0, p.saveStcg, 1e-6); assertEquals(35_000.0 * 0.125, p.saveLtcg, 1e-6)
        assertEquals(133L, p.daysLeft); assertEquals(listOf("A", "B"), p.losses.map { it.symbol })
        assertEquals(0.0, Harvest.plan(p.losses, 0.0, LocalDate.of(2026, 11, 18)).offset, 1e-9)
    }

    @Test fun alertsParseAndFireOncePerDay() {
        val d = LocalDate.of(2026, 11, 18)
        assertEquals(Alerts.Kind.ABOVE, Alerts.parse("INFY above 1,800", 1, d)!!.kind)
        assertEquals(Alerts.Kind.BELOW, Alerts.parse("@tcs below ₹3000", 2, d)!!.kind)
        assertEquals(5.0, Alerts.parse("SBIN moves 5%", 3, d)!!.value, 1e-9)
        assertNull(Alerts.parse("INFY maybe 5", 4, d)); assertNull(Alerts.parse("INFY above 0", 4, d))
        val days = Bhavcopy.tradingDayCandidates(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 13))
        val p = Panel.build(days.mapIndexed { i, x -> x to listOf(Bhavcopy.Bar("INFY", "EQ", 1700.0, 1700.0, 1700.0, if (i == days.size - 1) 1810.0 else 1700.0, 1700.0, 1, 1.0, 1, "")) })
        val rules = listOf(Alerts.parse("INFY above 1800", 1, d)!!, Alerts.parse("INFY below 1500", 2, d)!!, Alerts.parse("INFY moves 5", 3, d)!!)
        val hits = Alerts.evaluate(rules, p, emptyMap())
        assertEquals(listOf(1L, 3L), hits.map { it.rule.id })
        assertTrue(hits[0].text.startsWith("INFY closed above ₹1,800"))
        assertTrue("already fired today", Alerts.evaluate(rules, p, mapOf(1L to p.dates.last().toString(), 3L to p.dates.last().toString())).isEmpty())
        assertNotNull(Alerts.distance(rules[1], 1810.0, 0.06))
    }
}
