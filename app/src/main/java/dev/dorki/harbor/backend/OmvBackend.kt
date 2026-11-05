package dev.dorki.harbor.backend

import dev.dorki.harbor.Disk
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

/** OpenMediaVault, through the JSON-RPC endpoint its own web UI uses (/rpc.php). */
object OmvBackend : NasBackend {
    override val kind = NasKind.OMV
    override val caps = Caps(power = true, stats = true)

    override suspend fun probe(base: OkHttpClient, cfg: ServerConfig, host: String, pin: String?, timeoutMs: Long): ProbeResult =
        httpProbe(
            base, cfg, host, pin, timeoutMs,
            request = { Request.Builder().url("$it/rpc.php").post(rpcBody("Session", "isAuthenticated", null)).build() },
            verify = { code, body ->
                when {
                    code in 301..308 -> "Redirects to HTTPS: turn HTTPS on"
                    runCatching { JSONObject(body).let { it.has("response") && it.has("error") } }.getOrNull() != true -> "Not OpenMediaVault"
                    else -> null
                }
            },
        )

    override fun session(env: SessionEnv): NasSession = OmvSession(env)

    fun rpcBody(service: String, method: String, params: JSONObject?) =
        JSONObject().put("service", service).put("method", method)
            .put("params", params ?: JSONObject.NULL).put("options", JSONObject.NULL)
            .toString().toRequestBody("application/json".toMediaType())
}

private class OmvSession(private val env: SessionEnv) : NasSession {
    private val client = env.base.forSession(env.cfg).newBuilder().cookieJar(MemoryCookies()).build()
    private val url get() = "${baseUrl(env.cfg, env.host)}/rpc.php"

    private suspend fun rpc(service: String, method: String, params: JSONObject? = null): Any? {
        val (_, body) = client.text(Request.Builder().url(url).post(OmvBackend.rpcBody(service, method, params)).build())
        val json = runCatching { JSONObject(body) }.getOrNull() ?: throw IOException("Unexpected answer from the NAS")
        val err = json.optJSONObject("error")
        if (err != null) {
            if (err.optInt("code") == 5001) throw AuthException("OpenMediaVault signed us out")
            throw IOException(err.optString("message").lineSequence().firstOrNull()?.take(80) ?: "OpenMediaVault error")
        }
        return json.opt("response")
    }

    override suspend fun login() {
        val r = rpc("Session", "login", JSONObject().put("username", env.cfg.username).put("password", env.cfg.apiKey)) as? JSONObject
        if (r?.optBoolean("authenticated") != true) throw AuthException("Wrong username or password")
    }

    override suspend fun run(push: (StatsUpdate) -> Unit) {
        var fails = 0
        var tick = 0
        while (true) {
            try {
                push(poll(full = tick++ % 4 == 0))
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
        val info = rpc("System", "getInformation") as? JSONObject
        val fs = if (full) runCatching { rpc("FileSystemMgmt", "enumerateFilesystems") as? org.json.JSONArray }.getOrNull() else null
        val devs = if (full) runCatching { rpc("DiskMgmt", "enumerateDevices") as? org.json.JSONArray }.getOrNull() else null
        val smart = if (full) runCatching {
            (rpc("Smart", "getList", JSONObject().put("start", 0).put("limit", -1)) as? JSONObject)?.optJSONArray("data")
        }.getOrNull() else null

        val temps = smart.objects().associate { it.optString("devicefile") to it.num("temperature")?.toInt() }
        val pools = fs?.objects()?.filter { it.optBoolean("mounted") && (it.long("size") ?: 0) > 0 }?.map { f ->
            val size = f.long("size") ?: 0
            val used = f.long("used") ?: (size - (f.long("available") ?: 0))
            Pool(f.str("label") ?: f.optString("devicefile").substringAfterLast('/'), "ONLINE", true, used.coerceAtLeast(0), size)
        }
        val disks = devs?.objects()?.filterNot { it.optString("devicefile").contains("loop") }?.map { d ->
            val dev = d.optString("devicefile")
            Disk(dev.substringAfterLast('/'), d.str("model")?.trim() ?: d.str("vendor") ?: "Disk", d.long("size") ?: 0, temps[dev], null)
        }?.sortedBy { it.name }

        val cpu = info?.num("cpuUtilization")
        val memTotal = info?.long("memTotal")
        val memUsed = info?.long("memUsed")
        return { st ->
            st.copy(
                hostname = info?.str("hostname") ?: st.hostname,
                version = info?.str("version")?.let { "OpenMediaVault $it" } ?: st.version,
                uptimeSec = info?.long("uptime") ?: st.uptimeSec,
                cpu = cpu?.toFloat() ?: st.cpu,
                memTotal = memTotal ?: st.memTotal,
                memUsed = memUsed ?: st.memUsed,
                pools = pools ?: st.pools,
                disks = disks ?: st.disks,
            )
        }
    }

    override suspend fun power(shutdown: Boolean) {
        rpc("System", if (shutdown) "shutdown" else "reboot", JSONObject().put("delay", 0))
    }

    override fun close() {
        // The cookie jar dies with this session; OpenMediaVault expires idle sessions itself.
    }
}
