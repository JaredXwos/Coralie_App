package com.jaredxwos.coralie.connection.tests

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jaredxwos.coralie.connection.awaitCondition
import com.jaredxwos.coralie.connection.awaitTrue
import com.jaredxwos.coralie.connection.buildPeerConnectionFactory
import com.jaredxwos.coralie.connection.externalMessages.PeerMessage
import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import com.jaredxwos.coralie.connection.testClients.FakeNostrSignallingClient
import com.jaredxwos.coralie.connection.testClients.LoopbackSignallingClient
import com.jaredxwos.coralie.transport.SdpType
import com.jaredxwos.coralie.transport.SessionDescriptionData
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Basic lifecycle, handshake, direct messaging, and guard-clause behaviour. */
@RunWith(AndroidJUnit4::class)
class ConnectionManagerCoreTest {
    private val pubkeyA = "pubkey-a"
    private val pubkeyB = "pubkey-b"
    private val myPubkeyHex = "self-pubkey-hex"
    private val peerPubkeyHex = "peer-pubkey-hex"

    private fun TestScope.buildManager(signalling: FakeNostrSignallingClient) =
        LiveConnectionManager(
            parentScope = this,
            peerConnectionFactory = buildPeerConnectionFactory(),
            myPubkeyHex = myPubkeyHex,
            signalling = signalling,
            iceServers = emptyList(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun peersStartsEmptyAndSignallingIsStartedOnConstruction() = runTest {
        val signalling = FakeNostrSignallingClient()
        val manager = buildManager(signalling)
        runCurrent()

        assertTrue(manager.peers.value.isEmpty())
        assertTrue(signalling.started)

        manager.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun addPeerWithMyOwnPubkeyIsANoOp() = runTest {
        val signalling = FakeNostrSignallingClient()
        val manager = buildManager(signalling)

        manager.addPeer(myPubkeyHex)
        runCurrent()

        assertTrue(manager.peers.value.isEmpty())
        assertEquals(0, signalling.sentMessages.size) // never even tried to offer to itself

        manager.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sendMessageToAnUnconnectedPeerFailsWithoutTouchingTransport() = runTest {
        val signalling = FakeNostrSignallingClient()
        val manager = buildManager(signalling)

        val result = manager.sendMessage("some-other-pubkey", "hello".encodeToByteArray())
        runCurrent()

        assertTrue(result.isFailure)

        manager.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun closeStopsTheSignallingClient() = runTest {
        val signalling = FakeNostrSignallingClient()
        val manager = buildManager(signalling)
        runCurrent()

        manager.close()

        assertTrue(signalling.closed)
    }

    @Test
    fun addPeerSendsARealOfferAndDoesNotConnectWithoutAnAnswer() = runBlocking {
        val signalling = FakeNostrSignallingClient()
        val parentScope = CoroutineScope(SupervisorJob())
        val manager = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildPeerConnectionFactory(),
            myPubkeyHex = myPubkeyHex,
            signalling = signalling,
            iceServers = emptyList(),
        )

        manager.addPeer(peerPubkeyHex)

        val sentOffer = awaitCondition {
            signalling.sentMessages.firstOrNull { it.first == peerPubkeyHex }
        }
        assertNotNull("expected an Offer to peerPubkeyHex within the timeout", sentOffer)

        val encodedOffer = sentOffer!!.second
        val encodedType = Json.parseToJsonElement(encodedOffer)
            .jsonObject
            .getValue("type")
            .jsonPrimitive
            .content
        assertEquals("offer", encodedType)

        val decoded = Json.decodeFromString<SessionDescriptionData>(encodedOffer)
        assertEquals(SdpType.OFFER, decoded.type)
        assertTrue(decoded.sdp.isNotBlank())

        // No peer ever answered, so the manager should still show nobody connected.
        assertTrue(manager.peers.value.isEmpty())

        manager.close()
    }

    @Test
    fun addPeerCompletesFullHandshakeAndExchangesMessagesBothWays() = runBlocking {
        val (signallingA, signallingB) = LoopbackSignallingClient.pair(pubkeyA, pubkeyB)
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

        // Subscribed before anything can possibly be sent -- see class doc.
        val receivedByA = CopyOnWriteArrayList<PeerMessage>()
        val receivedByB = CopyOnWriteArrayList<PeerMessage>()
        val collectJobA = launch { managerA.incomingMessages.collect { receivedByA += it } }
        val collectJobB = launch { managerB.incomingMessages.collect { receivedByB += it } }

        managerA.addPeer(pubkeyB)

        assertTrue("A never saw B as connected", awaitTrue { pubkeyB in managerA.peers.value })
        assertTrue("B never saw A as connected", awaitTrue { pubkeyA in managerB.peers.value })

        // Include the full signed-byte boundary used by Kotlin ByteArray JSON.
        // The browser sends 128..255 as -128..-1 and reconstructs them with
        // `value & 0xff` after decoding.
        val bytesAtoB = byteArrayOf(0, 1, 127, -128, -1)
        assertTrue(managerA.sendMessage(pubkeyB, bytesAtoB).isSuccess)
        assertTrue("B never received A's message", awaitTrue { receivedByB.isNotEmpty() })
        assertEquals(pubkeyA, receivedByB.first().fromPubkeyHex)
        assertTrue(bytesAtoB.contentEquals(receivedByB.first().bytes))

        val bytesBtoA = byteArrayOf(-1, -128, 127, 1, 0)
        assertTrue(managerB.sendMessage(pubkeyA, bytesBtoA).isSuccess)
        assertTrue("A never received B's reply", awaitTrue { receivedByA.isNotEmpty() })
        assertEquals(pubkeyB, receivedByA.first().fromPubkeyHex)
        assertTrue(bytesBtoA.contentEquals(receivedByA.first().bytes))

        collectJobA.cancel()
        collectJobB.cancel()
        managerA.close()
        managerB.close()
    }

    @Test
    fun closingOneManagerRemovesItFromTheOtherSidesConnectedSet() = runBlocking {
        val (signallingA, signallingB) = LoopbackSignallingClient.pair(pubkeyA, pubkeyB)
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

        managerA.addPeer(pubkeyB)

        assertTrue("A never connected to B", awaitTrue { pubkeyB in managerA.peers.value })
        assertTrue("B never connected to A", awaitTrue { pubkeyA in managerB.peers.value })

        // Simulate A going away -- B is told nothing out of band, and should
        // notice purely from its own transport-level observation.
        managerA.close()

        assertTrue(
            "B never noticed A's departure",
            awaitTrue(timeoutMs = 15_000) { pubkeyA !in managerB.peers.value },
        )

        managerB.close()
    }
}
