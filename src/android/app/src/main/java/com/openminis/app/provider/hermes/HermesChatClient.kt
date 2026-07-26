package com.openminis.app.provider.hermes

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Thin RPC facade over [HermesGatewayClient] for the handful of methods
 * OpenMinis's transparent Hermes passthrough needs. Renamed from upstream's
 * `ChatRepository` to avoid clashing with OpenMinis's local Room-backed
 * `ChatRepository`. Messaging is fire-and-forget: [submit] sends
 * `prompt.submit` and returns; the assistant's streamed reply arrives as
 * `message.*` events on [events], which `ChatViewModel.runHermesTurn` collects.
 */
class HermesChatClient(private val client: HermesGatewayClient) {
    val events: SharedFlow<ServerEvent> get() = client.events
    val connectionState: StateFlow<ConnectionState> get() = client.connectionState

    fun connect() = client.connect()
    fun disconnect() = client.close()
    fun reconnect() = client.reconnectNow()

    /**
     * Create a new session. [profile] MUST be the active profile: the gateway
     * binds the session to a per-profile state.db at creation time. Returns
     * the new session id.
     */
    suspend fun createSession(profile: String? = null): String {
        val result = client.call("session.create", buildJsonObject {
            if (!profile.isNullOrBlank()) put("profile", profile)
        })
        return result.jsonObject["session_id"]?.jsonPrimitive?.content
            ?: error("session.create returned no id")
    }

    /**
     * Resume a session. The gateway accepts the stored (REST) id but returns
     * a NEW short live handle in `session_id` - callers MUST use that handle
     * for subsequent submit/interrupt and for filtering streamed events.
     * Returns null if not present.
     */
    suspend fun resume(sessionId: String, profile: String? = null): String? {
        val result = client.call("session.resume", buildJsonObject {
            put("session_id", sessionId)
            if (!profile.isNullOrBlank()) put("profile", profile)
        })
        return result.jsonObject["session_id"]?.jsonPrimitive?.content
    }

    /** Fire-and-forget: send a user prompt. The reply streams as events. */
    suspend fun submit(sessionId: String, text: String) {
        client.call("prompt.submit", buildJsonObject {
            put("session_id", sessionId)
            put("text", text)
        })
    }

    suspend fun interrupt(sessionId: String) {
        client.call("session.interrupt", buildJsonObject { put("session_id", sessionId) })
    }
}
