package dev.dorki.harbor.backend

import dev.dorki.harbor.AppState
import dev.dorki.harbor.Disk
import dev.dorki.harbor.FsEntry
import dev.dorki.harbor.HarborViewModel
import dev.dorki.harbor.NasApp
import dev.dorki.harbor.NetSample
import dev.dorki.harbor.Pool
import dev.dorki.harbor.Share
import dev.dorki.harbor.SnapView
import dev.dorki.harbor.data.NasKind
import dev.dorki.harbor.data.ServerConfig
import dev.dorki.harbor.net.PinningTrustManager
import dev.dorki.harbor.net.ProbeResult
import dev.dorki.harbor.net.RpcSocket
import dev.dorki.harbor.net.pinned
import dev.dorki.harbor.net.probe
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.Call
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.cancelChildren

object TrueNasBackend : NasBackend {
    override val kind = NasKind.TRUENAS
    override val caps = Caps(apps = true, appUpdates = true, logs = true, snapshots = true, power = true, files = true, stats = true)

    override suspend fun probe(base: OkHttpClient, cfg: ServerConfig, host: String, pin: String?, timeoutMs: Long): ProbeResult =
        probe(base, host, pin, timeoutMs, cfg.https)

    override fun session(env: SessionEnv): NasSession = TrueNasSession(env)
}

/** JSON-RPC 2.0 over the TrueNAS websocket at /api/current. */
private class TrueNasSession(private val env: SessionEnv) : NasSession {
    private var sock: RpcSocket? = null
    private val s get() = sock ?: throw IOException("Not connected")

    override suspend fun login() {
        val client = if (env.cfg.https) env.base.pinned(PinningTrustManager(env.cfg.pin)) else env.base
        val opened = RpcSocket.open(client, env.host, env.cfg.https)
        sock = opened
        try {
            if (opened.call("auth.login_with_api_key", env.cfg.apiKey) != true) throw AuthException("TrueNAS rejected the API key")
        } catch (e: Throwable) {
            opened.close()
            throw e
        }
    }

    override suspend fun run(push: (StatsUpdate) -> Unit) = coroutineScope {
        val sk = s
        launch {
            sk.events.collect { ev ->
                if (ev.optString("collection") == "reporting.realtime") parseRealtime(ev.optJSONObject("fields"))?.let(push)
            }
        }
        runCatching { sk.call("core.subscribe", "reporting.realtime") }
        launch {
            while (isActive) {
                push(poll())
                delay(15_000)
            }
        }
        val cause = sk.closed.await()
        android.util.Log.w("Harbor", "API socket closed", cause)
        coroutineContext.cancelChildren()
    }

    override suspend fun poll(): StatsUpdate {
        val sk = s
        val info = runCatching { sk.call("system.info") as? JSONObject }.getOrNull()
        val pools = runCatching { sk.call("pool.query") as? JSONArray }.getOrNull()
        val apps = runCatching { sk.call("app.query") as? JSONArray }.getOrNull()
        val disks = loadDisks(sk)
        return { st ->
            st.copy(
                hostname = info?.optString("hostname")?.takeIf { it.isNotBlank() } ?: st.hostname,
                version = info?.optString("version")?.takeIf { it.isNotBlank() } ?: st.version,
                uptimeSec = info?.optDouble("uptime_seconds")?.takeIf { !it.isNaN() }?.toLong() ?: st.uptimeSec,
                cores = info?.optInt("cores")?.takeIf { it > 0 } ?: st.cores,
                memTotal = info?.optLong("physmem")?.takeIf { it > 0 } ?: st.memTotal,
                pools = pools?.let(::parsePools) ?: st.pools,
                apps = apps?.let(::parseApps) ?: st.apps,
                disks = disks ?: st.disks,
            )
        }
    }

