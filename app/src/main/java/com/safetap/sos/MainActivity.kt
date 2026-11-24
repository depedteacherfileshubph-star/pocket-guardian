package com.safetap.sos

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

enum class Screen { HOME, PIN, PIN_CHANGE, SETTINGS, TRIGGER, ACTIONS, CONTACTS, PERMISSIONS, ABOUT, UPDATE, BACKGROUND, LICENSE }

/** Guardian screens that need an active licence (the trial counts). Safety features are never in this list. */
private val LICENSED_SCREENS = setOf(Screen.TRIGGER, Screen.ACTIONS, Screen.CONTACTS, Screen.UPDATE, Screen.BACKGROUND)

/** Which meaning the volume keys have on the current screen. */
enum class KeyMode { NONE, LIVE, TEST }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    var config by mutableStateOf(store.loadConfig()); private set
    var contacts by mutableStateOf(store.loadContacts()); private set
    var screen by mutableStateOf(Screen.HOME)
    var unlockedAt by mutableLongStateOf(0L)
    var keyMode by mutableStateOf(KeyMode.NONE)
    /** Bumped on resume / after permission changes so readiness is re-evaluated. */
    var refresh by mutableIntStateOf(0)
    var licenseTick by mutableIntStateOf(0)
    var updateAvailable by mutableStateOf<UpdateInfo?>(null)
    var autoCheck by mutableStateOf(store.autoUpdateCheck)

    private val handler = Handler(Looper.getMainLooper())
    private val allCounts = (Limits.MIN_PRESSES..Limits.MAX_PRESSES).toSet()
    private val liveDetector = TriggerDetector(config.pattern, config.activeCounts, config.windowMs)
    /** The test screen reports every count 2..8, mapped or not. */
    private val testDetector = TriggerDetector(config.pattern, allCounts, config.windowMs)

    /** Set by the activity: starts SOS with the actions mapped to the recognised press count. */
    var onSeries: ((Int) -> Unit)? = null

    var testProgress by mutableIntStateOf(0); private set
    var testLastCount by mutableIntStateOf(0); private set
    var testHits by mutableIntStateOf(0); private set
    var lastKey by mutableStateOf("None yet"); private set

    private val pollTask = Runnable {
        val d = activeDetector()
        d.poll(SystemClock.uptimeMillis())?.let { finish(it) }
        testProgress = d.progress
    }

    private fun activeDetector() = if (keyMode == KeyMode.TEST) testDetector else liveDetector

    fun updateConfig(f: (SosConfig) -> SosConfig) {
        config = f(config)
        store.saveConfig(config)
        liveDetector.configure(config.pattern, config.activeCounts, config.windowMs)
        testDetector.configure(config.pattern, allCounts, config.windowMs)
        testProgress = 0
    }

    fun updateContacts(list: List<Contact>) {
        contacts = list
        store.saveContacts(list)
        if (config.callContactId != null && list.none { it.id == config.callContactId }) {
            updateConfig { it.copy(callContactId = null) }
        }
    }

    fun unlock() { unlockedAt = System.currentTimeMillis() }
    fun lock() { unlockedAt = 0L }
    fun isUnlocked() = System.currentTimeMillis() - unlockedAt < UNLOCK_MS

    fun resetTest() {
        handler.removeCallbacks(pollTask)
        testDetector.reset(); testProgress = 0; testHits = 0; testLastCount = 0; lastKey = "None yet"
    }

    fun resetDetector() {
        handler.removeCallbacks(pollTask)
        liveDetector.reset(); testDetector.reset(); testProgress = 0
    }

    private fun finish(count: Int) {
        if (keyMode == KeyMode.TEST) {
            testLastCount = count
            testHits++
        } else if (keyMode == KeyMode.LIVE) {
            onSeries?.invoke(config.mappings[count] ?: 0)
        }
    }

    fun onVolumeKey(code: Int, time: Long) {
        if (keyMode == KeyMode.NONE) return
        if (keyMode == KeyMode.LIVE && !config.enabled) return
        val d = activeDetector()
        handler.removeCallbacks(pollTask)
        val immediate = d.onKeyDown(code, time)
        if (keyMode == KeyMode.TEST) {
            lastKey = if (code == KeyEvent.KEYCODE_VOLUME_UP) "Volume Up" else "Volume Down"
            testProgress = d.progress
        }
        if (immediate != null) finish(immediate)
        else if (d.progress > 0) handler.postDelayed(pollTask, d.gapMs + 40)
    }

    override fun onCleared() {
        handler.removeCallbacks(pollTask)
        super.onCleared()
    }

    companion object { const val UNLOCK_MS = 10 * 60_000L }
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.onSeries = { actions -> if (actions != 0) startSos("Volume buttons", actions) }
        setContent {
            SafeTapTheme {
                var splash by rememberSaveable { mutableStateOf(true) }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (splash) SplashScreen { splash = false }
                    else AppRoot(vm) { startSos("On-screen button", vm.config.buttonActions) }
                }
            }
        }
    }

    override fun onPause() {
        BgState.uiVisible = (BgState.uiVisible - 1).coerceAtLeast(0)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        BgState.uiVisible++
        // Restarting here (app visible) is the only restart path: no boot receiver, no hidden start.
        // The screen-off shortcut is a licensed guardian feature; it stays off after the trial ends.
        if (vm.config.backgroundMode && !BgState.running && !License.isExpired(this, vm.store)) ShortcutService.start(this)
        vm.refresh++
        if (vm.screen != Screen.HOME && !vm.isUnlocked()) vm.screen = Screen.HOME
        vm.resetDetector()
    }

    /**
     * Volume keys are observed ONLY while this activity is visible and in front. We return the
     * normal super result, so the system still changes the volume as usual: nothing is
     * intercepted or suppressed.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0 &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            vm.onVolumeKey(keyCode, event.eventTime)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        vm.onSeries = null
        super.onDestroy()
    }

    private fun startSos(source: String, actions: Int) {
        startActivity(
            Intent(this, SosActivity::class.java)
                .putExtra(SosActivity.EXTRA_SOURCE, source)
                .putExtra(SosActivity.EXTRA_ACTIONS, actions)
        )
    }
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel(), onSos: () -> Unit) {
    val ctx = LocalContext.current
    val expired = remember(vm.refresh, vm.licenseTick, vm.screen) { License.isExpired(ctx, vm.store) }
    val screen = if (expired && vm.screen in LICENSED_SCREENS) Screen.LICENSE else vm.screen

    LaunchedEffect(screen) {
        vm.keyMode = when (screen) { Screen.HOME -> KeyMode.LIVE; Screen.TRIGGER -> KeyMode.TEST; else -> KeyMode.NONE }
        vm.resetDetector()
    }
    BackHandler(enabled = vm.screen != Screen.HOME) {
        vm.screen = when (vm.screen) {
            Screen.SETTINGS, Screen.PIN -> Screen.HOME
            else -> Screen.SETTINGS
        }
    }
    when (screen) {
        Screen.HOME -> HomeScreen(vm, onSos)
        Screen.PIN, Screen.PIN_CHANGE -> PinScreen(vm)
        Screen.SETTINGS -> SettingsScreen(vm)
        Screen.TRIGGER -> TriggerScreen(vm)
        Screen.ACTIONS -> ActionsScreen(vm)
        Screen.CONTACTS -> ContactsScreen(vm)
        Screen.PERMISSIONS -> PermissionsScreen(vm)
        Screen.ABOUT -> AboutScreen(vm)
        Screen.UPDATE -> UpdateScreen(vm)
        Screen.BACKGROUND -> ShortcutScreen(vm)
        Screen.LICENSE -> LicenseScreen(vm)
    }
}
