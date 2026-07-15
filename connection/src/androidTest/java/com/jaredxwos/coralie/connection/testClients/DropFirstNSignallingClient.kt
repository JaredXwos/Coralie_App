package com.jaredxwos.coralie.connection.testClients

import com.jaredxwos.coralie.signalling.NostrSignallingClient
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Wraps another [NostrSignallingClient], dropping the first [dropCount] outbound
 * sends to [targetPubkeyHex] — reporting success back to the caller (the message
 * "reached the relay" fine) while never actually delivering it. Used to simulate
 * a peer being unreachable for exactly N attempts, in order to force a handshake
 * timeout specifically, as distinct from a signalling-layer rejection (§6.6,
 * covered elsewhere).
 *
 * [totalSendAttempts] records every call, dropped or delivered — the underlying
 * delegate's own sentMessages would only show delivered ones, since dropped calls
 * never reach it at all.
 */
class DropFirstNSignallingClient(
    private val delegate: NostrSignallingClient,
    private val targetPubkeyHex: String,
    private val dropCount: Int,
) : NostrSignallingClient by delegate {

    private var droppedSoFar = 0
    val totalSendAttempts: MutableList<Pair<String, String>> = CopyOnWriteArrayList() // (toPubkey, plaintext)

    override suspend fun send(toPubkey: String, plaintext: String): Boolean {
        totalSendAttempts += toPubkey to plaintext
        if (toPubkey == targetPubkeyHex && droppedSoFar < dropCount) {
            droppedSoFar++
            return true
        }
        return delegate.send(toPubkey, plaintext)
    }
}