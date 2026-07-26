package com.openminis.app.provider.hermes

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow

private const val TAG = "HermesGatewayClient"

class GatewayRpcException(val code: Int, message: String) : Exception(message)

data class BackoffPolicy(
    val baseMs: Long = 500,
    val factor: Double = 2.0,
    val maxMs: Long = 10_000,
) {
    fun delayFor(attempt: Int): Long =
        min(maxMs, (baseMs * factor.pow(attempt)).toLong())
}

/**
 * Manages the WebSocket JSON-RPC stream to a Hermes gateway. Ported from
 * upstream `HermesGatewayClient` (loopback-token mode only - no gated WS
 * ticket flow). Reconnects with exponential backoff; `call()` blocks until
 * the matching RPC result arrives; `events` exposes the pushed event stream.
 *
 * OpenMinis drives this through [HermesClientHolder] (a process singleton),
 * and `ChatViewModel.runHermesTurn` collects `events` to render a turn.
 */
open class HermesGatewayClient(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val scope: CoroutineScope,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    // suspend so a future gated mode could fetch a WS ticket (HTTP) before
    // each connect; loopback mode returns the URL synchronously.
    private val wsUrlProvider: suspend () -> String,
) {
    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    @Volatile private var ws: WebSocket? = null
    @Volatile protected var manuallyClosed = false
    private val attempt = AtomicInteger(0)
    // Monotonic socket generation. Each openSocket() bumps it; a socket's
    // callbacks are ignored once a newer socket has opened, so an in-flight
    // backoff reopen can never race a manual reconnectNow() into two sockets.
    private val generation = AtomicInteger(0)

    // Readiness gate: awaited by call() before sending RPCs. Recreated
    // (uncompleted) on each openSocket(); completed when gateway.ready
    // arrives; completed exceptionally when the socket closes/fails.
    @Volatile private var readyGate: CompletableDeferred<Unit> = CompletableDeferred()

    private companion object {
        const val READY_TIMEOUT_MS = 15_000L
    }

    fun connect() {
        // Idempotent: multiple owners may all call connect() on this shared
        // singleton. If a socket is already open or a connect is in flight,
        // this is a no-op - otherwise a second openSocket() would leak a
        // duplicate live WebSocket. reconnectNow() bypasses this guard.
        val cur = _state.value
        if (cur is ConnectionState.Connecting || cur is ConnectionState.Connected ||
            cur is ConnectionState.Reconnecting
        ) {
            Log.d(TAG, "connect() no-op - already $cur")
            return
        }
        manuallyClosed = false
        openSocket()
    }

    protected fun openSocket() {
        val gen = generation.incrementAndGet()
        Log.d(TAG, "opening socket (gen=$gen)")
        readyGate = CompletableDeferred()
        _state.value = ConnectionState.Connecting
        scope.launch {
            val url = try {
                wsUrlProvider()
            } catch (e: Exception) {
                Log.w(TAG, "ws url failed (gen=$gen): ${e.message}")
                onSocketClosed(gen, e.message ?: "ws url failed")
                return@launch
            }
            if (gen != generation.get() || manuallyClosed) return@launch
            val request = Request.Builder().url(url).build()
            ws = okHttp.newWebSocket(request, makeListener(gen))
        }
    }

    /** Force an immediate reconnect, bypassing any pending backoff wait. */
    fun reconnectNow() {
        manuallyClosed = false
        attempt.set(0)
        val old = ws
        openSocket()
        old?.cancel()
    }

    private fun makeListener(gen: Int) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Do NOT set state to Connected here. Wait for gateway.ready.
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation.get()) return // superseded socket - drop late frames
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                when (val msg = parseInbound(json, line)) {
                    is RpcResult -> pending.remove(msg.id)?.complete(msg.result)
                    is RpcErrorReply -> {
                        Log.d(TAG, "rpc#${msg.id} <- error ${msg.error.code}: ${msg.error.message}")
                        pending.remove(msg.id)
                            ?.completeExceptionally(GatewayRpcException(msg.error.code, msg.error.message))
                    }
                    is RpcEvent -> {
                        if (msg.event.type == "gateway.ready") {
                            attempt.set(0)
                            _state.value = ConnectionState.Connected
                            readyGate.complete(Unit)
                        }
                        // Skip logging the high-frequency deltas so the logcat
                        // trail stays readable around a failure.
                        if (msg.event.type != "message.delta" && msg.event.type != "reasoning.delta") {
                            Log.d(TAG, "event ${msg.event.type} session=${msg.event.sessionId ?: "-"}")
                        }
                        _events.tryEmit(msg.event)
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onSocketClosed(gen, t.message ?: "connection failed")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onSocketClosed(gen, reason.ifBlank { "closed" })
        }
    }

    protected open fun onSocketClosed(gen: Int, reason: String) {
        if (gen != generation.get()) return
        Log.d(TAG, "socket closed (gen=$gen): $reason")
        readyGate.completeExceptionally(GatewayRpcException(0, reason))
        failAllPending(reason)
        if (manuallyClosed) {
            _state.value = ConnectionState.Disconnected
            return
        }
        _state.value = ConnectionState.Reconnecting
        val delayMs = backoff.delayFor(attempt.getAndIncrement())
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (!manuallyClosed && gen == generation.get()) openSocket()
        }
    }

    private fun failAllPending(reason: String) {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.completeExceptionally(GatewayRpcException(0, reason))
        }
    }

    /** Send an RPC and await its result. Throws on timeout / socket failure. */
    suspend fun call(method: String, params: JsonObject): JsonElement {
        try {
            withTimeout(READY_TIMEOUT_MS) { readyGate.await() }
        } catch (e: TimeoutCancellationException) {
            throw GatewayRpcException(0, "gateway readiness timeout")
        }
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        Log.d(TAG, "rpc#$id -> $method")
        val sent = ws?.send(RpcRequest(id, method, params).encode(json)) ?: false
        if (!sent) {
            pending.remove(id)
            Log.d(TAG, "rpc#$id $method failed: not connected")
            throw GatewayRpcException(0, "not connected")
        }
        return deferred.await()
    }

    fun close() {
        manuallyClosed = true
        readyGate.completeExceptionally(GatewayRpcException(0, "client closing"))
        failAllPending("client closing")
        ws?.close(1000, "client closing")
        ws = null
        _state.value = ConnectionState.Disconnected
    }
}
