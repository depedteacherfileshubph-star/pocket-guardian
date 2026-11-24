package com.safetap.sos

import android.app.Application
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

enum class Screen { HOME, PIN, PIN_CHANGE, SETTINGS, TRIGGER, ACTIONS, CONTACTS, PERMISSIONS, ABOUT, UPDATE, BACKGROUND }

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
    var updateAvailable by mutableStateOf<UpdateInfo?>(null)
    var autoCheck by mutableStateOf(store.autoUpdateCheck)

    private val detector = TriggerDetector(config.sequence, config.windowMs)
    var testProgress by mutableIntStateOf(0); private set
    var testHits by mutableIntStateOf(0); private set
    var lastKey by mutableStateOf("None yet"); private set

    fun updateConfig(f: (SosConfig) -> SosConfig) {
        config = f(config)
        store.saveConfig(config)
        detector.configure(config.sequence, config.windowMs)
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

    fun resetTest() { detector.reset(); testProgress = 0; testHits = 0; lastKey = "None yet" }
    fun resetDetector() { detector.reset(); testProgress = 0 }

    /** @return true if the real SOS trigger just completed. */
    fun onVolumeKey(code: Int, time: Long): Boolean {
        if (keyMode == KeyMode.NONE) return false
        if (keyMode == KeyMode.LIVE && !config.enabled) return false
        val hit = detector.onKeyDown(code, time)
        if (keyMode == KeyMode.TEST) {
            lastKey = if (code == KeyEvent.KEYCODE_VOLUME_UP) "Volume Up" else "Volume Down"
            testProgress = detector.progress
            if (hit) testHits++
            return false // test only: never starts SOS
        }
        return hit
    }

    companion object { const val UNLOCK_MS = 10 * 60_000L }
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SafeTapTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(vm) { startSos("Volume buttons") }
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
        // The guardian turned the screen-off shortcut on; a reboot/kill may have stopped it.
        // Restarting here (app visible) is the only restart path: no boot receiver, no hidden start.
        if (vm.config.backgroundMode && !BgState.running) ShortcutService.start(this)
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
            if (vm.onVolumeKey(keyCode, event.eventTime)) startSos("Volume buttons")
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startSos(source: String) {
        startActivity(Intent(this, SosActivity::class.java).putExtra(SosActivity.EXTRA_SOURCE, source))
    }
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel(), onSos: () -> Unit) {
    val screen = vm.screen
    LaunchedEffect(screen) {
        vm.keyMode = when (screen) { Screen.HOME -> KeyMode.LIVE; Screen.TRIGGER -> KeyMode.TEST; else -> KeyMode.NONE }
        vm.resetDetector()
    }
    BackHandler(enabled = screen != Screen.HOME) {
        vm.screen = when (screen) {
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
    }
}
