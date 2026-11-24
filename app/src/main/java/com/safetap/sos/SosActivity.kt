package com.safetap.sos

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Phase { OFF, COUNTDOWN, RUNNING, DONE, CANCELLED }

/**
 * The one place where SOS actually runs. It is always visible (never a hidden background task):
 * it shows a countdown, big Cancel and Send-now buttons, live status, and fallback guidance.
 */
class SosActivity : ComponentActivity() {
    private var engine: SosEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val store = Store(this)
        val cfg = store.loadConfig()
        val contacts = store.loadContacts()
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "On-screen button"
        val lines = mutableStateListOf<Pair<LogLevel, String>>()
        val eng = SosEngine(this) { level, text -> lines.add(level to text) }
        engine = eng
        setContent {
            SafeTapTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SosScreen(cfg, contacts, eng, lines, source) { finish() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        BgState.uiVisible++
    }

    override fun onPause() {
        BgState.uiVisible = (BgState.uiVisible - 1).coerceAtLeast(0)
        super.onPause()
    }

    override fun onDestroy() {
        engine?.stopAlarm()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SOURCE = "source"
    }
}

@Composable
private fun SosScreen(
    cfg: SosConfig,
    contacts: List<Contact>,
    engine: SosEngine,
    lines: MutableList<Pair<LogLevel, String>>,
    source: String,
    onClose: () -> Unit,
) {
    var phase by remember {
        mutableStateOf(if (!cfg.enabled) Phase.OFF else if (cfg.graceSeconds == 0) Phase.RUNNING else Phase.COUNTDOWN)
    }
    var secondsLeft by remember { mutableIntStateOf(cfg.graceSeconds) }
    var result by remember { mutableStateOf<RunResult?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var alarmStopped by remember { mutableStateOf(false) }
    var allClearMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(phase, attempt) {
        if (phase == Phase.COUNTDOWN) {
            while (secondsLeft > 0) { delay(1000); secondsLeft-- }
            phase = Phase.RUNNING
        } else if (phase == Phase.RUNNING) {
            result = engine.run(cfg, contacts)
            phase = Phase.DONE
        }
    }

    fun cancel() {
        engine.stopAlarm()
        alarmStopped = true
        phase = Phase.CANCELLED
    }

    BackHandler(enabled = phase == Phase.COUNTDOWN) { cancel() }

    Column(
        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val live = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
        when (phase) {
            Phase.OFF -> {
                Text("SOS is turned off", style = MaterialTheme.typography.titleLarge)
                Text("A guardian has not turned SOS on, so nothing was sent.", style = MaterialTheme.typography.bodyLarge)
                Guidance()
            }
            Phase.COUNTDOWN -> {
                Text("SOS from: $source", style = MaterialTheme.typography.bodyMedium)
                Text("Sending help in", style = MaterialTheme.typography.titleLarge, modifier = live)
                Text("$secondsLeft", fontSize = 120.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
                Text("Pressed by accident? Tap Cancel.", style = MaterialTheme.typography.bodyLarge)
                BigButton("CANCEL - I'M OK", { cancel() })
                BigButton("SEND HELP NOW", { phase = Phase.RUNNING }, danger = true)
            }
            Phase.RUNNING -> {
                Text("Getting help...", style = MaterialTheme.typography.titleLarge, modifier = live)
                LogView(lines)
                if (cfg.alarm && !alarmStopped) BigButton("STOP ALARM", { engine.stopAlarm(); alarmStopped = true }, outlined = true)
                BigButton("STOP - I'M OK", { cancel() })
            }
            Phase.DONE -> {
                val r = result
                val ok = r?.anyHelpSent == true && (r.smsFailed == 0)
                Text(
                    when {
                        r == null -> "Finished"
                        ok -> "Help request sent"
                        r.anyHelpSent -> "Help request partly sent"
                        else -> "Help could NOT be sent"
                    },
                    style = MaterialTheme.typography.titleLarge, modifier = live,
                )
                LogView(lines)
                if (!ok) Guidance()
                if (cfg.alarm && !alarmStopped) BigButton("STOP ALARM", { engine.stopAlarm(); alarmStopped = true }, outlined = true)
                if (!ok) BigButton("TRY AGAIN", { lines.add(LogLevel.INFO to "Trying again..."); result = null; attempt++; phase = Phase.RUNNING }, danger = true)
                if (engine.smsSentSoFar > 0) AllClearButton(allClearMsg) {
                    scope.launch {
                        val n = engine.sendAllClear(contacts)
                        allClearMsg = if (n > 0) "Told $n contact(s) you are OK." else "Couldn't send the \"I'm OK\" message. Call them if you can."
                    }
                }
                BigButton("CLOSE", onClose, outlined = true)
            }
            Phase.CANCELLED -> {
                Text("Cancelled", style = MaterialTheme.typography.titleLarge, modifier = live)
                if (engine.smsSentSoFar > 0) {
                    Text("A help message was already sent before you cancelled. Your contacts may be worried.", style = MaterialTheme.typography.bodyLarge)
                    AllClearButton(allClearMsg) {
                        scope.launch {
                            val n = engine.sendAllClear(contacts)
                            allClearMsg = if (n > 0) "Told $n contact(s) you are OK." else "Couldn't send the \"I'm OK\" message. Call them if you can."
                        }
                    }
                } else Text("Nothing was sent.", style = MaterialTheme.typography.bodyLarge)
                BigButton("CLOSE", onClose)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("In immediate danger, call your local emergency number.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AllClearButton(message: String?, onClick: () -> Unit) {
    if (message != null) Text(message, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    else BigButton("TELL CONTACTS I'M OK", onClick, outlined = true)
}

@Composable
private fun LogView(lines: List<Pair<LogLevel, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        lines.forEach { (level, text) ->
            val tag = when (level) { LogLevel.OK -> "Done: "; LogLevel.WARN -> "Warning: "; LogLevel.ERROR -> "Problem: "; LogLevel.INFO -> "" }
            Text(tag + text, style = MaterialTheme.typography.bodyLarge,
                color = if (level == LogLevel.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                fontWeight = if (level == LogLevel.INFO) FontWeight.Normal else FontWeight.Bold)
        }
    }
}

@Composable
private fun Guidance() {
    Section("What to do now") {
        Text("1. Tell a trusted adult near you.", style = MaterialTheme.typography.bodyLarge)
        Text("2. Call your local emergency number from this phone.", style = MaterialTheme.typography.bodyLarge)
        Text("3. If you can, turn off Airplane mode and move to a place with signal, then try again.", style = MaterialTheme.typography.bodyLarge)
    }
}
