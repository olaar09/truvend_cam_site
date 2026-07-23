package com.app.truvend_cam.network

import com.app.truvend_cam.data.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IsapiXmlParserTest {

    @Test
    fun parseStreamingChannels_extractsMainAndSub() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <StreamingChannelList>
              <StreamingChannel>
                <id>101</id>
                <channelName>Camera 01</channelName>
                <name>Camera 01</name>
                <enabled>true</enabled>
                <Video>
                  <videoCodecType>H.264</videoCodecType>
                  <videoResolutionWidth>1920</videoResolutionWidth>
                  <videoResolutionHeight>1080</videoResolutionHeight>
                </Video>
                <videoInputChannelID>1</videoInputChannelID>
              </StreamingChannel>
              <StreamingChannel>
                <id>102</id>
                <name>Camera 01</name>
                <Video>
                  <videoCodecType>H.264</videoCodecType>
                  <videoResolutionWidth>704</videoResolutionWidth>
                  <videoResolutionHeight>576</videoResolutionHeight>
                </Video>
                <videoInputChannelID>1</videoInputChannelID>
              </StreamingChannel>
              <StreamingChannel>
                <id>201</id>
                <name>Gate</name>
                <videoInputChannelID>2</videoInputChannelID>
                <Video>
                  <videoCodecType>H.265</videoCodecType>
                </Video>
              </StreamingChannel>
            </StreamingChannelList>
        """.trimIndent()

        val channels = IsapiXmlParser.parseStreamingChannels(xml)
        assertEquals(3, channels.size)

        val ch101 = channels.first { it.streamingChannelId == 101 }
        assertEquals("Camera 01", ch101.name)
        assertEquals(1, ch101.videoInputChannelId)
        assertEquals(StreamType.MAIN, ch101.streamType)
        assertEquals("H.264", ch101.codec)
        assertEquals(1920, ch101.width)
        assertEquals(1080, ch101.height)

        val ch102 = channels.first { it.streamingChannelId == 102 }
        assertEquals(StreamType.SUB, ch102.streamType)
        assertEquals(704, ch102.width)

        val ch201 = channels.first { it.streamingChannelId == 201 }
        assertEquals("Gate", ch201.name)
        assertEquals("H.265", ch201.codec)
    }

    @Test
    fun parseVideoInputChannels_andExpand() {
        val xml = """
            <VideoInputChannelList>
              <VideoInputChannel>
                <id>1</id>
                <inputPort>1</inputPort>
                <name>Cam1</name>
              </VideoInputChannel>
              <VideoInputChannel>
                <id>3</id>
                <name>Cam3</name>
              </VideoInputChannel>
            </VideoInputChannelList>
        """.trimIndent()

        val ids = IsapiXmlParser.parseVideoInputChannels(xml)
        assertEquals(listOf(1, 3), ids)

        val expanded = IsapiXmlParser.expandInputChannelsToStreaming(ids)
        assertEquals(4, expanded.size)
        assertTrue(expanded.any { it.streamingChannelId == 101 && it.streamType == StreamType.MAIN })
        assertTrue(expanded.any { it.streamingChannelId == 102 && it.streamType == StreamType.SUB })
        assertTrue(expanded.any { it.streamingChannelId == 301 })
        assertTrue(expanded.any { it.streamingChannelId == 302 })
    }

    @Test
    fun probeChannelList_coversSixteenCameras() {
        val list = IsapiXmlParser.probeChannelList(16)
        assertEquals(32, list.size)
        assertEquals(101, list.first().streamingChannelId)
        assertEquals(1602, list.last().streamingChannelId)
    }

    @Test
    fun parseStreamingChannels_emptyOnGarbage() {
        val channels = IsapiXmlParser.parseStreamingChannels("<root></root>")
        assertTrue(channels.isEmpty())
    }
}
