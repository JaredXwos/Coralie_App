package com.jaredxwos.coralie.connection.tests

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jaredxwos.coralie.connection.awaitTrue
import com.jaredxwos.coralie.connection.buildPeerConnectionFactory
import com.jaredxwos.coralie.connection.externalMessages.PeerMessage
import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import com.jaredxwos.coralie.connection.testClients.LoopbackSignallingBus
import com.jaredxwos.coralie.connection.testClients.LoopbackSignallingBusClient
import com.jaredxwos.coralie.transport.SdpType
import com.jaredxwos.coralie.transport.SessionDescriptionData
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory

/** Deterministic in-process gossip mesh formation and payload delivery. */
@RunWith(AndroidJUnit4::class)
class ConnectionManagerGossipTest {
    private val pubkeyA = "11".repeat(32)
    private val pubkeyB = "22".repeat(32)
    private val pubkeyC = "33".repeat(32)

    @Test
    fun aConnectingToBAndCCausesBAndCToSelfConnectViaGossip() = runBlocking {
        val bus = LoopbackSignallingBus()
        val signallingA = bus.createClient(pubkeyA)
        val signallingB = bus.createClient(pubkeyB)
        val signallingC = bus.createClient(pubkeyC)

        val parentScope = CoroutineScope(SupervisorJob())

        val managerA = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildPeerConnectionFactory(),
            myPubkeyHex = pubkeyA,
            signalling = signallingA,
            iceServers = emptyList(),
        )
        val managerB = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildPeerConnectionFactory(),
            myPubkeyHex = pubkeyB,
            signalling = signallingB,
            iceServers = emptyList(),
        )
        val managerC = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildPeerConnectionFactory(),
            myPubkeyHex = pubkeyC,
            signalling = signallingC,
            iceServers = emptyList(),
        )

        // Only A ever calls addPeer -- B and C should never be told about each
        // other directly by this test.
        managerA.addPeer(pubkeyB)
        managerA.addPeer(pubkeyC)

        assertTrue(
            "A never connected to B",
            awaitTrue(timeoutMs = 30_000) { pubkeyB in managerA.peers.value })
        assertTrue(
            "A never connected to C",
            awaitTrue(timeoutMs = 30_000) { pubkeyC in managerA.peers.value })

        // The actual point of this test: B and C reach each other purely through
        // A's gossip, with no addPeer() ever called between them.
        assertTrue(
            "B never connected to C via gossip",
            awaitTrue(timeoutMs = 30_000) { pubkeyC in managerB.peers.value },
        )
        assertTrue(
            "C never connected to B via gossip",
            awaitTrue(timeoutMs = 30_000) { pubkeyB in managerC.peers.value },
        )

        managerA.close()
        managerB.close()
        managerC.close()
    }

    @Test
    fun peerLearnedThroughGossipConnectsAndExchangesAppMessage() = runBlocking {
        val parentScope = CoroutineScope(SupervisorJob())
        val peerConnectionFactory = buildPeerConnectionFactory()
        val bus = LoopbackSignallingBus()

        val signallingA = bus.createClient(pubkeyA)
        val signallingB = bus.createClient(pubkeyB)
        val signallingC = bus.createClient(pubkeyC)

        val managerA = newManager(
            parentScope = parentScope,
            factory = peerConnectionFactory,
            pubkey = pubkeyA,
            signalling = signallingA,
        )
        val managerB = newManager(
            parentScope = parentScope,
            factory = peerConnectionFactory,
            pubkey = pubkeyB,
            signalling = signallingB,
        )
        val managerC = newManager(
            parentScope = parentScope,
            factory = peerConnectionFactory,
            pubkey = pubkeyC,
            signalling = signallingC,
        )

        val receivedByC = CopyOnWriteArrayList<PeerMessage>()
        val collectorC = launch {
            managerC.incomingMessages.collect { receivedByC += it }
        }

        try {
            // Establish A-B first. This makes the later A-C connection produce
            // an unambiguous A -> B Announce(C), rather than relying on which of
            // two concurrent handshakes happens to finish first.
            managerA.addPeer(pubkeyB)

            assertTrue(
                "A never connected to B",
                awaitTrue { pubkeyB in managerA.peers.value },
            )
            assertTrue(
                "B never connected to A",
                awaitTrue { pubkeyA in managerB.peers.value },
            )

            // Only A is explicitly told about C. Neither B.addPeer(C) nor
            // C.addPeer(B) is called anywhere in this test.
            managerA.addPeer(pubkeyC)

            assertTrue(
                "A never connected to C",
                awaitTrue { pubkeyC in managerA.peers.value },
            )
            assertTrue(
                "C never connected to A",
                awaitTrue { pubkeyA in managerC.peers.value },
            )

            // The core gossip assertion: B learned C from A's Announce frame
            // and consequently sent C an SDP offer itself.
            assertTrue(
                "B never initiated a connection to C after learning C via gossip",
                awaitTrue {
                    signallingB.sentOfferTo(pubkeyC)
                },
            )

            assertTrue(
                "B never connected to C through gossip",
                awaitTrue { pubkeyC in managerB.peers.value },
            )
            assertTrue(
                "C never connected to B through gossip",
                awaitTrue { pubkeyB in managerC.peers.value },
            )

            assertEquals(
                setOf(pubkeyB, pubkeyC),
                managerA.peers.value,
            )
            assertEquals(
                setOf(pubkeyA, pubkeyC),
                managerB.peers.value,
            )
            assertEquals(
                setOf(pubkeyA, pubkeyB),
                managerC.peers.value,
            )

            // Prove that the gossip-created B-C entry is a usable application
            // connection, not merely stale state in the peers StateFlow.
            val payload = byteArrayOf(0, 1, 127, -128, -1)
            val sendResult = managerB.sendMessage(pubkeyC, payload)

            assertTrue(
                "B failed to send an app message to gossip-discovered C: " +
                    sendResult.exceptionOrNull()?.message,
                sendResult.isSuccess,
            )
            assertTrue(
                "C never received B's app message over the gossip-created link",
                awaitTrue {
                    receivedByC.any { message ->
                        message.fromPubkeyHex == pubkeyB &&
                            message.bytes.contentEquals(payload)
                    }
                },
            )
        } finally {
            collectorC.cancel()
            managerA.close()
            managerB.close()
            managerC.close()
            parentScope.cancel()
            peerConnectionFactory.dispose()
        }
    }

    private fun newManager(
        parentScope: CoroutineScope,
        factory: PeerConnectionFactory,
        pubkey: String,
        signalling: LoopbackSignallingBusClient,
    ): LiveConnectionManager =
        LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = factory,
            myPubkeyHex = pubkey,
            signalling = signalling,
            iceServers = emptyList(),
        )

    private fun LoopbackSignallingBusClient.sentOfferTo(targetPubkey: String): Boolean =
        sentMessages.any { (target, plaintext) ->
            target == targetPubkey &&
                runCatching {
                    Json.decodeFromString<SessionDescriptionData>(plaintext).type == SdpType.OFFER
                }.getOrDefault(false)
        }
}
