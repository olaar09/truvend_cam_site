package com.app.truvend_cam.player

import android.content.Context

/**
 * Creates [VideoSource] instances. UI code depends only on the interface.
 */
object VideoSourceFactory {
    fun create(context: Context): VideoSource {
        return VlcVideoSource(VlcHolder.get(context))
    }
}
