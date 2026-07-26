package com.openminis.app.provider.hermes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Trimmed DTO set for OpenMinis's transparent Hermes passthrough. We only need
 * session create/resume, message history, and gateway status - the upstream
 * client's cron / analytics / messaging / projects DTOs are dropped (YAGNI).
 */

@Serializable data class GatewayStatusDto(
    val version: String? = null,
    @SerialName("gateway_running") val gatewayRunning: Boolean = false,
    @SerialName("gateway_state") val gatewayState: String? = null,
)

@Serializable data class SessionDto(
    // The gateway returns the session id under "id" (not "session_id").
    @SerialName("id") val sessionId: String,
    val title: String? = null,
    val model: String? = null,
    val provider: String? = null,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    val profile: String? = null,
    val archived: Boolean = false,
)
@Serializable data class SessionListDto(val sessions: List<SessionDto> = emptyList())

@Serializable data class MessageDto(
    val id: Int? = null,
    val role: String,
    val content: String? = null,
)
@Serializable data class MessagesDto(val messages: List<MessageDto> = emptyList())

@Serializable data class ProfileDto(
    val name: String,
    @SerialName("is_default") val isDefault: Boolean = false,
)
@Serializable data class ProfilesDto(val profiles: List<ProfileDto> = emptyList())

@Serializable data class ActiveProfileDto(val active: String? = null, val current: String? = null)
