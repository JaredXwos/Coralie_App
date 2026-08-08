package com.jaredxwos.coralie.feature.viewer.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendMessageResponseTest {
    @Test
    fun successResponseIsExplicit() {
        val response =
            Json.parseToJsonElement(
                sendMessageSuccessResponse(),
            ).jsonObject

        assertTrue(
            response.getValue("ok")
                .jsonPrimitive.boolean,
        )
    }

    @Test
    fun unavailablePeerProducesControlledBridgeError() {
        val target = "ab".repeat(32)
        val response =
            Json.parseToJsonElement(
                sendMessageFailureResponse(
                    error =
                        IllegalStateException(
                            "native data channel failure",
                        ),
                    target = target,
                    peerUnavailable = true,
                ),
            ).jsonObject

        assertFalse(
            response.getValue("ok")
                .jsonPrimitive.boolean,
        )
        assertEquals(
            "PeerUnavailableError",
            response.getValue("errorName")
                .jsonPrimitive.content,
        )
        assertEquals(
            "Peer disconnected or channel unavailable",
            response.getValue("message")
                .jsonPrimitive.content,
        )
        assertEquals(
            "sendMessage",
            response.getValue("operation")
                .jsonPrimitive.content,
        )
        assertEquals(
            target,
            response.getValue("target")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun staleClosedChannelDoesNotLeakNativeFailureDetails() {
        val response =
            Json.parseToJsonElement(
                sendMessageFailureResponse(
                    error = IllegalStateException("SCTP channel CLOSED at native address 0x1234"),
                    target = "cd".repeat(32),
                    peerUnavailable = true,
                ),
            ).jsonObject

        assertEquals(
            "PeerUnavailableError",
            response.getValue("errorName").jsonPrimitive.content,
        )
        assertEquals(
            "Peer disconnected or channel unavailable",
            response.getValue("message").jsonPrimitive.content,
        )
        assertFalse(response.toString().contains("0x1234"))
    }
}
