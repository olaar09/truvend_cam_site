package com.app.truvend_cam.data

/**
 * Relay-specific settings persisted alongside DVR credentials.
 * DVR host and RTSP port live in [DvrConfig] — one source of truth.
 */
data class RelaySettings(
    val listenPort: Int = DEFAULT_LISTEN_PORT,
    val enabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_LISTEN_PORT = 8554
    }
}
