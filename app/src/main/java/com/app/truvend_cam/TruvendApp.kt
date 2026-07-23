package com.app.truvend_cam

import android.app.Application
import com.app.truvend_cam.data.ChannelInfo
import com.app.truvend_cam.data.ConfigRepository
import com.app.truvend_cam.data.DvrConfig
import com.app.truvend_cam.network.IsapiClient
import com.app.truvend_cam.network.WifiNetworkBinder
import com.app.truvend_cam.player.VlcHolder
import com.app.truvend_cam.util.AppLog

class TruvendApp : Application() {

    lateinit var configRepository: ConfigRepository
        private set
    lateinit var wifiBinder: WifiNetworkBinder
        private set
    lateinit var isapiClient: IsapiClient
        private set

    /** Last successful discovery — kept in memory for live/grid screens. */
    @Volatile
    var cachedChannels: List<ChannelInfo> = emptyList()

    @Volatile
    var activeConfig: DvrConfig? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        configRepository = ConfigRepository(this)
        wifiBinder = WifiNetworkBinder(this)
        isapiClient = IsapiClient(wifiBinder)
        activeConfig = configRepository.load()
        // Warm LibVLC once at process start
        VlcHolder.get(this)
        AppLog.i(TAG, "TruvendApp started")
    }

    companion object {
        private const val TAG = "TruvendApp"
        lateinit var instance: TruvendApp
            private set
    }
}
