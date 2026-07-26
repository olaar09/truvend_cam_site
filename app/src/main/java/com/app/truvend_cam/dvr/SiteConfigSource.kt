package com.app.truvend_cam.dvr

/**
 * Applies per-site settings from the backend onto the box.
 *
 * Super admin already edits `segmentation_*` in the DB (tru-view). This source
 * is intentionally a no-op for now — **do not add HTTP polling**.
 *
 * TODO(mqtt): when MQTT lands, publish site config on save and have the box
 * subscribe (`sites/{device_id}/config`), then return a [SiteConfigSnapshot]
 * (or write prefs directly). See docs/09-dvr-segmentation.md § "Reminder — MQTT".
 */
fun interface SiteConfigSource {
    /** Null means "no remote update" (keep local prefs). */
    fun refresh(): SiteConfigSnapshot?
}

/** Local prefs only until MQTT (or another push path) is wired. */
class LocalOnlySiteConfigSource : SiteConfigSource {
    override fun refresh(): SiteConfigSnapshot? = null
}
