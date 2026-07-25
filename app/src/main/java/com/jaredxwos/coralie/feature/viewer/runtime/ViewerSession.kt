package com.jaredxwos.coralie.feature.viewer.runtime

import android.util.Log
import android.webkit.WebView
import com.jaredxwos.coralie.feature.viewer.bridge.CoralieEventEmitter
import com.jaredxwos.coralie.data.library.model.PageCapabilities
import com.jaredxwos.coralie.data.library.model.PageCapability
import com.jaredxwos.coralie.data.library.PageLibrary
import com.jaredxwos.coralie.data.permission.DomainPermissionStore
import com.jaredxwos.coralie.data.space.SpaceKeyValueStore
import com.jaredxwos.coralie.feature.viewer.runtime.http.AsyncHttpRequestDispatcher
import com.jaredxwos.coralie.feature.viewer.runtime.mesh.AppMesh
import com.jaredxwos.coralie.feature.viewer.runtime.timer.AppTimers
import com.jaredxwos.coralie.feature.viewer.runtime.permission.CapabilityPermissionPrompt
import com.jaredxwos.coralie.feature.viewer.runtime.permission.DomainPermissionPrompt
import com.jaredxwos.coralie.feature.viewer.runtime.permission.PermissionDecision
import com.jaredxwos.coralie.feature.viewer.runtime.permission.PermissionRejectedException
import com.jaredxwos.coralie.feature.viewer.runtime.permission.PermissionScope
import com.jaredxwos.coralie.feature.viewer.runtime.permission.SessionPermissionPrompt
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ViewerSession internal constructor(
    val assetId: Long,
    val spaceId: Long,
    initialCapabilities: PageCapabilities,
    private val keyValueStore: SpaceKeyValueStore,
    private val domainPermissionStore:
        DomainPermissionStore,
    private val pageLibrary: PageLibrary,
    parentScope: CoroutineScope,
) : AutoCloseable {
    val sessionId: String =
        UUID.randomUUID().toString()

    private val closed =
        AtomicBoolean(false)
    private val persistedMask =
        AtomicLong(initialCapabilities.mask)
    private val onceMask =
        AtomicLong(PageCapabilities.NONE_MASK)
    private val rejectedMask =
        AtomicLong(PageCapabilities.NONE_MASK)
    private val activatedMask =
        AtomicLong(PageCapabilities.NONE_MASK)

    private val allowedDomains =
        ConcurrentHashMap.newKeySet<String>()
    private val rejectedDomains =
        ConcurrentHashMap.newKeySet<String>()

    private val sessionJob =
        SupervisorJob(
            parentScope.coroutineContext[Job],
        )
    val scope =
        CoroutineScope(
            parentScope.coroutineContext +
                sessionJob,
        )

    private val emitterRef =
        AtomicReference<CoralieEventEmitter?>(null)

    private val httpDispatcherDelegate =
        lazy(
            LazyThreadSafetyMode.SYNCHRONIZED,
        ) {
            AsyncHttpRequestDispatcher(
                session = this,
                scope = scope,
            )
        }
    private val httpDispatcher by
        httpDispatcherDelegate

    private val activationGate = Mutex()
    private val permissionGate = Mutex()
    private var pendingDecision:
        CompletableDeferred<PermissionDecision>? =
        null

    private val _permissionPrompt =
        MutableStateFlow<SessionPermissionPrompt?>(
            null,
        )
    val permissionPrompt:
        StateFlow<SessionPermissionPrompt?> =
        _permissionPrompt.asStateFlow()

    fun effectiveCapabilities():
        PageCapabilities =
        PageCapabilities(
            persistedMask.get() or
                onceMask.get(),
        )

    fun hasCapability(
        capability: PageCapability,
    ): Boolean =
        effectiveCapabilities()
            .allows(capability)

    suspend fun prepare() {
        checkOpen()

        for (
            capability in
            effectiveCapabilities().asSet()
        ) {
            ensureCapabilityReady(capability)
        }
    }

    fun attachWebView(webView: WebView) {
        checkOpen()

        val emitter =
            CoralieEventEmitter(webView)

        emitterRef
            .getAndSet(emitter)
            ?.close()

        scope.launchSafely(
            "activate-attached-capabilities",
        ) {
            for (
                capability in
                effectiveCapabilities().asSet()
            ) {
                ensureCapabilityReady(
                    capability,
                )
            }
        }
    }

    suspend fun authorizeCapability(
        capability: PageCapability,
        operation: String,
    ) {
        checkOpen()

        if (hasCapability(capability)) {
            ensureCapabilityReady(capability)
            return
        }

        if (
            rejectedMask.get() and
            capability.bit != 0L
        ) {
            throw rejectedCapability(
                capability,
                operation,
            )
        }

        permissionGate.withLock {
            if (hasCapability(capability)) {
                ensureCapabilityReady(
                    capability,
                )
                return@withLock
            }

            if (
                rejectedMask.get() and
                capability.bit != 0L
            ) {
                throw rejectedCapability(
                    capability,
                    operation,
                )
            }

            when (
                awaitDecision(
                    CapabilityPermissionPrompt(
                        capability,
                    ),
                )
            ) {
                PermissionDecision.ALLOW_ONCE -> {
                    onceMask.getAndUpdate {
                        it or capability.bit
                    }
                    ensureCapabilityReady(
                        capability,
                    )
                }

                PermissionDecision.ALLOW_ALWAYS -> {
                    val updated =
                        PageCapabilities(
                            persistedMask.get() or
                                capability.bit,
                        )

                    withContext(Dispatchers.IO) {
                        pageLibrary
                            .updatePageCapabilities(
                                assetId = assetId,
                                capabilities = updated,
                            )
                            .getOrThrow()
                    }

                    persistedMask.set(
                        updated.mask,
                    )
                    rejectedMask.getAndUpdate {
                        it and
                            capability.bit.inv()
                    }
                    ensureCapabilityReady(
                        capability,
                    )
                }

                PermissionDecision.REJECT -> {
                    rejectedMask.getAndUpdate {
                        it or capability.bit
                    }
                    throw rejectedCapability(
                        capability,
                        operation,
                    )
                }
            }
        }
    }

    suspend fun authorizeDomain(
        domain: String,
        operation: String,
    ) {
        checkOpen()

        val normalized =
            normalizeDomain(domain)

        if (
            normalized in allowedDomains ||
            isPersistedDomainAllowed(
                normalized,
            )
        ) {
            return
        }

        if (normalized in rejectedDomains) {
            throw rejectedDomain(
                normalized,
                operation,
            )
        }

        permissionGate.withLock {
            if (
                normalized in allowedDomains ||
                isPersistedDomainAllowed(
                    normalized,
                )
            ) {
                return@withLock
            }

            if (
                normalized in rejectedDomains
            ) {
                throw rejectedDomain(
                    normalized,
                    operation,
                )
            }

            when (
                awaitDecision(
                    DomainPermissionPrompt(
                        normalized,
                    ),
                )
            ) {
                PermissionDecision.ALLOW_ONCE -> {
                    allowedDomains += normalized
                }

                PermissionDecision.ALLOW_ALWAYS -> {
                    withContext(Dispatchers.IO) {
                        domainPermissionStore
                            .allow(normalized)
                            .getOrThrow()
                    }
                    allowedDomains += normalized
                    rejectedDomains -= normalized
                }

                PermissionDecision.REJECT -> {
                    rejectedDomains += normalized
                    throw rejectedDomain(
                        normalized,
                        operation,
                    )
                }
            }
        }
    }

    internal fun startHttpRequest(
        requestId: String,
        requestJson: String,
    ) {
        checkOpen()
        httpDispatcher.start(
            requestId = requestId,
            requestJson = requestJson,
        )
    }

    internal fun cancelHttpRequest(
        requestId: String,
    ): Boolean =
        if (closed.get()) {
            false
        } else {
            httpDispatcher.cancel(
                requestId,
            )
        }

    internal fun emitHttpSuccess(
        requestId: String,
        responseJson: String,
    ) {
        emitterRef.get()
            ?.emitHttpSuccess(
                requestId =
                    requestId,
                responseJson =
                    responseJson,
            )
            ?: Log.w(
                TAG,
                "http.result.drop " +
                    "id=$requestId " +
                    "reason=emitter-unavailable",
            )
    }

    internal fun emitHttpFailure(
        requestId: String,
        errorName: String,
        message: String,
        scope: String? = null,
        target: String? = null,
        operation: String? = null,
    ) {
        emitterRef.get()
            ?.emitHttpFailure(
                requestId =
                    requestId,
                errorName =
                    errorName,
                message = message,
                scope = scope,
                target = target,
                operation =
                    operation,
            )
            ?: Log.w(
                TAG,
                "http.result.drop " +
                    "id=$requestId " +
                    "reason=emitter-unavailable",
            )
    }

    fun resolvePermissionPrompt(
        decision: PermissionDecision,
    ) {
        pendingDecision?.complete(decision)
    }

    fun requireStorage():
        SpaceKeyValueStore {
        checkOpen()
        check(
            hasCapability(
                PageCapability.STORAGE,
            ),
        ) {
            "Storage capability is not active"
        }
        return keyValueStore
    }

    suspend fun ensureCapabilityReady(
        capability: PageCapability,
    ) {
        checkOpen()

        if (!hasCapability(capability)) {
            return
        }

        activationGate.withLock {
            if (
                activatedMask.get() and
                capability.bit != 0L
            ) {
                return
            }

            when (capability) {
                PageCapability.STORAGE -> Unit

                PageCapability.MESH ->
                    withContext(
                        Dispatchers.Main.immediate,
                    ) {
                        val emitter =
                            emitterRef.get()
                                ?: return@withContext
                        AppMesh.attach(
                            scope,
                            emitter::emit,
                        )
                        AppMesh.rebuild()
                    }

                PageCapability.TIMERS ->
                    withContext(
                        Dispatchers.Main.immediate,
                    ) {
                        val emitter =
                            emitterRef.get()
                                ?: return@withContext
                        AppTimers.attach(
                            scope,
                            emitter::emit,
                        )
                    }

                PageCapability.HTTP -> Unit
            }

            if (
                capability ==
                PageCapability.MESH ||
                capability ==
                PageCapability.TIMERS
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
        if (
            !closed.compareAndSet(
                false,
                true,
            )
        ) {
            return
        }

        if (
            httpDispatcherDelegate
                .isInitialized()
        ) {
            httpDispatcher.close()
        }

        pendingDecision?.complete(
            PermissionDecision.REJECT,
        )
        pendingDecision = null
        _permissionPrompt.value = null

        emitterRef
            .getAndSet(null)
            ?.close()

        val activated =
            activatedMask.getAndSet(0L)

        if (
            activated and
            PageCapability.MESH.bit != 0L
        ) {
            AppMesh.teardownForPageExit()
        }

        if (
            activated and
            PageCapability.TIMERS.bit != 0L
        ) {
            AppTimers.teardownForPageExit()
        }

        keyValueStore.close()
        allowedDomains.clear()
        rejectedDomains.clear()
        scope.cancel(
            "ViewerSession closed",
        )

        Log.i(
            TAG,
            "session.closed " +
                "id=$sessionId " +
                "assetId=$assetId",
        )
    }

    private suspend fun awaitDecision(
        prompt: SessionPermissionPrompt,
    ): PermissionDecision {
        val deferred =
            CompletableDeferred<
                PermissionDecision
            >()

        pendingDecision = deferred
        _permissionPrompt.value = prompt

        Log.i(
            TAG,
            "permission.prompt " +
                "id=$sessionId " +
                "assetId=$assetId " +
                "target=${prompt.logTarget()}",
        )

        return try {
            deferred.await()
        } finally {
            _permissionPrompt.value = null
            pendingDecision = null
        }
    }

    private suspend fun isPersistedDomainAllowed(
        domain: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            domainPermissionStore
                .isAllowed(domain)
                .getOrDefault(false)
        }

    private fun rejectedCapability(
        capability: PageCapability,
        operation: String,
    ): PermissionRejectedException =
        PermissionRejectedException(
            scope =
                PermissionScope.CAPABILITY,
            target = capability.wireName,
            operation = operation,
        )

    private fun rejectedDomain(
        domain: String,
        operation: String,
    ): PermissionRejectedException =
        PermissionRejectedException(
            scope =
                PermissionScope.DOMAIN,
            target = domain,
            operation = operation,
        )

    private fun normalizeDomain(
        domain: String,
    ): String =
        domain.trim()
            .trimEnd('.')
            .lowercase()

    private fun SessionPermissionPrompt
        .logTarget(): String =
        when (this) {
            is CapabilityPermissionPrompt ->
                "capability:" +
                    capability.wireName
            is DomainPermissionPrompt ->
                "domain:$domain"
        }

    private fun checkOpen() {
        check(!closed.get()) {
            "Viewer session is closed"
        }
    }

    private fun CoroutineScope.launchSafely(
        operation: String,
        block:
            suspend CoroutineScope.() -> Unit,
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
                        "exception=" +
                        error.javaClass.name +
                        " message=" +
                        error.message,
                    error,
                )
            }
        }
    }

    private companion object {
        const val TAG =
            "CoralieSession"
    }
}
