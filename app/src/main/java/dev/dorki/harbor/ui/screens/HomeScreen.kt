package dev.dorki.harbor.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SignalWifiOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dorki.harbor.ApiState
import dev.dorki.harbor.Disk
import dev.dorki.harbor.LogView
import dev.dorki.harbor.NetSample
import dev.dorki.harbor.SnapView
import dev.dorki.harbor.AppState
import dev.dorki.harbor.Conn
import dev.dorki.harbor.HarborViewModel
import dev.dorki.harbor.NasStats
import dev.dorki.harbor.OfflineReason
import dev.dorki.harbor.Route
import dev.dorki.harbor.Tile
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.FolderShared
import dev.dorki.harbor.Share
import dev.dorki.harbor.TileKind
import dev.dorki.harbor.data.ServerConfig
import dev.dorki.harbor.data.Shortcut
import dev.dorki.harbor.net.NetState
import dev.dorki.harbor.net.ProbeResult
import dev.dorki.harbor.ui.components.AppIcon
import dev.dorki.harbor.ui.components.Bar
import dev.dorki.harbor.ui.components.Dot
import dev.dorki.harbor.ui.components.Panel
import dev.dorki.harbor.ui.components.Pill
import dev.dorki.harbor.ui.components.Ring
import dev.dorki.harbor.ui.components.SectionLabel
import dev.dorki.harbor.ui.components.StatusOrb
import dev.dorki.harbor.ui.components.formatBytes
import dev.dorki.harbor.ui.components.formatUptime
import dev.dorki.harbor.ui.theme.Palette
import dev.dorki.harbor.backend.Caps
import dev.dorki.harbor.backend.backendFor
import dev.dorki.harbor.data.NasKind
import dev.dorki.harbor.ui.components.LocalBarInset
import androidx.compose.material.icons.rounded.UnfoldMore
import kotlinx.coroutines.launch

fun routeColor(conn: Conn): Color = when (conn) {
    is Conn.Online -> if (conn.route == Route.LAN) Palette.Lan else Palette.Tailscale
    is Conn.Resolving -> Palette.Searching
    is Conn.Offline -> Palette.Offline
    Conn.Setup -> Palette.Muted
}

