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

/** Cold-start preference snapshot read in a single DataStore transaction. */
data class InitialPrefs(
    val orders: Map<String, List<String>> = emptyMap(),
    val showExecution: Boolean = true,
    val runTracking: Boolean = true,
    val haptics: Boolean = true,
    val holiday: Boolean = false,
    val themeMode: Int = 2
)

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
    private val runningKey = stringPreferencesKey("running_executions_json") // ruleId -> startTime
    private val showExecutionKey = androidx.datastore.preferences.core.booleanPreferencesKey("show_execution_status")
    private val runTrackingKey = androidx.datastore.preferences.core.booleanPreferencesKey("run_tracking_enabled")
    private val hapticsKey = androidx.datastore.preferences.core.booleanPreferencesKey("haptics_enabled")
    private val themeModeKey = androidx.datastore.preferences.core.intPreferencesKey("theme_mode")

    val snapshot: Flow<ScanSnapshot> = context.dataStore.data.map { prefs ->
        val raw = prefs[snapshotKey]
        if (raw.isNullOrBlank()) ScanSnapshot()
        else try {
            json.decodeFromString<ScanSnapshot>(raw)
        } catch (e: Exception) {
            com.lucaslu.tickhub.util.AppLogger.e("DataIntegrity", "snapshot flow JSON corrupt: ${e.message}")
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
            com.lucaslu.tickhub.util.AppLogger.e("DataIntegrity", "rules flow JSON corrupt: ${e.message}")
            emptyList()
        }
    }

    val logs: Flow<List<TriggerLog>> = context.dataStore.data.map { prefs ->
        val raw = prefs[logsKey]
        if (raw.isNullOrBlank()) emptyList()
        else try {
            json.decodeFromString<List<TriggerLog>>(raw)
        } catch (e: Exception) {
            com.lucaslu.tickhub.util.AppLogger.e("DataIntegrity", "logs flow JSON corrupt: ${e.message}")
            emptyList()
        }
    }


    // fixed 3-minute cap per product decision; kept as a setting key for compatibility
    companion object {
        /** Fixed 3-minute network wait cap (was a never-written preference). */
        const val NETWORK_WAIT_MINUTES = 3
    }
    suspend fun getNetworkWaitMinutesOnce(): Int = NETWORK_WAIT_MINUTES

    suspend fun getSnapshotOnce(): com.lucaslu.tickhub.data.ScanSnapshot = snapshot.first()

    suspend fun getRulesOnce(): List<Rule> = rules.first()

    suspend fun getLogsOnce(): List<TriggerLog> = logs.first()

    /** Compute next fire time with holiday calendar + deterministic drift.
     *  Loads the current AND next year so cross-year holidays (e.g. New Year in
     *  December) resolve correctly instead of returning null. */
    suspend fun computeNextFire(rule: Rule, from: LocalDateTime): Pair<LocalDateTime?, Long?> {
        val year = from.year
        // Load year-1..year+2 (M5): a 400-day scan in late December can reach
        // year+2; using the wrong year's calendar could fire on a weekend.
        val years = (year - 1..year + 2).associateWith { HolidayCalendar.load(context, it) }
        val isWorkday: (LocalDate) -> Boolean = { d ->
            HolidayCalendar.isLegalWorkday(years[d.year] ?: years.getValue(year), d)
        }
        val isHoliday: (LocalDate) -> Boolean = { d ->
            HolidayCalendar.isLegalHoliday(years[d.year] ?: years.getValue(year), d)
        }

        // C4: LEGAL_HOLIDAY / LEGAL_WORKDAY with no calendar data must NOT return
        // null (COMPLETED forever) AND must not silently fall back to Mon-Fri for
        // LEGAL_WORKDAY — that would check in on statutory holidays and skip
        // make-up weekends. Keep ENABLED with a 30-day probe for both types.
        val needsHolidayData = rule.type in setOf(
            com.lucaslu.tickhub.data.RuleType.LEGAL_HOLIDAY,
            com.lucaslu.tickhub.data.RuleType.LEGAL_WORKDAY
        ) && years.getValue(year).days.isEmpty() && years.getValue(year + 1).days.isEmpty()
        if (needsHolidayData) {
            com.lucaslu.tickhub.util.AppLogger.w("Repo", "holiday data missing for ${rule.id.take(8)} — keep ENABLED, probe in 30d")
            val probe = from.plusDays(30)
            val base = RuleEngine.toEpochMillis(probe)
            return probe to RuleEngine.applyDrift(rule.id, base, rule.driftMinutes)
        }

        val next = RuleEngine.computeNextFire(rule, from, isWorkday, isHoliday)
        val base = next?.let { RuleEngine.toEpochMillis(it) }
        val effective = base?.let { RuleEngine.applyDrift(rule.id, it, rule.driftMinutes) }
        return next to effective
    }

    suspend fun getRuleOnce(id: String): Rule? = getRulesOnce().firstOrNull { it.id == id }

    /** Atomically update a rule from the LATEST stored object via a patch lambda.
     *  Prevents stale-object overwrites when multiple workers/VMs touch one rule.
     *  Returns the updated rule (or null if the rule no longer exists). */
    suspend fun updateRuleFields(id: String, patch: (Rule) -> Rule): Rule? {
        var result: Rule? = null
        context.dataStore.edit { prefs ->
            val current = readRules(prefs).toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val updated = patch(current[idx]) // patch the LATEST object, not a stale snapshot
                current[idx] = updated
                result = updated
                prefs[rulesKey] = json.encodeToString(current)
            }
        }
        return result
    }

    /** Legacy full-object update — prefer [updateRuleFields] to avoid stale writes. */
    suspend fun updateRule(rule: Rule): Rule = updateRuleFields(rule.id) { rule } ?: rule

    /**
     * CRITICAL atomicity: write the outcome log and apply the rule transition in
     * ONE dataStore.edit. A process kill between the two can no longer leave a
     * SUCCESS log with an un-advanced rule (slot stranded forever) or vice versa.
     * Returns the updated rule so the caller can re-register the alarm.
     */
    suspend fun commitDispatchOutcome(
        log: TriggerLog,
        tracking: Boolean,
        ruleId: String,
        transition: DispatchTransition
    ): Rule? {
        var result: Rule? = null
        context.dataStore.edit { prefs ->
            // 1) append log (newest first, 200-cap with 24h tracking expiry)
            val logs = readLogs(prefs).toMutableList()
            logs.add(0, if (tracking) log.copy(tracking = true) else log)
            prefs[logsKey] = json.encodeToString(trimLogs(logs))

            // 2) patch rule from the LATEST stored object
            val rules = readRules(prefs).toMutableList()
            val idx = rules.indexOfFirst { it.id == ruleId }
            if (idx >= 0) {
                val cur = rules[idx]
                // Pure state machine — see TransitionApplier (JVM-testable).
                val updated = TransitionApplier.apply(cur, transition, System.currentTimeMillis())
                rules[idx] = updated
                result = updated
                prefs[rulesKey] = json.encodeToString(rules)
            }
        }
        return result
    }

    /** Trim to the newest 200, preserving still-tracking logs — but expire any
     *  tracking older than 24h so a stuck tracker cannot grow the list forever
     *  or show a permanent spinner (M3). */
    private fun trimLogs(current: List<TriggerLog>): List<TriggerLog> {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
        val normalized = current.map { if (it.tracking && (it.firedAt ?: 0L) < cutoff) it.copy(tracking = false) else it }
        val kept = normalized.take(200).toMutableList()
        // Keep only recent tracking logs beyond the cap, in newest-first order.
        val extra = normalized.drop(200).filter { it.tracking }.sortedByDescending { it.firedAt }.take(10)
        kept.addAll(extra)
        return kept
    }

    /** Add a log. [tracking] is set atomically at insert time so a crash between
     *  addLog and a separate markTracking cannot leave an untracked dispatched log. */
    suspend fun addLog(log: TriggerLog, tracking: Boolean = false) {
        context.dataStore.edit { prefs ->
            val current = readLogs(prefs).toMutableList()
            current.add(0, if (tracking) log.copy(tracking = true) else log) // newest first
            prefs[logsKey] = json.encodeToString(trimLogs(current))
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
    suspend fun finalizeLog(logId: String, durationMs: Long, success: Boolean, cancelled: Boolean = false) {
        context.dataStore.edit { prefs ->
            val logs = readLogs(prefs).toMutableList()
            val idx = logs.indexOfFirst { it.id == logId }
            if (idx >= 0) {
                val old = logs[idx]
                val newStatus = if (cancelled) com.lucaslu.tickhub.data.TriggerStatus.SKIPPED
                else if (success) {
                    if (old.isMakeup) com.lucaslu.tickhub.data.TriggerStatus.MAKEUP
                    else com.lucaslu.tickhub.data.TriggerStatus.SUCCESS
                } else com.lucaslu.tickhub.data.TriggerStatus.FAILED
                logs[idx] = old.copy(
                    status = newStatus,
                    httpCode = null,
                    errorMessage = if (success || cancelled) null else "工作流执行失败（GitHub 结论）",
                    errorKey = if (success || cancelled) null else "工作流执行失败（GitHub 结论）",
                    errorArgs = emptyList(),
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

    suspend fun showExecutionStatusOnce(): Boolean = runCatching { context.dataStore.data.first()[showExecutionKey] ?: true }.getOrDefault(true)
    suspend fun hapticsOnce(): Boolean = runCatching { context.dataStore.data.first()[hapticsKey] ?: true }.getOrDefault(true)

    /** Theme mode: 0=light, 1=dark, 2=system. Defaults to system (2). */
    val themeMode: Flow<Int> = context.dataStore.data.map { it[themeModeKey] ?: 2 }
    suspend fun themeModeOnce(): Int = runCatching { context.dataStore.data.first()[themeModeKey] ?: 2 }.getOrDefault(2)
    suspend fun setThemeMode(mode: Int) { context.dataStore.edit { it[themeModeKey] = mode } }

    /** Atomic read-modify-write inside a single edit — rapid double-taps can't
     *  both read the same old value and collapse two cycles into one. */
    suspend fun cycleThemeMode() {
        context.dataStore.edit { prefs ->
            val cur = prefs[themeModeKey] ?: 2
            prefs[themeModeKey] = (cur + 1) % 3
        }
    }

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
    suspend fun holidayEnabledOnce(): Boolean = runCatching { context.dataStore.data.first()[holidayEnabledKey] ?: false }.getOrDefault(false)
    suspend fun setHolidayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[holidayEnabledKey] = enabled }
    }

    private val timezoneKey = androidx.datastore.preferences.core.stringPreferencesKey("app_timezone_id")
    suspend fun getSavedTimezone(): String? = context.dataStore.data.first()[timezoneKey]
    suspend fun setSavedTimezone(tz: String) { context.dataStore.edit { it[timezoneKey] = tz } }

    private val holidayAnnualFetchYearKey = androidx.datastore.preferences.core.intPreferencesKey("holiday_annual_fetch_year")
    suspend fun holidayAnnualFetchYearOnce(): Int = context.dataStore.data.first()[holidayAnnualFetchYearKey] ?: 0
    suspend fun setHolidayAnnualFetchYear(year: Int) {
        context.dataStore.edit { it[holidayAnnualFetchYearKey] = year }
    }

    // ---- card ordering (reorder by long-press drag) ----
    private fun orderKey(kind: String) = androidx.datastore.preferences.core.stringPreferencesKey("order_$kind")

    /** Card orders as a single Flow (single source of truth for UI state). */
    val orders: Flow<Map<String, List<String>>> = context.dataStore.data.map { p ->
        listOf("repos", "rules", "logs").associateWith { kind ->
            val raw = p[orderKey(kind)] ?: return@associateWith emptyList()
            try { json.decodeFromString(raw) } catch (e: Exception) { emptyList() }
        }
    }

    suspend fun saveOrder(kind: String, ids: List<String>) {
        context.dataStore.edit { it[orderKey(kind)] = json.encodeToString(ids) }
    }

    suspend fun readOrder(kind: String): List<String> {
        val raw = context.dataStore.data.first()[orderKey(kind)] ?: return emptyList()
        return try { json.decodeFromString(raw) } catch (e: Exception) { emptyList() }
    }

    /** Cold-start optimisation: ONE DataStore read instead of eight separate
     *  `first()` calls. Same values, far less main-thread IO at startup. */
    suspend fun readInitialPrefsOnce(): InitialPrefs = runCatching {
        context.dataStore.data.first().let { p ->
            fun decodeOrder(kind: String): List<String> {
                val raw = p[orderKey(kind)] ?: return emptyList()
                return try { json.decodeFromString(raw) } catch (e: Exception) { emptyList() }
            }
            InitialPrefs(
                orders = listOf("repos", "rules", "logs").associateWith { decodeOrder(it) },
                showExecution = p[showExecutionKey] ?: true,
                runTracking = p[runTrackingKey] ?: true,
                haptics = p[hapticsKey] ?: true,
                holiday = p[holidayEnabledKey] ?: false,
                themeMode = p[themeModeKey] ?: 2
            )
        }
    }.getOrDefault(InitialPrefs())

    /** All saved orders as a flow, so the UI renders the persisted order on the first frame. */
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

    /** Remove every account-scoped datum on logout. Preferences (network wait,
     *  show-execution, haptics, run tracking, language, holidays) are kept. */
    suspend fun clearAllUserData() {
        context.dataStore.edit { p ->
            listOf(
                tokenUserKey, avatarKey, snapshotKey, rulesKey, logsKey,
                lastScanKey, runningKey
            ).forEach { p.remove(it) }
            listOf("repos", "rules", "logs").forEach { p.remove(orderKey(it)) }
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
        context.dataStore.edit { prefs ->
            // Re-read the latest snapshot inside edit: a user may have toggled
            // selections while the network calls were in flight. Apply the fresh
            // workflow list but keep the newest selected/managed state.
            val latest = readSnapshot(prefs)
            val latestSelected = latest.workflows.associateBy { it.id }
            val merged = refreshed.map { wf ->
                wf.copy(selected = latestSelected[wf.id]?.selected ?: wf.selected)
            }
            val latestManaged = latest.repos.associateBy { it.id }
            val repos = latest.repos.map { r ->
                r.copy(managed = latestManaged[r.id]?.managed ?: r.managed)
            }
            prefs[snapshotKey] = json.encodeToString(latest.copy(repos = repos, workflows = merged, scannedAt = System.currentTimeMillis()))
        }
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

                context.dataStore.edit { prefs ->
                    // Preserve any selection/managed changes made while scanning.
                    val latest = readSnapshot(prefs)
                    val latestRepo = latest.repos.associateBy { it.id }
                    val latestWf = latest.workflows.associateBy { it.id }
                    val repos = mergedRepos.map { r -> r.copy(managed = latestRepo[r.id]?.managed ?: r.managed) }
                    val workflows = mergedWorkflows.map { w -> w.copy(selected = latestWf[w.id]?.selected ?: w.selected) }
                    val scannedAt = System.currentTimeMillis()
                    prefs[snapshotKey] = json.encodeToString(ScanSnapshot(repos, workflows, scannedAt))
                    prefs[lastScanKey] = scannedAt
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
                if (it.id == id) {
                    if (enabled) it.copy(status = com.lucaslu.tickhub.data.RuleStatus.ENABLED)
                    else it.copy(status = com.lucaslu.tickhub.data.RuleStatus.DISABLED, retryAt = null, retryPlannedAt = null, retryFailedWfIds = emptyList())
                } else it
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


    private fun readSnapshot(prefs: Preferences): ScanSnapshot {
        val raw = prefs[snapshotKey] ?: return ScanSnapshot()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            com.lucaslu.tickhub.util.AppLogger.e("DataIntegrity", "snapshot JSON corrupt, treating as empty: ${e.message}")
            ScanSnapshot()
        }
    }

    private fun readRules(prefs: Preferences): List<Rule> {
        val raw = prefs[rulesKey] ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            com.lucaslu.tickhub.util.AppLogger.e("DataIntegrity", "rules JSON corrupt, treating as empty: ${e.message}")
            emptyList()
        }
    }

    private fun readLogs(prefs: Preferences): List<TriggerLog> {
        val raw = prefs[logsKey] ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            com.lucaslu.tickhub.util.AppLogger.e("DataIntegrity", "logs JSON corrupt, treating as empty: ${e.message}")
            emptyList()
        }
    }
}
