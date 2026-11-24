package com.safetap.sos

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

enum class LogLevel { INFO, OK, WARN, ERROR }

data class SmsOutcome(val ok: Boolean, val reason: String)

sealed interface Fix {
    data class Current(val lat: Double, val lon: Double, val accuracyM: Float) : Fix
    data class LastKnown(val lat: Double, val lon: Double, val ageMin: Long) : Fix
    data class Unavailable(val reason: String) : Fix
}

/** What the SOS run achieved; used to choose fallback guidance. */
data class RunResult(val smsSent: Int, val smsFailed: Int, val callStarted: Boolean, val alarmOn: Boolean) {
    val anyHelpSent get() = smsSent > 0 || callStarted
}

/**
 * Executes the SOS actions. Location is fetched ONCE, here, when SOS runs. There is no
 * background location tracking anywhere in the app. Must be created with an Activity context
 * (SosActivity) so that location access is allowed (app is visible) and the call UI can start.
 */
class SosEngine(private val ctx: Context, private val log: (LogLevel, String) -> Unit) {

    private val alarm = AlarmPlayer(ctx)

    /** Messages already handed to the network in this engine's life (survives a cancel). */
    var smsSentSoFar = 0
        private set

    fun stopAlarm() = alarm.stop()
    val alarmPlaying get() = alarm.playing

    suspend fun run(cfg: SosConfig, contacts: List<Contact>): RunResult {
        var sent = 0
        var failed = 0
        var called = false

        preflight(cfg)
        if (cfg.alarm) {
            if (alarm.start()) log(LogLevel.OK, "Alarm is sounding. Tap \"Stop alarm\" to silence it.")
            else log(LogLevel.WARN, "Couldn't start the alarm sound.")
        }

        if (cfg.sendSms) {
            when {
                !Perms.sms(ctx) -> log(LogLevel.ERROR, "Can't send messages: SMS permission is off.")
                contacts.isEmpty() -> log(LogLevel.ERROR, "Can't send messages: no trusted contacts saved.")
                else -> {
                    var body = cfg.message.trim()
                    if (cfg.includeLocation) {
                        log(LogLevel.INFO, "Getting your location (one time, up to ${LOCATION_TIMEOUT_MS / 1000} s)...")
                        val fix = getLocation()
                        body += " " + locationText(fix)
                        when (fix) {
                            is Fix.Current -> log(LogLevel.OK, "Location found.")
                            is Fix.LastKnown -> log(LogLevel.WARN, "Only an older location (${fix.ageMin} min) was available.")
                            is Fix.Unavailable -> log(LogLevel.WARN, "Location unavailable: ${fix.reason}. Message will say so.")
                        }
                    }
                    for (c in contacts) {
                        log(LogLevel.INFO, "Sending message to ${c.name}...")
                        val r = sendSms(c, body)
                        if (r.ok) {
                            sent++
                            smsSentSoFar++
                            log(LogLevel.OK, "Message to ${c.name} was handed to the mobile network. (This is not proof it was read.)")
                        } else {
                            failed++
                            log(LogLevel.ERROR, "Message to ${c.name} failed: ${r.reason}")
                        }
                    }
                }
            }
        }

        if (cfg.placeCall) {
            val cc = contacts.firstOrNull { it.id == cfg.callContactId }
            if (cc == null) log(LogLevel.ERROR, "Can't call: no call contact chosen.")
            else called = placeCall(cc)
        }
        return RunResult(sent, failed, called, alarm.playing)
    }

    private fun preflight(cfg: SosConfig) {
        if (DeviceState.airplaneMode(ctx)) log(LogLevel.WARN, "Airplane mode is ON. Turn it off so messages and calls can work.")
        else if (DeviceState.hasTelephony(ctx) && !DeviceState.simReady(ctx) && (cfg.sendSms || cfg.placeCall))
            log(LogLevel.WARN, "SIM card isn't ready. Messages and calls may fail.")
        if (DeviceState.lowBattery(ctx)) log(LogLevel.WARN, "Battery is low (${DeviceState.batteryPercent(ctx)}%). SOS will still try to run.")
    }

    // ---------------- Location (one shot) ----------------

