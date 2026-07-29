package com.jaredxwos.coralie.feature.viewer.bridge

import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import com.jaredxwos.coralie.data.library.model.PageCapability
import com.jaredxwos.coralie.connection.manager.ConnectionManager
import com.jaredxwos.coralie.feature.viewer.runtime.mesh.AppMesh
import com.jaredxwos.coralie.data.space.SpaceKeyValueStore
import com.jaredxwos.coralie.feature.viewer.runtime.timer.AppTimers
import com.jaredxwos.coralie.feature.viewer.runtime.permission.PermissionRejectedException
import com.jaredxwos.coralie.feature.viewer.runtime.ViewerSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Private Android implementation exposed to JavaScript as `CoralieNative`.
 *
 * `/Coralie/v2/host.js` wraps this object and publishes the page-facing
 * `window.Coralie` API. HTTP is started here without blocking the JavaScript
 * bridge thread; completion is delivered through `coralie:httpResult`.
 */
class CoralieJavascriptInterface(
    private val session: ViewerSession,
) {

    @JavascriptInterface
    fun apiVersion(): Int = 2

    @JavascriptInterface
    fun hostKind(): String = "android-native"

    @JavascriptInterface
    fun getPubkey(): String =
        loggedCall("getPubkey") {
            authorizeCapability(
                PageCapability.MESH,
                "getPubkey",
            )
            requireMesh().myPubkeyHex
        }

    @JavascriptInterface
    fun addPeer(pubkeyHex: String) {
        loggedCall(
            operation = "addPeer",
            details = "peer=${shortPubkey(pubkeyHex)}",
        ) {
            authorizeCapability(
                PageCapability.MESH,
                "addPeer",
            )
            requirePubkey(pubkeyHex, "pubkeyHex")
            requireMesh().addPeer(pubkeyHex.lowercase())
        }
    }

    /**
     * WebView's Java bridge supports ES6 typed arrays as array-like arguments,
     * so page code may pass a Uint8Array directly.
     */
    @JavascriptInterface
    fun sendMessage(
        toPubkeyHex: String,
        payload: IntArray,
    ): String {
        val normalizedPubkey =
            toPubkeyHex.lowercase()

        val bytes =
            try {
                loggedCall(
                    operation = "sendMessage",
                    details =
                        "peer=${shortPubkey(toPubkeyHex)} " +
                            "payloadBytes=${payload.size}",
                ) {
                    authorizeCapability(
                        PageCapability.MESH,
                        "sendMessage",
                    )
                    requirePubkey(
                        toPubkeyHex,
                        "toPubkeyHex",
                    )

                    ByteArray(payload.size) { index ->
                        val value = payload[index]
                        require(value in 0..255) {
                            "payload[$index] must be between 0 and 255"
                        }
                        value.toByte()
                    }
                }
            } catch (error: Exception) {
                return sendMessageFailureResponse(
                    error = error,
                    target = normalizedPubkey,
                    peerUnavailable = false,
                )
            }

        val result =
            try {
                runBlocking {
                    requireMesh().sendMessage(
                        normalizedPubkey,
                        bytes,
                    )
                }
            } catch (error: Exception) {
                Result.failure(error)
            }

        return result.fold(
            onSuccess = {
                sendMessageSuccessResponse()
            },
            onFailure = { error ->
                Log.w(
                    TAG,
                    "call.rejected operation=sendMessage " +
                        "peer=${shortPubkey(toPubkeyHex)} " +
                        "reason=peer-unavailable " +
                        "exception=${error.javaClass.name} " +
                        "message=${oneLine(error.message.orEmpty(), 240)}",
                )
                sendMessageFailureResponse(
                    error =
                        error as? Exception
                            ?: IllegalStateException(
                                "Message delivery failed",
                                error,
                            ),
                    target = normalizedPubkey,
                    peerUnavailable = true,
                )
            },
        )
    }

    /** Returns a JSON array of `{pubkeyHex, connectedAt}` objects. */
    @JavascriptInterface
    fun getPeersJson(): String =
        loggedCall("getPeersJson") {
            authorizeCapability(
                PageCapability.MESH,
                "getPeersJson",
            )
            buildJsonArray {
                requireMesh().peers.value.forEach { pubkeyHex ->
                    add(buildJsonObject {
                        put("pubkeyHex", pubkeyHex)
                        put("connectedAt", JsonNull)
                    })
                }
            }.toString()
        }

    @JavascriptInterface
    fun reset(): String =
        loggedCall("reset") {
            authorizeCapability(
                PageCapability.MESH,
                "reset",
            )
            AppMesh.rebuild()
        }

    @JavascriptInterface
    fun close() {
        loggedCall("close") {
            authorizeCapability(
                PageCapability.MESH,
                "close",
            )
            AppMesh.teardownForPageExit()
        }
    }

    // ---------------------------------------------------------------------
    // Storage. These methods mirror localStorage semantics:
    // - missing get -> null
    // - removing a missing key -> no-op
    // ---------------------------------------------------------------------

    @JavascriptInterface
    fun storageGetItem(key: String): String? =
        loggedCall(
            operation = "storageGetItem",
            details = "key=${safeStorageKey(key)}",
        ) {
            authorizeCapability(
                PageCapability.STORAGE,
                "storageGetItem",
            )
            runBlocking {
                val result = requireStorage().get(key)
                val error = result.exceptionOrNull()
                when {
                    error == null -> result.getOrThrow()
                    error is NoSuchElementException -> null
                    else -> throw error
                }
            }
        }

    @JavascriptInterface
    fun storageSetItem(key: String, value: String) {
        loggedCall(
            operation = "storageSetItem",
            details = "key=${safeStorageKey(key)} valueChars=${value.length}",
        ) {
            authorizeCapability(
                PageCapability.STORAGE,
                "storageSetItem",
            )
            runBlocking {
                requireStorage()
                    .set(
                        name = key,
                        value = value,
                        upsert = true,
                    )
                    .getOrThrow()
            }
        }
    }

    @JavascriptInterface
    fun storageRemoveItem(key: String) {
        loggedCall(
            operation = "storageRemoveItem",
            details = "key=${safeStorageKey(key)}",
        ) {
            authorizeCapability(
                PageCapability.STORAGE,
                "storageRemoveItem",
            )
            runBlocking {
                val result = requireStorage().remove(key)
                val error = result.exceptionOrNull()
                if (error != null && error !is NoSuchElementException) {
                    throw error
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // HTTP. The JavaScript facade creates the request ID and Promise before
    // calling this non-blocking starter, preventing completion races.
    // ---------------------------------------------------------------------

    @JavascriptInterface
    fun httpRequestStart(
        requestId: String,
        requestJson: String,
    ) {
        loggedCall(
            operation = "httpRequestStart",
            details =
                "id=${oneLine(requestId, 128)} " +
                    "requestChars=${requestJson.length}",
        ) {
            session.startHttpRequest(
                requestId = requestId,
                requestJson = requestJson,
            )
        }
    }

    @JavascriptInterface
    fun httpRequestCancel(
        requestId: String,
    ) {
        session.cancelHttpRequest(requestId)
    }

    // ---------------------------------------------------------------------
    // Existing native timers retained for older pages.
    // ---------------------------------------------------------------------

    @JavascriptInterface
    fun timerQueue(
        id: String?,
        delaySeconds: Long,
        payload: String?,
    ): String =
        loggedCall(
            operation = "timerQueue",
            details =
                "id=${oneLine(id ?: "(generated)", 80)} " +
                    "delaySeconds=$delaySeconds " +
                    "payloadChars=${payload?.length ?: 0}",
        ) {
            authorizeCapability(
                PageCapability.TIMERS,
                "timerQueue",
            )
            require(delaySeconds > 0) {
                "delaySeconds must be positive"
            }
            AppTimers.queue(
                id ?: UUID.randomUUID().toString(),
                delaySeconds,
                payload,
            )
        }

    @JavascriptInterface
    fun timerCancel(id: String) {
        loggedCall(
            operation = "timerCancel",
            details = "id=${oneLine(id, 80)}",
        ) {
            authorizeCapability(
                PageCapability.TIMERS,
                "timerCancel",
            )
            AppTimers.cancel(id)
        }
    }

    @JavascriptInterface
    fun timerListJson(): String =
        loggedCall("timerListJson") {
            authorizeCapability(
                PageCapability.TIMERS,
                "timerListJson",
            )
            buildJsonArray {
                AppTimers.list().forEach { (id, remainingMs) ->
                    add(buildJsonObject {
                        put("id", id)
                        put("remainingMs", remainingMs)
                    })
                }
            }.toString()
        }

    private fun authorizeCapability(
        capability: PageCapability,
        operation: String,
    ) {
        runBlocking {
            session.authorizeCapability(
                capability = capability,
                operation = operation,
            )
        }
    }

    private fun requireMesh(): ConnectionManager =
        AppMesh.current
            ?: throw IllegalStateException(
                "Coralie mesh is closed",
            )

    private fun requireStorage():
        SpaceKeyValueStore =
        session.requireStorage()

    private fun requirePubkey(
        value: String,
        fieldName: String,
    ) {
        require(PUBKEY_REGEX.matches(value)) {
            "$fieldName must be a 64-character hexadecimal public key"
        }
    }

    private inline fun <T> loggedCall(
        operation: String,
        details: String = "",
        block: () -> T,
    ): T {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            block()
        } catch (error: PermissionRejectedException) {
            val elapsedMs =
                SystemClock.elapsedRealtime() - startedAt
            Log.w(
                TAG,
                buildString {
                    append("call.rejected")
                    append(" operation=").append(operation)
                    if (details.isNotBlank()) {
                        append(' ').append(details)
                    }
                    append(" scope=").append(
                        error.scope.name.lowercase(),
                    )
                    append(" target=").append(
                        oneLine(error.target, 160),
                    )
                    append(" elapsedMs=").append(elapsedMs)
                },
            )
            throw error
        } catch (error: Exception) {
            val elapsedMs =
                SystemClock.elapsedRealtime() - startedAt
            Log.e(
                TAG,
                buildString {
                    append("call.fail")
                    append(" operation=").append(operation)
                    if (details.isNotBlank()) {
                        append(' ').append(details)
                    }
                    append(" elapsedMs=").append(elapsedMs)
                    append(" thread=").append(
                        Thread.currentThread().name,
                    )
                    append(" exception=").append(
                        error.javaClass.name,
                    )
                    append(" message=").append(
                        oneLine(
                            error.message ?: "(no message)",
                            400,
                        ),
                    )
                    append(" causeChain=").append(
                        causeChain(error),
                    )
                },
                error,
            )
            throw error
        }
    }

    private fun causeChain(
        error: Throwable,
    ): String =
        generateSequence(error) {
            it.cause
        }
            .take(MAX_CAUSE_DEPTH)
            .joinToString(" <- ") {
                buildString {
                    append(
                        it.javaClass
                            .simpleName,
                    )

                    val message =
                        it.message

                    if (
                        !message
                            .isNullOrBlank()
                    ) {
                        append(": ")
                        append(
                            oneLine(
                                message,
                                240,
                            ),
                        )
                    }
                }
            }

    private fun safeStorageKey(
        key: String,
    ): String =
        oneLine(key, 80)

    private fun shortPubkey(
        pubkeyHex: String,
    ): String =
        when {
            pubkeyHex.length <= 20 -> oneLine(pubkeyHex, 20)
            else ->
                "${pubkeyHex.take(8)}…${pubkeyHex.takeLast(8)}"
        }

    private fun oneLine(
        value: String,
        maxLength: Int,
    ): String =
        value
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLength)

    private companion object {
        const val TAG =
            "CoralieJsInterface"
        const val MAX_CAUSE_DEPTH = 8

        val PUBKEY_REGEX =
            Regex("^[0-9a-fA-F]{64}$")
    }
}
