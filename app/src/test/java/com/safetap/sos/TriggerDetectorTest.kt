package com.safetap.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// KeyEvent constants are compile-time ints (25 = VOLUME_DOWN, 24 = VOLUME_UP), so no Android runtime needed.
class TriggerDetectorTest {
    private val down = 25
    private val up = 24

    private fun detector(count: Int = 5, window: Long = 3000, p: PatternType = PatternType.DOWN_ONLY) =
        TriggerDetector(p.sequence(count), window)

    @Test fun fiveQuickDownPressesTrigger() {
        val d = detector()
        val results = (0 until 5).map { d.onKeyDown(down, it * 400L) }
        assertEquals(listOf(false, false, false, false, true), results)
    }

    @Test fun tooSlowDoesNotTrigger() {
        val d = detector()
        assertFalse((0 until 5).map { d.onKeyDown(down, it * 1000L) }.any { it }) // spans 4 s > 3 s
    }

    @Test fun fourPressesDoNotTrigger() {
        val d = detector()
        assertFalse((0 until 4).map { d.onKeyDown(down, it * 300L) }.any { it })
    }

    @Test fun wrongButtonDoesNotCount() {
        val d = detector()
        val times = listOf(0L, 300, 600, 900, 1200)
        val keys = listOf(down, down, up, down, down)
        assertFalse(keys.indices.map { d.onKeyDown(keys[it], times[it]) }.any { it })
    }

    @Test fun triggersAfterStrayPressesOutsideWindow() {
        val d = detector()
        d.onKeyDown(down, 0)
        d.onKeyDown(down, 100)
        val r = (0 until 5).map { d.onKeyDown(down, 10_000L + it * 300) }
        assertTrue(r.last())
    }

    @Test fun resetsAfterTriggerSoNoDoubleFire() {
        val d = detector(3)
        val first = (0 until 3).map { d.onKeyDown(down, it * 200L) }
        assertTrue(first.last())
        assertFalse(d.onKeyDown(down, 700)) // 1 of 3 again
        assertEquals(1, d.progress)
    }

    @Test fun alternatingPattern() {
        val d = detector(5, 3000, PatternType.ALTERNATING)
        val keys = listOf(up, down, up, down, up)
        val r = keys.indices.map { d.onKeyDown(keys[it], it * 300L) }
        assertTrue(r.last())
    }

    @Test fun alternatingRejectsRepeatedSameKey() {
        val d = detector(5, 3000, PatternType.ALTERNATING)
        assertFalse((0 until 5).map { d.onKeyDown(down, it * 300L) }.any { it })
    }

    @Test fun otherKeysIgnored() {
        val d = detector(3)
        assertFalse(d.onKeyDown(26 /* power */, 0))
        assertEquals(0, d.progress)
    }

    @Test fun progressReportsCount() {
        val d = detector()
        d.onKeyDown(down, 0); d.onKeyDown(down, 200)
        assertEquals(2, d.progress)
    }

    @Test fun phoneNumberValidation() {
        assertTrue(isPlausiblePhone("+44 7700 900123"))
        assertFalse(isPlausiblePhone("12"))
        assertFalse(isPlausiblePhone("call me"))
    }
}
