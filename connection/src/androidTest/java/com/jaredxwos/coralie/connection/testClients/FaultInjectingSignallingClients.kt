package com.jaredxwos.coralie.connection.testClients

import com.jaredxwos.coralie.signalling.InboundMessage
import com.jaredxwos.coralie.signalling.NostrSignallingClient
import com.jaredxwos.coralie.signalling.RelayStatus
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

class FakeNostrSignallingClient(
    private val sendResult: Boolean = true,
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
        return sendResult
    }

    override fun connectionStatuses(): List<RelayStatus> = emptyList()

    override fun close() {
        closed = true
        inboundChannel.close()
    }

    suspend fun deliverInbound(fromPubkey: String, plaintext: String) {
        inboundChannel.send(InboundMessage(fromPubkey, plaintext))
    }
}

class DropFirstNSignallingClient(
    private val delegate: NostrSignallingClient,
    private val targetPubkeyHex: String,
    private val dropCount: Int,
) : NostrSignallingClient by delegate {
    private var droppedSoFar = 0
    val totalSendAttempts: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    override suspend fun send(toPubkey: String, plaintext: String): Boolean {
        totalSendAttempts += toPubkey to plaintext
        if (toPubkey == targetPubkeyHex && droppedSoFar < dropCount) {
            droppedSoFar += 1
            return true
        }
        return delegate.send(toPubkey, plaintext)
    }
}

class DelayFirstNSignallingClient(
    private val delegate: NostrSignallingClient,
    private val targetPubkeyHex: String,
    private val delayCount: Int,
    private val delayDuration: Duration,
    private val scope: CoroutineScope,
) : NostrSignallingClient by delegate {
    private var delayedSoFar = 0

    override suspend fun send(toPubkey: String, plaintext: String): Boolean {
        if (toPubkey == targetPubkeyHex && delayedSoFar < delayCount) {
            delayedSoFar += 1
            scope.launch {
                delay(delayDuration)
                delegate.send(toPubkey, plaintext)
            }
            return true
        }
        return delegate.send(toPubkey, plaintext)
    }
}