    @SuppressLint("MissingPermission")
    private suspend fun getLocation(): Fix {
        if (!Perms.location(ctx)) return Fix.Unavailable("location permission is off")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm)) return Fix.Unavailable("the phone's location setting is off")

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (providers.isEmpty()) return Fix.Unavailable("no location provider is enabled")

        val fresh: Location? = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            val result = CompletableDeferred<Location?>()
            var pending = providers.size
            val cancels = providers.mapNotNull { p ->
                runCatching {
                    requestOneFix(lm, p) { loc ->
                        if (loc != null) result.complete(loc)
                        else if (--pending == 0) result.complete(null)
                    }
                }.getOrElse { if (--pending == 0) result.complete(null); null }
            }
            try { result.await() } finally { cancels.forEach { it() } }
        }
        if (fresh != null) return Fix.Current(fresh.latitude, fresh.longitude, fresh.accuracy)

        // Fallback: a recent cached position, clearly labelled as older.
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null) {
            val ageMin = (System.currentTimeMillis() - last.time) / 60_000
            if (ageMin <= 15) return Fix.LastKnown(last.latitude, last.longitude, ageMin)
        }
        return Fix.Unavailable("no GPS/network fix (try near a window or outdoors)")
    }

    /** One-shot location request (no ongoing updates). Returns a function that cancels it. */
    @SuppressLint("MissingPermission")
    private fun requestOneFix(lm: LocationManager, provider: String, cb: (Location?) -> Unit): () -> Unit {
        if (Build.VERSION.SDK_INT >= 30) {
            val signal = android.os.CancellationSignal()
            lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(ctx)) { cb(it) }
            return { signal.cancel() }
        }
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(l: Location) = cb(l)
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        @Suppress("DEPRECATION")
        lm.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
        return { lm.removeUpdates(listener) }
    }

    // ---------------- SMS ----------------

    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()

    suspend fun sendSms(contact: Contact, body: String): SmsOutcome {
        if (!Perms.sms(ctx)) return SmsOutcome(false, "SMS permission is off")
        val n = counter.incrementAndGet()
        val action = "com.safetap.sos.SMS_SENT.$n"
        val done = CompletableDeferred<SmsOutcome>()
        val sm = smsManager()
        val parts = sm.divideMessage(body)
        var remaining = parts.size
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (resultCode != Activity.RESULT_OK) done.complete(SmsOutcome(false, reasonFor(resultCode)))
                else if (--remaining == 0) done.complete(SmsOutcome(true, "Sent"))
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        return try {
            val pi = PendingIntent.getBroadcast(ctx, n, Intent(action).setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE)
            if (parts.size == 1) sm.sendTextMessage(contact.phone, null, body, pi, null)
            else sm.sendMultipartTextMessage(contact.phone, null, parts, ArrayList(parts.map { pi }), null)
            withTimeoutOrNull(30_000) { done.await() }
                ?: SmsOutcome(false, "no answer from the mobile network after 30 seconds (weak signal?)")
        } catch (e: SecurityException) {
            SmsOutcome(false, "Android blocked sending (permission)")
        } catch (e: Exception) {
            SmsOutcome(false, e.message ?: "unexpected error")
        } finally {
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }

    private fun reasonFor(code: Int) = when (code) {
        SmsManager.RESULT_ERROR_RADIO_OFF -> "airplane mode or mobile radio is off"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no mobile signal"
        SmsManager.RESULT_ERROR_NULL_PDU -> "message could not be built"
        5 -> "Android's SMS sending limit was reached"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "the phone couldn't send it (check SIM, signal, or credit)"
        else -> "error code $code"
    }

    /** "I'm OK" follow-up after an SOS that was already sent. Only sent on an explicit tap. */
    suspend fun sendAllClear(contacts: List<Contact>): Int {
        var ok = 0
        for (c in contacts) {
            val r = sendSms(c, "Pocket Guardian update: the earlier alert was a false alarm or is over. I am OK.")
            if (r.ok) ok++
        }
        return ok
    }

    // ---------------- Call ----------------

    fun placeCall(contact: Contact): Boolean {
        val direct = Perms.call(ctx)
        val intent = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", contact.phone, null))
        return try {
            ctx.startActivity(intent)
            if (direct) log(LogLevel.OK, "Calling ${contact.name}...")
            else log(LogLevel.WARN, "Phone permission is off. The dialer opened for ${contact.name}: press the green Call button.")
            true
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Couldn't start the call to ${contact.name}: ${e.message ?: "no phone app"}")
            false
        }
    }

    companion object {
        private val counter = AtomicInteger(0)
        const val LOCATION_TIMEOUT_MS = 8_000L

        fun locationText(fix: Fix): String = when (fix) {
            is Fix.Current -> "Location: ${mapsUrl(fix.lat, fix.lon)} (accuracy about ${fix.accuracyM.toInt()} m)"
            is Fix.LastKnown -> "Last known location (${fix.ageMin} min old): ${mapsUrl(fix.lat, fix.lon)}"
            is Fix.Unavailable -> "Location unavailable."
        }

        fun mapsUrl(lat: Double, lon: Double) = String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", lat, lon)
    }
}

/** Optional audible alarm. Uses the phone's alarm sound; restores the alarm volume when stopped. */
class AlarmPlayer(private val ctx: Context) {
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    val playing get() = player != null

    fun start(): Boolean {
        if (player != null) return true
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        if (savedVolume >= 0) {
            runCatching {
                (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            }
            savedVolume = -1
        }
    }
}
