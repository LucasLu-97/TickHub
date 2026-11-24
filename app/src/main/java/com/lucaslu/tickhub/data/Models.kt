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
