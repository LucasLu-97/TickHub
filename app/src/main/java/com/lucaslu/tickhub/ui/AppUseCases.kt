package com.lucaslu.tickhub.ui

import android.app.Application
import androidx.work.WorkManager
import com.lucaslu.tickhub.data.TokenStore
import com.lucaslu.tickhub.repository.AppRepository
import com.lucaslu.tickhub.scheduler.AlarmScheduler
import com.lucaslu.tickhub.scheduler.RunTrackerWorker
import com.lucaslu.tickhub.util.AppLogger
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
    /** Sign out: flip UI state immediately, then do all disk/Keystore IO off-main. */
    fun logout() {
        // Immediately mark logged-out on the caller (main) thread so no further
        // UI action can use the old token. The blocking Keystore commit used to
        // run here and could jank/ANR on a click — it now runs on IO below.
        authState.setLogin(LoginUiState())
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            tokenStore.clearSync() // commit() off-main; token gone before data wipe
            repository.getRulesOnce().forEach { alarmScheduler.cancel(it.id) }
            RunTrackerWorker.cancelAll(app)
            WorkManager.getInstance(app).cancelAllWorkByTag("tickhub_dispatch_all")
            repository.clearAllUserData()
            AppLogger.clear() // logs may contain username / private repo names
        }
    }

    /** Single language switch implementation (was duplicated in two ViewModels). */
    fun setLanguage(lang: L10n.Lang) {
        scope.launch { L10n.set(app, lang) }
    }
}
