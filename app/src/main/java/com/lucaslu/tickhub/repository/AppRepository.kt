package com.lucaslu.tickhub.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lucaslu.tickhub.data.HolidayCalendar
import com.lucaslu.tickhub.data.RepoItem
import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.RuleStatus
import com.lucaslu.tickhub.data.ScanSnapshot
import com.lucaslu.tickhub.data.TriggerLog
import com.lucaslu.tickhub.data.WorkflowItem
import com.lucaslu.tickhub.domain.RuleEngine
import com.lucaslu.tickhub.network.ApiResult
import com.lucaslu.tickhub.network.GitHubApi
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tickhub_data")

/**
 * Repository for scanned repos/workflows and selection state.
 * Uses DataStore + JSON instead of Room to avoid the KSP/Kotlin version conflict
 * documented in the requirements (see §6.2 / risk R5).
 */
class AppRepository(private val context: Context, private val api: GitHubApi) {

    private val json = Json { ignoreUnknownKeys = true }
    private val snapshotKey = stringPreferencesKey("scan_snapshot_json")
    private val rulesKey = stringPreferencesKey("rules_json")
    private val logsKey = stringPreferencesKey("trigger_logs_json")
    private val tokenUserKey = stringPreferencesKey("logged_in_user")
    private val avatarKey = stringPreferencesKey("avatar_url")
    private val lastScanKey = longPreferencesKey("last_scan_at")
    private val networkWaitKey = androidx.datastore.preferences.core.intPreferencesKey("network_wait_minutes")
    private val lastCatchUpKey = longPreferencesKey("last_catchup_at")
    private val runningKey = stringPreferencesKey("running_executions_json") // ruleId -> startTime
    private val showExecutionKey = androidx.datastore.preferences.core.booleanPreferencesKey("show_execution_status")
    private val runTrackingKey = androidx.datastore.preferences.core.booleanPreferencesKey("run_tracking_enabled")
    private val hapticsKey = androidx.datastore.preferences.core.booleanPreferencesKey("haptics_enabled")

    val snapshot: Flow<ScanSnapshot> = context.dataStore.data.map { prefs ->
        val raw = prefs[snapshotKey]
        if (raw.isNullOrBlank()) ScanSnapshot()
        else try {
            json.decodeFromString<ScanSnapshot>(raw)
        } catch (e: Exception) {
            ScanSnapshot()
        }
    }

    val loggedInUser: Flow<String?> = context.dataStore.data.map { it[tokenUserKey] }

