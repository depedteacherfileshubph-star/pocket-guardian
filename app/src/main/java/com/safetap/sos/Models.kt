package com.safetap.sos

import android.view.KeyEvent

/** Which physical-button sequences the app supports. Power button is deliberately NOT here. */
enum class PatternType(val label: String, val help: String) {
    DOWN_ONLY("Volume Down only", "Press Volume Down several times quickly."),
    UP_ONLY("Volume Up only", "Press Volume Up several times quickly."),
    ALTERNATING("Up, Down, Up, Down...", "Alternate Volume Up and Volume Down, starting with Up.");

    fun sequence(count: Int): List<Int> = List(count) { i ->
        when (this) {
            DOWN_ONLY -> KeyEvent.KEYCODE_VOLUME_DOWN
            UP_ONLY -> KeyEvent.KEYCODE_VOLUME_UP
            ALTERNATING -> if (i % 2 == 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
        }
    }
}

data class SosConfig(
    val enabled: Boolean = false,
    val pattern: PatternType = PatternType.DOWN_ONLY,
    val pressCount: Int = 5,
    val windowMs: Long = 3000L,
    val sendSms: Boolean = true,
    val placeCall: Boolean = false,
    val alarm: Boolean = false,
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
    val sequence: List<Int> get() = pattern.sequence(pressCount)
}

data class Contact(
    val id: String,
    val name: String,
    val phone: String,
    /** Guardian confirmed a test message / test call reached this person. */
    val verified: Boolean = false,
)

object Limits {
    const val MIN_PRESSES = 3
    const val MAX_PRESSES = 10
    val WINDOWS_MS = listOf(2000L, 3000L, 4000L, 5000L)
    val GRACE_SECONDS = listOf(0, 3, 5, 10)
    const val MAX_CONTACTS = 5
    const val MAX_MESSAGE = 120
}

fun isPlausiblePhone(raw: String): Boolean {
    val digits = raw.count { it.isDigit() }
    return digits in 5..15 && raw.all { it.isDigit() || it in "+ -()" }
}