fun routeLabel(route: Route) = if (route == Route.LAN) "Home network" else "Tailscale"

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HarborViewModel,
    onOpen: (Tile) -> Unit,
    onSettings: () -> Unit,
    onSwitch: () -> Unit = {},
    gridState: LazyGridState = rememberLazyGridState(),
    onOpenShare: (Share) -> Unit = {},
) {
    val shares by vm.files.shares.collectAsStateWithLifecycle()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()
    val net by vm.net.collectAsStateWithLifecycle()
    val probes by vm.probes.collectAsStateWithLifecycle()
    val api by vm.api.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val tiles by vm.tiles.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val snaps by vm.snaps.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val caps = remember(cfg.kind) { backendFor(cfg.kind).caps }
    val barInset = LocalBarInset.current

    var sheetTile by remember { mutableStateOf<Tile?>(null) }
    var editing by remember { mutableStateOf<Shortcut?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmPower by remember { mutableStateOf<String?>(null) }

    val online = conn is Conn.Online || (conn as? Conn.Resolving)?.prev != null
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 8.dp, bottom = bottom + barInset + 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            full("header") { Header(cfg, stats, conn, profiles.size > 1, onSwitch) }
            full("hero") {
                StatusHero(
                    conn, net, cfg, probes,
                    onRetry = vm::refresh,
                    onTailscale = { openTailscale(context) },
                    onSettings = onSettings,
                )
            }
            when (val a = api) {
                ApiState.Ready, ApiState.Connecting -> if (online) {
                    if (caps.stats) full("stats") { StatsRow(stats, a == ApiState.Connecting) }
                    if (stats.net.isNotEmpty()) full("net") { NetPanel(stats.net) }
                    if (stats.pools.isNotEmpty()) full("pools") { PoolsPanel(stats) }
                    if (stats.disks.isNotEmpty()) full("disks") { DisksPanel(stats.disks) }
                    if (a == ApiState.Ready && shares.isNotEmpty()) {
                        full("files-label") { SectionLabel("Files", trailing = "${shares.size} shares") }
                        full("files") { SharesPanel(shares, onOpenShare) }
                    }
                }
                is ApiState.Error -> if (online) full("apierr") { ApiBanner(cfg.kind.label, a.message, onSettings) }
                ApiState.Off -> if (online && !cfg.hasLogin && !cfg.kind.generic) full("apihint") { ApiHint(cfg.kind, onSettings) }
            }
            full("apps-label") {
                val running = tiles.count { it.state == AppState.RUNNING || it.reachable == true }
                SectionLabel("Apps", trailing = if (online) "$running up" else null)
            }
            val pending = stats.apps.count { it.upgrade }
            if (online && caps.appUpdates && pending > 0 && api == ApiState.Ready) full("update-all") {
                Pill(
                    "Update all ($pending)", Palette.Searching, filled = true, icon = Icons.Rounded.SystemUpdateAlt,
                    modifier = Modifier.clickable { vm.upgradeAll() },
                )
            }
            items(tiles, key = { it.id }) { t ->
                AppTile(
                    t, enabled = online && t.url != null,
                    onClick = {
                        if (online && t.url != null) onOpen(t)
                        else vm.messages.tryEmit("Not connected to ${cfg.title}")
                    },
                    onLongClick = { sheetTile = t },
                )
            }
            item(key = "add") { AddTile { adding = true } }
        }
        // Fade content out under the status bar instead of letting it collide with the clock.
        Box(
            Modifier.fillMaxWidth().height(top + 12.dp)
                .background(Brush.verticalGradient(listOf(Palette.Black, Palette.Black.copy(alpha = 0.85f), Color.Transparent)))
        )
    }

    sheetTile?.let { t ->
        TileSheet(
            t, api == ApiState.Ready, caps,
            onDismiss = { sheetTile = null },
            onOpen = { sheetTile = null; if (t.url != null) onOpen(t) },
            onBrowser = { sheetTile = null; t.url?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } },
            onCopy = {
                sheetTile = null
                t.url?.let {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(t.name, it))
                    vm.messages.tryEmit("Link copied")
                }
            },
            onAction = { sheetTile = null; vm.appAction(t, it) },
            onPower = { sheetTile = null; confirmPower = it },
            onLogs = { sheetTile = null; vm.showLogs(t) },
            onSnapshots = { sheetTile = null; vm.showSnapshots() },
            onEdit = { sheetTile = null; editing = t.shortcut },
            onRemove = { sheetTile = null; t.shortcut?.let(vm::removeShortcut) },
        )
    }
    logs?.let { LogSheet(it, onDismiss = vm::closeLogs) }
    snaps?.let { SnapSheet(it, stats.pools, onDismiss = vm::closeSnaps, onTake = vm::takeSnapshot) }

    confirmPower?.let { action ->
        val off = action == "shutdown"
        AlertDialog(
            onDismissRequest = { confirmPower = null },
            containerColor = Palette.Surface2,
            title = { Text(if (off) "Shut down ${cfg.title}?" else "Restart ${cfg.title}?") },
            text = {
                Text(
                    if (off) "Your apps and shares will go offline. You'll need to turn the NAS back on by hand."
                    else "Your apps and shares will be offline for a few minutes while it restarts.",
                    color = Palette.Muted,
                )
            },
            confirmButton = {
                TextButton({ confirmPower = null; vm.power(action) }) {
                    Text(if (off) "Shut down" else "Restart", color = Palette.Offline)
                }
            },
            dismissButton = { TextButton({ confirmPower = null }) { Text("Cancel", color = Palette.Muted) } },
        )
    }
    if (adding || editing != null) {
        ShortcutDialog(
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { vm.upsertShortcut(it); adding = false; editing = null },
        )
    }
}

@Composable
private fun Header(cfg: ServerConfig, stats: NasStats, conn: Conn, canSwitch: Boolean, onSwitch: () -> Unit) {
    val route = (conn as? Conn.Online)?.let { routeLabel(it.route) }
    Row(
        Modifier.fillMaxWidth().clip(Palette.shape(14.dp)).clickable(enabled = canSwitch, onClick = onSwitch)
            .padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(cfg.kind.label, route).joinToString("  ·  ").uppercase(),
                style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1,
                fontFamily = if (Palette.black) androidx.compose.ui.text.font.FontFamily.Monospace else null,
            )
            Text(stats.hostname ?: cfg.title, style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (canSwitch) Icon(Icons.Rounded.UnfoldMore, "Switch NAS", tint = Palette.Muted)
    }
}

