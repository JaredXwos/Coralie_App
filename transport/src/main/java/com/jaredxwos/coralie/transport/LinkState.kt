package com.jaredxwos.coralie.transport

import kotlin.time.Instant

sealed class LinkState {
    object New : LinkState()
    data class AwaitingRemoteDescription(val since: Instant) : LinkState()
    object Connecting : LinkState()
    object Connected : LinkState()
    object HandshakeTimedOut : LinkState()
    object Failed : LinkState()
    object Closed : LinkState()
}