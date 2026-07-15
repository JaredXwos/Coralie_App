package com.jaredxwos.coralie.identity

import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class Signer {
    private val privateKey: ByteArray
    val pubkeyHex: String

    constructor() {
        val (priv, pub) = generateRawKeyMaterial()
        privateKey = priv
        pubkeyHex = HexCodec.toHex(pub)
        validate()
    }

    constructor(privateKeyHex: String, xOnlyPubkeyHex: String) {
        privateKey = HexCodec.fromHex(privateKeyHex)
        pubkeyHex = HexCodec.toHex(HexCodec.fromHex(xOnlyPubkeyHex))
        validate()
    }

    private fun validate() {
        require(privateKey.size == 32) { "privateKey must be 32 bytes, was ${privateKey.size}" }
        require(HexCodec.fromHex(pubkeyHex).size == 32) { "xOnlyPubkey must be 32 bytes" }
    }

    fun sign(kind: Int, tags: List<List<String>>, content: String, createdAt: Long): NostrEvent {
        val unsigned = UnsignedNostrEvent(pubkeyHex, createdAt, kind, tags, content)
        val idBytes = unsigned.computeEventId()
        val sigBytes = Secp256k1.get().signSchnorr(idBytes, privateKey, freshAuxRand32())
        return NostrEvent(
            id = HexCodec.toHex(idBytes),
            pubkey = pubkeyHex,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = HexCodec.toHex(sigBytes)
        )
    }

    fun ecdh(theirPubkeyHex: String): ByteArray {
        val theirXOnly = HexCodec.fromHex(theirPubkeyHex)
        require(theirXOnly.size == 32) {
            "theirPubkeyHex must be a 32-byte x-only pubkey, was ${theirXOnly.size} bytes"
        }
        val theirCompressed = byteArrayOf(0x02) + theirXOnly
        val sharedPoint = Secp256k1.get().pubKeyTweakMul(theirCompressed, privateKey)
        return sharedPoint.copyOfRange(1, 33)
    }

    fun getConvoKey(theirPubkeyHex: String): ByteArray =
        hkdfExtract(ecdh(theirPubkeyHex), "nip44-v2".toByteArray(Charsets.UTF_8))

    private fun hkdfExtract(ikm: ByteArray, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun generateRawKeyMaterial(): Pair<ByteArray, ByteArray> {
        val random = SecureRandom()
        var priv: ByteArray
        do {
            priv = ByteArray(32).also { random.nextBytes(it) }
        } while (!Secp256k1.get().secKeyVerify(priv))
        val uncompressed = Secp256k1.get().pubkeyCreate(priv)
        return priv to uncompressed.copyOfRange(1, 33)
    }

    private fun freshAuxRand32(): ByteArray =
        ByteArray(32).also { SecureRandom().nextBytes(it) }
}