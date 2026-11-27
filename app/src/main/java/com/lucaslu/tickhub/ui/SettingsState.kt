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

    // ONE synchronous read at construction seeds the stateIn initial values.
    // Without this the first frame uses hardcoded defaults (theme flashes,
    // early cycleTheme() writes from the wrong base, disabled haptics vibrate).
    // Still far cleaner than the old runBlocking + 4 hand-written mirrors:
    // one read here, then stateIn mirrors every later DataStore change.
    private val initial = runCatching {
        kotlinx.coroutines.runBlocking { repository.readInitialPrefsOnce() }
    }.getOrDefault(com.lucaslu.tickhub.repository.InitialPrefs())

    init { Haptics.enabled = initial.haptics } // correct value before first compose/click

    val themeMode: StateFlow<Int> = repository.themeMode.stateIn(
        scope, SharingStarted.Eagerly, initialValue = initial.themeMode
    )
    val orders: StateFlow<Map<String, List<String>>> = repository.orders.stateIn(
        scope, SharingStarted.Eagerly, initialValue = initial.orders
    )
    val showExecutionStatus: StateFlow<Boolean> = repository.showExecutionStatus.stateIn(
        scope, SharingStarted.Eagerly, initialValue = initial.showExecution
    )
    val runTracking: StateFlow<Boolean> = repository.runTrackingEnabled.stateIn(
        scope, SharingStarted.Eagerly, initialValue = initial.runTracking
    )
    val hapticsEnabled: StateFlow<Boolean> = repository.hapticsEnabled.stateIn(
        scope, SharingStarted.Eagerly, initialValue = initial.haptics
    ).also { flow ->
        // Haptics is consumed from non-Compose code; keep its flag in sync.
        scope.launch { flow.collect { Haptics.enabled = it } }
    }
    val holidayEnabled: StateFlow<Boolean> = repository.holidayEnabled.stateIn(
        scope, SharingStarted.Eagerly, initialValue = initial.holiday
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
