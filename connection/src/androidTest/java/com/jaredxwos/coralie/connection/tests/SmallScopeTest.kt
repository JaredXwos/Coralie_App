package com.jaredxwos.coralie.connection.tests

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jaredxwos.coralie.connection.testClients.FakeNostrSignallingClient
import com.jaredxwos.coralie.connection.manager.LiveConnectionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory

/**
 * Smallest-scope tests: every case here is decided by a guard clause that returns
 * *before* LiveConnectionManager ever calls getInitiator()/getAnswerer(). The factory
 * is still a REAL PeerConnectionFactory, though — built fresh per test exactly like
 * LoopbackConnectionTest does — rather than mocked. Mocking it directly hangs
 * indefinitely on a plain JVM (byte-buddy still has to load/instrument the real class,
 * whose static init reaches for the native library and a real Context), which is why
 * this lives in androidTest even though nothing here does real ICE negotiation and
 * the factory itself is never actually invoked past construction.
 *
 * `runTest` (virtual time) remains appropriate here, unlike tests that actually
 * negotiate a real connection — nothing in this file does real native async work,
 * so there's nothing for `runTest`'s real-time watchdog to be waiting on for that
 * reason. There IS a different, unrelated trap, though: NEVER call `advanceUntilIdle()`
 * on a LiveConnectionManager test. Its handshake-timeout ticker (`while (isActive) {
 * delay(...); ... }`) runs for the manager's entire lifetime by design — `advanceUntilIdle()`
 * doesn't return until the dispatcher has no scheduled work left at all, and this ticker
 * always has one more tick pending, so it hangs forever advancing through it. Use
 * `runCurrent()` instead (runs only what's immediately ready, never fast-forwards
 * through a pending `delay()`), and reach for `advanceTimeBy(duration)` specifically
 * in tests that want the ticker/timeout logic to actually fire.
 */
@RunWith(AndroidJUnit4::class)
class SmallScopeTest {

    private val myPubkeyHex = "self-pubkey-hex"

    private fun buildFactory(): PeerConnectionFactory {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        return PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    private fun TestScope.buildManager(signalling: FakeNostrSignallingClient) =
        LiveConnectionManager(
            parentScope = this,
            peerConnectionFactory = buildFactory(),
            myPubkeyHex = myPubkeyHex,
            signalling = signalling,
            iceServers = emptyList(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun peersStartsEmptyAndSignallingIsStartedOnConstruction() = runTest {
        val signalling = FakeNostrSignallingClient()
        val manager = buildManager(signalling)
        runCurrent()

        assertTrue(manager.peers.value.isEmpty())
        assertTrue(signalling.started)

        manager.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun addPeerWithMyOwnPubkeyIsANoOp() = runTest {
        val signalling = FakeNostrSignallingClient()
        val manager = buildManager(signalling)

        manager.addPeer(myPubkeyHex)
        runCurrent()

        assertTrue(manager.peers.value.isEmpty())
        assertEquals(0, signalling.sentMessages.size) // never even tried to offer to itself

        manager.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sendMessageToAnUnconnectedPeerFailsWithoutTouchingTransport() = runTest {
        val signalling = FakeNostrSignallingClient()
        val manager = buildManager(signalling)

        val result = manager.sendMessage("some-other-pubkey", "hello".encodeToByteArray())
        runCurrent()

        assertTrue(result.isFailure)

        manager.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun closeStopsTheSignallingClient() = runTest {
        val signalling = FakeNostrSignallingClient()
        val manager = buildManager(signalling)
        runCurrent()

        manager.close()

        assertTrue(signalling.closed)
    }
}