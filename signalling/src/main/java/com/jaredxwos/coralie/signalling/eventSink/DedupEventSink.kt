package com.jaredxwos.coralie.signalling.eventSink

import com.jaredxwos.coralie.identity.NostrEvent
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DedupingEventSink(
    private val dedupWindow: Duration = 10.minutes,
    private val clock: Clock = Clock.System,
    channelCapacity: Int = Channel.BUFFERED,
) : EventSink {
    private val lock = Mutex()
    private val seenAt = LinkedHashMap<String, Instant>() // insertion order == arrival order
    private val channel = Channel<NostrEvent>(channelCapacity)

    override val events: ReceiveChannel<NostrEvent> get() = channel

    override suspend fun offer(event: NostrEvent): Boolean {
        val forwarded = lock.withLock {
            val now = clock.now()
            evictExpired(now)
            if (seenAt.containsKey(event.id)) {
                false
            } else {
                seenAt[event.id] = now
                true
            }
        }
        if (forwarded) channel.send(event)
        return forwarded
    }

    private fun evictExpired(now: Instant) {
        val cutoff = now - dedupWindow
        val it = seenAt.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value < cutoff) it.remove() else break
        }
    }
}