@Composable
private fun StatusHero(
    conn: Conn, net: NetState, cfg: ServerConfig, probes: Map<Route, ProbeResult>,
    onRetry: () -> Unit, onTailscale: () -> Unit, onSettings: () -> Unit,
) {
    val color = routeColor(conn)
    Panel(tint = color, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when (conn) {
                    is Conn.Online -> if (conn.route == Route.LAN) Icons.Rounded.Home else Icons.Rounded.VpnLock
                    is Conn.Resolving -> Icons.Rounded.Sync
                    is Conn.Offline -> when (conn.reason) {
                        OfflineReason.NO_NETWORK -> Icons.Rounded.SignalWifiOff
                        OfflineReason.CERT_MISMATCH -> Icons.Rounded.GppMaybe
                        else -> Icons.Rounded.CloudOff
                    }
                    Conn.Setup -> Icons.Rounded.Settings
                }
                StatusOrb(color, icon, pulsing = conn is Conn.Resolving)
                Spacer(Modifier.width(16.dp))
                AnimatedContent(conn.headline(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "headline") { (title, sub) ->
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(sub, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                RouteChip(
                    "Home", Icons.Rounded.Home, Palette.Lan, cfg.lanHost,
                    active = (conn as? Conn.Online)?.route == Route.LAN, probe = probes[Route.LAN],
                    idleNote = if (cfg.lanHost.isBlank()) "Not set" else null,
                    modifier = Modifier.weight(1f),
                )
                RouteChip(
                    "Tailscale", Icons.Rounded.VpnLock, Palette.Tailscale, cfg.tailscaleHost,
                    active = (conn as? Conn.Online)?.route == Route.TAILSCALE, probe = probes[Route.TAILSCALE],
                    idleNote = when {
                        cfg.tailscaleHost.isBlank() -> "Not set"
                        !net.vpn -> "VPN off"
                        else -> null
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (net.wifi) Pill("Wi-Fi", Palette.Muted, icon = Icons.Rounded.Wifi)
                if (net.cellular) Pill("Mobile data", Palette.Muted, icon = Icons.Rounded.SignalCellularAlt)
                Pill(if (net.vpn) "VPN on" else "VPN off", if (net.vpn) Palette.Tailscale else Palette.Muted, filled = net.vpn, icon = Icons.Rounded.VpnLock)
            }
            if (conn is Conn.Offline) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (conn.reason) {
                        OfflineReason.NOT_HOME -> {
                            Button(onTailscale, colors = ButtonDefaults.buttonColors(Palette.Accent, Palette.OnAccent)) {
                                Icon(Icons.Rounded.VpnLock, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open Tailscale")
                            }
                            OutlinedButton(onRetry) { Text("Try again", color = Palette.Text) }
                        }
                        OfflineReason.CERT_MISMATCH -> {
                            Button(onSettings, colors = ButtonDefaults.buttonColors(Palette.Offline, Palette.Black)) { Text("Review") }
                            OutlinedButton(onRetry) { Text("Try again", color = Palette.Text) }
                        }
                        else -> Button(onRetry, colors = ButtonDefaults.buttonColors(Palette.Text, Palette.Black)) { Text("Try again") }
                    }
                }
            }
        }
    }
}

private fun Conn.headline(): Pair<String, String> = when (this) {
    is Conn.Online -> "Connected" to "via ${routeLabel(route)} · ${latencyMs} ms"
    is Conn.Resolving -> (if (prev != null) "Checking route" else "Looking for your NAS") to
        (if (vpn) "Trying home network and Tailscale…" else "Trying your home network…")
    is Conn.Offline -> when (reason) {
        OfflineReason.NO_NETWORK -> "No connection" to "Your phone is offline"
        OfflineReason.NOT_HOME -> "Away from home" to "Home network didn't answer. Turn on Tailscale to reconnect."
        OfflineReason.UNREACHABLE -> "Can't reach NAS" to "Neither route answered${detail?.let { " · $it" } ?: ""}"
        OfflineReason.CERT_MISMATCH -> "Identity check failed" to "Something answered, but it isn't your NAS"
    }
    Conn.Setup -> "Not set up" to "Add your NAS in Settings"
}

@Composable
private fun RouteChip(
    label: String, icon: ImageVector, color: Color, host: String, active: Boolean,
    probe: ProbeResult?, idleNote: String?, modifier: Modifier = Modifier,
) {
    val shape = Palette.shape(16.dp)
    val status = when {
        idleNote != null -> idleNote
        probe == null -> "Standby"
        probe.ok -> "${probe.latencyMs} ms"
        else -> probe.error ?: "No answer"
    }
    val dot = when {
        active -> color
        probe?.ok == true -> color.copy(alpha = 0.5f)
        probe != null && idleNote == null -> Palette.Offline
        else -> Palette.Faint
    }
    Column(
        modifier
            .clip(shape)
            .background(if (active) color.copy(alpha = 0.14f) else Palette.Black.copy(alpha = 0.35f))
            .border(1.dp, if (active) color.copy(alpha = 0.55f) else Palette.Stroke, shape)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (active) color else Palette.Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) color else Palette.Text)
            Spacer(Modifier.weight(1f))
            Dot(dot)
        }
        Spacer(Modifier.height(6.dp))
        Text(host.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(status, style = MaterialTheme.typography.bodySmall, color = if (active) Palette.Text else Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatsRow(s: NasStats, loading: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().alpha(if (loading) 0.5f else 1f)) {
        StatCard("CPU", Modifier.weight(1f)) {
            Ring(s.cpu?.div(100f), Palette.Lan, Modifier.size(78.dp)) {
                Text(s.cpu?.let { "${it.toInt()}%" } ?: "—", style = MaterialTheme.typography.titleMedium)
            }
            Text(s.cores?.let { "$it cores" } ?: " ", style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
        }
        StatCard("Memory", Modifier.weight(1f)) {
            val frac = if (s.memUsed != null && s.memTotal != null) s.memUsed.toFloat() / s.memTotal else null
            Ring(frac, Palette.Tailscale, Modifier.size(78.dp)) {
                Text(frac?.let { "${(it * 100).toInt()}%" } ?: "—", style = MaterialTheme.typography.titleMedium)
            }
            Text(s.memTotal?.let { formatBytes(it) } ?: " ", style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
        }
        StatCard("Uptime", Modifier.weight(1f)) {
            Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
                Text(s.uptimeSec?.let(::formatUptime) ?: "—", style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp))
            }
            Text(s.version?.removePrefix("TrueNAS-")?.let { if (it.firstOrNull()?.isDigit() == true) "v$it" else it } ?: " ", style = MaterialTheme.typography.bodySmall, color = Palette.Faint, maxLines = 1)
        }
    }
}

@Composable
private fun StatCard(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    Panel(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun PoolsPanel(s: NasStats) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("STORAGE", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            val totUsed = s.pools.sumOf { it.used }
            val totSize = s.pools.sumOf { it.total }
            val totFrac = if (totSize > 0) totUsed.toFloat() / totSize else 0f
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatBytes(totSize), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(8.dp))
                    Text("in total", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.padding(bottom = 4.dp))
                    Spacer(Modifier.weight(1f))
                    Text("${(totFrac * 100).toInt()}% used", style = MaterialTheme.typography.labelLarge, color = Palette.Lan)
                }
                Spacer(Modifier.height(8.dp))
                Bar(totFrac, Palette.Lan)
                Spacer(Modifier.height(6.dp))
                Text("${formatBytes(totUsed)} used · ${formatBytes((totSize - totUsed).coerceAtLeast(0))} free", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
            s.pools.forEach { p ->
                val frac = if (p.total > 0) p.used.toFloat() / p.total else 0f
                val c = when {
                    !p.healthy -> Palette.Offline
                    frac > 0.85f -> Palette.Searching
                    else -> Palette.Lan
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(8.dp))
                        Pill(if (p.healthy) "Healthy" else p.status.lowercase().replaceFirstChar { it.uppercase() }, c, filled = true)
                        Spacer(Modifier.weight(1f))
                        Text("${(frac * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = c)
                    }
                    Spacer(Modifier.height(8.dp))
                    Bar(frac, c)
                    Spacer(Modifier.height(6.dp))
                    Text("${formatBytes(p.used)} of ${formatBytes(p.total)} · ${formatBytes(p.total - p.used)} free", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
            }
        }
    }
}

@Composable
private fun NetPanel(samples: List<NetSample>) {
    val last = samples.last()
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("NETWORK", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ArrowDownward, null, tint = Palette.Lan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${formatBytes(last.down.toLong())}/s", style = MaterialTheme.typography.titleSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ArrowUpward, null, tint = Palette.Tailscale, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${formatBytes(last.up.toLong())}/s", style = MaterialTheme.typography.titleSmall)
                }
            }
            Sparkline(samples.map { it.down }, samples.map { it.up })
        }
    }
}

