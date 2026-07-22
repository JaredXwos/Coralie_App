package com.jaredxwos.coralie.bridge

import android.webkit.WebView
import com.jaredxwos.coralie.mesh.AppMesh
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.lang.ref.WeakReference
import kotlin.io.encoding.Base64

/**
 * Sends unsolicited native events to the page without exposing a second bridge
 * object. Pages subscribe with ordinary DOM event listeners.
 */
class CoralieEventEmitter(webView: WebView) {
    private val webViewRef = WeakReference(webView)

    fun emit(type: String, data: JsonElement) {
        val eventName = "coralie:$type"
        val detail = normalize(type, data)
        val script = "window.dispatchEvent(new CustomEvent(" +
            Json.encodeToString(eventName) +
            ", { detail: $detail }));"

        webViewRef.get()?.post {
            webViewRef.get()?.evaluateJavascript(script, null)
        }
    }

    private fun normalize(type: String, data: JsonElement): JsonElement = when (type) {
        "peers" -> buildJsonArray {
            data.jsonArray.forEach { item ->
                add(buildJsonObject {
                    put("pubkeyHex", item.jsonPrimitive.content)
                    put("connectedAt", JsonNull)
                })
            }
        }

        "message" -> {
            val source = data.jsonObject
            val payloadBase64 = requireNotNull(source["payload"]) {
                "message payload is missing"
            }.jsonPrimitive.content
            val payloadBytes = Base64.decode(payloadBase64)

            buildJsonObject {
                put("fromPubkeyHex", requireNotNull(source["fromPubkeyHex"]).jsonPrimitive.content)
                put("toPubkeyHex", AppMesh.current?.myPubkeyHex.orEmpty())
                put("timestamp", System.currentTimeMillis())
                put("payload", buildJsonArray {
                    payloadBytes.forEach { byte -> add(byte.toInt() and 0xff) }
                })
            }
        }

        "terminalFailure" -> {
            val source = data.jsonObject
            buildJsonObject {
                put("pubkeyHex", requireNotNull(source["pubkeyHex"]).jsonPrimitive.content)
                put("attemptCount", requireNotNull(source["attemptsMade"]).jsonPrimitive.int)
                put("reason", "retry-exhausted")
            }
        }

        // timerFired and any future native events are already valid JSON.
        else -> data
    }
}
