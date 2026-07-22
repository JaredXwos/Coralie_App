package com.jaredxwos.coralie.transport.context

import android.util.Log
import com.jaredxwos.coralie.transport.HandshakeEvent
import com.jaredxwos.coralie.transport.IceServerConfig
import com.jaredxwos.coralie.transport.LinkState
import com.jaredxwos.coralie.transport.SessionDescriptionData
import com.jaredxwos.coralie.transport.utils.copyToByteArray
import com.jaredxwos.coralie.transport.utils.createOfferSuspend
import com.jaredxwos.coralie.transport.utils.createAnswerSuspend
import com.jaredxwos.coralie.transport.utils.setLocalDescriptionSuspend
import com.jaredxwos.coralie.transport.utils.setRemoteDescriptionSuspend
import com.jaredxwos.coralie.transport.utils.toData
import com.jaredxwos.coralie.transport.utils.toWebRtc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


internal class LiveRtcContext(
    factory: PeerConnectionFactory,
    iceServers: List<IceServerConfig>,
    parentScope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val handshakeTimeout: Duration = 30.seconds,
    private val remoteOffer: SessionDescriptionData? = null   // non-null only when constructed via getAnswerer()
) : Initiator, Answerer {

    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + dispatcher
    )

    private val _state = MutableStateFlow<LinkState>(LinkState.New)
    override val state: StateFlow<LinkState> = _state

    private val _incomingBytes = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incomingBytes: SharedFlow<ByteArray> = _incomingBytes

    private val _events = Channel<HandshakeEvent>(Channel.UNLIMITED)
    override val events: ReceiveChannel<HandshakeEvent> = _events

    private var dataChannel: DataChannel? = null
    private val gatheringComplete = CompletableDeferred<Unit>()
    private val pcObserver = object : PeerConnection.Observer {
        override fun onDataChannel(dc: DataChannel) {
            Log.d("PC Observer", "onDataChannel: initial state=${dc.state()}")
            dc.registerObserver(dcObserver)
            scope.launch {
                dataChannel = dc
                if (dc.state() == DataChannel.State.OPEN) {
                    Log.d("PC Observer", "onDataChannel: was already OPEN — setting _state=Connected (was ${_state.value})")
                    _state.value = LinkState.Connected
                }
            }
        }
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
            Log.d("PC Observer", "onIceConnectionChange: $s")
            scope.launch {
                if (s == PeerConnection.IceConnectionState.FAILED) _state.value = LinkState.Failed
                _events.trySend(HandshakeEvent.IceStateChanged(s))
            }
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            Log.d("PC Observer", "onIceCandidate: ${candidate.sdp}")
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
            Log.d("PC Observer", "onIceGatheringChange: $s")
            if (s == PeerConnection.IceGatheringState.COMPLETE) {
                gatheringComplete.complete(Unit)
            }
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    }

    private val peerConnection: PeerConnection = factory.createPeerConnection(
        PeerConnection.RTCConfiguration(
            iceServers.map { cfg ->
                val builder = PeerConnection.IceServer.builder(cfg.urls)
                if (cfg.username != null && cfg.credential != null)
                    builder.setUsername(cfg.username).setPassword(cfg.credential)
                builder.createIceServer()
            }
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        },
        pcObserver
    ) ?: error("factory failed to create PeerConnection")

    private val dcObserver = object : DataChannel.Observer {
        override fun onStateChange() {
            val current = dataChannel?.state()
            Log.d("DC Observer", "DataChannel.onStateChange: raw=$current")
            scope.launch {
                when (current) {
                    DataChannel.State.OPEN -> {
                        Log.d("DC Observer", "onStateChange: setting _state=Connected (was ${_state.value})")
                        _state.value = LinkState.Connected
                    }
                    DataChannel.State.CLOSED -> {
                        Log.d("DC Observer", "onStateChange: setting _state=Closed (was ${_state.value})")
                        _state.value = LinkState.Closed
                    }
                    else -> {
                        // CONNECTING / CLOSING — no state transition of ours maps to these, just observe
                        Log.d("DC Observer", "onStateChange: no-op for raw=$current")
                    }
                }
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = buffer.data.copyToByteArray()
            Log.d("DC Observer", "DataChannel.onMessage: ${bytes.size} bytes")
            scope.launch {
                val delivered = _incomingBytes.tryEmit(bytes)
                if (!delivered) {
                    Log.w("DC Observer", "onMessage: incomingBytes emit failed to deliver (buffer full?)")
                }
            }
        }

        override fun onBufferedAmountChange(previousAmount: Long) {
            Log.d("DC Observer", "DataChannel.onBufferedAmountChange: previous=$previousAmount")
        }
    }

    // ---- plain method — not part of Initiator/Answerer, only called once by getInitiator() ----

    fun createDataChannel(): DataChannel =
        peerConnection.createDataChannel("mesh", DataChannel.Init()).also {
            dataChannel = it
            it.registerObserver(dcObserver)
        }

    // ---- Initiator ----

    override suspend fun createOffer(): SessionDescriptionData = withContext(dispatcher) {
        val offer = peerConnection.createOfferSuspend()
        peerConnection.setLocalDescriptionSuspend(offer)
        gatheringComplete.await()
        val fullOffer = peerConnection.localDescription!!.toData()
        _state.value = LinkState.AwaitingRemoteDescription(since = clock.now())
        fullOffer.also { _events.trySend(HandshakeEvent.OfferReady(it)) }
    }

    override suspend fun acceptAnswer(answer: SessionDescriptionData) = withContext(dispatcher) {
        peerConnection.setRemoteDescriptionSuspend(answer.toWebRtc())
        _state.value = LinkState.Connecting
    }

    override fun checkHandshakeTimeout(): Boolean {
        val s = _state.value
        return if (s is LinkState.AwaitingRemoteDescription && (clock.now() - s.since) >= handshakeTimeout) {
            _state.value = LinkState.HandshakeTimedOut
            _events.trySend(HandshakeEvent.TimedOut)
            true
        } else false
    }

    // ---- Answerer ----

    override suspend fun createAnswer(): SessionDescriptionData = withContext(dispatcher) {
        val offer = checkNotNull(remoteOffer)
        peerConnection.setRemoteDescriptionSuspend(offer.toWebRtc())
        val answer = peerConnection.createAnswerSuspend()
        peerConnection.setLocalDescriptionSuspend(answer)
        gatheringComplete.await()
        val fullAnswer = peerConnection.localDescription!!.toData()
        fullAnswer.also { _events.trySend(HandshakeEvent.AnswerReady(it)) }
    }

    // ---- PeerLink (shared by both) ----

    override suspend fun send(bytes: ByteArray): Result<Unit> = withContext(dispatcher) {
        val dc = dataChannel
        if (dc == null || dc.state() != DataChannel.State.OPEN) {
            return@withContext Result.failure(IllegalStateException("Cannot send: link is ${_state.value}"))
        }
        val delivered = dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
        if (delivered) Result.success(Unit)
        else Result.failure(IllegalStateException("DataChannel.send failed"))
    }

    override fun close() {
        scope.launch {
            dataChannel?.close()
            peerConnection.close()
            _state.value = LinkState.Closed
            _events.close()
        }.invokeOnCompletion { executor.shutdown() }
    }
}