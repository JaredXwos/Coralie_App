package com.jaredxwos.coralie.connection.manager

import com.jaredxwos.coralie.connection.DataChannelFrame
import com.jaredxwos.coralie.connection.InitiationAttempt
import com.jaredxwos.coralie.connection.externalMessages.PeerMessage
import com.jaredxwos.coralie.connection.externalMessages.TerminalFailure
import com.jaredxwos.coralie.signalling.InboundMessage
import com.jaredxwos.coralie.signalling.NostrSignallingClient
import com.jaredxwos.coralie.transport.IceServerConfig
import com.jaredxwos.coralie.transport.LinkState
import com.jaredxwos.coralie.transport.SdpType
import com.jaredxwos.coralie.transport.SessionDescriptionData
import com.jaredxwos.coralie.transport.context.Initiator
import com.jaredxwos.coralie.transport.context.PeerLink
import com.jaredxwos.coralie.transport.context.getAnswerer
import com.jaredxwos.coralie.transport.context.getInitiator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.webrtc.PeerConnectionFactory

/**
 * Wires :signalling and :transport together — the only place either module's real
 * implementation meets the other. See phase4-connection-manager-design.md §4/§6 for
 * the full ownership/call-graph reference this implements.
 *
 * Calls `:transport`'s `getInitiator()`/`getAnswerer()` directly — deliberately, with
 * no injectable seam in between (see design doc §3b/§7). Consequence: the retry,
 * glare-resolution, and gossip logic below can't be exercised as plain JVM unit tests
 * against a fake transport; testing it requires a real or instrumented
 * `PeerConnectionFactory`.
 *
 * [parentScope] is passed straight through to every `getInitiator`/`getAnswerer`
 * call — this manager's own internal scope and every live link it creates end up as
 * siblings under that same externally-owned ancestor (a Service, a ViewModel, a
 * login session — whatever controls the mesh subsystem's lifetime), so a single
 * external cancellation tears down both. This manager's `close()` does not rely on
 * that cascade, though — it explicitly closes every tracked initiator/link itself,
 * since sibling scopes don't cancel each other.
 *
 * Every mutation of [initiating], [answering], and [_connected] happens only from coroutines running
 * on [dispatcher] (single-thread confinement, same convention :transport's LiveRtcContext
 * uses per connection). Every private function that touches those collections is
 * non-suspend and runs to completion before yielding, so the check-then-act guards
 * (e.g. onInboundOffer's two-step gate) are race-free with no explicit locking.
 */
