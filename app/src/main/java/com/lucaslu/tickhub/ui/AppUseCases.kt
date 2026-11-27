package com.lucaslu.tickhub.ui

import android.app.Application
import androidx.work.WorkManager
import com.lucaslu.tickhub.data.TokenStore
import com.lucaslu.tickhub.repository.AppRepository
import com.lucaslu.tickhub.scheduler.AlarmScheduler
import com.lucaslu.tickhub.scheduler.RunTrackerWorker
import com.lucaslu.tickhub.util.L10n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Cross-cutting business actions that were previously embedded in AppContainer.
 * Each is a single authoritative implementation reusable from any ViewModel.
 */
class AppUseCases(
    private val app: Application,
    private val repository: AppRepository,
    private val tokenStore: TokenStore,
    private val alarmScheduler: AlarmScheduler,
    private val scope: CoroutineScope,
    private val authState: AuthState
) {
    /** Sign out: clear token synchronously, then cancel work and wipe user data. */
    fun logout() {
        tokenStore.clearSync() // synchronous so token cannot survive a kill before write
        scope.launch {
            repository.getRulesOnce().forEach { alarmScheduler.cancel(it.id) }
            RunTrackerWorker.cancelAll(app)
            WorkManager.getInstance(app).cancelAllWorkByTag("tickhub_dispatch_all")
            repository.clearAllUserData()
            authState.setLogin(LoginUiState())
        }
    }

    /** Single language switch implementation (was duplicated in two ViewModels). */
    fun setLanguage(lang: L10n.Lang) {
        scope.launch { L10n.set(app, lang) }
    }
}
