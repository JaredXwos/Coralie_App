package com.jaredxwos.coralie.bridge

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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
 * Permission rejection is deliberately allowed to throw back to the page.
 */
suspend fun proxyHttpRequest(
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

    AppProxy.requestStarted()
    return try {
        authorizeDomain(url.host)
        Json.encodeToJsonElement(
            AppProxy.perform(request, url),
        )
    } finally {
        AppProxy.requestFinished()
    }
}

/** HTTP transport shared by the current viewer session. */
object AppProxy {
    private const val MAX_RESPONSE_BYTES =
        5L * 1024L * 1024L

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
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

    private val activeRequestCounter = AtomicInteger(0)
    private val _activeRequests = MutableStateFlow(0)

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
        request: HttpRequestParams,
        url: HttpUrl,
    ): HttpResponseData =
        withContext(Dispatchers.IO) {
            val method = request.method.uppercase()
            val body =
                if (method == "GET" || method == "HEAD") {
                    null
                } else {
                    (request.body ?: "").toRequestBody(null)
                }

            val builder =
                Request.Builder()
                    .url(url)
                    .method(method, body)
            request.headers.forEach { (key, value) ->
                builder.addHeader(key, value)
            }

            client.newCall(builder.build())
                .execute()
                .use { response ->
                    if (
                        response.body.contentLength() >
                        MAX_RESPONSE_BYTES
                    ) {
                        throw IOException(
                            "Response exceeds size limit",
                        )
                    }

                    HttpResponseData(
                        status = response.code,
                        statusText = response.message,
                        headers =
                            response.headers
                                .toMultimap()
                                .mapValues { (_, values) ->
                                    values.joinToString(", ")
                                },
                        body = response.body.string(),
                    )
                }
        }

    private fun InetAddress.isPubliclyRoutable(): Boolean {
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
            (bytes[0].toInt() and 0xfe) == 0xfc
        ) {
            return false
        }
        if (
            bytes.size == 4 &&
            (bytes[0].toInt() and 0xff) == 100 &&
            (bytes[1].toInt() and 0xc0) == 64
        ) {
            return false
        }
        return true
    }
}
