package dev.dorki.harbor.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import dev.dorki.harbor.FsEntry
import dev.dorki.harbor.HarborViewModel
import dev.dorki.harbor.NasFiles
import dev.dorki.harbor.net.describe
import dev.dorki.harbor.ui.components.formatBytes
import dev.dorki.harbor.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class ViewKind { IMAGE, TEXT, MEDIA, OTHER }

private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif")
private val textExt = setOf(
    "txt", "md", "log", "srt", "vtt", "ass", "json", "csv", "tsv", "xml", "yml", "yaml", "toml", "ini", "conf",
    "cfg", "env", "sh", "py", "js", "ts", "kt", "java", "c", "h", "cpp", "rs", "go", "html", "css", "nfo", "properties",
)
private val mediaExt = setOf(
    "mp4", "mkv", "webm", "mov", "m4v", "3gp", "ts", "avi",
    "mp3", "m4a", "m4b", "aac", "flac", "ogg", "opus", "wav",
)
private const val TEXT_LIMIT = 2L * 1024 * 1024

fun viewKind(e: FsEntry): ViewKind {
    val ext = e.name.substringAfterLast('.', "").lowercase()
    return when {
        ext in imageExt -> ViewKind.IMAGE
        ext in mediaExt -> ViewKind.MEDIA
        ext in textExt && e.size <= TEXT_LIMIT -> ViewKind.TEXT
        else -> ViewKind.OTHER
    }
}

/**
 * Opens a NAS file: photos and text inside Harbor, everything else (video, music, PDFs…) in
 * whichever app on the phone handles it. Files are fetched into the cache, never into Downloads.
 */
@Composable
fun FileViewer(vm: HarborViewModel, entry: FsEntry, siblings: List<FsEntry>, onClose: () -> Unit) {
    val context = LocalContext.current
    val kind = remember(entry) { viewKind(entry) }
    val images = remember(siblings, entry) {
        if (kind == ViewKind.IMAGE) siblings.filter { viewKind(it) == ViewKind.IMAGE } else listOf(entry)
    }
    val pager = rememberPagerState(initialPage = images.indexOf(entry).coerceAtLeast(0)) { images.size }
    var zoomed by remember { mutableStateOf(false) }
    val current = images.getOrElse(pager.currentPage) { entry }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (kind) {
            ViewKind.IMAGE -> HorizontalPager(
                state = pager,
                userScrollEnabled = !zoomed,
                beyondViewportPageCount = 1, // fetch the neighbours too, so swiping is instant
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                Loaded(vm, images[page]) { file -> ZoomableImage(file, onZoomChange = { zoomed = it }) }
            }
            ViewKind.TEXT -> Loaded(vm, entry) { file -> TextView(file) }
            ViewKind.MEDIA -> Loaded(vm, entry) { file -> MediaPlayer(file) }
            ViewKind.OTHER -> Loaded(vm, entry) { file ->
                LaunchedEffect(file) {
                    openExternally(context, file, entry.name, vm)
                    onClose()
                }
            }
        }

        // Top bar
        Row(
            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClose) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(current.name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    formatBytes(current.size) + if (images.size > 1) " · ${pager.currentPage + 1} of ${images.size}" else "",
                    color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall,
                )
            }
            if (kind != ViewKind.OTHER) {
                IconButton({ vm.files.cachedFile(current)?.let { share(context, it, current.name, vm) } }) {
                    Icon(Icons.Rounded.Share, "Share", tint = Color.White)
                }
                IconButton({ vm.files.cachedFile(current)?.let { openExternally(context, it, current.name, vm) } }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in another app", tint = Color.White)
                }
            }
            IconButton({ vm.files.download(listOf(current)) }) { Icon(Icons.Rounded.Download, "Download to phone", tint = Color.White) }
        }
    }
}

/** Fetches [e] (or takes it from the cache) and shows progress until it's ready. */
@Composable
private fun Loaded(vm: HarborViewModel, e: FsEntry, content: @Composable (File) -> Unit) {
    var file by remember(e) { mutableStateOf(vm.files.cachedFile(e)) }
    var done by remember(e) { mutableLongStateOf(0L) }
    var error by remember(e) { mutableStateOf<String?>(null) }
    var attempt by remember(e) { mutableStateOf(0) }

    LaunchedEffect(e, attempt) {
        if (file != null) return@LaunchedEffect
        error = null
        try {
            file = vm.files.fetch(e) { done = it }
        } catch (t: Exception) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            error = describe(t)
        }
    }

    val f = file
    when {
        f != null -> content(f)
        error != null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Couldn't open ${e.name}", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(error ?: "", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            TextButton({ attempt++ }) { Text("Try again", color = Palette.Accent) }
        }
        else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            val frac = if (e.size > 0) (done.toFloat() / e.size).coerceIn(0f, 1f) else 0f
            Box(contentAlignment = Alignment.Center) {
                if (done > 0 && e.size > 0) {
                    CircularProgressIndicator(progress = { frac }, color = Palette.Accent, trackColor = Color.White.copy(alpha = 0.12f), strokeWidth = 4.dp, modifier = Modifier.size(64.dp))
                    Text("${(frac * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelLarge)
                } else {
                    CircularProgressIndicator(color = Palette.Accent, strokeWidth = 4.dp, modifier = Modifier.size(64.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Opening ${e.name}", color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 32.dp))
            Text("${formatBytes(done)} of ${formatBytes(e.size)}", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ZoomableImage(file: File, onZoomChange: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    fun set(s: Float, o: Offset) {
        scale = s
        // Stop panning at the edges so a zoomed photo can't be dragged off-screen.
        val maxX = (s - 1f) * size.width / 2f
        val maxY = (s - 1f) * size.height / 2f
        offset = if (s > 1f) Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY)) else Offset.Zero
        onZoomChange(s > 1f)
    }
    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { if (scale > 1f) set(1f, Offset.Zero) else set(2.5f, Offset.Zero) })
            }
            .pointerInput(Unit) {
                // Pinch always zooms; one-finger drags only pan once zoomed in, so the pager still swipes at 1x.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            set((scale * event.calculateZoom()).coerceIn(1f, 6f), offset + event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun MediaPlayer(file: File) {
    val context = LocalContext.current
    val player = remember(file) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_PAUSE) { player.pause() }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = {
            androidx.media3.ui.PlayerView(it).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                this.player = player
                setShowNextButton(false)
                setShowPreviousButton(false)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
    )
}

@Composable
private fun TextView(file: File) {
    var text by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) { text = withContext(Dispatchers.IO) { file.readText() } }
    SelectionContainer {
        Text(
            text ?: "",
            color = Palette.Text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier
                .fillMaxSize()
                .background(Palette.Black)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(top = 60.dp, start = 16.dp, end = 16.dp, bottom = 24.dp)
                .navigationBarsPadding(),
        )
    }
}

private fun uriFor(context: Context, file: File) =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

private fun openExternally(context: Context, file: File, name: String, vm: HarborViewModel) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uriFor(context, file), NasFiles.mimeOf(name) ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        vm.messages.tryEmit("No app on this phone can open $name. Press and hold it to download instead.")
    }
}

private fun share(context: Context, file: File, name: String, vm: HarborViewModel) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(NasFiles.mimeOf(name) ?: "*/*")
        .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, name)) }
        .onFailure { vm.messages.tryEmit("Couldn't share $name") }
}
