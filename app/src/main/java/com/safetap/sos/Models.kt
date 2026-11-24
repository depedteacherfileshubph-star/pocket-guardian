package com.safetap.sos

import android.view.KeyEvent

/** Which physical-button sequences the app supports. Power button is deliberately NOT here. */
enum class PatternType(val label: String, val help: String) {
    DOWN_ONLY("Volume Down", "Press the Volume Down button."),
    UP_ONLY("Volume Up", "Press the Volume Up button."),
    ALTERNATING("Up, Down, Up, Down...", "Alternate Volume Up and Volume Down, starting with Up.");

    /** The exact key order for a series of [count] presses. */
    fun sequence(count: Int): List<Int> = List(count) { i ->
        when (this) {
            DOWN_ONLY -> KeyEvent.KEYCODE_VOLUME_DOWN
            UP_ONLY -> KeyEvent.KEYCODE_VOLUME_UP
            ALTERNATING -> if (i % 2 == 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
        }
    }
}

/** What SOS can do. A set of these is stored as a bit mask. */
object Act {
    const val SMS = 1
    const val CALL = 2
    const val ALARM = 4

    /** The choices offered when tapping an action row; each tap moves to the next. */
    val CHOICES = listOf(0, SMS, CALL, SMS or CALL, ALARM, SMS or CALL or ALARM)

    fun label(mask: Int): String {
        if (mask == 0) return "Do nothing"
        val parts = buildList {
            if (mask and SMS != 0) add("Send SMS")
            if (mask and CALL != 0) add("Call")
            if (mask and ALARM != 0) add("Alarm")
        }
        return parts.joinToString(" + ")
    }

    fun next(mask: Int): Int {
        val i = CHOICES.indexOf(mask)
        return CHOICES[(i + 1) % CHOICES.size]
    }
}

data class SosConfig(
    val enabled: Boolean = false,
    val pattern: PatternType = PatternType.DOWN_ONLY,
    /** Press count -> actions (bit mask). E.g. {2: SMS, 3: CALL, 5: SMS+CALL}. */
    val mappings: Map<Int, Int> = mapOf(5 to Act.SMS),
    /** Time limit for one whole series of presses. */
    val windowMs: Long = 3000L,
    /** What the on-screen SOS button does. */
    val buttonActions: Int = Act.SMS,
    val callContactId: String? = null,
    /** Seconds the child can cancel before help is sent. 0 = send immediately. */
    val graceSeconds: Int = 5,
    val message: String = "I need help. I pressed SOS on my phone.",
    val includeLocation: Boolean = true,
    /** Keeps the screen on while the child screen is open so button presses are seen. */
    val keepScreenOn: Boolean = false,
    /** Optional screen-off shortcut (foreground service + visible notification). Off by default. */
    val backgroundMode: Boolean = false,
) {
    /** Counts that actually do something. */
    val activeCounts: Set<Int> get() = mappings.filterValues { it != 0 }.keys

    /** Every action this configuration can ever run (for permissions and readiness). */
    val usedActions: Int get() = mappings.values.fold(buttonActions) { a, b -> a or b }
}

data class Contact(
    val id: String,
    val name: String,
    val phone: String,
    /** Guardian confirmed a test message / test call reached this person. */
    val verified: Boolean = false,
)

object Limits {
    const val MIN_PRESSES = 2
    const val MAX_PRESSES = 8
    /** Pause after the last press that ends a series. */
    const val GAP_MS = 700L
    val WINDOWS_MS = listOf(2000L, 3000L, 4000L, 5000L)
    val GRACE_SECONDS = listOf(0, 3, 5, 10)
    const val MAX_CONTACTS = 5
    const val MAX_MESSAGE = 120
    /** Counts of this many presses or fewer are easy to trigger by accident. */
    const val RISKY_AT_OR_BELOW = 3
}

fun isPlausiblePhone(raw: String): Boolean {
    val digits = raw.count { it.isDigit() }
    return digits in 5..15 && raw.all { it.isDigit() || it in "+ -()" }
}
