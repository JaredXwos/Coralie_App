package com.jaredxwos.coralie.signalling.crypto

import com.jaredxwos.coralie.identity.HexCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BouncyCastleNip44CipherTest {

    // v2.valid.calc_padded_len — all 24 official cases
    @Test
    fun calcPaddedLenMatchesOfficialVectors() {
        val cipher = BouncyCastleNip44Cipher(ByteArray(32))
        val cases = listOf(
            16 to 32, 32 to 32, 33 to 64, 37 to 64, 45 to 64, 49 to 64, 64 to 64,
            65 to 96, 100 to 128, 111 to 128, 200 to 224, 250 to 256, 320 to 320,
            383 to 384, 384 to 384, 400 to 448, 500 to 512, 512 to 512, 515 to 640,
            700 to 768, 800 to 896, 900 to 1024, 1020 to 1024, 65536 to 65536,
        )
        cases.forEach { (input, expected) ->
            assertEquals("calc_padded_len($input)", expected, cipher.calcPaddedLen(input))
        }
    }

    // v2.valid.get_message_keys — first entry
    @Test
    fun getMessageKeysMatchesOfficialVector() {
        val conversationKey = HexCodec.fromHex("a1a3d60f3470a8612633924e91febf96dc5366ce130f658b1f0fc652c20b3b54")
        val nonce = HexCodec.fromHex("e1e6f880560d6d149ed83dcc7e5861ee62a5ee051f7fde9975fe5d25d2a02d72")
        val keys = BouncyCastleNip44Cipher(conversationKey).getMessageKeys(nonce)

        assertEquals("f145f3bed47cb70dbeaac07f3a3fe683e822b3715edb7c4fe310829014ce7d76", HexCodec.toHex(keys.chachaKey))
        assertEquals("c4ad129bb01180c0933a160c", HexCodec.toHex(keys.chachaNonce))
        assertEquals("027c1db445f05e2eee864a0975b0ddef5b7110583c8c192de3732571ca5838c4", HexCodec.toHex(keys.hmacKey))
    }

    // v2.valid.encrypt_decrypt — literal round-trip vectors, exact byte match both directions
    @Test
    fun encryptMatchesOfficialVector_simpleAscii() {
        val conversationKey = HexCodec.fromHex("c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d")
        val nonce = HexCodec.fromHex("0000000000000000000000000000000000000000000000000000000000000001")
        val expected = "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"
        assertEquals(expected, BouncyCastleNip44Cipher(conversationKey).encrypt("a", nonce))
    }

    @Test
    fun decryptMatchesOfficialVector_simpleAscii() {
        val conversationKey = HexCodec.fromHex("c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d")
        val payload = "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"
        val result = BouncyCastleNip44Cipher(conversationKey).decrypt(payload)
        assertTrue(result.isSuccess)
        assertEquals("a", result.getOrNull())
    }

    @Test
    fun encryptMatchesOfficialVector_emoji() {
        val conversationKey = HexCodec.fromHex("c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d")
        val nonce = HexCodec.fromHex("f00000000000000000000000000000f00000000000000000000000000000000f")
        val expected = "AvAAAAAAAAAAAAAAAAAAAPAAAAAAAAAAAAAAAAAAAAAPSKSK6is9ngkX2+cSq85Th16oRTISAOfhStnixqZziKMDvB0QQzgFZdjLTPicCJaV8nDITO+QfaQ61+KbWQIOO2Yj"
        assertEquals(expected, BouncyCastleNip44Cipher(conversationKey).encrypt("🍕🫃", nonce))
    }

    @Test
    fun decryptMatchesOfficialVector_mixedUnicode() {
        val conversationKey = HexCodec.fromHex("3e2b52a63be47d34fe0a80e34e73d436d6963bc8f39827f327057a9986c20a45")
        val payload = "ArY1I2xC2yDwIbuNHN/1ynXdGgzHLqdCrXUPMwELJPc7s7JqlCMJBAIIjfkpHReBPXeoMCyuClwgbT419jUWU1PwaNl4FEQYKCDKVJz+97Mp3K+Q2YGa77B6gpxB/lr1QgoqpDf7wDVrDmOqGoiPjWDqy8KzLueKDcm9BVP8xeTJIxs="
        val result = BouncyCastleNip44Cipher(conversationKey).decrypt(payload)
        assertTrue(result.isSuccess)
        assertEquals("表ポあA鷗ŒéＢ逍Üßªąñ丂㐀𠀀", result.getOrNull())
    }

    // v2.invalid.decrypt — real official vectors, not self-constructed corruption
    @Test
    fun rejectsOfficialInvalidDecryptVectors() {
        data class Case(val conversationKey: String, val payload: String, val note: String)
        val cases = listOf(
            Case("ca2527a037347b91bea0c8a30fc8d9600ffd81ec00038671e3a0f0cb0fc9f642",
                "#Atqupco0WyaOW2IGDKcshwxI9xO8HgD/P8Ddt46CbxDbrhdG8VmJdU0MIDf06CUvEvdnr1cp1fiMtlM/GrE92xAc1K5odTpCzUB+mjXgbaqtntBUbTToSUoT0ovrlPwzGjyp",
                "unknown encryption version"),
            Case("36f04e558af246352dcf73b692fbd3646a2207bd8abd4b1cd26b234db84d9481",
                "AK1AjUvoYW3IS7C/BGRUoqEC7ayTfDUgnEPNeWTF/reBZFaha6EAIRueE9D1B1RuoiuFScC0Q94yjIuxZD3JStQtE8JMNacWFs9rlYP+ZydtHhRucp+lxfdvFlaGV/sQlqZz",
                "unknown encryption version 0"),
            Case("ca2527a037347b91bea0c8a30fc8d9600ffd81ec00038671e3a0f0cb0fc9f642",
                "Atфupco0WyaOW2IGDKcshwxI9xO8HgD/P8Ddt46CbxDbrhdG8VmJZE0UICD06CUvEvdnr1cp1fiMtlM/GrE92xAc1EwsVCQEgWEu2gsHUVf4JAa3TpgkmFc3TWsax0v6n/Wq",
                "invalid base64"),
            Case("cff7bd6a3e29a450fd27f6c125d5edeb0987c475fd1e8d97591e0d4d8a89763c",
                "Agn/l3ULCEAS4V7LhGFM6IGA17jsDUaFCKhrbXDANholyySBfeh+EN8wNB9gaLlg4j6wdBYh+3oK+mnxWu3NKRbSvQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "invalid MAC"),
            Case("cfcc9cf682dfb00b11357f65bdc45e29156b69db424d20b3596919074f5bf957",
                "AmWxSwuUmqp9UsQX63U7OQ6K1thLI69L7G2b+j4DoIr0oRWQ8avl4OLqWZiTJ10vIgKrNqjoaX+fNhE9RqmR5g0f6BtUg1ijFMz71MO1D4lQLQfW7+UHva8PGYgQ1QpHlKgR",
                "invalid MAC"),
            Case("5254827d29177622d40a7b67cad014fe7137700c3c523903ebbe3e1b74d40214",
                "Anq2XbuLvCuONcr7V0UxTh8FAyWoZNEdBHXvdbNmDZHB573MI7R7rrTYftpqmvUpahmBC2sngmI14/L0HjOZ7lWGJlzdh6luiOnGPc46cGxf08MRC4CIuxx3i2Lm0KqgJ7vA",
                "invalid padding"),
            Case("fea39aca9aa8340c3a78ae1f0902aa7e726946e4efcd7783379df8096029c496",
                "An1Cg+O1TIhdav7ogfSOYvCj9dep4ctxzKtZSniCw5MwRrrPJFyAQYZh5VpjC2QYzny5LIQ9v9lhqmZR4WBYRNJ0ognHVNMwiFV1SHpvUFT8HHZN/m/QarflbvDHAtO6pY16",
                "invalid padding"),
            Case("0c4cffb7a6f7e706ec94b2e879f1fc54ff8de38d8db87e11787694d5392d5b3f",
                "Am+f1yZnwnOs0jymZTcRpwhDRHTdnrFcPtsBzpqVdD6b2NZDaNm/TPkZGr75kbB6tCSoq7YRcbPiNfJXNch3Tf+o9+zZTMxwjgX/nm3yDKR2kHQMBhVleCB9uPuljl40AJ8kXRD0gjw+aYRJFUMK9gCETZAjjmrsCM+nGRZ1FfNsHr6Z",
                "invalid padding"),
            Case("5cd2d13b9e355aeb2452afbd3786870dbeecb9d355b12cb0a3b6e9da5744cd35", "", "invalid payload length: 0"),
            Case("d61d3f09c7dfe1c0be91af7109b60a7d9d498920c90cbba1e137320fdd938853", "Ag==", "invalid payload length: 4"),
            Case("873bb0fc665eb950a8e7d5971965539f6ebd645c83c08cd6a85aafbad0f0bc47",
                "AqxgToSh3H7iLYRJjoWAM+vSv/Y1mgNlm6OWWjOYUClrFF8=", "invalid payload length: 48"),
            Case("9f2fef8f5401ac33f74641b568a7a30bb19409c76ffdc5eae2db6b39d2617fbe",
                "Ap/2SEZCVFIhYk6qx7nqJxM6TMI1ZoKmAzrO7vBDVJhhuZXWiM20i/tIsbjT0KxkJs2MZjh1oXNYMO9ggfk7i47WQA==", "invalid payload length: 92"),
        )
        cases.forEach { case ->
            val cipher = BouncyCastleNip44Cipher(HexCodec.fromHex(case.conversationKey))
            assertTrue("expected failure for: ${case.note}", cipher.decrypt(case.payload).isFailure)
        }
    }

    // v2.invalid.encrypt_msg_lengths
    @Test
    fun rejectsOversizedPlaintextOnEncrypt() {
        val cipher = BouncyCastleNip44Cipher(ByteArray(32))
        try {
            cipher.encrypt("x".repeat(65536))
            fail("expected IllegalArgumentException for oversized plaintext")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun rejectsZeroLengthPlaintextOnEncrypt() {
        val cipher = BouncyCastleNip44Cipher(ByteArray(32))
        try {
            cipher.encrypt("")
            fail("expected IllegalArgumentException for zero-length plaintext")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

}