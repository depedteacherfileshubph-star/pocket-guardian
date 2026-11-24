package com.safetap.sos

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.UUID

// ---------------------------------------------------------------- Trigger setup + test

@Composable
fun TriggerScreen(vm: AppViewModel) {
    val cfg = vm.config
    ScreenFrame("Trigger: choose and test", { vm.screen = Screen.SETTINGS }) {
        Section("How it works") {
            Text("1. Pick the button pattern.", style = MaterialTheme.typography.bodyLarge)
            Text("2. Pick how many presses, and how quickly they must happen.", style = MaterialTheme.typography.bodyLarge)
            Text("3. Practice in the test box below. Nothing is sent during the test.", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Important: Android only lets an ordinary app see volume buttons while the app is open on screen " +
                    "and the phone is awake. It will NOT work with the screen off, on the lock screen, or in other apps. " +
                    "The Power button cannot be used. The big SOS button and the Quick Settings tile work as backups.",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            )
        }

        Section("Choose the pattern") {
            ChoiceGroup("Buttons", PatternType.entries, cfg.pattern, { it.label + " - " + it.help }) { p ->
                vm.updateConfig { it.copy(pattern = p) }
            }
            Stepper("Number of presses", cfg.pressCount, Limits.MIN_PRESSES, Limits.MAX_PRESSES) { n ->
                vm.updateConfig { it.copy(pressCount = n) }
            }
            ChoiceGroup("Time window (all presses must fit inside)", Limits.WINDOWS_MS, cfg.windowMs, { "${it / 1000} seconds" }) { w ->
                vm.updateConfig { it.copy(windowMs = w) }
            }
            Text("Tip: 5 presses in 3 seconds is hard to do by accident but easy on purpose. Fewer presses or longer windows cause more accidents.", style = MaterialTheme.typography.bodyMedium)
            ToggleRow(
                "Keep screen on while the child screen is open",
                "Helps the shortcut keep working. Uses more battery. Turns off when the app is closed.",
                cfg.keepScreenOn,
            ) { on -> vm.updateConfig { it.copy(keepScreenOn = on) } }
        }

        Section("Test it (no message, no call)") {
            Text("Press the volume buttons now: ${cfg.pattern.label}, ${cfg.pressCount} times within ${cfg.windowMs / 1000} seconds.", style = MaterialTheme.typography.bodyLarge)
            Text("Last button seen: ${vm.lastKey}", style = MaterialTheme.typography.bodyLarge)
            Text("Presses counted in window: ${vm.testProgress} of ${cfg.pressCount}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            if (vm.testHits > 0) StatusBanner("Test passed", "Trigger recognised ${vm.testHits} time(s). Nothing was sent.", true)
            else StatusBanner("Waiting", "No trigger recognised yet.", null)
            Text("If no presses are counted, your phone may not report volume keys to apps on this screen. Use the on-screen SOS button instead and see 'Privacy and device limits'.", style = MaterialTheme.typography.bodyMedium)
            BigButton("Reset test", { vm.resetTest() }, outlined = true)
        }
    }
}

// ---------------------------------------------------------------- SOS actions

@Composable
fun ActionsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val cfg = vm.config
    // Re-read permission state after any permission result.
    val smsOk = remember(vm.refresh) { Perms.sms(ctx) }
    val locOk = remember(vm.refresh) { Perms.location(ctx) }
    val callOk = remember(vm.refresh) { Perms.call(ctx) }

    ScreenFrame("SOS actions", { vm.screen = Screen.SETTINGS }) {
        Section("Send a text message (SMS)") {
            ToggleRow("Send SMS to trusted contacts", "Uses the phone's SIM. Carrier SMS charges may apply.", cfg.sendSms) { on ->
                vm.updateConfig { it.copy(sendSms = on) }
            }
            if (cfg.sendSms) {
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
        }

        Section("Place a phone call") {
            ToggleRow("Call one trusted contact", null, cfg.placeCall) { on -> vm.updateConfig { it.copy(placeCall = on) } }
            if (cfg.placeCall) {
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
        }

        Section("Alarm sound") {
            ToggleRow("Play a loud alarm", "Uses the phone's alarm sound at full alarm volume until stopped. The child can stop it with a big button.", cfg.alarm) { on ->
                vm.updateConfig { it.copy(alarm = on) }
            }
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

// ---------------------------------------------------------------- Contacts + tests

@Composable
fun ContactsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val engine = remember { SosEngine(ctx) { _, t -> status = t } }
    var editing by remember { mutableStateOf<Contact?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Contact?>(null) }
    var confirmSend by remember { mutableStateOf<Contact?>(null) }
    var pending by remember { mutableStateOf(setOf<String>()) } // test sent/started, waiting for guardian to confirm
    var busy by remember { mutableStateOf(false) }
    val smsOk = remember(vm.refresh) { Perms.sms(ctx) }

    ScreenFrame("Trusted contacts", { vm.screen = Screen.SETTINGS }) {
        Text("Only these people are contacted. Each one should be tested so you know SOS will reach them. Tests only run when you press a test button.", style = MaterialTheme.typography.bodyLarge)
        if (!smsOk) PermissionRow(
            "SMS", "Needed to send the test message and help messages.", "You can still test by calling, but SOS messages cannot be sent.",
            false, Perms.SMS,
        ) { vm.refresh++ }
        status?.let { StatusBanner("Last result", it, null) }

        vm.contacts.forEach { c ->
            Section(c.name) {
                Text(c.phone, style = MaterialTheme.typography.bodyLarge)
                Text(if (c.verified) "Tested and confirmed" else "Not tested yet", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                BigButton("Send a test message", { confirmSend = c }, enabled = !busy && smsOk)
                BigButton("Test call (opens the dialer)", {
                    ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", c.phone, null)))
                    pending = pending + c.id
                    status = "Dialer opened for ${c.name}. Press Call yourself, then confirm below."
                }, outlined = true)
                if (c.id in pending && !c.verified) {
                    Text("Did ${c.name} receive the test message or call?", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    BigButton("Yes, ${c.name} confirmed", {
                        vm.updateContacts(vm.contacts.map { if (it.id == c.id) it.copy(verified = true) else it })
                        pending = pending - c.id
                    })
                }
                if (c.verified) BigButton("Mark as not tested", {
                    vm.updateContacts(vm.contacts.map { if (it.id == c.id) it.copy(verified = false) else it })
                }, outlined = true)
                BigButton("Edit", { editing = c }, outlined = true)
                BigButton("Remove", { removing = c }, outlined = true)
            }
        }
        if (vm.contacts.size < Limits.MAX_CONTACTS) BigButton("Add a contact", { adding = true })
        else Text("Maximum of ${Limits.MAX_CONTACTS} contacts reached.", style = MaterialTheme.typography.bodyLarge)
    }

    if (adding || editing != null) {
        val base = editing
        ContactDialog(base, onDismiss = { adding = false; editing = null }) { name, phone ->
            if (base == null) vm.updateContacts(vm.contacts + Contact(UUID.randomUUID().toString(), name, phone))
            else vm.updateContacts(vm.contacts.map {
                if (it.id == base.id) it.copy(name = name, phone = phone, verified = it.verified && it.phone == phone) else it
            })
            adding = false; editing = null
        }
    }
    removing?.let { c ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${c.name}?") },
            text = { Text("They will no longer be contacted by SOS.", style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton({ vm.updateContacts(vm.contacts.filter { it.id != c.id }); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton({ removing = null }) { Text("Keep") } },
        )
    }
    confirmSend?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmSend = null },
            title = { Text("Send a test SMS to ${c.name}?") },
            text = { Text("This sends one real text message that says it is only a test. Carrier charges may apply. It does not call anyone or share location.", style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton({
                    confirmSend = null
                    busy = true
                    status = "Sending test message to ${c.name}..."
                    scope.launch {
                        val r = engine.sendSms(c, "Pocket Guardian TEST: this is only a test message, not an emergency. You were added as a trusted contact.")
                        status = if (r.ok) "Test message sent to ${c.name}. Ask them to confirm they received it." else "Test message failed: ${r.reason}. Check signal, SIM and airplane mode."
                        if (r.ok) pending = pending + c.id
                        busy = false
                    }
                }) { Text("Send test") }
            },
            dismissButton = { TextButton({ confirmSend = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ContactDialog(existing: Contact?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add contact" else "Edit contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(30) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    phone, { phone = it.take(20) }, label = { Text("Phone number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                Text("Include the country code if the contact is abroad, e.g. +44...", style = MaterialTheme.typography.bodyMedium)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            }
        },
        confirmButton = {
            TextButton({
                when {
                    name.isBlank() -> error = "Enter a name."
                    !isPlausiblePhone(phone) -> error = "Enter a valid phone number."
                    else -> onSave(name.trim(), phone.trim())
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- Permissions explained

@Composable
fun PermissionsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val sms = remember(vm.refresh) { Perms.sms(ctx) }
    val loc = remember(vm.refresh) { Perms.location(ctx) }
    val call = remember(vm.refresh) { Perms.call(ctx) }
    ScreenFrame("Permissions explained", { vm.screen = Screen.SETTINGS }) {
        Text("Pocket Guardian asks only for what the chosen SOS actions need, and only when you turn them on. You can say no; the app will explain what stops working.", style = MaterialTheme.typography.bodyLarge)
        Section("Send SMS") {
            PermissionRow("SMS", "Lets the app send the help message and tests from this phone's SIM.", "SOS messages can't be sent.", sms, Perms.SMS) { vm.refresh++ }
        }
        Section("Location") {
            PermissionRow("Location", "Lets the app add a map link to the help message. Checked once when SOS is sent; never continuously.", "Messages say location is unavailable.", loc, Perms.LOCATION) { vm.refresh++ }
        }
        Section("Phone calls") {
            PermissionRow("Phone calls", "Lets the app start the SOS call immediately.", "The dialer opens and someone must press Call.", call, Perms.CALL) { vm.refresh++ }
        }
        Section("Never used") {
            Text("Microphone, camera, contacts list, call log, photos, files, or background location. The app records no audio and has no hidden mode.", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// ---------------------------------------------------------------- Privacy + limitations

@Composable
fun AboutScreen(vm: AppViewModel) {
    ScreenFrame("Privacy and device limits", { vm.screen = Screen.SETTINGS }) {
        Section("Privacy") {
            listOf(
                "Stored on this phone only: your settings, trusted contacts, and a scrambled (hashed) PIN. Nothing is uploaded and there are no accounts, ads or analytics. The only internet use is the optional update check/download from GitHub (App updates), which sends no personal data.",
                "Location: read once when SOS is sent, put into the message, then discarded. Never tracked, never stored.",
                "No audio is recorded. The app is never hidden: it has a normal icon, a visible SOS screen, and a visible countdown.",
                "Messages go through the phone's normal SMS system, so your mobile carrier can see them like any other text.",
            ).forEach { Text("- $it", style = MaterialTheme.typography.bodyLarge) }
        }
        Section("What works where") {
            listOf(
                "Volume shortcut: works while Pocket Guardian's main screen is open and the phone is awake (Android 8 to 15). Real-device test needed per phone model.",
                "Volume shortcut with the screen off: only if a guardian turns on the optional Screen-off shortcut (visible notification) AND it passes the screen-off test on this phone. Otherwise it does not work with the screen off or in other apps.",
                "Power button: not supported. Android restricts it and it differs by phone.",
                "On-screen SOS button: works whenever the app is open (also over the lock screen when started from the app).",
                "Quick Settings tile: optional backup. Add it yourself: swipe down twice, tap Edit (pencil), drag 'Pocket Guardian' in. On a locked phone Android usually asks to unlock first. Real-device test needed.",
                "SMS and calls need a working SIM, signal and no Airplane mode. Phones without a SIM (most tablets) can't send SOS.",
                "A 'sent' status means the phone handed the message to the network, not that the person read it.",
            ).forEach { Text("- $it", style = MaterialTheme.typography.bodyLarge) }
        }
        Section("Good habits") {
            Text("Re-run the contact tests now and then, keep the phone charged, and make sure Do Not Disturb or battery savers aren't stopping Pocket Guardian. Never rely on Pocket Guardian alone: teach the child to call the local emergency number too.", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
