package com.jaredxwos.coralie.feature.viewer.runtime.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class HttpRequestParams(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

@Serializable
data class HttpResponseData(
    val status: Int,
    val statusText: String,
    val headers: Map<String, String>,
    val body: String?,
)

/**
 * Decodes and performs an HTTPS request after [authorizeDomain] succeeds.
 *
 * [requestId] is shared with the JavaScript Promise and identifies the active
 * OkHttp Call for immediate cancellation.
 */
suspend fun proxyHttpRequest(
    requestId: String,
    params: JsonElement,
    authorizeDomain: suspend (String) -> Unit,
): JsonElement {
    val request =
        Json.decodeFromJsonElement<HttpRequestParams>(params)

    val url = request.url.toHttpUrlOrNull()
        ?: throw IllegalArgumentException(
            "Only http(s) URLs are supported",
        )
    require(url.scheme == "https") {
        "Only https requests are allowed"
    }

    NativeHttpProxy.requestStarted()
    return try {
        authorizeDomain(url.host)
        Json.encodeToJsonElement(
            NativeHttpProxy.perform(
                requestId = requestId,
                request = request,
                url = url,
            ),
        )
    } finally {
        NativeHttpProxy.requestFinished()
    }
}

/**
 * Asynchronous HTTP transport shared by viewer sessions.
 *
 * No JavaScript bridge thread or coroutine IO thread is held while the network
 * request is in flight. Coroutine/session cancellation immediately cancels the
 * corresponding OkHttp Call.
 */
object NativeHttpProxy {
    internal const val MAX_RESPONSE_BYTES =
        64L * 1024L * 1024L // 64 MiB

    internal const val DEFAULT_CALL_TIMEOUT_MILLIS =
        45_000L

    private const val MAX_CALL_TIMEOUT_MILLIS =
        5L * 60L * 1000L

    private val safeDns = Dns { hostname ->
        Dns.SYSTEM.lookup(hostname)
            .filter { it.isPubliclyRoutable() }
            .ifEmpty {
                throw UnknownHostException(
                    "Blocked non-public host: $hostname",
                )
            }
    }

    private val client =
        OkHttpClient.Builder()
            .dns(safeDns)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    private val activeCalls =
        ConcurrentHashMap<String, Call>()

    private val activeRequestCounter =
        AtomicInteger(0)
    private val _activeRequests =
        MutableStateFlow(0)

    val activeRequests: StateFlow<Int> =
        _activeRequests.asStateFlow()

    internal fun requestStarted() {
        _activeRequests.value =
            activeRequestCounter.incrementAndGet()
    }

    internal fun requestFinished() {
        val remaining =
            activeRequestCounter.updateAndGet { current ->
                (current - 1).coerceAtLeast(0)
            }
        _activeRequests.value = remaining
    }

    internal suspend fun perform(
        requestId: String,
        request: HttpRequestParams,
        url: HttpUrl,
        timeoutMillis: Long =
            DEFAULT_CALL_TIMEOUT_MILLIS,
    ): HttpResponseData {
        require(requestId.isNotBlank()) {
            "requestId must not be blank"
        }
        require(
            timeoutMillis in
                1L..MAX_CALL_TIMEOUT_MILLIS,
        ) {
            "timeoutMillis must be between 1 and " +
                MAX_CALL_TIMEOUT_MILLIS
        }

        val method = request.method.uppercase()
        val body =
            if (
                method == "GET" ||
                method == "HEAD"
            ) {
                null
            } else {
                (request.body ?: "")
                    .toRequestBody(null)
            }

        val builder =
            Request.Builder()
                .url(url)
                .method(method, body)

        request.headers.forEach {
                (key, value) ->
            builder.addHeader(key, value)
        }

        val call =
            client.newCall(builder.build())

        // This is configured per Call rather than globally, allowing future
        // Android-only callers to choose a narrower timeout without changing
        // the page/browser interface.
        call.timeout().timeout(
            timeoutMillis,
            TimeUnit.MILLISECONDS,
        )

        check(
            activeCalls.putIfAbsent(
                requestId,
                call,
            ) == null,
        ) {
            "Duplicate HTTP request ID: $requestId"
        }

        return try {
            awaitResponse(call)
        } finally {
            activeCalls.remove(
                requestId,
                call,
            )
        }
    }

    internal fun cancel(
        requestId: String,
    ): Boolean {
        val call =
            activeCalls[requestId]
                ?: return false
        call.cancel()
        return true
    }

    private suspend fun awaitResponse(
        call: Call,
    ): HttpResponseData =
        suspendCancellableCoroutine {
                continuation ->
            val completed =
                AtomicBoolean(false)

            continuation
                .invokeOnCancellation {
                    if (
                        completed.compareAndSet(
                            false,
                            true,
                        )
                    ) {
                        call.cancel()
                    }
                }

            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (
                            completed.compareAndSet(
                                false,
                                true,
                            )
                        ) {
                            continuation
                                .resumeWithException(
                                    e,
                                )
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        try {
                            val result =
                                response.use {
                                    HttpResponseData(
                                        status =
                                            it.code,
                                        statusText =
                                            it.message,
                                        headers =
                                            it.headers
                                                .toMultimap()
                                                .mapValues {
                                                        (_, values) ->
                                                    values.joinToString(
                                                        ", ",
                                                    )
                                                },
                                        body =
                                            readResponseBodyLimited(
                                                body =
                                                    it.body,
                                                maximumBytes =
                                                    MAX_RESPONSE_BYTES,
                                            ),
                                    )
                                }

                            if (
                                completed.compareAndSet(
                                    false,
                                    true,
                                )
                            ) {
                                continuation
                                    .resume(result)
                            }
                        } catch (
                            error: Exception
                        ) {
                            if (
                                completed.compareAndSet(
                                    false,
                                    true,
                                )
                            ) {
                                continuation
                                    .resumeWithException(
                                        error,
                                    )
                            }
                        }
                    }
                },
            )
        }

    internal fun readResponseBodyLimited(
        body: ResponseBody,
        maximumBytes: Long =
            MAX_RESPONSE_BYTES,
    ): String {
        require(maximumBytes > 0L) {
            "maximumBytes must be positive"
        }

        val declaredLength =
            body.contentLength()
        if (
            declaredLength >= 0L &&
            declaredLength > maximumBytes
        ) {
            throw ResponseTooLargeException(
                limitBytes = maximumBytes,
                observedBytes =
                    declaredLength,
                declaredByServer = true,
            )
        }

        val charset =
            body.contentType()
                ?.charset(Charsets.UTF_8)
                ?: Charsets.UTF_8

        body.byteStream().use { input ->
            val output =
                ByteArrayOutputStream()
            val buffer =
                ByteArray(
                    DEFAULT_BUFFER_SIZE,
                )
            var totalBytes = 0L

            while (true) {
                val count =
                    input.read(buffer)
                if (count == -1) {
                    break
                }

                totalBytes += count
                if (
                    totalBytes >
                    maximumBytes
                ) {
                    throw ResponseTooLargeException(
                        limitBytes =
                            maximumBytes,
                        observedBytes =
                            totalBytes,
                        declaredByServer =
                            false,
                    )
                }

                output.write(
                    buffer,
                    0,
                    count,
                )
            }

            return output
                .toByteArray()
                .toString(charset)
        }
    }

    private fun InetAddress
        .isPubliclyRoutable(): Boolean {
        if (
            isLoopbackAddress ||
            isAnyLocalAddress ||
            isLinkLocalAddress ||
            isSiteLocalAddress ||
            isMulticastAddress
        ) {
            return false
        }

        val bytes = address
        if (
            bytes.size == 16 &&
            (bytes[0].toInt() and 0xfe) ==
            0xfc
        ) {
            return false
        }
        if (
            bytes.size == 4 &&
            (bytes[0].toInt() and 0xff) ==
            100 &&
            (bytes[1].toInt() and 0xc0) ==
            64
        ) {
            return false
        }
        return true
    }
}