    val rules: Flow<List<Rule>> = context.dataStore.data.map { prefs ->
        val raw = prefs[rulesKey]
        if (raw.isNullOrBlank()) emptyList()
        else try {
            json.decodeFromString<List<Rule>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    val logs: Flow<List<TriggerLog>> = context.dataStore.data.map { prefs ->
        val raw = prefs[logsKey]
        if (raw.isNullOrBlank()) emptyList()
        else try {
            json.decodeFromString<List<TriggerLog>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    val networkWaitMinutes: Flow<Int> = context.dataStore.data.map { it[networkWaitKey] ?: 3 }

    // fixed 3-minute cap per product decision; kept as a setting key for compatibility
    suspend fun getNetworkWaitMinutesOnce(): Int = context.dataStore.data.first()[networkWaitKey] ?: 3

    suspend fun getRulesOnce(): List<Rule> = rules.first()

    suspend fun getLogsOnce(): List<TriggerLog> = logs.first()

    /** Compute next fire time with holiday calendar + deterministic drift. */
    suspend fun computeNextFire(rule: Rule, from: LocalDateTime): Pair<LocalDateTime?, Long?> {
        val year = from.year
        val holidays = HolidayCalendar.load(context, year)
        val isWorkday: (LocalDate) -> Boolean = { d ->
            HolidayCalendar.isLegalWorkday(holidays, d)
        }
        val isHoliday: (LocalDate) -> Boolean = { d ->
            HolidayCalendar.isLegalHoliday(holidays, d)
        }
        val next = RuleEngine.computeNextFire(rule, from, isWorkday, isHoliday)
        val base = next?.let { RuleEngine.toEpochMillis(it) }
        val effective = base?.let { RuleEngine.applyDrift(rule.id, it, rule.driftMinutes) }
        return next to effective
    }

    suspend fun getRuleOnce(id: String): Rule? = getRulesOnce().firstOrNull { it.id == id }

    /** Atomically update a rule. Returns the updated rule. */
    suspend fun updateRule(rule: Rule): Rule {
        context.dataStore.edit { prefs ->
            val current = readRules(prefs).toMutableList()
            val idx = current.indexOfFirst { it.id == rule.id }
            if (idx >= 0) current[idx] = rule else current.add(rule)
            prefs[rulesKey] = json.encodeToString(current)
        }
        return rule
    }

    suspend fun addLog(log: TriggerLog) {
        context.dataStore.edit { prefs ->
            val current = readLogs(prefs).toMutableList()
            current.add(0, log) // newest first
            val trimmed = current.take(200) // keep last 200
            prefs[logsKey] = json.encodeToString(trimmed)
        }
    }

    /** Attach discovered GitHub run ids to a log and mark it as tracking. */
    suspend fun updateLogTracking(logId: String, runIds: Map<String, Long>) {
        context.dataStore.edit { prefs ->
            val logs = readLogs(prefs).toMutableList()
            val idx = logs.indexOfFirst { it.id == logId }
            if (idx >= 0) {
                logs[idx] = logs[idx].copy(runIds = runIds, tracking = runIds.isNotEmpty())
                prefs[logsKey] = json.encodeToString(logs)
            }
        }
    }

    /** Mark a log as tracking right after dispatch, before GitHub has created the run.
     *  The tracker Worker discovers the run itself on its first poll (~20s later). */
    suspend fun markTracking(logId: String) {
        context.dataStore.edit { prefs ->
            val logs = readLogs(prefs).toMutableList()
            val idx = logs.indexOfFirst { it.id == logId }
            if (idx >= 0) {
                logs[idx] = logs[idx].copy(tracking = true)
                prefs[logsKey] = json.encodeToString(logs)
            }
        }
    }

    /** Update the live-growing duration of a still-tracking log (keeps tracking=true). */
    suspend fun updateLogElapsed(logId: String, durationMs: Long) {
        context.dataStore.edit { prefs ->
            val logs = readLogs(prefs).toMutableList()
            val idx = logs.indexOfFirst { it.id == logId }
            if (idx >= 0) {
                logs[idx] = logs[idx].copy(durationMs = durationMs, tracking = true)
                prefs[logsKey] = json.encodeToString(logs)
            }
        }
    }

    /** Stop tracking after the safety net without flipping a successful dispatch to FAILED. */
    suspend fun finalizeUnconfirmed(logId: String, durationMs: Long) {
        context.dataStore.edit { prefs ->
            val logs = readLogs(prefs).toMutableList()
            val idx = logs.indexOfFirst { it.id == logId }
            if (idx >= 0) {
                logs[idx] = logs[idx].copy(durationMs = durationMs, tracking = false)
                prefs[logsKey] = json.encodeToString(logs)
            }
        }
    }

    /** Finalize a tracked log with the real GitHub run duration and conclusion. */
    suspend fun finalizeLog(logId: String, durationMs: Long, success: Boolean) {
        context.dataStore.edit { prefs ->
            val logs = readLogs(prefs).toMutableList()
            val idx = logs.indexOfFirst { it.id == logId }
            if (idx >= 0) {
                val old = logs[idx]
                val newStatus = if (success) {
                    if (old.isMakeup) com.lucaslu.tickhub.data.TriggerStatus.MAKEUP
                    else com.lucaslu.tickhub.data.TriggerStatus.SUCCESS
                } else com.lucaslu.tickhub.data.TriggerStatus.FAILED
                logs[idx] = old.copy(
                    status = newStatus,
                    httpCode = null,
                    errorMessage = if (success) null else com.lucaslu.tickhub.util.L10n.tNow("工作流执行失败（GitHub 结论）"),
                    durationMs = durationMs,
                    tracking = false
                )
                prefs[logsKey] = json.encodeToString(logs)
            }
        }
    }

    suspend fun deleteLog(id: String) {
        context.dataStore.edit { prefs ->
            val kept = readLogs(prefs).filterNot { it.id == id }
            prefs[logsKey] = json.encodeToString(kept)
        }
    }

    suspend fun clearLogs() {
        context.dataStore.edit { it.remove(logsKey) }
    }

    // ---- execution status (running tasks) ----
    // Includes BOTH the short-lived dispatch Worker window AND logs still being
    // tracked against GitHub. So the spinner keeps running for the whole real
    // execution (minutes), not just the few seconds of the HTTP dispatch.
    val runningExecutions: Flow<Map<String, Long>> = context.dataStore.data.map { prefs ->
        val raw = prefs[runningKey]
        val result = (if (raw.isNullOrBlank()) emptyMap<String, Long>()
        else try { json.decodeFromString<Map<String, Long>>(raw) } catch (e: Exception) { emptyMap() }).toMutableMap()

        // merge in every log whose GitHub run is still executing
        readLogs(prefs).filter { it.tracking }.forEach { log ->
            val existing = result[log.ruleId]
            val start = log.firedAt ?: 0L
            if (existing == null || start < existing) result[log.ruleId] = start
        }
        result
    }

    val showExecutionStatus: Flow<Boolean> = context.dataStore.data.map { it[showExecutionKey] ?: true }

    suspend fun showExecutionStatusOnce(): Boolean = context.dataStore.data.first()[showExecutionKey] ?: true
    suspend fun runTrackingOnce(): Boolean = context.dataStore.data.first()[runTrackingKey] ?: true
    suspend fun hapticsOnce(): Boolean = context.dataStore.data.first()[hapticsKey] ?: true

    suspend fun setShowExecutionStatus(enabled: Boolean) {
        context.dataStore.edit { it[showExecutionKey] = enabled }
    }

    val runTrackingEnabled: Flow<Boolean> = context.dataStore.data.map { it[runTrackingKey] ?: true }

    suspend fun setRunTrackingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[runTrackingKey] = enabled }
    }

    suspend fun isRunTrackingEnabledOnce(): Boolean = context.dataStore.data.first()[runTrackingKey] ?: true

    val hapticsEnabled: Flow<Boolean> = context.dataStore.data.map { it[hapticsKey] ?: true }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[hapticsKey] = enabled }
    }

