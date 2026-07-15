package com.jaredxwos.coralie.connection.tests

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jaredxwos.coralie.connection.LoopbackSignallingBus
import com.jaredxwos.coralie.connection.awaitTrue
import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory

/**
 * Gossip mesh formation (§6.2/§6.9): A connects directly to B and to C (two
 * explicit addPeer() calls, in that order). B and C never call addPeer() on
 * each other — the B-C link is expected to form entirely through gossip: once
 * A is connected to both, whichever connection completes second triggers an
 * Announce to the other, which self-initiates on hearing about a pubkey it was
 * never told about by anything except that Announce.
 *
 * Which of A-B or A-C actually finishes first isn't something this test assumes
 * or depends on — both are independent real negotiations running concurrently,
 * so either order is fine; the assertions just poll for the eventual full-mesh
 * state regardless of which one gossiped the other.
 */
@RunWith(AndroidJUnit4::class)
class GossipMeshTest {

    private val pubkeyA = "pubkey-a"
    private val pubkeyB = "pubkey-b"
    private val pubkeyC = "pubkey-c"

    private fun buildFactory(): PeerConnectionFactory {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        return PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    @Test
    fun aConnectingToBAndCCausesBAndCToSelfConnectViaGossip() = runBlocking {
        val bus = LoopbackSignallingBus()
        val signallingA = bus.createClient(pubkeyA)
        val signallingB = bus.createClient(pubkeyB)
        val signallingC = bus.createClient(pubkeyC)

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
        val managerC = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildFactory(),
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

}