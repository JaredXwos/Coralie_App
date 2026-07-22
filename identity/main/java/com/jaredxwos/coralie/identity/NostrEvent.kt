package com.jaredxwos.coralie.identity
import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.security.MessageDigest

data class UnsignedNostrEvent(
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String
) {
    fun computeEventId(): ByteArray {
        val array = buildJsonArray {
            add(JsonPrimitive(0))
            add(JsonPrimitive(pubkey))
            add(JsonPrimitive(createdAt))
            add(JsonPrimitive(kind))
            add(buildJsonArray {
                tags.forEach { tag -> add(buildJsonArray { tag.forEach { add(JsonPrimitive(it)) } }) }
            })
            add(JsonPrimitive(content))
        }
        return MessageDigest.getInstance("SHA-256").digest(array.toString().toByteArray(Charsets.UTF_8))
    }
}

@Serializable
data class NostrEvent(
    val id: String,
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
) {
    fun verify(): Boolean {
        val unsigned = UnsignedNostrEvent(pubkey, createdAt, kind, tags, content)
        val expectedId = HexCodec.toHex(unsigned.computeEventId())
        if (expectedId != id) return false
        return Secp256k1.get().verifySchnorr(
            HexCodec.fromHex(sig), HexCodec.fromHex(expectedId), HexCodec.fromHex(pubkey)
        )
    }
}