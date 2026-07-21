package com.jaredxwos.coralie.connection.tests
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jaredxwos.coralie.connection.awaitTrue
import com.jaredxwos.coralie.connection.externalMessages.PeerMessage
import com.jaredxwos.coralie.connection.testClients.LoopbackSignallingClient
import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Smallest true end-to-end test: two managers, real transport on both sides,
 * signalling looped back in-memory ([LoopbackSignallingClient]) rather than a real
 * Nostr relay. Exercises the full path this design centers on: addPeer -> real
 * Offer -> real Answer -> Connected on both sides -> app messages flowing both
 * directions over the real (loopback) data channel.
 *
 * Message-arrival collectors are attached to `incomingMessages` immediately after
 * each manager is constructed, well before anything is sent — incomingMessages is
 * a SharedFlow with no replay, so a collector attached after a message already
 * flowed would simply never see it (a real loopback round-trip can easily be
 * faster than the test's own next line). Polling ([awaitTrue]) against the
 * accumulated list sidesteps that race entirely rather than depending on
 * subscription timing via something like `.first()`.
 *
 * No ICE servers configured — both PeerConnections are on the same device, so host
 * candidates alone should be enough to find each other, same reasoning as the
 * single-manager offer test.
 */
@RunWith(AndroidJUnit4::class)
class LoopbackTest {

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
    fun addPeerCompletesFullHandshakeAndExchangesMessagesBothWays() = runBlocking {
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

        // Subscribed before anything can possibly be sent -- see class doc.
        val receivedByA = CopyOnWriteArrayList<PeerMessage>()
        val receivedByB = CopyOnWriteArrayList<PeerMessage>()
        val collectJobA = launch { managerA.incomingMessages.collect { receivedByA += it } }
        val collectJobB = launch { managerB.incomingMessages.collect { receivedByB += it } }

        managerA.addPeer(pubkeyB)

        assertTrue("A never saw B as connected", awaitTrue { pubkeyB in managerA.peers.value })
        assertTrue("B never saw A as connected", awaitTrue { pubkeyA in managerB.peers.value })

        // Include the full signed-byte boundary used by Kotlin ByteArray JSON.
        // The browser sends 128..255 as -128..-1 and reconstructs them with
        // `value & 0xff` after decoding.
        val bytesAtoB = byteArrayOf(0, 1, 127, -128, -1)
        assertTrue(managerA.sendMessage(pubkeyB, bytesAtoB).isSuccess)
        assertTrue("B never received A's message", awaitTrue { receivedByB.isNotEmpty() })
        assertEquals(pubkeyA, receivedByB.first().fromPubkeyHex)
        assertTrue(bytesAtoB.contentEquals(receivedByB.first().bytes))

        val bytesBtoA = byteArrayOf(-1, -128, 127, 1, 0)
        assertTrue(managerB.sendMessage(pubkeyA, bytesBtoA).isSuccess)
        assertTrue("A never received B's reply", awaitTrue { receivedByA.isNotEmpty() })
        assertEquals(pubkeyB, receivedByA.first().fromPubkeyHex)
        assertTrue(bytesBtoA.contentEquals(receivedByA.first().bytes))

        collectJobA.cancel()
        collectJobB.cancel()
        managerA.close()
        managerB.close()
    }
}
