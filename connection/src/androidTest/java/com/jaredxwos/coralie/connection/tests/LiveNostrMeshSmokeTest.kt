package com.jaredxwos.coralie.connection.tests

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jaredxwos.coralie.connection.PublicMeshEndpoints
import com.jaredxwos.coralie.connection.awaitCondition
import com.jaredxwos.coralie.connection.awaitTrue
import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import com.jaredxwos.coralie.identity.Signer
import com.jaredxwos.coralie.signalling.LiveNostrSignallingClient
import com.jaredxwos.coralie.signalling.backoff.exponentialBackoff
import com.jaredxwos.coralie.signalling.eventSink.DedupingEventSink
import com.jaredxwos.coralie.signalling.relaySession.LiveRelaySession
import com.jaredxwos.coralie.signalling.relaySession.RelaySession
import com.jaredxwos.coralie.signalling.relaySocket.LiveRelaySocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end gossip test using the production public Nostr relays and the real
 * Android WebRTC transport.
 *
 * This is intentionally separate from [ConnectionManagerGossipTest]: that test keeps Nostr
 * signalling deterministic and in-process, whereas this test exercises:
 *
 *  - real WebSocket connections to [PublicMeshEndpoints.relayUrls]
 *  - the production ephemeral Nostr kind 28080
 *  - NIP-44 encryption/decryption
 *  - real SDP offer/answer delivery through public relays
 *  - STUN-backed WebRTC links using [PublicMeshEndpoints.iceServers]
 *  - Announce-based gossip followed by a usable B-C application connection
 *
 * The test creates three logical app instances on one Android device/emulator.
 * Only A is explicitly given peer addresses. B must learn C from A's data-
 * channel Announce frame and then establish B-C through live Nostr signalling.
 *
 * This test runs by default with the Android instrumentation suite and therefore
 * requires internet access and currently reachable public Nostr relays.
 */
@RunWith(AndroidJUnit4::class)
class LiveNostrMeshSmokeTest {

