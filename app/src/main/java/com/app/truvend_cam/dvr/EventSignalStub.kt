package com.app.truvend_cam.dvr

/**
 * **Deferred — design only, do not implement.**
 *
 * Once periodic segmentation ships, an "event" is not a separate on-box recording
 * path. The detector (whenever built) should send a lightweight signal
 * (`device_id`, `channel`, `timestamp`, `event_type` — no video) to the server.
 * The server then uses the same dumb-playback pipeline as user-requested
 * playback: search DVR segments covering that timestamp, download, clip, upload.
 *
 * No on-box pre-roll buffer, no separate clip recorder, no separate upload path.
 */
@Suppress("unused")
object EventSignalStub {
    // Intentionally empty. Revisit when detection hardware/model and UI settle.
}
