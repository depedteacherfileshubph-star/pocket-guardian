package com.safetap.sos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** The simple screen the child sees. */
@Composable
fun HomeScreen(vm: AppViewModel, onSos: () -> Unit) {
    val ctx = LocalContext.current
    val cfg = vm.config
    val readiness = remember(cfg, vm.contacts, vm.refresh) { evaluateReadiness(ctx, cfg, vm.contacts) }
    val lic = remember(vm.refresh, vm.licenseTick) { License.state(ctx, vm.store) }

    val view = LocalView.current
    val keepOn = cfg.keepScreenOn && cfg.enabled
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    Column(
        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pocket Guardian", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })

        val good: Boolean? = when (readiness.level) {
            ReadyLevel.READY -> true
            ReadyLevel.READY_WITH_WARNINGS -> null
            else -> false
        }
        val kind = when (readiness.level) {
            ReadyLevel.READY -> "Status: OK"
            ReadyLevel.READY_WITH_WARNINGS -> "Status: Check"
            ReadyLevel.INCOMPLETE -> "Status: Not ready"
            ReadyLevel.DISABLED -> "Status: Off"
        }
        StatusBanner(kind, readiness.headline, good)
        readiness.issues.take(3).forEach { Text("- ${it.text}", style = MaterialTheme.typography.bodyLarge) }

        when (lic) {
            is LicenseState.Trial -> Text("Free trial: ${lic.daysLeft} day(s) left.", style = MaterialTheme.typography.bodyMedium)
            LicenseState.Expired -> StatusBanner("Trial ended", "SOS still works. A guardian must activate Pocket Guardian to change settings.", null)
            LicenseState.Licensed -> {}
        }

        BigButton("SOS - GET HELP", onSos, danger = true, modifier = Modifier.heightIn(min = 160.dp))
        Text("Tap it if you need help. You will have a moment to cancel if it was an accident.", style = MaterialTheme.typography.bodyLarge)

        Section("Volume button shortcuts") {
            if (cfg.enabled && cfg.activeCounts.isNotEmpty()) {
                Text(
                    "Press ${cfg.pattern.label} quickly, then pause (within ${cfg.windowMs / 1000} seconds):",
                    style = MaterialTheme.typography.bodyLarge,
                )
                cfg.activeCounts.sorted().forEach { n ->
                    PressSequence(cfg.pattern, n)
                    Text("$n presses = ${Act.label(cfg.mappings[n] ?: 0)}", style = MaterialTheme.typography.bodyLarge)
                }
                val bgOn = cfg.backgroundMode && BgState.running
                val tested = remember(vm.refresh) { vm.store.bgVerified }
                Text(
                    when {
                        bgOn && tested -> "Screen-off shortcut: ON and tested. Works with the screen off while the Pocket Guardian notification is showing. You will feel two buzzes when it is recognised."
                        bgOn -> "Screen-off shortcut: ON but NOT tested yet. It may not work on this phone. Until a guardian tests it, rely on the SOS button."
                        cfg.backgroundMode -> "Screen-off shortcut: STOPPED. Ask a guardian to open this app to restart it. Until then it only works while this screen is open."
                        else -> "It works only while this screen is open and the phone is awake. Use the SOS button otherwise."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (cfg.enabled) {
                Text("No shortcut is set up. Use the SOS button.", style = MaterialTheme.typography.bodyLarge)
            } else {
                Text("Off. Ask your guardian to set it up.", style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text("Your location is checked only at the moment SOS is sent. It is never tracked, and nothing is recorded.", style = MaterialTheme.typography.bodyMedium)

        BigButton("Guardian settings", {
            vm.screen = if (vm.isUnlocked()) Screen.SETTINGS else Screen.PIN
        }, outlined = true)
    }
}

@Composable
fun PinScreen(vm: AppViewModel) {
    val create = !vm.store.hasPin() || vm.screen == Screen.PIN_CHANGE
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val back = if (vm.screen == Screen.PIN_CHANGE) Screen.SETTINGS else Screen.HOME

    ScreenFrame(if (create) "Create guardian PIN" else "Enter guardian PIN", { vm.screen = back }) {
        Text(
            if (create) "Choose a 4 to 8 digit PIN. Only the guardian should know it. It protects all SOS settings."
            else "Settings are locked so they can't be changed by accident.",
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("PIN") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        if (create) OutlinedTextField(
            confirm, { confirm = it.filter(Char::isDigit).take(8) }, label = { Text("Type the PIN again") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
        BigButton(if (create) "Save PIN" else "Unlock", {
            if (create) {
                when {
                    pin.length < 4 -> error = "PIN must be at least 4 digits."
                    pin != confirm -> error = "The two PINs don't match."
                    else -> { vm.store.setPin(pin); vm.unlock(); vm.screen = Screen.SETTINGS }
                }
            } else when (val r = vm.store.verifyPin(pin)) {
                PinResult.Ok -> { vm.unlock(); vm.screen = Screen.SETTINGS }
                PinResult.Wrong -> { error = "Wrong PIN."; pin = "" }
                is PinResult.Locked -> { error = "Too many tries. Wait ${(r.remainingMs / 1000) + 1} seconds."; pin = "" }
            }
        })
        if (!create) Text("Forgot the PIN? Clear this app's storage in phone Settings > Apps (this erases all Pocket Guardian settings).", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val cfg = vm.config
    val readiness = remember(cfg, vm.contacts, vm.refresh) { evaluateReadiness(ctx, cfg, vm.contacts) }
    val lic = remember(vm.refresh, vm.licenseTick) { License.state(ctx, vm.store) }
    val expired = lic == LicenseState.Expired

    LaunchedEffect(Unit) {
        if (vm.autoCheck && !expired && Updater.configured() && vm.updateAvailable == null) {
            (Updater.check() as? UpdateResult.Available)?.let { vm.updateAvailable = it.info }
        }
    }

    ScreenFrame("Guardian settings", { vm.screen = Screen.HOME }) {
        when (lic) {
            LicenseState.Licensed -> {}
            is LicenseState.Trial -> StatusBanner("Free trial", "${lic.daysLeft} day(s) left.", null)
            LicenseState.Expired -> StatusBanner("Trial ended", "Activate to change settings. The child's SOS button keeps working.", false)
        }
        if (lic != LicenseState.Licensed) BigButton(if (expired) "Unlock now (one-time payment)" else "Unlock (one-time payment)", { vm.screen = Screen.LICENSE }, danger = expired)

        if (!expired) {
            vm.updateAvailable?.let {
                StatusBanner("Update available", "Version ${it.version} is ready to download.", null)
                BigButton("Review update", { vm.screen = Screen.UPDATE })
            }
            ToggleRow(
                "SOS enabled", "Turn off to disable the whole SOS feature (volume shortcut and SOS button).",
                cfg.enabled,
            ) { on -> vm.updateConfig { it.copy(enabled = on) } }
        }

        val good: Boolean? = when (readiness.level) { ReadyLevel.READY -> true; ReadyLevel.READY_WITH_WARNINGS -> null; else -> false }
        StatusBanner("Setup check", readiness.headline, good)
        readiness.issues.forEach {
            Text((if (it.blocking) "Fix: " else "Note: ") + it.text, style = MaterialTheme.typography.bodyLarge)
        }

        if (!expired) {
            BigButton("1. Volume button: choose and test", { vm.screen = Screen.TRIGGER })
            BigButton("2. What the presses do", { vm.screen = Screen.ACTIONS })
            BigButton("3. Trusted contacts and tests", { vm.screen = Screen.CONTACTS })
        }
        BigButton("Permissions explained", { vm.screen = Screen.PERMISSIONS }, outlined = true)
        if (!expired) {
            BigButton("Screen-off shortcut (optional)", { vm.screen = Screen.BACKGROUND }, outlined = true)
            BigButton("App updates", { vm.screen = Screen.UPDATE }, outlined = true)
        }
        BigButton("Privacy and device limits", { vm.screen = Screen.ABOUT }, outlined = true)
        BigButton("Change PIN", { vm.screen = Screen.PIN_CHANGE }, outlined = true)
        BigButton("Lock settings and go back", { vm.lock(); vm.screen = Screen.HOME }, outlined = true)
    }
}
