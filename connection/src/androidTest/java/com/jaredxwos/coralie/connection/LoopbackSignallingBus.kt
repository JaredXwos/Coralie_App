package com.jaredxwos.coralie.connection

import com.jaredxwos.coralie.signalling.InboundMessage
import com.jaredxwos.coralie.signalling.NostrSignallingClient
import com.jaredxwos.coralie.signalling.RelayStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import com.jaredxwos.coralie.connection.testClients.LoopbackSignallingClient

/**
 * A shared in-memory bus for N-party loopback signalling — `send(toPubkey, ...)`
 * looks up the target by pubkey and delivers straight into its inbound channel.
 * Generalizes [LoopbackSignallingClient]'s pairwise wiring to an arbitrary mesh,
 * needed once gossip means a peer signals someone it was never explicitly paired
 * with (e.g. B initiating to C after learning about C only through A's Announce).
 *
 * All [createClient] calls are expected to happen during test setup, before any
 * manager starts real async work — [clients] only needs to tolerate concurrent
 * *reads* after that point, but uses a concurrent map regardless now that real
 * dispatcher threads (not a single virtual scheduler) are involved.
 */
class LoopbackSignallingBus {
    private val clients = ConcurrentHashMap<String, LoopbackSignallingBusClient>()

    fun createClient(pubkeyHex: String): LoopbackSignallingBusClient {
        val client = LoopbackSignallingBusClient(pubkeyHex, this)
        clients[pubkeyHex] = client
        return client
    }

    internal suspend fun deliver(toPubkey: String, fromPubkey: String, plaintext: String): Boolean {
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

    val sentMessages: MutableList<Pair<String, String>> = CopyOnWriteArrayList() // (toPubkey, plaintext)

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