    // ---- Chinese statutory holiday calendar (China-only, opt-in, default off) ----
    private val holidayEnabledKey = androidx.datastore.preferences.core.booleanPreferencesKey("holiday_calendar_enabled")

    val holidayEnabled: Flow<Boolean> = context.dataStore.data.map { it[holidayEnabledKey] ?: false }
    suspend fun holidayEnabledOnce(): Boolean = context.dataStore.data.first()[holidayEnabledKey] ?: false
    suspend fun setHolidayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[holidayEnabledKey] = enabled }
    }

    // ---- card ordering (reorder by long-press drag) ----
    private fun orderKey(kind: String) = androidx.datastore.preferences.core.stringPreferencesKey("order_$kind")

    suspend fun saveOrder(kind: String, ids: List<String>) {
        context.dataStore.edit { it[orderKey(kind)] = json.encodeToString(ids) }
    }

    suspend fun readOrder(kind: String): List<String> {
        val raw = context.dataStore.data.first()[orderKey(kind)] ?: return emptyList()
        return try { json.decodeFromString(raw) } catch (e: Exception) { emptyList() }
    }

    /** All saved orders as a flow, so the UI renders the persisted order on the first frame. */
    val allOrders: Flow<Map<String, List<String>>> = context.dataStore.data.map { prefs ->
        listOf("repos", "rules", "logs").associateWith { kind ->
            val raw = prefs[orderKey(kind)] ?: return@associateWith emptyList()
            try { json.decodeFromString<List<String>>(raw) } catch (e: Exception) { emptyList() }
        }
    }

    /** Apply a saved id order to a list, keeping any new items at the end. */
    fun <T> applyOrder(list: List<T>, order: List<String>, idOf: (T) -> String): List<T> {
        if (order.isEmpty()) return list
        val byId = list.associateBy(idOf)
        val head = order.mapNotNull { byId[it] }
        val tail = list.filter { idOf(it) !in order.toSet() }
        return head + tail
    }

    suspend fun markRunning(ruleId: String, startAt: Long) {
        context.dataStore.edit { prefs ->
            val current = runningOnce(prefs).toMutableMap()
            current[ruleId] = startAt
            prefs[runningKey] = json.encodeToString(current)
        }
    }

    suspend fun markDone(ruleId: String) {
        context.dataStore.edit { prefs ->
            val current = runningOnce(prefs).toMutableMap()
            current.remove(ruleId)
            prefs[runningKey] = json.encodeToString(current)
        }
    }

    suspend fun runningStartOnce(ruleId: String): Long? =
        context.dataStore.data.first()[runningKey]?.let { raw ->
            try { json.decodeFromString<Map<String, Long>>(raw)[ruleId] } catch (e: Exception) { null }
        }

    private fun runningOnce(prefs: Preferences): Map<String, Long> {
        val raw = prefs[runningKey] ?: return emptyMap()
        return try { json.decodeFromString(raw) } catch (e: Exception) { emptyMap() }
    }

    suspend fun getLastCatchUpAt(): Long = context.dataStore.data.first()[lastCatchUpKey] ?: 0L
    suspend fun setLastCatchUpAt(ts: Long) {
        context.dataStore.edit { it[lastCatchUpKey] = ts }
    }

    suspend fun saveUser(login: String, avatarUrl: String? = null) {
        context.dataStore.edit { p ->
            p[tokenUserKey] = login
            if (avatarUrl != null) p[avatarKey] = avatarUrl
        }
    }

    val avatarUrl: Flow<String?> = context.dataStore.data.map { it[avatarKey] }

    suspend fun clearUser() {
        context.dataStore.edit { p ->
            p.remove(tokenUserKey)
            p.remove(avatarKey)
        }
    }

    suspend fun setSelected(workflowId: Long, selected: Boolean) {
        context.dataStore.edit { prefs ->
            val current = readSnapshot(prefs)
            val workflows = current.workflows.map {
                if (it.id == workflowId) it.copy(selected = selected) else it
            }
            // keep each repo's managed flag in sync with actual selections
            val repos = current.repos.map { repo ->
                val wfs = workflows.filter { it.repoId == repo.id }
                repo.copy(managed = wfs.isNotEmpty() && wfs.all { it.selected })
            }
            prefs[snapshotKey] = json.encodeToString(current.copy(workflows = workflows, repos = repos))

            // when unchecking a workflow, drop rules that would have no workflow left
            if (!selected) {
                val selectedIds = workflows.filter { it.selected }.map { it.id }.toSet()
                val kept = readRules(prefs).filter { rule ->
                    rule.resolvedWorkflowIds().any { selectedIds.contains(it) }
                }
                prefs[rulesKey] = json.encodeToString(kept)
            }
        }
    }

    suspend fun setManaged(repoId: Long, managed: Boolean) {
        context.dataStore.edit { prefs ->
            val current = readSnapshot(prefs)
            val updated = current.copy(
                repos = current.repos.map {
                    if (it.id == repoId) it.copy(managed = managed) else it
                },
                workflows = current.workflows.map {
                    if (it.repoId == repoId) it.copy(selected = managed) else it
                }
            )
            prefs[snapshotKey] = json.encodeToString(updated)
            if (!managed) {
                val selectedIds = updated.workflows.filter { it.selected }.map { it.id }.toSet()
                val kept = readRules(prefs).filter { rule ->
                    rule.resolvedWorkflowIds().any { selectedIds.contains(it) }
                }
                prefs[rulesKey] = json.encodeToString(kept)
            }
        }
    }

    /** Set selection for all workflows of a repo, and sync the managed flag. */
    suspend fun setRepoWorkflowsSelected(repoId: Long, selected: Boolean) {
        setManaged(repoId, selected)
    }

    /**
     * Light refresh for pull-to-refresh: update avatar/login and refresh workflows
     * only for already-managed repos. Does NOT enumerate all repos (that is scan()).
     */
    suspend fun refreshManaged(token: String): ApiResult<Int> {
        // refresh profile
        when (val user = api.getCurrentUser(token)) {
            is ApiResult.Success -> saveUser(user.data.login, user.data.avatarUrl)
            is ApiResult.Error -> { /* keep old profile */ }
        }
        val current = snapshot.first()
        if (current.repos.isEmpty()) return ApiResult.Success(0)
        val managedRepos = current.repos.filter { it.managed }
        if (managedRepos.isEmpty()) return ApiResult.Success(0)

        val prevWf = current.workflows.associateBy { it.id }
        val refreshed = current.workflows.toMutableList()
        var failures = 0
        for (repo in managedRepos) {
            when (val wfResult = api.listWorkflows(token, repo.owner, repo.name)) {
                is ApiResult.Success -> {
                    refreshed.removeAll { it.repoId == repo.id }
                    wfResult.data.forEach { wf ->
                        refreshed.add(wf.copy(repoId = repo.id, selected = prevWf[wf.id]?.selected ?: false))
                    }
                }
                is ApiResult.Error -> failures++
            }
        }
        val updated = current.copy(workflows = refreshed, scannedAt = System.currentTimeMillis())
        context.dataStore.edit { it[snapshotKey] = json.encodeToString(updated) }
        return ApiResult.Success(failures)
    }

    /** Scan repos and their workflows, preserving existing selection/managed state. */
    suspend fun scan(token: String): ApiResult<Int> {
        when (val reposResult = api.listOwnRepos(token)) {
            is ApiResult.Error -> return reposResult
            is ApiResult.Success -> {
                val freshRepos = reposResult.data
                val previous = snapshot.first()
                val prevRepoState = previous.repos.associateBy { it.id }
                val prevWfState = previous.workflows.associateBy { it.id }

                val mergedRepos = mutableListOf<RepoItem>()
                val mergedWorkflows = mutableListOf<WorkflowItem>()
                var failures = 0

                for (repo in freshRepos) {
                    val managed = prevRepoState[repo.id]?.managed ?: false
                    mergedRepos.add(repo.copy(managed = managed))

                    when (val wfResult = api.listWorkflows(token, repo.owner, repo.name)) {
                        is ApiResult.Success -> {
                            wfResult.data.forEach { wf ->
                                mergedWorkflows.add(
                                    wf.copy(
                                        repoId = repo.id,
                                        selected = prevWfState[wf.id]?.selected ?: false
                                    )
                                )
                            }
                        }
                        is ApiResult.Error -> failures++
                    }
                }

                val newSnapshot = ScanSnapshot(
                    repos = mergedRepos,
                    workflows = mergedWorkflows,
                    scannedAt = System.currentTimeMillis()
                )
                context.dataStore.edit { prefs ->
                    prefs[snapshotKey] = json.encodeToString(newSnapshot)
                    prefs[lastScanKey] = newSnapshot.scannedAt
                }
                return ApiResult.Success(failures)
            }
        }
    }

    suspend fun saveRule(rule: Rule) {
        context.dataStore.edit { prefs ->
            val current = readRules(prefs).toMutableList()
            val idx = current.indexOfFirst { it.id == rule.id }
            if (idx >= 0) current[idx] = rule else current.add(rule)
            prefs[rulesKey] = json.encodeToString(current)
        }
    }

    suspend fun deleteRule(id: String) {
        context.dataStore.edit { prefs ->
            val current = readRules(prefs).filterNot { it.id == id }
            prefs[rulesKey] = json.encodeToString(current)
        }
    }

    suspend fun setRuleEnabled(id: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = readRules(prefs).map {
                if (it.id == id) it.copy(status = if (enabled) com.lucaslu.tickhub.data.RuleStatus.ENABLED else com.lucaslu.tickhub.data.RuleStatus.DISABLED)
                else it
            }
            prefs[rulesKey] = json.encodeToString(current)
        }
    }

    suspend fun removeRepo(repoId: Long) {
        context.dataStore.edit { prefs ->
            val current = readSnapshot(prefs)
            val wfIds = current.workflows.filter { it.repoId == repoId }.map { it.id }
            val updated = current.copy(
                repos = current.repos.filterNot { it.id == repoId },
                workflows = current.workflows.filterNot { it.repoId == repoId }
            )
            prefs[snapshotKey] = json.encodeToString(updated)
            // drop rules whose workflows belonged to this repo
            val kept = readRules(prefs).filter { rule ->
                rule.resolvedWorkflowIds().none { wfIds.contains(it) }
            }
            prefs[rulesKey] = json.encodeToString(kept)
        }
    }

    suspend fun clearSnapshot() {
        context.dataStore.edit { prefs ->
            prefs.remove(snapshotKey)
            prefs.remove(lastScanKey)
        }
    }

    private fun readSnapshot(prefs: Preferences): ScanSnapshot {
        val raw = prefs[snapshotKey] ?: return ScanSnapshot()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            ScanSnapshot()
        }
    }

    private fun readRules(prefs: Preferences): List<Rule> {
        val raw = prefs[rulesKey] ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readLogs(prefs: Preferences): List<TriggerLog> {
        val raw = prefs[logsKey] ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
