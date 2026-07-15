package com.jaredxwos.coralie.timer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds


/**
 * Lets HTML pages queue a one-shot "tell me when N seconds have passed"
 * timer, delivered as a "timerFired" event through the same onEvent
 * channel AppMesh uses for "peers"/"message"/"terminalFailure".
 *
 * Deliberately page-lifetime-scoped, not process-lifetime-scoped: timers
 * live on the same CoroutineScope/sendEvent pair passed in from
 * ViewerScreen (the same ones AppMesh.attach() receives), so they keep
 * running across backgrounding and screen-off — the composable and its
 * scope don't get torn down for that — but are cleared the moment the
 * room itself closes (see teardownForPageExit()). There is no
 * persistence layer here on purpose: if the process is actually killed,
 * these timers are gone, same as AppMesh's connections are.
 */
object AppTimers {
    private var scope: CoroutineScope? = null
    private var sendEvent: ((String, JsonElement) -> Unit)? = null
    private val jobs: MutableMap<String, Job> = mutableMapOf()
    private val targetEpochMs: MutableMap<String, Long> = mutableMapOf()

    /** Called once per PageRender instance, alongside AppMesh.attach() — same scope/sendEvent. */
    fun attach(scope: CoroutineScope, sendEvent: (String, JsonElement) -> Unit) {
        this.scope = scope
        this.sendEvent = sendEvent
    }

    /**
     * Queues a timer under `id`, firing "timerFired" after `delaySeconds`.
     * Re-queuing an `id` that's already pending replaces it (cancels the
     * old one first) rather than running both.
     */
    fun queue(id: String, delaySeconds: Long, payload: String?): String {
        val scope = requireNotNull(scope) { "AppTimers.attach() must be called before queue()" }
        val sendEvent = requireNotNull(sendEvent) { "AppTimers.attach() must be called before queue()" }

        jobs[id]?.cancel()
        targetEpochMs[id] = System.currentTimeMillis() + delaySeconds * 1000

        jobs[id] = scope.launch {
            delay(delaySeconds.seconds)
            jobs.remove(id)
            targetEpochMs.remove(id)
            sendEvent("timerFired", buildJsonObject {
                put("id", id)
                if (payload != null) put("payload", payload)
            })
        }
        return id
    }

    /** Cancels a pending timer. No-op (not an error) if `id` isn't pending — mirrors meshClose-style leniency. */
    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        targetEpochMs.remove(id)
    }

    /** Snapshot of currently pending timers as (id, remainingMs) pairs — e.g. for a countdown UI. */
    fun list(): List<Pair<String, Long>> {
        val now = System.currentTimeMillis()
        return targetEpochMs.map { (id, target) -> id to (target - now).coerceAtLeast(0) }
    }

    /** Called from DisposableEffect.onDispose, alongside AppMesh.teardownForPageExit(). */
    fun teardownForPageExit() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        targetEpochMs.clear()
    }
}