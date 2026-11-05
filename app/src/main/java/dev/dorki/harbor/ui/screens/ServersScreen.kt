package dev.dorki.harbor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dorki.harbor.HarborViewModel
import dev.dorki.harbor.backend.fullHost
import dev.dorki.harbor.data.NasKind
import dev.dorki.harbor.data.ServerConfig
import dev.dorki.harbor.data.Shortcut
import dev.dorki.harbor.net.ProbeResult
import dev.dorki.harbor.ui.components.Dot
import dev.dorki.harbor.ui.components.LocalBarInset
import dev.dorki.harbor.ui.components.Panel
import dev.dorki.harbor.ui.components.Pill
import dev.dorki.harbor.ui.components.SectionLabel
import dev.dorki.harbor.ui.theme.Palette
import kotlinx.coroutines.launch

/** Every NAS Harbor knows about. Tap one to make it the active one. */
@Composable
fun ServersScreen(vm: HarborViewModel, onOpen: (ServerConfig) -> Unit, onEdit: (String?) -> Unit, onAdd: () -> Unit) {
    val profiles by vm.profiles.collectAsState()
    val active by vm.store.activeId.collectAsState()
    val status by vm.status.collectAsState()
    LaunchedEffect(profiles.size) { vm.checkStatus() }

    Column(
        Modifier.background(Palette.Black).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle("Servers", "${profiles.size} saved")
        profiles.forEach { c ->
            ServerCard(c, c.id == active, status[c.id], onClick = { onOpen(c) }, onEdit = { onEdit(c.id) })
        }
        AddCard(onAdd)
        Text(
            "Each NAS keeps its own addresses, sign-in, certificate and shortcuts. Harbor shows one at a time; tap a server to switch to it.",
            style = MaterialTheme.typography.bodySmall, color = Palette.Faint, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(LocalBarInset.current + 16.dp))
    }
}

@Composable
private fun ServerCard(c: ServerConfig, active: Boolean, up: Boolean?, onClick: () -> Unit, onEdit: () -> Unit) {
    val shape = Palette.shape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(Palette.Surface)
            .border(1.dp, if (active) Palette.Accent.copy(alpha = 0.7f) else Palette.Stroke, shape)
            .clickable(onClick = onClick).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(
            when (up) { true -> Palette.Good; false -> Palette.Offline; null -> Palette.Faint },
            Modifier.padding(end = 14.dp), size = 10.dp,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                if (active) Pill("Showing", Palette.Accent, Modifier.padding(start = 8.dp))
            }
            Text(
                listOf(c.kind.label, c.lanHost.ifBlank { c.tailscaleHost }).filter { it.isNotBlank() }.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontFamily = if (Palette.black) FontFamily.Monospace else null,
            )
            Text(
                when (up) { true -> "Online"; false -> "Not answering"; null -> "Checking…" },
                style = MaterialTheme.typography.bodySmall, color = when (up) { true -> Palette.Good; false -> Palette.Offline; null -> Palette.Faint },
            )
        }
        IconButton(onEdit) { Icon(Icons.Rounded.Edit, "Edit ${c.title}", tint = Palette.Muted) }
    }
}

@Composable
private fun AddCard(onClick: () -> Unit) {
    val shape = Palette.shape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, Palette.Stroke, shape).clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, null, tint = Palette.Text)
        Spacer(Modifier.width(12.dp))
        Text("Add a NAS", style = MaterialTheme.typography.titleSmall)
    }
}

