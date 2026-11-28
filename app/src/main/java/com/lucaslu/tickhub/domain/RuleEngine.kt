package com.lucaslu.tickhub.domain

import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.RuleType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Pure logic for computing the next fire time of a rule.
 * Uses java.time (available since API 26, our minSdk). No Android dependencies
 * so it can be unit-tested on the JVM.
 */
object RuleEngine {

    /**
     * A holiday rule whose scan found no next fire means the coming year's
     * calendar is not loaded yet. It must NOT be treated as COMPLETED — keep
     * the rule ENABLED and probe again later, otherwise it dies permanently
     * (e.g. LEGAL_HOLIDAY in late 2026 before 2027 data is published).
     */
    fun holidayRuleNeedsProbe(type: RuleType, nextFire: LocalDateTime?): Boolean =
        nextFire == null && (type == RuleType.LEGAL_HOLIDAY || type == RuleType.LEGAL_WORKDAY)

    /**
     * LEGAL_WORKDAY needs calendar data for BOTH the current and next year: its
     * scan reaches ~400 days ahead, and missing data there falls back to Mon-Fri
     * (would check in on statutory holidays / skip make-up weekends). Probe in
     * 30 days instead of using the fallback. WEEKDAYS never needs holiday data.
     */
    fun workdayNeedsProbe(type: RuleType, currentYearHasData: Boolean, nextYearHasData: Boolean): Boolean =
        type == RuleType.LEGAL_WORKDAY && (!currentYearHasData || !nextYearHasData)

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * Returns the first trigger time strictly after `from`, or null if no future
     * occurrence exists (e.g. expired ONCE rule, or invalid config).
     *
     * @param isWorkday optional predicate: given a date, is it a statutory workday?
     *                  If null, Mon-Fri is used (no holiday data available).
     * @param isHoliday optional predicate: given a date, is it a statutory holiday?
     */
    fun computeNextFire(
        rule: Rule,
        from: LocalDateTime,
        isWorkday: ((LocalDate) -> Boolean)? = null,
        isHoliday: ((LocalDate) -> Boolean)? = null
    ): LocalDateTime? {
        val slots = rule.timeSlots.mapNotNull { parseTime(it) }
            .ifEmpty { listOf(LocalTime.of(rule.hour.coerceIn(0, 23), rule.minute.coerceIn(0, 59))) }
        return slots.mapNotNull { computeNextForTime(rule, it, from, isWorkday, isHoliday) }
            .minOrNull()
    }

    fun parseTime(value: String): LocalTime? = try {
        if (value.isBlank()) null else LocalTime.parse(value)
    } catch (e: Exception) { null }

    private fun computeNextForTime(
        rule: Rule,
        time: LocalTime,
        from: LocalDateTime,
        isWorkday: ((LocalDate) -> Boolean)?,
        isHoliday: ((LocalDate) -> Boolean)?
    ): LocalDateTime? = when (rule.type) {
        RuleType.ONCE -> computeOnce(rule, time, from)
        RuleType.DAILY -> nextAtOrAfter(from, time, includeToday = true)
        @Suppress("DEPRECATION")
        RuleType.WEEKDAYS, RuleType.LEGAL_WORKDAY -> computeLegalWorkdays(time, from, isWorkday)
        RuleType.LEGAL_HOLIDAY -> if (isHoliday == null) null else computeLegalHolidays(time, from, isHoliday)
        RuleType.WEEKLY -> computeWeekly(rule, time, from)
        RuleType.EVERY_N_DAYS -> computeEveryNDays(rule, time, from)
    }

    private fun computeOnce(rule: Rule, time: LocalTime, from: LocalDateTime): LocalDateTime? {
        val date = parseDate(rule.onceDate) ?: return null
        val candidate = LocalDateTime.of(date, time)
        return if (candidate.isAfter(from)) candidate else null
    }

