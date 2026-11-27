package com.lucaslu.tickhub.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the OAuth access token encrypted at rest using Android Keystore-backed
 * EncryptedSharedPreferences. Never log or expose the token.
 *
 * If Keystore is unavailable (device migration / corrupted keys), degrades to a
 * transient in-memory token: never persists in plaintext, never crashes. The user
 * is simply asked to sign in again on next launch.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences? get() = sharedPrefs

    companion object {
        // Keystore/EncryptedSharedPreferences setup is expensive; RunTrackerWorker
        // constructs a TokenStore every 5s poll. Cache ONE instance per process.
        @Volatile
        private var cachedContext: Context? = null
        @Volatile
        private var sharedPrefs: SharedPreferences? = null
        private val prefsLock = Any()

        private fun prefsFor(context: Context): SharedPreferences? {
            cachedContext?.let { return sharedPrefs }
            return synchronized(prefsLock) {
                cachedContext?.let { return@synchronized sharedPrefs }
                val appCtx = context.applicationContext
                val result = runCatching {
                    val masterKey = MasterKey.Builder(appCtx)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    EncryptedSharedPreferences.create(
                        appCtx, "tickhub_secure_prefs", masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                }.getOrElse {
                    android.util.Log.e("TokenStore", "Keystore unavailable, token will be transient", it)
                    null
                }
                cachedContext = appCtx
                sharedPrefs = result
                result
            }
        }
    }

    init { prefsFor(context) }

    fun getToken(): String? =
        (prefs?.getString(KEY_TOKEN, null) ?: transientToken)?.takeIf { it.isNotBlank() }

    /** Returns true if the token was securely persisted; false means Keystore is
     *  unavailable and the token lives only in process memory (lost on restart). */
    fun saveToken(token: String): Boolean {
        return if (prefs != null) {
            prefs.edit().putString(KEY_TOKEN, token).apply()
            true
        } else {
            transientToken = token
            false
        }
    }

    fun clear() {
        prefs?.edit()?.remove(KEY_TOKEN)?.apply()
        transientToken = null
    }

    /** Synchronous clear — guarantees the token is gone before logout returns. */
    fun clearSync() {
        prefs?.edit()?.remove(KEY_TOKEN)?.commit()
        transientToken = null
    }

    companion object {
        private const val KEY_TOKEN = "github_access_token"
        // Keystore-unavailable fallback is PROCESS-WIDE, not per-instance:
        // AuthViewModel/RepoViewModel, DispatchEngine, RunTrackerWorker and BootReceiver each
        // construct their own TokenStore — an instance field lost the token for
        // all background paths. Lost on process death (never written to disk).
        @Volatile
        private var transientToken: String? = null
    }
}
