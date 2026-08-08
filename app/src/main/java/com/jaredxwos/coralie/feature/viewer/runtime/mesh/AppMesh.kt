package com.jaredxwos.coralie.feature.viewer.runtime.mesh

import com.jaredxwos.coralie.connection.manager.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * Process-wide mesh runtime. A page binding owns event delivery, not the
 * connection manager: replacing a WebView rebinds collectors without replacing
 * the manager, its identity, or any live PeerLinks.
 */
object AppMesh {
    private val runtime = MeshRuntime()

    val current: ConnectionManager?
        get() = runtime.current

    fun configure(buildManager: () -> ConnectionManager) =
        runtime.configure(buildManager)

    fun attach(
        ownerId: String,
        scope: CoroutineScope,
        sendEvent: (String, JsonElement) -> Unit,
    ) = runtime.attach(ownerId, scope, sendEvent)

    fun start(ownerId: String): String =
        runtime.start(ownerId)

    fun reset(ownerId: String): String =
        runtime.reset(ownerId)

    fun teardownForPageExit(ownerId: String) =
        runtime.teardown(ownerId)
}

internal class MeshRuntime {
    private var manager: ConnectionManager? = null
    private var collectorJobs: List<Job> = emptyList()
    private var binding: Binding? = null
    private var buildManager: (() -> ConnectionManager)? = null

    val current: ConnectionManager?
        @Synchronized get() = manager

    @Synchronized
    fun configure(buildManager: () -> ConnectionManager) {
        this.buildManager = buildManager
    }

    /** Replaces only the page event sink; live mesh state is retained. */
    @Synchronized
    fun attach(
        ownerId: String,
        scope: CoroutineScope,
        sendEvent: (String, JsonElement) -> Unit,
    ) {
        binding = Binding(ownerId, scope, sendEvent)
        manager?.let(::bindCollectors)
    }

    /** Starts the mesh once, or returns the existing identity after a rebind. */
    @Synchronized
    fun start(ownerId: String): String {
        val activeBinding = requireOwner(ownerId)
        val activeManager = manager ?: requireBuilder().invoke().also { manager = it }
        if (collectorJobs.isEmpty()) {
            bindCollectors(activeManager, activeBinding)
        }
        return activeManager.myPubkeyHex
    }

    /** Explicit page API: discard all links and create a fresh mesh identity. */
    @Synchronized
    fun reset(ownerId: String): String {
        val activeBinding = requireOwner(ownerId)
        closeManager()
        val replacement = requireBuilder().invoke()
        manager = replacement
        bindCollectors(replacement, activeBinding)
        return replacement.myPubkeyHex
    }

    /** Explicit page/session exit. Stale owners cannot tear down a newer page. */
    @Synchronized
    fun teardown(ownerId: String) {
        if (binding?.ownerId != ownerId) return
        closeManager()
        binding = null
    }

    private fun requireOwner(ownerId: String): Binding {
        val activeBinding = requireNotNull(binding) {
            "AppMesh.attach() must be called before starting the mesh"
        }
        check(activeBinding.ownerId == ownerId) {
            "Mesh binding belongs to another viewer session"
        }
        return activeBinding
    }

    private fun requireBuilder(): () -> ConnectionManager =
        requireNotNull(buildManager) { "AppMesh.configure() must be called before starting the mesh" }

    private fun bindCollectors(
        activeManager: ConnectionManager,
        activeBinding: Binding = requireNotNull(binding),
    ) {
        collectorJobs.forEach(Job::cancel)
        collectorJobs = listOf(
            activeBinding.scope.launch {
                activeManager.peers.collect {
                    activeBinding.sendEvent("peers", it.toJsonElement())
                }
            },
            activeBinding.scope.launch {
                activeManager.incomingMessages.collect {
                    activeBinding.sendEvent("message", it.toJsonElement())
                }
            },
            activeBinding.scope.launch {
                for (failure in activeManager.terminalFailures) {
                    activeBinding.sendEvent("terminalFailure", failure.toJsonElement())
                }
            },
        )
    }

    private fun closeManager() {
        collectorJobs.forEach(Job::cancel)
        collectorJobs = emptyList()
        manager?.close()
        manager = null
    }

    private data class Binding(
        val ownerId: String,
        val scope: CoroutineScope,
        val sendEvent: (String, JsonElement) -> Unit,
    )
}

private fun Set<String>.toJsonElement(): JsonElement =
    buildJsonArray { forEach { add(JsonPrimitive(it)) } }
