package com.jaredxwos.coralie.transport.context

import com.jaredxwos.coralie.transport.HandshakeEvent
import com.jaredxwos.coralie.transport.SessionDescriptionData
import kotlinx.coroutines.channels.ReceiveChannel

interface Answerer : PeerLink {
    /** AnswerReady, IceStateChanged — this side never emits OfferReady or TimedOut (no wait state exists). */
    val events: ReceiveChannel<HandshakeEvent>

    suspend fun createAnswer(): SessionDescriptionData
}