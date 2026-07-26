package com.openminis.app.provider.hermes

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "HermesRestApi"

class HermesApiException(val code: Int, message: String) : Exception(message)

/**
 * REST client for the Hermes gateway. Trimmed from upstream to just the
 * endpoints OpenMinis's transparent passthrough needs: gateway status,
 * session list/history, profiles. Messaging (prompt.submit) goes over the
 * WebSocket RPC stream, not REST.
 */
class HermesRestApi(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val configProvider: () -> GatewayConfig?,
) {
    private fun config(): GatewayConfig =
        configProvider() ?: throw HermesApiException(0, "no gateway configured")

    private fun builder(path: String): Request.Builder {
        val cfg = config()
        // Trim trailing slashes so "http://host:9119/" doesn't produce "//api/...".
        val b = Request.Builder().url("${cfg.baseUrl.trimEnd('/')}$path")
        if (cfg.token.isNotBlank()) b.header("X-Hermes-Session-Token", cfg.token)
        return b
    }

    private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        Log.d(TAG, "GET $path")
        okHttp.newCall(builder(path).get().build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "GET $path <- ${resp.code} ${body.take(200)}")
                throw HermesApiException(resp.code, body.ifBlank { "HTTP ${resp.code}" })
            }
            json.decodeFromString<T>(body)
        }
    }

    /** Connectivity probe with explicit creds (no configProvider read). */
    suspend fun statusFor(baseUrl: String, token: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val rb = Request.Builder().url("${baseUrl.trimEnd('/')}/api/status").get()
            if (token.isNotBlank()) rb.header("X-Hermes-Session-Token", token)
            okHttp.newCall(rb.build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun status(): Boolean {
        val cfg = configProvider() ?: return false
        return statusFor(cfg.baseUrl, cfg.token)
    }

    suspend fun gatewayStatus(): GatewayStatusDto = get("/api/status")

    suspend fun sessions(limit: Int, offset: Int, profile: String? = null): List<SessionDto> =
        get<SessionListDto>(
            "/api/sessions?limit=$limit&offset=$offset&order=recent${profileParam(profile)}",
        ).sessions

    suspend fun messages(sessionId: String, profile: String? = null): List<MessageDto> =
        get<MessagesDto>("/api/sessions/$sessionId/messages${profileParam(profile, first = true)}").messages

    suspend fun profiles(): List<ProfileDto> = get<ProfilesDto>("/api/profiles").profiles

    suspend fun activeProfile(): String? =
        get<ActiveProfileDto>("/api/profiles/active").let { it.current ?: it.active }

    /** "&profile=x" (or "?profile=x" when [first]) - empty when profile is null/blank. */
    private fun profileParam(profile: String?, first: Boolean = false): String {
        if (profile.isNullOrBlank()) return ""
        val sep = if (first) "?" else "&"
        return "${sep}profile=${java.net.URLEncoder.encode(profile, "UTF-8")}"
    }
}
