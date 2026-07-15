package com.jaredxwos.coralie.connection

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class DataChannelFrame {
    @Serializable @SerialName("app")
    data class App(val payload: ByteArray) : DataChannelFrame() {
        override fun equals(other: Any?): Boolean =
            other is App && payload.contentEquals(other.payload)
        override fun hashCode(): Int = payload.contentHashCode()
    }

    @Serializable @SerialName("announce")
    data class Announce(val pubkeyHex: String) : DataChannelFrame()
}