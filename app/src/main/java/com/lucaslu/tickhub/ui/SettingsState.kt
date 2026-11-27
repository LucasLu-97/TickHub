package com.lucaslu.tickhub.ui

import com.lucaslu.tickhub.repository.AppRepository
import com.lucaslu.tickhub.util.Haptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings state with DataStore as the SINGLE source of truth.
 *
 * Replaces AppContainer's runBlocking cold-start read + four hand-written
 * mirror collectors (memory StateFlow + DataStore dual-write drift). Each
 * StateFlow is now derived directly from its repository Flow via stateIn —
 * no manual mirror, no constructor blocking, one write path.
 */
class SettingsState(
    private val repository: AppRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val themeMode: StateFlow<Int> = repository.themeMode.stateIn(
        scope, SharingStarted.Eagerly, initialValue = 2 // 2 = system
    )
    val orders: StateFlow<Map<String, List<String>>> = repository.orders.stateIn(
        scope, SharingStarted.Eagerly, initialValue = emptyMap()
    )
    val showExecutionStatus: StateFlow<Boolean> = repository.showExecutionStatus.stateIn(
        scope, SharingStarted.Eagerly, initialValue = true
    )
    val runTracking: StateFlow<Boolean> = repository.runTrackingEnabled.stateIn(
        scope, SharingStarted.Eagerly, initialValue = true
    )
    val hapticsEnabled: StateFlow<Boolean> = repository.hapticsEnabled.stateIn(
        scope, SharingStarted.Eagerly, initialValue = true
    ).also { flow ->
        // Haptics is consumed from non-Compose code; mirror its enabled flag.
        scope.launch { flow.collect { Haptics.enabled = it } }
    }
    val holidayEnabled: StateFlow<Boolean> = repository.holidayEnabled.stateIn(
        scope, SharingStarted.Eagerly, initialValue = false
    )

    /** Cycle 0=light, 1=dark, 2=system. Write only to DataStore; stateIn mirrors back. */
    fun cycleTheme() {
        val next = (themeMode.value + 1) % 3
        scope.launch { repository.setThemeMode(next) }
    }

    fun saveOrder(kind: String, ids: List<String>) {
        scope.launch { repository.saveOrder(kind, ids) }
    }
}
