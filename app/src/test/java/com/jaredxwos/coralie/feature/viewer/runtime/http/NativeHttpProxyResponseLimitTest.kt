package com.jaredxwos.coralie.feature.viewer.runtime.http

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert
import org.junit.Test

class NativeHttpProxyResponseLimitTest {
    @Test
    fun fixedLimitIs64MiB() {
        Assert.assertEquals(
            64L * 1024L * 1024L,
            NativeHttpProxy.MAX_RESPONSE_BYTES,
        )
    }

    @Test
    fun readsBodyWithinLimit() {
        val body =
            "hello".toResponseBody(
                "text/plain; charset=utf-8".toMediaType(),
            )

        Assert.assertEquals(
            "hello",
            NativeHttpProxy.readResponseBodyLimited(
                body = body,
                maximumBytes = 5L,
            ),
        )
    }

    @Test
    fun rejectsDeclaredBodyAboveLimit() {
        val body =
            "123456".toResponseBody(
                "text/plain".toMediaType(),
            )

        val error = expectResponseTooLarge {
            NativeHttpProxy.readResponseBodyLimited(
                body = body,
                maximumBytes = 5L,
            )
        }

        Assert.assertEquals(5L, error.limitBytes)
        Assert.assertEquals(6L, error.observedBytes)
        Assert.assertEquals(true, error.declaredByServer)
    }

    @Test
    fun rejectsUnknownLengthStreamAboveLimit() {
        val body = object : ResponseBody() {
            override fun contentType(): MediaType =
                "text/plain".toMediaType()

            override fun contentLength(): Long = -1L

            override fun source(): BufferedSource =
                Buffer().writeUtf8("123456")
        }

        val error = expectResponseTooLarge {
            NativeHttpProxy.readResponseBodyLimited(
                body = body,
                maximumBytes = 5L,
            )
        }

        Assert.assertEquals(5L, error.limitBytes)
        Assert.assertEquals(6L, error.observedBytes)
        Assert.assertEquals(false, error.declaredByServer)
    }
    private fun expectResponseTooLarge(
        block: () -> Unit,
    ): ResponseTooLargeException {
        try {
            block()
        } catch (error: ResponseTooLargeException) {
            return error
        }
        throw AssertionError(
            "Expected ResponseTooLargeException",
        )
    }

}