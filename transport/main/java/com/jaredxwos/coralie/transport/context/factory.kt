package com.jaredxwos.coralie.transport.context

import com.jaredxwos.coralie.transport.IceServerConfig
import com.jaredxwos.coralie.transport.SessionDescriptionData
import kotlinx.coroutines.CoroutineScope
import org.webrtc.PeerConnectionFactory
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun getInitiator(
    factory: PeerConnectionFactory,
    iceServers: List<IceServerConfig>,
    parentScope: CoroutineScope,
    handshakeTimeout: Duration = 30.seconds,
    clock: Clock = Clock.System
): Initiator = LiveRtcContext(factory, iceServers, parentScope, clock, handshakeTimeout).also { it.createDataChannel() }

fun getAnswerer(
    factory: PeerConnectionFactory,
    iceServers: List<IceServerConfig>,
    parentScope: CoroutineScope,
    offer: SessionDescriptionData,
    clock: Clock = Clock.System
): Answerer = LiveRtcContext(factory, iceServers, parentScope, clock, remoteOffer = offer)