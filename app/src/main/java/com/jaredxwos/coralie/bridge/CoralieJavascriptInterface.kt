package com.jaredxwos.coralie.bridge

import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import com.jaredxwos.coralie.capability.PageCapabilities
import com.jaredxwos.coralie.capability.PageCapability
import com.jaredxwos.coralie.connection.manager.ConnectionManager
import com.jaredxwos.coralie.mesh.AppMesh
import com.jaredxwos.coralie.storage.AppStorage
import com.jaredxwos.coralie.storage.HtmlStorage
import com.jaredxwos.coralie.timer.AppTimers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import androidx.core.net.toUri

/**
 * Android implementation of the page-facing `window.Coralie` object.
 *
 * Every JavaScript-facing operation logs its own operation name before
 * rethrowing. HTTP additionally returns structured status-599 diagnostics so
 * WebView does not replace the useful Kotlin error with the generic
 * "Java exception was raised during method invocation" message.
 */
class CoralieJavascriptInterface(
    private val capabilities: PageCapabilities,
) {

    @JavascriptInterface
    fun apiVersion(): Int = 2

    @JavascriptInterface
    fun hostKind(): String = "android-native"

    /** Granted capability names, encoded as a JSON string array. */
    @JavascriptInterface
    fun capabilitiesJson(): String = capabilities.toJson()

    @JavascriptInterface
    fun getPubkey(): String =
        loggedCall("getPubkey") {
            requireCapability(
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
            requireCapability(
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
    fun sendMessage(toPubkeyHex: String, payload: IntArray) {
        loggedCall(
            operation = "sendMessage",
            details = "peer=${shortPubkey(toPubkeyHex)} payloadBytes=${payload.size}",
        ) {
            requireCapability(
                PageCapability.MESH,
                "sendMessage",
            )
            requirePubkey(toPubkeyHex, "toPubkeyHex")

            val bytes = ByteArray(payload.size) { index ->
                val value = payload[index]
                require(value in 0..255) {
                    "payload[$index] must be between 0 and 255"
                }
                value.toByte()
            }

            runBlocking {
                requireMesh()
                    .sendMessage(toPubkeyHex.lowercase(), bytes)
                    .getOrThrow()
            }
        }
    }

    /** Returns a JSON array of `{pubkeyHex, connectedAt}` objects. */
    @JavascriptInterface
    fun getPeersJson(): String =
        loggedCall("getPeersJson") {
            requireCapability(
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
            requireCapability(
                PageCapability.MESH,
                "reset",
            )
            AppMesh.rebuild()
        }

    @JavascriptInterface
    fun close() {
        loggedCall("close") {
            requireCapability(
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
            requireCapability(
                PageCapability.STORAGE,
                "storageGetItem",
            )
            runBlocking {
                val result = requireStorage().retrieveValue(key)
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
            requireCapability(
                PageCapability.STORAGE,
                "storageSetItem",
            )
            runBlocking {
                requireStorage()
                    .updateValue(key, value, upsert = true)
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
            requireCapability(
                PageCapability.STORAGE,
                "storageRemoveItem",
            )
            runBlocking {
                val result = requireStorage().deleteItem(key)
                val error = result.exceptionOrNull()
                if (error != null && error !is NoSuchElementException) {
                    throw error
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // HTTP. Objects cannot cross addJavascriptInterface directly, so request
    // and response objects are encoded as JSON strings.
    // ---------------------------------------------------------------------

    @JavascriptInterface
    fun httpRequestJson(requestJson: String): String {
        val requestId = HTTP_REQUEST_IDS.getAndIncrement()
        val startedAt = SystemClock.elapsedRealtime()

        var stage = "parse-request"
        var method = "UNKNOWN"
        var safeUrl = "(unparsed)"
        var headerNames: List<String> = emptyList()
        var requestBodyBytes = 0

        return try {
            stage = "capability-check"
            requireCapability(
                PageCapability.HTTP,
                "httpRequestJson",
            )

            stage = "parse-request"
            val params = Json.parseToJsonElement(requestJson)
            val requestObject = params.jsonObject

            val rawUrl = requestObject["url"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?: throw IllegalArgumentException(
                    "request.url must be a string"
                )

            method = requestObject["method"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.uppercase()
                ?: "GET"

            safeUrl = safeUrlForLog(rawUrl)

            headerNames =
                (requestObject["headers"] as? JsonObject)
                    ?.keys
                    ?.sortedBy { it.lowercase() }
                    ?: emptyList()

            requestBodyBytes =
                requestObject["body"]
                    ?.takeUnless { it is JsonNull }
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toByteArray(Charsets.UTF_8)
                    ?.size
                    ?: 0

            Log.i(
                TAG,
                buildString {
                    append("http.start")
                    append(" id=").append(requestId)
                    append(" method=").append(method)
                    append(" url=").append(safeUrl)
                    append(" headers=").append(headerNames)
                    append(" bodyBytes=").append(requestBodyBytes)
                    append(" thread=").append(Thread.currentThread().name)
                },
            )

            stage = "native-proxy"
            val response = runBlocking {
                // @JavascriptInterface methods already run off the Android UI
                // thread. AppProxy uses thread-safe StateFlow for permission
                // prompts and Dispatchers.IO for the network request, so moving
                // the whole operation onto Main would freeze Compose/WebView UI.
                proxyHttpRequest(params)
            }

            stage = "validate-response"
            val responseObject = response.jsonObject
            val status =
                responseObject["status"]
                    ?.jsonPrimitive
                    ?.intOrNull
            val statusText =
                responseObject["statusText"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
            val responseBodyBytes =
                responseObject["body"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toByteArray(Charsets.UTF_8)
                    ?.size
                    ?: 0
            val contentType =
                (responseObject["headers"] as? JsonObject)
                    ?.entries
                    ?.firstOrNull {
                        it.key.equals(
                            "content-type",
                            ignoreCase = true,
                        )
                    }
                    ?.value
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.take(120)

            val elapsedMs =
                SystemClock.elapsedRealtime() - startedAt

            val completionMessage = buildString {
                append("http.finish")
                append(" id=").append(requestId)
                append(" method=").append(method)
                append(" url=").append(safeUrl)
                append(" status=").append(status ?: "missing")
                append(" statusText=").append(
                    oneLine(statusText, 100)
                )
                append(" elapsedMs=").append(elapsedMs)
                append(" responseBodyBytes=").append(
                    responseBodyBytes,
                )
                if (!contentType.isNullOrBlank()) {
                    append(" contentType=").append(contentType)
                }
            }

            if (status != null && status in 200..399) {
                Log.i(TAG, completionMessage)
            } else {
                Log.w(TAG, completionMessage)
            }

            response.toString()
        } catch (error: Exception) {
            val elapsedMs =
                SystemClock.elapsedRealtime() - startedAt
            val category = classifyHttpFailure(error)
            val root = rootCause(error)

            Log.e(
                TAG,
                buildString {
                    append("http.fail")
                    append(" id=").append(requestId)
                    append(" stage=").append(stage)
                    append(" category=").append(category)
                    append(" method=").append(method)
                    append(" url=").append(safeUrl)
                    append(" elapsedMs=").append(elapsedMs)
                    append(" requestChars=").append(
                        requestJson.length,
                    )
                    append(" headerNames=").append(headerNames)
                    append(" requestBodyBytes=").append(
                        requestBodyBytes,
                    )
                    append(" exception=").append(
                        error.javaClass.name,
                    )
                    append(" rootException=").append(
                        root.javaClass.name,
                    )
                    append(" message=").append(
                        oneLine(
                            error.message
                                ?: root.message
                                ?: "(no message)",
                            400,
                        ),
                    )
                    append(" causeChain=").append(
                        causeChain(error),
                    )
                },
                error,
            )

            nativeHttpFailureResponse(
                requestId = requestId,
                stage = stage,
                category = category,
                method = method,
                safeUrl = safeUrl,
                elapsedMs = elapsedMs,
                error = error,
            )
        }
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
            requireCapability(
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
            requireCapability(
                PageCapability.TIMERS,
                "timerCancel",
            )
            AppTimers.cancel(id)
        }
    }

    @JavascriptInterface
    fun timerListJson(): String =
        loggedCall("timerListJson") {
            requireCapability(
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

    private fun requireCapability(
        capability: PageCapability,
        operation: String,
    ) {
        try {
            capabilities.require(
                capability = capability,
                operation = operation,
            )
        } catch (error: SecurityException) {
            Log.w(
                TAG,
                "capability.denied " +
                    "operation=$operation " +
                    "required=${capability.wireName} " +
                    "granted=${capabilities.toJson()}",
            )
            throw error
        }
    }

    private fun requireMesh(): ConnectionManager =
        AppMesh.current
            ?: throw IllegalStateException(
                "Coralie mesh is closed",
            )

    private fun requireStorage(): HtmlStorage =
        AppStorage.current
            ?: throw IllegalStateException(
                "No space is currently open",
            )

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

    /**
     * Keep native HTTP exceptions from crossing addJavascriptInterface. Status
     * 599 is outside the normal HTTP range and is handled by the page as a
     * native transport failure.
     */
    private fun nativeHttpFailureResponse(
        requestId: Long,
        stage: String,
        category: String,
        method: String,
        safeUrl: String,
        elapsedMs: Long,
        error: Exception,
    ): String {
        val root = rootCause(error)
        val message =
            error.message
                ?.takeIf { it.isNotBlank() }
                ?: root.message
                    ?.takeIf { it.isNotBlank() }
                ?: error.javaClass.simpleName

        return buildJsonObject {
            put("status", 599)
            put("statusText", "Native HTTP failure")
            put("headers", buildJsonObject {})
            put(
                "body",
                buildJsonObject {
                    put("requestId", requestId)
                    put("stage", stage)
                    put("category", category)
                    put("method", method)
                    put("url", safeUrl)
                    put("elapsedMs", elapsedMs)
                    put("message", oneLine(message, 800))
                    put(
                        "exception",
                        error.javaClass.name,
                    )
                    put(
                        "rootException",
                        root.javaClass.name,
                    )
                    put(
                        "causeChain",
                        causeChain(error),
                    )
                }.toString(),
            )
        }.toString()
    }

    private fun classifyHttpFailure(
        error: Throwable,
    ): String {
        val chainText =
            generateSequence(error) { it.cause }
                .take(MAX_CAUSE_DEPTH)
                .joinToString(" ") {
                    "${it.javaClass.name} ${it.message.orEmpty()}"
                }
                .lowercase()

        if (
            "capability" in chainText &&
            "not granted" in chainText
        ) {
            return "capability-denied"
        }

        if (
            "rejected" in chainText ||
            "denied" in chainText ||
            "not allowed" in chainText
        ) {
            return "permission-denied"
        }

        val root = rootCause(error)
        return when (root) {
            is CancellationException -> "cancelled"
            is SocketTimeoutException -> "timeout"
            is UnknownHostException -> "dns"
            is SSLException -> "tls"
            is SecurityException -> "security"
            is SerializationException -> "invalid-json"
            is IllegalArgumentException -> "invalid-request"
            is IllegalStateException -> "invalid-state"
            is IOException -> "network-io"
            else -> "internal"
        }
    }

    private fun rootCause(
        error: Throwable,
    ): Throwable =
        generateSequence(error) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .last()

    private fun causeChain(
        error: Throwable,
    ): String =
        generateSequence(error) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .joinToString(" <- ") {
                buildString {
                    append(it.javaClass.simpleName)
                    val message = it.message
                    if (!message.isNullOrBlank()) {
                        append(": ")
                        append(oneLine(message, 240))
                    }
                }
            }

    private fun safeUrlForLog(
        rawUrl: String,
    ): String {
        val uri = rawUrl.toUri()
        val scheme = uri.scheme ?: "(no-scheme)"
        val host = uri.host ?: "(no-host)"
        val port =
            if (uri.port >= 0) ":${uri.port}" else ""
        val path =
            uri.encodedPath
                ?.takeIf { it.isNotBlank() }
                ?: "/"

        // Query parameters can contain cursors, tokens, or user data.
        return "$scheme://$host$port$path"
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
        const val TAG = "CoralieJsInterface"
        const val MAX_CAUSE_DEPTH = 8

        val PUBKEY_REGEX =
            Regex("^[0-9a-fA-F]{64}$")

        val HTTP_REQUEST_IDS =
            AtomicLong(1)
    }
}
