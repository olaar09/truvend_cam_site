package com.app.truvend_cam.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogRedactTest {

    @Test
    fun redact_stripsUrlPassword() {
        val out = AppLog.redact("opening rtsp://admin:hunter2@192.168.1.1:554/x")
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("***"))
    }

    @Test
    fun redact_stripsPasswordKey() {
        val out = AppLog.redact("password=hunter2 saved")
        assertFalse(out.contains("hunter2"))
    }
}
