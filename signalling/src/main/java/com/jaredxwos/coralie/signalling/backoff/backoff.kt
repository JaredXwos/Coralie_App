package com.jaredxwos.coralie.signalling.backoff

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A pluggable backoff policy: given how many consecutive failures have
 * occurred, returns the delay before the next retry, or `null` to signal
 * "give up, don't retry again."
 */
typealias BackoffStrategy = (failureCount: Int) -> Duration?

/**
 * Exponential backoff with a hard cap on both delay and attempt count.
 *
 * Indexing convention: [failureCount] is the number of consecutive failures
 * observed so far, 1-indexed. `failureCount = 1` is the delay to apply after
 * the *first* failure, and yields exactly [base]. Each subsequent failure
 * multiplies the previous delay by [multiplier], capped at [max].
 *
 * Returns `null` once [failureCount] exceeds [maxAttempts] — the caller
 * should treat that as a terminal give-up, not schedule another retry.
 *
 * @param failureCount number of consecutive failures so far (must be >= 1)
 * @param base delay applied after the first failure
 * @param max upper bound on any single delay
 * @param multiplier growth factor applied per additional consecutive failure
 * @param maxAttempts number of attempts allowed before giving up; `null` means unlimited
 */
fun exponentialBackoff(
    failureCount: Int,
    base: Duration = 1.seconds,
    max: Duration = 60.seconds,
    multiplier: Double = 2.0,
    maxAttempts: Int? = 10,
): Duration? {
    require(failureCount >= 1) { "failureCount must be >= 1, was $failureCount" }
    if (maxAttempts != null && failureCount > maxAttempts) return null

    val scaled = base * multiplier.pow(failureCount - 1)
    return minOf(scaled, max)
}