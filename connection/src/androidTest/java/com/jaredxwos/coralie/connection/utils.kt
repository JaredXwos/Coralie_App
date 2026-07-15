package com.jaredxwos.coralie.connection

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

suspend fun <T> awaitCondition(
    timeoutMs: Long = 30_000,
    pollMs: Long = 100,
    condition: () -> T?,
): T? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        condition()?.let { return it }
        delay(pollMs.milliseconds)
    }
    return condition()
}

/** Boolean-returning convenience wrapper around [awaitCondition]. */
suspend fun awaitTrue(
    timeoutMs: Long = 30_000,
    pollMs: Long = 100,
    condition: () -> Boolean,
): Boolean = awaitCondition(timeoutMs, pollMs) { if (condition()) true else null } ?: false