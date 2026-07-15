package com.jaredxwos.coralie.connection.tests

import com.jaredxwos.coralie.connection.LoopbackSignallingBus
import com.jaredxwos.coralie.connection.awaitTrue
import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import com.jaredxwos.coralie.connection.testClients.DelayFirstNSignallingClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * §6.4's resolution, tested for the case it can actually reach deterministically:
 * a stale answer (matching an offer a retry already superseded) arrives well
 * after A has already reconnected via a fresh attempt. By the time it lands,
 * pubkeyB has left `initiating` entirely — it's absorbed by the ordinary
 * "no attempt expecting this" guard, not the narrower `===` staleness check
 * (see the surrounding conversation for why that specific race isn't
 * practically reachable via integration testing). What this DOES confirm: the
 * stale answer doesn't corrupt or destabilize the connection it arrives after.
 */
@RunWith(AndroidJUnit4::class)
class StaleAnswerTest {

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