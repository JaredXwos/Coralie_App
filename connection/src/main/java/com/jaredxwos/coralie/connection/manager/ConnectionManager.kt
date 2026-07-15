package com.jaredxwos.coralie.connection.manager

import com.jaredxwos.coralie.connection.externalMessages.PeerMessage
import com.jaredxwos.coralie.connection.externalMessages.TerminalFailure
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

interface ConnectionManager : Closeable {
    val myPubkeyHex: String
    val peers: StateFlow<Set<String>>
    val incomingMessages: SharedFlow<PeerMessage>
    val terminalFailures: ReceiveChannel<TerminalFailure>
    fun addPeer(pubkeyHex: String)
    suspend fun sendMessage(toPubkeyHex: String, bytes: ByteArray): Result<Unit>
    override fun close()
}