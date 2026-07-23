package com.app.truvend_cam.ui

import com.app.truvend_cam.data.ChannelInfo
import com.app.truvend_cam.data.StreamType

/**
 * Helpers for working with discovered channel lists.
 */
object ChannelIndex {

    /** Unique video-input channels (cameras), ordered. */
    fun cameras(channels: List<ChannelInfo>): List<Int> =
        channels.map { it.videoInputChannelId }.distinct().sorted()

    fun find(
        channels: List<ChannelInfo>,
        cameraId: Int,
        streamType: StreamType,
    ): ChannelInfo? {
        return channels.firstOrNull {
            it.videoInputChannelId == cameraId && it.streamType == streamType
        } ?: channels.firstOrNull { it.videoInputChannelId == cameraId }
    }

    fun displayChannels(
        channels: List<ChannelInfo>,
        streamType: StreamType,
    ): List<ChannelInfo> {
        val cams = cameras(channels)
        return cams.mapNotNull { find(channels, it, streamType) }
    }

    fun subStreams(channels: List<ChannelInfo>, limit: Int = 4): List<ChannelInfo> =
        displayChannels(channels, StreamType.SUB).take(limit)
}
