package com.jaredxwos.coralie.connection.externalMessages

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class TerminalFailure(val pubkeyHex: String, val attemptsMade: Int){
    fun toJsonElement(): JsonElement =
        buildJsonObject {
            put("pubkeyHex", pubkeyHex)
            put("attemptsMade", attemptsMade)
        }
}