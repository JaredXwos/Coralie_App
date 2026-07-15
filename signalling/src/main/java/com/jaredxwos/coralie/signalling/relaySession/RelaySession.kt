package com.jaredxwos.coralie.signalling.relaySession

import com.jaredxwos.coralie.signalling.nostrMessage.ClientToServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface RelaySession {
    enum class SubStatus { PENDING, LIVE, STOPPED }

    val subStates: StateFlow<Map<String, SubStatus>>

    fun start(scope: CoroutineScope)
    suspend fun subscribe(request: ClientToServerMessage.Req): Result<Unit>
    suspend fun publish(event: ClientToServerMessage.Event): Result<Unit>
}