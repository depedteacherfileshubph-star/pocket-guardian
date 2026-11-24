package com.safetap.sos.keygen

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pocket Guardian Key Maker. For the SELLER only, on the seller's own phone.
 * Holds the private licence key, so never install it on a customer's phone or share this APK.
 * Paste the customer's device code, get their activation code, send it to them.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the key out of screenshots, screen recordings and the recent-apps preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(primary = Color(0xFF0B3D91)),
                typography = Typography(
                    titleLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
                    bodyLarge = TextStyle(fontSize = 19.sp, lineHeight = 26.sp),
                    bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
                    labelLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold),
                ),
            ) { KeyMaker() }
        }
    }
}

@Composable
private fun KeyMaker() {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("keymaker", Context.MODE_PRIVATE) }
    var key by remember { mutableStateOf(sp.getString("k", "") ?: "") }
    var keyInput by remember { mutableStateOf("") }
    var device by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val secure = remember { (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure }

    Column(
        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Pocket Guardian Key Maker", style = MaterialTheme.typography.titleLarge)
        Text("For the seller only. Never install this on a customer's phone or send them this app.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF8B0000))

        if (key.isEmpty()) {
            Text("Step 1: save your private licence key on this phone (one time).", style = MaterialTheme.typography.bodyLarge)
            if (!secure) Text("Set a screen lock (PIN, pattern or fingerprint) on this phone first. The key is only as safe as the phone.", style = MaterialTheme.typography.bodyLarge, color = Color(0xFF8B0000))
            Text("Open license_private.b64 on your computer, copy everything in it, and paste it below.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                keyInput, { keyInput = it }, label = { Text("Private key") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val k = keyInput.filter { !it.isWhitespace() }
                    if (Signer.isValidPrivateKey(k)) {
                        sp.edit().putString("k", k).apply(); key = k; keyInput = ""; note = "Key saved on this phone only."
                    } else note = "That isn't a valid key. Copy the whole file contents."
                },
                enabled = secure && keyInput.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
            ) { Text("Save key") }
        } else {
            Text("Customer's device code", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text("They see it on the app's unlock screen, like A1B2-C3D4. Check their payment first.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                device, { device = it.take(12).uppercase(); code = "" }, label = { Text("Device code") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    if (Signer.isValidDeviceCode(device)) {
                        code = Signer.makeCode(key, device); note = null
                    } else { code = ""; note = "The device code must be 8 letters/numbers (0-9, A-F), like A1B2-C3D4." }
                },
                enabled = device.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
            ) { Text("Make activation code") }

            if (code.isNotEmpty()) {
                Text("Activation code for ${device.uppercase()}:", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(code, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                OutlinedButton(
                    onClick = {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Activation code", code))
                        note = "Copied."
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                ) { Text("Copy code") }
                OutlinedButton(
                    onClick = {
                        ctx.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code), "Send the code"
                            )
                        )
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                ) { Text("Share (Messenger, etc.)") }
                Text("The code only works on that one device. It can't be reused on another phone.", style = MaterialTheme.typography.bodyMedium)
            }

            OutlinedButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Remove the key from this phone")
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold) }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the key?") },
            text = { Text("You will need to paste the key again to make codes. Keep your backup copy safe.") },
            confirmButton = { TextButton({ sp.edit().remove("k").apply(); key = ""; code = ""; confirmRemove = false }) { Text("Remove") } },
            dismissButton = { TextButton({ confirmRemove = false }) { Text("Keep") } },
        )
    }
}
