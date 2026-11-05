package dev.dorki.harbor.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.dorki.harbor.HarborViewModel
import dev.dorki.harbor.data.Shortcut
import dev.dorki.harbor.ui.components.LocalBarInset
import dev.dorki.harbor.ui.components.Panel
import dev.dorki.harbor.ui.components.SectionLabel
import dev.dorki.harbor.ui.theme.Palette
import dev.dorki.harbor.ui.theme.Scheme
import dev.dorki.harbor.ui.theme.Schemes

/** App-wide settings: how Harbor looks, alerts, about. Everything about a particular NAS lives under Servers. */
@Composable
fun SettingsScreen(vm: HarborViewModel) {
    val ap by vm.appearance.collectAsState()
    val alerts by vm.alerts.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val context = LocalContext.current
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(
        Modifier
            .background(Palette.Black)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle("Settings", "Harbor")

        SectionLabel("Color scheme")
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 14.dp)) {
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().then(Modifier),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Schemes.choices.take(3).forEach { s -> SchemeSwatch(s, ap.scheme == s.id && !ap.black, Modifier.weight(1f)) { vm.setAppearance(ap.copy(scheme = s.id, black = false)) } }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Schemes.choices.drop(3).forEach { s -> SchemeSwatch(s, ap.scheme == s.id && !ap.black, Modifier.weight(1f)) { vm.setAppearance(ap.copy(scheme = s.id, black = false)) } }
                    SchemeSwatch(Schemes.Mono, ap.black, Modifier.weight(1f)) { vm.setAppearance(ap.copy(black = true)) }
                }
            }
        }

        SectionLabel("Black mode")
        Panel(Modifier.fillMaxWidth()) {
            Column {
                SettingSwitch(
                    "Black mode", "Pure black and white. Frosted glass over what scrolls beneath it, tighter corners. Color stays only for status, graphs and app icons.",
                    ap.black,
                ) { vm.setAppearance(ap.copy(black = it)) }
                if (ap.black) {
                    Hairline()
                    SettingSlider("Blur", if (ap.blur < 0.02f) "Off" else "${(ap.blur * 100).toInt()}%", ap.blur, 0f..1f) { vm.setAppearance(ap.copy(blur = it)) }
                }
                Hairline()
                val r = if (ap.black) ap.blackRadius else ap.radius
                SettingSlider("Corner radius", "$r dp", r.toFloat(), 0f..28f) {
                    val v = it.toInt()
                    vm.setAppearance(if (ap.black) ap.copy(blackRadius = v) else ap.copy(radius = v))
                }
            }
        }

        SectionLabel("Alerts")
        Panel(Modifier.fillMaxWidth()) {
            SettingSwitch(
                "Tell me if a NAS goes offline",
                "Checks every saved NAS about every 15 minutes while you're on its home Wi-Fi, and sends one notification when one stops answering.",
                alerts,
            ) { on ->
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                vm.setAlerts(on)
            }
        }

        SectionLabel("About")
        Panel(Modifier.fillMaxWidth()) {
            Column {
                SettingRow("Harbor", "Version 1.0") {}
                Hairline()
                SettingRow("Saved NAS", "${profiles.size} configured. Manage them under Servers.") {}
            }
        }
        Spacer(Modifier.height(LocalBarInset.current + 16.dp))
    }
}

@Composable
private fun SchemeSwatch(s: Scheme, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = Palette.shape(14.dp)
    Column(
        modifier
            .clip(shape)
            .background(s.bg)
            .border(if (selected) 2.dp else 1.dp, if (selected) Palette.Accent else Palette.Stroke, shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(s.surface3, s.accent, s.lan, s.tailscale).forEach { c ->
                Box(Modifier.size(12.dp).clip(CircleShape).background(c).border(1.dp, s.stroke, CircleShape))
            }
        }
        Text(s.label, style = MaterialTheme.typography.labelMedium, color = s.text, maxLines = 1)
    }
}

@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text, supporting: String? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, placeholder = { Text(placeholder, color = Palette.Faint) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = Palette.shape(14.dp),
    )
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Accent,
    unfocusedBorderColor = Palette.Stroke,
    focusedLabelColor = Palette.Accent,
    cursorColor = Palette.Accent,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
)

@Composable
fun ShortcutDialog(initial: Shortcut?, onDismiss: () -> Unit, onSave: (Shortcut) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "") }
    var path by remember { mutableStateOf(initial?.path ?: "/") }
    var https by remember { mutableStateOf(initial?.https ?: false) }
    val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface2,
        title = { Text(if (initial == null) "Add shortcut" else "Edit shortcut") },
        text = {
            dev.dorki.harbor.ui.components.GlassBehind()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Name", name, { name = it }, "Jellyfin")
                Field("Port", port, { port = it.filter(Char::isDigit).take(5) }, "8080", KeyboardType.Number)
                Field("Path", path, { path = it }, "/")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use HTTPS", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(https, { https = it }, colors = switchColors())
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && portNum != null,
                onClick = {
                    val p = path.trim().ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" }
                    onSave((initial ?: Shortcut(name = name, port = 0)).copy(name = name.trim(), port = portNum!!, path = p, https = https))
                },
            ) { Text("Save", color = Palette.Accent) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Palette.Muted) } },
    )
}
