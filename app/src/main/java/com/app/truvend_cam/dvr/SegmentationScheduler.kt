package com.app.truvend_cam.dvr

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.app.truvend_cam.data.ChannelInfo
import com.app.truvend_cam.data.ConfigRepository
import com.app.truvend_cam.data.StreamType
import com.app.truvend_cam.network.IsapiClient
import com.app.truvend_cam.util.AppLog

/**
 * Forces the DVR to close/reopen recording on a timer so search never returns
 * multi-day unbounded segments.
 *
 * Lives outside the TCP forwarder. Hosted by [com.app.truvend_cam.service.ForwarderService]
 * only for wake-lock / boot lifetime — all logic stays in this package.
 *
 * Does **not** report boundaries to the server; the server discovers segments via
 * ContentMgmt/search at playback time.
 */
class SegmentationScheduler(
    private val configRepository: ConfigRepository,
    private val recordControl: RecordControlClient,
    private val siteConfigSource: SiteConfigSource,
    private val trackIdsProvider: () -> List<Int>,
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var running = false

    @Volatile
    var lastCycleAtElapsed: Long = 0L
        private set

    @Volatile
    var lastCycleSummary: String? = null
        private set

    private val tick = Runnable { runCycleAndReschedule() }

    fun start() {
        if (running) {
            AppLog.i(TAG, "Already running")
            return
        }
        val ht = HandlerThread(THREAD_NAME).also { it.start() }
        thread = ht
        handler = Handler(ht.looper)
        running = true
        val settings = configRepository.loadSegmentationSettings()
        AppLog.i(
            TAG,
            "Started enabled=${settings.enabled} intervalHours=${settings.clampedIntervalHours}",
        )
        scheduleNext(settings.intervalMs)
    }

    fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
        AppLog.i(TAG, "Stopped")
    }

    fun isRunning(): Boolean = running

    /** Test / diagnostics: fire one cycle ASAP on the worker thread. */
    fun runCycleNow() {
        val h = handler ?: return
        h.removeCallbacks(tick)
        h.post { runCycleAndReschedule() }
    }

    private fun scheduleNext(delayMs: Long) {
        val h = handler ?: return
        h.removeCallbacks(tick)
        val clamped = delayMs.coerceAtLeast(MIN_RESCHEDULE_MS)
        AppLog.d(TAG, "Next cycle in ${clamped}ms")
        h.postDelayed(tick, clamped)
    }

    private fun runCycleAndReschedule() {
        if (!running) return

        applyRemoteConfigIfAny()
        val settings = configRepository.loadSegmentationSettings()
        if (!settings.enabled) {
            AppLog.i(TAG, "Disabled — skipping cycle")
            scheduleNext(settings.intervalMs)
            return
        }

        val config = configRepository.load()
        if (config == null || !config.isComplete()) {
            AppLog.w(TAG, "No DVR config — skipping cycle")
            scheduleNext(settings.intervalMs)
            return
        }

        val tracks = trackIdsProvider().ifEmpty { listOf(DEFAULT_MAIN_TRACK) }
        AppLog.i(TAG, "Cycle starting tracks=$tracks")

        var successes = 0
        var failures = 0
        for (trackId in tracks) {
            if (!running) break
            when (segmentWithRetries(config, trackId)) {
                true -> successes++
                false -> failures++
            }
        }

        lastCycleAtElapsed = SystemClock.elapsedRealtime()
        lastCycleSummary = "ok=$successes fail=$failures tracks=${tracks.size}"
        AppLog.i(TAG, "Cycle done $lastCycleSummary")

        // Reload settings in case the interval was edited mid-cycle.
        val next = configRepository.loadSegmentationSettings()
        scheduleNext(next.intervalMs)
    }

    private fun applyRemoteConfigIfAny() {
        val snapshot = try {
            siteConfigSource.refresh()
        } catch (e: Exception) {
            AppLog.w(TAG, "Site config refresh failed: ${e.message}")
            null
        } ?: return

        val applied = SegmentationSettings(
            enabled = snapshot.segmentationEnabled,
            intervalHours = snapshot.segmentationIntervalHours,
        )
        configRepository.saveSegmentationSettings(applied)
        AppLog.i(
            TAG,
            "Applied remote config enabled=${applied.enabled} " +
                "intervalHours=${applied.clampedIntervalHours}",
        )
    }

    /**
     * Retry stop→start up to [MAX_ATTEMPTS]. A miss just means one longer segment.
     */
    private fun segmentWithRetries(
        config: com.app.truvend_cam.data.DvrConfig,
        trackId: Int,
    ): Boolean {
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            when (val result = recordControl.segmentTrack(config, trackId)) {
                is IsapiClient.Result.Ok -> {
                    AppLog.i(
                        TAG,
                        "Track $trackId ok attempt=$attempt elapsedMs=${result.value}",
                    )
                    return true
                }
                is IsapiClient.Result.Err -> {
                    AppLog.w(
                        TAG,
                        "Track $trackId failed attempt=$attempt/${MAX_ATTEMPTS}: " +
                            result.error.userMessage,
                    )
                    if (attempt < MAX_ATTEMPTS) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return false
                        }
                    }
                }
            }
        }
        AppLog.e(TAG, "Track $trackId giving up until next cycle after $MAX_ATTEMPTS attempts")
        return false
    }

    companion object {
        /** On-device log tag — keep opaque (UI codename). */
        private const val TAG = "Keeper"
        private const val THREAD_NAME = "keeper"
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2_000L
        private const val MIN_RESCHEDULE_MS = 60_000L
        private const val DEFAULT_MAIN_TRACK = 101

        /**
         * Main-stream track IDs (101, 201, …). Prefers discovered MAIN channels;
         * otherwise derives from video-input ids; last resort is [DEFAULT_MAIN_TRACK].
         */
        fun mainTrackIds(channels: List<ChannelInfo>): List<Int> {
            val mains = channels
                .filter { it.streamType == StreamType.MAIN }
                .map { it.streamingChannelId }
                .distinct()
                .sorted()
            if (mains.isNotEmpty()) return mains

            val derived = channels
                .map { it.videoInputChannelId }
                .filter { it > 0 }
                .distinct()
                .map { it * 100 + StreamType.MAIN.code }
                .sorted()
            if (derived.isNotEmpty()) return derived

            return listOf(DEFAULT_MAIN_TRACK)
        }
    }
}
