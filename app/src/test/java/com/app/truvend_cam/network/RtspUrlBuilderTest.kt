package com.app.truvend_cam.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspUrlBuilderTest {

    @Test
    fun streamingChannelId_mainAndSub() {
        assertEquals(101, RtspUrlBuilder.streamingChannelId(1, 1))
        assertEquals(102, RtspUrlBuilder.streamingChannelId(1, 2))
        assertEquals(201, RtspUrlBuilder.streamingChannelId(2, 1))
        assertEquals(802, RtspUrlBuilder.streamingChannelId(8, 2))
        assertEquals(1601, RtspUrlBuilder.streamingChannelId(16, 1))
    }

    @Test
    fun channelNumberAndStreamType_roundTrip() {
        assertEquals(1, RtspUrlBuilder.channelNumberOf(101))
        assertEquals(1, RtspUrlBuilder.streamTypeCodeOf(101))
        assertEquals(8, RtspUrlBuilder.channelNumberOf(802))
        assertEquals(2, RtspUrlBuilder.streamTypeCodeOf(802))
    }

    @Test
    fun build_includesTcpPathAndPorts() {
        val url = RtspUrlBuilder.build(
            host = "192.168.1.64",
            port = 554,
            username = "admin",
            password = "secret",
            streamingChannelId = 102,
        )
        assertEquals(
            "rtsp://admin:secret@192.168.1.64:554/Streaming/Channels/102",
            url,
        )
    }

    @Test
    fun build_encodesSpecialCharsInPassword() {
        val url = RtspUrlBuilder.build(
            host = "10.0.0.5",
            port = 554,
            username = "admin",
            password = "p@ss word",
            streamingChannelId = 101,
        )
        assertTrue(url.contains("p%40ss%20word") || url.contains("%40"))
        assertTrue(url.startsWith("rtsp://admin:"))
        assertTrue(url.contains("@10.0.0.5:554/Streaming/Channels/101"))
    }

    @Test
    fun redact_hidesPassword() {
        val url = "rtsp://admin:s3cret@192.168.1.64:554/Streaming/Channels/101"
        val redacted = RtspUrlBuilder.redact(url)
        assertFalse(redacted.contains("s3cret"))
        assertTrue(redacted.contains("***"))
        assertTrue(redacted.contains("admin"))
    }
}
