package com.jaredxwos.coralie.feature.viewer.runtime.mesh

import com.jaredxwos.coralie.connection.externalMessages.PeerMessage
import com.jaredxwos.coralie.connection.externalMessages.TerminalFailure
import com.jaredxwos.coralie.connection.manager.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRuntimeLifecycleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun pageReloadRebindsEventsWithoutClosingManagerOrLosingPeers() {
        val manager = FakeConnectionManager("identity-a")
        val runtime = runtimeBuilding(manager)
        val firstEvents = mutableListOf<String>()
        val reboundEvents = mutableListOf<String>()

        runtime.attach("session", scope) { type, _ -> firstEvents += type }
        assertEquals("identity-a", runtime.start("session"))
        manager.mutablePeers.value = setOf("peer-a")
        val eventsBeforeRebind = firstEvents.size

        runtime.attach("session", scope) { type, _ -> reboundEvents += type }
        manager.mutablePeers.value = setOf("peer-a", "peer-b")

        assertSame(manager, runtime.current)
        assertEquals(setOf("peer-a", "peer-b"), manager.peers.value)
        assertTrue(
            runBlocking {
                runtime.current!!.sendMessage("peer-a", byteArrayOf(1)).isSuccess
            },
        )
        assertEquals(0, manager.closeCount)
        assertTrue("replacement page receives peer state", reboundEvents.contains("peers"))
        assertEquals(
            "destroyed page no longer receives events",
            eventsBeforeRebind,
            firstEvents.size,
        )
    }

    @Test
    fun configurationRebindAndStaleSessionExitCannotCloseActiveMesh() {
        val manager = FakeConnectionManager("identity-a")
        val runtime = runtimeBuilding(manager)

        runtime.attach("old-session", scope, ::ignoreEvent)
        runtime.start("old-session")
        runtime.attach("new-session", scope, ::ignoreEvent)
        assertEquals("identity-a", runtime.start("new-session"))

        runtime.teardown("old-session")

        assertSame(manager, runtime.current)
        assertEquals(0, manager.closeCount)
    }

    @Test
    fun ownerExitBeforeStartClearsItsBindingWithoutCreatingManager() {
        var buildCount = 0
        val runtime = MeshRuntime().apply {
            configure {
                buildCount += 1
                FakeConnectionManager("identity-a")
            }
        }

        runtime.attach("session", scope, ::ignoreEvent)
        runtime.teardown("session")

        assertEquals(null, runtime.current)
        assertEquals(0, buildCount)
        val error = runCatching { runtime.start("session") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun intentionalExitClosesManagerAndChannelsExactlyOnce() {
        val manager = FakeConnectionManager("identity-a")
        val runtime = runtimeBuilding(manager)
        runtime.attach("session", scope, ::ignoreEvent)
        runtime.start("session")

        runtime.teardown("session")
        runtime.teardown("session")

        assertEquals(null, runtime.current)
        assertEquals(1, manager.closeCount)
        assertTrue(manager.terminal.tryReceive().isClosed)
    }

    @Test
    fun explicitResetClosesOldChannelsAndBuildsFreshIdentity() {
        val old = FakeConnectionManager("identity-a")
        val replacement = FakeConnectionManager("identity-b")
        val managers = ArrayDeque(listOf(old, replacement))
        val runtime = MeshRuntime().apply { configure { managers.removeFirst() } }
        runtime.attach("session", scope, ::ignoreEvent)
        runtime.start("session")

        assertEquals("identity-b", runtime.reset("session"))

        assertEquals(1, old.closeCount)
        assertTrue(old.terminal.tryReceive().isClosed)
        assertSame(replacement, runtime.current)
        assertEquals(0, replacement.closeCount)
    }

    private fun runtimeBuilding(manager: FakeConnectionManager) =
        MeshRuntime().apply { configure { manager } }

    private fun ignoreEvent(type: String, data: JsonElement) = Unit

    private class FakeConnectionManager(
        override val myPubkeyHex: String,
    ) : ConnectionManager {
        val mutablePeers = MutableStateFlow<Set<String>>(emptySet())
        override val peers: StateFlow<Set<String>> = mutablePeers
        private val messages = MutableSharedFlow<PeerMessage>()
        override val incomingMessages: SharedFlow<PeerMessage> = messages
        val terminal = Channel<TerminalFailure>(Channel.UNLIMITED)
        override val terminalFailures: ReceiveChannel<TerminalFailure> = terminal
        var closeCount = 0

        override fun addPeer(pubkeyHex: String) = Unit

        override suspend fun sendMessage(toPubkeyHex: String, bytes: ByteArray): Result<Unit> =
            if (toPubkeyHex in peers.value) Result.success(Unit)
            else Result.failure(IllegalStateException("stale or unavailable channel"))

        override fun close() {
            closeCount += 1
            terminal.close()
        }
    }
}
