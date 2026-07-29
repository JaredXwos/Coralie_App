package com.jaredxwos.coralie.feature.viewer.bridge

import com.jaredxwos.coralie.feature.viewer.runtime.permission.PermissionRejectedException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun sendMessageSuccessResponse(): String =
    buildJsonObject {
        put("ok", true)
    }.toString()

internal fun sendMessageFailureResponse(
    error: Exception,
    target: String,
    peerUnavailable: Boolean,
): String {
    val errorName: String
    val message: String

    when {
        peerUnavailable -> {
            errorName = "PeerUnavailableError"
            message =
                "Peer disconnected or channel unavailable"
        }

        error is PermissionRejectedException -> {
            errorName = "PermissionRejectedError"
            message =
                error.message
                    ?: "Mesh permission was rejected"
        }

        error is IllegalArgumentException -> {
            errorName = "InvalidArgumentError"
            message =
                error.message
                    ?: "Invalid sendMessage argument"
        }

        else -> {
            errorName = "CoralieHostError"
            message = "Unable to send message"
        }
    }

    return buildJsonObject {
        put("ok", false)
        put("errorName", errorName)
        put("message", message)
        put("operation", "sendMessage")
        put("target", target)
    }.toString()
}
