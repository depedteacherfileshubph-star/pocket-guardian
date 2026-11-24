package com.safetap.sos

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch

@Composable
fun UpdateScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf<java.io.File?>(null) }
    val info = vm.updateAvailable

    ScreenFrame("App updates", { vm.screen = Screen.SETTINGS }) {
        Text("Installed version: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)

        Section("How updates work") {
            Text("This is the only part of Pocket Guardian that uses the internet. It asks an update server whether a newer version exists, only when you press the button (or if you turn on the option below).", style = MaterialTheme.typography.bodyLarge)
            Text("Nothing about you, the child, contacts or location is sent. The update server can see the phone's internet address, like any website.", style = MaterialTheme.typography.bodyMedium)
            Text("Updates never install silently: the file's checksum and signing key are checked, then Android asks you to confirm the install.", style = MaterialTheme.typography.bodyMedium)
        }

        if (!Updater.configured()) {
            StatusBanner("Not set up", "This build has no update source configured, so updates are unavailable.", false)
        } else {
            status?.let { StatusBanner("Update", it, null) }

            if (info == null) {
                BigButton(if (busy) "Checking..." else "Check for updates", {
                    busy = true; status = "Checking for updates..."
                    scope.launch {
                        when (val r = Updater.check()) {
                            is UpdateResult.Available -> { vm.updateAvailable = r.info; status = "Version ${r.info.version} is available." }
                            UpdateResult.UpToDate -> status = "You have the latest version."
                            UpdateResult.NotConfigured -> status = "Updates are not configured."
                            is UpdateResult.Error -> status = r.message
                        }
                        busy = false
                    }
                }, enabled = !busy)
            } else {
                Section("Version ${info.version} is available") {
                    if (info.notes.isNotBlank()) Text(info.notes, style = MaterialTheme.typography.bodyMedium)
                    if (info.sizeBytes > 0) Text("Download size: ${info.sizeBytes / 1024 / 1024 + 1} MB. Mobile data charges may apply.", style = MaterialTheme.typography.bodyMedium)
                    if (ready == null) {
                        BigButton(if (busy) "Downloading..." else "Download update", {
                            busy = true; status = "Downloading..."
                            scope.launch {
                                try {
                                    ready = Updater.download(ctx, info) { p -> status = "Downloading... $p%" }
                                    status = "Downloaded and verified. Tap Install to continue."
                                } catch (e: Exception) {
                                    status = e.message ?: "Download failed."
                                }
                                busy = false
                            }
                        }, enabled = !busy)
                    } else {
                        BigButton("Install update", {
                            val started = Updater.launchInstall(ctx, ready!!)
                            status = if (started) "Android will ask you to confirm the install."
                            else "Android needs your permission first: allow installs from Pocket Guardian, come back, and tap Install again."
                        })
                    }
                }
            }
        }

        ToggleRow(
            "Check for updates when Guardian settings opens",
            "Off by default. Only checks; never downloads or installs by itself.",
            vm.autoCheck,
        ) { on -> vm.autoCheck = on; vm.store.autoUpdateCheck = on }
    }
}
