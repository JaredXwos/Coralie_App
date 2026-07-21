package com.jaredxwos.coralie.connection.tests

import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import com.jaredxwos.coralie.transport.SdpType
import com.jaredxwos.coralie.transport.SessionDescriptionData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jaredxwos.coralie.connection.awaitCondition
import com.jaredxwos.coralie.connection.testClients.FakeNostrSignallingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory

/**
 * First step up from the smallest-scope tests: a single manager actually calls
 * getInitiator() for real. No second peer exists, so nothing ever answers —
 * this only exercises offer creation and the outbound signalling send, not any
 * handshake completion.
 *
 * Uses runBlocking, not runTest: createOffer() awaits real ICE gathering
 * (LiveRtcContext's gatheringComplete), which takes real, variable wall-clock
 * time exactly like LoopbackConnectionTest's own log shows. There's no virtual
 * clock to fast-forward here, so this polls for the result with a real timeout
 * instead of a fixed delay.
 *
 * No ICE servers are configured — only local host candidates need to be
 * gathered for createOffer() to return, so this stays fast and doesn't risk
 * the ~45s real STUN-timeout seen in LoopbackConnectionTest's bad-STUN case.
 */
@RunWith(AndroidJUnit4::class)
class RealOfferTest {

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
    fun addPeerSendsARealOfferAndDoesNotConnectWithoutAnAnswer() = runBlocking {
        val signalling = FakeNostrSignallingClient()
        val parentScope = CoroutineScope(SupervisorJob())
        val manager = LiveConnectionManager(
            parentScope = parentScope,
            peerConnectionFactory = buildFactory(),
            myPubkeyHex = myPubkeyHex,
            signalling = signalling,
            iceServers = emptyList(),
        )

        manager.addPeer(peerPubkeyHex)

        val sentOffer = awaitCondition {
            signalling.sentMessages.firstOrNull { it.first == peerPubkeyHex }
        }
        assertNotNull("expected an Offer to peerPubkeyHex within the timeout", sentOffer)

        val encodedOffer = sentOffer!!.second
        val encodedType = Json.parseToJsonElement(encodedOffer)
            .jsonObject
            .getValue("type")
            .jsonPrimitive
            .content
        assertEquals("offer", encodedType)

        val decoded = Json.decodeFromString<SessionDescriptionData>(encodedOffer)
        assertEquals(SdpType.OFFER, decoded.type)
        assertTrue(decoded.sdp.isNotBlank())

        // No peer ever answered, so the manager should still show nobody connected.
        assertTrue(manager.peers.value.isEmpty())

        manager.close()
    }
}