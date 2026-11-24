package com.lucaslu.tickhub.domain

import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class RuleEngineTest {

    private fun rule(
        type: RuleType,
        hour: Int = 8,
        minute: Int = 0,
        onceDate: String? = null,
        weekdays: List<Int> = emptyList(),
        intervalDays: Int = 7,
        startDate: String? = null
    ) = Rule(
        id = "test", workflowId = 1, type = type, hour = hour, minute = minute,
        onceDate = onceDate, weekdays = weekdays, intervalDays = intervalDays, startDate = startDate
    )

    // ---- DAILY ----

    @Test
    fun daily_beforeTime_firesToday() {
        val r = rule(RuleType.DAILY, hour = 7, minute = 17)
        val from = LocalDateTime.of(2026, 11, 24, 6, 0)
        assertEquals(
            LocalDateTime.of(2026, 11, 24, 7, 17),
            RuleEngine.computeNextFire(r, from)
        )
    }

    @Test
    fun daily_afterTime_firesTomorrow() {
        val r = rule(RuleType.DAILY, hour = 7, minute = 17)
        val from = LocalDateTime.of(2026, 11, 24, 9, 0)
        assertEquals(
            LocalDateTime.of(2026, 11, 25, 7, 17),
            RuleEngine.computeNextFire(r, from)
        )
    }

    @Test
    fun daily_exactTime_firesTomorrow() {
        val r = rule(RuleType.DAILY, hour = 7, minute = 17)
        val from = LocalDateTime.of(2026, 11, 24, 7, 17)
        assertEquals(
            LocalDateTime.of(2026, 11, 25, 7, 17),
            RuleEngine.computeNextFire(r, from)
        )
    }

    @Test
    fun daily_crossYear() {
        val r = rule(RuleType.DAILY, hour = 8, minute = 0)
        val from = LocalDateTime.of(2026, 12, 31, 9, 0)
        assertEquals(
            LocalDateTime.of(2027, 1, 1, 8, 0),
            RuleEngine.computeNextFire(r, from)
        )
    }

    // ---- WEEKDAYS ----

    @Test
    fun weekdays_fridayAfterTime_firesNextMonday() {
        val r = rule(RuleType.WEEKDAYS, hour = 18, minute = 17)
        // 2026-11-27 is Friday
        val from = LocalDateTime.of(2026, 11, 27, 19, 0)
        assertEquals(
            LocalDateTime.of(2026, 11, 30, 18, 17),
            RuleEngine.computeNextFire(r, from)
        )
    }

    @Test
    fun weekdays_mondayBeforeTime_firesSameMonday() {
        val r = rule(RuleType.WEEKDAYS, hour = 9, minute = 0)
        // 2026-11-23 is Monday
        val from = LocalDateTime.of(2026, 11, 23, 8, 0)
        assertEquals(
            LocalDateTime.of(2026, 11, 23, 9, 0),
            RuleEngine.computeNextFire(r, from)
        )
    }

    @Test
    fun weekdays_saturday_firesNextMonday() {
        val r = rule(RuleType.WEEKDAYS, hour = 9, minute = 0)
        // 2026-11-28 is Saturday
        val from = LocalDateTime.of(2026, 11, 28, 6, 0)
        assertEquals(
            LocalDateTime.of(2026, 11, 30, 9, 0),
            RuleEngine.computeNextFire(r, from)
        )
    }

    // ---- WEEKLY ----

    @Test
    fun weekly_monWedFri_picksNextSelectedDay() {
        val r = rule(RuleType.WEEKLY, hour = 10, minute = 0, weekdays = listOf(1, 3, 5))
        // 2026-11-24 is Tuesday
        val from = LocalDateTime.of(2026, 11, 24, 8, 0)
        assertEquals(
            LocalDateTime.of(2026, 11, 25, 10, 0), // Wednesday
            RuleEngine.computeNextFire(r, from)
        )
    }

    @Test
    fun weekly_emptyDays_returnsNull() {
        val r = rule(RuleType.WEEKLY, weekdays = emptyList())
        val from = LocalDateTime.of(2026, 11, 24, 8, 0)
        assertNull(RuleEngine.computeNextFire(r, from))
    }

    // ---- EVERY_N_DAYS ----

    @Test
    fun every7days_anchorBefore_firesOnAnchorPlusN() {
        val r = rule(RuleType.EVERY_N_DAYS, hour = 12, minute = 0, intervalDays = 7, startDate = "2026-11-01")
        val from = LocalDateTime.of(2026, 11, 3, 8, 0)
        assertEquals(
            LocalDateTime.of(2026, 11, 8, 12, 0),
            RuleEngine.computeNextFire(r, from)
        )
    }

    @Test
    fun every30days_crossMonth() {
        val r = rule(RuleType.EVERY_N_DAYS, hour = 9, minute = 0, intervalDays = 30, startDate = "2026-11-01")
        val from = LocalDateTime.of(2026, 11, 15, 8, 0)
        assertEquals(
            LocalDateTime.of(2026, 12, 1, 9, 0),
            RuleEngine.computeNextFire(r, from)
        )
    }

    @Test
    fun everyNDays_leapYear February() {
        val r = rule(RuleType.EVERY_N_DAYS, hour = 9, minute = 0, intervalDays = 1, startDate = "2028-02-28")
        val from = LocalDateTime.of(2028, 2, 28, 10, 0)
        assertEquals(
            LocalDateTime.of(2028, 2, 29, 9, 0), // 2028 is a leap year
            RuleEngine.computeNextFire(r, from)
        )
    }

    // ---- ONCE ----

    @Test
    fun once_futureDate_firesAtThatTime() {
        val r = rule(RuleType.ONCE, hour = 15, minute = 30, onceDate = "2026-12-25")
        val from = LocalDateTime.of(2026, 11, 24, 8, 0)
        assertEquals(
            LocalDateTime.of(2026, 12, 25, 15, 30),
            RuleEngine.computeNextFire(r, from)
        )
    }

    @Test
    fun once_pastDate_returnsNull() {
        val r = rule(RuleType.ONCE, hour = 15, minute = 30, onceDate = "2026-11-01")
        val from = LocalDateTime.of(2026, 11, 24, 8, 0)
        assertNull(RuleEngine.computeNextFire(r, from))
    }

    @Test
    fun once_invalidDate_returnsNull() {
        val r = rule(RuleType.ONCE, onceDate = "not-a-date")
        val from = LocalDateTime.of(2026, 11, 24, 8, 0)
        assertNull(RuleEngine.computeNextFire(r, from))
    }
}
