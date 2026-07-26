package com.app.truvend_cam.dvr

/**
 * Remote site config the box will eventually pull on enrolment / periodic refresh.
 * Only segmentation fields are modelled here for now.
 */
data class SiteConfigSnapshot(
    val segmentationEnabled: Boolean,
    val segmentationIntervalHours: Int,
)
