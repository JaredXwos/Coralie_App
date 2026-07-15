package com.jaredxwos.coralie.signalling.eventSink

import com.jaredxwos.coralie.identity.NostrEvent
import kotlinx.coroutines.channels.ReceiveChannel

interface EventSink {
    val events: ReceiveChannel<NostrEvent>
    suspend fun offer(event: NostrEvent): Boolean
}