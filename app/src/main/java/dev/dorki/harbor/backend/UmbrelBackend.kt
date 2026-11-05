package dev.dorki.harbor.backend

import dev.dorki.harbor.AppState
import dev.dorki.harbor.NasApp
import dev.dorki.harbor.Pool
import dev.dorki.harbor.data.NasKind
import dev.dorki.harbor.data.ServerConfig
import dev.dorki.harbor.net.ProbeResult
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** umbrelOS, through the tRPC API its own dashboard uses (/trpc). Works with umbrelOS 1.x and 2.x. */
object UmbrelBackend : NasBackend {
    override val kind = NasKind.UMBREL
    override val caps = Caps(apps = true, appUpdates = true, logs = true, power = true, stats = true)

    override suspend fun probe(base: OkHttpClient, cfg: ServerConfig, host: String, pin: String?, timeoutMs: Long): ProbeResult =
        httpProbe(
            base, cfg, host, pin, timeoutMs,
            request = { Request.Builder().url("$it/trpc/system.online").build() },
            verify = { code, body ->
                when {
                    code in 301..308 -> "Redirects to HTTPS: turn HTTPS on"
                    runCatching { JSONObject(body).optJSONObject("result")?.optBoolean("data") }.getOrNull() != true -> "Not umbrelOS"
                    else -> null
                }
            },
        )

    override fun session(env: SessionEnv): NasSession = UmbrelSession(env)
}

private class UmbrelSession(private val env: SessionEnv) : NasSession {
    private val client = env.base.forSession(env.cfg).newBuilder().cookieJar(MemoryCookies()).build()
    private val base get() = "${baseUrl(env.cfg, env.host)}/trpc"
    private var token: String? = null

    private fun Request.Builder.auth() = apply { token?.let { header("Authorization", "Bearer $it") } }

    private fun unwrap(code: Int, body: String): Any? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: throw IOException("Unexpected answer from umbrelOS")
        val err = json.optJSONObject("error")
        if (err != null) {
            val message = err.optString("message").take(90)
            if (code == 401 || err.optJSONObject("data")?.optString("code") == "UNAUTHORIZED") throw AuthException(message.ifBlank { "Wrong password" })
            throw IOException(message.ifBlank { "umbrelOS error" })
        }
        return json.optJSONObject("result")?.opt("data")
    }

    private suspend fun query(proc: String, input: JSONObject? = null): Any? {
        val url = "$base/$proc".toHttpUrl().newBuilder().apply { input?.let { addQueryParameter("input", it.toString()) } }.build()
        val (code, body) = client.text(Request.Builder().url(url).auth().build())
        return unwrap(code, body)
    }

    private suspend fun mutate(proc: String, input: JSONObject? = null): Any? {
        val json = (input ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())
        val (code, body) = client.text(Request.Builder().url("$base/$proc").auth().post(json).build())
        return unwrap(code, body)
    }

    override suspend fun login() {
        token = null
        val t = mutate("user.login", JSONObject().put("password", env.cfg.apiKey)) as? String
        token = t?.takeIf { it.isNotBlank() } ?: throw AuthException("Wrong password")
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
        val cpu = (query("system.cpuUsage") as? JSONObject)?.num("totalUsed")
        val mem = runCatching { query("system.systemMemoryUsage") as? JSONObject }.getOrNull()
        val disk = if (full) runCatching { query("system.systemDiskUsage") as? JSONObject }.getOrNull() else null
        val uptime = if (full) runCatching { query("system.uptime") as? Number }.getOrNull() else null
        val name = if (full) runCatching { query("system.deviceName") as? String }.getOrNull() else null
        val version = if (full) runCatching { (query("system.version") as? JSONObject)?.str("name") }.getOrNull() else null
        val apps = if (full) (query("apps.list") as? JSONArray)?.let(::parseApps) else null
        val updates = if (full) runCatching {
            (query("apps.updates") as? JSONArray).objects().map { it.optString("id") }.toSet()
        }.getOrDefault(emptySet()) else emptySet()
        val pool = disk?.let {
            val size = it.long("size") ?: 0
            Pool("System", "ONLINE", true, it.long("totalUsed") ?: 0, size).takeIf { size > 0 }
        }
        return { st ->
            st.copy(
                hostname = name ?: st.hostname,
                version = version ?: st.version,
                uptimeSec = uptime?.toLong() ?: st.uptimeSec,
                cpu = cpu?.toFloat() ?: st.cpu,
                memTotal = mem?.long("size") ?: st.memTotal,
                memUsed = mem?.long("totalUsed") ?: st.memUsed,
                pools = pool?.let(::listOf) ?: st.pools,
                apps = apps?.map { if (full) it.copy(upgrade = it.name in updates) else it } ?: st.apps,
            )
        }
    }

    private fun parseApps(arr: JSONArray) = arr.objects().filter { it.has("name") }.map { a ->
        val id = a.optString("id")
        val path = a.str("path")?.let { if (it.startsWith("/")) it else "/$it" } ?: "/"
        NasApp(
            name = id,
            title = a.optString("name"),
            state = when (a.optString("state")) {
                "ready", "running" -> AppState.RUNNING
                "stopped" -> AppState.STOPPED
                "stopping", "uninstalling" -> AppState.STOPPING
                "starting", "restarting", "installing", "updating" -> AppState.DEPLOYING
                else -> AppState.UNKNOWN
            },
            icon = a.str("icon")?.takeIf { it.startsWith("http") },
            portals = a.long("port")?.takeIf { it > 0 }?.let { listOf("http://0.0.0.0:$it$path") } ?: emptyList(),
            version = a.str("version"),
            upgrade = false,
        )
    }

    override suspend fun appAction(app: String, action: String) {
        mutate("apps.$action", JSONObject().put("appId", app))
    }

    override suspend fun upgradeApps(names: List<String>) {
        names.forEach { mutate("apps.update", JSONObject().put("appId", it)) }
    }

    override suspend fun power(shutdown: Boolean) {
        mutate(if (shutdown) "system.shutdown" else "system.restart")
    }

    override suspend fun logs(app: String): List<String> =
        (query("apps.logs", JSONObject().put("appId", app)) as? String).orEmpty().lines().takeLast(400)

    override fun close() {
        // The session cookie dies with this client; the dashboard token expires on its own.
    }
}
