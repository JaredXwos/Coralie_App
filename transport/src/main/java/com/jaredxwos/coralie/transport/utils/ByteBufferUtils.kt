package com.jaredxwos.coralie.transport.utils

import java.nio.ByteBuffer

fun ByteBuffer.copyToByteArray(): ByteArray {
    val bytes = ByteArray(remaining())
    get(bytes)
    return bytes
}