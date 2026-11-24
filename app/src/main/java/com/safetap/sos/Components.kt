package com.safetap.sos

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    outlined: Boolean = false,
) {
    val m = modifier.fillMaxWidth().heightIn(min = 64.dp)
    val shape = RoundedCornerShape(16.dp)
    if (outlined) {
        OutlinedButton(onClick, m, enabled, shape = shape, border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)) {
            Text(text, color = MaterialTheme.colorScheme.onSurface)
        }
    } else {
        val colors = if (danger) ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError
        ) else ButtonDefaults.buttonColors()
        Button(onClick, m, enabled, shape = shape, colors = colors) { Text(text) }
    }
}

/** Title row + scrollable body. Back is a large labelled button (not just an arrow icon). */
@Composable
fun ScreenFrame(title: String, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(
        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (onBack != null) BigButton("< Back", onBack, outlined = true)
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** Status is conveyed by a word AND a colour, never colour alone. */
@Composable
fun StatusBanner(kind: String, text: String, good: Boolean?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (good) {
        true -> cs.secondary to cs.onSecondary
        false -> cs.error to cs.onError
        null -> cs.primary to cs.onPrimary
    }
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .then(Modifier.padding(0.dp)),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp).fillMaxWidth()) {
                Text(kind.uppercase(), style = MaterialTheme.typography.labelLarge)
                Text(text, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun ToggleRow(label: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium)
        }
        // The Row is the single accessibility node; the Switch is visual only.
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
fun <T> ChoiceGroup(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        options.forEach { o ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = o == selected, role = Role.RadioButton, onClick = { onSelect(o) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = o == selected, onClick = null, modifier = Modifier.clearAndSetSemantics {})
                Text(label(o), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(
                onClick = { onChange((value - 1).coerceAtLeast(min)) }, enabled = value > min,
                modifier = Modifier.size(64.dp).semantics { contentDescription = "Fewer presses" },
            ) { Text("-") }
            Text("$value", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { contentDescription = "$value presses" })
            OutlinedButton(
                onClick = { onChange((value + 1).coerceAtMost(max)) }, enabled = value < max,
                modifier = Modifier.size(64.dp).semantics { contentDescription = "More presses" },
            ) { Text("+") }
        }
    }
}

/**
 * Asks for a permission IN CONTEXT with a plain-language reason, and shows a way forward if the
 * guardian says no (feature keeps working in a reduced way; see [fallback]).
 */
@Composable
fun PermissionRow(
    title: String,
    why: String,
    fallback: String,
    granted: Boolean,
    permissions: Array<String>,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        denied = r.values.none { it }
        onChanged()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$title: ${if (granted) "Allowed" else "Not allowed"}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        Text(why, style = MaterialTheme.typography.bodyMedium)
        if (!granted) {
            if (denied) Text("Permission was declined. $fallback", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            BigButton("Allow $title", { launcher.launch(permissions) })
            if (denied) BigButton("Open phone settings", {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            }, outlined = true)
        }
    }
}
