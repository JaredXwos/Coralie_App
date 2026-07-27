package com.jaredxwos.coralie.connection.tests

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jaredxwos.coralie.connection.PublicMeshEndpoints
import com.jaredxwos.coralie.connection.awaitCondition
import com.jaredxwos.coralie.connection.externalMessages.TerminalFailure
import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import com.jaredxwos.coralie.identity.Signer
import com.jaredxwos.coralie.signalling.LiveNostrSignallingClient
import com.jaredxwos.coralie.signalling.backoff.exponentialBackoff
import com.jaredxwos.coralie.signalling.eventSink.DedupingEventSink
import com.jaredxwos.coralie.signalling.relaySession.LiveRelaySession
import com.jaredxwos.coralie.signalling.relaySession.RelaySession
import com.jaredxwos.coralie.signalling.relaySocket.LiveRelaySocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * Live six-node Nostr/WebRTC stress test.
 *
 * Five members simultaneously call addPeer() for the same sixth member (the
 * hub). No leaf is explicitly told about another leaf. The hub's Announce
 * frames must therefore cause the five leaves to discover one another and
 * eventually form the complete six-node mesh.
 *
 * A complete six-node undirected mesh contains 15 links. Each node must expose
 * the other five pubkeys in ConnectionManager.peers, giving 30 directed peer
 * entries in total. After the mesh resolves, the test sends one application
 * message in every direction between every pair (30 concurrent deliveries) to
 * prove that all gossip-created links are usable.
 *
 * This test runs live by default. It depends on public Nostr relays, internet
 * connectivity, STUN reachability, and the device/emulator's WebRTC support.
 */
@RunWith(AndroidJUnit4::class)
class LiveNostrMeshStressTest {

