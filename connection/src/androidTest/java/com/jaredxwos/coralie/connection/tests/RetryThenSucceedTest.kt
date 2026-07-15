package com.jaredxwos.coralie.connection.tests

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jaredxwos.coralie.connection.awaitTrue
import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import com.jaredxwos.coralie.connection.testClients.DropFirstNSignallingClient
import com.jaredxwos.coralie.connection.testClients.LoopbackSignallingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Retry-then-succeed — the other half of §6.7 not covered by
 * LiveConnectionManagerRetryExhaustionTest. That test covers every attempt
 * failing; this one needs the first attempt to time out but the second to
 * actually connect, which is a genuinely different code path: the retry branch
 * that creates a fresh Initiator and recovers, rather than the one that exhausts
 * maxInitiationAttempts and gives up.
 *
 * A's first Offer to B is dropped at the signalling layer (DropFirstNSignallingClient)
 * rather than B being slow to answer — this deterministically forces exactly one
 * timeout rather than depending on real timing variance to produce one.
 */
@RunWith(AndroidJUnit4::class)
class RetryThenSucceedTest {

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
    fun firstAttemptTimesOutSecondAttemptConnects() = runBlocking {
        val (rawSignallingA, signallingB) = LoopbackSignallingClient.pair(pubkeyA, pubkeyB)
        val signallingA =
            DropFirstNSignallingClient(rawSignallingA, targetPubkeyHex = pubkeyB, dropCount = 1)
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
        )

        managerA.addPeer(pubkeyB)

        assertTrue(
            "A never connected to B on retry",
            awaitTrue(timeoutMs = 20_000) { pubkeyB in managerA.peers.value },
        )
        assertTrue("B never connected to A", awaitTrue(timeoutMs = 5_000) { pubkeyA in managerB.peers.value })

        // Confirms it actually retried rather than connecting on the very first
        // attempt -- one dropped, one delivered.
        val offerAttempts = signallingA.totalSendAttempts.count { it.first == pubkeyB }
        assertEquals(2, offerAttempts)

        managerA.close()
        managerB.close()
    }
}