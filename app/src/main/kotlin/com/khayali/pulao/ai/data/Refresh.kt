package com.khayali.pulao.ai.data

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * The refresh schedule for what the phone keeps, timed by the phone's own clock in Indian time.
 * A task is due when it hasn't run since its current period began (today's 7 pm close, this
 * Saturday, the 1st of the month, of the quarter, of the year). Pure and unit-tested; the runner
 * in [Refresher] does the work.
 */
object Refresh {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    /** NSE publishes the day's file in the evening; this is when "today" counts as available. */
    val CLOSE_FILE: LocalTime = LocalTime.of(19, 0)

    enum class Every(val label: String) {
        DAILY("Every trading day after 7 pm"), WEEKLY("Every Saturday"), MONTHLY("After each month ends"),
        QUARTERLY("After each quarter ends"), YEARLY("Each January")
    }
    data class Task(val id: String, val every: Every, val title: String, val what: String)

    val TASKS = listOf(
        Task("daily-prices", Every.DAILY, "Today's closing prices", "Fetches the day's NSE file once it's published — weekends and holidays have none."),
        Task("weekly-check", Every.WEEKLY, "Fill gaps & check files", "Re-fetches any trading day that was missed and re-checks the knowledge bank's checksum."),
        Task("monthly-rebuild", Every.MONTHLY, "Refresh the stock universe", "Rebuilds the analysis snapshot so month-end strategy picks use this month's liquid stocks."),
        Task("quarterly-compact", Every.QUARTERLY, "Tidy the database", "Compacts the market database to reclaim space."),
        Task("yearly-roll", Every.YEARLY, "Roll the history window", "Drops days older than your history length plus a year of warm-up, so storage stays steady."),
    )

    /** When the current period of [every] began, at [now]. */
    fun periodStart(every: Every, now: ZonedDateTime): ZonedDateTime {
        val t = now.withZoneSameInstant(IST); val day = t.toLocalDate()
        return when (every) {
            Every.DAILY -> (if (t.toLocalTime() >= CLOSE_FILE) day else day.minusDays(1)).atTime(CLOSE_FILE).atZone(IST)
            Every.WEEKLY -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)).atStartOfDay(IST)
            Every.MONTHLY -> day.withDayOfMonth(1).atStartOfDay(IST)
            Every.QUARTERLY -> day.withMonth((day.monthValue - 1) / 3 * 3 + 1).withDayOfMonth(1).atStartOfDay(IST)
            Every.YEARLY -> day.withDayOfYear(1).atStartOfDay(IST)
        }
    }

    /** When the next period begins. */
    fun nextStart(every: Every, now: ZonedDateTime): ZonedDateTime {
        val s = periodStart(every, now)
        return when (every) {
            Every.DAILY -> s.plusDays(1); Every.WEEKLY -> s.plusWeeks(1); Every.MONTHLY -> s.plusMonths(1)
            Every.QUARTERLY -> s.plusMonths(3); Every.YEARLY -> s.plusYears(1)
        }
    }

    /** Due if it never ran, or last ran before the current period began. */
    fun due(every: Every, lastRunMs: Long?, now: ZonedDateTime): Boolean = lastRunMs == null || lastRunMs < periodStart(every, now).toInstant().toEpochMilli()

    /** The newest day whose file should exist at [now]: today after 7 pm, else the previous weekday (holidays are found by a missing file). */
    fun latestTradingDay(now: ZonedDateTime): java.time.LocalDate {
        val t = now.withZoneSameInstant(IST)
        var d = if (t.toLocalTime() >= CLOSE_FILE) t.toLocalDate() else t.toLocalDate().minusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
        return d
    }
}
