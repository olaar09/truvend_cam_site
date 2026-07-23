package com.app.truvend_cam.player

import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {

    @Test
    fun backoff_growsAndCapsNear15s() {
        val d1 = VlcVideoSource.backoffMs(1)
        val d2 = VlcVideoSource.backoffMs(2)
        val d5 = VlcVideoSource.backoffMs(5)
        val d10 = VlcVideoSource.backoffMs(10)

        assertTrue("attempt1=$d1", d1 in 1000L..1500L)
        assertTrue("attempt2=$d2", d2 in 2000L..3000L)
        assertTrue("attempt5=$d5", d5 in 15000L..19000L)
        assertTrue("attempt10=$d10", d10 in 15000L..19000L)
    }
}
