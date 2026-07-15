package com.jaredxwos.coralie.transport.context

import com.jaredxwos.coralie.transport.LinkState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface PeerLink {
    val state: StateFlow<LinkState>
    val incomingBytes: SharedFlow<ByteArray>
    suspend fun send(bytes: ByteArray): Result<Unit>
    fun close()
}