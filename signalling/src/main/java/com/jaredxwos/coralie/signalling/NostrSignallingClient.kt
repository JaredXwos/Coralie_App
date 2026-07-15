package com.jaredxwos.coralie.signalling

import com.jaredxwos.coralie.signalling.relaySession.RelaySession
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.Closeable

/**
 * The single entry point this module exposes externally — the boundary
 * decided on earlier. Everything Nostr-specific (relay URLs, subscriptions,
 * NIP-44, signing) is fully absorbed behind this; callers only ever see
 * pubkeys and plaintext.
 *
 * The relay list is fixed at construction — no add/remove, no per-relay
 * management surface, by design.
 */
interface NostrSignallingClient : Closeable {
    val inbound: ReceiveChannel<InboundMessage>
    val lastInboundStatus: Boolean?
    fun start()
    suspend fun send(toPubkey: String, plaintext: String): Boolean
    fun connectionStatuses(): List<RelayStatus>
    override fun close()
}

data class InboundMessage(val fromPubkey: String, val plaintext: String)

/**
 * [subscriptionStatus] is nullable to cover the brief window at startup
 * before a relay's session has completed its first subscribe() call at all —
 * distinct from PENDING, which means "subscribed, waiting on EOSE."
 */
data class RelayStatus(
    val relayUrl: String,
    val isOpen: Boolean,
    val subscriptionStatus: RelaySession.SubStatus?,
)