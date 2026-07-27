package com.jaredxwos.coralie.connection.tests

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jaredxwos.coralie.connection.LoopbackSignallingBus
import com.jaredxwos.coralie.connection.LoopbackSignallingBusClient
import com.jaredxwos.coralie.connection.awaitTrue
import com.jaredxwos.coralie.connection.externalMessages.PeerMessage
import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
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

/**
 * Verifies app-to-app gossip at the production connection-manager boundary.
 *
 * The current protocol does not relay application payloads through an
 * intermediate peer. It gossips [com.jaredxwos.coralie.connection.DataChannelFrame.Announce]
 * frames so that newly learned peers establish their own direct WebRTC link.
 * This test therefore verifies the complete behaviour:
 *
 * 1. A connects directly to B.
 * 2. A then connects directly to C.
 * 3. A announces C to B over the existing A-B data channel.
 * 4. B initiates a B-C connection without the test calling B.addPeer(C).
 * 5. B and C exchange an application payload over that gossip-created link.
 *
 * Signalling is kept in-process and deterministic with [LoopbackSignallingBus],
 * while all three data-channel connections use the real Android WebRTC stack.
 */
@RunWith(AndroidJUnit4::class)
class AppToAppGossipTest {

    private val pubkeyA = "11".repeat(32)
    private val pubkeyB = "22".repeat(32)
    private val pubkeyC = "33".repeat(32)

    @Test
    fun peerLearnedThroughGossipConnectsAndExchangesAppMessage() = runBlocking {
        val parentScope = CoroutineScope(SupervisorJob())
        val peerConnectionFactory = buildFactory()
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

    private fun buildFactory(): PeerConnectionFactory {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context)
                .createInitializationOptions(),
        )
        return PeerConnectionFactory
            .builder()
            .createPeerConnectionFactory()
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

    private fun LoopbackSignallingBusClient.sentOfferTo(
        targetPubkey: String,
    ): Boolean =
        sentMessages.any { (target, plaintext) ->
            target == targetPubkey &&
                runCatching {
                    Json.decodeFromString<SessionDescriptionData>(plaintext).type ==
                        SdpType.OFFER
                }.getOrDefault(false)
        }
}
