package com.jaredxwos.coralie.signalling.nostrMessage

import com.jaredxwos.coralie.identity.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * One parsed NIP-01 relay frame. Each concrete type below parses itself,
 * independently, from an already-split [kotlinx.serialization.json.JsonArray] -- deciding *which* type
 * applies to a given raw string is [parse]'s job, one level up, since that
 * decision has to happen before any single type's own parsing can even
 * begin.
 *
 * Every parse path returns [Result] rather than throwing or returning null.
 * Unlike a nullable, a [Result.failure] carries the actual [Throwable] --
 * invalid JSON, a missing array element, a field of the wrong type -- which
 * matters the moment this project has somewhere to log that reason. Until
 * then the reason is simply unused at the call site, not unavailable.
 */
sealed class ServerToClientMessage {

    data class Event(val subscriptionId: String, val event: NostrEvent) : ServerToClientMessage() {
        companion object {
            fun tryParse(array: JsonArray): Result<Event> = runCatching {
                Event(
                    subscriptionId = array[1].jsonPrimitive.content,
                    event = Json.decodeFromJsonElement(NostrEvent.serializer(), array[2])
                )
            }
        }
    }

    data class Ok(val eventId: String, val accepted: Boolean, val message: String) : ServerToClientMessage() {
        companion object {
            fun tryParse(array: JsonArray): Result<Ok> = runCatching {
                Ok(
                    eventId = array[1].jsonPrimitive.content,
                    accepted = array[2].jsonPrimitive.boolean,
                    message = array.getOrNull(3)?.jsonPrimitive?.contentOrNull ?: ""
                )
            }
        }
    }

    data class Eose(val subscriptionId: String) : ServerToClientMessage() {
        companion object {
            fun tryParse(array: JsonArray): Result<Eose> = runCatching {
                Eose(subscriptionId = array[1].jsonPrimitive.content)
            }
        }
    }

    data class Closed(val subscriptionId: String, val message: String) : ServerToClientMessage() {
        companion object {
            fun tryParse(array: JsonArray): Result<Closed> = runCatching {
                Closed(
                    subscriptionId = array[1].jsonPrimitive.content,
                    message = array.getOrNull(2)?.jsonPrimitive?.contentOrNull ?: ""
                )
            }
        }
    }

    data class Notice(val message: String) : ServerToClientMessage() {
        companion object {
            fun tryParse(array: JsonArray): Result<Notice> = runCatching {
                Notice(message = array[1].jsonPrimitive.content)
            }
        }
    }

    companion object {
        /**
         * The entry point: turn a raw relay message into a [ServerToClientMessage],
         * deciding which of the five types applies along the way. Never
         * throws -- this runs on an OkHttp callback thread, and one
         * malformed frame must never be allowed to kill that thread. Three
         * distinct failure points all funnel into the same Result.failure:
         * the raw string not even being a valid JSON array, the first
         * element not being one of the five tags this client understands,
         * and -- delegated out to each type's own tryParse -- a valid array
         * whose fields don't match what its own tag promised.
         */
        fun parse(raw: String): Result<ServerToClientMessage> = runCatching {
            Json.parseToJsonElement(raw).jsonArray
        }.fold(
            onSuccess = { array ->
                when (val tag = array.firstOrNull()?.jsonPrimitive?.contentOrNull) {
                    "EVENT" -> Event.tryParse(array)
                    "OK" -> Ok.tryParse(array)
                    "EOSE" -> Eose.tryParse(array)
                    "CLOSED" -> Closed.tryParse(array)
                    "NOTICE" -> Notice.tryParse(array)
                    else -> Result.failure(
                        IllegalArgumentException(
                            if (tag == null) {
                                "relay frame missing a type tag (empty array or non-string first element)"
                            } else {
                                "unrecognized relay frame type: \"$tag\""
                            }
                        )
                    )
                }
            },
            onFailure = { Result.failure(it) }
        )
    }
}