package com.jaredxwos.coralie.connection.tests

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jaredxwos.coralie.connection.awaitTrue
import com.jaredxwos.coralie.connection.buildPeerConnectionFactory
import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import com.jaredxwos.coralie.connection.testClients.DelayFirstNSignallingClient
import com.jaredxwos.coralie.connection.testClients.DropFirstNSignallingClient
import com.jaredxwos.coralie.connection.testClients.FakeNostrSignallingClient
import com.jaredxwos.coralie.connection.testClients.LoopbackSignallingBus
import com.jaredxwos.coralie.connection.testClients.LoopbackSignallingClient
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Immediate send rejection, timeout retries, recovery, and stale-answer safety. */
@RunWith(AndroidJUnit4::class)
class ConnectionManagerRetryTest {
    private val pubkeyA = "pubkey-a"
    private val pubkeyB = "pubkey-b"
    private val myPubkeyHex = "self-pubkey-hex"
    private val peerPubkeyHex = "unreachable-peer-pubkey-hex"

    @Test
    fun rejectedSignallingSendCountsAsAFailedAttempt() = runBlocking {
        val signalling = FakeNostrSignallingClient(sendResult = false) // every send() is rejected
        val parentScope = CoroutineScope(SupervisorJob())
        val maxAttempts = 2

        val manager = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildPeerConnectionFactory(),
            myPubkeyHex = myPubkeyHex,
            signalling = signalling,
            iceServers = emptyList(),
            maxInitiationAttempts = maxAttempts,
        )

        manager.addPeer(peerPubkeyHex)

        val terminalFailure = withTimeoutOrNull(15_000.milliseconds) { manager.terminalFailures.receive() }
        assertNotNull("expected a TerminalFailure", terminalFailure)
        assertEquals(peerPubkeyHex, terminalFailure!!.pubkeyHex)
        assertEquals(maxAttempts, terminalFailure.attemptsMade)

        manager.close()
    }

    @Test
    fun addPeerRetriesThenGivesUpWhenNobodyEverAnswers() = runBlocking {
        val signalling = FakeNostrSignallingClient()
        val parentScope = CoroutineScope(SupervisorJob())
        val maxAttempts = 3

        val manager = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildPeerConnectionFactory(),
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

    @Test
    fun firstAttemptTimesOutSecondAttemptConnects() = runBlocking {
        val (rawSignallingA, signallingB) = LoopbackSignallingClient.pair(pubkeyA, pubkeyB)
        val signallingA =
            DropFirstNSignallingClient(rawSignallingA, targetPubkeyHex = pubkeyB, dropCount = 1)
        val parentScope = CoroutineScope(SupervisorJob())

        val managerA = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildPeerConnectionFactory(),
            myPubkeyHex = pubkeyA,
            signalling = signallingA,
            iceServers = emptyList(),
            handshakeTimeout = 2.seconds,
            handshakeTickInterval = 500.milliseconds,
            maxInitiationAttempts = 3,
        )
        val managerB = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildPeerConnectionFactory(),
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

    @Test
    fun lateAnswerArrivingAfterASuccessfulRetryDoesNotDisturbTheConnection() = runBlocking {
        val bus = LoopbackSignallingBus()
        val rawSignallingB = bus.createClient(pubkeyB)
        val signallingA = bus.createClient(pubkeyA)
        val parentScope = CoroutineScope(SupervisorJob())

        // Attempt 1's answer is delayed past A's handshakeTimeout, forcing a
        // retry. delayCount = 1 means attempt 2's own answer is NOT delayed,
        // so it completes normally once it starts.
        val delayedSignallingB = DelayFirstNSignallingClient(
            delegate = rawSignallingB,
            targetPubkeyHex = pubkeyA,
            delayCount = 1,
            delayDuration = 3.seconds,
            scope = parentScope,
        )

        val managerA = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildPeerConnectionFactory(),
            myPubkeyHex = pubkeyA,
            signalling = signallingA,
            iceServers = emptyList(),
            handshakeTimeout = 2.seconds,
            handshakeTickInterval = 500.milliseconds,
            maxInitiationAttempts = 3,
        )
        val managerB = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildPeerConnectionFactory(),
            myPubkeyHex = pubkeyB,
            signalling = delayedSignallingB,
            iceServers = emptyList(),
        )

        managerA.addPeer(pubkeyB)

        assertTrue(
            "A never connected to B despite retrying",
            awaitTrue(timeoutMs = 20_000) { pubkeyB in managerA.peers.value },
        )
        assertTrue("B never connected to A", awaitTrue(timeoutMs = 20_000) { pubkeyA in managerB.peers.value })

        // By now attempt 1's stale answer (delayed from before the retry even
        // happened) has very likely already arrived and been safely dropped.
        // Confirm the connection survived it rather than being silently
        // corrupted by whatever acceptAnswer() did with it.
        delay(2.seconds)
        assertTrue("A should still show B as connected after the stale answer arrived", pubkeyB in managerA.peers.value)
        assertTrue(managerA.sendMessage(pubkeyB, "still working".encodeToByteArray()).isSuccess)

        managerA.close()
        managerB.close()
    }
}
