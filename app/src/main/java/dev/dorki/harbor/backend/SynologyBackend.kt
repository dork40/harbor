package dev.dorki.harbor.backend

import dev.dorki.harbor.AppState
import dev.dorki.harbor.Disk
import dev.dorki.harbor.FsEntry
import dev.dorki.harbor.NasApp
import dev.dorki.harbor.NetSample
import dev.dorki.harbor.Pool
import dev.dorki.harbor.Share
import dev.dorki.harbor.data.NasKind
import dev.dorki.harbor.data.ServerConfig
import dev.dorki.harbor.net.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Synology DiskStation Manager, through the DSM Web API (the same one DS apps use). */
object SynologyBackend : NasBackend {
    override val kind = NasKind.SYNOLOGY
    override val caps = Caps(apps = true, power = true, files = true, stats = true)

    override suspend fun probe(base: OkHttpClient, cfg: ServerConfig, host: String, pin: String?, timeoutMs: Long): ProbeResult =
        httpProbe(
            base, cfg, host, pin, timeoutMs,
            request = { Request.Builder().url("$it/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=SYNO.API.Auth").build() },
            verify = { code, body ->
                when {
                    code in 301..308 -> "Redirects to HTTPS: turn HTTPS on"
                    code != 200 -> "Not a Synology (HTTP $code)"
                    runCatching { JSONObject(body).optJSONObject("data")?.has("SYNO.API.Auth") }.getOrNull() != true -> "Not a Synology"
                    else -> null
                }
            },
        )

    override fun session(env: SessionEnv): NasSession = SynologySession(env)
}

private class SynoException(val code: Int, message: String) : IOException(message)

private class SynologySession(private val env: SessionEnv) : NasSession {
    private val client = env.base.forSession(env.cfg)
    private val root get() = "${baseUrl(env.cfg, env.host)}/webapi"
    @Volatile private var sid: String? = null
    private var containers = emptyMap<String, String>()

    override suspend fun login() {
        val form = FormBody.Builder()
            .add("api", "SYNO.API.Auth").add("version", "3").add("method", "login")
            .add("account", env.cfg.username).add("passwd", env.cfg.apiKey)
            .add("session", "Harbor").add("format", "sid").build()
        val (_, body) = client.text(Request.Builder().url("$root/auth.cgi").post(form).build())
        val json = runCatching { JSONObject(body) }.getOrNull() ?: throw IOException("Not a Synology")
        if (!json.optBoolean("success")) {
            throw AuthException(
                when (json.optJSONObject("error")?.optInt("code")) {
                    400 -> "Wrong username or password"
                    401 -> "That account is disabled"
                    402 -> "That account can't sign in from here"
                    403, 404 -> "Two-step sign-in isn't supported. Use an account without it."
                    else -> "Synology refused the sign-in"
                }
            )
        }
        sid = json.getJSONObject("data").getString("sid")
    }

    private fun urlFor(api: String, method: String, version: Int, params: Array<out Pair<String, String>>) =
        "$root/entry.cgi".toHttpUrl().newBuilder()
            .addQueryParameter("api", api).addQueryParameter("version", version.toString()).addQueryParameter("method", method)
            .addQueryParameter("_sid", sid ?: "")
            .also { b -> params.forEach { (k, v) -> b.addQueryParameter(k, v) } }
            .build().toString()

    /** Calls a DSM API and returns its `data`; signs in again once if the session expired. */
    suspend fun call(api: String, method: String, version: Int, vararg params: Pair<String, String>, retry: Boolean = true): JSONObject {
        val (_, body) = client.text(Request.Builder().url(urlFor(api, method, version, params)).build())
        val json = runCatching { JSONObject(body) }.getOrNull() ?: throw IOException("Unexpected answer from the NAS")
        if (json.optBoolean("success")) return json.optJSONObject("data") ?: JSONObject()
        val code = json.optJSONObject("error")?.optInt("code") ?: -1
        if (retry && code in listOf(105, 106, 107, 119)) {
            login()
            return call(api, method, version, *params, retry = false)
        }
        throw SynoException(code, if (code == 105) "This account isn't allowed to do that" else "Synology error $code")
    }

    override suspend fun run(push: (StatsUpdate) -> Unit) {
        var fails = 0
        var tick = 0
        while (true) {
            try {
                push(live())
                if (tick++ % 5 == 0) push(poll())
                fails = 0
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (++fails >= 3) return
            }
            delay(3_000)
        }
    }

    private var lastRx = 0.0
    private suspend fun live(): StatsUpdate {
        val d = call("SYNO.Core.System.Utilization", "get", 1, "type" to "current", "resource" to """["cpu","memory","network"]""")
        val cpu = d.optJSONObject("cpu")?.let { (it.num("user_load") ?: 0.0) + (it.num("system_load") ?: 0.0) }
        val mem = d.optJSONObject("memory")
        val total = mem?.long("total_real")?.times(1024)
        val avail = (mem?.long("avail_real") ?: 0) * 1024 + ((mem?.long("cached") ?: 0) * 1024)
        val tot = d.optJSONArray("network").objects().firstOrNull { it.optString("device") == "total" }
        val sample = tot?.let { NetSample((it.num("rx") ?: 0.0).toFloat(), (it.num("tx") ?: 0.0).toFloat()) }
        return { st ->
            st.copy(
                cpu = cpu?.toFloat() ?: st.cpu,
                memTotal = total ?: st.memTotal,
                memUsed = if (total != null) (total - avail).coerceAtLeast(0) else st.memUsed,
                net = if (sample != null) (st.net + sample).takeLast(40) else st.net,
            )
        }
    }

