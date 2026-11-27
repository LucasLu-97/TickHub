package com.lucaslu.tickhub.scheduler

/**
 * Pure decision logic for dispatch retry / catch-up. No Android dependencies so
 * the critical state-machine decisions can be unit-tested on the JVM.
 */
object DispatchPolicy {

    const val MAX_RETRIES = 5
    const val MAX_INFRA_RETRIES = 3 // token/network infra failures must not retry forever
    const val RETRY_DELAY_MS = 15 * 60_000L

    /** Whether a failed slot should retry (vs. advance to the next slot). */
    fun shouldRetry(failCount: Int): Boolean = failCount < MAX_RETRIES

    /** Infra failures (no token / no network) get their own smaller budget. */
    fun shouldRetryInfra(infraCount: Int): Boolean = infraCount < MAX_INFRA_RETRIES

    /** Permanent HTTP errors (except 429 rate-limit) must not be retried. */
    fun isPermanentHttpError(code: Int?): Boolean =
        code != null && code in 400..499 && code != 429

    /**
     * Which workflows to dispatch on a retry of a partially-failed slot.
     * Already-successful workflows must NOT be re-sent (duplicate check-in).
     */
    fun retryWorkflowSubset(
        allWorkflowIds: List<Long>,
        failedWorkflowIds: List<Long>,
        isRetryOfSlot: Boolean
    ): List<Long> {
        if (!isRetryOfSlot || failedWorkflowIds.isEmpty()) return allWorkflowIds
        val valid = failedWorkflowIds.filter { it in allWorkflowIds }
        return valid.ifEmpty { allWorkflowIds }
    }

    /** Result of evaluating a rule during catch-up after app start / reboot. */
    enum class CatchUpAction { SKIP_NOT_DUE, SKIP_RETRY_PENDING, DISPATCH_RETRY_OVERDUE, DISPATCH_MAKEUP, SKIP_ALREADY_ATTEMPTED }

    /**
     * Decide what catch-up should do for one rule slot.
     *
     * [retryAt]/[retryPlannedAt] are the persisted retry state (survives reboot);
     * [now] current wall clock; [slotDueAt] base-or-effective due time;
     * [slotBase] base slot time (dedup key); [hasAnyLog] whether a log exists for
     * this slot.
     *
     * A persisted retry in the future means the (restored) retry alarm owns it —
     * skip. An overdue persisted retry means the alarm was lost on reboot —
     * dispatch it now so the slot cannot be stranded forever.
     */
    fun catchUpAction(
        now: Long,
        slotDueAt: Long,
        slotBase: Long,
        retryAt: Long?,
        retryPlannedAt: Long?,
        hasAnyLog: Boolean
    ): CatchUpAction {
        if (slotDueAt > now) return CatchUpAction.SKIP_NOT_DUE
        if (retryAt != null && retryPlannedAt == slotBase) {
            return if (retryAt > now) CatchUpAction.SKIP_RETRY_PENDING
            else CatchUpAction.DISPATCH_RETRY_OVERDUE
        }
        if (hasAnyLog) return CatchUpAction.SKIP_ALREADY_ATTEMPTED
        return CatchUpAction.DISPATCH_MAKEUP
    }
}
