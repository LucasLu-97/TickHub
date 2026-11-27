package com.lucaslu.tickhub.repository

import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.RuleStatus

/**
 * PURE rule-state transition. No Context / DataStore — so the most critical
 * state machine (atomic log+rule commit) is directly JVM-unit-testable.
 *
 * Three branches, order matters:
 *  1. Non-ENABLED rule (user disabled it while a Worker was in flight): only
 *     touch lastFiredAt. Never overwrite DISABLED with COMPLETED or re-arm a
 *     retry (C-1: disabled rules must never fire again).
 *  2. completed: mark COMPLETED, clear every schedule field.
 *  3. normal patch: apply next/effective/retry fields from the transition.
 */
object TransitionApplier {
    fun apply(cur: Rule, t: DispatchTransition, now: Long): Rule = when {
        cur.status != RuleStatus.ENABLED -> cur.copy(
            lastFiredAt = t.lastFiredAt ?: cur.lastFiredAt
        )
        t.completed -> cur.copy(
            status = RuleStatus.COMPLETED,
            nextFireAt = null, effectiveFireAt = null,
            lastFiredAt = t.lastFiredAt ?: now,
            retryAt = null, retryPlannedAt = null, retryFailedWfIds = emptyList()
        )
        else -> cur.copy(
            nextFireAt = t.nextFireAt ?: cur.nextFireAt,
            effectiveFireAt = t.effectiveFireAt ?: cur.effectiveFireAt,
            lastFiredAt = t.lastFiredAt ?: cur.lastFiredAt,
            retryAt = t.retryAt,
            retryPlannedAt = t.retryPlannedAt,
            retryFailedWfIds = t.retryFailedWfIds
        )
    }
}
