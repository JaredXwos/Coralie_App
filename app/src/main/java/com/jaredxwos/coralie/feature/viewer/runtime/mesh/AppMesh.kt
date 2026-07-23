package com.jaredxwos.coralie.feature.viewer.runtime.mesh

import com.jaredxwos.coralie.connection.manager.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

object AppMesh {
    var current: ConnectionManager? = null
        private set
    private var collectorJobs: List<Job> = emptyList()
    private var scope: CoroutineScope? = null
    private var sendEvent: ((String, JsonElement) -> Unit)? = null
    private lateinit var buildManager: () -> ConnectionManager

    /** Called once, app-wide — e.g. Application.onCreate. Configured from Application.onCreate. */
    fun configure(buildManager: () -> ConnectionManager) {
        this.buildManager = buildManager
    }

    /** Called once per PageRender instance, from the AndroidView factory —
     *  this is the only place a live WebView (and therefore evaluateJavascript)
     *  exists yet. The viewer session controls per-page attachment. */
    fun attach(scope: CoroutineScope, sendEvent: (String, JsonElement) -> Unit) {
        this.scope = scope
        this.sendEvent = sendEvent
    }

    /** Called right after attach() on WebView construction, and again from the
     *  bridge's close() callback (which reuses the scope/sendEvent already
     *  attached — it doesn't have a WebView reference of its own to build a
     *  new one from). Returns the new pubkey hex. */
    fun rebuild(): String {
        teardown()
        val scope = requireNotNull(scope) { "AppMesh.attach() must be called before rebuild()" }
        val sendEvent = requireNotNull(sendEvent) { "AppMesh.attach() must be called before rebuild()" }
        val manager = buildManager()           // fresh identity generated inside :connection
        current = manager
        collectorJobs = listOf(
            scope.launch { manager.peers.collect { sendEvent("peers", it.toJsonElement()) } },
            scope.launch { manager.incomingMessages.collect { sendEvent("message", it.toJsonElement()) } },
            scope.launch { for (f in manager.terminalFailures) sendEvent("terminalFailure", f.toJsonElement()) },
        )
        return manager.myPubkeyHex
    }

    /** Called from DisposableEffect.onDispose only — no rebuild. */
    fun teardownForPageExit() = teardown()

    private fun teardown() {
        collectorJobs.forEach { it.cancel() }
        collectorJobs = emptyList()
        current?.close()
        current = null
    }

    private fun Set<String>.toJsonElement(): JsonElement =
        buildJsonArray { forEach { add(JsonPrimitive(it)) } }
}