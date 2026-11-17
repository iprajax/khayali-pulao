package com.khayali.pulao

import com.khayali.pulao.ai.data.Refresh
import com.khayali.pulao.ai.data.Refresh.Every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class RefreshTest {
    private fun at(s: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(s), Refresh.IST)
    private fun ms(s: String) = at(s).toInstant().toEpochMilli()

    @Test fun theLatestFileFollowsThePhoneClock() {
        assertEquals(LocalDate.of(2026, 11, 16), Refresh.latestTradingDay(at("2026-11-17T18:59")))   // Tuesday, before 7 pm: Monday
        assertEquals(LocalDate.of(2026, 11, 17), Refresh.latestTradingDay(at("2026-11-17T19:01")))   // after 7 pm: today
        assertEquals(LocalDate.of(2026, 11, 20), Refresh.latestTradingDay(at("2026-11-22T10:00")))   // Sunday: Friday
        assertEquals(LocalDate.of(2026, 11, 20), Refresh.latestTradingDay(at("2026-11-23T09:00")))   // Monday morning: Friday
        // a phone set to another time zone still uses Indian time
        assertEquals(LocalDate.of(2026, 11, 17), Refresh.latestTradingDay(ZonedDateTime.of(2026, 11, 17, 15, 0, 0, 0, java.time.ZoneId.of("Europe/London"))))
    }

    @Test fun dailyIsDueOncePerEveningClose() {
        assertTrue(Refresh.due(Every.DAILY, ms("2026-11-17T12:00"), at("2026-11-17T19:30")))       // ran at noon; today's file is out
        assertFalse(Refresh.due(Every.DAILY, ms("2026-11-17T19:45"), at("2026-11-17T23:00")))      // already ran this evening
        assertFalse(Refresh.due(Every.DAILY, ms("2026-11-16T20:00"), at("2026-11-17T10:00")))      // last night's run covers this morning
        assertTrue(Refresh.due(Every.DAILY, null, at("2026-11-17T10:00")))
    }

    @Test fun longerCadencesStartOnTheirBoundaries() {
        assertEquals(at("2026-11-14T00:00"), Refresh.periodStart(Every.WEEKLY, at("2026-11-17T10:00")))      // last Saturday
        assertEquals(at("2026-11-01T00:00"), Refresh.periodStart(Every.MONTHLY, at("2026-11-17T10:00")))
        assertEquals(at("2026-10-01T00:00"), Refresh.periodStart(Every.QUARTERLY, at("2026-11-17T10:00")))
        assertEquals(at("2026-01-01T00:00"), Refresh.periodStart(Every.YEARLY, at("2026-11-17T10:00")))
        assertEquals(at("2026-12-01T00:00"), Refresh.nextStart(Every.MONTHLY, at("2026-11-17T10:00")))
        assertEquals(at("2027-01-01T00:00"), Refresh.nextStart(Every.QUARTERLY, at("2026-11-17T10:00")))
        assertTrue(Refresh.due(Every.MONTHLY, ms("2026-10-31T23:00"), at("2026-11-01T08:00")))
        assertFalse(Refresh.due(Every.QUARTERLY, ms("2026-10-02T09:00"), at("2026-12-31T23:59")))
        assertEquals(5, Refresh.TASKS.size); assertEquals(Every.entries.toSet(), Refresh.TASKS.map { it.every }.toSet())
    }
}
