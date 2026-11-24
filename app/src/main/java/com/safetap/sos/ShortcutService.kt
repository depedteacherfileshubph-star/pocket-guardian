package com.safetap.sos

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    /** Volume presses delivered to the service. Shows the guardian whether keys reach it at all. */
    var keysSeen by mutableIntStateOf(0)
    /** True when the silent keep-alive audio track is playing (needed for keys to arrive with the screen off). */
    var audioActive by mutableStateOf(false)

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
    private var keepAlive: AudioTrack? = null
    private lateinit var detector: TriggerDetector
    private val handler = Handler(Looper.getMainLooper())
    private var lastEventAt = 0L
    @Volatile private var testUntil = 0L

    private val pollTask = Runnable {
        detector.poll(SystemClock.uptimeMillis())?.let { onSeries(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val store = Store(this)
        val cfg = store.loadConfig()
        detector = TriggerDetector(cfg.pattern, cfg.activeCounts, cfg.windowMs)
        val n = statusNotification("Screen-off shortcut is ON. Volume buttons work with the screen off.")
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(ID_STATUS, n)

        // Licensed guardian feature: it does not run after the free trial ends without activation.
        if (License.isExpired(this, store)) {
            stopSelf()
            return
        }

        val provider = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) = handleVolume(direction)
        }
        session = MediaSession(this, "PocketGuardianShortcut").apply {
            setPlaybackToRemote(provider)
            setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Pocket Guardian screen-off shortcut")
                    .build()
            )
            setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).setActions(0).build())
            isActive = true
        }
        startKeepAliveAudio()
        BgState.keysSeen = 0
        BgState.running = true
    }

    /**
     * With the screen off, Android only hands volume keys to a media session while the music
     * stream is actually playing. So the service plays an inaudible looping track (about 1/32768
     * of full scale, far below hearing) purely to keep that route open. No audio is recorded or
     * sent anywhere; this plays nothing the user can hear. A media card for the shortcut may show
     * in the notification shade; that is expected and visible by design.
     */
    private fun startKeepAliveAudio() {
        BgState.audioActive = runCatching {
            val rate = 8000
            val data = ShortArray(rate) { if (it % 2 == 0) 1 else -1 }
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(data, 0, data.size)
            t.setLoopPoints(0, data.size, -1)
            t.play()
            keepAlive = t
            true
        }.getOrDefault(false)
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
        BgState.keysSeen++
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
        // While testing, any count 2..8 is recognised so the guardian can prove the keys arrive.
        val counts = if (testing) (Limits.MIN_PRESSES..Limits.MAX_PRESSES).toSet() else cfg.activeCounts
        detector.configureIfChanged(cfg.pattern, counts, cfg.windowMs)
        val key = if (direction > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
        handler.removeCallbacks(pollTask)
        val immediate = detector.onKeyDown(key, now)
        if (immediate != null) onSeries(immediate)
        else if (detector.progress > 0) handler.postDelayed(pollTask, detector.gapMs + 40)
    }

    private fun onSeries(count: Int) {
        val now = SystemClock.uptimeMillis()
        val testing = now < testUntil
        val cfg = Store(this).loadConfig()
        val actions = cfg.mappings[count] ?: 0
        if (!testing && actions == 0) return
        vibrate() // tactile confirmation: the child can feel it registered with the screen off
        if (testing) {
            testUntil = 0
            BgState.testHits++
            val screenOff = !(getSystemService(POWER_SERVICE) as PowerManager).isInteractive
            if (screenOff) Store(this).bgVerified = true
            updateStatus(
                if (screenOff) "Test passed with the screen off ($count presses). Nothing was sent."
                else "Recognised, but the screen was on. Repeat the test with the screen off."
            )
            return
        }
        val open = Intent(this, SosActivity::class.java)
            .putExtra(SosActivity.EXTRA_SOURCE, "Volume buttons (screen off)")
            .putExtra(SosActivity.EXTRA_ACTIONS, actions)
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
        handler.removeCallbacks(pollTask)
        runCatching { session?.isActive = false; session?.release() }
        runCatching { keepAlive?.stop(); keepAlive?.release() }
        keepAlive = null
        session = null
        BgState.audioActive = false
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