class LiveConnectionManager(
    private val parentScope: CoroutineScope,
    private val peerConnectionFactory: PeerConnectionFactory,
    override val myPubkeyHex: String,
    private val signalling: NostrSignallingClient,
    private val iceServers: List<IceServerConfig>,
    private val clock: Clock = Clock.System,
    private val handshakeTimeout: Duration = 30.seconds,
    private val maxInitiationAttempts: Int = 5,
    private val handshakeTickInterval: Duration = 1.seconds,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val logWarning: (message: String, cause: Throwable?) -> Unit = { _, _ -> },
) : ConnectionManager {

    // A child of `parentScope` (inherits its cancellation) with its own SupervisorJob
    // (a failure in one of this manager's coroutines doesn't cancel its siblings) and
    // pinned to `dispatcher` for the state-confinement guarantee described above.
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + dispatcher
    )

    // -- state; touched only from coroutines running on `dispatcher` --
    private val initiating = mutableMapOf<String, InitiationAttempt>()
    private val answering = mutableMapOf<String, PeerLink>()
    private val _connected = MutableStateFlow<Map<String, PeerLink>>(emptyMap())

    private val _incomingMessages = MutableSharedFlow<PeerMessage>(extraBufferCapacity = 64)
    private val _terminalFailures = Channel<TerminalFailure>(Channel.BUFFERED)

    override val peers: StateFlow<Set<String>> =
        _connected.map { it.keys }.stateIn(scope, SharingStarted.Eagerly, emptySet())
    override val incomingMessages: SharedFlow<PeerMessage> = _incomingMessages.asSharedFlow()
    override val terminalFailures: ReceiveChannel<TerminalFailure> get() = _terminalFailures

    init {
        signalling.start()

        // §6.3/§6.4 — the sole inbound signalling loop. Runs on `dispatcher` for its
        // entire lifetime, so onInboundOffer/onInboundAnswer never need to hop context.
        scope.launch {
            for (msg in signalling.inbound) onInboundSignallingMessage(msg)
        }

        // The piece Phase 3 left external: checkHandshakeTimeout() only transitions
        // LinkState if something ticks it. No tick, no timeout, no retry — ever.
        scope.launch {
            while (isActive) {
                delay(handshakeTickInterval)
                // Snapshot first: checkHandshakeTimeout() can synchronously drive
                // onAttemptFailed() via watchLinkState's collector below, which would
                // mutate `initiating` mid-iteration otherwise.
                initiating.values.toList().forEach { it.initiator.checkHandshakeTimeout() }
            }
        }
    }

    // ------------------------------------------------------------------
    // Public surface (ConnectionManager)
    // ------------------------------------------------------------------

    override fun addPeer(pubkeyHex: String) {
        scope.launch { onNewPeerLearned(pubkeyHex) }
    }

    override suspend fun sendMessage(toPubkeyHex: String, bytes: ByteArray): Result<Unit> {
        val link = _connected.value[toPubkeyHex]
            ?: return Result.failure(NoSuchElementException("not connected: $toPubkeyHex"))
        val frame = Json.encodeToString<DataChannelFrame>(DataChannelFrame.App(bytes))
        return link.send(frame.encodeToByteArray())
    }

    override fun close() {
        scope.cancel()
        initiating.values.forEach { it.initiator.close() }
        answering.values.forEach { it.close() }
        _connected.value.values.forEach { it.close() }
        signalling.close()
        _terminalFailures.close()
    }

    private fun createInitiator(): Initiator = getInitiator(
        factory = peerConnectionFactory,
        iceServers = iceServers,
        parentScope = parentScope,
        handshakeTimeout = handshakeTimeout,
        clock = clock,
    )

    // ------------------------------------------------------------------
    // §6.1 / §6.2 / §6.10 — a new pubkey, from either an out-of-band paste
    // or a gossip Announce. Both funnel through here; this is the only
    // place the self-key and idempotency guards live.
    // ------------------------------------------------------------------
    private fun onNewPeerLearned(pubkeyHex: String) {
        if (pubkeyHex == myPubkeyHex) return
        if (pubkeyHex in initiating || pubkeyHex in answering || pubkeyHex in _connected.value) return

        val initiator = createInitiator()
        val watcherJob = watchLinkState(pubkeyHex, initiator)
        initiating[pubkeyHex] = InitiationAttempt(
            initiator = initiator,
            attemptCount = 1,
            startedAt = clock.now(),
            watcherJob = watcherJob,
        )
        sendOffer(pubkeyHex, initiator)
    }

    private fun sendOffer(pubkeyHex: String, initiator: Initiator) {
        scope.launch {
            val offer = initiator.createOffer()
            val accepted = signalling.send(pubkeyHex, Json.encodeToString(offer))
            // Guard against a stale rejection clobbering a newer attempt: this call
            // is async, so a retry (triggered by, say, the timeout ticker) could
            // already have superseded `initiator` by the time this resolves.
            if (!accepted && initiating[pubkeyHex]?.initiator === initiator) {
                onAttemptFailed(pubkeyHex) // §6.6 — same counter as a handshake timeout
            }
        }
    }

    // §6.5 / §6.6 / §6.7 — one shared failure path for a handshake timeout
    // and a rejected signalling send alike.
    private fun onAttemptFailed(pubkeyHex: String) {
        val attempt = initiating[pubkeyHex] ?: return
        attempt.watcherJob.cancel()
        attempt.initiator.close()

        if (attempt.attemptCount >= maxInitiationAttempts) {
            initiating.remove(pubkeyHex)
            _terminalFailures.trySend(TerminalFailure(pubkeyHex, attempt.attemptCount))
            return
        }

        val fresh = createInitiator()
        initiating[pubkeyHex] = attempt.copy(
            initiator = fresh,
            attemptCount = attempt.attemptCount + 1,
            watcherJob = watchLinkState(pubkeyHex, fresh),
        )
        sendOffer(pubkeyHex, fresh)
    }

    // Reached identically by an Initiator or an Answerer. Identity checks
    // prevent a stale duplicate negotiation from replacing an already-live link.
    private fun onLinkConnected(pubkeyHex: String, link: PeerLink) {
        val existing = _connected.value[pubkeyHex]
        if (existing != null && existing !== link) {
            if (initiating[pubkeyHex]?.initiator === link) initiating.remove(pubkeyHex)
            if (answering[pubkeyHex] === link) answering.remove(pubkeyHex)
            link.close()
            return
        }

        if (initiating[pubkeyHex]?.initiator === link) initiating.remove(pubkeyHex)
        if (answering[pubkeyHex] === link) answering.remove(pubkeyHex)
        _connected.update { it + (pubkeyHex to link) }
        launchFrameReader(pubkeyHex, link)
        broadcastAnnounce(pubkeyHex)
    }

    // One watcher per link. Identity checks are important because a stale link
    // may fail after a newer link for the same pubkey has already connected.
    private fun watchLinkState(pubkeyHex: String, link: PeerLink): Job = scope.launch {
        link.state.collect { state ->
            when (state) {
                LinkState.Connected -> onLinkConnected(pubkeyHex, link)
                LinkState.HandshakeTimedOut, LinkState.Failed -> {
                    when {
                        initiating[pubkeyHex]?.initiator === link -> onAttemptFailed(pubkeyHex)
                        answering[pubkeyHex] === link -> {
                            answering.remove(pubkeyHex)
                            link.close()
                        }
                        _connected.value[pubkeyHex] === link -> {
                            _connected.update { it - pubkeyHex }
                        }
                    }
                    cancel()
                }
                LinkState.Closed -> {
                    if (answering[pubkeyHex] === link) answering.remove(pubkeyHex)
                    if (_connected.value[pubkeyHex] === link) {
                        _connected.update { it - pubkeyHex }
                    }
                    cancel()
                }
                LinkState.New, is LinkState.AwaitingRemoteDescription, LinkState.Connecting -> Unit
            }
        }
    }

    // §6.9 — best-effort, fire-and-forget; no retry on a failed send.
    private fun broadcastAnnounce(newPeerPubkeyHex: String) {
        val frame = Json.encodeToString<DataChannelFrame>(DataChannelFrame.Announce(newPeerPubkeyHex))
            .encodeToByteArray()
        _connected.value.forEach { (pubkeyHex, link) ->
            if (pubkeyHex != newPeerPubkeyHex) {
                scope.launch {
                    link.send(frame).onFailure { cause ->
                        logWarning("failed to announce $newPeerPubkeyHex to $pubkeyHex", cause)
                    }
                }
            }
        }
    }

    // §6.2 / §6.11 — demux, and the log-then-drop malformed-frame path.
    private fun launchFrameReader(pubkeyHex: String, link: PeerLink) {
        scope.launch {
            link.incomingBytes.collect { bytes ->
                val frame = runCatching { Json.decodeFromString<DataChannelFrame>(bytes.decodeToString()) }
                    .getOrElse {
                        logWarning("malformed data-channel frame from $pubkeyHex", it)
                        return@collect
                    }
                when (frame) {
                    is DataChannelFrame.App ->
                        _incomingMessages.emit(PeerMessage(pubkeyHex, frame.payload))
                    is DataChannelFrame.Announce ->
                        onNewPeerLearned(frame.pubkeyHex)
                }
            }
        }
    }

    // §6.3 / §6.4 — dispatch by SessionDescriptionData.type. Runs inline
    // inside the init-launched inbound loop, already on `dispatcher`.
    private fun onInboundSignallingMessage(msg: InboundMessage) {
        val sdp = runCatching { Json.decodeFromString<SessionDescriptionData>(msg.plaintext) }
            .getOrElse {
                logWarning("malformed signalling payload from ${msg.fromPubkey}", it)
                return
            }
        when (sdp.type) {
            SdpType.OFFER -> onInboundOffer(msg.fromPubkey, sdp)
            SdpType.ANSWER -> onInboundAnswer(msg.fromPubkey, sdp)
        }
    }

    // Accept offers independently per peer. When both sides initiate the same
    // pair, the lexicographically smaller pubkey remains the initiator and the
    // larger pubkey abandons its attempt and answers. Initiations to unrelated
    // peers must never block this offer.
    private fun onInboundOffer(fromPubkeyHex: String, offer: SessionDescriptionData) {
        if (fromPubkeyHex in _connected.value) return
        if (fromPubkeyHex in answering) return

        val localAttempt = initiating[fromPubkeyHex]
        if (localAttempt != null) {
            val iAmDesignatedInitiator = myPubkeyHex < fromPubkeyHex
            if (iAmDesignatedInitiator) return

            initiating.remove(fromPubkeyHex)
            localAttempt.watcherJob.cancel()
            localAttempt.initiator.close()
        }

        val answerer = getAnswerer(
            factory = peerConnectionFactory,
            iceServers = iceServers,
            parentScope = parentScope,
            offer = offer,
            clock = clock,
        )
        answering[fromPubkeyHex] = answerer
        watchLinkState(fromPubkeyHex, answerer)
        scope.launch {
            runCatching {
                val answer = answerer.createAnswer()
                check(signalling.send(fromPubkeyHex, Json.encodeToString(answer))) {
                    "all relays rejected answer for $fromPubkeyHex"
                }
            }.onFailure { cause ->
                if (answering[fromPubkeyHex] === answerer) {
                    answering.remove(fromPubkeyHex)
                    answerer.close()
                }
                logWarning("failed to answer offer from $fromPubkeyHex", cause)
            }
        }
    }

    // §6.4 — dropped if no in-flight initiation was expecting it. Resolved: no
    // offer/answer correlation id is needed. A stale answer (one paired with an
    // offer a retry has already superseded) fails safely — ICE and DTLS both
    // require credentials tied to the specific negotiation round, so a mismatched
    // answer either throws here synchronously (caught below) or setRemoteDescription
    // succeeds syntactically and the connection simply fails ICE negotiation later,
    // via the ordinary LinkState.Failed path watchLinkState already handles. Either
    // way it costs one attempt, same as §6.6.
    private fun onInboundAnswer(fromPubkeyHex: String, answer: SessionDescriptionData) {
        val attempt = initiating[fromPubkeyHex] ?: return
        scope.launch {
            runCatching { attempt.initiator.acceptAnswer(answer) }
                .onFailure {
                    // Same guard as sendOffer's: only penalize if a retry hasn't
                    // already superseded this attempt while this call was in flight.
                    if (initiating[fromPubkeyHex] === attempt) onAttemptFailed(fromPubkeyHex)
                }
        }
    }
}