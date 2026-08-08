package com.jaredxwos.coralie.connection.manager

import android.content.Context
import com.jaredxwos.coralie.connection.PublicMeshEndpoints
import com.jaredxwos.coralie.identity.Signer
import com.jaredxwos.coralie.signalling.LiveNostrSignallingClient
import com.jaredxwos.coralie.signalling.backoff.exponentialBackoff
import com.jaredxwos.coralie.signalling.eventSink.DedupingEventSink
import com.jaredxwos.coralie.signalling.relaySession.LiveRelaySession
import com.jaredxwos.coralie.signalling.relaySocket.LiveRelaySocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.webrtc.PeerConnectionFactory

private lateinit var meshHttpClient: OkHttpClient
private lateinit var meshPeerConnectionFactory: PeerConnectionFactory

/**
 * One-time setup for the mesh's expensive, identity-independent runtime objects.
 * Call once from Application.onCreate, before AppMesh.configure — mirrors
 * AppStorage.init(dao, filesDir)'s "call once at startup" convention.
 *
 * PeerConnectionFactory and the OkHttpClient used for relay sockets are built
 * once and reused across all ConnectionManager instances: they carry no peer
 * identity, so explicit mesh resets do not need to repay their native and
 * connection-pool construction costs.
 */
fun initMeshRuntime(context: Context) {
    meshHttpClient = OkHttpClient()

    PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .createInitializationOptions()
    )
    meshPeerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
}

/**
 * Builds a brand-new ConnectionManager: fresh Signer (fresh pubkey/peer identity)
 * and fresh relay sockets/sessions every call. Intended as AppMesh's
 * buildManager lambda: ordinary WebView rebinds retain its current instance,
 * while an explicit reset or a later start after intentional teardown invokes
 * this factory to mint a new identity.
 *
 * [parentScope] should be an app-lifetime-owned scope (e.g. HtmlHosterApplication's
 * appScope) so it outlives any single page/WebView — LiveConnectionManager treats
 * it as the external ancestor whose cancellation should tear down the whole mesh.
 *
 * Relay sockets are owned by the returned manager and are rebuilt only when a
 * new manager is explicitly requested.
 */
fun buildLiveConnectionManager(parentScope: CoroutineScope): ConnectionManager {
    check(::meshHttpClient.isInitialized && ::meshPeerConnectionFactory.isInitialized) {
        "initMeshRuntime(context) must be called before buildLiveConnectionManager()"
    }

    val signer = Signer()
    val sink = DedupingEventSink()

    // Child of parentScope: cancelling the app-level scope tears these sockets
    // down too, but each buildLiveConnectionManager() call gets its own set,
    // isolated from any previous call's sockets.
    val socketScope = CoroutineScope(
        SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO
    )

    val relays = PublicMeshEndpoints.relayUrls.map { url ->
        val socket = LiveRelaySocket(meshHttpClient, url, socketScope, ::exponentialBackoff)
        LiveNostrSignallingClient.RelayEndpoint(url, socket, LiveRelaySession(socket, sink))
    }

    val signalling = LiveNostrSignallingClient(
        relays = relays,
        signer = signer,
        sink = sink,
    )

    return LiveConnectionManager(
        parentScope = parentScope,
        peerConnectionFactory = meshPeerConnectionFactory,
        myPubkeyHex = signer.pubkeyHex,
        signalling = signalling,
        iceServers = PublicMeshEndpoints.iceServers,
    )
}
