package dev.dorki.harbor.backend

import dev.dorki.harbor.AppState
import dev.dorki.harbor.FsEntry
import dev.dorki.harbor.NasApp
import dev.dorki.harbor.Pool
import dev.dorki.harbor.Share
import dev.dorki.harbor.data.NasKind
import dev.dorki.harbor.data.ServerConfig
import dev.dorki.harbor.net.ProbeResult
import kotlinx.coroutines.delay
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** CasaOS, and ZimaOS which is built on the same services (/v1, /v2/app_management). */
class CasaBackend(override val kind: NasKind) : NasBackend {
    override val caps = Caps(apps = true, logs = true, power = true, files = true, stats = true)

    override suspend fun probe(base: OkHttpClient, cfg: ServerConfig, host: String, pin: String?, timeoutMs: Long): ProbeResult =
        httpProbe(
            base, cfg, host, pin, timeoutMs,
            request = { Request.Builder().url("$it/v1/users/status").build() },
            verify = { code, body ->
                when {
                    code in 301..308 -> "Redirects to HTTPS: turn HTTPS on"
                    runCatching { JSONObject(body).has("success") }.getOrNull() != true -> "Not ${kind.label}"
                    else -> null
                }
            },
        )

    override fun session(env: SessionEnv): NasSession = CasaSession(env)
}

private val JSON = "application/json".toMediaType()

private class CasaSession(private val env: SessionEnv) : NasSession, FileApi {
    private val client = env.base.forSession(env.cfg)
    private val base get() = baseUrl(env.cfg, env.host)
    private var token: String? = null

    private fun req(path: String) = Request.Builder().url(base + path).apply { token?.let { header("Authorization", it) } }

    private suspend fun call(r: Request): JSONObject {
        val (code, body) = client.text(r)
        if (code == 401) throw AuthException("Signed out by the NAS")
        val json = runCatching { JSONObject(body) }.getOrNull() ?: throw IOException("Unexpected answer from the NAS")
        val ok = json.optInt("success", code)
        if (ok != 200 && code >= 400) throw IOException(json.optString("message").take(90).ifBlank { "NAS error $code" })
        return json
    }

    override suspend fun login() {
        token = null
        val body = JSONObject().put("username", env.cfg.username).put("password", env.cfg.apiKey).toString().toRequestBody(JSON)
        val (_, text) = client.text(req("/v1/users/login").post(body).build())
        val t = runCatching { JSONObject(text).optJSONObject("data")?.optJSONObject("token")?.str("access_token") }.getOrNull()
        token = t ?: throw AuthException("Wrong username or password")
    }

    override suspend fun run(push: (StatsUpdate) -> Unit) {
        var fails = 0
        var tick = 0
        while (true) {
            try {
                push(poll(full = tick++ % 3 == 0))
                fails = 0
            } catch (e: AuthException) {
                runCatching { login() }.onFailure { if (++fails >= 3) return }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (++fails >= 3) return
            }
            delay(5_000)
        }
    }

    override suspend fun poll(): StatsUpdate = poll(full = true)

    private suspend fun poll(full: Boolean): StatsUpdate {
        val u = call(req("/v1/sys/utilization").build()).optJSONObject("data")
        val cpu = u?.optJSONObject("cpu")
        val mem = u?.optJSONObject("mem")
        val disk = u?.optJSONObject("sys_disk")
        val grid = if (full) runCatching { call(req("/v2/app_management/web/appgrid").build()).optJSONArray("data") }.getOrNull() else null
        val pool = disk?.let {
            val size = it.long("size") ?: 0
            val used = it.long("used") ?: (size - (it.long("avail") ?: 0))
            Pool("System", "ONLINE", true, used.coerceAtLeast(0), size).takeIf { size > 0 }
        }
        return { st ->
            st.copy(
                cpu = cpu?.num("percent")?.toFloat() ?: st.cpu,
                cores = cpu?.num("num")?.toInt() ?: st.cores,
                memTotal = mem?.long("total") ?: st.memTotal,
                memUsed = mem?.long("used") ?: st.memUsed,
                pools = pool?.let(::listOf) ?: st.pools,
                apps = grid?.let(::parseApps) ?: st.apps,
            )
        }
    }

    private fun parseApps(arr: JSONArray) = arr.objects().filter { it.str("name") != null }.map { a ->
        val name = a.optString("name")
        val title = a.optJSONObject("title")?.let { t -> t.str("en_us") ?: t.str("en_US") ?: t.keys().asSequence().firstOrNull()?.let(t::optString) } ?: name
        val scheme = a.str("scheme") ?: "http"
        val port = a.str("port")
        val path = a.str("index")?.let { if (it.startsWith("/")) it else "/$it" } ?: "/"
        NasApp(
            name = name,
            title = title,
            state = when (a.optString("status").lowercase()) {
                "running" -> AppState.RUNNING
                "stopped", "exited", "created" -> AppState.STOPPED
                "restarting", "starting" -> AppState.DEPLOYING
                else -> AppState.UNKNOWN
            },
            icon = a.str("icon")?.takeIf { it.startsWith("http") },
            portals = port?.let { listOf("$scheme://${a.str("hostname") ?: "0.0.0.0"}:$it$path") } ?: emptyList(),
            version = null,
            upgrade = false,
        )
    }

    override suspend fun appAction(app: String, action: String) {
        val body = JSONObject.quote(action).toRequestBody(JSON)
        call(req("/v2/app_management/compose/${enc(app)}/status").put(body).build())
    }

    override suspend fun logs(app: String): List<String> {
        val j = call(req("/v2/app_management/compose/${enc(app)}/logs?lines=400").build())
        return j.optString("data").lines().takeLast(400)
    }

    override suspend fun power(shutdown: Boolean) {
        call(req("/v1/sys/state/${if (shutdown) "off" else "restart"}").put("".toRequestBody(JSON)).build())
    }

    // --- files ---

    override val files: FileApi get() = this

    override suspend fun shares(): List<Share> = listOf(Share("DATA", "/DATA", "Your files on this NAS"), Share("media", "/media", "Other drives"))

    override suspend fun list(path: String): List<FsEntry> {
        val url = "$base/v1/folder".toHttpUrl().newBuilder().addQueryParameter("path", path).build()
        val j = call(Request.Builder().url(url).apply { token?.let { header("Authorization", it) } }.build())
        val data = j.opt("data")
        val rows = (data as? JSONObject)?.optJSONArray("content") ?: data as? JSONArray
        return rows.objects().filterNot { it.optString("name").startsWith(".") }.map {
            FsEntry(it.optString("name"), it.str("path") ?: "$path/${it.optString("name")}", it.optBoolean("is_dir"), it.long("size") ?: 0)
        }.sortedWith(compareBy<FsEntry> { !it.dir }.thenBy { it.name.lowercase() })
    }

    override suspend fun mkdir(parent: String, name: String) {
        val body = JSONObject().put("path", "$parent/$name").toString().toRequestBody(JSON)
        call(req("/v1/folder").post(body).build())
    }

    override suspend fun downloadUrl(e: FsEntry): String =
        "$base/v1/file".toHttpUrl().newBuilder().addQueryParameter("path", e.path).addQueryParameter("token", token ?: "").build().toString()

    override suspend fun upload(dir: String, name: String, mime: String, size: Long, body: RequestBody, track: (Call) -> Unit) {
        throw UnsupportedException("Uploads aren't supported on this NAS yet")
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    override fun close() {
        token = null
    }
}
