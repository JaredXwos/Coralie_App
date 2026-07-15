package com.jaredxwos.coralie.connection.externalMessages

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

@Serializable
data class PeerMessage(val fromPubkeyHex: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is PeerMessage && fromPubkeyHex == other.fromPubkeyHex && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = 31 * fromPubkeyHex.hashCode() + bytes.contentHashCode()
    fun toJsonElement(): JsonElement =
    buildJsonObject {
        put("fromPubkeyHex", fromPubkeyHex)
        put("payload", Base64.encode(bytes))
    }
}