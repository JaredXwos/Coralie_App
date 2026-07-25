package com.jaredxwos.coralie.feature.viewer.runtime.http

import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import com.jaredxwos.coralie.data.library.model.PageCapability
import com.jaredxwos.coralie.feature.viewer.runtime.ViewerSession
import com.jaredxwos.coralie.feature.viewer.runtime.permission.PermissionRejectedException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Owns all asynchronous HTTP requests for one ViewerSession.
 *
 * Each request maps one JavaScript Promise ID to one coroutine Job and, once
 * the network stage begins, one OkHttp Call. Closing the dispatcher cancels
 * both immediately.
 */
internal class AsyncHttpRequestDispatcher(
    private val session: ViewerSession,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val closed =
        AtomicBoolean(false)

    private val requests =
        ConcurrentHashMap<String, Job>()

    fun start(
        requestId: String,
        requestJson: String,
    ) {
        check(!closed.get()) {
            "HTTP dispatcher is closed"
        }
        require(
            REQUEST_ID_REGEX.matches(
                requestId,
            ),
        ) {
            "requestId must contain 1-128 safe characters"
        }

        val job =
            scope.launch(
                start = CoroutineStart.LAZY,
            ) {
                execute(
                    requestId =
                        requestId,
                    requestJson =
                        requestJson,
                )
            }

        check(
            requests.putIfAbsent(
                requestId,
                job,
            ) == null,
        ) {
            "Duplicate HTTP request ID: $requestId"
        }

        job.invokeOnCompletion {
            requests.remove(
                requestId,
                job,
            )
        }
        job.start()
    }

    fun cancel(
        requestId: String,
    ): Boolean {
        val job =
            requests[requestId]
                ?: return false

        NativeHttpProxy.cancel(requestId)
        job.cancel(
            CancellationException(
                "HTTP request cancelled: " +
                    requestId,
            ),
        )
        return true
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

        val snapshot =
            requests.entries.toList()

        snapshot.forEach {
                (requestId, job) ->
            NativeHttpProxy.cancel(
                requestId,
            )
            job.cancel(
                CancellationException(
                    "Viewer session closed",
                ),
            )
        }

        requests.clear()
    }

    private suspend fun execute(
        requestId: String,
        requestJson: String,
    ) {
        val startedAt =
            SystemClock.elapsedRealtime()

        var stage = "capability-check"
        var method = "UNKNOWN"
        var safeUrl = "(unparsed)"
        var headerNames:
            List<String> =
            emptyList()
        var requestBodyBytes = 0

        try {
            session.authorizeCapability(
                capability =
                    PageCapability.HTTP,
                operation =
                    HTTP_OPERATION,
            )

            stage = "parse-request"
            val params =
                Json.parseToJsonElement(
                    requestJson,
                )
            val requestObject =
                params.jsonObject

            val rawUrl =
                requestObject["url"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: throw
                    IllegalArgumentException(
                        "request.url must be a string",
                    )

            method =
                requestObject["method"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.uppercase()
                    ?: "GET"

            safeUrl =
                safeUrlForLog(rawUrl)

            headerNames =
                (
                    requestObject[
                        "headers"
                    ] as? JsonObject
                )
                    ?.keys
                    ?.sortedBy {
                        it.lowercase()
                    }
                    ?: emptyList()

            requestBodyBytes =
                requestObject["body"]
                    ?.takeUnless {
                        it is JsonNull
                    }
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toByteArray(
                        Charsets.UTF_8,
                    )
                    ?.size
                    ?: 0

            Log.i(
                TAG,
                buildString {
                    append("http.start")
                    append(" id=")
                    append(requestId)
                    append(" method=")
                    append(method)
                    append(" url=")
                    append(safeUrl)
                    append(" headers=")
                    append(headerNames)
                    append(" bodyBytes=")
                    append(requestBodyBytes)
                    append(" thread=")
                    append(
                        Thread.currentThread()
                            .name,
                    )
                },
            )

            stage = "native-proxy"
            val response =
                proxyHttpRequest(
                    requestId =
                        requestId,
                    params = params,
                ) { domain ->
                    session.authorizeDomain(
                        domain = domain,
                        operation =
                            HTTP_OPERATION,
                    )
                }

            stage = "validate-response"
            logResponse(
                requestId =
                    requestId,
                method = method,
                safeUrl = safeUrl,
                startedAt = startedAt,
                response =
                    response.jsonObject,
            )

            session.emitHttpSuccess(
                requestId =
                    requestId,
                responseJson =
                    response.toString(),
            )
        } catch (
            error:
                PermissionRejectedException
        ) {
            val elapsedMs =
                SystemClock.elapsedRealtime() -
                    startedAt

            Log.w(
                TAG,
                "http.rejected " +
                    "id=$requestId " +
                    "stage=$stage " +
                    "scope=" +
                    error.scope.name
                        .lowercase() +
                    " target=" +
                    oneLine(
                        error.target,
                        160,
                    ) +
                    " operation=" +
                    error.operation +
                    " elapsedMs=$elapsedMs",
            )

            session.emitHttpFailure(
                requestId = requestId,
                errorName =
                    error.javaClass
                        .simpleName,
                message =
                    error.message
                        ?: "HTTP permission rejected",
                scope =
                    error.scope.name
                        .lowercase(),
                target = error.target,
                operation =
                    error.operation,
            )
        } catch (
            error:
                CancellationException
        ) {
            Log.i(
                TAG,
                "http.cancel " +
                    "id=$requestId " +
                    "stage=$stage",
            )

            // On page/session shutdown there is no live Promise to settle.
            if (!closed.get()) {
                session.emitHttpFailure(
                    requestId = requestId,
                    errorName =
                        "AbortError",
                    message =
                        error.message
                            ?: "HTTP request cancelled",
                    operation =
                        HTTP_OPERATION,
                )
            }

            throw error
        } catch (error: Exception) {
            val elapsedMs =
                SystemClock.elapsedRealtime() -
                    startedAt
            val category =
                classifyHttpFailure(error)
            val root =
                rootCause(error)

            Log.e(
                TAG,
                buildString {
                    append("http.fail")
                    append(" id=")
                    append(requestId)
                    append(" stage=")
                    append(stage)
                    append(" category=")
                    append(category)
                    append(" method=")
                    append(method)
                    append(" url=")
                    append(safeUrl)
                    append(" elapsedMs=")
                    append(elapsedMs)
                    append(" requestChars=")
                    append(
                        requestJson.length,
                    )
                    append(" headerNames=")
                    append(headerNames)
                    append(
                        " requestBodyBytes=",
                    )
                    append(requestBodyBytes)
                    append(" exception=")
                    append(
                        error.javaClass.name,
                    )
                    append(" rootException=")
                    append(
                        root.javaClass.name,
                    )
                    append(" message=")
                    append(
                        oneLine(
                            error.message
                                ?: root.message
                                ?: "(no message)",
                            400,
                        ),
                    )
                    append(" causeChain=")
                    append(
                        causeChain(error),
                    )
                },
                error,
            )

            // Preserve the existing API contract: transport/native failures
            // resolve with status 599. Only permission rejection rejects.
            session.emitHttpSuccess(
                requestId = requestId,
                responseJson =
                    nativeHttpFailureResponse(
                        requestId =
                            requestId,
                        stage = stage,
                        category =
                            category,
                        method = method,
                        safeUrl =
                            safeUrl,
                        elapsedMs =
                            elapsedMs,
                        error = error,
                    ),
            )
        }
    }

    private fun logResponse(
        requestId: String,
        method: String,
        safeUrl: String,
        startedAt: Long,
        response: JsonObject,
    ) {
        val status =
            response["status"]
                ?.jsonPrimitive
                ?.intOrNull
        val statusText =
            response["statusText"]
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
        val responseBodyBytes =
            response["body"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toByteArray(
                    Charsets.UTF_8,
                )
                ?.size
                ?: 0
        val contentType =
            (
                response[
                    "headers"
                ] as? JsonObject
            )
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
            SystemClock.elapsedRealtime() -
                startedAt

        val message =
            buildString {
                append("http.finish")
                append(" id=")
                append(requestId)
                append(" method=")
                append(method)
                append(" url=")
                append(safeUrl)
                append(" status=")
                append(
                    status ?: "missing",
                )
                append(" statusText=")
                append(
                    oneLine(
                        statusText,
                        100,
                    ),
                )
                append(" elapsedMs=")
                append(elapsedMs)
                append(
                    " responseBodyBytes=",
                )
                append(responseBodyBytes)
                if (
                    !contentType
                        .isNullOrBlank()
                ) {
                    append(
                        " contentType=",
                    )
                    append(contentType)
                }
            }

        if (
            status != null &&
            status in 200..399
        ) {
            Log.i(TAG, message)
        } else {
            Log.w(TAG, message)
        }
    }

    private fun nativeHttpFailureResponse(
        requestId: String,
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
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: root.message
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: error.javaClass
                    .simpleName

        return buildJsonObject {
            put("status", 599)
            put(
                "statusText",
                if (
                    category ==
                    "response-too-large"
                ) {
                    "Native response too large"
                } else {
                    "Native HTTP failure"
                },
            )
            put(
                "headers",
                buildJsonObject {},
            )
            put(
                "body",
                buildJsonObject {
                    put(
                        "requestId",
                        requestId,
                    )
                    put("stage", stage)
                    put(
                        "category",
                        category,
                    )
                    put("method", method)
                    put("url", safeUrl)
                    put(
                        "elapsedMs",
                        elapsedMs,
                    )
                    put(
                        "message",
                        oneLine(
                            message,
                            800,
                        ),
                    )
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

                    findResponseTooLarge(
                        error,
                    )?.let {
                            oversized ->
                        put(
                            "limitBytes",
                            oversized.limitBytes,
                        )
                        put(
                            "observedBytes",
                            oversized.observedBytes,
                        )
                        put(
                            "declaredByServer",
                            oversized
                                .declaredByServer,
                        )
                    }
                }.toString(),
            )
        }.toString()
    }

    private fun classifyHttpFailure(
        error: Throwable,
    ): String {
        val root = rootCause(error)
        return when (root) {
            is ResponseTooLargeException ->
                "response-too-large"
            is CancellationException ->
                "cancelled"
            is SocketTimeoutException ->
                "timeout"
            is UnknownHostException ->
                "dns"
            is SSLException -> "tls"
            is SecurityException ->
                "security"
            is SerializationException ->
                "invalid-json"
            is IllegalArgumentException ->
                "invalid-request"
            is IllegalStateException ->
                "invalid-state"
            is IOException ->
                "network-io"
            else -> "internal"
        }
    }

    private fun findResponseTooLarge(
        error: Throwable,
    ): ResponseTooLargeException? =
        generateSequence(error) {
            it.cause
        }
            .take(MAX_CAUSE_DEPTH)
            .filterIsInstance<
                ResponseTooLargeException
            >()
            .firstOrNull()

    private fun rootCause(
        error: Throwable,
    ): Throwable =
        generateSequence(error) {
            it.cause
        }
            .take(MAX_CAUSE_DEPTH)
            .last()

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

    private fun safeUrlForLog(
        rawUrl: String,
    ): String {
        val uri = rawUrl.toUri()
        val scheme =
            uri.scheme
                ?: "(no-scheme)"
        val host =
            uri.host
                ?: "(no-host)"
        val port =
            if (uri.port >= 0) {
                ":${uri.port}"
            } else {
                ""
            }
        val path =
            uri.encodedPath
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: "/"

        return "$scheme://$host$port$path"
    }

    private fun oneLine(
        value: String,
        maxLength: Int,
    ): String =
        value
            .replace(
                Regex("\\s+"),
                " ",
            )
            .trim()
            .take(maxLength)

    private companion object {
        const val TAG =
            "CoralieAsyncHttp"
        const val HTTP_OPERATION =
            "httpRequestJson"
        const val MAX_CAUSE_DEPTH = 8

        val REQUEST_ID_REGEX =
            Regex(
                "^[A-Za-z0-9._:-]{1,128}$",
            )
    }
}