    /** Daily: try today, if today's time has passed go to tomorrow. */
    private fun nextAtOrAfter(from: LocalDateTime, time: LocalTime, includeToday: Boolean): LocalDateTime {
        var date = from.toLocalDate()
        if (!includeToday) date = date.plusDays(1)
        var candidate = LocalDateTime.of(date, time)
        if (!candidate.isAfter(from)) {
            candidate = candidate.plusDays(1)
        }
        return candidate
    }

    /** Statutory workdays: uses holiday data when available, else Mon-Fri. */
    private fun computeLegalWorkdays(
        time: LocalTime,
        from: LocalDateTime,
        isWorkday: ((LocalDate) -> Boolean)?
    ): LocalDateTime {
        var date = from.toLocalDate()
        repeat(400) { // look up to ~1 year ahead for long holidays
            val candidate = LocalDateTime.of(date, time)
            val workday = isWorkday?.invoke(date) ?: (date.dayOfWeek.value <= 5)
            if (candidate.isAfter(from) && workday) return candidate
            date = date.plusDays(1)
        }
        return LocalDateTime.of(from.toLocalDate().plusDays(400), time)
    }

    /** Statutory holidays (days off from holiday calendar, not ordinary weekends). */
    private fun computeLegalHolidays(
        time: LocalTime,
        from: LocalDateTime,
        isHoliday: (LocalDate) -> Boolean
    ): LocalDateTime? {
        var date = from.toLocalDate()
        repeat(400) {
            val candidate = LocalDateTime.of(date, time)
            if (candidate.isAfter(from) && isHoliday(date)) return candidate
            date = date.plusDays(1)
        }
        return null
    }

    /** Weekly: fire on selected weekdays (1=Mon..7=Sun). */
    private fun computeWeekly(rule: Rule, time: LocalTime, from: LocalDateTime): LocalDateTime? {
        val days = rule.weekdays.filter { it in 1..7 }.distinct().sorted()
        if (days.isEmpty()) return null
        var date = from.toLocalDate()
        repeat(8) {
            val candidate = LocalDateTime.of(date, time)
            if (candidate.isAfter(from) && date.dayOfWeek.value in days) return candidate
            date = date.plusDays(1)
        }
        return null
    }

    /**
     * Every N days: anchor at startDate (or `from`'s date if absent), then
     * step by N days. Find the first occurrence strictly after `from`.
     */
    private fun computeEveryNDays(rule: Rule, time: LocalTime, from: LocalDateTime): LocalDateTime? {
        val n = rule.intervalDays.coerceAtLeast(1)
        val anchor = parseDate(rule.startDate) ?: from.toLocalDate()
        val daysSinceAnchor = ChronoUnit.DAYS.between(anchor, from.toLocalDate())
        var k = if (daysSinceAnchor < 0) 0 else daysSinceAnchor / n
        repeat(3) {
            val candidate = LocalDateTime.of(anchor.plusDays(k * n.toLong()), time)
            if (candidate.isAfter(from)) return candidate
            k++
        }
        return null
    }

    /**
     * Deterministic drift in [0, driftMinutes) derived from ruleId + base time,
     * so the same planned slot always gets the same offset even after app restart.
     */
    fun applyDrift(ruleId: String, baseEpoch: Long, driftMinutes: Int): Long {
        if (driftMinutes <= 0) return baseEpoch
        val seed = (ruleId.hashCode().toLong() xor baseEpoch)
        val random = java.util.Random(seed)
        val offsetMinutes = random.nextInt(driftMinutes) // 0 .. driftMinutes-1
        return baseEpoch + offsetMinutes * 60_000L
    }

    fun parseDate(value: String?): LocalDate? = try {
        if (value.isNullOrBlank()) null else LocalDate.parse(value, dateFormatter)
    } catch (e: Exception) {
        null
    }

    fun formatDate(date: LocalDate): String = date.format(dateFormatter)

    /** Convert epoch millis to local LocalDateTime (system default zone). */
    fun fromEpochMillis(epoch: Long): LocalDateTime =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epoch), ZoneId.systemDefault())

    /** Convert local LocalDateTime to epoch millis (system default zone). */
    fun toEpochMillis(dateTime: LocalDateTime): Long =
        dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
