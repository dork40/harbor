package dev.dorki.harbor.backend

import dev.dorki.harbor.AppState
import dev.dorki.harbor.Disk
import dev.dorki.harbor.NasApp
import dev.dorki.harbor.Pool
import dev.dorki.harbor.data.NasKind
import dev.dorki.harbor.data.ServerConfig
import dev.dorki.harbor.net.ProbeResult
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/** Unraid, through its GraphQL API (Unraid 7.2+ or the Connect plugin; create a key under Settings → Management Access → API Keys). */
object UnraidBackend : NasBackend {
    override val kind = NasKind.UNRAID
    override val caps = Caps(apps = true, stats = true)

    override suspend fun probe(base: OkHttpClient, cfg: ServerConfig, host: String, pin: String?, timeoutMs: Long): ProbeResult =
        httpProbe(
            base, cfg, host, pin, timeoutMs,
            request = { Request.Builder().url("$it/login").build() },
            verify = { code, body ->
                when {
                    code in 301..308 && !cfg.https -> "Redirects to HTTPS: turn HTTPS on"
                    code >= 500 -> "Not Unraid (HTTP $code)"
                    code == 200 && !body.contains("unraid", ignoreCase = true) -> "Not Unraid"
                    else -> null
                }
            },
        )

    override fun session(env: SessionEnv): NasSession = UnraidSession(env)
}

private class UnraidSession(private val env: SessionEnv) : NasSession {
    private val client = env.base.forSession(env.cfg)
    private var containers = emptyMap<String, String>()

    private suspend fun gql(query: String): JSONObject {
        val req = Request.Builder().url("${baseUrl(env.cfg, env.host)}/graphql")
            .header("x-api-key", env.cfg.apiKey)
            .post(JSONObject().put("query", query).toString().toRequestBody("application/json".toMediaType())).build()
        val (code, body) = client.text(req)
        if (code == 401 || code == 403) throw AuthException("Unraid rejected the API key")
        val json = runCatching { JSONObject(body) }.getOrNull() ?: throw IOException("Unraid's API isn't available (HTTP $code)")
        val data = json.optJSONObject("data")
        val errors = json.optJSONArray("errors")
        if (data == null || (errors != null && errors.length() > 0 && data.length() == 0)) {
            val msg = errors?.optJSONObject(0)?.optString("message").orEmpty()
            if (msg.contains("unauthor", true) || msg.contains("forbidden", true) || msg.contains("api key", true)) throw AuthException("Unraid rejected the API key")
            throw IOException(msg.take(80).ifBlank { "Unraid's API said no" })
        }
        return data
    }

    override suspend fun login() {
        gql("{ __typename }")
    }

    override suspend fun run(push: (StatsUpdate) -> Unit) {
        var fails = 0
        while (true) {
            try {
                push(poll())
                fails = 0
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (++fails >= 3) return
            }
            delay(8_000)
        }
    }

    override suspend fun poll(): StatsUpdate {
        val info = runCatching { gql("{ info { os { hostname uptime release } } }").optJSONObject("info")?.optJSONObject("os") }.getOrNull()
        val array = runCatching { gql("{ array { state capacity { kilobytes { free used total } } disks { name size temp status } caches { name size temp status } } }").optJSONObject("array") }.getOrNull()
        val docker = runCatching { gql("{ docker { containers { id names state image } } }").optJSONObject("docker")?.optJSONArray("containers") }.getOrNull()
        val metrics = runCatching { gql("{ metrics { cpu { percentTotal } memory { total used } } }").optJSONObject("metrics") }.getOrNull()

        val kb = array?.optJSONObject("capacity")?.optJSONObject("kilobytes")
        val pools = array?.let { a ->
            val state = a.optString("state")
            listOf(Pool("Array", state.lowercase().replaceFirstChar { it.uppercase() }, state == "STARTED", (kb?.long("used") ?: 0) * 1024, (kb?.long("total") ?: 0) * 1024))
        }
        val disks = array?.let { a ->
            (a.optJSONArray("disks").objects() + a.optJSONArray("caches").objects()).filter { it.str("name") != null }.map {
                Disk(it.optString("name"), it.optString("name"), (it.long("size") ?: 0) * 1024, it.num("temp")?.toInt()?.takeIf { t -> t > 0 }, null)
            }
        }
        val apps = docker?.objects()?.map { c ->
            val name = (c.optJSONArray("names")?.optString(0) ?: c.optString("id")).removePrefix("/")
            NasApp(
                name = name, title = name.replaceFirstChar { it.uppercase() },
                state = when (c.optString("state").uppercase()) {
                    "RUNNING" -> AppState.RUNNING
                    "EXITED", "PAUSED", "CREATED" -> AppState.STOPPED
                    "RESTARTING" -> AppState.DEPLOYING
                    else -> AppState.UNKNOWN
                },
                icon = null, portals = emptyList(), version = c.str("image")?.substringAfterLast('/'), upgrade = false,
            ) to c.optString("id")
        }
        if (apps != null) containers = apps.associate { it.first.name to it.second }

        val boot = info?.str("uptime")
        val uptime = boot?.let { b -> b.toLongOrNull() ?: runCatching { (System.currentTimeMillis() - Instant.parse(b).toEpochMilli()) / 1000 }.getOrNull() }
        val cpu = metrics?.optJSONObject("cpu")?.num("percentTotal")
        val memTotal = metrics?.optJSONObject("memory")?.long("total")
        val memUsed = metrics?.optJSONObject("memory")?.long("used")
        return { st ->
            st.copy(
                hostname = info?.str("hostname") ?: st.hostname,
                version = info?.str("release")?.let { "Unraid $it" } ?: st.version,
                uptimeSec = uptime ?: st.uptimeSec,
                cpu = cpu?.toFloat() ?: st.cpu,
                memTotal = memTotal ?: st.memTotal,
                memUsed = memUsed ?: st.memUsed,
                pools = pools ?: st.pools,
                disks = disks ?: st.disks,
                apps = apps?.map { it.first }?.sortedBy { it.title.lowercase() } ?: st.apps,
            )
        }
    }

    override suspend fun appAction(app: String, action: String) {
        val id = containers[app] ?: throw IOException("Container not found")
        suspend fun m(verb: String) = gql("""mutation { docker { $verb(id: "$id") { id } } }""")
        when (action) {
            "start" -> m("start")
            "stop" -> m("stop")
            else -> { m("stop"); m("start") }
        }
    }

    override fun close() {}
}
