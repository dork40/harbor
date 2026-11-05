package dev.dorki.harbor.net

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class DownloadItem(
    val id: Long,
    val name: String,
    val total: Long,
    val done: Long = 0,
    val state: State = State.RUNNING,
    val uri: Uri? = null,
    val error: String? = null,
    val upload: Boolean = false,
    /** For uploads: the NAS folder the file is going into. */
    val dir: String? = null,
) {
    enum class State { RUNNING, DONE, FAILED }

    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * Downloads files from NAS apps into the public Downloads folder.
 *
 * The system DownloadManager runs in its own process, which can't reach the NAS on the
 * local network (or past its self-signed certificate), so Harbor fetches files itself over
 * the same connection the app already uses.
 */
class Downloads private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextId = AtomicLong(1)
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    /** In-flight blob downloads streamed in from page JavaScript. */
    private class Sink(val id: Long, val uri: Uri, val out: OutputStream)
    private val sinks = ConcurrentHashMap<String, Sink>()
    private val calls = ConcurrentHashMap<Long, Call>()
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()

    init {
        clearStuckSystemDownloads()
    }

    fun fetch(
        url: String, userAgent: String?, disposition: String?, mime: String?, referer: String?, client: OkHttpClient,
        /** Size known ahead of time, for streams that don't send Content-Length (TrueNAS file downloads). */
        expectedSize: Long = -1,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return legacy(url, userAgent, disposition, mime)
        val guessed = URLUtil.guessFileName(url, disposition, mime)
        val id = add(guessed, expectedSize)
        scope.launch {
            var uri: Uri? = null
            try {
                val req = Request.Builder().url(url).apply {
                    userAgent?.let { header("User-Agent", it) }
                    referer?.let { header("Referer", it) }
                    CookieManager.getInstance().getCookie(url)?.let { header("Cookie", it) }
                }.build()
                val call = client.newCall(req).also { track(id, it) }
                call.execute().use { resp ->
                    if (!resp.isSuccessful) error("Server said ${resp.code}")
                    val body = resp.body ?: error("Empty response")
                    val type = body.contentType()?.let { "${it.type}/${it.subtype}" } ?: mime
                    val name = resp.header("Content-Disposition")
                        ?.let { URLUtil.guessFileName(url, it, type) }
                        ?.takeIf { disposition.isNullOrBlank() } ?: guessed
                    val total = body.contentLength().takeIf { it > 0 } ?: expectedSize
                    update(id) { it.copy(name = name, total = total) }
                    val (u, out) = create(name, type)
                    uri = u
                    out.use { o ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastReport = 0L
                            while (isActive) {
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                                val now = System.nanoTime()
                                if (now - lastReport > 150_000_000L) {
                                    lastReport = now
                                    update(id) { it.copy(done = done) }
                                }
                            }
                            update(id) { it.copy(done = done) }
                        }
                    }
                    publish(u)
                    finish(id, u)
                }
            } catch (e: Throwable) {
                uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                fail(id, describe(e))
            }
        }
    }

    /** Copies a file Harbor already has (e.g. one opened from the NAS) into Downloads. */
    fun saveLocal(file: java.io.File, name: String, mime: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val id = add(name, file.length())
        scope.launch {
            var uri: Uri? = null
            try {
                val (u, out) = create(name, mime)
                uri = u
                out.use { o ->
                    file.inputStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            update(id) { it.copy(done = done) }
                        }
                    }
                }
                publish(u)
                finish(id, u)
            } catch (e: Exception) {
                uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                fail(id, describe(e))
            }
        }
    }

    /** Called from page JavaScript for blob: URLs, which only exist inside the page. */
    inner class Bridge {
        @JavascriptInterface
        fun begin(name: String, mime: String, size: Double): String {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ""
            val type = mime.ifBlank { null }
            val fileName = name.ifBlank {
                "download" + (type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.let { ".$it" } ?: "")
            }
            val id = add(fileName, size.toLong())
            return try {
                val (uri, out) = create(fileName, type)
                val token = "b$id"
                sinks[token] = Sink(id, uri, out)
                token
            } catch (e: Exception) {
                fail(id, describe(e))
                ""
            }
        }

        @JavascriptInterface
        fun chunk(token: String, b64: String) {
            val s = sinks[token] ?: return
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                s.out.write(bytes)
                update(s.id) { it.copy(done = it.done + bytes.size) }
            } catch (e: Exception) {
                abort(token, describe(e))
            }
        }

        @JavascriptInterface
        fun end(token: String) {
            val s = sinks.remove(token) ?: return
            runCatching { s.out.close() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) publish(s.uri)
            finish(s.id, s.uri)
        }

        @JavascriptInterface
        fun fail(token: String, message: String) {
            if (token.isBlank()) toast("Download failed: $message") else abort(token, message)
        }
    }

    val bridge = Bridge()

    fun open(item: DownloadItem) {
        val uri = item.uri ?: return
        val type = context.contentResolver.getType(uri)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun dismiss(id: Long) = _items.update { list -> list.filterNot { it.id == id } }

    private fun abort(token: String, message: String) {
        val s = sinks.remove(token) ?: return
        runCatching { s.out.close() }
        runCatching { context.contentResolver.delete(s.uri, null, null) }
        fail(s.id, message)
    }

    private fun add(name: String, total: Long, upload: Boolean = false, dir: String? = null): Long {
        val id = nextId.getAndIncrement()
        _items.update { it + DownloadItem(id, name, total, upload = upload, dir = dir) }
        TransferService.start(context)
        return id
    }

    // Uploads are driven by the caller (they need the API session); this just tracks them.
    fun beginUpload(name: String, total: Long, dir: String): Long = add(name, total, upload = true, dir = dir)

    fun progress(id: Long, done: Long) = update(id) { it.copy(done = done) }

    fun uploaded(id: Long) {
        calls.remove(id)
        update(id) { it.copy(state = DownloadItem.State.DONE, done = maxOf(it.done, it.total)) }
        _items.value.firstOrNull { it.id == id }?.let { TransferNotifications.finished(context, it) }
        scope.launch { delay(6_000); dismiss(id) }
    }

    /** Registers the HTTP call behind a transfer so it can be cancelled. */
    fun track(id: Long, call: Call) {
        calls[id] = call
        if (id in cancelled) call.cancel()
    }

    fun cancelAll() {
        _items.value.filter { it.state == DownloadItem.State.RUNNING }.forEach { item ->
            cancelled += item.id
            calls.remove(item.id)?.cancel()
            update(item.id) { it.copy(state = DownloadItem.State.FAILED, error = "Cancelled") }
            scope.launch { delay(4_000); dismiss(item.id) }
        }
    }

    fun failed(id: Long, message: String) = fail(id, message)

    fun downloadFailed(name: String, message: String) = fail(add(name, -1), message)

    private fun update(id: Long, f: (DownloadItem) -> DownloadItem) =
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }

    private fun finish(id: Long, uri: Uri) {
        calls.remove(id)
        update(id) { it.copy(state = DownloadItem.State.DONE, uri = uri, done = maxOf(it.done, it.total)) }
        _items.value.firstOrNull { it.id == id }?.let { TransferNotifications.finished(context, it) }
        scope.launch { delay(10_000); dismiss(id) }
    }

    private fun fail(id: Long, message: String) {
        calls.remove(id)
        if (id in cancelled) return // already marked by cancelAll()
        update(id) { it.copy(state = DownloadItem.State.FAILED, error = message) }
        _items.value.firstOrNull { it.id == id }?.let { TransferNotifications.finished(context, it) }
        scope.launch { delay(10_000); dismiss(id) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun create(name: String, mime: String?): Pair<Uri, OutputStream> {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            mime?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create file")
        val out = resolver.openOutputStream(uri) ?: run {
            resolver.delete(uri, null, null)
            error("Couldn't open file")
        }
        return uri to out
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publish(uri: Uri) {
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }

    private fun legacy(url: String, userAgent: String?, disposition: String?, mime: String?) {
        runCatching {
            val name = URLUtil.guessFileName(url, disposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            userAgent?.let { req.addRequestHeader("User-Agent", it) }
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            context.getSystemService(DownloadManager::class.java).enqueue(req)
            toast("Downloading $name")
        }
    }

    /** Earlier builds handed files to DownloadManager, which retries unreachable NAS URLs forever. */
    private fun clearStuckSystemDownloads() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val dm = context.getSystemService(DownloadManager::class.java)
            val q = DownloadManager.Query().setFilterByStatus(
                DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or
                    DownloadManager.STATUS_PAUSED or DownloadManager.STATUS_FAILED
            )
            val ids = mutableListOf<Long>()
            dm.query(q)?.use { c ->
                val col = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                while (c.moveToNext()) ids += c.getLong(col)
            }
            if (ids.isNotEmpty()) {
                val removed = dm.remove(*ids.toLongArray())
                android.util.Log.i("Harbor", "Removed $removed stuck system downloads: $ids")
            }
        }.onFailure { android.util.Log.w("Harbor", "Couldn't clear stuck downloads", it) }
    }

    private fun toast(text: String) {
        scope.launch { withContext(Dispatchers.Main) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() } }
    }

    companion object {
        @Volatile private var instance: Downloads? = null

        fun get(context: Context): Downloads =
            instance ?: synchronized(this) { instance ?: Downloads(context.applicationContext).also { instance = it } }

        /** Remembers the filename a page gives a blob: link, since the URL itself doesn't carry one. */
        const val HOOK_JS = """
(function(){
  if (window.__harborHook) return; window.__harborHook = 1; window.__harborNames = {};
  var rec = function(a){ try { if (a && a.href && a.href.indexOf('blob:') === 0) window.__harborNames[a.href] = a.download || ''; } catch(e){} };
  var click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function(){ rec(this); return click.apply(this, arguments); };
  document.addEventListener('click', function(e){ var a = e.target && e.target.closest && e.target.closest('a[download]'); if (a) rec(a); }, true);
})();
"""

        fun blobJs(url: String): String {
            val u = org.json.JSONObject.quote(url)
            return """
(async function(u){
  var H = window.HarborDl, t = '';
  try {
    var b = await (await fetch(u)).blob();
    var name = (window.__harborNames || {})[u] || '';
    t = H.begin(name, b.type || '', b.size);
    if (!t) return;
    var CH = 1 << 20;
    for (var o = 0; o < b.size; o += CH) {
      var buf = new Uint8Array(await b.slice(o, o + CH).arrayBuffer()), s = '';
      for (var i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode.apply(null, buf.subarray(i, i + 0x8000));
      H.chunk(t, btoa(s));
    }
    H.end(t);
  } catch (e) { H.fail(t, String(e)); }
})($u);
"""
        }
    }
}
