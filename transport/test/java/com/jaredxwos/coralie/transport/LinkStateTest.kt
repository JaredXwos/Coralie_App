package com.jaredxwos.coralie.transport

import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant

class LinkStateTest {

    @Test
    fun `AwaitingRemoteDescription carries its own timestamp`() {
        val since = Instant.parse("2026-07-10T00:00:00Z")
        val state = LinkState.AwaitingRemoteDescription(since)
        assertEquals(since, state.since)
    }

    @Test
    fun `two AwaitingRemoteDescription with same instant are equal`() {
        val since = Instant.parse("2026-07-10T00:00:00Z")
        assertEquals(LinkState.AwaitingRemoteDescription(since), LinkState.AwaitingRemoteDescription(since))
    }

    @Test
    fun `object states are singletons`() {
        assertSame(LinkState.Connected, LinkState.Connected)
        assertSame(LinkState.Failed, LinkState.Failed)
    }

    @Test
    fun `different LinkState subtypes are not equal`() {
        assertNotEquals(LinkState.New as LinkState, LinkState.Connecting as LinkState)
    }
}