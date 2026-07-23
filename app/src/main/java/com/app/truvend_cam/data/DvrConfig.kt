package com.app.truvend_cam.data

/**
 * Persisted DVR connection settings.
 * Password is stored encrypted; never log it.
 */
data class DvrConfig(
    val host: String,
    val httpPort: Int = 80,
    val rtspPort: Int = 554,
    val username: String,
    val password: String,
    val defaultStreamType: StreamType = StreamType.SUB,
    val verified: Boolean = false,
) {
    val httpBaseUrl: String
        get() = "http://$host:$httpPort"

    fun isComplete(): Boolean =
        host.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}
