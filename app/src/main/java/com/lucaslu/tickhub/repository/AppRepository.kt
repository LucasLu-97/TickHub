package com.lucaslu.tickhub.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lucaslu.tickhub.data.RepoItem
import com.lucaslu.tickhub.data.ScanSnapshot
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
    private val tokenUserKey = stringPreferencesKey("logged_in_user")
    private val lastScanKey = longPreferencesKey("last_scan_at")

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
}
