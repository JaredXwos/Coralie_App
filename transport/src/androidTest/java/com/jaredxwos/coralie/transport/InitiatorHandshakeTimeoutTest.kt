package com.jaredxwos.coralie.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jaredxwos.coralie.transport.context.getInitiator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class InitiatorHandshakeTimeoutTest {

    private fun buildFactory(): PeerConnectionFactory {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        return PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    @Test
    fun checkHandshakeTimeout_falseBeforeCreateOfferIsCalled() {
        val clock = FixedClock(Instant.parse("2026-07-10T00:00:00Z"))
        val initiator = getInitiator(
            factory = buildFactory(),
            iceServers = emptyList(),
            parentScope = CoroutineScope(Dispatchers.Default),
            handshakeTimeout = 30.seconds,
            clock = clock
        )

        // state is still New — nothing to time out yet
        assertFalse(initiator.checkHandshakeTimeout())
        assertEquals(LinkState.New, initiator.state.value)
    }

    @Test
    fun checkHandshakeTimeout_falseWhenUnderThreshold() = runTest {
        val clock = FixedClock(Instant.parse("2026-07-10T00:00:00Z"))
        val initiator = getInitiator(
            factory = buildFactory(),
            iceServers = emptyList(),
            parentScope = CoroutineScope(Dispatchers.Default),
            handshakeTimeout = 30.seconds,
            clock = clock
        )

        initiator.createOffer()
        assertTrue(initiator.state.value is LinkState.AwaitingRemoteDescription)

        clock.advanceBy(29.seconds)
        assertFalse(initiator.checkHandshakeTimeout())
        assertTrue(initiator.state.value is LinkState.AwaitingRemoteDescription)
    }

    @Test
    fun checkHandshakeTimeout_transitionsAtThreshold() = runTest {
        val clock = FixedClock(Instant.parse("2026-07-10T00:00:00Z"))
        val initiator = getInitiator(
            factory = buildFactory(),
            iceServers = emptyList(),
            parentScope = CoroutineScope(Dispatchers.Default),
            handshakeTimeout = 30.seconds,
            clock = clock
        )

        initiator.createOffer()
        clock.advanceBy(30.seconds)

        assertTrue(initiator.checkHandshakeTimeout())
        assertEquals(LinkState.HandshakeTimedOut, initiator.state.value)
    }

    @Test
    fun checkHandshakeTimeout_falseOnceAlreadyTimedOut() = runTest {
        val clock = FixedClock(Instant.parse("2026-07-10T00:00:00Z"))
        val initiator = getInitiator(
            factory = buildFactory(),
            iceServers = emptyList(),
            parentScope = CoroutineScope(Dispatchers.Default),
            handshakeTimeout = 30.seconds,
            clock = clock
        )

        initiator.createOffer()
        clock.advanceBy(30.seconds)
        assertTrue(initiator.checkHandshakeTimeout())

        // second call — already HandshakeTimedOut, not AwaitingRemoteDescription — must no-op
        clock.advanceBy(30.seconds)
        assertFalse(initiator.checkHandshakeTimeout())
    }
}