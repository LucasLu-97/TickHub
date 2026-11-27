package com.lucaslu.tickhub.repository

import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.RuleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers the atomic log+rule state machine's PURE transition logic. */
class TransitionApplierTest {

    private fun rule(status: RuleStatus = RuleStatus.ENABLED) = Rule(
        id = "r1", status = status,
        nextFireAt = 1000L, effectiveFireAt = 1100L, lastFiredAt = 500L,
        retryAt = 900L, retryPlannedAt = 800L, retryFailedWfIds = listOf(11L)
    )

    @Test
    fun `disabled rule keeps DISABLED and is never re-armed even when transition requests retry`() {
        val cur = rule(RuleStatus.DISABLED)
        val t = DispatchTransition(
            nextFireAt = 9999L, retryAt = 8888L, retryPlannedAt = 7777L,
            retryFailedWfIds = listOf(22L), completed = false, lastFiredAt = 1234L
        )
        val out = TransitionApplier.apply(cur, t, now = 5000L)
        assertEquals(RuleStatus.DISABLED, out.status) // C-1: not COMPLETED, not re-armed
        assertNull(out.retryAt)
        assertNull(out.retryPlannedAt)
        assertEquals(emptyList<Long>(), out.retryFailedWfIds)
        assertEquals(1000L, out.nextFireAt)
        assertEquals(1234L, out.lastFiredAt) // only lastFiredAt advances
    }

    @Test
    fun `disabled rule stays DISABLED even when transition says completed`() {
        val out = TransitionApplier.apply(
            rule(RuleStatus.DISABLED),
            DispatchTransition(completed = true, lastFiredAt = 2000L),
            now = 5000L
        )
        assertEquals(RuleStatus.DISABLED, out.status)
        assertEquals(1000L, out.nextFireAt)
    }

    @Test
    fun `completed transition marks COMPLETED and clears all schedule fields`() {
        val out = TransitionApplier.apply(
            rule(), DispatchTransition(completed = true, lastFiredAt = 3000L), now = 5000L
        )
        assertEquals(RuleStatus.COMPLETED, out.status)
        assertNull(out.nextFireAt)
        assertNull(out.effectiveFireAt)
        assertNull(out.retryAt)
        assertNull(out.retryPlannedAt)
        assertEquals(emptyList<Long>(), out.retryFailedWfIds)
        assertEquals(3000L, out.lastFiredAt)
    }

    @Test
    fun `completed transition falls back to now when no lastFiredAt given`() {
        val out = TransitionApplier.apply(rule(), DispatchTransition(completed = true), now = 5000L)
        assertEquals(5000L, out.lastFiredAt)
    }

    @Test
    fun `normal patch applies retry fields and keeps unspecified ones`() {
        val t = DispatchTransition(
            nextFireAt = 2000L, retryAt = 6000L, retryPlannedAt = 5555L,
            retryFailedWfIds = listOf(33L), lastFiredAt = 4000L
        )
        val out = TransitionApplier.apply(rule(), t, now = 5000L)
        assertEquals(RuleStatus.ENABLED, out.status)
        assertEquals(2000L, out.nextFireAt)
        assertEquals(1100L, out.effectiveFireAt) // not specified -> preserved
        assertEquals(6000L, out.retryAt)
        assertEquals(5555L, out.retryPlannedAt)
        assertEquals(listOf(33L), out.retryFailedWfIds)
        assertEquals(4000L, out.lastFiredAt)
    }

    @Test
    fun `manual trigger transition with null retry clears stale retry state`() {
        // Manual trigger must leave no retry alarm behind.
        val out = TransitionApplier.apply(
            rule(), DispatchTransition(retryAt = null, retryPlannedAt = null), now = 5000L
        )
        assertNull(out.retryAt)
        assertNull(out.retryPlannedAt)
        assertEquals(emptyList<Long>(), out.retryFailedWfIds)
        assertEquals(500L, out.lastFiredAt) // preserved when transition omits it
    }
}
