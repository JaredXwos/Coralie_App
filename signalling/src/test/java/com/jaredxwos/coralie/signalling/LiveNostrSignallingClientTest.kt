package com.jaredxwos.coralie.signalling
import com.jaredxwos.coralie.identity.NostrEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.jaredxwos.coralie.identity.Signer
import com.jaredxwos.coralie.signalling.crypto.BouncyCastleNip44Cipher
import com.jaredxwos.coralie.signalling.eventSink.EventSink
import com.jaredxwos.coralie.signalling.nostrMessage.ClientToServerMessage
import com.jaredxwos.coralie.signalling.nostrMessage.ServerToClientMessage
import com.jaredxwos.coralie.signalling.relaySession.RelaySession
import com.jaredxwos.coralie.signalling.relaySocket.RelaySocket
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope


class LiveNostrSignallingClientTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.newTestClient(
        relays: List<LiveNostrSignallingClient.RelayEndpoint>,
        signer: Signer,
        sink: EventSink,
        signallingKind: Int = 20001,
    ) = LiveNostrSignallingClient(
        relays = relays, signer = signer, signallingKind = signallingKind, sink = sink,
        dispatcher = StandardTestDispatcher(testScheduler),
        logDebug = {},
    )
    private class FakeRelaySocket(override var isOpen: Boolean = true) : RelaySocket {
        override val frames = Channel<Result<ServerToClientMessage>>(Channel.UNLIMITED)
        var closed = false; private set
        override fun send(text: String): Result<Unit> = Result.success(Unit)
        override fun close() { closed = true }
    }

    private class FakeRelaySession : RelaySession {
        private val _subStates = MutableStateFlow<Map<String, RelaySession.SubStatus>>(emptyMap())
        override val subStates: StateFlow<Map<String, RelaySession.SubStatus>> = _subStates

        val subscribed = mutableListOf<ClientToServerMessage.Req>()
        val published = mutableListOf<ClientToServerMessage.Event>()
        var startedWith: CoroutineScope? = null

        /** Configurable per test — defaults to success, so every existing test
         *  using this fake needs no changes to keep passing. */
        var subscribeResult: Result<Unit> = Result.success(Unit)
        var publishResult: Result<Unit> = Result.success(Unit)

        override fun start(scope: CoroutineScope) { startedWith = scope }
        override suspend fun subscribe(request: ClientToServerMessage.Req): Result<Unit> {
            subscribed.add(request)
            _subStates.value = mapOf(request.subId to RelaySession.SubStatus.PENDING)
            return subscribeResult
        }
        override suspend fun publish(event: ClientToServerMessage.Event): Result<Unit> {
            published.add(event)
            return publishResult
        }
    }

    private class FakeEventSink : EventSink {
        override val events = Channel<NostrEvent>(Channel.UNLIMITED)
        override suspend fun offer(event: NostrEvent): Boolean {
            events.send(event)
            return true
        }
    }

    private val signallingKind = 20001

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startSubscribesEveryRelayToInbox() = runTest {
        val me = Signer()
        val sessionA = FakeRelaySession()
        val sessionB = FakeRelaySession()
        val client = newTestClient(
            relays = listOf(
                LiveNostrSignallingClient.RelayEndpoint("wss://a", FakeRelaySocket(), sessionA),
                LiveNostrSignallingClient.RelayEndpoint("wss://b", FakeRelaySocket(), sessionB),
            ),
            signer = me, sink = FakeEventSink(),
        )

        client.start()
        advanceUntilIdle()

        listOf(sessionA, sessionB).forEach { session ->
            val req = session.subscribed.single()
            assertEquals(listOf(signallingKind), req.kinds)
            assertEquals(listOf(me.pubkeyHex), req.tags["p"])
        }
    }

    @Test
    fun sendBroadcastsEncryptedEventToEveryRelay() = runTest {
        val me = Signer()
        val peer = Signer()
        val sessionA = FakeRelaySession()
        val sessionB = FakeRelaySession()
        val client = newTestClient(
            relays = listOf(
                LiveNostrSignallingClient.RelayEndpoint("wss://a", FakeRelaySocket(), sessionA),
                LiveNostrSignallingClient.RelayEndpoint("wss://b", FakeRelaySocket(), sessionB),
            ),
            signer = me, sink = FakeEventSink(),
        )

        assertTrue(client.send(peer.pubkeyHex, "hello peer"))

        listOf(sessionA, sessionB).forEach { session ->
            val event = session.published.single().event
            assertEquals(signallingKind, event.kind)
            assertEquals(listOf(listOf("p", peer.pubkeyHex)), event.tags)
            assertTrue(event.verify())

            val peerCipher = BouncyCastleNip44Cipher(peer.getConvoKey(me.pubkeyHex))
            assertEquals("hello peer", peerCipher.decrypt(event.content).getOrNull())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun inboundForwardsVerifiedDecryptedMessage() = runTest {
        val me = Signer()
        val peer = Signer()
        val sink = FakeEventSink()
        val client = newTestClient(
            relays = listOf(LiveNostrSignallingClient.RelayEndpoint("wss://a", FakeRelaySocket(), FakeRelaySession())),
            signer = me, sink = sink,
        )
        client.start()
        advanceUntilIdle()

        val ciphertext = BouncyCastleNip44Cipher(peer.getConvoKey(me.pubkeyHex)).encrypt("hi from peer")
        val event = peer.sign(kind = signallingKind, tags = listOf(listOf("p", me.pubkeyHex)), content = ciphertext, createdAt = 1000L)
        sink.offer(event)
        advanceUntilIdle()

        val received = client.inbound.receive()
        assertEquals(peer.pubkeyHex, received.fromPubkey)
        assertEquals("hi from peer", received.plaintext)
        assertEquals(true, client.lastInboundStatus)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun inboundDropsEventWithInvalidSignature() = runTest {
        val me = Signer()
        val peer = Signer()
        val sink = FakeEventSink()
        val client = newTestClient(
            relays = listOf(LiveNostrSignallingClient.RelayEndpoint("wss://a", FakeRelaySocket(), FakeRelaySession())),
            signer = me, sink = sink,
        )
        client.start()
        advanceUntilIdle()

        val ciphertext = BouncyCastleNip44Cipher(peer.getConvoKey(me.pubkeyHex)).encrypt("tampered")
        val event = peer.sign(kind = signallingKind, tags = listOf(listOf("p", me.pubkeyHex)), content = ciphertext, createdAt = 1000L)
        sink.offer(event.copy(content = "corrupted-after-signing"))
        advanceUntilIdle()

        assertTrue(client.inbound.tryReceive().isFailure)
        assertEquals(false, client.lastInboundStatus)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun connectionStatusesReflectsSocketAndSubscriptionState() = runTest {
        val socket = FakeRelaySocket(isOpen = true)
        val session = FakeRelaySession()
        val client = newTestClient(
            relays = listOf(LiveNostrSignallingClient.RelayEndpoint("wss://a", socket, session)),
            signer = Signer(), sink = FakeEventSink(),
        )
        client.start()
        advanceUntilIdle()

        val status = client.connectionStatuses().single()
        assertEquals("wss://a", status.relayUrl)
        assertTrue(status.isOpen)
        assertEquals(RelaySession.SubStatus.PENDING, status.subscriptionStatus)
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    @Test
    fun closeCancelsAndClosesEverything() = runTest {
        val socket = FakeRelaySocket()
        val client = newTestClient(
            relays = listOf(LiveNostrSignallingClient.RelayEndpoint("wss://a", socket, FakeRelaySession())),
            signer = Signer(), sink = FakeEventSink(),
        )
        client.start()
        advanceUntilIdle()

        client.close()

        assertTrue(socket.closed)
        assertTrue(client.inbound.isClosedForReceive)
    }
}
