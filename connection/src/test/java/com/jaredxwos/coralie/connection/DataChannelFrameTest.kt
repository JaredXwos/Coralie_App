package com.jaredxwos.coralie.connection

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataChannelFrameTest {

    @Test
    fun appFrameUsesLowercaseTypeAndSignedByteArray() {
        val payload = byteArrayOf(0, 127, -128, -1)

        val encoded = Json.encodeToString<DataChannelFrame>(
            DataChannelFrame.App(payload),
        )

        assertEquals(
            Json.parseToJsonElement(
                """{"type":"app","payload":[0,127,-128,-1]}""",
            ),
            Json.parseToJsonElement(encoded),
        )

        val decoded = Json.decodeFromString<DataChannelFrame>(
            """{"type":"app","payload":[0,127,-128,-1]}""",
        )

        assertTrue(decoded is DataChannelFrame.App)
        assertArrayEquals(payload, (decoded as DataChannelFrame.App).payload)
    }

    @Test
    fun announceFrameUsesLowercaseTypeAndPubkeyField() {
        val pubkeyHex = "ab".repeat(32)

        val encoded = Json.encodeToString<DataChannelFrame>(
            DataChannelFrame.Announce(pubkeyHex),
        )

        assertEquals(
            Json.parseToJsonElement(
                """{"type":"announce","pubkeyHex":"$pubkeyHex"}""",
            ),
            Json.parseToJsonElement(encoded),
        )

        val decoded = Json.decodeFromString<DataChannelFrame>(
            """{"type":"announce","pubkeyHex":"$pubkeyHex"}""",
        )

        assertEquals(DataChannelFrame.Announce(pubkeyHex), decoded)
    }
}
