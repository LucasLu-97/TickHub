package com.lucaslu.tickhub.ui

/** Ephemeral UI state for the login screen. */
data class LoginUiState(
    val isLoggedIn: Boolean = false,
    val userCode: String? = null,
    val verificationUri: String? = null,
    val status: String = "",
    val error: String? = null,
    val loading: Boolean = false,
    val statusKey: String? = null,
    val statusArgs: List<String> = emptyList()
)

/** Ephemeral UI state for the repo screen. */
data class RepoUiState(
    val scanning: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val messageKey: String? = null,   // translatable template with {0} placeholders
    val messageArgs: List<String> = emptyList()
)
