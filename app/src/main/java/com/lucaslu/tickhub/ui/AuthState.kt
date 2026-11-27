package com.lucaslu.tickhub.ui

import com.lucaslu.tickhub.data.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ephemeral UI state for auth + repo screen. Process-scoped, not persisted —
 * the persistent truth is TokenStore / DataStore. Split out of AppContainer so
 * the container only wires dependencies instead of owning UI state.
 */
class AuthState(tokenStore: TokenStore) {
    private val _login = MutableStateFlow(LoginUiState(isLoggedIn = tokenStore.getToken() != null))
    val login: StateFlow<LoginUiState> = _login.asStateFlow()
    fun setLogin(value: LoginUiState) { _login.value = value }
    fun updateLogin(transform: (LoginUiState) -> LoginUiState) { _login.value = transform(_login.value) }

    private val _repo = MutableStateFlow(RepoUiState())
    val repo: StateFlow<RepoUiState> = _repo.asStateFlow()
    fun setRepo(value: RepoUiState) { _repo.value = value }
    fun updateRepo(transform: (RepoUiState) -> RepoUiState) { _repo.value = transform(_repo.value) }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    fun setRefreshing(value: Boolean) { _refreshing.value = value }
}
