package com.lucaslu.tickhub.ui
import com.lucaslu.tickhub.di.AppGraph

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lucaslu.tickhub.data.RuleStatus
import com.lucaslu.tickhub.domain.RuleEngine
import com.lucaslu.tickhub.util.AppLogger
import com.lucaslu.tickhub.util.L10n
import java.time.LocalDateTime
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** App settings, startup maintenance (timezone/alarms/catch-up) and holidays. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val c = AppGraph.get(app)

    val showExecutionStatusFlow: StateFlow<Boolean> get() = c.showExecutionStatusFlow
    val runTrackingFlow: StateFlow<Boolean> get() = c.runTrackingFlow
    val hapticsFlow: StateFlow<Boolean> get() = c.hapticsFlow
    val holidayEnabledFlow: StateFlow<Boolean> get() = c.holidayEnabledFlow
    // SettingsScreen also shows theme switch and logout (shared state via container).
    val themeMode: StateFlow<Int> get() = c.themeMode

    fun cycleTheme() = c.cycleTheme()

    fun logout() = c.logout()

    fun setShowExecutionStatus(enabled: Boolean) {
        viewModelScope.launch { c.repository.setShowExecutionStatus(enabled) }
    }

    fun setRunTrackingEnabled(enabled: Boolean) {
        viewModelScope.launch { c.repository.setRunTrackingEnabled(enabled) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { c.repository.setHapticsEnabled(enabled) }
    }

    fun setLanguage(lang: L10n.Lang) = c.setLanguage(lang)

    /** China-only statutory calendar opt-in. Turning it on immediately fetches
     *  current + next year data (cached thereafter); turning off stops refreshes. */
    fun setHolidayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            c.repository.setHolidayEnabled(enabled)
            if (enabled) refreshHolidays()
        }
    }

    /** Refresh holiday data from GitHub (manual: settings toggle / explicit action). */
    fun refreshHolidays() {
        viewModelScope.launch {
            val result = com.lucaslu.tickhub.data.HolidayCalendar.refresh(getApplication())
            AppLogger.i("Holiday", "manual refresh result=${result.ok} years=${result.years}")
            if (result.ok) c.repository.setHolidayAnnualFetchYear(java.time.LocalDate.now().year)
            // Skip a redundant "success" when nothing was actually fetched (data
            // already current) — avoid nagging on every settings toggle.
            if (result.changed || !result.ok) {
                com.lucaslu.tickhub.util.NotificationHelper.notifyHolidayRefresh(getApplication(), result.ok, manual = true)
            }
        }
    }

    /** Auto-pull next-year holiday data on/after Dec-17 only; posts a result
     *  notification. Outside that window use the settings toggle to pull manually. */
    fun maybeAnnualHolidayRefresh() {
        viewModelScope.launch {
            if (!c.repository.holidayEnabledOnce()) return@launch
            val today = java.time.LocalDate.now()
            val nextYear = today.year + 1
            val nextData = com.lucaslu.tickhub.data.HolidayCalendar.load(getApplication(), nextYear)
            val alreadyFetched = c.repository.holidayAnnualFetchYearOnce() >= today.year
            val inWindow = today.monthValue == 12 && today.dayOfMonth >= 17
            // Primary window: Dec-17 onward. Keep the bounded Jan catch-up fallback
            // (if we never got next-year data) — users may not open the app in late
            // December. Outside these windows, no auto pull; the settings toggle
            // still works manually and posts its own notification.
            val janFallback = today.monthValue == 1 && !alreadyFetched && nextData.days.isEmpty()
            if (!inWindow && !janFallback) return@launch
            if (alreadyFetched && nextData.days.isNotEmpty()) return@launch
            AppLogger.i("Holiday", "annual refresh for $nextYear (inWindow=$inWindow, dataReady=${nextData.days.isNotEmpty()}, fetched=$alreadyFetched)")
            val result = com.lucaslu.tickhub.data.HolidayCalendar.refresh(getApplication())
            val nextFetched = result.years.contains(nextYear)
            if (nextFetched) c.repository.setHolidayAnnualFetchYear(today.year)
            AppLogger.i("Holiday", "annual refresh result=${result.ok} nextFetched=$nextFetched years=${result.years}")
            com.lucaslu.tickhub.util.NotificationHelper.notifyHolidayRefresh(getApplication(), nextFetched, manual = false)
        }
    }

    /** Serialized startup maintenance: timezone resync -> alarm registration ->
     *  catch-up. Running these in one coroutine avoids stale reads between them. */
    // Serialize startup maintenance: cold start fires from both LaunchedEffect
    // and ON_RESUME. Running two concurrent resync->catchUp passes could race on
    // rule state and duplicate alarms/logs. CAS gate keeps a single pass in flight.
    private val appReadyInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun onAppReady() {
        if (!appReadyInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                checkTimezoneAndResyncInternal()
                syncAlarmsInternal()
                c.dispatchEngine.catchUpMissed()
                AppLogger.i("App", "startup maintenance done")
            } finally {
                appReadyInFlight.set(false)
            }
        }
    }

    private suspend fun checkTimezoneAndResyncInternal() {
        val zone = java.time.ZoneId.systemDefault()
        // Include the current UTC offset so DST shifts within the same ZoneId
        // (e.g. America/New_York) are detected too — id alone misses them.
        val offset = zone.rules.getOffset(java.time.Instant.now()).totalSeconds
        val current = "${zone.id}|$offset"
        val saved = c.repository.getSavedTimezone()
        if (saved == null) { c.repository.setSavedTimezone(current); return }
        if (saved == current) return
        AppLogger.i("App", "timezone $saved -> $current, catching up old slots BEFORE recomputing")

        // C2: catch up slots missed under the OLD timezone before recomputing,
        // otherwise the old slot is silently overwritten and never made up.
        c.dispatchEngine.catchUpMissed()
        c.repository.setSavedTimezone(current)

        val now = LocalDateTime.now()
        val enabled = c.repository.getRulesOnce().filter { it.status == RuleStatus.ENABLED }
        for (rule in enabled) {
            val (next, effective) = c.repository.computeNextFire(rule, now)
            if (next == null || effective == null) {
                c.alarmScheduler.cancel(rule.id)
                continue
            }
            // Field-level patch (C3): don't overwrite concurrent retry state.
            val updated = c.repository.updateRuleFields(rule.id) { cur ->
                cur.copy(nextFireAt = RuleEngine.toEpochMillis(next), effectiveFireAt = effective)
            } ?: continue
            c.alarmScheduler.scheduleWithSlot(updated, triggerAt = effective, plannedAt = updated.nextFireAt!!)
        }
    }

    private suspend fun syncAlarmsInternal() {
        val enabled = c.repository.getRulesOnce().filter { it.status == RuleStatus.ENABLED }
        AppLogger.i("App", "syncAlarms: ${enabled.size} enabled rules")
        val now = System.currentTimeMillis()
        enabled.forEach { rule ->
            val base = rule.nextFireAt ?: return@forEach
            // Restore a pending retry alarm if still in the future — the in-memory
            // alarm is lost on process death; catchUp covers overdue retries.
            if (rule.retryAt != null && rule.retryPlannedAt == base && rule.retryAt > now) {
                c.alarmScheduler.scheduleRetry(rule.id, rule.workflowName, rule.retryAt, base)
                return@forEach
            }
            val trigger = rule.effectiveFireAt ?: base
            c.alarmScheduler.scheduleWithSlot(rule, triggerAt = trigger, plannedAt = base)
        }
    }
}
