package dev.dorki.harbor.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dorki.harbor.FsEntry
import dev.dorki.harbor.HarborViewModel
import dev.dorki.harbor.Share
import dev.dorki.harbor.net.DownloadItem
import dev.dorki.harbor.net.Downloads
import dev.dorki.harbor.net.describe
import dev.dorki.harbor.ui.components.formatBytes
import dev.dorki.harbor.ui.theme.Palette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(vm: HarborViewModel, share: Share, onClose: () -> Unit) {
    val files = vm.files
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val transfers = remember { Downloads.get(vm.getApplication()) }
    val transferItems by transfers.items.collectAsState()

    var path by rememberSaveable(share.path) { mutableStateOf(share.path) }
    var entries by remember(path) { mutableStateOf(files.cached(path)) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var reload by remember { mutableIntStateOf(0) }
    var newFolder by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<FsEntry?>(null) }
    var menuFor by remember { mutableStateOf<FsEntry?>(null) }
    val listState = rememberLazyListState()

    // Cached listing shows instantly; the fresh one replaces it when it lands.
    LaunchedEffect(path, reload) {
        loading = true
        try {
            entries = files.list(path)
            error = null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = describe(e)
        } finally {
            loading = false
            refreshing = false
        }
    }
    LaunchedEffect(path) {
        selected = emptySet()
        listState.scrollToItem(0)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val target = path
            files.upload(target, uris, entries.orEmpty().map { it.name }.toSet()) { if (path == target) reload++ }
        }
    }

    // Transfers run in a foreground service; ask once so its progress notification can show.
    val context = androidx.compose.ui.platform.LocalContext.current
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterPermission?.invoke()
        afterPermission = null
    }
    fun withNotifications(action: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            afterPermission = action
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    val atRoot = path == share.path
    BackHandler {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            !atRoot -> path = path.substringBeforeLast('/')
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Black)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(Palette.Surface).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selected.isNotEmpty()) {
                        IconButton({ selected = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection", tint = Palette.Text) }
                        Text("${selected.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    } else {
                        IconButton({ if (atRoot) onClose() else path = path.substringBeforeLast('/') }) {
                            Icon(if (atRoot) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Palette.Text)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (atRoot) share.name else path.substringAfterLast('/'),
                                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                entries?.let { e -> "${e.count { it.dir }} folders · ${e.count { !it.dir }} files" } ?: "Loading…",
                                style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1,
                            )
                        }
                        IconButton({ newFolder = true }) { Icon(Icons.Rounded.CreateNewFolder, "New folder", tint = Palette.Muted) }
                    }
                }
                Breadcrumbs(share, path) { path = it }
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (loading && entries != null) {
                        androidx.compose.material3.LinearProgressIndicator(color = Palette.Accent, trackColor = Color.Transparent, modifier = Modifier.fillMaxWidth().height(2.dp))
                    }
                }
                transferItems.forEach { item ->
                    DownloadRow(item, onOpen = { transfers.open(item) }, onDismiss = { transfers.dismiss(item.id) })
                }
            }

            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { refreshing = true; reload++ },
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                val list = entries
                when {
                    list == null && error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Palette.Accent, strokeWidth = 3.dp)
                    }
                    list == null -> Message("Couldn't open this folder", error)
                    list.isEmpty() -> Message("This folder is empty", "Tap Upload to add files")
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
                    ) {
                        items(list, key = { it.path }) { e ->
                            val isSelected = e.path in selected
                            EntryRow(
                                e, isSelected,
                                onClick = {
                                    when {
                                        selected.isNotEmpty() && !e.dir -> selected = if (isSelected) selected - e.path else selected + e.path
                                        e.dir -> path = e.path
                                        else -> opening = e
                                    }
                                },
                                onLongClick = {
                                    if (!e.dir) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (selected.isNotEmpty()) selected = if (isSelected) selected - e.path else selected + e.path
                                        else menuFor = e
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        val fabModifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp)
        if (selected.isEmpty()) {
            ExtendedFloatingActionButton(
                onClick = { withNotifications { picker.launch(arrayOf("*/*")) } },
                icon = { Icon(Icons.Rounded.Upload, null) },
                text = { Text("Upload") },
                containerColor = Palette.Accent, contentColor = Palette.OnAccent,
                modifier = fabModifier,
            )
        } else {
            ExtendedFloatingActionButton(
                onClick = {
                    val picked = entries.orEmpty().filter { it.path in selected }
                    selected = emptySet()
                    withNotifications { files.download(picked) }
                },
                icon = { Icon(Icons.Rounded.Download, null) },
                text = { Text("Download ${selected.size}") },
                containerColor = Palette.Accent, contentColor = Palette.OnAccent,
                modifier = fabModifier,
            )
        }
    }

    menuFor?.let { e ->
        FileMenu(
            e,
            onDismiss = { menuFor = null },
            onDownload = { menuFor = null; withNotifications { files.download(listOf(e)) } },
            onOpen = { menuFor = null; opening = e },
            onSelect = { menuFor = null; selected = setOf(e.path) },
        )
    }

    opening?.let { e ->
        FileViewer(vm, e, entries.orEmpty().filterNot { it.dir }, onClose = { opening = null })
    }

    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            containerColor = Palette.Surface2,
            title = { Text("New folder") },
            text = {
                OutlinedTextField(name, { name = it.replace("/", "") }, singleLine = true, label = { Text("Name") }, colors = fieldColors())
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    newFolder = false
                    val parent = path
                    scope.launch {
                        try {
                            files.mkdir(parent, name.trim())
                            reload++
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            vm.messages.tryEmit("Couldn't create folder: ${describe(e)}")
                        }
                    }
                }) { Text("Create", color = Palette.Accent) }
            },
            dismissButton = { TextButton({ newFolder = false }) { Text("Cancel", color = Palette.Muted) } },
        )
    }
}

