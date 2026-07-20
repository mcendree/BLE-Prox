package com.example.pixelproximity

import android.content.Context

/**
 * Persists the Wiliot owner ID + API key the user enters at runtime.
 *
 * NOTE: uses plain SharedPreferences for simplicity. The API key is stored in
 * app-private storage (not world-readable), but if you want at-rest encryption,
 * swap this for androidx.security:security-crypto EncryptedSharedPreferences.
 */
class CredentialStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("wiliot_creds", Context.MODE_PRIVATE)

    var ownerId: String
        get() = prefs.getString(KEY_OWNER, "") ?: ""
        set(value) { prefs.edit().putString(KEY_OWNER, value).apply() }

    var apiKey: String
        get() = prefs.getString(KEY_API, "") ?: ""
        set(value) { prefs.edit().putString(KEY_API, value).apply() }

    val hasCredentials: Boolean
        get() = ownerId.isNotBlank() && apiKey.isNotBlank()

    fun save(owner: String, key: String) {
        prefs.edit()
            .putString(KEY_OWNER, owner.trim())
            .putString(KEY_API, key.trim())
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_OWNER = "owner_id"
        private const val KEY_API = "api_key"
    }
}
