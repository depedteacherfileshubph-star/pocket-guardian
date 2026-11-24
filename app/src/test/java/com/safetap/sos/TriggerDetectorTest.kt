package com.safetap.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// KeyEvent constants are compile-time ints (25 = VOLUME_DOWN, 24 = VOLUME_UP), so no Android runtime needed.
class TriggerDetectorTest {
    private val down = 25
    private val up = 24

    private fun detector(counts: Set<Int>, pattern: PatternType = PatternType.DOWN_ONLY, window: Long = 3000) =
        TriggerDetector(pattern, counts, window, gapMs = 700)

    /** Presses at the given times; returns the immediate result of the last press. */
    private fun press(d: TriggerDetector, times: List<Long>, key: Int = down): Int? {
        var r: Int? = null
        times.forEach { r = d.onKeyDown(key, it) }
        return r
    }

    @Test fun highestCountFiresImmediately() {
        val d = detector(setOf(5))
        assertEquals(5, press(d, listOf(0, 300, 600, 900, 1200)))
    }

    @Test fun lowerCountFiresAfterPause() {
        val d = detector(setOf(2, 3, 5))
        assertNull(press(d, listOf(0, 300)))
        assertNull(d.poll(500))          // still inside the pause
        assertEquals(2, d.poll(1100))    // pause over -> 2 presses
    }

    @Test fun threePressesDistinctFromTwo() {
        val d = detector(setOf(2, 3, 5))
        press(d, listOf(0, 300, 600))
        assertEquals(3, d.poll(1400))
    }

    @Test fun unmappedCountIgnored() {
        val d = detector(setOf(2, 5))
        press(d, listOf(0, 300, 600, 900)) // 4 presses, not mapped
        assertNull(d.poll(2000))
    }

    @Test fun longPauseStartsNewSeries() {
        val d = detector(setOf(2, 3))
        press(d, listOf(0, 300))
        assertEquals(2, d.poll(1100))
        // 3 is the highest mapped count, so it fires on the 3rd press without waiting.
        assertEquals(3, press(d, listOf(5000, 5300, 5600)))
    }

    @Test fun seriesTooLongStartsOver() {
        val d = detector(setOf(5), window = 2000)
        // 5 presses spread over 2.4 s with short gaps: exceeds the 2 s window
        assertNull(press(d, listOf(0, 600, 1200, 1800, 2400)))
    }

    @Test fun wrongKeyBreaksDownSeries() {
        val d = detector(setOf(3))
        d.onKeyDown(down, 0); d.onKeyDown(down, 200)
        assertNull(d.onKeyDown(up, 400))
        assertEquals(0, d.progress)
    }

    @Test fun alternatingPattern() {
        val d = detector(setOf(4), PatternType.ALTERNATING)
        val keys = listOf(up, down, up, down)
        var r: Int? = null
        keys.forEachIndexed { i, k -> r = d.onKeyDown(k, i * 300L) }
        assertEquals(4, r)
    }

    @Test fun alternatingRejectsSameKey() {
        val d = detector(setOf(4), PatternType.ALTERNATING)
        assertNull(press(d, listOf(0, 300, 600, 900), down))
    }

    @Test fun otherKeysIgnored() {
        val d = detector(setOf(3))
        assertNull(d.onKeyDown(26 /* power */, 0))
        assertEquals(0, d.progress)
    }

    @Test fun noMappedCountsMeansNoTrigger() {
        val d = detector(emptySet())
        assertNull(press(d, listOf(0, 200, 400)))
        assertNull(d.poll(2000))
    }

    @Test fun progressReportsCount() {
        val d = detector(setOf(5))
        press(d, listOf(0, 200))
        assertEquals(2, d.progress)
    }

    @Test fun actionLabelsAndCycle() {
        assertEquals("Send SMS + Call", Act.label(Act.SMS or Act.CALL))
        assertEquals("Do nothing", Act.label(0))
        assertEquals(Act.SMS, Act.next(0))
        assertEquals(0, Act.next(Act.SMS or Act.CALL or Act.ALARM))
    }

    @Test fun usedActionsCoversButtonAndMappings() {
        val cfg = SosConfig(mappings = mapOf(2 to Act.SMS, 3 to Act.CALL), buttonActions = Act.ALARM)
        assertEquals(Act.SMS or Act.CALL or Act.ALARM, cfg.usedActions)
    }

    @Test fun phoneNumberValidation() {
        org.junit.Assert.assertTrue(isPlausiblePhone("+44 7700 900123"))
        org.junit.Assert.assertFalse(isPlausiblePhone("12"))
        org.junit.Assert.assertFalse(isPlausiblePhone("call me"))
    }
}
