package com.safetap.sos

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

sealed interface PinResult {
    data object Ok : PinResult
    data object Wrong : PinResult
    data class Locked(val remainingMs: Long) : PinResult
}

/** App-private storage, excluded from backup. Holds only: settings, contacts, PIN hash. */
class Store(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("safetap", Context.MODE_PRIVATE)

    fun loadConfig(): SosConfig {
        val d = SosConfig()
        return SosConfig(
            enabled = sp.getBoolean("enabled", d.enabled),
            pattern = runCatching { PatternType.valueOf(sp.getString("pattern", null) ?: "") }.getOrDefault(d.pattern),
            pressCount = sp.getInt("pressCount", d.pressCount).coerceIn(Limits.MIN_PRESSES, Limits.MAX_PRESSES),
            windowMs = sp.getLong("windowMs", d.windowMs),
            sendSms = sp.getBoolean("sendSms", d.sendSms),
            placeCall = sp.getBoolean("placeCall", d.placeCall),
            alarm = sp.getBoolean("alarm", d.alarm),
            callContactId = sp.getString("callContactId", null),
            graceSeconds = sp.getInt("graceSeconds", d.graceSeconds),
            message = sp.getString("message", d.message) ?: d.message,
            includeLocation = sp.getBoolean("includeLocation", d.includeLocation),
            keepScreenOn = sp.getBoolean("keepScreenOn", d.keepScreenOn),
            backgroundMode = sp.getBoolean("backgroundMode", d.backgroundMode),
        )
    }

    fun saveConfig(c: SosConfig) {
        sp.edit()
            .putBoolean("enabled", c.enabled).putString("pattern", c.pattern.name)
            .putInt("pressCount", c.pressCount).putLong("windowMs", c.windowMs)
            .putBoolean("sendSms", c.sendSms).putBoolean("placeCall", c.placeCall)
            .putBoolean("alarm", c.alarm).putString("callContactId", c.callContactId)
            .putInt("graceSeconds", c.graceSeconds).putString("message", c.message)
            .putBoolean("includeLocation", c.includeLocation).putBoolean("keepScreenOn", c.keepScreenOn)
            .putBoolean("backgroundMode", c.backgroundMode)
            .apply()
    }

    fun loadContacts(): List<Contact> {
        val arr = JSONArray(sp.getString("contacts", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Contact(o.getString("id"), o.getString("name"), o.getString("phone"), o.optBoolean("verified"))
        }
    }

    fun saveContacts(list: List<Contact>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phone).put("verified", it.verified))
        }
        sp.edit().putString("contacts", arr.toString()).apply()
    }

    /** True only after the guardian passed a real screen-off test of the background shortcut. */
    var bgVerified: Boolean
        get() = sp.getBoolean("bgVerified", false)
        set(v) { sp.edit().putBoolean("bgVerified", v).apply() }

    /** Opt-in: check GitHub for a newer release when Guardian settings opens. Off by default. */
    var autoUpdateCheck: Boolean
        get() = sp.getBoolean("autoUpdate", false)
        set(v) { sp.edit().putBoolean("autoUpdate", v).apply() }

    // ---- Guardian PIN (salted PBKDF2; never stored in clear) ----
    fun hasPin() = sp.contains("pinHash")

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit().putString("pinSalt", b64(salt)).putString("pinHash", b64(hash(pin, salt)))
            .putInt("pinFails", 0).putLong("pinLockUntil", 0).apply()
    }

    fun lockoutRemainingMs(now: Long = System.currentTimeMillis()): Long =
        (sp.getLong("pinLockUntil", 0) - now).coerceAtLeast(0)

    fun verifyPin(pin: String, now: Long = System.currentTimeMillis()): PinResult {
        val remaining = lockoutRemainingMs(now)
        if (remaining > 0) return PinResult.Locked(remaining)
        val salt = Base64.decode(sp.getString("pinSalt", "") ?: "", Base64.NO_WRAP)
        val expected = Base64.decode(sp.getString("pinHash", "") ?: "", Base64.NO_WRAP)
        if (MessageDigest.isEqual(expected, hash(pin, salt))) {
            sp.edit().putInt("pinFails", 0).apply()
            return PinResult.Ok
        }
        val fails = sp.getInt("pinFails", 0) + 1
        val edit = sp.edit().putInt("pinFails", fails)
        if (fails >= 5) {
            // 30 s, doubling for each further failure, capped at 10 minutes.
            val delay = (30_000L shl (fails - 5).coerceAtMost(5)).coerceAtMost(600_000L)
            edit.putLong("pinLockUntil", now + delay)
            edit.apply()
            return PinResult.Locked(delay)
        }
        edit.apply()
        return PinResult.Wrong
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}
