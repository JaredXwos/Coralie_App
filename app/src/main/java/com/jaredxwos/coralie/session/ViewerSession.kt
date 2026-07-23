package com.jaredxwos.coralie.session

import android.util.Log
import android.webkit.WebView
import com.jaredxwos.coralie.bridge.AppProxy
import com.jaredxwos.coralie.bridge.CoralieEventEmitter
import com.jaredxwos.coralie.capability.PageCapabilities
import com.jaredxwos.coralie.capability.PageCapability
import com.jaredxwos.coralie.mesh.AppMesh
import com.jaredxwos.coralie.storage.AppStorage
import com.jaredxwos.coralie.timer.AppTimers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class CapabilityDecision {
    REJECT,
    ALLOW_ONCE,
    ALLOW_ALWAYS,
}

data class CapabilityPrompt(
    val capability: PageCapability,
)

/**
 * Owns one open HTML page's native lifetime.
 *
 * Persisted grants come from the HTML database row. ALLOW_ONCE grants are held
 * only in this object and disappear when [close] is called. ALLOW_ALWAYS updates
 * the HTML row before the method returns to JavaScript.
 */
class ViewerSession(
    val assetId: Long,
    val spaceId: Long,
    initialCapabilities: PageCapabilities,
    parentScope: CoroutineScope,
) : AutoCloseable {
    val sessionId: String = UUID.randomUUID().toString()

    private val closed = AtomicBoolean(false)
    private val persistedMask = AtomicLong(initialCapabilities.mask)
    private val onceMask = AtomicLong(PageCapabilities.NONE_MASK)
    private val rejectedMask = AtomicLong(PageCapabilities.NONE_MASK)
    private val activatedMask = AtomicLong(PageCapabilities.NONE_MASK)

    private val sessionJob =
        SupervisorJob(parentScope.coroutineContext[Job])
    val scope =
        CoroutineScope(parentScope.coroutineContext + sessionJob)

    private val emitterRef =
        AtomicReference<CoralieEventEmitter?>(null)

    private val activationGate = Mutex()
    private val permissionGate = Mutex()
    private var pendingDecision:
        CompletableDeferred<CapabilityDecision>? = null

    private val _permissionPrompt =
        MutableStateFlow<CapabilityPrompt?>(null)
    val permissionPrompt: StateFlow<CapabilityPrompt?> =
        _permissionPrompt.asStateFlow()

    fun capabilities(): PageCapabilities =
        PageCapabilities(
            persistedMask.get() or onceMask.get(),
        )

    fun capabilitiesJson(): String =
        capabilities().toJson()

    fun hasCapability(
        capability: PageCapability,
    ): Boolean =
        capabilities().allows(capability)

    fun requireCapability(
        capability: PageCapability,
        operation: String,
    ) {
        checkOpen()
        capabilities().require(capability, operation)
    }

    /**
     * Opens resources needed by capabilities already persisted for the page.
     * Mesh/timer activation waits until a WebView event emitter is attached.
     */
    suspend fun prepare() {
        checkOpen()
        if (hasCapability(PageCapability.STORAGE)) {
            ensureCapabilityReady(PageCapability.STORAGE)
        }
    }

    fun attachWebView(webView: WebView) {
        checkOpen()
        val emitter = CoralieEventEmitter(webView)
        emitterRef.getAndSet(emitter)?.close()

        scope.launchSafely("activate-attached-capabilities") {
            for (capability in capabilities().asSet()) {
                ensureCapabilityReady(capability)
            }
        }
    }

    suspend fun requestCapability(
        capability: PageCapability,
    ): Boolean {
        checkOpen()

        if (hasCapability(capability)) {
            ensureCapabilityReady(capability)
            return true
        }
        if (rejectedMask.get() and capability.bit != 0L) {
            return false
        }

        return permissionGate.withLock {
            if (hasCapability(capability)) {
                ensureCapabilityReady(capability)
                return@withLock true
            }
            if (rejectedMask.get() and capability.bit != 0L) {
                return@withLock false
            }

            when (awaitDecision(capability)) {
                CapabilityDecision.ALLOW_ONCE -> {
                    onceMask.getAndUpdate { it or capability.bit }
                    ensureCapabilityReady(capability)
                    true
                }

                CapabilityDecision.ALLOW_ALWAYS -> {
                    val updated =
                        PageCapabilities(
                            persistedMask.get() or capability.bit,
                        )
                    withContext(Dispatchers.IO) {
                        AppStorage.updateHtmlCapabilities(
                            assetId = assetId,
                            capabilities = updated,
                        ).getOrThrow()
                    }
                    persistedMask.set(updated.mask)
                    rejectedMask.getAndUpdate {
                        it and capability.bit.inv()
                    }
                    ensureCapabilityReady(capability)
                    true
                }

                CapabilityDecision.REJECT -> {
                    rejectedMask.getAndUpdate {
                        it or capability.bit
                    }
                    false
                }
            }
        }
    }

    fun resolveCapabilityPrompt(
        decision: CapabilityDecision,
    ) {
        pendingDecision?.complete(decision)
    }

    suspend fun ensureCapabilityReady(
        capability: PageCapability,
    ) {
        checkOpen()
        if (!hasCapability(capability)) {
            return
        }

        activationGate.withLock {
            if (activatedMask.get() and capability.bit != 0L) {
                return
            }

            when (capability) {
                PageCapability.STORAGE ->
                    withContext(Dispatchers.IO) {
                        AppStorage.openSpace(spaceId).getOrThrow()
                    }

                PageCapability.MESH ->
                    withContext(Dispatchers.Main.immediate) {
                        val emitter =
                            emitterRef.get()
                                ?: return@withContext
                        AppMesh.attach(scope, emitter::emit)
                        AppMesh.rebuild()
                    }

                PageCapability.TIMERS ->
                    withContext(Dispatchers.Main.immediate) {
                        val emitter =
                            emitterRef.get()
                                ?: return@withContext
                        AppTimers.attach(scope, emitter::emit)
                    }

                PageCapability.HTTP -> Unit
            }

            // Mesh/timers are not considered active until an emitter exists.
            if (
                capability == PageCapability.MESH ||
                capability == PageCapability.TIMERS
            ) {
                if (emitterRef.get() == null) {
                    return
                }
            }
            activatedMask.getAndUpdate {
                it or capability.bit
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        pendingDecision?.complete(CapabilityDecision.REJECT)
        pendingDecision = null
        _permissionPrompt.value = null

        emitterRef.getAndSet(null)?.close()

        val activated = activatedMask.getAndSet(0L)
        if (activated and PageCapability.STORAGE.bit != 0L) {
            AppStorage.closeSpaceSync()
        }
        if (activated and PageCapability.MESH.bit != 0L) {
            AppMesh.teardownForPageExit()
        }
        if (activated and PageCapability.TIMERS.bit != 0L) {
            AppTimers.teardownForPageExit()
        }

        AppProxy.teardownForPageExit()
        scope.cancel("ViewerSession closed")

        Log.i(
            TAG,
            "session.closed id=$sessionId assetId=$assetId",
        )
    }

    private suspend fun awaitDecision(
        capability: PageCapability,
    ): CapabilityDecision {
        val deferred =
            CompletableDeferred<CapabilityDecision>()
        pendingDecision = deferred
        _permissionPrompt.value =
            CapabilityPrompt(capability)

        Log.i(
            TAG,
            "permission.prompt id=$sessionId " +
                "assetId=$assetId " +
                "capability=${capability.wireName}",
        )

        return try {
            deferred.await()
        } finally {
            _permissionPrompt.value = null
            pendingDecision = null
        }
    }

    private fun checkOpen() {
        check(!closed.get()) {
            "Viewer session is closed"
        }
    }

    private fun CoroutineScope.launchSafely(
        operation: String,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        launch {
            try {
                block()
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "session.operation.fail " +
                        "id=$sessionId " +
                        "operation=$operation " +
                        "exception=${error.javaClass.name} " +
                        "message=${error.message}",
                    error,
                )
            }
        }
    }

    private companion object {
        const val TAG = "CoralieSession"
    }
}
