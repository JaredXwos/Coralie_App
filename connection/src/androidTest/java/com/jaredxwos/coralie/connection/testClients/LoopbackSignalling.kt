package com.jaredxwos.coralie.connection.testClients

import com.jaredxwos.coralie.signalling.InboundMessage
import com.jaredxwos.coralie.signalling.NostrSignallingClient
import com.jaredxwos.coralie.signalling.RelayStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

class LoopbackSignallingClient private constructor(
    private val myPubkeyHex: String,
    private val peerPubkeyHex: String,
) : NostrSignallingClient {
    private val inboundChannel = Channel<InboundMessage>(Channel.UNLIMITED)
    override val inbound: ReceiveChannel<InboundMessage> = inboundChannel
    override val lastInboundStatus: Boolean? = null

    lateinit var other: LoopbackSignallingClient
        private set

    val sentMessages: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    @Volatile var started: Boolean = false
        private set
    @Volatile var closed: Boolean = false
        private set

    override fun start() { started = true }

    override suspend fun send(toPubkey: String, plaintext: String): Boolean {
        sentMessages += toPubkey to plaintext
        if (toPubkey != peerPubkeyHex) return false
        other.inboundChannel.send(InboundMessage(myPubkeyHex, plaintext))
        return true
    }

    override fun connectionStatuses(): List<RelayStatus> = emptyList()

    override fun close() {
        closed = true
        inboundChannel.close()
    }

    suspend fun deliverInbound(fromPubkeyHex: String, plaintext: String) {
        inboundChannel.send(InboundMessage(fromPubkeyHex, plaintext))
    }

    companion object {
        fun pair(
            pubkeyA: String,
            pubkeyB: String,
        ): Pair<LoopbackSignallingClient, LoopbackSignallingClient> {
            val a = LoopbackSignallingClient(pubkeyA, pubkeyB)
            val b = LoopbackSignallingClient(pubkeyB, pubkeyA)
            a.other = b
            b.other = a
            return a to b
        }
    }
}

class LoopbackSignallingBus {
    private val clients = ConcurrentHashMap<String, LoopbackSignallingBusClient>()

    fun createClient(pubkeyHex: String): LoopbackSignallingBusClient {
        val client = LoopbackSignallingBusClient(pubkeyHex, this)
        clients[pubkeyHex] = client
        return client
    }

    internal suspend fun deliver(
        toPubkey: String,
        fromPubkey: String,
        plaintext: String,
    ): Boolean {
        val target = clients[toPubkey] ?: return false
        target.receiveInbound(fromPubkey, plaintext)
        return true
    }
}

class LoopbackSignallingBusClient internal constructor(
    private val myPubkeyHex: String,
    private val bus: LoopbackSignallingBus,
) : NostrSignallingClient {
    private val inboundChannel = Channel<InboundMessage>(Channel.UNLIMITED)
    override val inbound: ReceiveChannel<InboundMessage> = inboundChannel
    override val lastInboundStatus: Boolean? = null

    val sentMessages: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    @Volatile var started: Boolean = false
        private set
    @Volatile var closed: Boolean = false
        private set

    override fun start() { started = true }

    override suspend fun send(toPubkey: String, plaintext: String): Boolean {
        sentMessages += toPubkey to plaintext
        return bus.deliver(toPubkey, myPubkeyHex, plaintext)
    }

    override fun connectionStatuses(): List<RelayStatus> = emptyList()

    override fun close() {
        closed = true
        inboundChannel.close()
    }

    internal suspend fun receiveInbound(fromPubkey: String, plaintext: String) {
        inboundChannel.send(InboundMessage(fromPubkey, plaintext))
    }
}
