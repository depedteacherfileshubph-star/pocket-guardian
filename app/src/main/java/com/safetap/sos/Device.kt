package com.safetap.sos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.BatteryManager
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

object Perms {
    private fun has(c: Context, p: String) =
        ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun sms(c: Context) = has(c, Manifest.permission.SEND_SMS)
    fun call(c: Context) = has(c, Manifest.permission.CALL_PHONE)
    fun location(c: Context) =
        has(c, Manifest.permission.ACCESS_FINE_LOCATION) || has(c, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Needed so the screen-off shortcut's required notification is visible (Android 13+). */
    fun notifications(c: Context) =
        if (Build.VERSION.SDK_INT >= 33) has(c, Manifest.permission.POST_NOTIFICATIONS)
        else androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled()

    val NOTIFY: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    val SMS = arrayOf(Manifest.permission.SEND_SMS)
    val CALL = arrayOf(Manifest.permission.CALL_PHONE)
    val LOCATION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
}

/** Read-only checks of phone state. Nothing here is stored or sent anywhere. */
object DeviceState {
    fun airplaneMode(c: Context) =
        Settings.Global.getInt(c.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    fun hasTelephony(c: Context) =
        c.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    fun simReady(c: Context): Boolean {
        val tm = c.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
        return tm.simState == TelephonyManager.SIM_STATE_READY
    }

    fun locationServiceOn(c: Context): Boolean {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    /** @return battery percent, or -1 if unknown. */
    fun batteryPercent(c: Context): Int {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    fun charging(c: Context): Boolean {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val s = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }

    fun lowBattery(c: Context): Boolean {
        val p = batteryPercent(c)
        return p in 0..15 && !charging(c)
    }
}

enum class ReadyLevel { DISABLED, INCOMPLETE, READY, READY_WITH_WARNINGS }

data class Issue(val text: String, val blocking: Boolean)

data class Readiness(val level: ReadyLevel, val headline: String, val issues: List<Issue>)

/**
 * Honest status: only says "ready" when everything the chosen actions need is in place.
 * Note the volume-button shortcut itself only works while the app is open and in front;
 * the on-screen SOS button works whenever the app is open.
 */
fun evaluateReadiness(c: Context, cfg: SosConfig, contacts: List<Contact>): Readiness {
    if (!cfg.enabled) {
        return Readiness(ReadyLevel.DISABLED, "SOS is turned off", listOf(Issue("A guardian can turn it on in Guardian settings.", false)))
    }
    val issues = mutableListOf<Issue>()
    fun block(t: String) = issues.add(Issue(t, true))
    fun warn(t: String) = issues.add(Issue(t, false))

    if (!cfg.sendSms && !cfg.placeCall && !cfg.alarm) block("No SOS action is chosen.")
    if (!DeviceState.hasTelephony(c)) block("This device has no phone/SMS hardware, so it can't send SMS or call.")
    if (cfg.sendSms) {
        if (contacts.isEmpty()) block("Add at least one trusted contact.")
        else if (contacts.none { it.verified }) block("No trusted contact has been tested yet.")
        else if (contacts.any { !it.verified }) warn("Some contacts have not been tested.")
        if (!Perms.sms(c)) block("SMS permission is off, so messages can't be sent.")
    }
    if (cfg.placeCall) {
        val cc = contacts.firstOrNull { it.id == cfg.callContactId }
        if (cc == null) block("Choose who to call.")
        else if (!cc.verified) warn("The call contact has not been tested.")
        if (!Perms.call(c)) warn("Phone permission is off: the call screen will open but someone must tap Call.")
    }
    if ((cfg.sendSms) && cfg.includeLocation && !Perms.location(c)) warn("Location permission is off: messages will say location is unavailable.")
    if ((cfg.sendSms) && cfg.includeLocation && Perms.location(c) && !DeviceState.locationServiceOn(c)) warn("Phone location is switched off: messages may lack a location.")
    if (cfg.backgroundMode) {
        if (!BgState.running) warn("Screen-off shortcut is stopped. Open Pocket Guardian to restart it.")
        else if (!Store(c).bgVerified) warn("Screen-off shortcut has not passed a screen-off test yet.")
        if (!Perms.notifications(c)) warn("Notifications are off, so the screen-off shortcut and its SOS alert can't be shown.")
    }
    if (DeviceState.airplaneMode(c)) warn("Airplane mode is on: SMS and calls won't work.")
    else if ((cfg.sendSms || cfg.placeCall) && DeviceState.hasTelephony(c) && !DeviceState.simReady(c)) warn("SIM card is not ready: SMS and calls may fail.")
    if (DeviceState.lowBattery(c)) warn("Battery is low: please charge the phone.")

    return when {
        issues.any { it.blocking } -> Readiness(ReadyLevel.INCOMPLETE, "SOS is not fully set up", issues)
        issues.isNotEmpty() -> Readiness(ReadyLevel.READY_WITH_WARNINGS, "SOS is ready, with warnings", issues)
        else -> Readiness(ReadyLevel.READY, "SOS is ready", issues)
    }
}
