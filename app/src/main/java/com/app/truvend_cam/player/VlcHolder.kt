package com.app.truvend_cam.player

import android.content.Context
import org.videolan.libvlc.LibVLC

/**
 * Application-scoped LibVLC holder. Instantiate once.
 */
object VlcHolder {

    @Volatile
    private var libVlc: LibVLC? = null

    private val lock = Any()

    fun get(context: Context): LibVLC {
        libVlc?.let { return it }
        synchronized(lock) {
            libVlc?.let { return it }
            val options = arrayListOf(
                "--no-audio",
                "--network-caching=300",
                "--clock-jitter=0",
                "--clock-synchro=0",
                "--drop-late-frames",
                "--skip-frames",
            )
            val instance = LibVLC(context.applicationContext, options)
            libVlc = instance
            return instance
        }
    }

    fun release() {
        synchronized(lock) {
            libVlc?.release()
            libVlc = null
        }
    }
}
