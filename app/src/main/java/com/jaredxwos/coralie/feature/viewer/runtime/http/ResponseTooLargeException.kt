package com.jaredxwos.coralie.feature.viewer.runtime.http

import java.io.IOException

/**
 * Raised when an HTTP response exceeds Coralie's fixed native response limit.
 *
 * [observedBytes] is either the server-declared Content-Length or the number of
 * decoded response bytes read before the proxy stopped consuming the body.
 */
class ResponseTooLargeException(
    val limitBytes: Long,
    val observedBytes: Long,
    val declaredByServer: Boolean,
) : IOException(
    buildString {
        append("Response exceeds size limit")
        append(" (limit=").append(limitBytes)
        append(" bytes, observed=").append(observedBytes)
        append(" bytes")
        if (declaredByServer) {
            append(", source=content-length")
        } else {
            append(", source=response-stream")
        }
        append(')')
    },
)
