package com.app.truvend_cam.data

/**
 * Preferred RTSP stream quality.
 * Hikvision channel id = channelNumber * 100 + streamTypeCode
 * where main=1, sub=2, third=3.
 */
enum class StreamType(val code: Int, val label: String) {
    MAIN(1, "Main"),
    SUB(2, "Sub");

    companion object {
        fun fromCode(code: Int): StreamType =
            entries.firstOrNull { it.code == code } ?: SUB
    }
}
