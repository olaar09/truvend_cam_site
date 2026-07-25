package com.app.truvend_cam.ui

import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.app.truvend_cam.R
import com.app.truvend_cam.TruvendApp
import com.app.truvend_cam.data.DvrConfig
import com.app.truvend_cam.data.StreamType
import com.app.truvend_cam.databinding.ActivitySetupBinding
import com.app.truvend_cam.network.IsapiClient
import com.app.truvend_cam.network.RtspUrlBuilder
import com.app.truvend_cam.network.WifiNetworkBinder
import com.app.truvend_cam.player.PlayerState
import com.app.truvend_cam.player.VideoSource
import com.app.truvend_cam.player.VideoSourceFactory
import com.app.truvend_cam.player.VlcVideoSource
import com.app.truvend_cam.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home screen: DVR settings, relay/logs entry points, and Live view when configured.
 * Shows a first-camera preview so LAN binding / RTSP path is warm before relay use.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var adapter: ChannelListAdapter
    private val app get() = application as TruvendApp

    private var homeVideoSource: VideoSource? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var previewStarted = false
    /** Expanded by default; user can Hide/Show. */
    private var previewExpanded = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ChannelListAdapter { /* preview list only */ }
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = adapter

        prefill()
        updateNetworkHint()
        updateLiveViewButton()
        applyPreviewExpandedUi()

        binding.btnLiveView.setOnClickListener { openLiveView() }
        binding.homePreview.setOnClickListener { openLiveView() }
        binding.btnTogglePreview.setOnClickListener { toggleHomePreview() }
        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnSave.setOnClickListener { saveConfig() }
        binding.btnLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.btnRelay.setOnClickListener {
            startActivity(Intent(this, RelayActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        networkCallback = app.wifiBinder.registerNetworkRegain {
            AppLog.i(TAG, "Network regained — refreshing home preview")
            (homeVideoSource as? VlcVideoSource)?.reconnectNow()
        }
        app.wifiBinder.bindToLanSync()
        maybeStartHomePreview()
    }

    override fun onStop() {
        networkCallback?.let { app.wifiBinder.unregisterCallback(it) }
        networkCallback = null
        stopHomePreview()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateNetworkHint()
        updateLiveViewButton()
    }

    private fun updateLiveViewButton() {
        val ready = app.configRepository.hasWorkingConfig() ||
            (app.activeConfig?.verified == true && app.cachedChannels.isNotEmpty())
        binding.btnLiveView.isEnabled = ready
        if (ready && !previewStarted) {
            maybeStartHomePreview()
        } else if (!ready) {
            binding.homePreviewSection.visibility = View.GONE
            stopHomePreview()
        }
    }

    private fun toggleHomePreview() {
        previewExpanded = !previewExpanded
        applyPreviewExpandedUi()
        if (previewExpanded) {
            maybeStartHomePreview()
        } else {
            stopHomePreview()
        }
    }

    private fun applyPreviewExpandedUi() {
        binding.homePreview.visibility = if (previewExpanded) View.VISIBLE else View.GONE
        binding.btnTogglePreview.setText(
            if (previewExpanded) R.string.action_hide_preview else R.string.action_show_preview,
        )
    }

    private fun openLiveView() {
        if (!binding.btnLiveView.isEnabled) {
            Toast.makeText(this, "Test and save the DVR connection first.", Toast.LENGTH_SHORT).show()
            return
        }
        // Persist current form if already verified so stream type changes stick.
        val stream = if (binding.radioMain.isChecked) StreamType.MAIN else StreamType.SUB
        app.activeConfig?.let { cfg ->
            if (cfg.verified) {
                val toSave = cfg.copy(defaultStreamType = stream, verified = true)
                app.configRepository.save(toSave)
                app.activeConfig = toSave
            }
        }
        startActivity(Intent(this, LiveViewActivity::class.java))
    }

    private fun maybeStartHomePreview() {
        val config = app.activeConfig?.takeIf { it.verified }
            ?: app.configRepository.load()?.takeIf { it.verified }
            ?: return
        app.activeConfig = config
        binding.homePreviewSection.visibility = View.VISIBLE
        applyPreviewExpandedUi()
        if (!previewExpanded) return

        binding.homePreviewStatus.text = getString(R.string.status_connecting)

        lifecycleScope.launch {
            if (app.cachedChannels.isEmpty()) {
                val result = withContext(Dispatchers.IO) {
                    app.isapiClient.discoverChannels(config)
                }
                when (result) {
                    is IsapiClient.Result.Ok -> {
                        app.cachedChannels = result.value
                        adapter.submit(result.value)
                        binding.channelsHeader.visibility = View.VISIBLE
                        binding.btnSave.isEnabled = true
                        updateLiveViewButton()
                    }
                    is IsapiClient.Result.Err -> {
                        binding.homePreviewStatus.text = result.error.userMessage
                        AppLog.w(TAG, "Home preview discover failed: ${result.error.userMessage}")
                        return@launch
                    }
                }
            }
            if (previewExpanded) playHomePreview(config)
        }
    }

    private fun playHomePreview(config: DvrConfig) {
        if (!previewExpanded) return
        binding.homePreviewSection.visibility = View.VISIBLE
        applyPreviewExpandedUi()

        val stream = config.defaultStreamType
        val cameras = ChannelIndex.cameras(app.cachedChannels)
        if (cameras.isEmpty()) {
            binding.homePreviewStatus.text = "No channels discovered."
            return
        }
        val cameraId = cameras.first()
        val channel = ChannelIndex.find(app.cachedChannels, cameraId, stream) ?: run {
            binding.homePreviewStatus.text = "Channel $cameraId not found."
            return
        }

        stopHomePreview()
        val url = RtspUrlBuilder.build(
            host = config.host,
            port = config.rtspPort,
            username = config.username,
            password = config.password,
            streamingChannelId = channel.streamingChannelId,
        )
        binding.homePreviewChannel.text = getString(
            R.string.overlay_channel,
            channel.videoInputChannelId,
            channel.displayName,
            stream.label,
        )
        val source = VideoSourceFactory.create(this)
        homeVideoSource = source
        previewStarted = true
        source.setStateListener { state ->
            binding.homePreviewStatus.text = when (state) {
                is PlayerState.Connecting -> getString(R.string.status_connecting)
                is PlayerState.Reconnecting -> getString(R.string.status_reconnecting, state.attempt)
                is PlayerState.Playing -> getString(R.string.status_playing)
                is PlayerState.Failed -> state.message
                else -> ""
            }
        }
        source.attachSurface(binding.homeVideoSurface)
        source.start(url)
        AppLog.i(TAG, "Home preview started cam=$cameraId")
    }

    private fun stopHomePreview() {
        homeVideoSource?.stop()
        homeVideoSource?.release()
        homeVideoSource = null
        previewStarted = false
    }

    private fun prefill() {
        val cfg = app.configRepository.load()
        if (cfg == null) {
            binding.inputHost.setText(R.string.default_dvr_host)
            binding.inputHttpPort.setText("80")
            binding.inputRtspPort.setText("554")
            binding.inputUsername.setText(R.string.default_dvr_username)
            binding.inputPassword.setText(R.string.default_dvr_password)
            binding.radioSub.isChecked = true
            return
        }
        binding.inputHost.setText(cfg.host)
        binding.inputHttpPort.setText(cfg.httpPort.toString())
        binding.inputRtspPort.setText(cfg.rtspPort.toString())
        // Prefer lowercase admin (Hikvision); migrate prior default "Admin".
        val username = if (cfg.username == "Admin") "admin" else cfg.username
        binding.inputUsername.setText(username)
        binding.inputPassword.setText(cfg.password)
        if (cfg.defaultStreamType == StreamType.MAIN) {
            binding.radioMain.isChecked = true
        } else {
            binding.radioSub.isChecked = true
        }
        if (cfg.verified) {
            binding.btnSave.isEnabled = true
            app.activeConfig = cfg.copy(username = username)
            if (app.cachedChannels.isNotEmpty()) {
                adapter.submit(app.cachedChannels)
                binding.channelsHeader.visibility = View.VISIBLE
            }
        }
    }

    private fun updateNetworkHint() {
        val status = app.wifiBinder.currentLanStatus()
        binding.networkHint.text = when (status) {
            is WifiNetworkBinder.LanStatus.CellularOnly ->
                getString(R.string.error_cellular_only)
            is WifiNetworkBinder.LanStatus.WifiAvailable ->
                "Wi‑Fi / Ethernet detected — DVR traffic will be bound to this network."
            is WifiNetworkBinder.LanStatus.NoNetwork ->
                "No network connection detected."
            is WifiNetworkBinder.LanStatus.Unknown ->
                "Network status unknown."
        }
        if (status is WifiNetworkBinder.LanStatus.CellularOnly) {
            binding.networkHint.setTextColor(getColor(R.color.error))
        } else {
            binding.networkHint.setTextColor(getColor(R.color.text_secondary))
        }
    }

    private fun readConfig(): DvrConfig? {
        val host = binding.inputHost.text?.toString()?.trim().orEmpty()
        val httpPort = binding.inputHttpPort.text?.toString()?.toIntOrNull() ?: 80
        val rtspPort = binding.inputRtspPort.text?.toString()?.toIntOrNull() ?: 554
        val username = binding.inputUsername.text?.toString()?.trim().orEmpty()
        val password = binding.inputPassword.text?.toString().orEmpty()
        if (host.isBlank() || username.isBlank() || password.isBlank()) {
            binding.statusText.text = "Enter IP, username, and password."
            return null
        }
        val stream = if (binding.radioMain.isChecked) StreamType.MAIN else StreamType.SUB
        return DvrConfig(
            host = host,
            httpPort = httpPort,
            rtspPort = rtspPort,
            username = username,
            password = password,
            defaultStreamType = stream,
            verified = false,
        )
    }

    private fun testConnection() {
        val config = readConfig() ?: return
        if (app.wifiBinder.currentLanStatus() is WifiNetworkBinder.LanStatus.CellularOnly) {
            binding.statusText.text = getString(R.string.error_cellular_only)
            binding.statusText.setTextColor(getColor(R.color.error))
            return
        }

        binding.progress.visibility = View.VISIBLE
        binding.btnTest.isEnabled = false
        binding.statusText.text = "Testing connection…"
        binding.statusText.setTextColor(getColor(R.color.text_secondary))

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                app.isapiClient.discoverChannels(config)
            }
            binding.progress.visibility = View.GONE
            binding.btnTest.isEnabled = true

            when (result) {
                is IsapiClient.Result.Ok -> {
                    app.cachedChannels = result.value
                    app.activeConfig = config.copy(verified = true)
                    adapter.submit(result.value)
                    binding.channelsHeader.visibility = View.VISIBLE
                    binding.btnSave.isEnabled = true
                    updateLiveViewButton()
                    binding.statusText.setTextColor(getColor(R.color.accent))
                    binding.statusText.text =
                        "Connected. Found ${result.value.size} streaming channels."
                    AppLog.i(TAG, "Test OK — ${result.value.size} channels")
                    playHomePreview(config.copy(verified = true))
                }
                is IsapiClient.Result.Err -> {
                    binding.btnSave.isEnabled = false
                    binding.channelsHeader.visibility = View.GONE
                    adapter.submit(emptyList())
                    stopHomePreview()
                    binding.homePreviewSection.visibility = View.GONE
                    updateLiveViewButton()
                    binding.statusText.setTextColor(getColor(R.color.error))
                    binding.statusText.text = result.error.userMessage
                    AppLog.w(TAG, "Test failed: ${result.error.userMessage}")
                }
            }
        }
    }

    private fun saveConfig() {
        val config = app.activeConfig?.copy(verified = true) ?: readConfig()?.copy(verified = true)
        if (config == null || app.cachedChannels.isEmpty()) {
            Toast.makeText(this, "Test the connection first.", Toast.LENGTH_SHORT).show()
            return
        }
        val stream = if (binding.radioMain.isChecked) StreamType.MAIN else StreamType.SUB
        val toSave = config.copy(defaultStreamType = stream, verified = true)
        app.configRepository.save(toSave)
        app.activeConfig = toSave
        updateLiveViewButton()
        binding.statusText.setTextColor(getColor(R.color.accent))
        binding.statusText.text = "Saved. Tap Live view to watch cameras."
        Toast.makeText(this, R.string.action_save, Toast.LENGTH_SHORT).show()
        playHomePreview(toSave)
    }

    companion object {
        /** Kept for callers that still pass it; Home no longer auto-skips. */
        const val EXTRA_FORCE_SETUP = "force_setup"
        private const val TAG = "SetupActivity"
    }
}
