package com.safetap.sos

import android.view.KeyEvent

/**
 * Counts a series of presses and reports how many there were once the series is over.
 *
 * A series ends when (a) the highest mapped count is reached (fires at once, no waiting), or
 * (b) the user pauses for [gapMs] after the last press (call [poll]). Only counts in
 * [counts] are reported; anything else is ignored.
 *
 * Pure logic so it can be unit-tested. Feed it key-DOWN events with repeatCount == 0.
 */
class TriggerDetector(
    private var pattern: PatternType,
    private var counts: Set<Int>,
    private var windowMs: Long,
    val gapMs: Long = Limits.GAP_MS,
) {
    private val series = ArrayList<Pair<Int, Long>>()
    private val maxCount get() = counts.maxOrNull() ?: 0

    /** Number of correct presses so far in the current series (for UI feedback). */
    val progress: Int get() = series.size

    /** Time of the last press, or -1 if no series is in progress. */
    val lastPressAt: Long get() = series.lastOrNull()?.second ?: -1L

    fun configure(pattern: PatternType, counts: Set<Int>, windowMs: Long) {
        this.pattern = pattern
        this.counts = counts
        this.windowMs = windowMs
        reset()
    }

    fun configureIfChanged(pattern: PatternType, counts: Set<Int>, windowMs: Long) {
        if (pattern != this.pattern || counts != this.counts || windowMs != this.windowMs) {
            configure(pattern, counts, windowMs)
        }
    }

    fun reset() = series.clear()

    /** @return the press count if the series just reached the highest mapped count, else null. */
    fun onKeyDown(keyCode: Int, timeMs: Long): Int? {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) return null
        if (counts.isEmpty()) return null

        // A pause, or a series that has run too long, starts a new series.
        if (series.isNotEmpty() &&
            (timeMs - series.last().second > gapMs || timeMs - series.first().second > windowMs)
        ) series.clear()

        val expected = pattern.sequence(Limits.MAX_PRESSES)
        if (keyCode != expected[series.size]) {
            // Wrong key for this position: start over, counting this press as a possible first press.
            series.clear()
            if (keyCode != expected[0]) return null
        }
        series.add(keyCode to timeMs)

        if (series.size >= maxCount) {
            val n = series.size
            series.clear()
            return if (n in counts) n else null
        }
        return null
    }

    /** Call after [gapMs] of quiet. @return the press count of the finished series, if it is mapped. */
    fun poll(nowMs: Long): Int? {
        if (series.isEmpty() || nowMs - series.last().second < gapMs) return null
        val n = series.size
        series.clear()
        return if (n in counts) n else null
    }
}
