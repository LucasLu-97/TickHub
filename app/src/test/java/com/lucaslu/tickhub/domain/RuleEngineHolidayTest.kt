package com.lucaslu.tickhub.domain

import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class RuleEngineHolidayTest {

    /** Simplified 2026 holiday data: Oct 1-7 off, Oct 10 (Sat) makeup workday. */
    private val isWorkday: (LocalDate) -> Boolean = { d ->
        when (d.toString()) {
            in listOf("2026-10-01","2026-10-02","2026-10-03","2026-10-04","2026-10-05","2026-10-06","2026-10-07") -> false
            "2026-10-10" -> true // makeup work Saturday
            else -> d.dayOfWeek.value <= 5
        }
    }
    private val isHoliday: (LocalDate) -> Boolean = { d ->
        d.toString() in listOf("2026-10-01","2026-10-02","2026-10-03","2026-10-04","2026-10-05","2026-10-06","2026-10-07")
    }

    private fun rule(type: RuleType) = Rule(id = "r1", workflowIds = listOf(1, 2), type = type, hour = 9, minute = 0)

    @Test
    fun legalWorkday_skipsHolidayWeek() {
        // Sep 30 (Wed) after 9:00 -> next legal workday is Oct 8 (Thu)
        val from = LocalDateTime.of(2026, 9, 30, 10, 0)
        val next = RuleEngine.computeNextFire(rule(RuleType.LEGAL_WORKDAY), from, isWorkday, isHoliday)
        assertEquals(LocalDateTime.of(2026, 10, 8, 9, 0), next)
    }

    @Test
    fun legalWorkday_includesMakeupSaturday() {
        // Oct 9 (Fri) after 9:00 -> Oct 10 (Sat, makeup workday) at 9:00
        val from = LocalDateTime.of(2026, 10, 9, 10, 0)
        val next = RuleEngine.computeNextFire(rule(RuleType.LEGAL_WORKDAY), from, isWorkday, isHoliday)
        assertEquals(LocalDateTime.of(2026, 10, 10, 9, 0), next)
    }

    @Test
    fun legalHoliday_findsNextHoliday() {
        val from = LocalDateTime.of(2026, 9, 30, 10, 0)
        val next = RuleEngine.computeNextFire(rule(RuleType.LEGAL_HOLIDAY), from, isWorkday, isHoliday)
        assertEquals(LocalDateTime.of(2026, 10, 1, 9, 0), next)
    }

    @Test
    fun legalHoliday_noData_returnsNull() {
        val from = LocalDateTime.of(2026, 9, 30, 10, 0)
        val next = RuleEngine.computeNextFire(rule(RuleType.LEGAL_HOLIDAY), from, null, null)
        assertEquals(null, next)
    }

    @Test
    fun drift_isDeterministicAndWithinWindow() {
        val base = LocalDateTime.of(2026, 11, 25, 8, 0)
        val baseEpoch = RuleEngine.toEpochMillis(base)
        val a = RuleEngine.applyDrift("rule-abc", baseEpoch, 30)
        val b = RuleEngine.applyDrift("rule-abc", baseEpoch, 30)
        assertEquals(a, b) // deterministic across restarts
        assertTrue(a >= baseEpoch)
        assertTrue(a < baseEpoch + 30 * 60_000L)
    }

    @Test
    fun drift_zeroMeansNoDelay() {
        val baseEpoch = RuleEngine.toEpochMillis(LocalDateTime.of(2026, 11, 25, 8, 0))
        assertEquals(baseEpoch, RuleEngine.applyDrift("r", baseEpoch, 0))
    }

    @Test
    fun multipleTimeSlots_picksEarliestUpcoming() {
        // two slots 08:00 and 20:00; at 09:00 the next should be 20:00 today
        val r = rule(RuleType.DAILY).copy(hour = 8, minute = 0, timeSlots = listOf("08:00", "20:00"))
        val from = LocalDateTime.of(2026, 11, 25, 9, 0)
        val next = RuleEngine.computeNextFire(r, from)
        assertEquals(LocalDateTime.of(2026, 11, 25, 20, 0), next)
    }

    @Test
    fun multipleTimeSlots_wrapToNextDay() {
        // at 21:00 both slots passed -> next is 08:00 tomorrow
        val r = rule(RuleType.DAILY).copy(timeSlots = listOf("08:00", "20:00"))
        val from = LocalDateTime.of(2026, 11, 25, 21, 0)
        val next = RuleEngine.computeNextFire(r, from)
        assertEquals(LocalDateTime.of(2026, 11, 26, 8, 0), next)
    }

    @Test
    fun multiWorkflowRule_resolvesIds() {
        val r = rule(RuleType.DAILY)
        assertEquals(listOf(1L, 2L), r.resolvedWorkflowIds())
        // legacy fallback
        val legacy = Rule(id = "old", workflowId = 99L)
        assertEquals(listOf(99L), legacy.resolvedWorkflowIds())
    }

    @Test
    fun `holiday rule with no next fire stays enabled via probe not COMPLETED`() {
        // Regression: in late 2026, 2027 data is unpublished; a LEGAL_HOLIDAY
        // scan returns null and used to mark the rule COMPLETED forever.
        // It must probe instead.
        assertTrue(RuleEngine.holidayRuleNeedsProbe(RuleType.LEGAL_HOLIDAY, null))
        assertTrue(RuleEngine.holidayRuleNeedsProbe(RuleType.LEGAL_WORKDAY, null))
        // A normal next fire means no probe needed.
        val next = java.time.LocalDateTime.of(2027, 1, 1, 9, 0)
        assertFalse(RuleEngine.holidayRuleNeedsProbe(RuleType.LEGAL_HOLIDAY, next))
        // Non-holiday rules with no next fire are genuinely COMPLETED.
        assertFalse(RuleEngine.holidayRuleNeedsProbe(RuleType.DAILY, null))
        assertFalse(RuleEngine.holidayRuleNeedsProbe(RuleType.ONCE, null))
    }

    @Test
    fun `legal workday probes when either current or next year data missing`() {
        // LEGAL_WORKDAY scan reaches ~400 days ahead; missing next-year data
        // would silently fall back to Mon-Fri and mis-fire on statutory holidays.
        assertTrue(RuleEngine.workdayNeedsProbe(RuleType.LEGAL_WORKDAY, true, false))
        assertTrue(RuleEngine.workdayNeedsProbe(RuleType.LEGAL_WORKDAY, false, true))
        assertTrue(RuleEngine.workdayNeedsProbe(RuleType.LEGAL_WORKDAY, false, false))
        assertFalse(RuleEngine.workdayNeedsProbe(RuleType.LEGAL_WORKDAY, true, true))
        // WEEKDAYS never needs holiday data.
        assertFalse(RuleEngine.workdayNeedsProbe(RuleType.WEEKDAYS, false, false))
    }

    @Test
    fun `holiday probe slot must not dispatch on a non-matching day`() {
        // A probe slot scheduled while data was missing must never check in on
        // an ordinary day (no data => invalid, non-matching date => invalid).
        assertFalse(RuleEngine.holidaySlotValid(RuleType.LEGAL_HOLIDAY, false, false))
        assertFalse(RuleEngine.holidaySlotValid(RuleType.LEGAL_HOLIDAY, false, true))
        assertFalse(RuleEngine.holidaySlotValid(RuleType.LEGAL_HOLIDAY, true, false))
        assertTrue(RuleEngine.holidaySlotValid(RuleType.LEGAL_HOLIDAY, true, true))
        assertFalse(RuleEngine.holidaySlotValid(RuleType.LEGAL_WORKDAY, true, false))
        assertTrue(RuleEngine.holidaySlotValid(RuleType.LEGAL_WORKDAY, true, true))
        // Non-holiday rules unaffected.
        assertTrue(RuleEngine.holidaySlotValid(RuleType.DAILY, false, false))
    }
}
