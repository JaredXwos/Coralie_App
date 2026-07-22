package com.jaredxwos.coralie.signalling.crypto


import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.log2

private const val CHACHA_KEY_LEN = 32
private const val CHACHA_NONCE_LEN = 12
private const val HMAC_KEY_LEN = 32
private const val MESSAGE_KEYS_LEN = CHACHA_KEY_LEN + CHACHA_NONCE_LEN + HMAC_KEY_LEN

class BouncyCastleNip44Cipher(private val conversationKey: ByteArray) : Nip44Cipher {


    init {
        require(conversationKey.size == 32) { "conversationKey must be 32 bytes, was ${conversationKey.size}" }
    }

    internal class MessageKeys(val chachaKey: ByteArray, val chachaNonce: ByteArray, val hmacKey: ByteArray)

    override fun encrypt(plaintext: String): String = encrypt(plaintext, freshNonce())

    // Nonce-injectable overload — this is what the official test vectors need,
    // since they specify an exact nonce and check ciphertext byte-for-byte.
    internal fun encrypt(plaintext: String, nonce: ByteArray): String {
        require(nonce.size == 32) { "nonce must be 32 bytes, was ${nonce.size}" }
        val keys = getMessageKeys(nonce)
        val padded = pad(plaintext.toByteArray(Charsets.UTF_8))
        val ciphertext = chacha20(keys.chachaKey, keys.chachaNonce, padded)
        val mac = hmacAad(keys.hmacKey, ciphertext, nonce)
        val payload = byteArrayOf(2) + nonce + ciphertext + mac
        return Base64.getEncoder().encodeToString(payload)
    }

    override fun decrypt(payload: String): Result<String> = runCatching {
        // '#' is NIP-44's reserved future-version flag — not valid base64, but
        // the spec wants a distinct "unsupported version" signal here rather
        // than falling through to a generic base64-parse error.
        require(payload.isNotEmpty() && payload[0] != '#') { "unsupported encryption version" }
        require(payload.length in 132..87472) { "invalid payload size" }

        val data = Base64.getDecoder().decode(payload)
        require(data.size in 99..65603) { "invalid decoded payload size" }

        val version = data[0].toInt() and 0xFF
        require(version == 2) { "unsupported encryption version $version" }
        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)

        val keys = getMessageKeys(nonce)
        val expectedMac = hmacAad(keys.hmacKey, ciphertext, nonce)
        // MessageDigest.isEqual is constant-time by contract — deliberately not
        // hand-rolled, since a homemade constant-time compare is an easy place
        // to reintroduce exactly the timing leak this is meant to prevent.
        require(MessageDigest.isEqual(expectedMac, mac)) { "MAC mismatch" }

        val padded = chacha20(keys.chachaKey, keys.chachaNonce, ciphertext)
        unpad(padded)
    }

    internal fun getMessageKeys(nonce: ByteArray): MessageKeys {
        require(nonce.size == 32) { "nonce must be 32 bytes, was ${nonce.size}" }
        val keys = hkdfExpand(conversationKey, nonce)
        return MessageKeys(
            chachaKey = keys.copyOfRange(0, CHACHA_KEY_LEN),
            chachaNonce = keys.copyOfRange(CHACHA_KEY_LEN, CHACHA_KEY_LEN + CHACHA_NONCE_LEN),
            hmacKey = keys.copyOfRange(CHACHA_KEY_LEN + CHACHA_NONCE_LEN, MESSAGE_KEYS_LEN),
        )
    }

    // RFC 5869 HKDF-expand — distinct from the HKDF-*extract* already built
    // into Signer.getConvoKey(); same underlying HMAC-SHA256 primitive, different
    // construction, easy to conflate the two by name alone.
    private fun hkdfExpand(prk: ByteArray, info: ByteArray): ByteArray {
        val hashLen = 32
        val n = (MESSAGE_KEYS_LEN + hashLen - 1) / hashLen
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        var previous = ByteArray(0)
        val okm = ByteArray(n * hashLen)
        for (i in 1..n) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(i.toByte())
            previous = mac.doFinal()
            previous.copyInto(okm, (i - 1) * hashLen)
        }
        return okm.copyOf(MESSAGE_KEYS_LEN)
    }

    private fun hmacAad(key: ByteArray, message: ByteArray, aad: ByteArray): ByteArray {
        require(aad.size == 32) { "AAD (nonce) must be 32 bytes" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(aad)
        return mac.doFinal(message)
    }

    // BC's ChaCha7539Engine defaults to starting block counter 0 when given a
    // plain ParametersWithIV — matching what NIP-44 requires with no extra step.
    private fun chacha20(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val out = ByteArray(data.size)
        engine.processBytes(data, 0, data.size, out, 0)
        return out
    }

    internal fun calcPaddedLen(unpaddedLen: Int): Int {
        if (unpaddedLen <= 32) return 32
        val nextPower = 1 shl (kotlin.math.floor(log2((unpaddedLen - 1).toDouble())).toInt() + 1)
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpaddedLen - 1) / chunk + 1) // typo in spec's pseudocode fixed: unpadded_len, not len
    }

    private fun pad(plaintext: ByteArray): ByteArray {
        val unpaddedLen = plaintext.size
        require(unpaddedLen in 1..65535) { "plaintext length must be 1..65535 bytes, was $unpaddedLen" }
        val prefix = byteArrayOf((unpaddedLen shr 8).toByte(), unpaddedLen.toByte())
        val result = ByteArray(2 + calcPaddedLen(unpaddedLen))
        prefix.copyInto(result, 0)
        plaintext.copyInto(result, 2)
        return result
    }

    private fun unpad(padded: ByteArray): String {
        require(padded.size >= 2) { "invalid padding" }
        val unpaddedLen = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        require(unpaddedLen != 0 && padded.size >= 2 + unpaddedLen) { "invalid padding" }
        val unpadded = padded.copyOfRange(2, 2 + unpaddedLen)
        require(padded.size == 2 + calcPaddedLen(unpaddedLen)) { "invalid padding" }
        return strictUtf8Decode(unpadded)
    }

    // Kotlin's String(bytes, UTF_8) silently substitutes U+FFFD for malformed
    // sequences rather than failing — too lenient for decrypt, which needs to
    // reject bad input rather than quietly hand back garbled-but-valid text.
    private fun strictUtf8Decode(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun freshNonce(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
}