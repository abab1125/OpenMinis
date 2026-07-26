package com.openminis.app.provider.hermes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A gateway-pushed event on the WS stream. Ported from upstream; the gateway
 * wraps each event as `{method:"event", params:{type, payload, session_id}}`.
 */
data class ServerEvent(
    val type: String,
    val sessionId: String?,
    val payload: JsonObject,
) {
    companion object {
        fun from(params: JsonObject): ServerEvent {
            val type = params["type"]?.jsonPrimitive?.content ?: "unknown"
            val payload = (params["payload"] as? JsonObject) ?: JsonObject(emptyMap())
            val sessionId = payload["session_id"]?.jsonPrimitive?.content
                ?: params["session_id"]?.jsonPrimitive?.content
            return ServerEvent(type, sessionId, payload)
        }
    }
}

// Defensive payload readers - structured JSON (e.g. tool results) must never
// throw here, or the uncaught exception escapes the event collector and kills
// the stream mid-turn. Mirror upstream's str/bool/strList helpers.
internal fun ServerEvent.str(key: String): String? = when (val el = payload[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> el.content
    else -> el.toString()
}

internal fun JsonObject.objOrEmpty(key: String): JsonObject =
    (this[key] as? JsonObject) ?: JsonObject(emptyMap())

internal fun ServerEvent.bool(key: String): Boolean? =
    (payload[key] as? JsonPrimitive)?.booleanOrNull

internal fun ServerEvent.strList(key: String): List<String> =
    (payload[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
