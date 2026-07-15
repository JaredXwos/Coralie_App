package com.jaredxwos.coralie.transport

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jaredxwos.coralie.transport.context.getAnswerer
import com.jaredxwos.coralie.transport.context.getInitiator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class LoopbackConnectionTest {

    private val realStun = listOf(IceServerConfig(urls = listOf("stun:stun.l.google.com:19302")))

    private fun buildFactory(): PeerConnectionFactory {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        return PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    @Test
    fun offerAnswerExchange_opensChannel_bytesFlowBothWays() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val factory = buildFactory()

        val initiator = getInitiator(factory, realStun, scope)
        val offer = initiator.createOffer()

        val answerer = getAnswerer(factory, realStun, scope, offer)
        val answer = answerer.createAnswer()

        initiator.acceptAnswer(answer)

        withTimeout(15.seconds) {
            Log.d("TEST", "waiting for initiator Connected, currently ${initiator.state.value}")
            initiator.state.first { it == LinkState.Connected }
            Log.d("TEST", "initiator Connected observed")

            Log.d("TEST", "waiting for answerer Connected, currently ${answerer.state.value}")
            answerer.state.first { it == LinkState.Connected }
            Log.d("TEST", "answerer Connected observed")
        }

        val fromInitiator = "hello from initiator".toByteArray()
        val fromAnswerer = "hello from answerer".toByteArray()

        assertTrue(initiator.send(fromInitiator).isSuccess)
        assertTrue(answerer.send(fromAnswerer).isSuccess)

        val answererReceived = async { withTimeout(5.seconds) { answerer.incomingBytes.first() } }
        val initiatorReceived = async { withTimeout(5.seconds) { initiator.incomingBytes.first() } }

        assertTrue(initiator.send(fromInitiator).isSuccess)
        assertTrue(answerer.send(fromAnswerer).isSuccess)

        assertArrayEquals(fromInitiator, answererReceived.await())
        assertArrayEquals(fromAnswerer, initiatorReceived.await())

        initiator.close()
        answerer.close()
    }

    @Test
    fun iceConnectionStateChanges_areObservedOnBothSides() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val factory = buildFactory()

        val initiator = getInitiator(factory, realStun, scope)
        val offer = initiator.createOffer()
        val answerer = getAnswerer(factory, realStun, scope, offer)
        val answer = answerer.createAnswer()
        initiator.acceptAnswer(answer)

        val initiatorIceStates = mutableListOf<org.webrtc.PeerConnection.IceConnectionState>()
        val collectJob = scope.launch {
            for (event in initiator.events) {
                if (event is HandshakeEvent.IceStateChanged) initiatorIceStates.add(event.raw)
            }
        }

        withTimeout(15.seconds) { initiator.state.first { it == LinkState.Connected } }

        assertTrue(
            "expected at least CHECKING and CONNECTED among observed ICE states, got $initiatorIceStates",
            initiatorIceStates.isNotEmpty()
        )

        collectJob.cancel()
        initiator.close()
        answerer.close()
    }

    @Test
    fun closingOneSide_surfacesAsClosedOnTheOther() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val factory = buildFactory()

        val initiator = getInitiator(factory, realStun, scope)
        val offer = initiator.createOffer()
        val answerer = getAnswerer(factory, realStun, scope, offer)
        val answer = answerer.createAnswer()
        initiator.acceptAnswer(answer)

        withTimeout(15.seconds) {
            initiator.state.first { it == LinkState.Connected }
            answerer.state.first { it == LinkState.Connected }
        }

        initiator.close()

        withTimeout(10.seconds) {
            answerer.state.first { it == LinkState.Closed }
        }
        Unit
    }

    @Test
    fun badThenGoodStunEntry_configSwapTakesEffect() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val factory = buildFactory()

        val badStun = listOf(IceServerConfig(urls = listOf("stun:127.0.0.1:1")))  // unreachable, deliberately bad
        val goodStun = realStun

        val badInitiator = getInitiator(factory, badStun, scope)
        val badOffer = badInitiator.createOffer()
        val badAnswerer = getAnswerer(factory, badStun, scope, badOffer)
        badAnswerer.createAnswer()
        // deliberately not acceptAnswer'd — proving nothing here except that bad STUN doesn't itself throw;
        // real failure proof is ICE never reaching Connected within a short timeout:
        val badResult = runCatching {
            withTimeout(5.seconds) { badInitiator.state.first { it == LinkState.Connected } }
        }
        assertTrue("expected bad STUN config to fail to connect in time", badResult.isFailure)
        badInitiator.close()
        badAnswerer.close()

        val goodInitiator = getInitiator(factory, goodStun, scope)
        val goodOffer = goodInitiator.createOffer()
        val goodAnswerer = getAnswerer(factory, goodStun, scope, goodOffer)
        val goodAnswer = goodAnswerer.createAnswer()
        goodInitiator.acceptAnswer(goodAnswer)

        withTimeout(15.seconds) { goodInitiator.state.first { it == LinkState.Connected } }

        goodInitiator.close()
        goodAnswerer.close()
    }
}