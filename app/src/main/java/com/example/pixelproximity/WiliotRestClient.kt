package com.example.pixelproximity

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to the Wiliot cloud REST API directly (no SDK gateway pipeline):
 *  1) exchange the API key for a bearer token  (POST /v1/auth/token/api)
 *  2) resolve a raw pixel payload into a stable Pixel ID (POST /v1/owner/{ownerId}/resolve)
 *
 * This is exactly what the SDK does under the hood, so it uses the SAME API key
 * (no new key/category needed). Only the encrypted payload is sent to the cloud —
 * RSSI is never uploaded.
 */
class WiliotRestClient(
    private val apiBase: String,
    private val ownerId: String,
    private val apiKey: String,
    private val gatewayId: String
) {
    private val http = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var token: String? = null        // API-key token (for register)
    @Volatile private var gatewayToken: String? = null // token returned by registration (for resolve)
    @Volatile private var loggedError = false
    @Volatile private var registered = false

    private val jsonType = "application/json".toMediaType()

    /** POST /v1/auth/token/api  with header  Authorization: <apiKey>  (no "Bearer"). */
    @Synchronized
    private fun authenticate(): String {
        val req = Request.Builder()
            .url("$apiBase/v1/auth/token/api")
            .post(ByteArray(0).toRequestBody(null))
            .header("Authorization", apiKey)
            .header("Content-Type", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "auth failed: HTTP ${resp.code} $body")
                throw RuntimeException("Auth failed: HTTP ${resp.code}")
            }
            val t = JSONObject(body).getString("access_token")
            token = t
            Log.i(TAG, "auth ok, token acquired")
            return t
        }
    }

    private fun currentToken(): String = token ?: authenticate()

    /**
     * Register this gateway with the owner (one-time). The owner-scoped /resolve
     * endpoint rejects packets from a gateway that isn't associated with the
     * owner (403 ACCESS_DENIED) — the SDK does this at startup and we must too.
     *   POST /v1/owner/{ownerId}/gateway/{gatewayId}/mobile   {"gatewayType":"android"}
     */
    @Synchronized
    private fun ensureRegistered(tk: String) {
        if (registered) return
        try {
            val req = Request.Builder()
                .url("$apiBase/v1/owner/$ownerId/gateway/$gatewayId/mobile")
                .post("{\"gatewayType\":\"android\"}".toRequestBody(jsonType))
                // Official Wiliot Python client sends the RAW token (no "Bearer").
                .header("Authorization", tk)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build()
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (r.isSuccessful) {
                    // Registration returns a gateway-scoped token — resolve needs THIS token.
                    gatewayToken = runCatching {
                        JSONObject(body).getJSONObject("data").getString("access_token")
                    }.getOrNull()
                    registered = true
                    Log.i(TAG, "gateway register HTTP ${r.code} id=$gatewayId gatewayToken=${gatewayToken != null}")
                } else {
                    Log.w(TAG, "gateway register HTTP ${r.code} id=$gatewayId body=${body.take(300)}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "gateway register error: ${t.message}")
        }
    }

    /**
     * Resolve one raw service-data payload (the hex captured from the 0xFDAF
     * service data, e.g. "CB0004..."). Returns the external Pixel ID, or null
     * if the cloud couldn't resolve it.
     */
    fun resolve(rawServiceDataHex: String): String? {
        val payload = "affd" + rawServiceDataHex.lowercase()
        val tagId = if (payload.length >= 30) payload.substring(18, 30) else null
        val now = System.currentTimeMillis()

        val bodyJson = JSONObject().apply {
            put("gatewayId", gatewayId)
            put("gatewayType", "android")
            put("timestamp", now)
            put("packets", JSONArray().put(JSONObject().apply {
                if (tagId != null) put("tagId", tagId)
                put("payload", payload)
                put("sequenceId", 0)
                put("timestamp", now)
            }))
        }

        ensureRegistered(currentToken())        // best-effort gateway association (raw token)
        // Per the official Wiliot Python client (api_client.py), owner-scoped calls
        // are authorized with the API-KEY access token (not the gateway token), sent
        // as the RAW token with no "Bearer" prefix.
        var resp = doResolve(bodyJson, currentToken())
        if (resp.code == 401) {                 // token expired -> re-auth once
            resp.close()
            token = null; registered = false
            resp = doResolve(bodyJson, currentToken())
        }
        resp.use {
            val body = it.body?.string().orEmpty()
            if (it.code == 400) return null     // explicitly unresolved
            if (!it.isSuccessful) {
                if (!loggedError) {
                    loggedError = true
                    Log.w(TAG, "resolve HTTP ${it.code} url=$apiBase/v1/owner/$ownerId/resolve body=${body.take(400)}")
                }
                return null
            }
            val data = JSONObject(body).optJSONArray("data") ?: return null
            if (data.length() == 0) return null
            val first = data.getJSONObject(0)
            return if (first.isNull("externalId")) null else first.getString("externalId")
        }
    }

    private fun doResolve(body: JSONObject, tk: String) =
        http.newCall(
            Request.Builder()
                .url("$apiBase/v1/owner/$ownerId/resolve")
                .post(body.toString().toRequestBody(jsonType))
                // RAW token, no "Bearer" — matches the official Wiliot Python client.
                .header("Authorization", tk)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build()
        ).execute()

    companion object {
        private const val TAG = "WiliotRest"
        const val GCP_WMT_PROD_API = "https://api.us-central1.wmt-prod.gcp.wiliot.cloud"
    }
}