@Composable
private fun Sparkline(down: List<Float>, up: List<Float>) {
    val top = (down + up).maxOrNull()?.coerceAtLeast(1f) ?: 1f
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        fun line(values: List<Float>, color: Color) {
            if (values.size < 2) return
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = size.width * i / (values.size - 1)
                val y = size.height - (v / top) * (size.height - 4f) - 2f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
        }
        drawLine(Palette.Stroke, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 2f)
        line(down, Palette.Lan)
        line(up, Palette.Tailscale)
    }
}

@Composable
private fun DisksPanel(disks: List<Disk>) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("DISKS", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            disks.forEach { d ->
                val c = when {
                    d.tempC == null -> Palette.Faint
                    d.tempC >= 55 -> Palette.Offline
                    d.tempC >= 45 -> Palette.Searching
                    else -> Palette.Lan
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Storage, null, tint = Palette.Muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.model, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${d.name} · ${formatBytes(d.size)}${d.pool?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    }
                    Pill(d.tempC?.let { "$it °C" } ?: "—", c, filled = d.tempC != null)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSheet(v: LogView, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.Surface, scrimColor = Color.Black.copy(alpha = 0.6f)) {
        dev.dorki.harbor.ui.components.GlassBehind()
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("${v.title} logs", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            when {
                v.loading -> Text("Loading…", color = Palette.Muted)
                v.error != null -> Text(v.error, color = Palette.Muted)
                else -> Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE))) {
                    v.lines.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp), color = Palette.Text)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnapSheet(v: SnapView, pools: List<dev.dorki.harbor.Pool>, onDismiss: () -> Unit, onTake: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = Palette.Surface, scrimColor = Color.Black.copy(alpha = 0.6f)) {
        dev.dorki.harbor.ui.components.GlassBehind()
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("Snapshots", style = MaterialTheme.typography.titleLarge)
            Text(v.count?.let { "$it on your NAS" } ?: if (v.loading) "Loading…" else "", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            Spacer(Modifier.height(12.dp))
            v.error?.let { Text(it, color = Palette.Offline) }
            if (v.recent.isNotEmpty()) {
                SectionLabel("Most recent")
                v.recent.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 3.dp))
                }
            }
            if (pools.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SectionLabel("Take one now")
                pools.forEach { p -> SheetRow(Icons.Rounded.PhotoCamera, "Snapshot ${p.name}", !v.loading) { onTake(p.name) } }
            }
        }
    }
}

