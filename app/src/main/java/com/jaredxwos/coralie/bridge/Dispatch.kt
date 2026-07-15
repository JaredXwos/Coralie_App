package com.jaredxwos.coralie.bridge

import androidx.webkit.WebMessageCompat
import com.jaredxwos.coralie.mesh.meshAddPeer
import com.jaredxwos.coralie.mesh.meshClose
import com.jaredxwos.coralie.mesh.meshGetPeers
import com.jaredxwos.coralie.mesh.meshGetPubkey
import com.jaredxwos.coralie.mesh.meshSendMessage
import com.jaredxwos.coralie.storage.storageClear
import com.jaredxwos.coralie.storage.storageCreateValue
import com.jaredxwos.coralie.storage.storageDeleteValue
import com.jaredxwos.coralie.storage.storageGetAllWithTag
import com.jaredxwos.coralie.storage.storageRetrieveValue
import com.jaredxwos.coralie.storage.storageGetTag
import com.jaredxwos.coralie.storage.storageUpdateValue
import com.jaredxwos.coralie.storage.storageSetTag
import com.jaredxwos.coralie.timer.timerCancel
import com.jaredxwos.coralie.timer.timerList
import com.jaredxwos.coralie.timer.timerQueue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// 
@Serializable
enum class BridgeStatus { ERROR, SUCCESS}

// Class formalising incoming bridge.js messages
@Serializable
data class BridgeRequest(
    val id: Int,
    val callback: String,
    val params: JsonElement
)

// Class formalising outgoing bridge.js responses
@Serializable
data class BridgeResponse(
    val status: BridgeStatus,
    val id: Int?,
    val data: JsonElement
)

// List of registered callbacks
val callbacks: Map<String, suspend (JsonElement) -> JsonElement> = mapOf(
    "createValue"   to ::storageCreateValue,
    "retrieveValue" to ::storageRetrieveValue,
    "updateValue"   to ::storageUpdateValue,
    "deleteValue"   to ::storageDeleteValue,
    "getTag"        to ::storageGetTag,
    "setTag"        to ::storageSetTag,
    "getAllWithTag" to ::storageGetAllWithTag,
    "clear"         to ::storageClear,
    "getPubkey"     to ::meshGetPubkey,
    "addPeer"       to ::meshAddPeer,
    "sendMessage"   to ::meshSendMessage,
    "getPeers"      to ::meshGetPeers,
    "closeMesh"     to ::meshClose,
    "queueTimer"    to ::timerQueue,
    "cancelTimer"   to ::timerCancel,
    "listTimers"    to ::timerList,
)

// Given a request, dispatches the right callback
suspend fun dispatch(message: WebMessageCompat): BridgeResponse
{
    // Check if request exists
    val rawData = message.data ?: return BridgeResponse(BridgeStatus.ERROR, null, JsonPrimitive("No request"))

    // Check if request is in a valid format
    val request = try {
        Json.decodeFromString<BridgeRequest>(rawData)
    } catch (e: Exception) {
        return BridgeResponse(BridgeStatus.ERROR, null, JsonPrimitive("Malformed request: ${e.message}"))
    }

    // Check if callback exists
    val handler = callbacks[request.callback] ?: return BridgeResponse(BridgeStatus.ERROR, request.id, JsonPrimitive("Callback not registered"))

    // Check if callback is successful
    val result = try {
        handler(request.params)
    } catch (e: Exception) {
        return BridgeResponse(BridgeStatus.ERROR, request.id, JsonPrimitive("Callback encountered an error. " + e.message))
    }

    return BridgeResponse(BridgeStatus.SUCCESS, request.id, result)
}