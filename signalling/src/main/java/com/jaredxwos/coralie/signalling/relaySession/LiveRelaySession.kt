package com.jaredxwos.coralie.signalling.relaySession

import android.util.Log
import com.jaredxwos.coralie.signalling.eventSink.EventSink
import com.jaredxwos.coralie.signalling.nostrMessage.ClientToServerMessage
import com.jaredxwos.coralie.signalling.nostrMessage.ServerToClientMessage
import com.jaredxwos.coralie.signalling.relaySocket.RelaySocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LiveRelaySession(
    private val relay: RelaySocket,
    private val sink: EventSink,
    private val heartbeatInterval: Duration = 60.seconds,
    private val reconnectPollInterval: Duration = 500.milliseconds,
) : RelaySession {

    private data class Subscription(
        val request: ClientToServerMessage.Req,
        var status: RelaySession.SubStatus,
        var lastEventAt: Long? = null,
    )

    private val lock = Mutex()
    private val subs = mutableMapOf<String, Subscription>()
    private val _subStates = MutableStateFlow<Map<String, RelaySession.SubStatus>>(emptyMap())
    override val subStates: StateFlow<Map<String, RelaySession.SubStatus>> = _subStates.asStateFlow()

    override fun start(scope: CoroutineScope) {
        scope.launch { consumeFrames() }
        scope.launch { heartbeatLoop() }
        scope.launch { watchReconnects() }
    }

    override suspend fun subscribe(request: ClientToServerMessage.Req): Result<Unit> {
        lock.withLock { subs[request.subId] = Subscription(request, RelaySession.SubStatus.PENDING) }
        emitStates()
        Log.d("RelaySession","REQ: ${request.toWireText()}")
        val result = relay.send(request.toWireText())
        Log.d("RelaySession", "subscribe send result: $result")
        return result
    }
    override suspend fun publish(event: ClientToServerMessage.Event): Result<Unit> =
        relay.send(event.toWireText())

    private suspend fun consumeFrames() {
        for (result in relay.frames) {
            Log.d("RelaySession", "frame received: $result")
            result.fold(
                onSuccess = { handleFrame(it) },
                onFailure = { Log.d("RelaySession", "frame parse failed: ${it.message}") },
            )
        }
        // Channel only closes when RelaySocket has given up retrying entirely.
        lock.withLock { subs.values.forEach { it.status = RelaySession.SubStatus.STOPPED } }
        emitStates()
    }

    private suspend fun handleFrame(frame: ServerToClientMessage) {
        when (frame) {
            is ServerToClientMessage.Event -> {
                lock.withLock {
                    subs[frame.subscriptionId]?.let { sub ->
                        val at = frame.event.createdAt
                        if (sub.lastEventAt == null || at > sub.lastEventAt!!) {
                            sub.lastEventAt = at
                        }
                    }
                }
                sink.offer(frame.event) // sink owns id-dedup; this class never dedups
            }
            is ServerToClientMessage.Eose -> {
                lock.withLock { subs[frame.subscriptionId]?.status = RelaySession.SubStatus.LIVE }
                emitStates()
            }
            is ServerToClientMessage.Closed -> {
                lock.withLock { subs[frame.subscriptionId]?.status = RelaySession.SubStatus.STOPPED }
                emitStates()
            }
            is ServerToClientMessage.Ok -> Unit
            is ServerToClientMessage.Notice -> Unit
        }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(heartbeatInterval)
            reissueAll()
        }
    }

    private suspend fun reissueAll() {
        val toSend = lock.withLock { subs.values.toList() }
        for (sub in toSend) {
            val refreshed = sub.lastEventAt?.let { sub.request.copy(since = it) } ?: sub.request
            val result = relay.send(refreshed.toWireText())
            Log.d("RelaySession", "reissue send result: $result for ${refreshed.toWireText()}")
        }
    }

    private suspend fun watchReconnects() {
        var wasOpen = relay.isOpen
        while (true) {
            delay(reconnectPollInterval)
            val nowOpen = relay.isOpen
            if (nowOpen && !wasOpen) {
                lock.withLock { subs.values.forEach { it.status = RelaySession.SubStatus.PENDING } }
                emitStates()
                reissueAll()
            }
            wasOpen = nowOpen
        }
    }

    private suspend fun emitStates() {
        _subStates.value = lock.withLock { subs.mapValues { it.value.status } }
    }
}