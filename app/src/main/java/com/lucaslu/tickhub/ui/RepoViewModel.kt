package com.lucaslu.tickhub.ui
import com.lucaslu.tickhub.di.AppGraph

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lucaslu.tickhub.data.TriggerLog
import com.lucaslu.tickhub.data.TriggerStatus
import com.lucaslu.tickhub.network.ApiResult
import com.lucaslu.tickhub.scheduler.RunTrackerWorker
import com.lucaslu.tickhub.util.AppLogger
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Repo scanning, managed-workflow selection, manual trigger and repo ordering. */
class RepoViewModel(app: Application) : AndroidViewModel(app) {

    private val c = AppGraph.get(app)

    val repo: StateFlow<RepoUiState> get() = c.repo
    val refreshing: StateFlow<Boolean> get() = c.refreshing
    val snapshot get() = c.snapshot
    val loggedInUser get() = c.loggedInUser
    val avatarUrl get() = c.avatarUrl
    val orders get() = c.orders

    fun scanRepos() {
        val token = c.tokenStore.getToken()
        if (token == null) {
            c.setRepo(RepoUiState(error = "未登录"))
            return
        }
        c.setRepo(RepoUiState(scanning = true, message = "正在扫描仓库与工作流…"))
        viewModelScope.launch {
            when (val result = c.repository.scan(token)) {
                is ApiResult.Error -> {
                    if (result.isUnauthorized) {
                        // Token revoked: sign out so the user can re-authorize.
                        c.tokenStore.clearSync()
                        c.setLogin(LoginUiState())
                    } else {
                        val msg = if (result.isRateLimited) "GitHub 访问频率受限，请稍后再试"
                            else if (result.isServerError) "GitHub 服务暂时不可用，请稍后再试"
                            else result.message
                        c.setRepo(RepoUiState(error = msg))
                    }
                }
                is ApiResult.Success -> {
                    c.setRepo(if (result.data == 0) RepoUiState(messageKey = "扫描完成")
                    else RepoUiState(
                        messageKey = "扫描完成，{0} 个仓库的工作流读取失败",
                        messageArgs = listOf("${result.data}")
                    ))
                }
            }
        }
    }

    /** Pull-to-refresh: refresh avatar + managed repos only, no full scan. */
    fun refreshProfile() {
        if (c.refreshing.value) return
        val token = c.tokenStore.getToken() ?: return
        c.setRefreshing(true)
        viewModelScope.launch {
            val result = c.repository.refreshManaged(token)
            if (result is ApiResult.Error) {
                if (result.isUnauthorized) {
                    c.tokenStore.clearSync()
                    c.setLogin(LoginUiState()) // re-authorize; rules are preserved
                } else {
                    c.updateRepo { it.copy(error = result.message) }
                }
            }
            c.setRefreshing(false)
        }
    }

    /** Unchecking a workflow/repo drops its rules in Repository; cancel the
     *  corresponding alarms so a stale alarm cannot fire a ghost FAILED log,
     *  and TELL THE USER how many rules were removed (silent data loss was a
     *  top complaint in the audit). */
    private suspend fun cancelAlarmsForRemovedRules(change: suspend () -> Unit) {
        val before = c.repository.getRulesOnce().map { it.id }.toSet()
        change()
        val after = c.repository.getRulesOnce().map { it.id }.toSet()
        val removed = before - after
        removed.forEach { c.alarmScheduler.cancel(it) }
        if (removed.isNotEmpty()) {
            AppLogger.w("Repo", "removed ${removed.size} rule(s) with unchecked workflow/repo")
            c.updateRepo {
                it.copy(
                    messageKey = "已删除 {0} 条关联规则",
                    messageArgs = listOf(removed.size.toString())
                )
            }
        }
    }

    fun toggleWorkflow(id: Long, selected: Boolean) {
        viewModelScope.launch {
            if (selected) c.repository.setSelected(id, true)
            else cancelAlarmsForRemovedRules { c.repository.setSelected(id, false) }
        }
    }

    fun toggleManaged(repoId: Long, managed: Boolean) {
        viewModelScope.launch {
            if (managed) c.repository.setManaged(repoId, true)
            else cancelAlarmsForRemovedRules { c.repository.setManaged(repoId, false) }
        }
    }

    fun removeRepo(repoId: Long) {
        viewModelScope.launch { cancelAlarmsForRemovedRules { c.repository.removeRepo(repoId) } }
    }

    fun saveOrder(kind: String, ids: List<String>) {
        c.saveOrder(kind, ids)
    }

    suspend fun readOrder(kind: String): List<String> = c.orders.value[kind] ?: c.repository.readOrder(kind)

    fun <T> applyOrder(list: List<T>, order: List<String>, idOf: (T) -> String): List<T> =
        c.repository.applyOrder(list, order, idOf)

    /** Manually trigger a single workflow directly (not via a rule). */
    fun triggerWorkflowNow(repoFullName: String, workflowId: Long, workflowName: String) {
        viewModelScope.launch {
            val token = c.tokenStore.getToken() ?: return@launch
            val parts = repoFullName.split("/")
            if (parts.size != 2) return@launch
            val ref = c.repository.getSnapshotOnce().repos.firstOrNull { it.fullName == repoFullName }?.defaultBranch?.takeIf { it.isNotBlank() } ?: "main"
            when (val result = c.api.dispatchWorkflow(token, parts[0], parts[1], workflowId, ref)) {
                is ApiResult.Success -> {
                    AppLogger.i("Manual", "trigger OK wf=$workflowName repo=$repoFullName")
                    val logId = java.util.UUID.randomUUID().toString()
                    val now = System.currentTimeMillis()
                    c.repository.addLog(
                        TriggerLog(
                            id = logId,
                            ruleId = AppGraph.MANUAL_RULE_ID,
                            workflowName = workflowName,
                            workflowNames = listOf(workflowName),
                            repoFullName = repoFullName,
                            plannedAt = now,
                            firedAt = now,
                            status = TriggerStatus.SUCCESS,
                            httpCode = result.data,
                            tracking = true,
                            trackWorkflowIds = listOf(workflowId)
                        )
                    )
                    // Same real-duration tracking as rule triggers, so the history
                    // card shows a live-growing timer until the run completes.
                    RunTrackerWorker.enqueue(getApplication(), logId, AppGraph.MANUAL_RULE_ID)
                }
                is ApiResult.Error -> {
                    AppLogger.e("Manual", "trigger FAILED wf=$workflowName: ${result.message}")
                    // User-visible feedback + persisted failure record (was silent).
                    c.repository.addLog(
                        TriggerLog(
                            id = java.util.UUID.randomUUID().toString(),
                            ruleId = AppGraph.MANUAL_RULE_ID,
                            workflowName = workflowName, workflowNames = listOf(workflowName),
                            repoFullName = repoFullName, plannedAt = System.currentTimeMillis(),
                            firedAt = System.currentTimeMillis(), status = TriggerStatus.FAILED,
                            httpCode = result.code, errorMessage = result.message,
                            errorKey = "手动触发失败：{0}", errorArgs = listOf(result.message)
                        )
                    )
                    c.updateRepo { it.copy(error = com.lucaslu.tickhub.util.L10n.tFormat("手动触发失败：{0}", listOf(result.message))) }
                }
            }
        }
    }
}