    override suspend fun poll(): StatsUpdate {
        val info = runCatching { call("SYNO.DSM.Info", "getinfo", 2) }.getOrNull()
        val net = runCatching { call("SYNO.Core.Network", "get", 1) }.getOrNull()
        val storage = runCatching { call("SYNO.Storage.CGI.Storage", "load_info", 1) }.getOrNull()
        val docker = runCatching {
            call("SYNO.Docker.Container", "list", 1, "limit" to "-1", "offset" to "0", "type" to "all")
        }.getOrNull()

        val pools = storage?.optJSONArray("volumes").objects().map { v ->
            val size = v.optJSONObject("size")
            val status = v.optString("status")
            Pool(
                name = v.str("desc") ?: v.optString("id").replace("_", " ").replaceFirstChar { it.uppercase() },
                status = status.uppercase(), healthy = status == "normal",
                used = size?.long("used") ?: 0, total = size?.long("total") ?: 0,
            )
        }
        val disks = storage?.optJSONArray("disks").objects().map { d ->
            Disk(
                name = d.str("name") ?: d.optString("id"), model = d.str("model")?.trim() ?: "Disk",
                size = d.long("size_total") ?: 0, tempC = d.num("temp")?.toInt()?.takeIf { it > 0 }, pool = null,
            )
        }
        val apps = docker?.optJSONArray("containers").objects().map { c ->
            val name = c.optString("name")
            NasApp(
                name = name, title = name.replaceFirstChar { it.uppercase() },
                state = when (c.optString("status")) {
                    "running" -> AppState.RUNNING
                    "stopped", "exited", "created" -> AppState.STOPPED
                    "restarting", "starting" -> AppState.DEPLOYING
                    else -> if (c.optBoolean("is_running")) AppState.RUNNING else AppState.UNKNOWN
                },
                icon = null, portals = emptyList(), version = c.str("image"), upgrade = false,
            )
        }.sortedBy { it.title.lowercase() }
        return { st ->
            st.copy(
                hostname = net?.str("server_name") ?: st.hostname,
                version = info?.str("version_string") ?: st.version,
                uptimeSec = info?.long("uptime") ?: st.uptimeSec,
                memTotal = info?.long("ram")?.times(1024 * 1024) ?: st.memTotal,
                pools = if (storage != null) pools else st.pools,
                disks = if (storage != null) disks else st.disks,
                apps = if (docker != null) apps else st.apps,
            )
        }
    }

    override suspend fun appAction(app: String, action: String) {
        val method = when (action) { "start" -> "start"; "stop" -> "stop"; else -> "restart" }
        call("SYNO.Docker.Container", method, 1, "name" to JSONObject.quote(app))
    }

    override suspend fun power(shutdown: Boolean) {
        call("SYNO.Core.System", if (shutdown) "shutdown" else "reboot", 1)
    }

    override val files: FileApi = object : FileApi {
        override suspend fun shares(): List<Share> =
            call("SYNO.FileStation.List", "list_share", 2).optJSONArray("shares").objects()
                .map { Share(it.optString("name"), it.optString("path"), "") }
                .sortedBy { it.name.lowercase() }

        override suspend fun list(path: String): List<FsEntry> =
            call("SYNO.FileStation.List", "list", 2, "folder_path" to path, "additional" to """["size","type"]""", "limit" to "0")
                .optJSONArray("files").objects()
                .filterNot { it.optString("name").startsWith(".") }
                .map { FsEntry(it.optString("name"), it.optString("path"), it.optBoolean("isdir"), it.optJSONObject("additional")?.long("size") ?: 0) }

        override suspend fun mkdir(parent: String, name: String) {
            call("SYNO.FileStation.CreateFolder", "create", 2, "folder_path" to parent, "name" to name)
        }

        override suspend fun downloadUrl(e: FsEntry): String =
            urlFor("SYNO.FileStation.Download", "download", 2, arrayOf("path" to JSONArray().put(e.path).toString(), "mode" to "download"))

        override suspend fun upload(dir: String, name: String, mime: String, size: Long, body: RequestBody, track: (Call) -> Unit) {
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("path", dir).addFormDataPart("create_parents", "true").addFormDataPart("overwrite", "false")
                .addFormDataPart("file", name, body).build()
            val h = env.hostNow() ?: env.host
            val http = env.transferClient(dev.dorki.harbor.HarborViewModel.bareHost(h)).newBuilder()
                .writeTimeout(60, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
            val url = "${baseUrl(env.cfg, h)}/webapi/entry.cgi?api=SYNO.FileStation.Upload&version=2&method=upload&_sid=${sid ?: ""}"
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(url).post(form).build()).also(track).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IOException("Upload refused (${resp.code})")
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    if (json?.optBoolean("success") != true) throw IOException("Synology refused the upload (${json?.optJSONObject("error")?.optInt("code") ?: resp.code})")
                }
            }
        }
    }

    override fun close() {
        val s = sid ?: return
        sid = null
        // Fire and forget: sign out so the NAS doesn't pile up sessions.
        runCatching {
            client.newCall(Request.Builder().url("$root/auth.cgi?api=SYNO.API.Auth&version=3&method=logout&session=Harbor&_sid=$s").build())
                .enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: okhttp3.Response) = response.close()
                })
        }
    }
}