@Composable
private fun ApiHint(kind: NasKind, onSettings: () -> Unit) {
    Panel(Modifier.fillMaxWidth().clickable(onClick = onSettings)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Palette.Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Key, null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Unlock live stats", style = MaterialTheme.typography.titleSmall)
                Text(if (kind.usesUser) "Sign in to ${kind.label} to see CPU, memory, storage and more." else "Add your ${kind.label} ${kind.secretLabel} to see CPU, memory, storage and more.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }
    }
}

@Composable
private fun ApiBanner(label: String, message: String, onSettings: () -> Unit) {
    Panel(Modifier.fillMaxWidth().clickable(onClick = onSettings), tint = Palette.Searching) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Key, null, tint = Palette.Searching)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("$label API unavailable", style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(t: Tile, enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val shape = Palette.shape(22.dp)
    val dot = when {
        t.state == AppState.DEPLOYING || t.state == AppState.STOPPING -> Palette.Searching
        t.state == AppState.CRASHED -> Palette.Offline
        t.state == AppState.STOPPED -> Palette.Faint
        t.reachable == true -> Palette.Good
        t.reachable == false -> Palette.Offline
        else -> Palette.Faint
    }
    Column(
        Modifier
            .aspectRatio(0.92f)
            .clip(shape)
            .background(Palette.Surface2)
            .border(1.dp, Palette.Stroke, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
            )
            .padding(14.dp)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        Box {
            AppIcon(t.icons, t.name)
            Dot(dot, Modifier.align(Alignment.TopEnd).padding(0.dp).size(11.dp).border(2.dp, Palette.Surface2, CircleShape))
        }
        Spacer(Modifier.weight(1f))
        Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
            if (t.upgrade) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.SystemUpdateAlt, "Update available", tint = Palette.Searching, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddTile(onClick: () -> Unit) {
    val shape = Palette.shape(22.dp)
    Column(
        Modifier
            .aspectRatio(0.92f)
            .clip(shape)
            .border(1.dp, Palette.Stroke, shape)
            .combinedClickable(onClick = onClick)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Add, null, tint = Palette.Muted, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(6.dp))
        Text("Add shortcut", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TileSheet(
    t: Tile, apiReady: Boolean, caps: Caps, onDismiss: () -> Unit, onOpen: () -> Unit, onBrowser: () -> Unit,
    onCopy: () -> Unit, onAction: (String) -> Unit, onPower: (String) -> Unit, onLogs: () -> Unit, onSnapshots: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = Palette.Surface, scrimColor = Color.Black.copy(alpha = 0.6f)) {
        dev.dorki.harbor.ui.components.GlassBehind()
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(t.icons, t.name, size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(t.name, style = MaterialTheme.typography.titleLarge)
                    Text(t.url ?: "Not connected", style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(16.dp))
            val hasUrl = t.url != null
            SheetRow(Icons.AutoMirrored.Rounded.OpenInNew, "Open", hasUrl, onClick = onOpen)
            SheetRow(Icons.Rounded.Public, "Open in browser", hasUrl, onClick = onBrowser)
            SheetRow(Icons.Rounded.ContentCopy, "Copy link", hasUrl, onClick = onCopy)
            if (t.kind == TileKind.APP && caps.apps) {
                Spacer(Modifier.height(8.dp))
                SectionLabel("Control")
                if (t.state == AppState.STOPPED || t.state == AppState.CRASHED) SheetRow(Icons.Rounded.PlayArrow, "Start", apiReady) { onAction("start") }
                else SheetRow(Icons.Rounded.Stop, "Stop", apiReady) { onAction("stop") }
                SheetRow(Icons.Rounded.RestartAlt, "Restart", apiReady) { onAction("restart") }
                if (t.upgrade && caps.appUpdates) SheetRow(Icons.Rounded.SystemUpdateAlt, "Update", apiReady, tint = Palette.Searching) { onAction("upgrade") }
                if (caps.logs) SheetRow(Icons.Rounded.Description, "View logs", apiReady, onClick = onLogs)
            }
            if (t.kind == TileKind.SYSTEM && (caps.snapshots || caps.power)) {
                Spacer(Modifier.height(8.dp))
                if (caps.snapshots) SheetRow(Icons.Rounded.PhotoCamera, "Snapshots", apiReady, onClick = onSnapshots)
                if (caps.power) {
                    SectionLabel("Power")
                    SheetRow(Icons.Rounded.RestartAlt, "Restart NAS", apiReady) { onPower("reboot") }
                    SheetRow(Icons.Rounded.PowerSettingsNew, "Shut down NAS", apiReady, tint = Palette.Offline) { onPower("shutdown") }
                }
            }
            if (t.kind == TileKind.SHORTCUT) {
                Spacer(Modifier.height(8.dp))
                SectionLabel("Shortcut")
                SheetRow(Icons.Rounded.Edit, "Edit", true, onClick = onEdit)
                SheetRow(Icons.Rounded.Delete, "Remove", true, tint = Palette.Offline, onClick = onRemove)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SheetRow(icon: ImageVector, label: String, enabled: Boolean, tint: Color = Palette.Text, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Palette.shape(14.dp))
            .combinedClickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint, fontWeight = FontWeight.Medium)
    }
}

fun openTailscale(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
    val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.tailscale.ipn"))
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun SharesPanel(shares: List<Share>, onOpen: (Share) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            shares.forEach { sh ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(sh) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp).clip(Palette.shape(12.dp)).background(Palette.Accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.FolderShared, null, tint = Palette.Accent, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sh.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sh.comment.ifBlank { sh.path }, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.Faint)
                }
            }
        }
    }
}

