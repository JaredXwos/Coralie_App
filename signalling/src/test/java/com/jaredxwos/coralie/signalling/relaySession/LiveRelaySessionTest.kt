package com.jaredxwos.coralie.signalling.relaySession

import com.jaredxwos.coralie.identity.NostrEvent
import com.jaredxwos.coralie.signalling.eventSink.EventSink
import com.jaredxwos.coralie.signalling.nostrMessage.ClientToServerMessage
import com.jaredxwos.coralie.signalling.nostrMessage.ServerToClientMessage
import com.jaredxwos.coralie.signalling.relaySocket.RelaySocket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds



class LiveRelaySessionTest {
    private class FakeRelaySocket : RelaySocket {
        override val frames = Channel<Result<ServerToClientMessage>>(Channel.UNLIMITED)
        override var isOpen: Boolean = true
        val sent = mutableListOf<String>()
        var closed = false
            private set

        override fun send(text: String): Result<Unit> {
            sent.add(text)
            return Result.success(Unit)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeEventSink : EventSink {
        override val events = Channel<NostrEvent>(Channel.UNLIMITED)
        val offered = mutableListOf<NostrEvent>()

        override suspend fun offer(event: NostrEvent): Boolean {
            offered.add(event)
            return true
        }
    }

    // Duplicated from DedupingEventSinkTest (top-level `private` is file-scoped in
// Kotlin, so it can't be shared as-is) — worth promoting to a shared test
// fixtures file if a third test needs it too.
    private fun fakeEvent(id: String, createdAt: Long = 1000L): NostrEvent =
        NostrEvent(
            id = id, pubkey = "ab".repeat(32), createdAt = createdAt,
            kind = 20001, tags = emptyList(), content = "", sig = "00".repeat(64),
        )

    private fun fakeFilterReq(subId: String = "inbox") =
        ClientToServerMessage.Req(subId = subId, kinds = listOf(20001), tags = mapOf("p" to listOf("abcd")))

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun subscribeSendsReqAndMarksPending() = runTest {
        val socket = FakeRelaySocket()
        val session =
            LiveRelaySession(
                socket,
                FakeEventSink(),
                logDebug = {},
            )
        session.start(backgroundScope)

        session.subscribe(fakeFilterReq())
        runCurrent()

        assertTrue(socket.sent.single().startsWith("[\"REQ\""))
        assertEquals(RelaySession.SubStatus.PENDING, session.subStates.value["inbox"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun eoseMarksSubscriptionLive() = runTest {
        val socket = FakeRelaySocket()
        val session =
            LiveRelaySession(
                socket,
                FakeEventSink(),
                logDebug = {},
            )
        session.start(backgroundScope)
        session.subscribe(fakeFilterReq())
        runCurrent()

        socket.frames.send(Result.success(ServerToClientMessage.Eose("inbox")))
        runCurrent()

        assertEquals(RelaySession.SubStatus.LIVE, session.subStates.value["inbox"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun closedMarksSubscriptionStopped() = runTest {
        val socket = FakeRelaySocket()
        val session =
            LiveRelaySession(
                socket,
                FakeEventSink(),
                logDebug = {},
            )
        session.start(backgroundScope)
        session.subscribe(fakeFilterReq())
        runCurrent()

        socket.frames.send(Result.success(ServerToClientMessage.Closed("inbox", "rate-limited"))) // ← constructor assumed
        runCurrent()

        assertEquals(RelaySession.SubStatus.STOPPED, session.subStates.value["inbox"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun eventFrameIsForwardedToSink() = runTest {
        val socket = FakeRelaySocket()
        val sink = FakeEventSink()
        val session =
            LiveRelaySession(
                socket,
                sink,
                logDebug = {},
            )
        session.start(backgroundScope)
        session.subscribe(fakeFilterReq())
        runCurrent()

        val event = fakeEvent("id-1")
        socket.frames.send(Result.success(ServerToClientMessage.Event("inbox", event)))
        runCurrent()

        assertEquals(listOf(event), sink.offered)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun heartbeatReissuesReqWithAdvancedSince() = runTest {
        val socket = FakeRelaySocket()
        val session =
            LiveRelaySession(
                socket,
                FakeEventSink(),
                heartbeatInterval = 1.seconds,
                logDebug = {},
            )
        session.start(backgroundScope)
        session.subscribe(fakeFilterReq())
        runCurrent()

        socket.frames.send(Result.success(ServerToClientMessage.Event("inbox", fakeEvent("id-1", createdAt = 12345L))))
        runCurrent()

        advanceTimeBy(1.seconds)
        runCurrent()

        val reqs = socket.sent.filter { it.startsWith("[\"REQ\"") }
        assertTrue(reqs.size >= 2) // initial subscribe + at least one heartbeat reissue
        assertTrue(reqs.last().contains("\"since\":12345"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun reconnectResetsSubscriptionsAndReissuesReq() = runTest {
        val socket = FakeRelaySocket()
        val session =
            LiveRelaySession(
                socket,
                FakeEventSink(),
                reconnectPollInterval =
                    100.milliseconds,
                logDebug = {},
            )
        session.start(backgroundScope)
        session.subscribe(fakeFilterReq())
        runCurrent()
        socket.frames.send(Result.success(ServerToClientMessage.Eose("inbox")))
        runCurrent()
        assertEquals(RelaySession.SubStatus.LIVE, session.subStates.value["inbox"])

        socket.isOpen = false
        advanceTimeBy(100.milliseconds); runCurrent() // poll tick sees it down
        socket.isOpen = true
        advanceTimeBy(100.milliseconds); runCurrent() // poll tick sees false→true

        assertEquals(RelaySession.SubStatus.PENDING, session.subStates.value["inbox"])
        assertTrue(socket.sent.count { it.startsWith("[\"REQ\"") } >= 2)
    }

    @Test
    fun publishSendsEventText() = runTest {
        val socket = FakeRelaySocket()
        val session =
            LiveRelaySession(
                socket,
                FakeEventSink(),
                logDebug = {},
            )

        session.publish(ClientToServerMessage.Event(fakeEvent("id-1")))

        assertTrue(socket.sent.single().startsWith("[\"EVENT\""))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun frameChannelClosingMarksAllSubscriptionsStopped() = runTest {
        val socket = FakeRelaySocket()
        val session =
            LiveRelaySession(
                socket,
                FakeEventSink(),
                logDebug = {},
            )
        session.start(backgroundScope)
        session.subscribe(fakeFilterReq())
        runCurrent()
        socket.frames.send(Result.success(ServerToClientMessage.Eose("inbox")))
        runCurrent()

        socket.frames.close()
        runCurrent()

        assertEquals(RelaySession.SubStatus.STOPPED, session.subStates.value["inbox"])
    }
}
