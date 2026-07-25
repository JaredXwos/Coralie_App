package com.jaredxwos.coralie.feature.viewer.bridge

import android.util.Log
import android.webkit.WebView
import com.jaredxwos.coralie.feature.viewer.runtime.mesh.AppMesh
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.encoding.Base64

/**
 * Sends unsolicited native events to the page without exposing another bridge.
 *
 * Logs identify normalization failures, unavailable/closed WebViews, and
 * JavaScript dispatch failures without logging event payload contents.
 */
class CoralieEventEmitter(
    webView: WebView,
) {
    private val webViewRef =
        WeakReference(webView)

    private val closed =
        AtomicBoolean(false)

    /**
     * Stops future event delivery and releases the WebView reference.
     * Call this before the owning WebView leaves composition or is destroyed.
     */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            webViewRef.clear()
            Log.i(TAG, "emitter.closed")
        }
    }

    fun emitHttpSuccess(
        requestId: String,
        responseJson: String,
    ) {
        emit(
            type = "httpResult",
            data =
                buildJsonObject {
                    put("requestId", requestId)
                    put("ok", true)
                    put(
                        "responseJson",
                        responseJson,
                    )
                },
        )
    }

    fun emitHttpFailure(
        requestId: String,
        errorName: String,
        message: String,
        scope: String? = null,
        target: String? = null,
        operation: String? = null,
    ) {
        emit(
            type = "httpResult",
            data =
                buildJsonObject {
                    put("requestId", requestId)
                    put("ok", false)
                    put("errorName", errorName)
                    put("message", message)

                    scope?.let {
                        put("scope", it)
                    }
                    target?.let {
                        put("target", it)
                    }
                    operation?.let {
                        put("operation", it)
                    }
                },
        )
    }

    fun emit(
        type: String,
        data: JsonElement,
    ) {
        val eventId =
            EVENT_IDS.getAndIncrement()
        val eventName =
            "coralie:$type"

        val detail = try {
            normalize(type, data)
        } catch (error: Exception) {
            Log.e(
                TAG,
                "event.normalize.fail " +
                    "id=$eventId " +
                    "type=$type " +
                    "inputChars=${data.toString().length} " +
                    "exception=${error.javaClass.name} " +
                    "message=${oneLine(error.message.orEmpty(), 300)}",
                error,
            )
            return
        }

        val script = """
            (() => {
              try {
                window.dispatchEvent(
                  new CustomEvent(
                    ${Json.encodeToString(eventName)},
                    { detail: $detail },
                  ),
                );
                return "ok";
              } catch (error) {
                console.error(
                  "[Coralie native event $eventId] dispatch failed",
                  error,
                );
                return "error:" + String(
                  error?.stack || error,
                );
              }
            })()
        """.trimIndent()

        if (closed.get()) {
            Log.d(
                TAG,
                "event.drop " +
                    "id=$eventId " +
                    "type=$type " +
                    "reason=emitter-closed",
            )
            return
        }

        val webView = webViewRef.get()
        if (webView == null) {
            Log.w(
                TAG,
                "event.drop " +
                    "id=$eventId " +
                    "type=$type " +
                    "reason=webview-reference-unavailable",
            )
            return
        }

        val accepted = try {
            webView.post {
                if (closed.get()) {
                    Log.d(
                        TAG,
                        "event.drop " +
                            "id=$eventId " +
                            "type=$type " +
                            "reason=emitter-closed-before-dispatch",
                    )
                    return@post
                }

                val current = webViewRef.get()
                if (current == null) {
                    Log.w(
                        TAG,
                        "event.drop " +
                            "id=$eventId " +
                            "type=$type " +
                            "reason=webview-reference-lost-before-dispatch",
                    )
                    return@post
                }

                try {
                    current.evaluateJavascript(script) { result ->
                        if (result != "\"ok\"") {
                            Log.w(
                                TAG,
                                "event.dispatch.fail " +
                                    "id=$eventId " +
                                    "type=$type " +
                                    "result=${oneLine(result.orEmpty(), 500)}",
                            )
                        }
                    }
                } catch (error: Exception) {
                    Log.e(
                        TAG,
                        "event.evaluate.fail " +
                            "id=$eventId " +
                            "type=$type " +
                            "closed=${closed.get()} " +
                            "exception=${error.javaClass.name} " +
                            "message=${oneLine(error.message.orEmpty(), 300)}",
                        error,
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(
                TAG,
                "event.post.fail " +
                    "id=$eventId " +
                    "type=$type " +
                    "exception=${error.javaClass.name} " +
                    "message=${oneLine(error.message.orEmpty(), 300)}",
                error,
            )
            false
        }

        if (!accepted) {
            Log.w(
                TAG,
                "event.drop " +
                    "id=$eventId " +
                    "type=$type " +
                    "reason=webview-post-rejected",
            )
        }
    }

    private fun normalize(
        type: String,
        data: JsonElement,
    ): JsonElement =
        when (type) {
            "peers" ->
                buildJsonArray {
                    data.jsonArray.forEach { item ->
                        add(
                            buildJsonObject {
                                put(
                                    "pubkeyHex",
                                    item.jsonPrimitive.content,
                                )
                                put(
                                    "connectedAt",
                                    JsonNull,
                                )
                            },
                        )
                    }
                }

            "message" -> {
                val source =
                    data.jsonObject
                val payloadBase64 =
                    requireNotNull(source["payload"]) {
                        "message payload is missing"
                    }.jsonPrimitive.content
                val payloadBytes =
                    Base64.decode(payloadBase64)

                buildJsonObject {
                    put(
                        "fromPubkeyHex",
                        requireNotNull(
                            source["fromPubkeyHex"],
                        ).jsonPrimitive.content,
                    )
                    put(
                        "toPubkeyHex",
                        AppMesh.current
                            ?.myPubkeyHex
                            .orEmpty(),
                    )
                    put(
                        "timestamp",
                        System.currentTimeMillis(),
                    )
                    put(
                        "payload",
                        buildJsonArray {
                            payloadBytes.forEach { byte ->
                                add(byte.toInt() and 0xff)
                            }
                        },
                    )
                }
            }

            "terminalFailure" -> {
                val source =
                    data.jsonObject
                buildJsonObject {
                    put(
                        "pubkeyHex",
                        requireNotNull(
                            source["pubkeyHex"],
                        ).jsonPrimitive.content,
                    )
                    put(
                        "attemptCount",
                        requireNotNull(
                            source["attemptsMade"],
                        ).jsonPrimitive.int,
                    )
                    put(
                        "reason",
                        "retry-exhausted",
                    )
                }
            }

            // timerFired and future events are already valid JSON.
            else -> data
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
            "CoralieEvents"

        val EVENT_IDS =
            AtomicLong(1)
    }
}
