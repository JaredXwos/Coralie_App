package com.jaredxwos.coralie.connection

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import org.webrtc.PeerConnectionFactory
import kotlin.time.Duration.Companion.milliseconds

private val rtcInitializationLock = Any()

@Volatile
private var rtcInitialized = false

fun buildPeerConnectionFactory(): PeerConnectionFactory {
    if (!rtcInitialized) {
        synchronized(rtcInitializationLock) {
            if (!rtcInitialized) {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(context.applicationContext)
                        .createInitializationOptions(),
                )
                rtcInitialized = true
            }
        }
    }
    return PeerConnectionFactory.builder().createPeerConnectionFactory()
}

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

suspend fun awaitTrue(
    timeoutMs: Long = 30_000,
    pollMs: Long = 100,
    condition: () -> Boolean,
): Boolean = awaitCondition(timeoutMs, pollMs) { if (condition()) true else null } ?: false
