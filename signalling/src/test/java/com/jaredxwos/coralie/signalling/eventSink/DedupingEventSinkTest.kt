package com.jaredxwos.coralie.signalling.eventSink

import com.jaredxwos.coralie.identity.NostrEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DedupingEventSinkTest {
    private class FakeClock(var current: Instant) : Clock {
        override fun now(): Instant = current
    }
    private fun fakeEvent(id: String, createdAt: Long = 1000L): NostrEvent =
        NostrEvent(
            id = id,
            pubkey = "ab".repeat(32),
            createdAt = createdAt,
            kind = 20001,
            tags = emptyList(),
            content = "",
            sig = "00".repeat(64),
        )

    @Test
    fun firstOfferOfNewIdIsForwarded() = runBlocking {
        val sink = DedupingEventSink()
        val event = fakeEvent("id-1")

        assertTrue(sink.offer(event))
        withTimeout(1.seconds) { assertEquals(event, sink.events.receive()) }
    }

    @Test
    fun secondOfferOfSameIdIsDropped() = runBlocking {
        val sink = DedupingEventSink()
        val event = fakeEvent("id-1")

        assertTrue(sink.offer(event))
        assertFalse(sink.offer(event))

        withTimeout(1.seconds) { assertEquals(event, sink.events.receive()) } // exactly one delivery
    }

    @Test
    fun concurrentOffersOfSameIdForwardExactlyOnce() = runBlocking {
        val sink = DedupingEventSink()
        val event = fakeEvent("id-1")

        // Simulates N relays delivering the same event near-simultaneously.
        val results = (1..50).map { async { sink.offer(event) } }.awaitAll()

        assertEquals(1, results.count { it })
        assertEquals(49, results.count { !it })
    }

    @Test
    fun eventsWithDifferentIdsAreBothForwarded() = runBlocking {
        val sink = DedupingEventSink()
        val a = fakeEvent("id-a")
        val b = fakeEvent("id-b")

        assertTrue(sink.offer(a))
        assertTrue(sink.offer(b))

        withTimeout(1.seconds) {
            assertEquals(setOf(a, b), setOf(sink.events.receive(), sink.events.receive()))
        }
    }

    @Test
    fun idIsForwardedAgainAfterDedupWindowExpires() = runBlocking {
        val clock = FakeClock(Instant.fromEpochMilliseconds(0))
        val sink = DedupingEventSink(dedupWindow = 10.minutes, clock = clock)
        val event = fakeEvent("id-1")

        assertTrue(sink.offer(event))
        assertFalse(sink.offer(event)) // still within the window

        clock.current += 11.minutes
        assertTrue(sink.offer(event)) // window passed — treated as new again

        withTimeout(1.seconds) {
            assertEquals(event, sink.events.receive())
            assertEquals(event, sink.events.receive())
        }
    }

    @Test
    fun idJustInsideDedupWindowIsStillDropped() = runBlocking {
        val clock = FakeClock(Instant.fromEpochMilliseconds(0))
        val sink = DedupingEventSink(dedupWindow = 10.minutes, clock = clock)
        val event = fakeEvent("id-1")

        assertTrue(sink.offer(event))
        clock.current += 9.minutes // still inside the window
        assertFalse(sink.offer(event))
    }
}