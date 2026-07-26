package com.app.truvend_cam.dvr

/**
 * Per-site DVR segmentation policy.
 *
 * Persisted locally today; when enrolment / config refresh lands, the box will
 * overwrite these from the site record (`segmentation_enabled`,
 * `segmentation_interval_hours`) without re-enrolling.
 */
data class SegmentationSettings(
    val enabled: Boolean = DEFAULT_ENABLED,
    val intervalHours: Int = DEFAULT_INTERVAL_HOURS,
) {
    /** Clamped interval used by the scheduler. */
    val intervalMs: Long
        get() = clampedIntervalHours.toLong() * MS_PER_HOUR

    val clampedIntervalHours: Int
        get() = intervalHours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)

    companion object {
        const val DEFAULT_ENABLED = true
        const val DEFAULT_INTERVAL_HOURS = 1
        const val MIN_INTERVAL_HOURS = 1
        /** Cap at 7 days — beyond that the point of segmentation is lost. */
        const val MAX_INTERVAL_HOURS = 24 * 7
        private const val MS_PER_HOUR = 3_600_000L
    }
}
