package com.openminis.app.provider.hermes

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val TAG = "HermesClientHolder"
private const val PREFS_FILE = "hermes_gateway_prefs"
private const val KEY_BASE_URL = "base_url"
private const val KEY_TOKEN = "token"
private const val DEFAULT_BASE_URL = "http://localhost:8642"

/**
 * Process-wide singleton holding the Hermes gateway connection. OpenMinis has
 * no DI framework (no Hilt), so this object owns the OkHttpClient / Json /
 * client instances lazily and re-reads the config from encrypted prefs on
 * each access (so a settings change takes effect on the next connect without
 * rebuilding the holder).
 *
 * Config storage mirrors the upstream EncryptedCredentialStore approach:
 * base_url + token in EncryptedSharedPreferences (AES256-GCM).
 *
 * `ChatViewModel.runHermesTurn` drives a turn via [chat]; the events flow is
 * exposed so the ViewModel can render the streamed reply.
 */
object HermesClientHolder {

    private var appContext: Context? = null

    /** Initialise once from Application/Activity. Idempotent. */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun ctx(): Context =
        appContext ?: error("HermesClientHolder.init(context) must be called first")

    private val json by lazy {
        Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    /**
     * One shared OkHttpClient for REST + WebSocket. Mirrors upstream tuning:
     * 20s WebSocket ping keepalive, no read timeout (the agent stream is
     * long-lived), 15s connect timeout.
     */
    private val okHttp by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /** Current gateway config (re-read from prefs each access). */
    val config: GatewayConfig
        get() = loadConfig() ?: GatewayConfig(DEFAULT_BASE_URL, "")

    val restApi: HermesRestApi by lazy { HermesRestApi(okHttp, json) { config } }

    val gatewayClient: HermesGatewayClient by lazy {
        HermesGatewayClient(okHttp, json, scope, wsUrlProvider = { config.wsUrl })
    }

    val chat: HermesChatClient by lazy { HermesChatClient(gatewayClient) }

    /** Persist a new config. Takes effect on the next connect/reconnect. */
    fun saveConfig(baseUrl: String, token: String) {
        prefs().edit()
            .putString(KEY_BASE_URL, baseUrl.trim().ifBlank { DEFAULT_BASE_URL })
            .putString(KEY_TOKEN, token)
            .apply()
    }

    /** True when a non-blank base URL + token are configured. */
    fun isConfigured(): Boolean {
        val cfg = loadConfig() ?: return false
        return cfg.baseUrl.isNotBlank() && cfg.token.isNotBlank()
    }

    private fun loadConfig(): GatewayConfig? {
        return try {
            val p = prefs()
            val url = p.getString(KEY_BASE_URL, null) ?: return null
            val token = p.getString(KEY_TOKEN, "") ?: ""
            GatewayConfig(url, token)
        } catch (e: Exception) {
            Log.w(TAG, "loadConfig failed: ${e.message}")
            null
        }
    }

    /**
     * EncryptedSharedPreferences with the same keyset-recovery resilience as
     * upstream: if the keyset is corrupt (crash-loop guard), drop the prefs
     * file and recreate so the app never bricks on a bad keystore state.
     */
    private fun prefs() = try {
        val mk = MasterKey.Builder(ctx())
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx(),
            PREFS_FILE,
            mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.w(TAG, "encrypted prefs open failed, resetting: ${e.message}")
        ctx().deleteSharedPreferences(PREFS_FILE)
        val mk = MasterKey.Builder(ctx())
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx(),
            PREFS_FILE,
            mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
