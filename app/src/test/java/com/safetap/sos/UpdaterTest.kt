package com.safetap.sos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun newerVersionsDetected() {
        assertTrue(Updater.isNewer("v1.0.1", "1.0.0"))
        assertTrue(Updater.isNewer("1.10.0", "1.9.9"))
        assertTrue(Updater.isNewer("v2.0", "1.9.9"))
    }

    @Test fun sameOrOlderIgnored() {
        assertFalse(Updater.isNewer("v1.0.0", "1.0.0"))
        assertFalse(Updater.isNewer("1.0", "1.0.0"))
        assertFalse(Updater.isNewer("v0.9.9", "1.0.0"))
        assertFalse(Updater.isNewer("garbage", "1.0.0"))
    }

    @Test fun detectorKeepsProgressWhenConfigUnchanged() {
        val seq = PatternType.DOWN_ONLY.sequence(3)
        val d = TriggerDetector(seq, 3000)
        d.onKeyDown(25, 0)
        d.configureIfChanged(seq, 3000)
        d.onKeyDown(25, 100)
        assertTrue(d.onKeyDown(25, 200))
    }
}