    @Test
    fun fiveMembersConnectToOneHubAndGossipResolvesFullMesh() {
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
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            val nodes = mutableListOf<LiveNode>()

            try {
                val hub = buildNode("Hub", parentScope, peerConnectionFactory, httpClient)
                    .also(nodes::add)
                val leaves = (1..LEAF_COUNT).map { index ->
                    buildNode("Member-$index", parentScope, peerConnectionFactory, httpClient)
                        .also(nodes::add)
                }

                // Nostr kind 28080 is ephemeral. Every possible peer pair needs at
                // least one shared LIVE relay subscription before the simultaneous
                // offers begin; otherwise a valid offer may be published before the
                // recipient is subscribed and will not be replayed later.
                val relayReadiness = awaitCondition(timeoutMs = RELAY_READY_TIMEOUT_MS) {
                    val missingPairs = missingCommonRelayPairs(nodes)
                    if (missingPairs.isEmpty()) RelayReadiness.READY else null
                }
                assertTrue(
                    "The six nodes did not obtain pairwise common LIVE relay " +
                        "subscriptions within ${RELAY_READY_TIMEOUT_MS}ms.\n" +
                        diagnostics(nodes),
                    relayReadiness == RelayReadiness.READY,
                )

                // Start all five leaf -> hub requests from the same release gate.
                // The hub never calls addPeer(), and leaves never call addPeer() for
                // one another; all 10 leaf-to-leaf links must arise from gossip.
                val startGate = CompletableDeferred<Unit>()
                val connectionStarts = leaves.map { leaf ->
                    async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        startGate.await()
                        leaf.manager.addPeer(hub.signer.pubkeyHex)
                    }
                }
                startGate.complete(Unit)
                connectionStarts.awaitAll()

                if (!awaitTopology(HUB_SPOKE_TIMEOUT_MS) {
                        hubHasAllLeaves(hub, leaves)
                    }
                ) {
                    fail(
                        "The five simultaneous member-to-hub connections did not " +
                            "complete within ${HUB_SPOKE_TIMEOUT_MS}ms.\n" +
                            diagnostics(nodes),
                    )
                }

                // The central assertion: gossip must expand the initial five-link
                // spoke into the full 15-link mesh without any leaf-to-leaf addPeer().
                if (!awaitTopology(FULL_MESH_TIMEOUT_MS) { isFullMesh(nodes) }) {
                    fail(
                        "Gossip did not resolve all 15 links within " +
                            "${FULL_MESH_TIMEOUT_MS}ms. A recorded terminal failure " +
                            "is retained only as diagnostics because a later gossip " +
                            "announcement may start a fresh initiation cycle.\n" +
                            diagnostics(nodes),
                    )
                }

                nodes.forEach { node ->
                    assertEquals(
                        "${node.name} does not expose all five other peers.\n" +
                            diagnostics(nodes),
                        NODE_COUNT - 1,
                        node.manager.peers.value.size,
                    )
                }
                assertEquals(
                    "The mesh does not contain all 15 bidirectional links.\n" +
                        diagnostics(nodes),
                    EXPECTED_UNDIRECTED_LINKS,
                    connectedUndirectedPairCount(nodes),
                )
                assertEquals(
                    "The mesh does not contain all 30 directed peer entries.\n" +
                        diagnostics(nodes),
                    EXPECTED_DIRECTED_PEER_ENTRIES,
                    nodes.sumOf { it.manager.peers.value.size },
                )

                verifyConcurrentAllToAllDelivery(nodes)
            } finally {
                nodes.asReversed().forEach { node ->
                    runCatching { node.manager.close() }
                }
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
            signallingKind = 28080,
            sink = sink,
        )
        val manager = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = peerConnectionFactory,
            myPubkeyHex = signer.pubkeyHex,
            signalling = signalling,
            iceServers = PublicMeshEndpoints.iceServers,
            handshakeTimeout = 30.seconds,
            maxInitiationAttempts = 5,
        )
        val terminalFailures = ConcurrentLinkedQueue<TerminalFailure>()
        parentScope.launch {
            for (failure in manager.terminalFailures) {
                terminalFailures.add(failure)
            }
        }
        return LiveNode(name, signer, signalling, manager, terminalFailures)
    }

    private suspend fun awaitTopology(
        timeoutMs: Long,
        isComplete: () -> Boolean,
    ): Boolean = awaitCondition(timeoutMs = timeoutMs) {
        if (isComplete()) true else null
    } == true

    private fun hubHasAllLeaves(hub: LiveNode, leaves: List<LiveNode>): Boolean {
        val expectedLeafKeys = leaves.mapTo(mutableSetOf()) { it.signer.pubkeyHex }
        return hub.manager.peers.value.containsAll(expectedLeafKeys) &&
            leaves.all { hub.signer.pubkeyHex in it.manager.peers.value }
    }

    private fun isFullMesh(nodes: List<LiveNode>): Boolean {
        val allKeys = nodes.mapTo(mutableSetOf()) { it.signer.pubkeyHex }
        return nodes.all { node ->
            node.manager.peers.value == allKeys - node.signer.pubkeyHex
        }
    }

    private fun connectedUndirectedPairCount(nodes: List<LiveNode>): Int {
        var count = 0
        for (leftIndex in nodes.indices) {
            for (rightIndex in leftIndex + 1 until nodes.size) {
                val left = nodes[leftIndex]
                val right = nodes[rightIndex]
                if (
                    right.signer.pubkeyHex in left.manager.peers.value &&
                    left.signer.pubkeyHex in right.manager.peers.value
                ) {
                    count += 1
                }
            }
        }
        return count
    }

    private suspend fun verifyConcurrentAllToAllDelivery(nodes: List<LiveNode>) {
        coroutineScope {
            val nonce = System.nanoTime()
            val deliveries = buildList {
                nodes.forEach { from ->
                    nodes.forEach { to ->
                        if (from !== to) {
                            add(
                                Delivery(
                                    from = from,
                                    to = to,
                                    payload = (
                                        "six-node-stress:$nonce:${from.name}->${to.name}"
                                    ).encodeToByteArray(),
                                ),
                            )
                        }
                    }
                }
            }

            // Subscribe before sending because incomingMessages is a SharedFlow with
            // no replay. CoroutineStart.UNDISPATCHED runs every collector up to its
            // first suspension before any send is attempted.
            val receivers = deliveries.map { delivery ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(ALL_TO_ALL_MESSAGE_TIMEOUT_MS) {
                        delivery.to.manager.incomingMessages.first { message ->
                            message.fromPubkeyHex == delivery.from.signer.pubkeyHex &&
                                message.bytes.contentEquals(delivery.payload)
                        }
                    }
                }
            }

            val sendFailures = deliveries.map { delivery ->
                async(Dispatchers.Default) {
                    val result = delivery.from.manager.sendMessage(
                        delivery.to.signer.pubkeyHex,
                        delivery.payload,
                    )
                    if (result.isFailure) {
                        "${delivery.from.name}->${delivery.to.name}: " +
                            (result.exceptionOrNull()?.message ?: "unknown send failure")
                    } else {
                        null
                    }
                }
            }.awaitAll().filterNotNull()

            if (sendFailures.isNotEmpty()) {
                receivers.forEach { it.cancel() }
                fail(
                    "One or more all-to-all sends failed:\n" +
                        sendFailures.joinToString(separator = "\n") +
                        "\n${diagnostics(nodes)}",
                )
            }

            try {
                withTimeout(ALL_TO_ALL_MESSAGE_TIMEOUT_MS) {
                    receivers.awaitAll()
                }
            } catch (failure: Throwable) {
                receivers.forEach { it.cancel() }
                throw AssertionError(
                    "The full mesh formed, but not all 30 concurrent application " +
                        "messages were delivered.\n${diagnostics(nodes)}",
                    failure,
                )
            }
        }
    }

    private fun liveRelays(node: LiveNode): Set<String> =
        node.signalling.connectionStatuses()
            .filter { status ->
                status.isOpen && status.subscriptionStatus == RelaySession.SubStatus.LIVE
            }
            .mapTo(mutableSetOf()) { it.relayUrl }

    private fun missingCommonRelayPairs(nodes: List<LiveNode>): List<String> = buildList {
        for (leftIndex in nodes.indices) {
            for (rightIndex in leftIndex + 1 until nodes.size) {
                val left = nodes[leftIndex]
                val right = nodes[rightIndex]
                if (liveRelays(left).intersect(liveRelays(right)).isEmpty()) {
                    add("${left.name}<->${right.name}")
                }
            }
        }
    }

    private fun missingMeshLinks(nodes: List<LiveNode>): List<String> = buildList {
        for (leftIndex in nodes.indices) {
            for (rightIndex in leftIndex + 1 until nodes.size) {
                val left = nodes[leftIndex]
                val right = nodes[rightIndex]
                val leftHasRight = right.signer.pubkeyHex in left.manager.peers.value
                val rightHasLeft = left.signer.pubkeyHex in right.manager.peers.value
                if (!leftHasRight || !rightHasLeft) {
                    add(
                        "${left.name}<->${right.name}" +
                            "(${left.name}Has${right.name}=$leftHasRight, " +
                            "${right.name}Has${left.name}=$rightHasLeft)",
                    )
                }
            }
        }
    }

    private fun diagnostics(nodes: List<LiveNode>): String = buildString {
        appendLine("Live six-node stress diagnostics:")
        appendLine("Missing relay-overlap pairs: ${missingCommonRelayPairs(nodes)}")
        appendLine("Missing mesh links: ${missingMeshLinks(nodes)}")
        appendLine(
            "Connected undirected links: ${connectedUndirectedPairCount(nodes)}/" +
                EXPECTED_UNDIRECTED_LINKS,
        )
        nodes.forEach { node ->
            val peerNames = node.manager.peers.value.map { peerKey ->
                nodes.firstOrNull { it.signer.pubkeyHex == peerKey }?.name
                    ?: shortKey(peerKey)
            }.sorted()
            val failures = node.terminalFailures.map { failure ->
                val peerName = nodes.firstOrNull {
                    it.signer.pubkeyHex == failure.pubkeyHex
                }?.name ?: shortKey(failure.pubkeyHex)
                "$peerName(${failure.attemptsMade} attempts)"
            }
            appendLine(
                "${node.name}: pubkey=${shortKey(node.signer.pubkeyHex)}, " +
                    "peers=$peerNames, terminalFailures=$failures, " +
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

    private fun shortKey(pubkey: String): String =
        if (pubkey.length <= 12) pubkey else "${pubkey.take(6)}…${pubkey.takeLast(6)}"

    private class Delivery(
        val from: LiveNode,
        val to: LiveNode,
        val payload: ByteArray,
    )

    private data class LiveNode(
        val name: String,
        val signer: Signer,
        val signalling: LiveNostrSignallingClient,
        val manager: LiveConnectionManager,
        val terminalFailures: ConcurrentLinkedQueue<TerminalFailure>,
    )

    private enum class RelayReadiness { READY }

    private companion object {
        const val LEAF_COUNT = 5
        const val NODE_COUNT = LEAF_COUNT + 1
        const val EXPECTED_UNDIRECTED_LINKS = NODE_COUNT * (NODE_COUNT - 1) / 2
        const val EXPECTED_DIRECTED_PEER_ENTRIES = NODE_COUNT * (NODE_COUNT - 1)

        const val RELAY_READY_TIMEOUT_MS = 90_000L
        const val HUB_SPOKE_TIMEOUT_MS = 150_000L
        const val FULL_MESH_TIMEOUT_MS = 240_000L
        const val ALL_TO_ALL_MESSAGE_TIMEOUT_MS = 60_000L
    }
}
