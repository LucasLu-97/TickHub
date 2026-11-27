package com.lucaslu.tickhub.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the OAuth access token encrypted at rest using Android Keystore-backed
 * EncryptedSharedPreferences. Never log or expose the token.
 */
class TokenStore(context: Context) {

    // If Keystore is unavailable (device migration / corrupted keys), fall back to
    // a transient in-memory store: never persist a token in plaintext, never crash.
    // The user is simply asked to sign in again.
    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "tickhub_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        android.util.Log.e("TokenStore", "Keystore unavailable, using transient store", it)
        TransientPrefs()
    }

    private class TransientPrefs : SharedPreferences {
        private val map = java.util.concurrent.ConcurrentHashMap<String, String?>()
        override fun getAll(): MutableMap<String, *> = HashMap<String, Any?>(map)
        override fun getString(key: String?, defValue: String?): String? = map[key ?: ""] ?: defValue
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = map.containsKey(key ?: "")
        override fun edit(): SharedPreferences.Editor = TransientEditor(this)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class TransientEditor(private val prefs: TransientPrefs) : SharedPreferences.Editor {
            private val pending = HashMap<String, String?>()
            override fun putString(key: String?, value: String?) = apply { pending[key ?: ""] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = apply { pending.remove(key ?: "") }
            override fun clear() = apply { pending.clear() }
            override fun commit(): Boolean { commitAll(); return true }
            override fun apply() { commitAll() }
            private fun commitAll() {
                // map is private to TransientPrefs; expose via a helper
                pending.forEach { (k, v) -> prefs.set(k, v) }
                pending.clear()
            }
        }
        fun set(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    /** Synchronous clear — guarantees the token is gone before logout returns. */
    fun clearSync() {
        prefs.edit().remove(KEY_TOKEN).commit()
    }

    companion object {
        private const val KEY_TOKEN = "github_access_token"
    }
}
