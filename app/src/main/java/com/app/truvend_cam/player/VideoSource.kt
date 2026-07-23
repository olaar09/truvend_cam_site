package com.app.truvend_cam.player

import android.view.SurfaceView

/**
 * Abstraction over the video backend.
 * Nothing outside the player module should import VLC classes.
 * Phase 2 can add a snapshot-polling source alongside this interface.
 */
interface VideoSource {
    fun attachSurface(surfaceView: SurfaceView)
    fun start(url: String)
    fun stop()
    fun release()
    fun setStateListener(listener: ((PlayerState) -> Unit)?)
}
