package dev.dorki.harbor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dev.dorki.harbor.backend.FileApi
import dev.dorki.harbor.net.Downloads
import dev.dorki.harbor.net.describe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class Share(val name: String, val path: String, val comment: String)

data class FsEntry(val name: String, val path: String, val dir: Boolean, val size: Long)

/**
 * Browses, uploads to and downloads from the NAS through the TrueNAS API, so the shares
 * show up on their own and no SMB credentials are needed.
 */
class NasFiles(
    private val context: Context,
    private val scope: CoroutineScope,
    private val api: () -> FileApi?,
    private val ns: () -> String,
    private val client: (host: String) -> OkHttpClient,
    private val onError: (String) -> Unit,
) {
    private val _shares = MutableStateFlow<List<Share>>(emptyList())
    val shares: StateFlow<List<Share>> = _shares.asStateFlow()

    private val cache = ConcurrentHashMap<String, List<FsEntry>>()
    private val transfers = Downloads.get(context)
    private val downloadSlots = Semaphore(3)
    private val uploadSlots = Semaphore(2)

    suspend fun loadShares() {
        _shares.value = api()?.shares().orEmpty()
    }

    /** Forget everything about the previous NAS (called when switching). */
    fun reset() {
        _shares.value = emptyList()
        cache.clear()
    }

    private fun need(): FileApi = api() ?: throw IOException("Not connected to your NAS")

    fun cached(path: String): List<FsEntry>? = cache[path]

    suspend fun list(path: String): List<FsEntry> {
        val entries = need().list(path)
            .filterNot { it.name.startsWith(".") }
            .sortedWith(compareBy<FsEntry> { !it.dir }.thenBy { it.name.lowercase() })
        cache[path] = entries
        return entries
    }

    suspend fun mkdir(parent: String, name: String) = need().mkdir(parent, name)

    fun download(entries: List<FsEntry>) {
        entries.filterNot { it.dir }.forEach { e ->
            cachedFile(e)?.let { f ->
                transfers.saveLocal(f, e.name, mimeOf(e.name))
                return@forEach
            }
            scope.launch(Dispatchers.IO) {
                downloadSlots.withPermit {
                    try {
                        val url = need().downloadUrl(e)
                        val mime = mimeOf(e.name)
                        transfers.fetch(url, null, "attachment; filename=\"${e.name}\"", mime, null, client(java.net.URI(url).host), expectedSize = e.size)
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        transfers.downloadFailed(e.name, describe(t))
                    }
                }
            }
        }
    }

    /** Streams each picked file straight from the phone to [dir] on the NAS. */
    fun upload(dir: String, uris: List<Uri>, existing: Set<String>, onDone: () -> Unit) {
        val fs = api() ?: return onError("Not connected to your NAS")
        val taken = existing.toMutableSet()
        val resolver = context.contentResolver
        uris.forEach { uri ->
            val (rawName, size) = nameAndSize(uri)
            val name = uniqueName(rawName, taken).also { taken += it }
            val id = transfers.beginUpload(name, size, dir)
            scope.launch(Dispatchers.IO) {
                uploadSlots.withPermit {
                    try {
                        val mime = resolver.getType(uri) ?: mimeOf(name) ?: "application/octet-stream"
                        val body = object : RequestBody() {
                            override fun contentType() = mime.toMediaTypeOrNull()
                            override fun contentLength() = size
                            override fun writeTo(sink: BufferedSink) {
                                resolver.openInputStream(uri)?.use { input ->
                                    val buf = ByteArray(256 * 1024)
                                    var done = 0L
                                    var last = 0L
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        sink.write(buf, 0, n)
                                        done += n
                                        val now = System.nanoTime()
                                        if (now - last > 150_000_000L) {
                                            last = now
                                            transfers.progress(id, done)
                                        }
                                    }
                                    transfers.progress(id, done)
                                } ?: throw IOException("Couldn't read file")
                            }
                        }
                        fs.upload(dir, name, mime, size, body) { transfers.track(id, it) }
                        transfers.uploaded(id)
                        withContext(Dispatchers.Main) { onDone() }
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        transfers.failed(id, describe(t))
                    }
                }
            }
        }
    }

    // --- Opening files: fetched into the app cache, reused on the next open -------------

    private val cacheRoot get() = File(context.cacheDir, "files")

    private fun fileFor(e: FsEntry) = File(cacheRoot, "${ns().hashCode().toUInt()}-${e.path.hashCode().toUInt()}-${e.size}/${e.name}")

    fun cachedFile(e: FsEntry): File? = fileFor(e).takeIf { it.isFile && it.length() == e.size }

    /** Returns a local copy of [e], fetching it from the NAS if it isn't cached yet. */
    suspend fun fetch(e: FsEntry, onProgress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        cachedFile(e)?.let { it.setLastModified(System.currentTimeMillis()); return@withContext it }
        val url = need().downloadUrl(e)
        val call = client(java.net.URI(url).host).newCall(Request.Builder().url(url).build())
        val target = fileFor(e)
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".part")
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Server said ${resp.code}")
                val input = resp.body?.byteStream() ?: throw IOException("Empty response")
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done)
                    }
                }
            }
            if (!tmp.renameTo(target)) throw IOException("Couldn't save file")
            trimCache(keep = target)
            target
        } finally {
            if (!target.exists()) {
                call.cancel()
                tmp.delete()
            }
        }
    }

    /** Keeps the open-file cache under [limit], dropping the least recently opened first. */
    private fun trimCache(keep: File, limit: Long = 2L * 1024 * 1024 * 1024) {
        val files = cacheRoot.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toMutableList()
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= limit) break
            if (f == keep) continue
            total -= f.length()
            f.delete()
            f.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        }
    }

    private fun nameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return name to size
    }

    companion object {
        fun mimeOf(name: String): String? =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

        fun uniqueName(name: String, taken: Set<String>): String {
            if (name !in taken) return name
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
            var i = 1
            while ("$base ($i)$ext" in taken) i++
            return "$base ($i)$ext"
        }

        private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
