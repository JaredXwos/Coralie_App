package com.jaredxwos.coralie.connection.testClients

import com.jaredxwos.coralie.signalling.InboundMessage
import com.jaredxwos.coralie.signalling.NostrSignallingClient
import com.jaredxwos.coralie.signalling.RelayStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.CopyOnWriteArrayList

class FakeNostrSignallingClient(
    private val sendResult: Boolean = true,
) : NostrSignallingClient {

    private val inboundChannel = Channel<InboundMessage>(Channel.UNLIMITED)
    override val inbound: ReceiveChannel<InboundMessage> = inboundChannel
    override val lastInboundStatus: Boolean? = null

    val sentMessages: MutableList<Pair<String, String>> =
        CopyOnWriteArrayList() // (toPubkey, plaintext)

    @Volatile
    var started: Boolean = false
        private set

    @Volatile
    var closed: Boolean = false
        private set

    override fun start() {
        started = true
    }

    override suspend fun send(toPubkey: String, plaintext: String): Boolean {
        sentMessages += toPubkey to plaintext
        return sendResult
    }

    override fun connectionStatuses(): List<RelayStatus> = emptyList()

    override fun close() {
        closed = true
        inboundChannel.close()
    }

    /** Test-only helper — simulates an inbound signalling message arriving from a peer. */
    suspend fun deliverInbound(fromPubkey: String, plaintext: String) {
        inboundChannel.send(InboundMessage(fromPubkey, plaintext))
    }
}