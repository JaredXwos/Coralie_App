package com.jaredxwos.coralie.signalling.nostrMessage

import com.jaredxwos.coralie.identity.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

// Assumption: NostrEvent is @Serializable (per Phase 1 notes) and reachable from this
// module. `wireJson` below is a placeholder config — if a Json instance already exists
// wherever NostrEvent gets serialized elsewhere (e.g. inside :identity), reuse that one
// instead of defining a second one here, so both places agree on null-handling/formatting
// for the same type rather than silently drifting apart.
private val wireJson = Json { encodeDefaults = true }

/**
 * The three things a client can say to a relay, per NIP-01: publish an event, open or
 * replace a subscription, or close one. Mirrors [ServerToClientMessage] but for the opposite
 * direction — ServerToClientMessage parses relay-to-client text into a typed result; ClientMessage
 * goes typed intent in, wire text out. No failure case the way ServerToClientMessage has
 * Result.failure, since these are built from already-valid local data, not parsed from
 * untrusted input.
 */
sealed class ClientToServerMessage {
    abstract fun toWireText(): String

    /** Publish a signed event — the actual signalling payload, already encrypted+signed. */
    data class Event(val event: NostrEvent) : ClientToServerMessage() {
        override fun toWireText(): String = JsonArray(
            listOf(JsonPrimitive("EVENT"), wireJson.encodeToJsonElement(event))
        ).toString()
    }

    /**
     * Open a subscription, or — reusing the same subId — replace one in place. Holds the
     * NIP-01 filter criteria directly rather than wrapping a separate `NostrFilter` type,
     * since nothing in this system ever needs a filter that isn't already paired with a
     * subId to send it under.
     *
     * Tag filters (`#p`, `#e`, ...) are modeled as [tags], keyed by the bare letter ("p",
     * not "#p") — the "#" is added in exactly one place, [filterJson], rather than left as
     * something every call site has to remember. NIP-01 uses "#p" for a filter's tag key but
     * plain "p" for a tag entry inside an event itself; conflating the two is an easy
     * mistake this shape is meant to make impossible rather than just documented against.
     */
    data class Req(
        val subId: String,
        val ids: List<String>? = null,
        val authors: List<String>? = null,
        val kinds: List<Int>? = null,
        val tags: Map<String, List<String>> = emptyMap(),
        val since: Long? = null,
        val until: Long? = null,
        val limit: Int? = null,
    ) : ClientToServerMessage() {
        init {
            tags.keys.forEach { letter ->
                require(letter.length == 1) {
                    "tag filter key must be a bare single letter (e.g. \"p\"), got \"$letter\" — " +
                            "the \"#\" prefix is added automatically during encoding, don't include it here"
                }
            }
        }

        /** Just the filter object, e.g. `{"kinds":[20001],"#p":["abc..."],"since":123}` —
         *  kept separate from [toWireText] so "is the filter right" and "is the REQ envelope
         *  right" stay independently testable. */

        override fun toWireText(): String = JsonArray(
            listOf(
                JsonPrimitive("REQ"),
                JsonPrimitive(subId),
                buildJsonObject {
                    ids?.let { putStrings("ids", it) }
                    authors?.let { putStrings("authors", it) }
                    kinds?.let { put("kinds", JsonArray(it.map(::JsonPrimitive))) }
                    tags.forEach { (letter, values) -> putStrings("#$letter", values) }
                    since?.let { put("since", JsonPrimitive(it)) }
                    until?.let { put("until", JsonPrimitive(it)) }
                    limit?.let { put("limit", JsonPrimitive(it)) }
                })
        ).toString()

        companion object {
            /**
             * The one subscription shape this signalling layer actually needs: events of our
             * chosen ephemeral [signallingKind] (a single kind, e.g. 20001 — not the whole
             * 20000–29999 range) tagged to reach [myPubkeyHex]. [subId] is supplied by the
             * caller rather than generated here — subId uniqueness only needs to hold within
             * one relay connection, and generation policy (UUID vs. a fixed constant like
             * "inbox") is a caller concern, not something this factory should decide.
             */
            fun forInbox(
                subId: String,
                myPubkeyHex: String,
                signallingKind: Int,
                since: Long? = null,
            ): Req = Req(
                subId = subId,
                kinds = listOf(signallingKind),
                tags = mapOf("p" to listOf(myPubkeyHex)),
                since = since,
            )
        }
    }

    /**
     * Explicitly end a subscription. Note: LiveRelaySession's heartbeat/reconnect path never
     * sends this — it always re-sends [Req] on the same subId instead, since CLOSE-then-REQ
     * would reopen exactly the dead window in-place replace is relied on to avoid for
     * ephemeral-kind events.
     */
    data class Close(val subId: String) : ClientToServerMessage() {
        override fun toWireText(): String = JsonArray(
            listOf(JsonPrimitive("CLOSE"), JsonPrimitive(subId))
        ).toString()
    }
}

private fun JsonObjectBuilder.putStrings(key: String, values: List<String>) {
    put(key, JsonArray(values.map(::JsonPrimitive)))
}