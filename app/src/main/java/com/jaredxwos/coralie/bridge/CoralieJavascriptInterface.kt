package com.jaredxwos.coralie.bridge

import android.webkit.JavascriptInterface
import com.jaredxwos.coralie.connection.manager.ConnectionManager
import com.jaredxwos.coralie.mesh.AppMesh
import com.jaredxwos.coralie.storage.AppStorage
import com.jaredxwos.coralie.storage.HtmlStorage
import com.jaredxwos.coralie.timer.AppTimers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The Android implementation of the page-facing `window.Coralie` object.
 *
 * There is no second JavaScript transport object and no bridge.js. Every method
 * exposed to page code is declared explicitly with @JavascriptInterface.
 *
 * Android WebView invokes these methods on its private Java-bridge thread. The
 * methods are synchronous from JavaScript's perspective; `await` still accepts
 * their returned primitive values, but the native method call itself completes
 * before JavaScript resumes.
 */
class CoralieJavascriptInterface {

    @JavascriptInterface
    fun apiVersion(): Int = 2

    @JavascriptInterface
    fun hostKind(): String = "android-native"

    @JavascriptInterface
    fun getPubkey(): String = requireMesh().myPubkeyHex

    @JavascriptInterface
    fun addPeer(pubkeyHex: String) {
        requirePubkey(pubkeyHex, "pubkeyHex")
        requireMesh().addPeer(pubkeyHex.lowercase())
    }

    /**
     * WebView's Java bridge supports ES6 typed arrays as array-like arguments,
     * so page code may pass a Uint8Array directly.
     */
    @JavascriptInterface
    fun sendMessage(toPubkeyHex: String, payload: IntArray) {
        requirePubkey(toPubkeyHex, "toPubkeyHex")

        val bytes = ByteArray(payload.size) { index ->
            val value = payload[index]
            require(value in 0..255) { "payload[$index] must be between 0 and 255" }
            value.toByte()
        }

        runBlocking {
            requireMesh()
                .sendMessage(toPubkeyHex.lowercase(), bytes)
                .getOrThrow()
        }
    }

    /** Returns a JSON array of `{pubkeyHex, connectedAt}` objects. */
    @JavascriptInterface
    fun getPeersJson(): String = buildJsonArray {
        requireMesh().peers.value.forEach { pubkeyHex ->
            add(buildJsonObject {
                put("pubkeyHex", pubkeyHex)
                put("connectedAt", JsonNull)
            })
        }
    }.toString()

    @JavascriptInterface
    fun reset(): String = AppMesh.rebuild()

    @JavascriptInterface
    fun close() {
        AppMesh.teardownForPageExit()
    }

    // ---------------------------------------------------------------------
    // Storage. These methods mirror localStorage semantics:
    // - missing get -> null
    // - removing a missing key -> no-op
    // ---------------------------------------------------------------------

    @JavascriptInterface
    fun storageGetItem(key: String): String? = runBlocking {
        val result = requireStorage().retrieveValue(key)
        val error = result.exceptionOrNull()
        when {
            error == null -> result.getOrThrow()
            error is NoSuchElementException -> null
            else -> throw error
        }
    }

    @JavascriptInterface
    fun storageSetItem(key: String, value: String) {
        runBlocking {
            requireStorage().updateValue(key, value, upsert = true).getOrThrow()
        }
    }

    @JavascriptInterface
    fun storageRemoveItem(key: String) {
        runBlocking {
            requireStorage().deleteItem(key).getOrThrow()
        }
    }

    // ---------------------------------------------------------------------
    // HTTP. Objects cannot cross addJavascriptInterface directly, so request
    // and response objects are encoded as JSON strings.
    // ---------------------------------------------------------------------

    @JavascriptInterface
    fun httpRequestJson(requestJson: String): String = runBlocking {
        val params = Json.parseToJsonElement(requestJson)
        proxyHttpRequest(params).toString()
    }

    // ---------------------------------------------------------------------
    // Existing native timers retained for older pages. New pages may use
    // ordinary browser timers when background execution is not required.
    // ---------------------------------------------------------------------

    @JavascriptInterface
    fun timerQueue(id: String?, delaySeconds: Long, payload: String?): String {
        require(delaySeconds > 0) { "delaySeconds must be positive" }
        return AppTimers.queue(id ?: UUID.randomUUID().toString(), delaySeconds, payload)
    }

    @JavascriptInterface
    fun timerCancel(id: String) {
        AppTimers.cancel(id)
    }

    @JavascriptInterface
    fun timerListJson(): String = buildJsonArray {
        AppTimers.list().forEach { (id, remainingMs) ->
            add(buildJsonObject {
                put("id", id)
                put("remainingMs", remainingMs)
            })
        }
    }.toString()

    private fun requireMesh(): ConnectionManager =
        AppMesh.current ?: throw IllegalStateException("Coralie mesh is closed")

    private fun requireStorage(): HtmlStorage =
        AppStorage.current ?: throw IllegalStateException("No space is currently open")

    private fun requirePubkey(value: String, fieldName: String) {
        require(PUBKEY_REGEX.matches(value)) {
            "$fieldName must be a 64-character hexadecimal public key"
        }
    }

    private companion object {
        val PUBKEY_REGEX = Regex("^[0-9a-fA-F]{64}$")
    }
}
