package com.app.truvend_cam.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.app.truvend_cam.TruvendApp
import com.app.truvend_cam.data.ChannelInfo
import com.app.truvend_cam.data.StreamType
import com.app.truvend_cam.databinding.ActivityGridBinding
import com.app.truvend_cam.databinding.ViewGridTileBinding
import com.app.truvend_cam.network.IsapiClient
import com.app.truvend_cam.network.RtspUrlBuilder
import com.app.truvend_cam.player.PlayerState
import com.app.truvend_cam.player.VideoSource
import com.app.truvend_cam.player.VideoSourceFactory
import com.app.truvend_cam.player.VlcVideoSource
import com.app.truvend_cam.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 2×2 grid of sub-streams only, hard-capped at 4 concurrent players.
 * Each tile reconnects independently — one failure must not take down the grid.
 */
class GridActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGridBinding
    private val app get() = application as TruvendApp
    private val handler = Handler(Looper.getMainLooper())

    private val tiles = mutableListOf<TileController>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val hideOverlayRunnable = Runnable { setOverlayVisible(false) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGridBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.btnHome.setOnClickListener {
            startActivity(
                Intent(this, SetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
        binding.btnViewToggle.setOnClickListener {
            startActivity(
                Intent(this, LiveViewActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finish()
        }

        binding.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) bumpOverlay()
            false
        }

        lifecycleScope.launch {
            ensureChannels()
            val subs = ChannelIndex.subStreams(app.cachedChannels, MAX_TILES)
            if (subs.isEmpty()) {
                AppLog.w(TAG, "No sub-streams for grid")
                return@launch
            }
            val tileRoots = listOf(binding.tile0, binding.tile1, binding.tile2, binding.tile3)
            tiles.clear()
            for (i in tileRoots.indices) {
                val tileBinding: ViewGridTileBinding = tileRoots[i]
                val channel = subs.getOrNull(i)
                val controller = TileController(tileBinding, channel)
                tiles += controller
                if (channel != null) {
                    tileBinding.root.setOnClickListener {
                        bumpOverlay()
                        promote(channel.videoInputChannelId)
                    }
                    tileBinding.root.setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) {
                            tileBinding.root.isSelected = true
                            bumpOverlay()
                        }
                    }
                    tileBinding.root.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_UP &&
                            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                keyCode == KeyEvent.KEYCODE_ENTER)
                        ) {
                            bumpOverlay()
                            promote(channel.videoInputChannelId)
                            true
                        } else false
                    }
                } else {
                    tileBinding.root.visibility = View.INVISIBLE
                }
            }
            tileRoots.firstOrNull()?.root?.requestFocus()
            bumpOverlay()
        }
    }

    override fun onStart() {
        super.onStart()
        networkCallback = app.wifiBinder.registerNetworkRegain {
            tiles.forEach { (it.source as? VlcVideoSource)?.reconnectNow() }
        }
        tiles.forEach { it.start() }
    }

    override fun onStop() {
        networkCallback?.let { app.wifiBinder.unregisterCallback(it) }
        networkCallback = null
        tiles.forEach { it.release() }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideOverlayRunnable)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        bumpOverlay()
        return super.onKeyDown(keyCode, event)
    }

    private fun bumpOverlay() {
        setOverlayVisible(true)
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_MS)
    }

    private fun setOverlayVisible(visible: Boolean) {
        binding.overlayTitle.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private suspend fun ensureChannels() {
        val config = app.activeConfig ?: app.configRepository.load() ?: return
        app.activeConfig = config
        if (app.cachedChannels.isNotEmpty()) return
        val result = withContext(Dispatchers.IO) {
            app.isapiClient.discoverChannels(config)
        }
        if (result is IsapiClient.Result.Ok) {
            app.cachedChannels = result.value
        }
    }

    private fun promote(cameraId: Int) {
        startActivity(
            Intent(this, LiveViewActivity::class.java)
                .putExtra(LiveViewActivity.EXTRA_CAMERA_ID, cameraId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    private inner class TileController(
        private val tile: ViewGridTileBinding,
        private val channel: ChannelInfo?,
    ) {
        var source: VideoSource? = null
            private set

        fun start() {
            val ch = channel ?: return
            val config = app.activeConfig ?: return
            tile.tileLabel.text = "${ch.videoInputChannelId} · ${ch.displayName}"
            release()
            val url = RtspUrlBuilder.build(
                host = config.host,
                port = config.rtspPort,
                username = config.username,
                password = config.password,
                streamingChannelId = RtspUrlBuilder.streamingChannelId(
                    ch.videoInputChannelId,
                    StreamType.SUB.code,
                ),
            )
            val vs = VideoSourceFactory.create(this@GridActivity)
            source = vs
            vs.setStateListener { state ->
                when (state) {
                    is PlayerState.Failed, is PlayerState.Reconnecting -> {
                        tile.tileError.visibility = View.VISIBLE
                    }
                    is PlayerState.Playing -> {
                        tile.tileError.visibility = View.GONE
                    }
                    else -> Unit
                }
            }
            vs.attachSurface(tile.tileSurface)
            vs.start(url)
            AppLog.d(TAG, "Tile start cam=${ch.videoInputChannelId}")
        }

        fun release() {
            source?.stop()
            source?.release()
            source = null
        }
    }

    companion object {
        private const val TAG = "GridActivity"
        private const val MAX_TILES = 4
        private const val OVERLAY_HIDE_MS = 4_000L
    }
}
