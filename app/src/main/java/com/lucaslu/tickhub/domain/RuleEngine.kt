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

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * Returns the first trigger time strictly after `from`, or null if no future
     * occurrence exists (e.g. expired ONCE rule, or invalid config).
     */
    fun computeNextFire(rule: Rule, from: LocalDateTime): LocalDateTime? {
        val time = LocalTime.of(rule.hour.coerceIn(0, 23), rule.minute.coerceIn(0, 59))
        return when (rule.type) {
            RuleType.ONCE -> computeOnce(rule, time, from)
            RuleType.DAILY -> nextAtOrAfter(from, time, includeToday = true)
            RuleType.WEEKDAYS -> computeWeekdays(time, from)
            RuleType.WEEKLY -> computeWeekly(rule, time, from)
            RuleType.EVERY_N_DAYS -> computeEveryNDays(rule, time, from)
        }
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

    /** Weekdays (Mon-Fri): walk forward skipping Sat/Sun. */
    private fun computeWeekdays(time: LocalTime, from: LocalDateTime): LocalDateTime {
        var date = from.toLocalDate()
        repeat(8) {
            val candidate = LocalDateTime.of(date, time)
            if (candidate.isAfter(from) && date.dayOfWeek.value <= 5) return candidate
            date = date.plusDays(1)
        }
        return LocalDateTime.of(from.toLocalDate().plusDays(8), time) // unreachable in practice
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
