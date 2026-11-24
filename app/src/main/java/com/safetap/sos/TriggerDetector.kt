package com.safetap.sos

import android.view.KeyEvent

/**
 * Pure logic so it can be unit-tested. Feed it ACTION_DOWN events with repeatCount == 0
 * (a held key must not count as several presses).
 */
class TriggerDetector(private var sequence: List<Int>, private var windowMs: Long) {
    private val events = ArrayDeque<Pair<Int, Long>>()

    /** How many correct presses are currently inside the time window (for UI feedback). */
    var progress: Int = 0
        private set

    fun configure(sequence: List<Int>, windowMs: Long) {
        this.sequence = sequence
        this.windowMs = windowMs
        reset()
    }

    /** Re-applies settings only if they differ, so in-progress presses are not lost. */
    fun configureIfChanged(sequence: List<Int>, windowMs: Long) {
        if (sequence != this.sequence || windowMs != this.windowMs) configure(sequence, windowMs)
    }

    fun reset() {
        events.clear()
        progress = 0
    }

    /** @return true exactly when the configured sequence has just been completed in the window. */
    fun onKeyDown(keyCode: Int, timeMs: Long): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
        if (sequence.isEmpty()) return false
        events.addLast(keyCode to timeMs)
        // The whole sequence must fit inside the window, measured from its first press.
        while (events.isNotEmpty() && timeMs - events.first().second > windowMs) events.removeFirst()
        while (events.size > sequence.size) events.removeFirst()

        val keys = events.map { it.first }
        progress = if (keys == sequence.take(keys.size)) keys.size else 0
        if (keys == sequence) {
            reset()
            return true
        }
        return false
    }
}
