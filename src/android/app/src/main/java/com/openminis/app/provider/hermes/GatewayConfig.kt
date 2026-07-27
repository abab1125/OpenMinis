package com.openminis.app.provider.hermes

/**
 * Hermes gateway connection config. OpenMinis only uses loopback-token mode
 * (the gateway is reached through a self-hosted reverse proxy (e.g. nginx on a VPS) -> SSH tunnel
 * -> the local Hermes daemon on :8642), so we keep just [baseUrl] + [token] and drop
 * the gated (username/password + WS ticket) flow from the upstream client.
 *
 * `X-Hermes-Session-Token` is sent as the auth header on REST, and `?token=`
 * is appended to the WebSocket URL.
 */
data class GatewayConfig(
    val baseUrl: String,
    val token: String = "",
) {
    /** Base WS endpoint (no auth query). The gateway serves WS at `/api/ws`. */
    val wsBase: String
        get() {
            val ws = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
            return "${ws.trimEnd('/')}/api/ws"
        }

    /** Full WS URL with the session token as a query param. */
    val wsUrl: String get() = if (token.isBlank()) wsBase else "$wsBase?token=$token"
}
