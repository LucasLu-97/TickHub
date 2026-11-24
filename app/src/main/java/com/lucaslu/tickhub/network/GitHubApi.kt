package com.lucaslu.tickhub.network

import com.lucaslu.tickhub.BuildConfig
import com.lucaslu.tickhub.data.DeviceCodeResponse
import com.lucaslu.tickhub.data.GhOwner
import com.lucaslu.tickhub.data.GhRepo
import com.lucaslu.tickhub.data.GhUser
import com.lucaslu.tickhub.data.GhWorkflowList
import com.lucaslu.tickhub.data.RepoItem
import com.lucaslu.tickhub.data.TokenResponse
import com.lucaslu.tickhub.data.WorkflowItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int? = null) : ApiResult<Nothing>()
}

class GitHubApi {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val clientId: String get() = BuildConfig.GITHUB_CLIENT_ID
    private val apiBase = "https://api.github.com"

    fun isClientConfigured(): Boolean = clientId.isNotBlank()

    // ---- Device Flow ----

    suspend fun requestDeviceCode(): ApiResult<DeviceCodeResponse> = withContext(Dispatchers.IO) {
        if (!isClientConfigured()) {
            return@withContext ApiResult.Error("GitHub Client ID 未配置，请先在 OAuth App 注册后重新构建")
        }
        val body = buildJsonObject {
            put("client_id", clientId)
            put("scope", "repo")
        }.toString()
        postForm("https://github.com/login/device/code", body)?.let { raw ->
            parse<DeviceCodeResponse>(raw).let { result ->
                when (result) {
                    is ApiResult.Success -> {
                        val d = result.data
                        if (!d.error.isNullOrBlank()) {
                            ApiResult.Error(d.errorDescription ?: d.error)
                        } else ApiResult.Success(d)
                    }
                    else -> result
                }
            }
        } ?: ApiResult.Error("请求设备码失败：网络不可达")
    }

    suspend fun pollToken(deviceCode: String): ApiResult<TokenPoll> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("client_id", clientId)
            put("device_code", deviceCode)
            put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
        }.toString()
        val raw = postForm("https://github.com/login/oauth/access_token", body)
            ?: return@withContext ApiResult.Error("轮询 token 失败：网络不可达")
        when (val parsed = parse<TokenResponse>(raw)) {
            is ApiResult.Error -> parsed
            is ApiResult.Success -> {
                val t = parsed.data
                when (t.error) {
                    null -> if (!t.accessToken.isNullOrBlank()) {
                        ApiResult.Success(TokenPoll.Success(t.accessToken))
                    } else ApiResult.Error("响应缺少 access_token")
                    "authorization_pending" -> ApiResult.Success(TokenPoll.Pending)
                    "slow_down" -> ApiResult.Success(TokenPoll.SlowDown)
                    "expired" -> ApiResult.Success(TokenPoll.Expired)
                    "access_denied" -> ApiResult.Success(TokenPoll.Denied)
                    else -> ApiResult.Error(t.errorDescription ?: t.error)
                }
            }
        }
    }

    // ---- Authenticated endpoints ----

    suspend fun getCurrentUser(token: String): ApiResult<GhUser> = withContext(Dispatchers.IO) {
        val req = authorized(token, "$apiBase/user").build()
        executeJson(req)
    }

    suspend fun listOwnRepos(token: String): ApiResult<List<RepoItem>> = withContext(Dispatchers.IO) {
        val all = mutableListOf<GhRepo>()
        var page = 1
        while (true) {
            val url = "$apiBase/user/repos?affiliation=owner&per_page=100&page=$page&sort=updated"
            val req = authorized(token, url).build()
            when (val batch = executeJson<List<GhRepo>>(req)) {
                is ApiResult.Error -> return@withContext batch
                is ApiResult.Success -> {
                    all.addAll(batch.data)
                    if (batch.data.size < 100) break
                    page++
                    if (page > 20) break // safety cap: 2000 repos
                }
            }
        }
        ApiResult.Success(all.map { it.toRepoItem() })
    }

    suspend fun listWorkflows(
        token: String,
        owner: String,
        repo: String
    ): ApiResult<List<WorkflowItem>> = withContext(Dispatchers.IO) {
        val url = "$apiBase/repos/$owner/$repo/actions/workflows?per_page=100"
        val req = authorized(token, url).build()
        when (val result = executeJson<GhWorkflowList>(req)) {
            is ApiResult.Error -> result
            is ApiResult.Success -> {
                ApiResult.Success(
                    result.data.workflows.map { wf ->
                        WorkflowItem(
                            id = wf.id,
                            repoId = 0L, // filled by repository layer from matching repo
                            name = wf.name,
                            path = wf.path,
                            state = wf.state
                        )
                    }
                )
            }
        }
    }

    /** Trigger a workflow via workflow_dispatch. Uses numeric workflow id to avoid URL-encoding
     * issues with non-ASCII file names (e.g. 签到.yml). */
    suspend fun dispatchWorkflow(
        token: String,
        owner: String,
        repo: String,
        workflowId: Long,
        ref: String = "main"
    ): ApiResult<Int> = withContext(Dispatchers.IO) {
        val url = "$apiBase/repos/$owner/$repo/actions/workflows/$workflowId/dispatches"
        val body = buildJsonObject { put("ref", ref) }.toString()
            .toRequestBody("application/json".toMediaType())
        val req = authorized(token, url).post(body).build()
        try {
            client.newCall(req).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    // GitHub returns 204 (older) or 200 with run info (newer)
                    ApiResult.Success(response.code)
                } else {
                    ApiResult.Error(parseMessage(raw) ?: "HTTP ${response.code}", response.code)
                }
            }
        } catch (e: IOException) {
            ApiResult.Error("网络错误：${e.message}")
        }
    }

    // ---- Helpers ----

    private fun authorized(token: String, url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "TickHub-Android")

    private fun postForm(url: String, jsonBody: String): String? {
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "application/json")
            .header("User-Agent", "TickHub-Android")
            .build()
        return try {
            client.newCall(req).execute().use { response ->
                response.body?.string()
            }
        } catch (e: IOException) {
            null
        }
    }

    private inline fun <reified T> executeJson(req: Request): ApiResult<T> {
        return try {
            client.newCall(req).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    ApiResult.Error(parseMessage(raw) ?: "HTTP ${response.code}", response.code)
                } else {
                    parse<T>(raw)
                }
            }
        } catch (e: IOException) {
            ApiResult.Error("网络错误：${e.message}")
        }
    }

    private inline fun <reified T> parse(raw: String): ApiResult<T> {
        return try {
            ApiResult.Success(json.decodeFromString<T>(raw))
        } catch (e: SerializationException) {
            ApiResult.Error("解析响应失败：${e.message}")
        } catch (e: IllegalArgumentException) {
            ApiResult.Error("解析响应失败：${e.message}")
        }
    }

    private fun parseMessage(raw: String): String? = try {
        json.decodeFromString<ErrorBody>(raw).message
    } catch (e: Exception) {
        null
    }

    private fun GhRepo.toRepoItem() = RepoItem(
        id = id,
        owner = owner.login,
        name = name,
        fullName = fullName,
        private = private,
        defaultBranch = defaultBranch,
        updatedAt = updatedAt
    )
}

sealed class TokenPoll {
    data class Success(val token: String) : TokenPoll()
    data object Pending : TokenPoll()
    data object SlowDown : TokenPoll()
    data object Expired : TokenPoll()
    data object Denied : TokenPoll()
}

@kotlinx.serialization.Serializable
private data class ErrorBody(val message: String? = null)
