package com.app.truvend_cam.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.app.truvend_cam.data.ConfigRepository
import com.app.truvend_cam.util.AppLog

/**
 * Starts the RTSP relay after reboot when it was previously enabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        val repo = ConfigRepository(context)
        if (repo.isRelayConfigured()) {
            AppLog.i(TAG, "BOOT_COMPLETED — starting ForwarderService")
            ForwarderService.start(context)
        } else {
            AppLog.i(TAG, "BOOT_COMPLETED — relay not enabled, skipping")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
