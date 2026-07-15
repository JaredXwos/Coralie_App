package com.jaredxwos.coralie.transport

import kotlinx.serialization.Serializable

@Serializable
data class SessionDescriptionData(
    val type: SdpType,
    val sdp: String
    // `sdp` is the full SDP text — ufrag, password, DTLS fingerprint, and
    // (in wait-for-gathering-complete mode) every ICE candidate are already
    // embedded in this string. Nothing else needs to travel alongside it.
)

@Serializable
enum class SdpType { OFFER, ANSWER }

@Serializable
data class IceCandidateData(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String   // the raw `a=candidate` line (trickle mode)
)

@Serializable
data class IceServerConfig(
    val urls: List<String>,        // e.g. ["stun:stun.l.google.com:19302"]
    val username: String? = null,  // TURN only
    val credential: String? = null // TURN only
)