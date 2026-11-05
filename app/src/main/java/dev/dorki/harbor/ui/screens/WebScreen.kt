package dev.dorki.harbor.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.dorki.harbor.net.DownloadItem
import dev.dorki.harbor.net.Downloads
import dev.dorki.harbor.ui.components.formatBytes
import okhttp3.OkHttpClient
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import dev.dorki.harbor.Conn
import dev.dorki.harbor.HarborViewModel
import dev.dorki.harbor.Tile
import dev.dorki.harbor.ui.components.AppIcon
import dev.dorki.harbor.ui.components.Dot
import dev.dorki.harbor.ui.theme.Palette

/** One live WebView per app, so hopping back to Jellyfin keeps your place. */
class HarborWeb(
    context: Context,
    allowedHosts: () -> Set<String>,
    downloads: Downloads,
    clientFor: (host: String) -> OkHttpClient,
) {
    var progress by mutableFloatStateOf(0f)
    var title by mutableStateOf<String?>(null)
    var customView by mutableStateOf<View?>(null)
    var customCallback: WebChromeClient.CustomViewCallback? = null
    var fileCallback: ValueCallback<Array<Uri>>? = null
    var launchChooser: ((Intent) -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    val view: WebView = WebView(context).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(downloads.bridge, "HarborDl")

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                this@HarborWeb.progress = 0.05f
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(Downloads.HOOK_JS, null)
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // The NAS uses self-signed certs; accept them only for the NAS's own addresses.
                val host = runCatching { Uri.parse(error.url).host }.getOrNull()
                if (host != null && host in allowedHosts()) handler.proceed() else handler.cancel()
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                this@HarborWeb.progress = newProgress / 100f
            }

            override fun onReceivedTitle(view: WebView, t: String?) {
                this@HarborWeb.title = t
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                customCallback = callback
            }

            override fun onHideCustomView() {
                customView = null
                customCallback = null
            }

            override fun onShowFileChooser(
                webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = params.createIntent().apply {
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                val launch = launchChooser
                if (launch == null) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    return false
                }
                runCatching { launch(intent) }.onFailure {
                    fileCallback = null
                    callback.onReceiveValue(null)
                }
                return true
            }
        }

        setDownloadListener { url, userAgent, disposition, mime, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                evaluateJavascript(Downloads.blobJs(url), null)
            } else {
                downloads.fetch(url, userAgent, disposition, mime, this.url, clientFor(Uri.parse(url).host ?: ""))
            }
        }
    }
}

class WebCache(private val max: Int = 4) {
    private val map = LinkedHashMap<String, HarborWeb>(8, 0.75f, true)

    fun get(id: String, create: () -> HarborWeb): HarborWeb {
        map[id]?.let { return it }
        val w = create()
        map[id] = w
        while (map.size > max) {
            val eldest = map.entries.first()
            map.remove(eldest.key)
            (eldest.value.view.parent as? ViewGroup)?.removeView(eldest.value.view)
            eldest.value.view.destroy()
        }
        return w
    }

    fun destroyAll() {
        map.values.forEach { it.view.destroy() }
        map.clear()
    }
}

