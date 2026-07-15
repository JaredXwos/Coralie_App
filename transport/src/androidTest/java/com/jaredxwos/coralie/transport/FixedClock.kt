package com.jaredxwos.coralie.transport

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class FixedClock(private var current: Instant) : Clock {
    override fun now(): Instant = current
    fun advanceBy(duration: Duration) { current += duration }
}