package com.safetap.sos

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/** Shared, in-process state read by the UI. */
object BgState {
    var running by mutableStateOf(false)
    var testHits by mutableIntStateOf(0)

    /** Number of Pocket Guardian screens currently in front. While > 0 the service stays out of the way. */
    @Volatile var uiVisible = 0
}

/**
 * OPTIONAL "screen-off shortcut" mode. The guardian turns it on knowingly; it shows a permanent
 * notification the whole time and can be switched off from that notification.
 *
 * Mechanism: a MediaSession with a remote [VolumeProvider]. While a session is the active
 * "playback" session, Android routes the volume keys to its provider even when the screen is off.
 * Every key press is still forwarded to the normal system volume, so volume keeps working.
 *
 * NOT guaranteed: routing differs by phone/OEM/Android version, so the app only reports the
 * shortcut as "tested" after the guardian passes a real screen-off test (see ShortcutScreen).
 * It does not listen to the Power button, does not use an Accessibility Service, and never records.
 */
class ShortcutService : Service() {
    private var session: MediaSession? = null
    private lateinit var detector: TriggerDetector
    private var lastEventAt = 0L
    @Volatile private var testUntil = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val cfg = Store(this).loadConfig()
        detector = TriggerDetector(cfg.sequence, cfg.windowMs)
        val n = statusNotification("Screen-off shortcut is ON. Volume buttons work with the screen off.")
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(ID_STATUS, n)

        val provider = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) = handleVolume(direction)
        }
        session = MediaSession(this, "PocketGuardianShortcut").apply {
            setPlaybackToRemote(provider)
            setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).setActions(0).build())
            isActive = true
        }
        BgState.running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val s = Store(this)
                s.saveConfig(s.loadConfig().copy(backgroundMode = false))
                s.bgVerified = false
                stopSelf()
            }
            ACTION_TEST -> {
                testUntil = SystemClock.uptimeMillis() + TEST_WINDOW_MS
                updateStatus("TEST: turn the screen off and press the buttons now.")
            }
        }
        return START_NOT_STICKY // restarted only by opening the app (visible, guardian-aware)
    }

    private fun handleVolume(direction: Int) {
        if (direction == 0) return
        // Keep normal volume behaviour: apply the key to whatever stream Android would have changed.
        runCatching {
            (getSystemService(AUDIO_SERVICE) as AudioManager).adjustSuggestedStreamVolume(
                if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI,
            )
        }
        if (BgState.uiVisible > 0) return // the in-app handler is in charge while a screen is showing
        val now = SystemClock.uptimeMillis()
        val tooSoon = now - lastEventAt < REPEAT_GUARD_MS // a held key auto-repeats; count it once
        lastEventAt = now
        if (tooSoon) return

        val cfg = Store(this).loadConfig()
        val testing = now < testUntil
        if (!cfg.enabled && !testing) return
        detector.configureIfChanged(cfg.sequence, cfg.windowMs)
        val key = if (direction > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
        if (detector.onKeyDown(key, now)) onTrigger(testing, cfg)
    }

    private fun onTrigger(testing: Boolean, cfg: SosConfig) {
        vibrate() // tactile confirmation: the child can feel it registered with the screen off
        if (testing) {
            testUntil = 0
            BgState.testHits++
            val screenOff = !(getSystemService(POWER_SERVICE) as PowerManager).isInteractive
            if (screenOff) Store(this).bgVerified = true
            updateStatus(
                if (screenOff) "Test passed with the screen off. Nothing was sent."
                else "Recognised, but the screen was on. Repeat the test with the screen off."
            )
            return
        }
        val open = Intent(this, SosActivity::class.java)
            .putExtra(SosActivity.EXTRA_SOURCE, "Volume buttons (screen off)")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("SOS - tap to cancel or send")
            .setContentText("Help will be sent after the countdown.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(false).setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true) // shows the visible SOS screen over the lock screen
            .build()
        notifySafely(ID_ALERT, n)
        runCatching { startActivity(open) } // works on phones/states where Android allows it directly
    }

    private fun vibrate() = runCatching {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1)
        if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(effect)
        }
    }

    private fun statusNotification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val off = PendingIntent.getService(
            this, 2, Intent(this, ShortcutService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Pocket Guardian")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Turn off shortcut", off)
            .build()
    }

    private fun updateStatus(text: String) = notifySafely(ID_STATUS, statusNotification(text))

    @SuppressLint("MissingPermission")
    private fun notifySafely(id: Int, n: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(this).notify(id, n) }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Shortcut status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while the screen-off SOS shortcut is on."
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "SOS alert", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Opens the SOS screen when the button sequence is pressed."
        })
    }

    override fun onDestroy() {
        runCatching { session?.isActive = false; session?.release() }
        session = null
        BgState.running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.safetap.sos.STOP_SHORTCUT"
        const val ACTION_TEST = "com.safetap.sos.TEST_SHORTCUT"
        private const val CH_STATUS = "status"
        private const val CH_ALERT = "alert"
        private const val ID_STATUS = 100
        private const val ID_ALERT = 101
        private const val REPEAT_GUARD_MS = 120L
        private const val TEST_WINDOW_MS = 60_000L

        /** Call only while the app is visible (Android requires that to start a foreground service). */
        fun start(ctx: Context): Boolean = runCatching {
            ctx.startForegroundService(Intent(ctx, ShortcutService::class.java)); true
        }.getOrDefault(false)

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ShortcutService::class.java))
        }

        fun armTest(ctx: Context) {
            ctx.startService(Intent(ctx, ShortcutService::class.java).setAction(ACTION_TEST))
        }
    }
}
