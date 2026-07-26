package com.app.truvend_cam.dvr

import com.app.truvend_cam.data.DvrConfig
import com.app.truvend_cam.network.ConnectionError
import com.app.truvend_cam.network.IsapiClient
import com.app.truvend_cam.util.AppLog

/**
 * Thin wrapper around ISAPI manual record control.
 *
 * Isolated from the TCP forwarder — talks to the DVR on the LAN only.
 */
class RecordControlClient(
    private val isapi: IsapiClient,
) {

    /**
     * PUT …/manual/stop/tracks/{trackId}
     * then (after [interCommandDelayMs])
     * PUT …/manual/start/tracks/{trackId}
     *
     * Returns elapsed wall time from stop request start to start response end
     * (useful for gap measurement; true footage hole still needs ContentMgmt/search).
     */
    fun segmentTrack(
        config: DvrConfig,
        trackId: Int,
        interCommandDelayMs: Long = DEFAULT_INTER_COMMAND_DELAY_MS,
    ): IsapiClient.Result<Long> {
        val t0 = System.currentTimeMillis()
        when (val stop = stopTrack(config, trackId)) {
            is IsapiClient.Result.Err -> return stop
            is IsapiClient.Result.Ok -> Unit
        }
        if (interCommandDelayMs > 0L) {
            try {
                Thread.sleep(interCommandDelayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return IsapiClient.Result.Err(
                    ConnectionError.Generic("Interrupted during segment delay"),
                )
            }
        }
        when (val start = startTrack(config, trackId)) {
            is IsapiClient.Result.Err -> return start
            is IsapiClient.Result.Ok -> Unit
        }
        val elapsed = System.currentTimeMillis() - t0
        AppLog.i(TAG, "Track $trackId cycle completed in ${elapsed}ms")
        return IsapiClient.Result.Ok(elapsed)
    }

    fun stopTrack(config: DvrConfig, trackId: Int): IsapiClient.Result<Unit> =
        isapi.putRecordControl(config, "/ISAPI/ContentMgmt/record/control/manual/stop/tracks/$trackId")

    fun startTrack(config: DvrConfig, trackId: Int): IsapiClient.Result<Unit> =
        isapi.putRecordControl(config, "/ISAPI/ContentMgmt/record/control/manual/start/tracks/$trackId")

    /**
     * Probe for a native "checkpoint" / split capability that avoids a stop gap.
     * Call on-device before shipping if measured stop→start gap exceeds ~10s.
     */
    fun fetchManualCapabilities(config: DvrConfig): IsapiClient.Result<String> =
        isapi.getRecordControlCapabilities(config)

    companion object {
        private const val TAG = "Keeper"
        /** Short fixed pause between stop and start — no human delay. */
        const val DEFAULT_INTER_COMMAND_DELAY_MS = 500L
    }
}
