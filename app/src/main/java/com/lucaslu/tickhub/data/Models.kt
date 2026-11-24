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
    ONCE, DAILY, WEEKDAYS, WEEKLY, EVERY_N_DAYS
}

enum class RuleStatus {
    ENABLED, DISABLED, PAUSED, COMPLETED
}

@Serializable
data class Rule(
    val id: String,
    val workflowId: Long,
    val workflowName: String = "",
    val repoFullName: String = "",
    val type: RuleType = RuleType.DAILY,
    val hour: Int = 8,
    val minute: Int = 0,
    val onceDate: String? = null,      // yyyy-MM-dd, for ONCE
    val weekdays: List<Int> = emptyList(), // 1=Mon..7=Sun, for WEEKLY
    val intervalDays: Int = 7,          // for EVERY_N_DAYS
    val startDate: String? = null,      // yyyy-MM-dd, anchor for EVERY_N_DAYS
    val status: RuleStatus = RuleStatus.ENABLED,
    val nextFireAt: Long? = null,       // epoch millis, computed by RuleEngine
    val lastFiredAt: Long? = null,
    val createdAt: Long = 0L
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
