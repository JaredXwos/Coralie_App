package com.jaredxwos.coralie.signalling

import com.jaredxwos.coralie.identity.NostrEvent
import com.jaredxwos.coralie.identity.Signer
import com.jaredxwos.coralie.signalling.nostrMessage.ServerToClientMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerToClientMessageTest {

    @Test
    fun parsesEventFrame() {
        // Round-tripped from a real signed event, rather than hand-typed JSON —
        // avoids guessing NostrEvent's exact serialized field names, the same
        // mistake already made once this thread with ClientMessage/RelayFrame.
        val signer = Signer()
        val event = signer.sign(
            kind = 20001,
            tags = listOf(listOf("p", "abcd"), listOf("e", "deadbeef", "wss://relay.example")),
            content = "hello 👋 from a real signalling payload",
            createdAt = 1700000000L,
        )
        val eventJson = Json.encodeToString(NostrEvent.serializer(), event)
        val raw = "[\"EVENT\",\"inbox\",$eventJson]"

        val parsed = ServerToClientMessage.parse(raw).getOrNull() as? ServerToClientMessage.Event
        assertEquals("inbox", parsed?.subscriptionId)
        assertEquals(event, parsed?.event)
    }

    @Test
    fun parsesOkAccepted() {
        val parsed = ServerToClientMessage.parse("[\"OK\",\"abcd1234\",true,\"\"]").getOrNull() as? ServerToClientMessage.Ok
        assertEquals("abcd1234", parsed?.eventId)
        assertEquals(true, parsed?.accepted)
        assertEquals("", parsed?.message)
    }

    @Test
    fun parsesOkRejectedWithConventionalReasonPrefixes() {
        listOf(
            "duplicate: already have this event", "blocked: pubkey is banned",
            "rate-limited: slow down", "invalid: malformed event",
            "pow: insufficient difficulty", "error: internal error",
            "auth-required: this event requires authentication",
        ).forEach { reason ->
            val raw = "[\"OK\",\"abcd1234\",false,\"${reason.replace("\"", "\\\"")}\"]"
            val parsed = ServerToClientMessage.parse(raw).getOrNull() as? ServerToClientMessage.Ok
            assertEquals(false, parsed?.accepted)
            assertEquals(reason, parsed?.message)
        }
    }

    @Test
    fun parsesOkWithMissingMessageAsEmptyString() {
        // Some relays omit the 4th element entirely on success, rather than "".
        val parsed = ServerToClientMessage.parse("[\"OK\",\"abcd1234\",true]").getOrNull() as? ServerToClientMessage.Ok
        assertEquals("", parsed?.message)
    }

    @Test
    fun parsesEose() {
        val parsed = ServerToClientMessage.parse("[\"EOSE\",\"inbox\"]").getOrNull() as? ServerToClientMessage.Eose
        assertEquals("inbox", parsed?.subscriptionId)
    }

    @Test
    fun parsesClosedWithMessage() {
        val raw = "[\"CLOSED\",\"inbox\",\"auth-required: this relay requires authentication\"]"
        val parsed = ServerToClientMessage.parse(raw).getOrNull() as? ServerToClientMessage.Closed
        assertEquals("inbox", parsed?.subscriptionId)
        assertEquals("auth-required: this relay requires authentication", parsed?.message)
    }

    @Test
    fun parsesClosedWithEmptyMessage() {
        // Valid per NIP-01 -- an explicit "" is not the same as "missing".
        val parsed = ServerToClientMessage.parse("[\"CLOSED\",\"inbox\",\"\"]").getOrNull() as? ServerToClientMessage.Closed
        assertEquals("", parsed?.message)
    }

    @Test
    fun parsesClosedWithMissingMessageAsEmptyString() {
        val parsed = ServerToClientMessage.parse("[\"CLOSED\",\"inbox\"]").getOrNull() as? ServerToClientMessage.Closed
        assertEquals("", parsed?.message)
    }

    @Test
    fun parsesNotice() {
        val parsed = ServerToClientMessage.parse("[\"NOTICE\",\"rate limit exceeded, slow down\"]").getOrNull() as? ServerToClientMessage.Notice
        assertEquals("rate limit exceeded, slow down", parsed?.message)
    }

    @Test
    fun rejectsNonJsonInput() {
        assertTrue(ServerToClientMessage.parse("not json at all").isFailure)
    }

    @Test
    fun rejectsJsonThatIsNotAnArray() {
        assertTrue(ServerToClientMessage.parse("{\"foo\":\"bar\"}").isFailure)
    }

    @Test
    fun rejectsEmptyArray() {
        assertTrue(ServerToClientMessage.parse("[]").isFailure)
    }

    @Test
    fun rejectsUnrecognizedType() {
        assertTrue(ServerToClientMessage.parse("[\"WEIRD\",\"foo\"]").isFailure)
    }

    @Test
    fun rejectsRecognizedTypeMissingRequiredField() {
        assertTrue(ServerToClientMessage.parse("[\"EOSE\"]").isFailure)
        assertTrue(ServerToClientMessage.parse("[\"EVENT\",\"inbox\"]").isFailure)
        assertTrue(ServerToClientMessage.parse("[\"OK\",\"abcd\"]").isFailure)
    }

    @Test
    fun rejectsFirstElementThatIsNotAString() {
        assertTrue(ServerToClientMessage.parse("[123,\"inbox\"]").isFailure)
    }
}