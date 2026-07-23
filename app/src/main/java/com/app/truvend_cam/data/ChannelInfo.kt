package com.app.truvend_cam.data

/**
 * A discoverable streaming endpoint on the DVR.
 *
 * [streamingChannelId] is the Hikvision Streaming/Channels id
 * (e.g. 101 = ch1 main, 102 = ch1 sub).
 * [videoInputChannelId] is the camera/input index (1-based) when known.
 */
data class ChannelInfo(
    val streamingChannelId: Int,
    val name: String,
    val videoInputChannelId: Int,
    val streamType: StreamType,
    val codec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    val displayName: String
        get() = name.ifBlank { "Channel $videoInputChannelId" }
}
