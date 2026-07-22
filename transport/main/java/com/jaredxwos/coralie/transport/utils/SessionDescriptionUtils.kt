package com.jaredxwos.coralie.transport.utils

import com.jaredxwos.coralie.transport.SdpType
import com.jaredxwos.coralie.transport.SessionDescriptionData
import org.webrtc.SessionDescription

fun SessionDescriptionData.toWebRtc(): SessionDescription =
    SessionDescription(
        when (type) {
            SdpType.OFFER -> SessionDescription.Type.OFFER
            SdpType.ANSWER -> SessionDescription.Type.ANSWER
        },
        sdp
    )

fun SessionDescription.toData(): SessionDescriptionData =
    SessionDescriptionData(
        type = when (type) {
            SessionDescription.Type.OFFER -> SdpType.OFFER
            SessionDescription.Type.ANSWER -> SdpType.ANSWER
            else -> error("PRANSWER/ROLLBACK unused in this app")
        },
        sdp = description
    )