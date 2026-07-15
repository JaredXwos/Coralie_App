package com.jaredxwos.coralie.transport

import org.webrtc.PeerConnection

sealed class HandshakeEvent {
    data class OfferReady(val offer: SessionDescriptionData) : HandshakeEvent()
    data class AnswerReady(val answer: SessionDescriptionData) : HandshakeEvent()
    object TimedOut : HandshakeEvent()
    data class IceStateChanged(val raw: PeerConnection.IceConnectionState) : HandshakeEvent()
}