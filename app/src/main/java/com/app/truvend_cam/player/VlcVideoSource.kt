package com.app.truvend_cam.player

import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import com.app.truvend_cam.network.RtspUrlBuilder
import com.app.truvend_cam.util.AppLog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

/**
 * LibVLC-backed [VideoSource].
 *
 * Critical: always [release] the MediaPlayer before creating the next one on channel switch.
 * Leaking players exhausts HW decoder sessions on cheap SoCs.
 *
 * Includes exponential-backoff reconnect and a stall watchdog (no new frames for 10s → reconnect).
 */
class VlcVideoSource(
    private val libVlc: LibVLC,
) : VideoSource {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var currentUrl: String? = null
    private var stateListener: ((PlayerState) -> Unit)? = null

    private val released = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)
    private val lastFrameAt = AtomicLong(0L)
    private val watching = AtomicBoolean(false)

    private val stallCheckRunnable = object : Runnable {
        override fun run() {
            if (!watching.get() || released.get()) return
            val last = lastFrameAt.get()
            if (last > 0 && System.currentTimeMillis() - last > STALL_TIMEOUT_MS) {
                AppLog.w(TAG, "Stall watchdog: no frames for ${STALL_TIMEOUT_MS}ms — reconnecting")
                scheduleReconnect(fromStall = true)
                return
            }
            mainHandler.postDelayed(this, STALL_POLL_MS)
        }
    }

    private val reconnectRunnable = Runnable {
        if (released.get()) return@Runnable
        val url = currentUrl ?: return@Runnable
        AppLog.i(TAG, "Reconnect attempt ${reconnectAttempt.get()}")
        emit(PlayerState.Reconnecting(reconnectAttempt.get()))
        startInternal(url, isReconnect = true)
    }

    override fun attachSurface(surfaceView: SurfaceView) {
        this.surfaceView = surfaceView
        mediaPlayer?.let { bindVout(it, surfaceView) }
    }

    override fun start(url: String) {
        if (released.get()) return
        currentUrl = url
        reconnectAttempt.set(0)
        emit(PlayerState.Connecting)
        startInternal(url, isReconnect = false)
    }

    override fun stop() {
        watching.set(false)
        mainHandler.removeCallbacks(stallCheckRunnable)
        mainHandler.removeCallbacks(reconnectRunnable)
        tearDownPlayer()
        emit(PlayerState.Stopped)
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        watching.set(false)
        mainHandler.removeCallbacks(stallCheckRunnable)
        mainHandler.removeCallbacks(reconnectRunnable)
        tearDownPlayer()
        stateListener = null
        surfaceView = null
        currentUrl = null
        emit(PlayerState.Idle)
    }

    override fun setStateListener(listener: ((PlayerState) -> Unit)?) {
        stateListener = listener
    }

    /** Force an immediate reconnect (e.g. network regain). */
    fun reconnectNow() {
        if (released.get()) return
        val url = currentUrl ?: return
        reconnectAttempt.set(0)
        emit(PlayerState.Connecting)
        startInternal(url, isReconnect = true)
    }

    private fun startInternal(url: String, isReconnect: Boolean) {
        tearDownPlayer()
        if (released.get()) return

        val player = MediaPlayer(libVlc)
        mediaPlayer = player

        surfaceView?.let { bindVout(player, it) }

        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> {
                    if (!isReconnect) emit(PlayerState.Connecting)
                }
                MediaPlayer.Event.Playing -> {
                    reconnectAttempt.set(0)
                    lastFrameAt.set(System.currentTimeMillis())
                    emit(PlayerState.Playing)
                    startWatchdog()
                }
                MediaPlayer.Event.Vout -> {
                    // New video output / frame activity
                    lastFrameAt.set(System.currentTimeMillis())
                }
                MediaPlayer.Event.EncounteredError -> {
                    AppLog.e(TAG, "MediaPlayer EncounteredError url=${RtspUrlBuilder.redact(url)}")
                    scheduleReconnect(fromStall = false)
                }
                MediaPlayer.Event.EndReached -> {
                    AppLog.w(TAG, "MediaPlayer EndReached — treating as disconnect")
                    scheduleReconnect(fromStall = false)
                }
                MediaPlayer.Event.Stopped -> {
                    // ignore during intentional tearDown
                }
            }
        }

        val media = Media(libVlc, android.net.Uri.parse(url))
        media.addOption(":rtsp-tcp")
        media.addOption(":network-caching=300")
        media.setHWDecoderEnabled(true, false)
        player.media = media
        media.release()
        player.play()
        AppLog.d(TAG, "play ${RtspUrlBuilder.redact(url)}")
    }

    private fun bindVout(player: MediaPlayer, surface: SurfaceView) {
        val vout = player.vlcVout
        vout.setVideoView(surface)
        vout.attachViews()
        surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            try {
                player.vlcVout.setWindowSize(surface.width, surface.height)
            } catch (_: Exception) {
            }
        }
        if (surface.width > 0 && surface.height > 0) {
            try {
                player.vlcVout.setWindowSize(surface.width, surface.height)
            } catch (_: Exception) {
            }
        }
    }

    private fun tearDownPlayer() {
        watching.set(false)
        mainHandler.removeCallbacks(stallCheckRunnable)
        val player = mediaPlayer ?: return
        mediaPlayer = null
        try {
            player.setEventListener(null)
        } catch (_: Exception) {
        }
        try {
            player.stop()
        } catch (_: Exception) {
        }
        try {
            player.vlcVout.detachViews()
        } catch (_: Exception) {
        }
        try {
            player.media?.release()
        } catch (_: Exception) {
        }
        try {
            // Fully release — do not reuse. Required to free HW decoder sessions.
            player.release()
        } catch (e: Exception) {
            AppLog.e(TAG, "Error releasing MediaPlayer", e)
        }
    }

    private fun scheduleReconnect(fromStall: Boolean) {
        if (released.get()) return
        watching.set(false)
        mainHandler.removeCallbacks(stallCheckRunnable)
        mainHandler.removeCallbacks(reconnectRunnable)
        tearDownPlayer()

        val attempt = reconnectAttempt.incrementAndGet()
        val delay = backoffMs(attempt)
        AppLog.i(
            TAG,
            "Scheduling reconnect attempt=$attempt delayMs=$delay stall=$fromStall",
        )
        emit(PlayerState.Reconnecting(attempt))
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun startWatchdog() {
        watching.set(true)
        mainHandler.removeCallbacks(stallCheckRunnable)
        mainHandler.postDelayed(stallCheckRunnable, STALL_POLL_MS)
    }

    private fun emit(state: PlayerState) {
        mainHandler.post {
            stateListener?.invoke(state)
        }
    }

    companion object {
        private const val TAG = "VlcVideoSource"
        private const val STALL_TIMEOUT_MS = 10_000L
        private const val STALL_POLL_MS = 2_000L

        /** Exponential backoff: 1s, 2s, 4s, 8s, capped at 15s, with jitter. */
        fun backoffMs(attempt: Int): Long {
            val base = min(15_000L, (1L shl (attempt - 1).coerceAtMost(4)) * 1000L)
            val jitter = Random.nextLong(0, (base / 4).coerceAtLeast(1))
            return base + jitter
        }
    }
}
