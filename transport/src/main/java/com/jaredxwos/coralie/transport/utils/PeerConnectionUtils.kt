package com.jaredxwos.coralie.transport.utils

import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun PeerConnection.createOfferSuspend(): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { cont.resume(sdp) }
            override fun onCreateFailure(error: String) { cont.resumeWithException(IllegalStateException(error)) }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, MediaConstraints())
    }

suspend fun PeerConnection.createAnswerSuspend(): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { cont.resume(sdp) }
            override fun onCreateFailure(error: String) { cont.resumeWithException(IllegalStateException(error)) }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, MediaConstraints())
    }

suspend fun PeerConnection.setLocalDescriptionSuspend(sdp: SessionDescription) =
    suspendCancellableCoroutine<Unit> { cont ->
        setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() { cont.resume(Unit) }
            override fun onSetFailure(error: String) { cont.resumeWithException(IllegalStateException(error)) }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, sdp)
    }

suspend fun PeerConnection.setRemoteDescriptionSuspend(sdp: SessionDescription) =
    suspendCancellableCoroutine<Unit> { cont ->
        setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { cont.resume(Unit) }
            override fun onSetFailure(error: String) { cont.resumeWithException(IllegalStateException(error)) }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, sdp)
    }