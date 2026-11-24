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

/** App-private storage, excluded from backup. Holds only: settings, contacts, PIN hash, licence state. */
class Store(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("safetap", Context.MODE_PRIVATE)

    fun loadConfig(): SosConfig {
        val d = SosConfig()
        val pattern = runCatching { PatternType.valueOf(sp.getString("pattern", null) ?: "") }.getOrDefault(d.pattern)

        var mappings = parseMappings(sp.getString("mappings", null))
        var buttonActions = sp.getInt("buttonActions", -1)
        if (mappings == null) {
            // Settings saved by version 1.0.x: one press count with SMS / call / alarm switches.
            val old = (if (sp.getBoolean("sendSms", true)) Act.SMS else 0) or
                (if (sp.getBoolean("placeCall", false)) Act.CALL else 0) or
                (if (sp.getBoolean("alarm", false)) Act.ALARM else 0)
            val count = sp.getInt("pressCount", 5).coerceIn(Limits.MIN_PRESSES, Limits.MAX_PRESSES)
            mappings = mapOf(count to old)
            if (buttonActions < 0) buttonActions = if (old == 0) Act.SMS else old
        }
        if (buttonActions <= 0) buttonActions = d.buttonActions

        return SosConfig(
            enabled = sp.getBoolean("enabled", d.enabled),
            pattern = pattern,
            mappings = mappings,
            windowMs = sp.getLong("windowMs", d.windowMs),
            buttonActions = buttonActions,
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
            .putString("mappings", c.mappings.entries.joinToString(",") { "${it.key}:${it.value}" })
            .putLong("windowMs", c.windowMs).putInt("buttonActions", c.buttonActions)
            .putString("callContactId", c.callContactId)
            .putInt("graceSeconds", c.graceSeconds).putString("message", c.message)
            .putBoolean("includeLocation", c.includeLocation).putBoolean("keepScreenOn", c.keepScreenOn)
            .putBoolean("backgroundMode", c.backgroundMode)
            .apply()
    }

    private fun parseMappings(s: String?): Map<Int, Int>? {
        if (s == null) return null
        return s.split(",").mapNotNull {
            val p = it.split(":")
            val k = p.getOrNull(0)?.toIntOrNull()
            val v = p.getOrNull(1)?.toIntOrNull()
            if (k != null && v != null && k in Limits.MIN_PRESSES..Limits.MAX_PRESSES) k to v else null
        }.toMap()
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

    /** Opt-in: check for a newer version when Guardian settings opens. Off by default. */
    var autoUpdateCheck: Boolean
        get() = sp.getBoolean("autoUpdate", false)
        set(v) { sp.edit().putBoolean("autoUpdate", v).apply() }

    // ---- Licence / trial ----
    var trialStart: Long
        get() = sp.getLong("trialStart", 0L)
        set(v) { sp.edit().putLong("trialStart", v).apply() }

    /** Latest clock reading seen; stops the trial being extended by turning the clock back. */
    var lastSeen: Long
        get() = sp.getLong("lastSeen", 0L)
        set(v) { sp.edit().putLong("lastSeen", v).apply() }

    var licenseCode: String
        get() = sp.getString("licenseCode", "") ?: ""
        set(v) { sp.edit().putString("licenseCode", v).apply() }

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
