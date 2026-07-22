package com.jaredxwos.coralie.connection

import com.jaredxwos.coralie.transport.IceServerConfig

/**
 * Hardcoded free/public defaults for the mesh's signalling relays and ICE (STUN)
 * servers. No TURN servers are included — free TURN always requires signup/
 * credentials (bandwidth relaying isn't free to operate), so peers behind
 * symmetric/strict NATs may fail to connect until TURN support is added later.
 *
 * Kept in its own file so this list can be swapped for remote config later
 * without touching connection-building logic.
 */
object PublicMeshEndpoints {

    /** Well-known public Nostr relays — free, no auth required. */
    val relayUrls: List<String> = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://nostr.oxtr.dev",
        "wss://purplerelay.com",
    )

    /** Public STUN-only servers (Google + Cloudflare). No TURN — see class doc. */
    val iceServers: List<IceServerConfig> = listOf(
        IceServerConfig(urls = listOf("stun:stun.l.google.com:19302")),
        IceServerConfig(urls = listOf("stun:stun1.l.google.com:19302")),
        IceServerConfig(urls = listOf("stun:stun2.l.google.com:19302")),
        IceServerConfig(urls = listOf("stun:stun3.l.google.com:19302")),
        IceServerConfig(urls = listOf("stun:stun4.l.google.com:19302")),
        IceServerConfig(urls = listOf("stun:stun.cloudflare.com:3478")),
    )
}