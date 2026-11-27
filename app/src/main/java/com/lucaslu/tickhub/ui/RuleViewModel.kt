package com.lucaslu.tickhub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.RuleStatus
import com.lucaslu.tickhub.domain.RuleEngine
import com.lucaslu.tickhub.scheduler.DispatchWorker
import com.lucaslu.tickhub.util.AppLogger
import java.time.LocalDateTime
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Rule CRUD, alarm registration, catch-up and rule-based manual trigger. */
class RuleViewModel(app: Application) : AndroidViewModel(app) {

    private val c = AppContainer.get(app)

    val rules get() = c.rules
    val snapshot get() = c.snapshot
    val runningExecutions get() = c.runningExecutions
    val showExecutionStatusFlow: StateFlow<Boolean> get() = c.showExecutionStatusFlow
    val orders get() = c.orders

    fun saveRule(rule: Rule) {
        viewModelScope.launch {
            val (next, effective) = c.repository.computeNextFire(rule, LocalDateTime.now())
            val base = next?.let { RuleEngine.toEpochMillis(it) }
            val finalRule = rule.copy(
                nextFireAt = base,
                effectiveFireAt = effective,
                createdAt = if (rule.createdAt == 0L) System.currentTimeMillis() else rule.createdAt
            )
            c.repository.saveRule(finalRule)
            c.alarmScheduler.cancel(rule.id)
            if (finalRule.status == RuleStatus.ENABLED && effective != null && base != null) {
                c.alarmScheduler.scheduleWithSlot(finalRule, triggerAt = effective, plannedAt = base)
            }
        }
    }

    fun deleteRule(id: String) {
        viewModelScope.launch {
            c.alarmScheduler.cancel(id)
            c.repository.deleteRule(id)
        }
    }

    fun setRuleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            c.repository.setRuleEnabled(id, enabled)
            var rule = c.repository.getRuleOnce(id) ?: return@launch
            if (!enabled) {
                c.alarmScheduler.cancel(id)
                return@launch
            }
            // If the stored slot is in the past (rule was disabled across its
            // fire time), recompute from now instead of registering a dead alarm.
            val stored = rule.effectiveFireAt ?: rule.nextFireAt ?: 0L
            if (stored <= System.currentTimeMillis()) {
                val (next, effective) = c.repository.computeNextFire(rule, LocalDateTime.now())
                if (next == null || effective == null) {
                    AppLogger.w("App", "rule=$id re-enable: no future slot, mark COMPLETED")
                    c.repository.updateRule(rule.copy(status = RuleStatus.COMPLETED, nextFireAt = null, effectiveFireAt = null))
                    return@launch
                }
                rule = c.repository.updateRule(rule.copy(
                    nextFireAt = RuleEngine.toEpochMillis(next),
                    effectiveFireAt = effective
                ))
            }
            val base = rule.nextFireAt ?: return@launch
            c.alarmScheduler.scheduleWithSlot(rule, triggerAt = rule.effectiveFireAt ?: base, plannedAt = base)
        }
    }

    /** Manually trigger a rule now: log + track, but do NOT advance/reschedule the
     *  user's planned slot (a manual test or extra check-in must not consume it). */
    fun triggerNow(rule: Rule) {
        DispatchWorker.enqueue(getApplication(), rule.id, System.currentTimeMillis(), isMakeup = false, advance = false)
    }

    /** Catch up any rules whose scheduled time has passed but were not fired. */
    fun catchUpMissedRules() {
        viewModelScope.launch {
            val count = c.dispatchEngine.catchUpMissed()
            AppLogger.i("App", "catchUp done: $count makeup dispatched")
        }
    }

    fun saveOrder(kind: String, ids: List<String>) {
        c.saveOrder(kind, ids)
    }

    suspend fun readOrder(kind: String): List<String> = c.orders.value[kind] ?: c.repository.readOrder(kind)

    fun <T> applyOrder(list: List<T>, order: List<String>, idOf: (T) -> String): List<T> =
        c.repository.applyOrder(list, order, idOf)
}
