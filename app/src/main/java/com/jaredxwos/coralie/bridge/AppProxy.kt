package com.jaredxwos.coralie.bridge

import com.jaredxwos.coralie.storage.AppStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

// Entry point registered in Dispatch.kt's callbacks map.
// Returns JsonElement on success; throws on rejection/network/validation
// failure — dispatch() catches it and reports BridgeStatus.ERROR to JS,
// same as every other callback.
suspend fun proxyHttpRequest(params: JsonElement): JsonElement {
    val req = Json.decodeFromJsonElement<HttpRequestParams>(params)

    val url = req.url.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Only http(s) URLs are supported")
    if (url.scheme != "https")
        throw IllegalArgumentException("Only https requests are allowed")

    if (!AppProxy.authorize(url.host))
        throw IllegalStateException("Request to ${url.host} was rejected")

    return Json.encodeToJsonElement(AppProxy.perform(req, url))
}

object AppProxy {

    enum class Decision { REJECT, ALLOW_ONCE, ALLOW_ALWAYS }

    private const val MAX_RESPONSE_BYTES = 5L * 1024 * 1024

    // Reject loopback/private/link-local addresses at CONNECT time, on every hop.
    private val safeDns = Dns { hostname ->
        Dns.SYSTEM.lookup(hostname)
            .filter { it.isPubliclyRoutable() }
            .ifEmpty { throw UnknownHostException("Blocked non-public host: $hostname") }
    }

    private val client = OkHttpClient.Builder()
        .dns(safeDns)
        .followRedirects(false)   // see notes: a 3xx could escape the allowlist
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    // Per-page, in-memory decisions. Cleared on page teardown.
    private val sessionAllow = ConcurrentHashMap.newKeySet<String>()
    private val sessionReject = ConcurrentHashMap.newKeySet<String>()

    // Only one prompt shown at a time.
    private val gate = Mutex()
    private var pending: CompletableDeferred<Decision>? = null

    private val _prompt = MutableStateFlow<String?>(null)
    /** Domain awaiting a decision, or null. Observed by ViewerScreen. */
    val prompt: StateFlow<String?> = _prompt.asStateFlow()

    /** Called by the UI when the user taps a dialog button. */
    fun resolvePrompt(decision: Decision) { pending?.complete(decision) }

    fun teardownForPageExit() {
        sessionAllow.clear()
        sessionReject.clear()
        pending?.complete(Decision.REJECT)   // unblock anything still awaiting
    }

    internal suspend fun authorize(domain: String): Boolean {
        if (domain in sessionAllow || isPersistedAllowed(domain)) return true
        if (domain in sessionReject) return false

        return gate.withLock {
            if (domain in sessionAllow || isPersistedAllowed(domain)) return@withLock true
            if (domain in sessionReject) return@withLock false

            when (awaitDecision(domain)) {
                Decision.ALLOW_ALWAYS -> {
                    AppStorage.allowDomain(domain)
                    sessionAllow += domain
                    true
                }
                Decision.ALLOW_ONCE -> {
                    sessionAllow += domain
                    true
                }
                Decision.REJECT -> {
                    sessionReject += domain
                    false
                }
            }
        }
    }

    private suspend fun isPersistedAllowed(domain: String): Boolean =
        AppStorage.isDomainAllowed(domain).getOrDefault(false)

    private suspend fun awaitDecision(domain: String): Decision {
        val deferred = CompletableDeferred<Decision>()
        pending = deferred
        _prompt.value = domain
        return try {
            deferred.await()
        } finally {
            _prompt.value = null
            pending = null
        }
    }

    internal suspend fun perform(req: HttpRequestParams, url: HttpUrl): HttpResponseData =
        withContext(Dispatchers.IO) {
            val method = req.method.uppercase()
            val body = if (method == "GET" || method == "HEAD") null
            else (req.body ?: "").toRequestBody(null)

            val builder = Request.Builder().url(url).method(method, body)
            req.headers.forEach { (k, v) -> builder.addHeader(k, v) }

            client.newCall(builder.build()).execute().use { resp ->
                if ((resp.body?.contentLength() ?: -1L) > MAX_RESPONSE_BYTES)
                    throw IOException("Response exceeds size limit")

                HttpResponseData(
                    status = resp.code,
                    statusText = resp.message,
                    headers = resp.headers.toMultimap().mapValues { (_, v) -> v.joinToString(", ") },
                    body = resp.body?.string(),
                )
            }
        }

    private fun InetAddress.isPubliclyRoutable(): Boolean {
        if (isLoopbackAddress || isAnyLocalAddress || isLinkLocalAddress ||
            isSiteLocalAddress || isMulticastAddress) return false
        val b = address
        if (b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc) return false   // fc00::/7 ULA
        if (b.size == 4 && (b[0].toInt() and 0xff) == 100 &&
            (b[1].toInt() and 0xc0) == 64) return false                     // 100.64/10 CGNAT
        return true
    }
}