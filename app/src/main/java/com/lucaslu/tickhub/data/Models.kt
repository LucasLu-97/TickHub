package com.lucaslu.tickhub.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- Domain models (persisted via DataStore JSON) ----

@Serializable
data class RepoItem(
    val id: Long,
    val owner: String,
    val name: String,
    val fullName: String,
    val private: Boolean = false,
    val defaultBranch: String = "main",
    val updatedAt: String = "",
    val managed: Boolean = false
)

@Serializable
data class WorkflowItem(
    val id: Long,
    val repoId: Long,
    val name: String,
    val path: String,
    val state: String = "active",
    val selected: Boolean = false
)

@Serializable
data class ScanSnapshot(
    val repos: List<RepoItem> = emptyList(),
    val workflows: List<WorkflowItem> = emptyList(),
    val scannedAt: Long = 0L
)

enum class RuleType {
    ONCE, DAILY, WEEKLY, LEGAL_WORKDAY, LEGAL_HOLIDAY, EVERY_N_DAYS,
    @Deprecated("legacy alias kept so old persisted rules still deserialize")
    WEEKDAYS
}

enum class RuleStatus {
    ENABLED, DISABLED, PAUSED, COMPLETED
}

@Serializable
data class Rule(
    val id: String,
    val workflowId: Long = 0L,          // legacy single (kept for old saved data)
    val workflowIds: List<Long> = emptyList(), // multiple workflows in one rule
    val workflowName: String = "",
    val workflowNames: List<String> = emptyList(),
    val repoFullName: String = "",
    val repoId: Long = 0L,              // owner repo for grouping
    val type: RuleType = RuleType.DAILY,
    val hour: Int = 8,
    val minute: Int = 0,
    val timeSlots: List<String> = emptyList(), // multiple "HH:mm" slots; empty => use hour/minute
    val onceDate: String? = null,      // yyyy-MM-dd, for ONCE
    val weekdays: List<Int> = emptyList(), // 1=Mon..7=Sun, for WEEKLY
    val intervalDays: Int = 7,          // for EVERY_N_DAYS
    val startDate: String? = null,      // yyyy-MM-dd, anchor for EVERY_N_DAYS
    val driftMinutes: Int = 0,          // 0/10/30/60: random delay within window
    val status: RuleStatus = RuleStatus.ENABLED,
    val nextFireAt: Long? = null,       // base time (before drift), epoch millis
    val effectiveFireAt: Long? = null,  // actual scheduled time with drift applied
    val lastFiredAt: Long? = null,
    val createdAt: Long = 0L
) {
    /** Resolve effective workflow ids, tolerant of legacy single-workflow rules. */
    fun resolvedWorkflowIds(): List<Long> =
        workflowIds.ifEmpty { if (workflowId != 0L) listOf(workflowId) else emptyList() }
}

enum class TriggerStatus {
    SUCCESS, FAILED, SKIPPED, MAKEUP
}

@Serializable
data class TriggerLog(
    val id: String,
    val ruleId: String,
    val workflowName: String = "",
    val workflowNames: List<String> = emptyList(), // all workflows fired by this rule
    val repoFullName: String = "",
    val plannedAt: Long = 0L,
    val firedAt: Long? = null,
    val status: TriggerStatus = TriggerStatus.SUCCESS,
    val httpCode: Int? = null,
    val errorMessage: String? = null,
    val isMakeup: Boolean = false,
    val durationMs: Long = 0L,
    val runIds: Map<String, Long> = emptyMap(), // workflowId -> GitHub run id, for real-duration tracking
    val tracking: Boolean = false, // true while waiting for GitHub run to finish
    val trackWorkflowIds: List<Long> = emptyList(), // workflow ids to track for manual (non-rule) triggers
    val errorKey: String? = null, // translatable error key; UI renders via L10n at display time
    val errorArgs: List<String> = emptyList() // placeholder args for errorKey ({0}, {1}...)
)

// ---- GitHub REST DTOs ----

@Serializable
data class GhUser(
    val login: String,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null
)

@Serializable
data class GhRepo(
    val id: Long,
    val name: String,
    @SerialName("full_name") val fullName: String,
    val private: Boolean = false,
    val owner: GhOwner,
    @SerialName("default_branch") val defaultBranch: String = "main",
    @SerialName("updated_at") val updatedAt: String = ""
)

@Serializable
data class GhOwner(val login: String)

@Serializable
data class GhWorkflow(
    val id: Long,
    val name: String,
    val path: String,
    val state: String = "active"
)

@Serializable
data class GhWorkflowList(
    @SerialName("total_count") val totalCount: Int = 0,
    val workflows: List<GhWorkflow> = emptyList()
)

/** A GitHub Actions workflow run, used to track real execution duration. */
@Serializable
data class GhWorkflowRun(
    val id: Long,
    val status: String = "",            // queued | in_progress | completed
    val conclusion: String? = null,     // success | failure | cancelled | ...
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("run_started_at") val runStartedAt: String = "",
    @SerialName("updated_at") val updatedAt: String = ""
)

@Serializable
data class GhRunList(
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("workflow_runs") val workflowRuns: List<GhWorkflowRun> = emptyList()
)

// ---- Device Flow DTOs ----

@Serializable
data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Int = 900,
    val interval: Int = 5,
    @SerialName("error") val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null
)
