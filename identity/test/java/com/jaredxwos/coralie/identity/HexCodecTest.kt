package com.jaredxwos.coralie.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HexCodecTest {

    @Test
    fun roundTripsBytesThroughHex() {
        val original = byteArrayOf(0x00, 0x01, 0x0a, 0xff.toByte())
        assertTrue(original.contentEquals(HexCodec.fromHex(HexCodec.toHex(original))))
    }

    @Test
    fun toHexIsAlwaysLowercase() {
        assertEquals("abcd", HexCodec.toHex(byteArrayOf(0xAB.toByte(), 0xCD.toByte())))
    }

    @Test
    fun fromHexAcceptsUppercaseInput() {
        assertTrue(HexCodec.fromHex("ABCD").contentEquals(HexCodec.fromHex("abcd")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromHexRejectsOddLength() {
        HexCodec.fromHex("abc")
    }
}