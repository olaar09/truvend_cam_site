package com.app.truvend_cam.network

/**
 * Builds Hikvision RTSP URLs.
 *
 * Pattern (confirm on site — see README):
 *   rtsp://user:pass@host:554/Streaming/Channels/<id>
 * where id = channelNumber * 100 + streamTypeCode (1=main, 2=sub).
 */
object RtspUrlBuilder {

    fun build(
        host: String,
        port: Int,
        username: String,
        password: String,
        streamingChannelId: Int,
    ): String {
        val user = encodeUserInfo(username)
        val pass = encodeUserInfo(password)
        return "rtsp://$user:$pass@$host:$port/Streaming/Channels/$streamingChannelId"
    }

    fun streamingChannelId(channelNumber: Int, streamTypeCode: Int): Int {
        require(channelNumber in 1..99) { "channelNumber must be 1..99" }
        require(streamTypeCode in 1..3) { "streamTypeCode must be 1..3" }
        return channelNumber * 100 + streamTypeCode
    }

    fun channelNumberOf(streamingChannelId: Int): Int = streamingChannelId / 100

    fun streamTypeCodeOf(streamingChannelId: Int): Int = streamingChannelId % 100

    /**
     * Encode credentials for userinfo section of URI.
     * Does not touch the password for logging — callers must never log the result.
     */
    internal fun encodeUserInfo(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            when {
                ch.isLetterOrDigit() || ch in "-._~" -> sb.append(ch)
                else -> {
                    val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                    for (b in bytes) {
                        sb.append('%')
                        sb.append(((b.toInt() and 0xFF) shr 4).toString(16).uppercase())
                        sb.append((b.toInt() and 0x0F).toString(16).uppercase())
                    }
                }
            }
        }
        return sb.toString()
    }

    /** Redacted form safe for logs. */
    fun redact(url: String): String =
        url.replace(Regex("://([^:]+):([^@]+)@"), "://$1:***@")
}
