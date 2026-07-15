package com.jaredxwos.coralie.timer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.util.UUID

// ---- params DTOs ------------------------------------------------------

@Serializable
private data class QueueTimerParams(
    val id: String? = null,
    val delaySeconds: Long,
    val payload: String? = null
)

@Serializable
private data class IdParam(val id: String)

// ---- callbacks ------------------------------------------------------------
// Same pattern as MeshCallbacks/StorageCallbacks: decode params, call the
// underlying object, encode the result. dispatch() in Dispatch.kt already
// turns thrown exceptions into a BridgeStatus.ERROR response, so a bad
// `delaySeconds` just surfaces as a rejected promise on the HTML side.

suspend fun timerQueue(params: JsonElement): JsonElement {
    val p = Json.decodeFromJsonElement<QueueTimerParams>(params)
    require(p.delaySeconds > 0) { "delaySeconds must be positive" }
    val id = p.id ?: UUID.randomUUID().toString()
    return JsonPrimitive(AppTimers.queue(id, p.delaySeconds, p.payload))
}

suspend fun timerCancel(params: JsonElement): JsonElement {
    val p = Json.decodeFromJsonElement<IdParam>(params)
    AppTimers.cancel(p.id)
    return JsonNull
}

suspend fun timerList(params: JsonElement): JsonElement =
    buildJsonArray {
        AppTimers.list().forEach { (id, remainingMs) ->
            add(buildJsonObject {
                put("id", id)
                put("remainingMs", remainingMs)
            })
        }
    }