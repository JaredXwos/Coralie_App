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

internal fun Set<String>.toJsonElement(): JsonElement =
    buildJsonArray { forEach { add(JsonPrimitive(it)) } }

@Serializable
private data class PubkeyHexParam(val pubkeyHex: String)

@Serializable
private data class SendMessageParams(
    val toPubkeyHex: String,
    val payload: String,
)

private fun requireMesh(): ConnectionManager =
    AppMesh.current ?: throw IllegalStateException("no mesh open")

suspend fun meshGetPubkey(params: JsonElement): JsonElement =
    JsonPrimitive(requireMesh().myPubkeyHex)

suspend fun meshAddPeer(params: JsonElement): JsonElement {
    val parsed = Json.decodeFromJsonElement<PubkeyHexParam>(params)
    requireMesh().addPeer(parsed.pubkeyHex)
    return JsonNull
}

suspend fun meshSendMessage(params: JsonElement): JsonElement {
    val parsed = Json.decodeFromJsonElement<SendMessageParams>(params)
    requireMesh()
        .sendMessage(parsed.toPubkeyHex, Base64.decode(parsed.payload))
        .getOrThrow()
    return JsonNull
}

suspend fun meshGetPeers(params: JsonElement): JsonElement =
    requireMesh().peers.value.toJsonElement()

/** Closes the current mesh and immediately creates a fresh identity. */
suspend fun meshReset(params: JsonElement): JsonElement =
    JsonPrimitive(AppMesh.rebuild())

/** Permanently closes the current mesh until meshReset is called. */
suspend fun meshClose(params: JsonElement): JsonElement {
    AppMesh.teardownForPageExit()
    return JsonNull
}
