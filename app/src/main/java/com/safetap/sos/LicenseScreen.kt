package com.safetap.sos

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Trial status, one-time payment QR, and activation. Reachable by the guardian even after the trial ends. */
@Composable
fun LicenseScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val state = remember(vm.refresh, vm.licenseTick) { License.state(ctx, vm.store) }
    val deviceCode = remember { License.deviceCode(ctx) }
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    ScreenFrame("Unlock Pocket Guardian", { vm.screen = Screen.SETTINGS }) {
        when (state) {
            LicenseState.Licensed -> StatusBanner("Activated", "Thank you. All features are unlocked on this phone.", true)
            is LicenseState.Trial -> StatusBanner("Free trial", "${state.daysLeft} day(s) left. Pay once to keep guardian features after that.", null)
            LicenseState.Expired -> StatusBanner("Trial ended", "Guardian settings are locked until you activate. The child's SOS button keeps working.", false)
        }

        if (state != LicenseState.Licensed) {
            Section("What stays free, always") {
                Text("The child's SOS button, and the volume shortcut with the settings already saved, keep working after the trial. Safety is never locked.", style = MaterialTheme.typography.bodyLarge)
                Text("After the trial, the following need the one-time activation: changing settings and contacts, the screen-off shortcut, and app updates.", style = MaterialTheme.typography.bodyMedium)
            }

            Section("1. Your device code") {
                Text(deviceCode, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("Send this code with your payment proof. Your activation code works only on this phone.", style = MaterialTheme.typography.bodyMedium)
                BigButton("Copy device code", { copy(ctx, "Device code", deviceCode); message = "Device code copied." }, outlined = true)
            }

            Section("2. Pay once") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.drawable.payment_qr),
                        contentDescription = "Payment QR code. Scan it with your bank or e-wallet app.",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(300.dp).background(Color.White).padding(10.dp),
                    )
                }
                Text("Scan with your bank or e-wallet app (InstaPay QR). Transfer fees may apply.", style = MaterialTheme.typography.bodyLarge)
                if (BuildConfig.LICENSE_PRICE.isNotBlank()) {
                    Text("Amount: ${BuildConfig.LICENSE_PRICE}", style = MaterialTheme.typography.titleMedium)
                } else {
                    Text("Ask for the amount when you send your proof of payment.", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Section("3. Send your proof of payment") {
                Text("Send a screenshot of your payment and your device code on Messenger.", style = MaterialTheme.typography.bodyLarge)
                BigButton("Send proof on Messenger", {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(License.MESSENGER_URL))) }
                        .onFailure { message = "Couldn't open Messenger. Open ${License.MESSENGER_URL} in a browser." }
                })
            }

            Section("4. Enter your activation code") {
                Text("You will receive a long code after your payment is confirmed. Paste it here.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    code, { code = it.take(300) }, label = { Text("Activation code") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                )
                BigButton("Paste from clipboard", {
                    val t = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    if (t.isNullOrBlank()) message = "The clipboard is empty." else code = t.trim()
                }, outlined = true)
                BigButton("Activate", {
                    if (License.activate(ctx, vm.store, code)) {
                        message = "Activated. Thank you!"
                        vm.licenseTick++
                        vm.refresh++
                    } else message = "That code is not valid for this phone. Check it was copied completely and matches this device code."
                }, enabled = code.isNotBlank())
            }
        }
        message?.let { StatusBanner("Note", it, null) }
    }
}

private fun copy(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
}
