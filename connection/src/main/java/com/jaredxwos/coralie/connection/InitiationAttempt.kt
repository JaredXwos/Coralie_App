package com.jaredxwos.coralie.connection

import com.jaredxwos.coralie.transport.context.Initiator
import kotlinx.coroutines.Job
import kotlin.time.Instant

internal data class InitiationAttempt(
    val initiator: Initiator,
    val attemptCount: Int,
    val startedAt: Instant,
    val watcherJob: Job,
)