/** Adds a NAS (id = null) or edits one. [firstRun] shows the welcome header and no back button. */
@Composable
fun NasEditor(vm: HarborViewModel, id: String?, firstRun: Boolean, onClose: () -> Unit) {
    val existing = remember(id) { vm.profiles.value.firstOrNull { it.id == id } }
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(existing?.kind ?: NasKind.TRUENAS) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var lan by remember { mutableStateOf(existing?.lanHost ?: "") }
    var ts by remember { mutableStateOf(existing?.tailscaleHost ?: "") }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var secret by remember { mutableStateOf(existing?.apiKey ?: "") }
    var https by remember { mutableStateOf(existing?.https ?: kind.defaultHttps) }
    var preferLan by remember { mutableStateOf(existing?.preferLan ?: true) }
    var showSecret by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Triple<ProbeResult?, ProbeResult?, String?>?>(null) }
    var shortcuts by remember { mutableStateOf(existing?.let { vm.store.shortcutsOf(it.id) } ?: emptyList()) }
    var editing by remember { mutableStateOf<Shortcut?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val base = existing ?: remember { ServerConfig() }

    fun draft() = base.copy(kind = kind, name = name, lanHost = lan, tailscaleHost = ts, username = user, apiKey = secret, https = https, preferLan = preferLan)

    Column(
        Modifier.fillMaxSize().background(Palette.Black).verticalScroll(rememberScrollState())
            .statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (firstRun) {
            Spacer(Modifier.height(24.dp))
            Text("Welcome to Harbor", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = 4.dp))
            Text(
                "Your NAS at home and away. Harbor connects over your home network and switches to Tailscale when you leave. Add as many NAS boxes as you like and switch between them.",
                style = MaterialTheme.typography.bodyLarge, color = Palette.Muted, modifier = Modifier.padding(horizontal = 4.dp),
            )
        } else {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text(if (existing == null) "Add a NAS" else existing.title, style = MaterialTheme.typography.titleLarge)
            }
        }

        SectionLabel("Software")
        Panel(Modifier.fillMaxWidth()) {
            Column {
                NasKind.entries.forEachIndexed { i, k ->
                    if (i > 0) Hairline()
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (k != kind) { kind = k; https = k.defaultHttps; results = null }
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(18.dp).clip(CircleShape).border(1.5.dp, if (k == kind) Palette.Accent else Palette.Faint, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (k == kind) Box(Modifier.size(9.dp).clip(CircleShape).background(Palette.Accent)) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(k.label, style = MaterialTheme.typography.titleSmall)
                            Text(k.blurb, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                        }
                        if (k.experimental) Text("BETA", style = MaterialTheme.typography.labelSmall, color = Palette.Searching)
                    }
                }
            }
        }

        SectionLabel("Connection")
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Name", name, { name = it }, kind.label)
                Field("Home network address", lan, { lan = it }, "192.168.1.50", KeyboardType.Uri, supporting = "Used when your VPN is off" + (kind.defaultPort(https)?.let { ". Port $it is added if you leave it out" } ?: ""))
                Field("Tailscale address", ts, { ts = it }, "my-nas or 100.x.y.z", KeyboardType.Uri, supporting = "MagicDNS name or 100.x IP; used when Tailscale is on")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use HTTPS", style = MaterialTheme.typography.titleSmall)
                        Text("Pins the NAS's certificate the first time it answers. Off sends everything unencrypted.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    }
                    androidx.compose.material3.Switch(https, { https = it }, colors = switchColors())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Prefer home network", style = MaterialTheme.typography.titleSmall)
                        Text("With Tailscale on at home, use the faster direct route", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    }
                    androidx.compose.material3.Switch(preferLan, { preferLan = it }, colors = switchColors())
                }
            }
        }

        if (!kind.generic) {
            SectionLabel("Sign-in · optional")
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (kind.usesUser) Field("Username", user, { user = it }, "admin")
                    OutlinedTextField(
                        value = secret, onValueChange = { secret = it.trim() },
                        label = { Text(kind.secretLabel) }, singleLine = true,
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton({ showSecret = !showSecret }) {
                                Icon(if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show")
                            }
                        },
                        colors = fieldColors(), modifier = Modifier.fillMaxWidth(), shape = Palette.shape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Text(signInHelp(kind), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    testing = true
                    results = null
                    scope.launch {
                        val d = draft()
                        val (l, t) = vm.test(d)
                        val host = listOf(l to d.lanHost, t to d.tailscaleHost).firstOrNull { it.first?.ok == true }?.second
                        val login = if (host != null && d.hasLogin && !d.kind.generic) vm.testLogin(d, fullHost(d, host)) ?: "ok" else null
                        results = Triple(l, t, login)
                        testing = false
                    }
                },
                enabled = !testing && (lan.isNotBlank() || ts.isNotBlank()),
                shape = Palette.shape(14.dp),
            ) {
                if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Text)
                else Text("Test", color = Palette.Text)
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    val d = draft()
                    vm.saveConfig(d)
                    if (existing != null || shortcuts.isNotEmpty()) vm.store.saveShortcutsOf(d.id, shortcuts)
                    onClose()
                },
                enabled = lan.isNotBlank() || ts.isNotBlank(),
                colors = ButtonDefaults.buttonColors(Palette.Accent, Palette.OnAccent),
                shape = Palette.shape(14.dp),
            ) { Text(if (existing == null) "Add" else "Save") }
        }
        androidx.compose.animation.AnimatedVisibility(results != null) {
            val r = results
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TestRow("Home network", r?.first, lan.isBlank(), kind)
                    TestRow("Tailscale", r?.second, ts.isBlank(), kind, "Is Tailscale turned on?")
                    r?.third?.let { login ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (login == "ok") Icons.Rounded.CheckCircle else Icons.Rounded.Error, null, tint = if (login == "ok") Palette.Good else Palette.Offline, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Sign-in", style = MaterialTheme.typography.titleSmall)
                                Text(if (login == "ok") "Accepted" else login, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                            }
                        }
                    }
                }
            }
        }

        if (existing != null || kind != NasKind.OTHER || true) {
            SectionLabel("Shortcuts")
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    shortcuts.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { editing = s }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleSmall)
                                Text("${if (s.https) "https" else "http"} · port ${s.port}${s.path.takeIf { it != "/" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                            }
                            IconButton({ editing = s }) { Icon(Icons.Rounded.Edit, "Edit", tint = Palette.Muted) }
                            IconButton({ shortcuts = shortcuts.filterNot { it.id == s.id } }) { Icon(Icons.Rounded.Delete, "Remove", tint = Palette.Muted) }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { adding = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Palette.Accent)
                        Spacer(Modifier.width(12.dp))
                        Text("Add shortcut", color = Palette.Accent, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            Text(
                "A shortcut points at a port on whichever address is live. Apps found through the sign-in show up on their own.",
                style = MaterialTheme.typography.bodySmall, color = Palette.Faint, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        if (existing != null) {
            SectionLabel("Security")
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pinned certificate", style = MaterialTheme.typography.titleSmall)
                    Text(
                        existing.pin?.let { it.take(47) + "…" } ?: if (existing.https) "Not pinned yet: recorded the first time this NAS answers." else "Not used: this NAS is set to plain HTTP.",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = Palette.Muted,
                    )
                    if (existing.pin != null) {
                        TextButton(
                            onClick = { vm.store.setPin(existing.id, null); if (vm.store.activeId.value == existing.id) vm.resolve(force = true); onClose() },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        ) { Text("Forget and re-pin (after changing the NAS certificate)", color = Palette.Offline) }
                    }
                }
            }
            TextButton(onClick = { confirmDelete = true }) { Text("Remove ${existing.title} from Harbor", color = Palette.Offline) }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (adding || editing != null) {
        ShortcutDialog(
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { s -> shortcuts = if (shortcuts.any { it.id == s.id }) shortcuts.map { if (it.id == s.id) s else it } else shortcuts + s; adding = false; editing = null },
        )
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Palette.Surface2,
            title = { Text("Remove ${existing.title}?") },
            text = { Text("Harbor forgets its addresses, sign-in and shortcuts. Nothing changes on the NAS.", color = Palette.Muted) },
            confirmButton = { TextButton({ confirmDelete = false; vm.removeProfile(existing.id); onClose() }) { Text("Remove", color = Palette.Offline) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel", color = Palette.Muted) } },
        )
    }
}

private fun signInHelp(kind: NasKind) = when (kind) {
    NasKind.TRUENAS -> "In TrueNAS, open the user menu (top right) → API Keys → Add. The key unlocks live CPU, memory, pools, apps and files. It's stored encrypted on this phone."
    NasKind.SYNOLOGY -> "A DSM account. Admin accounts give full stats and power controls; any account with File Station access can browse files. Accounts with 2-step sign-in aren't supported yet."
    NasKind.OMV -> "Your OpenMediaVault web admin account (usually admin). Used for stats, drives and restart/shut down."
    NasKind.UNRAID -> "Unraid 7.2+: Settings → Management Access → API Keys → create a key with read access. Shows the array, drives and Docker containers."
    NasKind.UMBREL -> "The password you use for the Umbrel dashboard. Accounts with two-factor sign-in aren't supported yet. Shows apps, CPU, memory and storage; apps can be started, stopped, restarted and updated."
    NasKind.ZIMAOS, NasKind.CASAOS -> "The account you use for the ${kind.label} dashboard. Shows apps, CPU, memory and storage, and lets you browse and download files."
    NasKind.QNAP -> "A QNAP admin account. Accounts with 2-step verification aren't supported. Shows CPU, memory and uptime, plus restart and shut down."
    NasKind.PROXMOX -> "A Proxmox account such as root@pam (@pam is added if you leave it out). VMs and containers show up as apps you can start and stop."
    else -> ""
}

@Composable
private fun TestRow(label: String, r: ProbeResult?, blank: Boolean, kind: NasKind, failHint: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (blank) Box(Modifier.size(20.dp).clip(CircleShape).border(1.dp, Palette.Faint, CircleShape))
        else Icon(if (r?.ok == true) Icons.Rounded.CheckCircle else Icons.Rounded.Error, null, tint = if (r?.ok == true) Palette.Good else Palette.Offline, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    blank -> "Not configured"
                    r == null -> "—"
                    r.ok -> "${kind.label} answered in ${r.latencyMs} ms"
                    else -> listOfNotNull(r.error, failHint).joinToString(" · ")
                },
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
