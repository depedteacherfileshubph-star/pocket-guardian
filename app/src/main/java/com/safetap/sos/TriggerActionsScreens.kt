package com.safetap.sos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------- Trigger: choose button, test

@Composable
fun TriggerScreen(vm: AppViewModel) {
    val cfg = vm.config
    ScreenFrame("Volume button shortcut", { vm.screen = Screen.SETTINGS }) {
        Section("How it works") {
            Text("1. Pick which volume button you press.", style = MaterialTheme.typography.bodyLarge)
            Text("2. In SOS actions, choose what each number of presses does (for example 2 presses = SMS, 3 = call).", style = MaterialTheme.typography.bodyLarge)
            Text("3. Practise below. Nothing is sent during the test.", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Important: by default Android only lets an ordinary app see volume buttons while the app is open on screen " +
                    "and the phone is awake. For the screen off, use the optional Screen-off shortcut and test it. " +
                    "The Power button cannot be used. The big SOS button and the Quick Settings tile are backups.",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            )
        }

        Section("Which button?") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VolumeRocker(cfg.pattern)
                Spacer(Modifier.width(16.dp))
                Text(cfg.pattern.help, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
            ChoiceGroup("Button", PatternType.entries, cfg.pattern, { it.label }) { p ->
                vm.updateConfig { it.copy(pattern = p) }
            }
            ChoiceGroup("All presses must be finished within", Limits.WINDOWS_MS, cfg.windowMs, { "${it / 1000} seconds" }) { w ->
                vm.updateConfig { it.copy(windowMs = w) }
            }
            Text("A short pause (under a second) after the last press ends the series. Pressing the highest mapped count fires at once.", style = MaterialTheme.typography.bodyMedium)
            ToggleRow(
                "Keep screen on while the child screen is open",
                "Helps the shortcut keep working. Uses more battery. Turns off when the app is closed.",
                cfg.keepScreenOn,
            ) { on -> vm.updateConfig { it.copy(keepScreenOn = on) } }
        }

        Section("Test it (no message, no call)") {
            Text("Press the volume button now, then pause.", style = MaterialTheme.typography.bodyLarge)
            Text("Last button seen: ${vm.lastKey}", style = MaterialTheme.typography.bodyLarge)
            Text("Presses so far: ${vm.testProgress}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            if (vm.testLastCount > 0) {
                val mask = cfg.mappings[vm.testLastCount] ?: 0
                StatusBanner(
                    "Recognised",
                    "${vm.testLastCount} presses -> ${Act.label(mask)}. Nothing was sent.",
                    if (mask != 0) true else null,
                )
                PressSequence(cfg.pattern, vm.testLastCount)
                if (mask == 0) Text("No action is set for ${vm.testLastCount} presses. Set one in SOS actions.", style = MaterialTheme.typography.bodyMedium)
            } else StatusBanner("Waiting", "No press series recognised yet.", null)
            Text("If no presses are counted, your phone may not report volume keys to apps on this screen. Use the on-screen SOS button instead.", style = MaterialTheme.typography.bodyMedium)
            BigButton("Reset test", { vm.resetTest() }, outlined = true)
        }
    }
}

// ---------------------------------------------------------------- SOS actions

@Composable
fun ActionsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val cfg = vm.config
    val smsOk = remember(vm.refresh) { Perms.sms(ctx) }
    val locOk = remember(vm.refresh) { Perms.location(ctx) }
    val callOk = remember(vm.refresh) { Perms.call(ctx) }
    val used = cfg.usedActions

    ScreenFrame("SOS actions", { vm.screen = Screen.SETTINGS }) {
        Section("What each number of presses does") {
            Text("Tap a row to change its action. Every action still shows the cancel countdown first.", style = MaterialTheme.typography.bodyLarge)
            for (count in Limits.MIN_PRESSES..Limits.MAX_PRESSES) {
                val mask = cfg.mappings[count] ?: 0
                PressSequence(cfg.pattern, count)
                BigButton("$count presses: ${Act.label(mask)}  (tap to change)", {
                    vm.updateConfig { it.copy(mappings = it.mappings + (count to Act.next(mask))) }
                }, outlined = mask == 0)
                if (mask != 0 && count <= Limits.RISKY_AT_OR_BELOW) {
                    Text("Warning: $count presses can happen by accident when changing volume. Use a higher count for important actions.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Section("The on-screen SOS button does") {
            BigButton("${Act.label(cfg.buttonActions)}  (tap to change)", {
                var n = Act.next(cfg.buttonActions)
                if (n == 0) n = Act.next(n) // the SOS button must always do something
                vm.updateConfig { it.copy(buttonActions = n) }
            })
        }

        if (used and Act.SMS != 0) Section("Text message (SMS)") {
            Text("Uses the phone's SIM. Carrier SMS charges may apply.", style = MaterialTheme.typography.bodyMedium)
            PermissionRow(
                "SMS", "Needed to send the help message from this phone's SIM. Used only when SOS is triggered or you press a test button.",
                "Without it the app cannot send help messages; the child screen will say so.",
                smsOk, Perms.SMS,
            ) { vm.refresh++ }
            OutlinedTextField(
                cfg.message, { t -> vm.updateConfig { it.copy(message = t.take(Limits.MAX_MESSAGE)) } },
                label = { Text("Help message (${cfg.message.length}/${Limits.MAX_MESSAGE})") },
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow("Include current location", "Location is looked up once, only when SOS is sent.", cfg.includeLocation) { on ->
                vm.updateConfig { it.copy(includeLocation = on) }
            }
            if (cfg.includeLocation) PermissionRow(
                "Location", "Needed to put a map link in the message. Checked once when SOS is sent. Never tracked in the background.",
                "Messages will say 'Location unavailable' but will still be sent.",
                locOk, Perms.LOCATION,
            ) { vm.refresh++ }
        }

        if (used and Act.CALL != 0) Section("Phone call") {
            if (vm.contacts.isEmpty()) Text("Add a contact first (Trusted contacts).", style = MaterialTheme.typography.bodyLarge)
            else ChoiceGroup("Who to call", vm.contacts, vm.contacts.firstOrNull { it.id == cfg.callContactId }, { it?.name ?: "" }) { c ->
                if (c != null) vm.updateConfig { it.copy(callContactId = c.id) }
            }
            PermissionRow(
                "Phone calls", "Needed to start the call straight away. Used only when SOS is triggered.",
                "The dialer will still open with the number filled in, but someone must press Call.",
                callOk, Perms.CALL,
            ) { vm.refresh++ }
        }

        if (used and Act.ALARM != 0) Section("Alarm sound") {
            Text("Plays the phone's alarm sound at full alarm volume until stopped. The child can stop it with a big button.", style = MaterialTheme.typography.bodyLarge)
        }

        Section("Cancel time for accidents") {
            ChoiceGroup("Seconds before help is sent", Limits.GRACE_SECONDS, cfg.graceSeconds,
                { if (it == 0) "0 - send immediately, no cancel time" else "$it seconds" }) { g ->
                vm.updateConfig { it.copy(graceSeconds = g) }
            }
            Text("During this time the child sees a big Cancel button and a Send-now button. Pick 0 if instant help matters more than avoiding accidents.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
