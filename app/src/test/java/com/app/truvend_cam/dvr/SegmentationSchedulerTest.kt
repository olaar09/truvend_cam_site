package com.app.truvend_cam.dvr

import com.app.truvend_cam.data.ChannelInfo
import com.app.truvend_cam.data.StreamType
import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentationSchedulerTest {

    @Test
    fun mainTrackIds_prefersDiscoveredMainStreams() {
        val channels = listOf(
            channel(102, input = 1, StreamType.SUB),
            channel(101, input = 1, StreamType.MAIN),
            channel(201, input = 2, StreamType.MAIN),
            channel(202, input = 2, StreamType.SUB),
        )
        assertEquals(listOf(101, 201), SegmentationScheduler.mainTrackIds(channels))
    }

    @Test
    fun mainTrackIds_derivesFromInputsWhenOnlySubsPresent() {
        val channels = listOf(
            channel(102, input = 1, StreamType.SUB),
            channel(202, input = 2, StreamType.SUB),
        )
        assertEquals(listOf(101, 201), SegmentationScheduler.mainTrackIds(channels))
    }

    @Test
    fun mainTrackIds_fallsBackTo101WhenEmpty() {
        assertEquals(listOf(101), SegmentationScheduler.mainTrackIds(emptyList()))
    }

    @Test
    fun segmentationSettings_clampsInterval() {
        assertEquals(1, SegmentationSettings(intervalHours = 0).clampedIntervalHours)
        assertEquals(24 * 7, SegmentationSettings(intervalHours = 9999).clampedIntervalHours)
        assertEquals(3_600_000L, SegmentationSettings(intervalHours = 1).intervalMs)
    }

    private fun channel(id: Int, input: Int, type: StreamType) = ChannelInfo(
        streamingChannelId = id,
        name = "ch$input",
        videoInputChannelId = input,
        streamType = type,
    )
}
