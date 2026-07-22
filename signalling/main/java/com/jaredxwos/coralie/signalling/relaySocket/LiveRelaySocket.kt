package com.jaredxwos.coralie.signalling.relaySocket

import com.jaredxwos.coralie.signalling.nostrMessage.ServerToClientMessage
import com.jaredxwos.coralie.signalling.backoff.BackoffStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class LiveRelaySocket(
    private val client: OkHttpClient,
    private val url: String,
    private val scope: CoroutineScope,
    private val backoffStrategy: BackoffStrategy, // (failureCount: Int) -> kotlin.time.Duration?
) : RelaySocket {

    private val request = Request.Builder().url(url).build()
    private val lock = Any()
    private var webSocket: WebSocket? = null
    private var failureCount = 0
    private var closedByCaller = false
    private var reconnectJob: Job? = null
    private var _isOpen = false

    override val isOpen: Boolean
        get() = synchronized(lock) { _isOpen }
    override val frames = Channel<Result<ServerToClientMessage>>(Channel.UNLIMITED)

    // Declared before init{} runs -- Kotlin initializes properties in
    // declaration order, so no lateinit workaround is needed here.
    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                _isOpen = true
                failureCount = 0
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // UNLIMITED capacity means this should never fail except after
            // the channel is closed (post give-up/close) -- nothing to
            // recover from at that point, so dropping silently is fine.
            frames.trySend(ServerToClientMessage.parse(text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Ack the close per OkHttp's recommended pattern so the
            // handshake completes cleanly instead of relying on onClosed.
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleDisconnect()
        }
    }

    init {
        openSocket()
    }

    private fun openSocket() {
        synchronized(lock) {
            webSocket = client.newWebSocket(request, listener)
        }
    }

    private fun handleDisconnect() {
        synchronized(lock) {
            _isOpen = false
            if (closedByCaller) {
                frames.close()
                return
            }
            failureCount += 1
            val retryDelay = backoffStrategy(failureCount)
            if (retryDelay == null) {
                frames.close(
                    IllegalStateException("relay $url: giving up after $failureCount failures")
                )
                return
            }
            reconnectJob = scope.launch {
                delay(retryDelay) // kotlin.time.Duration overload — no java.time conversion needed
                openSocket()
            }
        }
    }

    override fun send(text: String): Result<Unit> {
        val ws = synchronized(lock) { if (_isOpen) webSocket else null }
            ?: return Result.failure(IllegalStateException("relay $url: not connected"))
        // isOpen is a fast-path hint, not the source of truth -- OkHttp's
        // own send() result is authoritative for whether the frame was
        // actually accepted.
        return if (ws.send(text)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("relay $url: send() rejected by socket"))
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closedByCaller) return
            closedByCaller = true
            reconnectJob?.cancel()
            webSocket?.close(1000, "client closing")
        }
    }
}