package com.jaredxwos.coralie.connection.tests

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jaredxwos.coralie.connection.DataChannelFrame
import com.jaredxwos.coralie.connection.awaitTrue
import com.jaredxwos.coralie.connection.buildPeerConnectionFactory
import com.jaredxwos.coralie.connection.externalMessages.PeerMessage
import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import com.jaredxwos.coralie.connection.testClients.LoopbackSignallingBus
import com.jaredxwos.coralie.connection.testClients.LoopbackSignallingClient
import com.jaredxwos.coralie.transport.LinkState
import com.jaredxwos.coralie.transport.SdpType
import com.jaredxwos.coralie.transport.SessionDescriptionData
import com.jaredxwos.coralie.transport.context.getInitiator
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Duplicate, malformed, and simultaneous-offer handling. */
@RunWith(AndroidJUnit4::class)
class ConnectionManagerOfferHandlingTest {
    private val pubkeyA = "pubkey-a"
    private val pubkeyB = "pubkey-b"
    private val pubkeyC = "pubkey-c"

    @Test
    fun duplicateOfferFromAnAlreadyConnectedPeerIsIgnored() = runBlocking {
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

        // Confirm the connection is genuinely usable before poking at it.
        val bytes = "hello".encodeToByteArray()
        assertTrue(managerA.sendMessage(pubkeyB, bytes).isSuccess)

        // Simulate B mistakenly re-offering -- A should drop this outright.
        val bogusOffer = Json.encodeToString(
            SessionDescriptionData(type = SdpType.OFFER, sdp = "bogus-duplicate-offer-sdp")
        )
        signallingA.deliverInbound(pubkeyB, bogusOffer)

        // Give it a moment to (not) do anything -- the guard is a synchronous,
        // immediate check, so this only needs to be long enough for the inbound
        // channel to be drained and processed, not anywhere near a full timeout.
        delay(1.seconds)

        assertTrue("A should still show B as connected", pubkeyB in managerA.peers.value)
        // The existing connection should still be genuinely usable, not silently
        // broken by whatever the bogus Offer triggered (if anything).
        assertTrue(managerA.sendMessage(pubkeyB, bytes).isSuccess)

        managerA.close()
        managerB.close()
    }

    @Test
    fun malformedFrameIsLoggedAndDroppedWithoutBreakingTheLink() = runBlocking {
        val bus = LoopbackSignallingBus()
        val signallingB = bus.createClient(pubkeyB)
        val signallingC = bus.createClient(pubkeyC)
        val parentScope = CoroutineScope(SupervisorJob())

        val loggedWarnings = CopyOnWriteArrayList<String>()
        val managerB = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildPeerConnectionFactory(),
            myPubkeyHex = pubkeyB,
            signalling = signallingB,
            iceServers = emptyList(),
            logWarning = { message, _ -> loggedWarnings += message },
        )

        val receivedByB = CopyOnWriteArrayList<PeerMessage>()
        val collectJobB = launch { managerB.incomingMessages.collect { receivedByB += it } }

        // Manually drive a raw Initiator from C to B -- same protocol a real
        // LiveConnectionManager would use, just not wrapped in one, so the test
        // can call PeerLink.send() on it directly afterward.
        val initiatorC = getInitiator(
            factory = buildPeerConnectionFactory(),
            iceServers = emptyList(),
            parentScope = parentScope,
        )
        val offer = initiatorC.createOffer()
        signallingC.send(pubkeyB, Json.encodeToString(offer))

        val answerMsg = withTimeoutOrNull(15_000.milliseconds) { signallingC.inbound.receive() }
        assertNotNull("C never received an answer from B", answerMsg)
        val encodedAnswer = answerMsg!!.plaintext
        val encodedType = Json.parseToJsonElement(encodedAnswer)
            .jsonObject
            .getValue("type")
            .jsonPrimitive
            .content
        assertEquals("answer", encodedType)

        val answer = Json.decodeFromString<SessionDescriptionData>(encodedAnswer)
        assertEquals(SdpType.ANSWER, answer.type)
        initiatorC.acceptAnswer(answer)

        assertTrue(
            "C's raw link to B never connected",
            awaitTrue(timeoutMs = 15_000) { initiatorC.state.value == LinkState.Connected },
        )
        assertTrue("B never saw C as connected", awaitTrue { pubkeyC in managerB.peers.value })

        // The actual test: bytes that aren't a valid DataChannelFrame at all.
        initiatorC.send("this is not json".encodeToByteArray())

        assertTrue(
            "B never logged a warning for the malformed frame",
            awaitTrue(timeoutMs = 5_000) { loggedWarnings.isNotEmpty() },
        )

        // The link must still be alive and usable, not silently broken.
        assertTrue("B dropped C from connected after a malformed frame", pubkeyC in managerB.peers.value)

        val validFrame = Json.encodeToString<DataChannelFrame>(
            DataChannelFrame.App("hello after garbage".encodeToByteArray())
        )
        initiatorC.send(validFrame.encodeToByteArray())

        assertTrue(
            "B never received the valid message sent right after the malformed one",
            awaitTrue(timeoutMs = 10_000) { receivedByB.isNotEmpty() },
        )
        assertEquals(pubkeyC, receivedByB.first().fromPubkeyHex)
        assertTrue("hello after garbage".encodeToByteArray().contentEquals(receivedByB.first().bytes))

        collectJobB.cancel()
        initiatorC.close()
        managerB.close()
    }

    @Test
    fun simultaneousMutualAddPeerRetriesThenExhaustsWithoutRoleSwitching() = runBlocking {
        val (signallingA, signallingB) = LoopbackSignallingClient.pair(pubkeyA, pubkeyB)
        val parentScope = CoroutineScope(SupervisorJob())
        val maxAttempts = 3

        val managerA = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildPeerConnectionFactory(),
            myPubkeyHex = pubkeyA,
            signalling = signallingA,
            iceServers = emptyList(),
            handshakeTimeout = 2.seconds,
            handshakeTickInterval = 500.milliseconds,
            maxInitiationAttempts = maxAttempts,
        )
        val managerB = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildPeerConnectionFactory(),
            myPubkeyHex = pubkeyB,
            signalling = signallingB,
            iceServers = emptyList(),
            handshakeTimeout = 2.seconds,
            handshakeTickInterval = 500.milliseconds,
            maxInitiationAttempts = maxAttempts,
        )

        // Both peers initiate the same pair. Same-peer offers are ignored rather
        // than switching roles, so both sides eventually exhaust their retries.
        managerA.addPeer(pubkeyB)
        managerB.addPeer(pubkeyA)

        // Both run concurrently in real time regardless of the order awaited here,
        // so this isn't 2x the wait -- by the time A's failure arrives, B's has
        // very likely already happened too.
        val failureA = withTimeoutOrNull(30_000.milliseconds) { managerA.terminalFailures.receive() }
        val failureB = withTimeoutOrNull(30_000.milliseconds) { managerB.terminalFailures.receive() }

        assertNotNull("A never gave up on B", failureA)
        assertNotNull("B never gave up on A", failureB)
        assertEquals(pubkeyB, failureA!!.pubkeyHex)
        assertEquals(pubkeyA, failureB!!.pubkeyHex)
        assertEquals(maxAttempts, failureA.attemptsMade)
        assertEquals(maxAttempts, failureB.attemptsMade)

        // Neither side connects, but both terminate cleanly instead of hanging.
        assertTrue(managerA.peers.value.isEmpty())
        assertTrue(managerB.peers.value.isEmpty())

        managerA.close()
        managerB.close()
    }
}
