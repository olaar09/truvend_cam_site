package com.app.truvend_cam.data

/**
 * Relay-specific settings persisted alongside DVR credentials.
 * DVR host and ports live in [DvrConfig] — one source of truth.
 *
 * Listen ports:
 * - [listenPort] (default 8554) → DVR RTSP ([DvrConfig.rtspPort], typically 554)
 * - [DEFAULT_HTTP_LISTEN_PORT] (8080, fixed) → DVR HTTP/ISAPI ([DvrConfig.httpPort], typically 80)
 */
data class RelaySettings(
    val listenPort: Int = DEFAULT_LISTEN_PORT,
    val enabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_LISTEN_PORT = 8554
        /** Fixed tunnel-side HTTP/ISAPI listen port (forwards to DVR httpPort). */
        const val DEFAULT_HTTP_LISTEN_PORT = 8080
    }
}
