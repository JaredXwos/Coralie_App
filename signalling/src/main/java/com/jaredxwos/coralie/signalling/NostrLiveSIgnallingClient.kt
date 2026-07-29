package com.jaredxwos.coralie.signalling

import android.util.Log
import com.jaredxwos.coralie.identity.Signer
import com.jaredxwos.coralie.signalling.crypto.BouncyCastleNip44Cipher
import com.jaredxwos.coralie.signalling.crypto.Nip44Cipher
import com.jaredxwos.coralie.signalling.eventSink.DedupingEventSink
import com.jaredxwos.coralie.signalling.eventSink.EventSink
import com.jaredxwos.coralie.signalling.nostrMessage.ClientToServerMessage
import com.jaredxwos.coralie.signalling.relaySession.RelaySession
import com.jaredxwos.coralie.signalling.relaySocket.RelaySocket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

private const val INBOX_SUB_ID = "inbox"
private const val NOSTR_KIND = 28080
class LiveNostrSignallingClient(
    private val relays: List<RelayEndpoint>,
    private val signer: Signer,
    private val signallingKind: Int = 28080,
    private val sink: EventSink = DedupingEventSink(),
    private val clock: Clock = Clock.System,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logDebug: (String) -> Unit = {
        Log.d(TAG, it)
    },
) : NostrSignallingClient {

    /** Caller-assembled per-relay wiring — both the socket and the session
     *  built around it, not just the socket, so tests can substitute a fake
     *  RelaySession without needing a fake RelaySocket to also behave
     *  correctly for heartbeat/reconnect logic it has nothing to do with.
     *  Real callers build this as RelayEndpoint(url, socket, LiveRelaySession(socket, sink)). */
    data class RelayEndpoint(val url: String, val socket: RelaySocket, val session: RelaySession)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var _lastInboundStatus: Boolean? = null
    override val lastInboundStatus: Boolean? get() = _lastInboundStatus

    private val cipherLock = Mutex()
    private val ciphers = mutableMapOf<String, Nip44Cipher>()

    private val _inbound = Channel<InboundMessage>(Channel.BUFFERED)
    override val inbound: ReceiveChannel<InboundMessage> = _inbound

    override fun start() {
        relays.forEach { it.session.start(scope) }
        scope.launch {
            relays.forEach { relay ->
                relay.session.subscribe(ClientToServerMessage.Req.forInbox(INBOX_SUB_ID, signer.pubkeyHex, signallingKind))
            }
        }
        scope.launch { pumpInbound() }
    }

    override suspend fun send(toPubkey: String, plaintext: String): Boolean {
        val ciphertext = cipherFor(toPubkey).encrypt(plaintext)
        val event = signer.sign(
            kind = signallingKind,
            tags = listOf(listOf("p", toPubkey)),
            content = ciphertext,
            createdAt = clock.now().epochSeconds,
        )
        val message = ClientToServerMessage.Event(event)
        val results = relays.map { it.session.publish(message) }
        return results.any { it.isSuccess }
    }

    override fun connectionStatuses(): List<RelayStatus> = relays.map { relay ->
        RelayStatus(
            relayUrl = relay.url,
            isOpen = relay.socket.isOpen,
            subscriptionStatus = relay.session.subStates.value[INBOX_SUB_ID],
        )
    }

    override fun close() {
        scope.cancel()
        relays.forEach { it.socket.close() }
        _inbound.close()
    }

    private suspend fun pumpInbound() {
        for (event in sink.events) {
            logDebug(
                "pumpInbound saw event " +
                    "id=${event.id} from=${event.pubkey}",
            )
            if (!event.verify()) {
                logDebug(
                    "verify() FAILED for event id=${event.id}",
                )
                _lastInboundStatus = false
                continue
            }
            cipherFor(event.pubkey).decrypt(event.content)
                .onSuccess { plaintext ->
                    logDebug(
                        "decrypt SUCCESS, forwarding " +
                            "to inbound channel",
                    )
                    _lastInboundStatus = true
                    _inbound.send(InboundMessage(fromPubkey = event.pubkey, plaintext = plaintext))
                }
                .onFailure {
                    logDebug(
                        "decrypt FAILED: ${it.message}",
                    )
                    _lastInboundStatus = false
                }
        }
    }

    private suspend fun cipherFor(peerPubkeyHex: String): Nip44Cipher = cipherLock.withLock {
        ciphers.getOrPut(peerPubkeyHex) { BouncyCastleNip44Cipher(signer.getConvoKey(peerPubkeyHex)) }
    }

    private companion object {
        const val TAG = "SignallingClient"
    }
}
