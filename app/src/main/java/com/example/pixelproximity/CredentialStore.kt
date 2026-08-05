package com.example.pixelproximity

import android.content.Context
import android.provider.Settings
import java.util.Locale

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

    // The SDK uses the uppercased Android ID as the gateway id. We default to the
    // same so the gateway we register matches what the owner would expect.
    private val defaultGw: String = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.uppercase(Locale.ROOT)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "ANDROID-PROXIMITY"

    var ownerId: String
        get() = prefs.getString(KEY_OWNER, "") ?: ""
        set(value) { prefs.edit().putString(KEY_OWNER, value).apply() }

    var apiKey: String
        get() = prefs.getString(KEY_API, "") ?: ""
        set(value) { prefs.edit().putString(KEY_API, value).apply() }

    var gatewayId: String
        get() = prefs.getString(KEY_GW, defaultGw) ?: defaultGw
        set(value) { prefs.edit().putString(KEY_GW, value).apply() }

    val hasCredentials: Boolean
        get() = ownerId.isNotBlank() && apiKey.isNotBlank()

    fun save(owner: String, key: String, gateway: String) {
        prefs.edit()
            .putString(KEY_OWNER, owner.trim())
            .putString(KEY_API, key.trim())
            .putString(KEY_GW, gateway.trim().ifBlank { defaultGw })
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_OWNER = "owner_id"
        private const val KEY_API = "api_key"
        private const val KEY_GW = "gateway_id"
    }
}
