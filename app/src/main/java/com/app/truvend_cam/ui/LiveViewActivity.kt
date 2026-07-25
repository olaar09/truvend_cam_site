package com.app.truvend_cam.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.app.truvend_cam.R
import com.app.truvend_cam.TruvendApp
import com.app.truvend_cam.data.ChannelInfo
import com.app.truvend_cam.data.StreamType
import com.app.truvend_cam.databinding.ActivityLiveViewBinding
import com.app.truvend_cam.databinding.DialogChannelPickerBinding
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
import kotlin.math.abs

class LiveViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveViewBinding
    private val app get() = application as TruvendApp
    private val handler = Handler(Looper.getMainLooper())

    private var videoSource: VideoSource? = null
    private var cameras: List<Int> = emptyList()
    private var cameraIndex = 0
    private var streamType: StreamType = StreamType.SUB
    private var overlayVisible = true
    private var pickerOpen = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val hideOverlayRunnable = Runnable { setOverlayVisible(false) }

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                bumpOverlay()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                if (abs(dx) > abs(e2.y - e1.y) && abs(dx) > 80) {
                    if (dx < 0) nextChannel() else prevChannel()
                    return true
                }
                return false
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        streamType = app.activeConfig?.defaultStreamType
            ?: app.configRepository.load()?.defaultStreamType
            ?: StreamType.SUB

        val startCamera = intent.getIntExtra(EXTRA_CAMERA_ID, -1)

        binding.btnRetry.setOnClickListener { playCurrent() }
        binding.btnHome.setOnClickListener { goHome() }
        binding.btnViewToggle.setOnClickListener {
            startActivity(Intent(this, GridActivity::class.java))
            finish()
        }

        binding.root.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        ensureChannelsThenStart(startCamera)
    }

    override fun onStart() {
        super.onStart()
        networkCallback = app.wifiBinder.registerNetworkRegain {
            AppLog.i(TAG, "Network regained — reconnecting")
            (videoSource as? VlcVideoSource)?.reconnectNow()
        }
        if (cameras.isNotEmpty()) {
            playCurrent()
        }
    }

    override fun onStop() {
        networkCallback?.let { app.wifiBinder.unregisterCallback(it) }
        networkCallback = null
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideOverlayRunnable)
        releasePlayer()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        bumpOverlay()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                prevChannel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                nextChannel()
                return true
            }
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> {
                if (!pickerOpen) openChannelPicker()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (pickerOpen) return true
            }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                val num = keyCode - KeyEvent.KEYCODE_0
                jumpToCamera(num)
                return true
            }
            KeyEvent.KEYCODE_NUMPAD_1, KeyEvent.KEYCODE_NUMPAD_2, KeyEvent.KEYCODE_NUMPAD_3,
            KeyEvent.KEYCODE_NUMPAD_4, KeyEvent.KEYCODE_NUMPAD_5, KeyEvent.KEYCODE_NUMPAD_6,
            KeyEvent.KEYCODE_NUMPAD_7, KeyEvent.KEYCODE_NUMPAD_8, KeyEvent.KEYCODE_NUMPAD_9,
            -> {
                val num = keyCode - KeyEvent.KEYCODE_NUMPAD_0
                jumpToCamera(num)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun ensureChannelsThenStart(startCamera: Int) {
        val config = app.activeConfig ?: app.configRepository.load()
        if (config == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        app.activeConfig = config

        lifecycleScope.launch {
            if (app.cachedChannels.isEmpty()) {
                binding.overlayStatus.text = getString(R.string.status_connecting)
                val result = withContext(Dispatchers.IO) {
                    app.isapiClient.discoverChannels(config)
                }
                when (result) {
                    is IsapiClient.Result.Ok -> app.cachedChannels = result.value
                    is IsapiClient.Result.Err -> {
                        showError(result.error.userMessage)
                        return@launch
                    }
                }
            }
            cameras = ChannelIndex.cameras(app.cachedChannels)
            if (cameras.isEmpty()) {
                showError("No channels discovered.")
                return@launch
            }
            cameraIndex = if (startCamera > 0) {
                cameras.indexOf(startCamera).takeIf { it >= 0 } ?: 0
            } else {
                0
            }
            buildChannelStrip()
            playCurrent()
            bumpOverlay()
        }
    }

    private fun playCurrent() {
        val config = app.activeConfig ?: return
        if (cameras.isEmpty()) return
        val cameraId = cameras[cameraIndex]
        val channel = ChannelIndex.find(app.cachedChannels, cameraId, streamType)
            ?: run {
                showError("Channel $cameraId not found.")
                return
            }

        // CRITICAL: fully release previous player before creating next.
        releasePlayer()

        val url = RtspUrlBuilder.build(
            host = config.host,
            port = config.rtspPort,
            username = config.username,
            password = config.password,
            streamingChannelId = channel.streamingChannelId,
        )

        binding.overlayChannel.text = getString(
            R.string.overlay_channel,
            channel.videoInputChannelId,
            channel.displayName,
            streamType.label,
        )
        binding.errorPanel.visibility = View.GONE

        val source = VideoSourceFactory.create(this)
        videoSource = source
        source.setStateListener { state -> onPlayerState(state) }
        source.attachSurface(binding.videoSurface)
        source.start(url)
        highlightStrip()
        AppLog.i(TAG, "Playing cam=$cameraId stream=${streamType.label} id=${channel.streamingChannelId}")
    }

    private fun releasePlayer() {
        videoSource?.stop()
        videoSource?.release()
        videoSource = null
    }

    private fun onPlayerState(state: PlayerState) {
        when (state) {
            is PlayerState.Connecting -> {
                binding.overlayStatus.text = getString(R.string.status_connecting)
                binding.errorPanel.visibility = View.GONE
                bumpOverlay()
            }
            is PlayerState.Reconnecting -> {
                binding.overlayStatus.text =
                    getString(R.string.status_reconnecting, state.attempt)
                binding.errorPanel.visibility = View.GONE
                setOverlayVisible(true)
            }
            is PlayerState.Playing -> {
                binding.overlayStatus.text = getString(R.string.status_playing)
                binding.errorPanel.visibility = View.GONE
            }
            is PlayerState.Failed -> {
                showError(state.message)
            }
            else -> Unit
        }
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorPanel.visibility = View.VISIBLE
        binding.overlayStatus.text = getString(R.string.status_failed)
        setOverlayVisible(true)
    }

    private fun nextChannel() {
        if (cameras.isEmpty()) return
        cameraIndex = (cameraIndex + 1) % cameras.size
        playCurrent()
        bumpOverlay()
    }

    private fun prevChannel() {
        if (cameras.isEmpty()) return
        cameraIndex = if (cameraIndex == 0) cameras.lastIndex else cameraIndex - 1
        playCurrent()
        bumpOverlay()
    }

    private fun jumpToCamera(number: Int) {
        val idx = cameras.indexOf(number)
        if (idx >= 0) {
            cameraIndex = idx
            playCurrent()
            bumpOverlay()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildChannelStrip() {
        val content = binding.channelStripContent
        content.removeAllViews()
        for ((i, cam) in cameras.withIndex()) {
            val chip = TextView(this).apply {
                text = " $cam "
                textSize = 18f
                setTextColor(getColor(R.color.text_primary))
                setBackgroundResource(R.drawable.bg_channel_chip)
                setPadding(28, 20, 28, 20)
                isFocusable = true
                isClickable = true
                minWidth = 72
                setOnClickListener {
                    cameraIndex = i
                    playCurrent()
                    bumpOverlay()
                }
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) bumpOverlay()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = 8 }
            content.addView(chip, lp)
        }
        highlightStrip()
    }

    private fun highlightStrip() {
        val content = binding.channelStripContent
        for (i in 0 until content.childCount) {
            content.getChildAt(i).isSelected = i == cameraIndex
        }
    }

    private fun bumpOverlay() {
        setOverlayVisible(true)
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_MS)
    }

    private fun goHome() {
        startActivity(
            Intent(this, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    private fun setOverlayVisible(visible: Boolean) {
        overlayVisible = visible
        val v = if (visible) View.VISIBLE else View.GONE
        // App bar stays; channel/status label + strip can fade.
        binding.overlayTop.visibility = v
        binding.channelStrip.visibility = v
    }

    private fun openChannelPicker() {
        pickerOpen = true
        val dialogBinding = DialogChannelPickerBinding.inflate(layoutInflater)
        if (streamType == StreamType.MAIN) {
            dialogBinding.pickerRadioMain.isChecked = true
        } else {
            dialogBinding.pickerRadioSub.isChecked = true
        }

        val list = ChannelIndex.displayChannels(app.cachedChannels, streamType)
        val adapter = ChannelListAdapter { channel ->
            val idx = cameras.indexOf(channel.videoInputChannelId)
            if (idx >= 0) cameraIndex = idx
            playCurrent()
            bumpOverlay()
        }
        dialogBinding.pickerList.layoutManager = LinearLayoutManager(this)
        dialogBinding.pickerList.adapter = adapter
        adapter.submit(list)

        fun refreshList() {
            streamType = if (dialogBinding.pickerRadioMain.isChecked) StreamType.MAIN else StreamType.SUB
            adapter.submit(ChannelIndex.displayChannels(app.cachedChannels, streamType))
        }
        dialogBinding.pickerStreamGroup.setOnCheckedChangeListener { _, _ ->
            refreshList()
            playCurrent()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setOnDismissListener { pickerOpen = false }
            .create()
        dialog.show()
        dialogBinding.pickerList.post {
            dialogBinding.pickerList.getChildAt(0)?.requestFocus()
        }
    }

    companion object {
        const val EXTRA_CAMERA_ID = "camera_id"
        private const val TAG = "LiveViewActivity"
        private const val OVERLAY_HIDE_MS = 4_000L
    }
}