@Composable
fun WebScreen(vm: HarborViewModel, tile: Tile, url: String?, conn: Conn, cache: WebCache, onClose: () -> Unit) {
    val context = LocalContext.current
    val web = remember(tile.id) {
        cache.get(tile.id) {
            HarborWeb(
                context,
                allowedHosts = vm::nasHosts,
                downloads = Downloads.get(context),
                clientFor = vm::downloadClient,
            )
        }
    }

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        val uris: Array<Uri>? = if (res.resultCode == Activity.RESULT_OK && data != null) {
            data.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }
                ?: data.data?.let { arrayOf(it) }
        } else null
        web.fileCallback?.onReceiveValue(uris)
        web.fileCallback = null
    }
    DisposableEffect(web) {
        web.launchChooser = { chooser.launch(it) }
        onDispose { web.launchChooser = null }
    }

    // First open loads the app; if the route changes while it's open, carry the current page over.
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val current = web.view.url
        when {
            current == null || current == "about:blank" -> web.view.loadUrl(url)
            Uri.parse(current).host != Uri.parse(url).host -> web.view.loadUrl(HarborViewModel.swapBase(current, url))
        }
    }

    val downloads = remember { Downloads.get(context) }
    val downloadItems by downloads.items.collectAsState()

    BackHandler {
        when {
            web.customView != null -> web.customCallback?.onCustomViewHidden()
            web.view.canGoBack() -> web.view.goBack()
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Black)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(Palette.Surface).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Rounded.Close, "Close", tint = Palette.Text) }
                    AppIcon(tile.icons, tile.name, size = 30.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Dot(routeColor(conn), size = 6.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when (conn) {
                                    is Conn.Online -> "via ${routeLabel(conn.route)}"
                                    is Conn.Resolving -> "Switching route…"
                                    else -> "Disconnected"
                                },
                                style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1,
                            )
                        }
                    }
                    IconButton({ if (web.view.canGoBack()) web.view.goBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Palette.Muted) }
                    IconButton({ web.view.reload() }) { Icon(Icons.Rounded.Refresh, "Reload", tint = Palette.Muted) }
                    IconButton({
                        (web.view.url ?: url)?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
                    }) { Icon(Icons.Rounded.Public, "Open in browser", tint = Palette.Muted) }
                }
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (web.progress in 0.01f..0.99f) {
                        LinearProgressIndicator(
                            progress = { web.progress }, color = routeColor(conn), trackColor = Color.Transparent,
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                        )
                    }
                }
                downloadItems.forEach { item ->
                    DownloadRow(item, onOpen = { downloads.open(item) }, onDismiss = { downloads.dismiss(item.id) })
                }
            }
            AndroidView(
                factory = {
                    (web.view.parent as? ViewGroup)?.removeView(web.view)
                    // AndroidView defaults to WRAP_CONTENT, which makes CSS 100vh resolve to 0 in WebView.
                    web.view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    web.view
                },
                modifier = Modifier.fillMaxWidth().weight(1f).navigationBarsPadding(),
            )
        }

        web.customView?.let { v ->
            FullscreenEffect()
            AndroidView(
                factory = {
                    (v.parent as? ViewGroup)?.removeView(v)
                    v
                },
                modifier = Modifier.fillMaxSize().background(Color.Black),
            )
        }
    }
}

@Composable
fun DownloadRow(item: DownloadItem, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val (icon, tint) = when (item.state) {
        DownloadItem.State.RUNNING -> (if (item.upload) Icons.Rounded.Upload else Icons.Rounded.Download) to Palette.Accent
        DownloadItem.State.DONE -> Icons.Rounded.CheckCircle to Palette.Good
        DownloadItem.State.FAILED -> Icons.Rounded.Error to Palette.Offline
    }
    Row(
        Modifier.fillMaxWidth().background(Palette.Surface2).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            when (item.state) {
                DownloadItem.State.RUNNING -> {
                    val f = item.fraction
                    if (f != null) {
                        LinearProgressIndicator(progress = { f }, color = Palette.Accent, trackColor = Palette.Stroke, modifier = Modifier.fillMaxWidth().height(3.dp))
                    } else {
                        LinearProgressIndicator(color = Palette.Accent, trackColor = Palette.Stroke, modifier = Modifier.fillMaxWidth().height(3.dp))
                    }
                    Text(
                        formatBytes(item.done) + (if (item.total > 0) " of ${formatBytes(item.total)}" else ""),
                        style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                    )
                }
                DownloadItem.State.DONE -> Text(if (item.upload) "Uploaded" else "Saved to Downloads", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                DownloadItem.State.FAILED -> Text(item.error ?: "Failed", style = MaterialTheme.typography.bodySmall, color = Palette.Offline, maxLines = 1)
            }
        }
        if (item.state == DownloadItem.State.DONE && !item.upload) {
            TextButton(onOpen) { Text("Open", color = Palette.Accent) }
        }
        IconButton(onDismiss) { Icon(Icons.Rounded.Close, "Dismiss", tint = Palette.Muted, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun FullscreenEffect() {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(Unit) {
        val ctl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { ctl.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
