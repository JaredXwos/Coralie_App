package com.jaredxwos.coralie.connection.testClients

import com.jaredxwos.coralie.signalling.NostrSignallingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Wraps another [NostrSignallingClient], delaying the first [delayCount] outbound
 * sends to [targetPubkeyHex] by [delayDuration] before actually delivering them —
 * reporting success back to the caller immediately regardless. Used to simulate a
 * slow/lagging relay round-trip for a specific message, distinct from
 * [DropFirstNSignallingClient] which never delivers at all.
 */
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
            delayedSoFar++
            scope.launch {
                delay(delayDuration)
                delegate.send(toPubkey, plaintext)
            }
            return true
        }
        return delegate.send(toPubkey, plaintext)
    }
}