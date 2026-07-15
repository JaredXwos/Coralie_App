package com.jaredxwos.coralie.transport

import com.jaredxwos.coralie.transport.utils.toData
import com.jaredxwos.coralie.transport.utils.toWebRtc
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.SessionDescription

class ConversionsTest {

    @Test
    fun `offer round-trips through toWebRtc and back`() {
        val original = SessionDescriptionData(type = SdpType.OFFER, sdp = "v=0\r\no=- 123 456 IN IP4 0.0.0.0\r\n")
        val webRtc = original.toWebRtc()

        assertEquals(SessionDescription.Type.OFFER, webRtc.type)
        assertEquals(original.sdp, webRtc.description)

        val roundTripped = webRtc.toData()
        assertEquals(original, roundTripped)
    }

    @Test
    fun `answer round-trips through toWebRtc and back`() {
        val original = SessionDescriptionData(type = SdpType.ANSWER, sdp = "v=0\r\no=- 789 012 IN IP4 0.0.0.0\r\n")
        val roundTripped = original.toWebRtc().toData()
        assertEquals(original, roundTripped)
    }

    @Test
    fun `toData throws on PRANSWER or ROLLBACK types`() {
        val prAnswer = SessionDescription(SessionDescription.Type.PRANSWER, "v=0\r\n")
        assertThrows(IllegalStateException::class.java) { prAnswer.toData() }
    }
}