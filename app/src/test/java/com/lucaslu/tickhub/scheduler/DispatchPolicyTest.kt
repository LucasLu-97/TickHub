package com.lucaslu.tickhub.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the critical dispatch state-machine decisions that caused a
 * missed check-in (C1) and duplicate dispatch (M1) in earlier versions.
 */
class DispatchPolicyTest {

    private val now = 1_700_000_000_000L
    private val base = now - 60_000L // slot due 1 minute ago

    @Test
    fun `infra failures get a smaller retry budget than dispatch failures`() {
        assertTrue(DispatchPolicy.shouldRetryInfra(0))
        assertTrue(DispatchPolicy.shouldRetryInfra(2))
        assertFalse(DispatchPolicy.shouldRetryInfra(3)) // cap: no endless 15-min wakes
        // Dispatch failures still get the full budget.
        assertTrue(DispatchPolicy.shouldRetry(4))
    }

    @Test
    fun `permanent HTTP errors skip retries while 429 and 5xx retry`() {
        assertTrue(DispatchPolicy.isPermanentHttpError(401))
        assertTrue(DispatchPolicy.isPermanentHttpError(404))
        assertTrue(DispatchPolicy.isPermanentHttpError(422))
        assertFalse(DispatchPolicy.isPermanentHttpError(429)) // rate-limit: retry later
        assertFalse(DispatchPolicy.isPermanentHttpError(500))
        assertFalse(DispatchPolicy.isPermanentHttpError(null)) // network error
    }

    @Test
    fun `shouldRetry up to MAX_RETRIES-1 then advance`() {
        assertTrue(DispatchPolicy.shouldRetry(0))
        assertTrue(DispatchPolicy.shouldRetry(4))
        assertFalse(DispatchPolicy.shouldRetry(5)) // exhausted -> advance
        assertFalse(DispatchPolicy.shouldRetry(9))
    }

    @Test
    fun `retry dispatches ONLY failed workflows, never already-successful ones`() {
        val all = listOf(1L, 2L, 3L)
        // Two failed, one succeeded -> retry only the two.
        assertEquals(listOf(1L, 3L), DispatchPolicy.retryWorkflowSubset(all, listOf(1L, 3L), isRetryOfSlot = true))
        // Normal (non-retry) slot -> all workflows.
        assertEquals(all, DispatchPolicy.retryWorkflowSubset(all, listOf(1L, 3L), isRetryOfSlot = false))
        // Retry with stale/invalid failed ids -> fall back to all (safety).
        assertEquals(all, DispatchPolicy.retryWorkflowSubset(all, listOf(99L), isRetryOfSlot = true))
    }

    @Test
    fun `C1 - failed slot is NOT stranded after reboot when retry time passed`() {
        // Root cause of the missed check-in: retry alarm is in-memory only, lost on
        // reboot. With a persisted retryAt in the past, catchUp must dispatch now.
        val action = DispatchPolicy.catchUpAction(
            now = now, slotDueAt = base, slotBase = base,
            retryAt = now - 1_000L, retryPlannedAt = base, hasAnyLog = true
        )
        assertEquals(DispatchPolicy.CatchUpAction.DISPATCH_RETRY_OVERDUE, action)
    }

    @Test
    fun `future persisted retry is left to the restored alarm (no double dispatch)`() {
        val action = DispatchPolicy.catchUpAction(
            now = now, slotDueAt = base, slotBase = base,
            retryAt = now + 60_000L, retryPlannedAt = base, hasAnyLog = true
        )
        assertEquals(DispatchPolicy.CatchUpAction.SKIP_RETRY_PENDING, action)
    }

    @Test
    fun `already attempted slot is skipped, unattempted slot is made up`() {
        val attempted = DispatchPolicy.catchUpAction(now, base, base, null, null, hasAnyLog = true)
        val unattempted = DispatchPolicy.catchUpAction(now, base, base, null, null, hasAnyLog = false)
        assertEquals(DispatchPolicy.CatchUpAction.SKIP_ALREADY_ATTEMPTED, attempted)
        assertEquals(DispatchPolicy.CatchUpAction.DISPATCH_MAKEUP, unattempted)
    }

    @Test
    fun `slot not yet due is skipped (no early duplicate in drift window)`() {
        val future = now + 60_000L
        val action = DispatchPolicy.catchUpAction(now, future, base, null, null, hasAnyLog = false)
        assertEquals(DispatchPolicy.CatchUpAction.SKIP_NOT_DUE, action)
    }

    @Test
    fun `retry state for a different slot does not affect current slot`() {
        // retryPlannedAt must match this base; a stale retry for another slot is ignored.
        val action = DispatchPolicy.catchUpAction(
            now = now, slotDueAt = base, slotBase = base,
            retryAt = now + 60_000L, retryPlannedAt = base - 86_400_000L, hasAnyLog = false
        )
        assertEquals(DispatchPolicy.CatchUpAction.DISPATCH_MAKEUP, action)
    }
}
