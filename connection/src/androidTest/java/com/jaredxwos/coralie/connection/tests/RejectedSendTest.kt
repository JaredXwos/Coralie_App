package com.jaredxwos.coralie.connection.tests

import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import com.jaredxwos.coralie.connection.testClients.FakeNostrSignallingClient
import kotlin.time.Duration.Companion.milliseconds
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory

/**
 * §6.6, tested in isolation from the timeout-driven retry path: every earlier
 * retry test (exhaustion, retry-then-succeed) used a real handshake timeout as
 * the failure driver. This one uses a signalling client whose send() always
 * returns false — every attempt fails immediately at the signalling step, never
 * even reaching the point where a timeout could matter. A good sanity check that
 * §6.6's path is genuinely distinct machinery from §6.5's, not just timeout
 * failures in disguise — and since nothing here waits on real timing, it should
 * resolve almost immediately.
 */
@RunWith(AndroidJUnit4::class)
class RejectedSendTest {

    private val myPubkeyHex = "self-pubkey-hex"
    private val peerPubkeyHex = "peer-pubkey-hex"

    private fun buildFactory(): PeerConnectionFactory {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        return PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    @Test
    fun rejectedSignallingSendCountsAsAFailedAttempt() = runBlocking {
        val signalling = FakeNostrSignallingClient(sendResult = false) // every send() is rejected
        val parentScope = CoroutineScope(SupervisorJob())
        val maxAttempts = 2

        val manager = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildFactory(),
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
}