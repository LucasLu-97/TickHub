package com.lucaslu.tickhub.repository

/**
 * Target rule state to apply ATOMICALLY together with an outcome log.
 * Computed by DispatchEngine (pure decisions), applied by AppRepository inside a
 * single dataStore.edit — so a process kill can never leave "log written but
 * rule not advanced" (which stranded slots forever) or a stale-object overwrite.
 */
data class DispatchTransition(
    val completed: Boolean = false,
    val nextFireAt: Long? = null,
    val effectiveFireAt: Long? = null,
    val lastFiredAt: Long? = null,
    val retryAt: Long? = null,
    val retryPlannedAt: Long? = null,
    val retryFailedWfIds: List<Long> = emptyList()
)
