package com.jaredxwos.coralie.mesh


import com.jaredxwos.coralie.connection.manager.ConnectionManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// --- JSON mapping for push-event payloads and getPeers()'s snapshot -------
//
// internal, not private: AppMesh.kt collects these same three flows
// (peers / incomingMessages / terminalFailures) to build its onEvent
// pushes, and needs the same conversions. A file-scoped `private` here
// would make them invisible from that other file even though they're
// in the same module — internal is the fix, not moving the functions.

internal fun Set<String>.toJsonElement(): JsonElement =
    buildJsonArray { forEach { add(JsonPrimitive(it)) } }

// --- params DTOs ------------------------------------------------------

@Serializable
private data class PubkeyHexParam(val pubkeyHex: String)

@Serializable
private data class SendMessageParams(val toPubkeyHex: String, val payload: String)

// --- helper -------------------------------------------------------------

private fun requireMesh(): ConnectionManager =
    AppMesh.current ?: throw IllegalStateException("no mesh open")

// --- callbacks ------------------------------------------------------------

suspend fun meshGetPubkey(params: JsonElement): JsonElement =
    JsonPrimitive(requireMesh().myPubkeyHex)

suspend fun meshAddPeer(params: JsonElement): JsonElement {
    val p = Json.decodeFromJsonElement<PubkeyHexParam>(params)
    requireMesh().addPeer(p.pubkeyHex)
    return JsonNull
}

@OptIn(ExperimentalEncodingApi::class)
suspend fun meshSendMessage(params: JsonElement): JsonElement {
    val p = Json.decodeFromJsonElement<SendMessageParams>(params)
    requireMesh().sendMessage(p.toPubkeyHex, Base64.decode(p.payload)).getOrThrow()
    return JsonNull
}

suspend fun meshGetPeers(params: JsonElement): JsonElement =
    requireMesh().peers.value.toJsonElement()

suspend fun meshClose(params: JsonElement): JsonElement =
    JsonPrimitive(AppMesh.rebuild())