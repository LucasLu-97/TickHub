package com.lucaslu.tickhub.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lucaslu.tickhub.data.RepoItem
import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.RuleStatus
import com.lucaslu.tickhub.data.ScanSnapshot
import com.lucaslu.tickhub.data.TriggerLog
import com.lucaslu.tickhub.data.WorkflowItem
import com.lucaslu.tickhub.network.ApiResult
import com.lucaslu.tickhub.network.GitHubApi
import kotlinx.coroutines.flow.Flow
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
    private val lastScanKey = longPreferencesKey("last_scan_at")
    private val networkWaitKey = androidx.datastore.preferences.core.intPreferencesKey("network_wait_minutes")
    private val lastCatchUpKey = longPreferencesKey("last_catchup_at")

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

    val networkWaitMinutes: Flow<Int> = context.dataStore.data.map { it[networkWaitKey] ?: 5 }

    suspend fun setNetworkWaitMinutes(minutes: Int) {
        context.dataStore.edit { it[networkWaitKey] = minutes.coerceIn(1, 15) }
    }

    suspend fun getNetworkWaitMinutesOnce(): Int = context.dataStore.data.first()[networkWaitKey] ?: 5

    suspend fun getRulesOnce(): List<Rule> = rules.first()

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

    suspend fun clearLogs() {
        context.dataStore.edit { it.remove(logsKey) }
    }

    suspend fun getLastCatchUpAt(): Long = context.dataStore.data.first()[lastCatchUpKey] ?: 0L
    suspend fun setLastCatchUpAt(ts: Long) {
        context.dataStore.edit { it[lastCatchUpKey] = ts }
    }

    suspend fun saveUser(login: String) {
        context.dataStore.edit { it[tokenUserKey] = login }
    }

    suspend fun clearUser() {
        context.dataStore.edit { it.remove(tokenUserKey) }
    }

    suspend fun setSelected(workflowId: Long, selected: Boolean) {
        context.dataStore.edit { prefs ->
            val current = readSnapshot(prefs)
            val updated = current.copy(
                workflows = current.workflows.map {
                    if (it.id == workflowId) it.copy(selected = selected) else it
                }
            )
            prefs[snapshotKey] = json.encodeToString(updated)
        }
    }

    suspend fun setManaged(repoId: Long, managed: Boolean) {
        context.dataStore.edit { prefs ->
            val current = readSnapshot(prefs)
            val updated = current.copy(
                repos = current.repos.map {
                    if (it.id == repoId) it.copy(managed = managed) else it
                }
            )
            prefs[snapshotKey] = json.encodeToString(updated)
        }
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
