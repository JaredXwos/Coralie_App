package com.jaredxwos.coralie.signalling.relaySocket

import com.jaredxwos.coralie.signalling.nostrMessage.ServerToClientMessage
import kotlinx.coroutines.channels.Channel

interface RelaySocket : java.io.Closeable {
    val frames: Channel<Result<ServerToClientMessage>>
    val isOpen: Boolean

    fun send(text: String): Result<Unit>
    override fun close()
}