    private fun parsePools(arr: JSONArray) = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        val size = o.optLong("size")
        val alloc = o.optLong("allocated").takeIf { it > 0 } ?: (size - o.optLong("free"))
        Pool(o.optString("name"), o.optString("status"), o.optBoolean("healthy", true), alloc.coerceAtLeast(0), size)
    }

    private fun parseApps(arr: JSONArray) = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        val meta = o.optJSONObject("metadata")
        val portals = o.optJSONObject("portals")?.let { p ->
            p.keys().asSequence().mapNotNull { p.optString(it).takeIf { u -> u.startsWith("http") } }.toList()
        } ?: emptyList()
        NasApp(
            name = o.optString("name"),
            title = meta?.optString("title")?.takeIf { it.isNotBlank() } ?: o.optString("name").replaceFirstChar { it.uppercase() },
            state = AppState.of(o.optString("state")),
            icon = meta?.optString("icon")?.takeIf { it.startsWith("http") },
            portals = portals,
            version = o.optString("human_version").takeIf { it.isNotBlank() },
            upgrade = o.optBoolean("upgrade_available"),
        )
    }.sortedBy { it.title.lowercase() }

    private fun parseRealtime(f: JSONObject?): StatsUpdate? {
        f ?: return null
        val cpuObj = f.optJSONObject("cpu")
        val cpu = (cpuObj?.optJSONObject("cpu") ?: cpuObj?.optJSONObject("average"))?.optDouble("usage")?.takeIf { !it.isNaN() }
        val sample = parseNet(f.optJSONObject("interfaces"))
        val mem = f.optJSONObject("memory")
        val total = mem?.optLong("physical_memory_total")?.takeIf { it > 0 }
        val avail = mem?.optLong("physical_memory_available")?.takeIf { it > 0 }
        return { st ->
            st.copy(
                cpu = cpu?.toFloat() ?: st.cpu,
                memTotal = total ?: st.memTotal,
                memUsed = if (total != null && avail != null) total - avail else st.memUsed,
                net = if (sample != null) (st.net + sample).takeLast(40) else st.net,
            )
        }
    }

    private fun parseNet(ifs: JSONObject?): NetSample? {
        ifs ?: return null
        var down = 0.0
        var up = 0.0
        var seen = false
        val skip = listOf("lo", "veth", "docker", "br-", "kube", "cni", "flannel", "cali")
        ifs.keys().forEach { name ->
            if (skip.any { name.startsWith(it) }) return@forEach
            val o = ifs.optJSONObject(name) ?: return@forEach
            o.keys().forEach { key ->
                val v = o.optDouble(key)
                if (v.isNaN()) return@forEach
                val k = key.lowercase()
                if (!("rate" in k || "speed" in k)) return@forEach
                when {
                    "receiv" in k || k.startsWith("rx") -> { down += v; seen = true }
                    "sent" in k || "transmit" in k || k.startsWith("tx") -> { up += v; seen = true }
                }
            }
        }
        return if (seen) NetSample(down.toFloat(), up.toFloat()) else null
    }

    private suspend fun loadDisks(sk: RpcSocket): List<Disk>? {
        val arr = runCatching {
            sk.call("disk.query", JSONArray(), JSONObject().put("select", JSONArray(listOf("name", "model", "size", "pool")))) as? JSONArray
        }.getOrNull() ?: return null
        val names = JSONArray().also { n -> for (i in 0 until arr.length()) n.put(arr.getJSONObject(i).optString("name")) }
        val temps = runCatching { sk.call("disk.temperatures", names) as? JSONObject }.getOrNull()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val name = o.optString("name")
            val t = temps?.optDouble(name)?.takeIf { !it.isNaN() }?.toInt()
            Disk(name, o.optString("model").trim().ifBlank { "Disk" }, o.optLong("size"), t, o.optString("pool").takeIf { it.isNotBlank() && it != "null" })
        }.sortedBy { it.name }
    }

    override suspend fun appAction(app: String, action: String) {
        val method = when (action) {
            "start" -> "app.start"
            "stop" -> "app.stop"
            "upgrade" -> "app.upgrade"
            else -> "app.redeploy"
        }
        s.call(method, app)
    }

    override suspend fun upgradeApps(names: List<String>) {
        val list = JSONArray().also { a -> names.forEach { a.put(JSONObject().put("app_name", it)) } }
        s.call("app.upgrade_bulk", list)
    }

    override suspend fun power(shutdown: Boolean) {
        s.call(if (shutdown) "system.shutdown" else "system.reboot", "Requested from Harbor")
    }

    override suspend fun logs(app: String): List<String> {
        val sk = s
        val ids = sk.call("app.container_ids", app, JSONObject().put("alive_only", false)) as? JSONObject
        val id = ids?.keys()?.asSequence()?.firstOrNull() ?: throw IOException("No containers found")
        val lines = mutableListOf<String>()
        val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
        val name = "app.container_log_follow:" + JSONObject().put("app_name", app).put("container_id", id).put("tail_lines", 200)
        coroutineScope {
            val collector = launch {
                sk.events.collect { ev ->
                    if (ev.optString("collection").startsWith("app.container_log_follow")) {
                        ev.optJSONObject("fields")?.optString("data")?.let { lines += it.replace(ansi, "").trimEnd() }
                    }
                }
            }
            val subId = sk.call("core.subscribe", name) as? String
            delay(2_500)
            collector.cancel()
            subId?.let { runCatching { sk.call("core.unsubscribe", it) } }
        }
        return lines.takeLast(200)
    }

    override suspend fun snapshots(): SnapView {
        val sk = s
        val count = (sk.call("pool.snapshot.query", JSONArray(), JSONObject().put("count", true)) as? Number)?.toInt()
        val opts = JSONObject().put("select", JSONArray(listOf("name"))).put("limit", 12)
        val newest = runCatching {
            sk.call("pool.snapshot.query", JSONArray(), JSONObject(opts.toString()).put("order_by", JSONArray(listOf("-createtxg")))) as? JSONArray
        }.getOrNull() ?: sk.call("pool.snapshot.query", JSONArray(), opts) as? JSONArray
        val names = newest?.let { a -> List(a.length()) { a.getJSONObject(it).optString("name") } } ?: emptyList()
        return SnapView(loading = false, count = count, recent = names)
    }

    override suspend fun takeSnapshot(pool: String): String {
        val name = "harbor-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        s.call("pool.snapshot.create", JSONObject().put("dataset", pool).put("name", name).put("recursive", true))
        return "$pool@$name"
    }

    override val files: FileApi = object : FileApi {
        private fun JSONArray?.objs() = (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }

        override suspend fun shares(): List<Share> {
            val smb = runCatching { s.call("sharing.smb.query", timeoutMs = 60_000) as? JSONArray }.getOrNull()
            val nfs = runCatching { s.call("sharing.nfs.query", timeoutMs = 60_000) as? JSONArray }.getOrNull()
            val out = linkedMapOf<String, Share>()
            smb.objs().filter { it.optBoolean("enabled", true) && !it.optBoolean("locked") }.forEach {
                val path = it.optString("path")
                if (path.startsWith("/mnt/")) out[path] = Share(it.optString("name"), path, it.optString("comment"))
            }
            nfs.objs().filter { it.optBoolean("enabled", true) && !it.optBoolean("locked") }.forEach {
                val path = it.optString("path")
                if (path.startsWith("/mnt/") && path !in out) out[path] = Share(path.substringAfterLast('/'), path, it.optString("comment"))
            }
            return out.values.sortedBy { it.name.lowercase() }
        }

        override suspend fun list(path: String): List<FsEntry> {
            val arr = s.call(
                "filesystem.listdir",
                path, JSONArray(), JSONObject().put("select", JSONArray(listOf("name", "path", "type", "size"))),
                timeoutMs = 60_000,
            ) as? JSONArray ?: JSONArray()
            return arr.objs()
                .filterNot { it.optString("name").startsWith(".") }
                .map { FsEntry(it.optString("name"), it.optString("path"), it.optString("type") == "DIRECTORY", it.optLong("size")) }
        }

        override suspend fun mkdir(parent: String, name: String) {
            s.call("filesystem.mkdir", JSONObject().put("path", "$parent/$name"), timeoutMs = 60_000)
        }

        override suspend fun downloadUrl(e: FsEntry): String {
            val h = env.hostNow() ?: throw IOException("Not connected to your NAS")
            val r = s.call("core.download", "filesystem.get", JSONArray().put(e.path), e.name, timeoutMs = 60_000) as JSONArray
            return "${if (env.cfg.https) "https" else "http"}://$h${r.getString(1)}"
        }

        override suspend fun upload(dir: String, name: String, mime: String, size: Long, body: RequestBody, track: (Call) -> Unit) {
            val h = env.hostNow() ?: throw IOException("Not connected to your NAS")
            val data = JSONObject().put("method", "filesystem.put").put("params", JSONArray().put("$dir/$name"))
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("data", data.toString())
                .addFormDataPart("file", name, body)
                .build()
            val req = Request.Builder()
                .url("${if (env.cfg.https) "https" else "http"}://$h/_upload")
                .header("Authorization", "Bearer ${env.cfg.apiKey}")
                .post(form)
                .build()
            val http = env.transferClient(HarborViewModel.bareHost(h)).newBuilder()
                .writeTimeout(60, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
            val jobId = withContext(Dispatchers.IO) {
                http.newCall(req).also(track).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IOException("Upload refused (${resp.code})")
                    JSONObject(text).optInt("job_id", -1)
                }
            }
            if (jobId > 0) waitForJob(jobId)
        }

        private suspend fun waitForJob(jobId: Int) {
            val filter = JSONArray().put(JSONArray().put("id").put("=").put(jobId))
            var misses = 0
            repeat(600) {
                val job = try {
                    ((env.current() as? TrueNasSession ?: this@TrueNasSession).s.call("core.get_jobs", filter, timeoutMs = 60_000) as? JSONArray)?.optJSONObject(0)
                } catch (e: IOException) {
                    if (++misses > 30) throw e
                    delay(700)
                    return@repeat
                }
                when (job?.optString("state")) {
                    "SUCCESS" -> return
                    "FAILED", "ABORTED" -> throw IOException(job.optString("error").lineSequence().firstOrNull()?.take(80) ?: "Upload failed")
                }
                delay(300)
            }
        }
    }

    override fun close() {
        sock?.close()
        sock = null
    }
}