    @Test
    fun publicRelayGossipCreatesUsablePeerConnection() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context.applicationContext)
                    .createInitializationOptions(),
            )

            val peerConnectionFactory = PeerConnectionFactory
                .builder()
                .createPeerConnectionFactory()
            val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val httpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // WebSockets are long-lived.
                .build()

            val nodes = mutableListOf<LiveNode>()

            try {
                val nodeA = buildNode("A", parentScope, peerConnectionFactory, httpClient)
                    .also(nodes::add)
                val nodeB = buildNode("B", parentScope, peerConnectionFactory, httpClient)
                    .also(nodes::add)
                val nodeC = buildNode("C", parentScope, peerConnectionFactory, httpClient)
                    .also(nodes::add)

                // Kind 28080 is ephemeral. Waiting for a common LIVE subscription is
                // essential: a valid offer published before the recipient subscribes
                // is not expected to be replayed later by the relay.
                val commonRelay = awaitCondition(timeoutMs = RELAY_READY_TIMEOUT_MS) {
                    commonLiveRelay(nodes)
                }
                assertTrue(
                    "A, B, and C never obtained a common LIVE relay subscription.\n" +
                        diagnostics(nodes),
                    commonRelay != null,
                )

                // Establish A-B first so the later A-C connection deterministically
                // makes A announce C to B. B.addPeer(C) and C.addPeer(B) are never
                // called anywhere in this test.
                nodeA.manager.addPeer(nodeB.signer.pubkeyHex)
                assertConnected("A -> B", nodeA, nodeB, nodes)
                assertConnected("B -> A", nodeB, nodeA, nodes)

                nodeA.manager.addPeer(nodeC.signer.pubkeyHex)
                assertConnected("A -> C", nodeA, nodeC, nodes)
                assertConnected("C -> A", nodeC, nodeA, nodes)

                // Core live-gossip assertion. B only knows C because A sent an
                // Announce(C) frame over the already-open A-B WebRTC data channel.
                assertConnected("B -> C through gossip", nodeB, nodeC, nodes)
                assertConnected("C -> B through gossip", nodeC, nodeB, nodes)

                assertEquals(
                    setOf(nodeB.signer.pubkeyHex, nodeC.signer.pubkeyHex),
                    nodeA.manager.peers.value,
                )
                assertEquals(
                    setOf(nodeA.signer.pubkeyHex, nodeC.signer.pubkeyHex),
                    nodeB.manager.peers.value,
                )
                assertEquals(
                    setOf(nodeA.signer.pubkeyHex, nodeB.signer.pubkeyHex),
                    nodeC.manager.peers.value,
                )

                // Prove that the gossip-created B-C link is an actual usable app
                // connection, rather than only an entry in the peers StateFlow.
                val payload = "live-nostr-gossip-${System.nanoTime()}".encodeToByteArray()
                val receivedByC = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(APP_MESSAGE_TIMEOUT_SECONDS.seconds) {
                        nodeC.manager.incomingMessages.first { message ->
                            message.fromPubkeyHex == nodeB.signer.pubkeyHex &&
                                message.bytes.contentEquals(payload)
                        }
                    }
                }

                val sendResult = nodeB.manager.sendMessage(nodeC.signer.pubkeyHex, payload)
                if (sendResult.isFailure) {
                    receivedByC.cancel()
                    fail(
                        "B could not send to gossip-discovered C: " +
                            "${sendResult.exceptionOrNull()?.message}\n${diagnostics(nodes)}",
                    )
                }

                try {
                    receivedByC.await()
                } catch (failure: Throwable) {
                    throw AssertionError(
                        "C never received B's application message over the " +
                            "gossip-created connection.\n${diagnostics(nodes)}",
                        failure,
                    )
                }
            } finally {
                nodes.asReversed().forEach { node -> runCatching { node.manager.close() } }
                parentScope.cancel()
                peerConnectionFactory.dispose()
                httpClient.dispatcher.executorService.shutdown()
                httpClient.connectionPool.evictAll()
            }
        }
    }

    private fun buildNode(
        name: String,
        parentScope: CoroutineScope,
        peerConnectionFactory: PeerConnectionFactory,
        httpClient: OkHttpClient,
    ): LiveNode {
        val signer = Signer()
        val sink = DedupingEventSink()
        val socketScope = CoroutineScope(
            SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO,
        )
        val relayEndpoints = PublicMeshEndpoints.relayUrls.map { relayUrl ->
            val socket = LiveRelaySocket(
                client = httpClient,
                url = relayUrl,
                scope = socketScope,
                backoffStrategy = ::exponentialBackoff,
            )
            LiveNostrSignallingClient.RelayEndpoint(
                url = relayUrl,
                socket = socket,
                session = LiveRelaySession(socket, sink),
            )
        }
        val signalling = LiveNostrSignallingClient(
            relays = relayEndpoints,
            signer = signer,
            // Explicitly use the production ephemeral signalling kind.
            signallingKind = 28080,
            sink = sink,
        )
        val manager = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = peerConnectionFactory,
            myPubkeyHex = signer.pubkeyHex,
            signalling = signalling,
            iceServers = PublicMeshEndpoints.iceServers,
            handshakeTimeout = 45.seconds,
        )
        return LiveNode(name, signer, signalling, manager)
    }

    private suspend fun assertConnected(
        description: String,
        from: LiveNode,
        to: LiveNode,
        nodes: List<LiveNode>,
    ) {
        val connected = awaitTrue(timeoutMs = PEER_CONNECTION_TIMEOUT_MS) {
            to.signer.pubkeyHex in from.manager.peers.value
        }
        assertTrue(
            "$description did not connect within ${PEER_CONNECTION_TIMEOUT_MS}ms.\n" +
                diagnostics(nodes),
            connected,
        )
    }

    private fun commonLiveRelay(nodes: List<LiveNode>): String? {
        val liveRelaySets = nodes.map { node ->
            node.signalling.connectionStatuses()
                .filter { status ->
                    status.isOpen &&
                        status.subscriptionStatus == RelaySession.SubStatus.LIVE
                }
                .mapTo(mutableSetOf()) { it.relayUrl }
        }
        return liveRelaySets
            .reduceOrNull { common, next -> common.apply { retainAll(next) } }
            ?.firstOrNull()
    }

    private fun diagnostics(nodes: List<LiveNode>): String = buildString {
        appendLine("Live Nostr diagnostics:")
        nodes.forEach { node ->
            appendLine(
                "${node.name}: pubkey=${node.signer.pubkeyHex}, " +
                    "peers=${node.manager.peers.value}, " +
                    "lastInboundStatus=${node.signalling.lastInboundStatus}",
            )
            node.signalling.connectionStatuses().forEach { status ->
                appendLine(
                    "  ${status.relayUrl}: open=${status.isOpen}, " +
                        "subscription=${status.subscriptionStatus}",
                )
            }
        }
    }

    private data class LiveNode(
        val name: String,
        val signer: Signer,
        val signalling: LiveNostrSignallingClient,
        val manager: LiveConnectionManager,
    )

    private companion object {
        const val RELAY_READY_TIMEOUT_MS = 60_000L
        const val PEER_CONNECTION_TIMEOUT_MS = 90_000L
        const val APP_MESSAGE_TIMEOUT_SECONDS = 30L
    }
}

