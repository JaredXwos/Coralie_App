package com.jaredxwos.coralie.connection.tests

import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jaredxwos.coralie.connection.testClients.FakeNostrSignallingClient
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
 * Retry exhaustion (§6.5/§6.7): nobody ever answers, so every attempt times out.
 * Verifies the manager retries up to maxInitiationAttempts times — a fresh
 * Initiator and a fresh Offer each time — then emits exactly one TerminalFailure
 * carrying the right attempt count.
 *
 * handshakeTimeout/handshakeTickInterval/maxInitiationAttempts are all shortened
 * from production defaults purely for test speed. Real time still has to elapse
 * (runBlocking, no virtual clock to fast-forward) — worst case here is roughly
 * maxInitiationAttempts * handshakeTimeout, not something a test scheduler could
 * skip through. Gathering time itself doesn't eat into that budget, since
 * LiveRtcContext only starts the handshakeTimeout clock (AwaitingRemoteDescription's
 * `since`) after gatheringComplete resolves, not before.
 *
 * terminalFailures is a Channel, not a SharedFlow like incomingMessages — channels
 * buffer, so there's no subscription-timing race to guard against here the way
 * the loopback test had to for message delivery. receive() gets whatever's already
 * queued even if it arrived before the call.
 */
@RunWith(AndroidJUnit4::class)
class RetryExhaustionTest {

    private val myPubkeyHex = "self-pubkey-hex"
    private val peerPubkeyHex = "unreachable-peer-pubkey-hex"

    private fun buildFactory(): PeerConnectionFactory {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        return PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    @Test
    fun addPeerRetriesThenGivesUpWhenNobodyEverAnswers() = runBlocking {
        val signalling = FakeNostrSignallingClient()
        val parentScope = CoroutineScope(SupervisorJob())
        val maxAttempts = 3

        val manager = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildFactory(),
            myPubkeyHex = myPubkeyHex,
            signalling = signalling,
            iceServers = emptyList(),
            handshakeTimeout = 2.seconds,
            handshakeTickInterval = 500.milliseconds,
            maxInitiationAttempts = maxAttempts,
        )

        manager.addPeer(peerPubkeyHex)

        val terminalFailure = withTimeoutOrNull(30_000.milliseconds) { manager.terminalFailures.receive() }
        assertNotNull("expected a TerminalFailure within the timeout", terminalFailure)
        assertEquals(peerPubkeyHex, terminalFailure!!.pubkeyHex)
        assertEquals(maxAttempts, terminalFailure.attemptsMade)

        // One Offer per attempt -- confirms it actually retried, not just gave up
        // after the first timeout.
        val offersSent = signalling.sentMessages.count { it.first == peerPubkeyHex }
        assertEquals(maxAttempts, offersSent)

        assertTrue(manager.peers.value.isEmpty())

        manager.close()
    }
}