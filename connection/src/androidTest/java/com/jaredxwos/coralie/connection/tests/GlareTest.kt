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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Both peers initiate simultaneously. The connection manager resolves same-peer
 * glare deterministically: the lexicographically smaller pubkey remains the
 * initiator and the larger pubkey abandons its local attempt and answers.
 */
@RunWith(AndroidJUnit4::class)
class GlareTest {

    private val pubkeyA = "pubkey-a"
    private val pubkeyB = "pubkey-b"

    private fun buildFactory(): PeerConnectionFactory {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(),
        )
        return PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    @Test
    fun simultaneousMutualAddPeerResolvesToOneConnection() = runBlocking {
        val (signallingA, signallingB) = LoopbackSignallingClient.pair(pubkeyA, pubkeyB)
        val parentScope = CoroutineScope(SupervisorJob())

        val managerA = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildFactory(),
            myPubkeyHex = pubkeyA,
            signalling = signallingA,
            iceServers = emptyList(),
            handshakeTimeout = 2.seconds,
            handshakeTickInterval = 500.milliseconds,
            maxInitiationAttempts = 3,
        )
        val managerB = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildFactory(),
            myPubkeyHex = pubkeyB,
            signalling = signallingB,
            iceServers = emptyList(),
            handshakeTimeout = 2.seconds,
            handshakeTickInterval = 500.milliseconds,
            maxInitiationAttempts = 3,
        )

        try {
            managerA.addPeer(pubkeyB)
            managerB.addPeer(pubkeyA)

            assertTrue(
                "A never connected to B after glare resolution",
                awaitTrue(timeoutMs = 30_000) { pubkeyB in managerA.peers.value },
            )
            assertTrue(
                "B never connected to A after glare resolution",
                awaitTrue(timeoutMs = 30_000) { pubkeyA in managerB.peers.value },
            )

            assertTrue(
                "The glare-resolved A-to-B link is not usable",
                managerA.sendMessage(pubkeyB, "glare-ok".encodeToByteArray()).isSuccess,
            )
        } finally {
            managerA.close()
            managerB.close()
        }
    }
}
