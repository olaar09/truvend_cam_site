package com.app.truvend_cam.player

/**
 * Player lifecycle states surfaced to the UI.
 */
sealed class PlayerState {
    data object Idle : PlayerState()
    data object Connecting : PlayerState()
    data class Reconnecting(val attempt: Int) : PlayerState()
    data object Playing : PlayerState()
    data class Failed(val message: String, val canRetry: Boolean = true) : PlayerState()
    data object Stopped : PlayerState()
}
