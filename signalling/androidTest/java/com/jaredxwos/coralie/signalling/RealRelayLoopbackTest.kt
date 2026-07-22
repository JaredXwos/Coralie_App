package com.jaredxwos.coralie.signalling

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jaredxwos.coralie.identity.Signer
import com.jaredxwos.coralie.signalling.backoff.exponentialBackoff
import com.jaredxwos.coralie.signalling.eventSink.DedupingEventSink
import com.jaredxwos.coralie.signalling.relaySession.LiveRelaySession
import com.jaredxwos.coralie.signalling.relaySession.RelaySession
import com.jaredxwos.coralie.signalling.relaySocket.LiveRelaySocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs against real public relays over the actual network — deliberately real
 * time, not TestScope virtual time, since the thing under test IS real network
 * behavior. Must run as androidTest (needs the device/emulator for secp256k1's
 * native JNI loading, and needs actual internet access).
 */
@RunWith(AndroidJUnit4::class)
class RealRelayLoopbackTest {

    private val signallingKind = 20001
    private val goodRelays = listOf("wss://relay.damus.io", "wss://nos.lol")
    // Deliberately unreachable — stands in for "one relay forced down," since
    // a real public relay can't be taken offline on command.
    private val deadRelay = "wss://this-relay-does-not-exist.invalid"

    private fun buildClient(signer: Signer, relayUrls: List<String>, httpClient: OkHttpClient): LiveNostrSignallingClient {
        val socketScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sink = DedupingEventSink()
        val relays = relayUrls.map { url ->
            val socket = LiveRelaySocket(httpClient, url, socketScope, ::exponentialBackoff)
            LiveNostrSignallingClient.RelayEndpoint(url, socket, LiveRelaySession(socket, sink))
        }
        return LiveNostrSignallingClient(relays, signer, signallingKind, sink = sink)
    }

    @Test
    fun aliceToBobRoundTripSucceedsDespiteOneDeadRelay() = runBlocking {
        val httpClient = OkHttpClient()
        val alice = Signer()
        val bob = Signer()

        val aliceClient = buildClient(alice, goodRelays, httpClient)
        // Bob's list includes the dead relay — proves the good relay(s) still
        // deliver despite one being unreachable.
        val bobClient = buildClient(bob, goodRelays + deadRelay, httpClient)

        try {
            aliceClient.start()
            bobClient.start()

            withTimeout(15.seconds) {
                while (aliceClient.connectionStatuses().none { it.isOpen } ||
                    bobClient.connectionStatuses()
                        .none { it.subscriptionStatus == RelaySession.SubStatus.LIVE }) {
                    delay(200.milliseconds)
                }
            }

            val sent = aliceClient.send(bob.pubkeyHex, "hello from a real relay test")
            Assert.assertTrue("aliceClient.send() reported failure on every relay", sent)

            val received = withTimeout(15.seconds) { bobClient.inbound.receive() }
            Assert.assertEquals(alice.pubkeyHex, received.fromPubkey)
            Assert.assertEquals("hello from a real relay test", received.plaintext)

            // Delivered once despite 2 good relays both carrying it — proves
            // DedupingEventSink is doing real work against real relay traffic,
            // not just the synthetic duplicate-offer case from the unit test.
            val second = bobClient.inbound.tryReceive()
            Assert.assertNull(second.getOrNull())
        } finally {
            aliceClient.close()
            bobClient.close()
        }
    }
}