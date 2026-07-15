package com.jaredxwos.coralie.identity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignerTest {

    @Test
    fun generatedPubkeyIsValid32ByteXOnlyHex() {
        val signer = Signer()
        val pubkeyBytes = HexCodec.fromHex(signer.pubkeyHex)
        assertEquals(32, pubkeyBytes.size)
        assertEquals(64, signer.pubkeyHex.length)
        assertTrue(signer.pubkeyHex.all { it in "0123456789abcdef" })
    }

    @Test
    fun twoSeparatelyGeneratedSignersHaveDifferentPubkeys() {
        val a = Signer()
        val b = Signer()
        assertNotEquals(a.pubkeyHex, b.pubkeyHex)
    }

    @Test
    fun signedEventVerifiesAgainstItsOwnPubkey() {
        val signer = Signer()
        val event = signer.sign(kind = 20001, tags = listOf(listOf("p", "abcd")), content = "hello", createdAt = 1000L)
        assertTrue(event.verify())
    }

    @Test
    fun tamperedContentFailsVerification() {
        val signer = Signer()
        val event = signer.sign(kind = 20001, tags = listOf(listOf("p", "abcd")), content = "hello", createdAt = 1000L)
        assertFalse(event.copy(content = "goodbye").verify())
    }

    @Test
    fun tamperedIdFailsVerificationEvenWithOriginalSignature() {
        val signer = Signer()
        val event = signer.sign(kind = 20001, tags = listOf(listOf("p", "abcd")), content = "hello", createdAt = 1000L)
        assertFalse(event.copy(id = "00".repeat(32)).verify())
    }

    @Test
    fun computeEventIdMatchesKnownNip01Vector() {
        val unsigned = UnsignedNostrEvent(
            pubkey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            createdAt = 1000,
            kind = 20001,
            tags = listOf(
                listOf("p", "abcd"),
                listOf("special", "line\nbreak \"quote\" back\\slash", "unicode:héllo/slash")
            ),
            content = "hello \"world\"\nwith a tab\tand a slash / here"
        )
        assertEquals(
            "7b66e19deadaa3338c23ea02f98002b5b0acc46db83e9c65d1dab553de03035f",
            HexCodec.toHex(unsigned.computeEventId())
        )
    }

    @Test
    fun ecdhOutputIs32Bytes() {
        val a = Signer()
        val b = Signer()
        assertEquals(32, a.ecdh(b.pubkeyHex).size)
    }

    @Test
    fun ecdhIsSymmetricBetweenTwoIndependentSigners() {
        val a = Signer()
        val b = Signer()
        assertEquals(HexCodec.toHex(a.ecdh(b.pubkeyHex)), HexCodec.toHex(b.ecdh(a.pubkeyHex)))
    }

    @Test
    fun ecdhWithDifferentPeersProducesDifferentSharedSecrets() {
        val me = Signer()
        val peer1 = Signer()
        val peer2 = Signer()
        assertNotEquals(HexCodec.toHex(me.ecdh(peer1.pubkeyHex)), HexCodec.toHex(me.ecdh(peer2.pubkeyHex)))
    }

    @Test
    fun ecdhOfPrivateKeyOneWithGeneratorPointReturnsGeneratorXCoordinate() {
        // NIP-44 vector: sec1 = 1, pub2 = secp256k1's own generator point G.
        // 1 * G == G, so the raw shared_x for this pair is pub2 itself — this is
        // the test that actually distinguishes "raw x-coordinate" (what NIP-44
        // needs) from "SHA256(compressed shared point)" (what ecdh() may return
        // by default on a library built primarily for Bitcoin/Lightning use).
        val sec1 = "0000000000000000000000000000000000000000000000000000000000000001"
        val pub2 = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        val signer = Signer(privateKeyHex = sec1, xOnlyPubkeyHex = pub2)

        assertEquals(pub2, HexCodec.toHex(signer.ecdh(pub2)))
    }

    @Test
    fun getConvoKeyMatchesNip44Vector() {
        val sec1 = "0000000000000000000000000000000000000000000000000000000000000001"
        val pub2 = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        val expected = "3b4610cb7189beb9cc29eb3716ecc6102f1247e8f3101a03a1787d8908aeb54e"

        val signer = Signer(privateKeyHex = sec1, xOnlyPubkeyHex = pub2)

        assertEquals(expected, HexCodec.toHex(signer.getConvoKey(pub2)))
    }
    @Test
    fun signedEventVerifiesWhenPublicKeyHasOddY() {
        // d=6's point has an odd Y-coordinate, verified independently (coincurve).
        // This is exactly the case where BIP-340 requires internally negating the
        // private key before signing. A random Signer() only hits this branch
        // ~50% of the time; this key forces it deterministically, every run.
        val signer = Signer(
            privateKeyHex = "0000000000000000000000000000000000000000000000000000000000000006",
            xOnlyPubkeyHex = "fff97bd5755eeea420453a14355235d382f6472f8568a18b2f057a1460297556",
        )
        val event = signer.sign(kind = 20001, tags = listOf(listOf("p", "abcd")), content = "hello", createdAt = 1000L)

        assertTrue(event.verify())
    }
}