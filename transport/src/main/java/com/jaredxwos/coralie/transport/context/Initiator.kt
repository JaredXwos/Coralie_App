package com.jaredxwos.coralie.transport.context

import com.jaredxwos.coralie.transport.HandshakeEvent
import com.jaredxwos.coralie.transport.SessionDescriptionData
import kotlinx.coroutines.channels.ReceiveChannel

interface Initiator : PeerLink {
    /** OfferReady, TimedOut, IceStateChanged — this side never emits AnswerReady. */
    val events: ReceiveChannel<HandshakeEvent>

    suspend fun createOffer(): SessionDescriptionData

    suspend fun acceptAnswer(answer: SessionDescriptionData)

    /** Caller ticks this periodically (production) or once, directly, after advancing a fixed clock (tests). */
    fun checkHandshakeTimeout(): Boolean
}
