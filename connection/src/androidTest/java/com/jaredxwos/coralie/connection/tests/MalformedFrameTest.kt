package com.jaredxwos.coralie.connection.tests

import com.jaredxwos.coralie.connection.DataChannelFrame
import com.jaredxwos.coralie.connection.LoopbackSignallingBus
import com.jaredxwos.coralie.connection.awaitTrue
import com.jaredxwos.coralie.connection.externalMessages.PeerMessage
import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import com.jaredxwos.coralie.transport.LinkState
import com.jaredxwos.coralie.transport.SdpType
import com.jaredxwos.coralie.transport.SessionDescriptionData
import com.jaredxwos.coralie.transport.context.getInitiator
import kotlin.time.Duration.Companion.milliseconds

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * §6.11: a malformed data-channel frame is logged and dropped, not treated as a
 * disconnect or a crash, and the link stays fully usable afterward.
 *
 * Sending genuinely malformed bytes requires bypassing LiveConnectionManager's
 * own encoding entirely (sendMessage() always wraps in a valid DataChannelFrame.App,
 * so there's no way to send real garbage through the public API). Rather than
 * reaching into the manager's private state, this drives a raw Initiator directly
 * against a real LiveConnectionManager (B) -- C connects to B exactly like a normal
 * peer would (a real offer/answer exchange over the same LoopbackSignallingBus),
 * but C is never wrapped in its own LiveConnectionManager, so the test can call
 * PeerLink.send() on it directly with arbitrary bytes.
 */
@RunWith(AndroidJUnit4::class)
class MalformedFrameTest {

    private val pubkeyB = "pubkey-b"
    private val pubkeyC = "pubkey-c"

    private fun buildFactory(): PeerConnectionFactory {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        return PeerConnectionFactory.builder().createPeerConnectionFactory()
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
            peerConnectionFactory = buildFactory(),
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
            factory = buildFactory(),
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
}