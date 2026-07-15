package com.jaredxwos.coralie.connection.tests

import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import com.jaredxwos.coralie.connection.testClients.LoopbackSignallingClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Glare (§6.13): both sides addPeer() each other in the same window, landing in
 * each other's `initiating` before either's inbound Offer can arrive. The global
 * open/closed gate (§6.3 guard 2) means neither side ever answers the other's
 * Offer — both get dropped — so both sides independently retry-then-exhaust,
 * exactly like the one-sided retry-exhaustion test, just symmetric. This is the
 * scenario the whole "no per-key tie-break, just a global gate" design choice
 * exists to resolve — never actually exercised until now.
 *
 * Timing note: addPeer() lands in `initiating` via a synchronous, non-suspend
 * critical section — calling it on both managers back-to-back is reliably faster
 * than the real gathering + signalling round-trip either Offer needs to actually
 * arrive at the other side, so true glare should occur with no extra
 * synchronization. If that assumption were ever wrong, this test fails loudly
 * (a normal one-sided connect wouldn't satisfy "both sides hit TerminalFailure,
 * neither ever connects") rather than silently passing on the wrong scenario.
 *
 * The gate itself doesn't have a gap during a retry to sneak an Offer through
 * either — onAttemptFailed's retry branch overwrites the `initiating` entry
 * rather than removing then reinserting it, and getInitiator() is synchronous
 * (gathering happens later, inside createOffer()'s suspend boundary) — so the
 * gate stays shut continuously across every attempt on both sides.
 */
@RunWith(AndroidJUnit4::class)
class GlareTest {

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
    fun simultaneousMutualAddPeerResolvesToBothSidesGivingUpCleanly() = runBlocking {
        val (signallingA, signallingB) = LoopbackSignallingClient.pair(pubkeyA, pubkeyB)
        val parentScope = CoroutineScope(SupervisorJob())
        val maxAttempts = 3

        val managerA = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildFactory(),
            myPubkeyHex = pubkeyA,
            signalling = signallingA,
            iceServers = emptyList(),
            handshakeTimeout = 2.seconds,
            handshakeTickInterval = 500.milliseconds,
            maxInitiationAttempts = maxAttempts,
        )
        val managerB = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildFactory(),
            myPubkeyHex = pubkeyB,
            signalling = signallingB,
            iceServers = emptyList(),
            handshakeTimeout = 2.seconds,
            handshakeTickInterval = 500.milliseconds,
            maxInitiationAttempts = maxAttempts,
        )

        // Both add each other in the same window -- see class doc for why this
        // reliably produces true glare rather than an ordinary one-sided connect.
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

        // Neither side ever connected -- glare resolved to a clean double
        // give-up, not a hang, not a duplicate link, not a one-sided success.
        assertTrue(managerA.peers.value.isEmpty())
        assertTrue(managerB.peers.value.isEmpty())

        managerA.close()
        managerB.close()
    }
}