@Composable
private fun Breadcrumbs(share: Share, path: String, onNavigate: (String) -> Unit) {
    val rel = path.removePrefix(share.path).trim('/').split('/').filter { it.isNotEmpty() }
    if (rel.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Crumb(share.name) { onNavigate(share.path) }
        rel.forEachIndexed { i, part ->
            Text("›", color = Palette.Faint)
            Crumb(part, current = i == rel.lastIndex) { onNavigate(share.path + "/" + rel.take(i + 1).joinToString("/")) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Crumb(label: String, current: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (current) Palette.Text else Palette.Muted,
        maxLines = 1,
        modifier = Modifier.clip(CircleShape).combinedClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(e: FsEntry, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val (icon, color) = iconFor(e)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Palette.Accent.copy(alpha = 0.10f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(Palette.shape(12.dp)).background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (selected) Icons.Rounded.CheckCircle else icon, null, tint = if (selected) Palette.Accent else color, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (e.dir) "Folder" else formatBytes(e.size), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
    }
}

private fun iconFor(e: FsEntry): Pair<ImageVector, Color> {
    if (e.dir) return Icons.Rounded.Folder to Palette.Accent
    return when (e.name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "raw", "dng" -> Icons.Rounded.Image to Color(0xFF5EB3FF)
        "mp4", "mkv", "mov", "avi", "webm", "m4v", "ts", "wmv" -> Icons.Rounded.Movie to Palette.Tailscale
        "mp3", "flac", "m4a", "m4b", "wav", "ogg", "opus", "aac" -> Icons.Rounded.AudioFile to Color(0xFFFF8A5C)
        "zip", "7z", "rar", "tar", "gz", "xz", "bz2", "zst" -> Icons.Rounded.FolderZip to Palette.Searching
        "pdf" -> Icons.Rounded.PictureAsPdf to Palette.Offline
        "txt", "md", "doc", "docx", "odt", "rtf", "csv", "xls", "xlsx", "json", "log" -> Icons.Rounded.Description to Palette.Text
        else -> Icons.Rounded.InsertDriveFile to Palette.Muted
    }
}

@Composable
private fun Message(title: String, detail: String?) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Folder, null, tint = Palette.Faint, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileMenu(e: FsEntry, onDismiss: () -> Unit, onDownload: () -> Unit, onOpen: () -> Unit, onSelect: () -> Unit) {
    val (icon, color) = iconFor(e)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        dev.dorki.harbor.ui.components.GlassBehind()
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(Palette.shape(14.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formatBytes(e.size), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
            }
            Spacer(Modifier.height(12.dp))
            MenuRow(Icons.Rounded.Download, "Download to phone", "Save a copy in your Downloads folder", onDownload)
            MenuRow(Icons.AutoMirrored.Rounded.OpenInNew, "Open", null, onOpen)
            MenuRow(Icons.Rounded.CheckCircle, "Select multiple", "Pick several files to download together", onSelect)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MenuRow(icon: ImageVector, label: String, detail: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Palette.shape(14.dp)).combinedClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
    }
}

