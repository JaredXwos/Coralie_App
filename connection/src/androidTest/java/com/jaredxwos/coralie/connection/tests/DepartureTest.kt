package com.jaredxwos.coralie.connection.tests

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jaredxwos.coralie.connection.awaitTrue
import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import com.jaredxwos.coralie.connection.testClients.LoopbackSignallingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory

/**
 * Departure detection (§6.12): after A and B connect, A closes. B should notice
 * independently — purely from its own transport-level state observation, with
 * nothing telling it out of band — and drop A from `connected`.
 *
 * The underlying WebRTC mechanics here (one side's PeerConnection/DataChannel
 * closing surfaces as Closed on the other) were already validated at the
 * transport layer by LoopbackConnectionTest's closingOneSide_surfacesAsClosedOnTheOther
 * test, which saw this happen within single-digit milliseconds. What this test
 * actually exercises is one level up: whether watchLinkState correctly reacts to
 * that transition by removing the peer from `connected`, not whether the
 * transition itself occurs.
 */
@RunWith(AndroidJUnit4::class)
class DepartureTest {

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
    fun closingOneManagerRemovesItFromTheOtherSidesConnectedSet() = runBlocking {
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