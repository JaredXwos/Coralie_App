package com.jaredxwos.coralie.connection.tests

import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import com.jaredxwos.coralie.connection.testClients.LoopbackSignallingClient
import com.jaredxwos.coralie.transport.SdpType
import com.jaredxwos.coralie.transport.SessionDescriptionData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jaredxwos.coralie.connection.awaitTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory
import kotlin.time.Duration.Companion.seconds

/**
 * §6.3 guard 1: an inbound Offer from a pubkey already in `connected` is dropped
 * unconditionally, before anything else runs — covers a plausible real scenario
 * (B thinks the link died and re-offers) that's supposed to be a no-op on A's side.
 *
 * This is a negative assertion ("nothing should happen"), which needs a different
 * shape than the polling pattern used elsewhere: inject the duplicate Offer, wait
 * briefly, then confirm the existing connection is untouched rather than polling
 * for some new state to appear — there's no positive condition to poll for when
 * proving an absence.
 *
 * The injected Offer's SDP is deliberately garbage ("bogus-duplicate-offer-sdp"),
 * not a well-formed one. If the guard were ever bypassed, processing it would very
 * likely throw when converting to a native SDP object — meaning a broken guard
 * fails this test loudly (an exception or a corrupted connection) rather than
 * silently passing regardless of whether the guard actually ran.
 */
@RunWith(AndroidJUnit4::class)
class DuplicateOfferTest {

    private val pubkeyA = "pubkey-a"
    private val pubkeyB = "pubkey-b"

    private fun buildFactory(): PeerConnectionFactory {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        return PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    @Test
    fun duplicateOfferFromAnAlreadyConnectedPeerIsIgnored() = runBlocking {
        val (signallingA, signallingB) = LoopbackSignallingClient.pair(pubkeyA, pubkeyB)
        val parentScope = CoroutineScope(SupervisorJob())

        val managerA = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildFactory(),
            myPubkeyHex = pubkeyA,
            signalling = signallingA,
            iceServers = emptyList(),
        )
        val managerB = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildFactory(),
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
}