package com.app.truvend_cam.network

sealed class ConnectionError(open val userMessage: String) {
    data class AuthFailed(
        override val userMessage: String = "Wrong username or password.",
    ) : ConnectionError(userMessage)

    data class NoResponse(
        val host: String,
        override val userMessage: String =
            "No response from $host — check the IP and that this device is on the same network as the DVR.",
    ) : ConnectionError(userMessage)

    data class HttpRejected(
        val code: Int,
        override val userMessage: String =
            "Reached the DVR but it rejected the request (HTTP $code).",
    ) : ConnectionError(userMessage)

    data class CellularOnly(
        override val userMessage: String =
            "This phone is not on the camera Wi‑Fi network. Connect to the same LAN as the DVR (mobile data alone cannot reach 192.168.x.x addresses).",
    ) : ConnectionError(userMessage)

    data class ParseFailed(
        override val userMessage: String =
            "Reached the DVR but could not understand the channel list. Check firmware / ISAPI support.",
    ) : ConnectionError(userMessage)

    data class Generic(
        val detail: String,
        override val userMessage: String = "Could not connect: $detail",
    ) : ConnectionError(userMessage)
}
