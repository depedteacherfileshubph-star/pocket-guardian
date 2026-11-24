package com.safetap.sos

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight

/** Guardian-only setup for the optional screen-off volume shortcut. */
@Composable
fun ShortcutScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val cfg = vm.config
    val verified = remember(BgState.testHits, vm.refresh, BgState.running) { vm.store.bgVerified }
    val notifOk = remember(vm.refresh) { Perms.notifications(ctx) }
    val fullScreenOk = remember(vm.refresh) {
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    ScreenFrame("Screen-off shortcut (optional)", { vm.screen = Screen.SETTINGS }) {
        Section("What this does") {
            Text("Lets the volume-button sequence work with the screen off or the phone locked.", style = MaterialTheme.typography.bodyLarge)
            listOf(
                "A permanent notification is shown the whole time it is on, with a Turn off button. It is never hidden.",
                "The phone buzzes twice when the sequence is recognised, so the child can feel it worked.",
                "Then the normal SOS screen opens (over the lock screen) with the same Cancel countdown.",
                "Volume buttons keep changing the volume as normal.",
                "Holding a button down counts as one press.",
                "To receive volume keys with the screen off, Android needs a media player to be 'playing'. The shortcut plays an inaudible silent track for that. Nothing is recorded or sent, and a small media card may appear in your notifications.",
            ).forEach { Text("- $it", style = MaterialTheme.typography.bodyMedium) }
        }
        Section("Honest limits") {
            listOf(
                "Android does not promise this works on every phone. Some makers' phones or battery savers stop it. Pocket Guardian only calls it 'tested' after you pass a real screen-off test below.",
                "It stops when the phone restarts or the system closes the app. Opening Pocket Guardian restarts it; nothing starts by itself in the background.",
                "The Power button is still not supported.",
                "Android 14 and newer may need you to allow 'full-screen notifications', or the SOS screen only appears as a notification to tap.",
            ).forEach { Text("- $it", style = MaterialTheme.typography.bodyMedium) }
        }

        if (!notifOk) PermissionRow(
            "Notifications", "Needed so the permanent shortcut notification and the SOS alert can be shown.",
            "The shortcut cannot be used without a visible notification.", false, Perms.NOTIFY,
        ) { vm.refresh++ }

        ToggleRow(
            "Screen-off shortcut", "Uses a little battery. Keeps a notification showing.", cfg.backgroundMode,
        ) { on ->
            vm.updateConfig { it.copy(backgroundMode = on) }
            vm.store.bgVerified = false
            if (on) ShortcutService.start(ctx) else ShortcutService.stop(ctx)
            vm.refresh++
        }

        if (cfg.backgroundMode) {
            StatusBanner(
                if (verified) "Tested" else "Not tested",
                when {
                    !BgState.running -> "Stopped. Re-open this screen or the app to restart it."
                    verified -> "Passed a screen-off test on this phone."
                    else -> "Running, but not tested on this phone yet."
                },
                if (BgState.running && verified) true else null,
            )

            Section("Is the shortcut receiving your button presses?") {
                Text("Press a volume button now (screen on). The number should go up each time.", style = MaterialTheme.typography.bodyLarge)
                Text("Presses received: ${BgState.keysSeen}", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (BgState.audioActive) "Keep-alive audio: running."
                    else "Keep-alive audio: NOT running. The screen-off shortcut will not work until this says running. Turn the shortcut off and on again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (BgState.running && BgState.keysSeen == 0) {
                    Text("If this stays at 0 after pressing, this phone is not sending volume keys to the shortcut at all. In that case the screen-off shortcut can't work on it; use the SOS button.", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Section("Test it with the screen off (nothing is sent)") {
                Text("1. Tap Start test.", style = MaterialTheme.typography.bodyLarge)
                Text("2. Press the phone's power button to turn the screen off.", style = MaterialTheme.typography.bodyLarge)
                Text("3. Press ${cfg.pattern.label} two or more times quickly, then pause. Two buzzes mean it worked.", style = MaterialTheme.typography.bodyLarge)
                Text("4. Turn the screen on and come back here. You have 60 seconds.", style = MaterialTheme.typography.bodyLarge)
                Text("Test recognitions so far: ${BgState.testHits}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                BigButton("Start 60-second test", { ShortcutService.armTest(ctx) }, enabled = BgState.running)
                Text("No buzz? The phone may not pass volume keys to apps while the screen is off. Turn the shortcut off and use the SOS button instead.", style = MaterialTheme.typography.bodyMedium)
            }

            if (!fullScreenOk) Section("Allow the SOS screen to pop up") {
                Text("Android is blocking full-screen alerts for this app. Allow them so the SOS screen can open by itself.", style = MaterialTheme.typography.bodyLarge)
                BigButton("Open the setting", {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
                })
            }
            Section("Keep it running") {
                Text("Phone makers often close background apps to save battery. Set Pocket Guardian to 'Unrestricted' battery use (Settings > Apps > Pocket Guardian > Battery), or open the list below and choose 'Don't optimise'.", style = MaterialTheme.typography.bodyLarge)
                BigButton("Open battery settings", {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }, outlined = true)
            }
        }
    }
}
