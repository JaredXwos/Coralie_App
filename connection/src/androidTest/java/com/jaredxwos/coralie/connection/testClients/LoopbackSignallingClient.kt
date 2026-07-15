package com.jaredxwos.coralie.connection.testClients

import com.jaredxwos.coralie.signalling.InboundMessage
import com.jaredxwos.coralie.signalling.NostrSignallingClient
import com.jaredxwos.coralie.signalling.RelayStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Two [NostrSignallingClient] instances wired directly to each other in memory —
 * loopback style, no real Nostr relay. `send(toPubkey, ...)` delivers straight into
 * the other side's inbound channel. Exists because :signalling still has a real
 * interface seam (unlike :transport, which no longer does), so a genuine two-manager
 * test can fake the relay hop entirely rather than needing a live one.
 *
 * Construct via [pair] only — a lone, unlinked instance isn't useful.
 */
class LoopbackSignallingClient private constructor(
    private val myPubkeyHex: String,
    private val peerPubkeyHex: String,
) : NostrSignallingClient {

    private val inboundChannel = Channel<InboundMessage>(Channel.UNLIMITED)
    override val inbound: ReceiveChannel<InboundMessage> = inboundChannel
    override val lastInboundStatus: Boolean? = null

    lateinit var other: LoopbackSignallingClient
        private set

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
        if (toPubkey != peerPubkeyHex) return false
        other.inboundChannel.send(InboundMessage(myPubkeyHex, plaintext))
        return true
    }

    override fun connectionStatuses(): List<RelayStatus> = emptyList()

    override fun close() {
        closed = true
        inboundChannel.close()
    }

    /** Test-only helper — simulates an inbound signalling message arriving as if from [fromPubkeyHex]. */
    suspend fun deliverInbound(fromPubkeyHex: String, plaintext: String) {
        inboundChannel.send(InboundMessage(fromPubkeyHex, plaintext))
    }

    companion object {
        /** Builds two clients already wired to each other. */
        fun pair(pubkeyA: String, pubkeyB: String): Pair<LoopbackSignallingClient, LoopbackSignallingClient> {
            val a = LoopbackSignallingClient(pubkeyA, pubkeyB)
            val b = LoopbackSignallingClient(pubkeyB, pubkeyA)
            a.other = b
            b.other = a
            return a to b
        }
    }
}