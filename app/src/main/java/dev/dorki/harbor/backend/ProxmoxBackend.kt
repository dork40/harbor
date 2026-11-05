package dev.dorki.harbor.backend

import dev.dorki.harbor.AppState
import dev.dorki.harbor.NasApp
import dev.dorki.harbor.Pool
import dev.dorki.harbor.data.NasKind
import dev.dorki.harbor.data.ServerConfig
import dev.dorki.harbor.net.ProbeResult
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Proxmox VE. Virtual machines and containers show up as apps; sign in as e.g. root@pam. */
object ProxmoxBackend : NasBackend {
    override val kind = NasKind.PROXMOX
    override val caps = Caps(apps = true, power = true, stats = true)

    override suspend fun probe(base: OkHttpClient, cfg: ServerConfig, host: String, pin: String?, timeoutMs: Long): ProbeResult =
        httpProbe(
            base, cfg, host, pin, timeoutMs,
            request = { Request.Builder().url("$it/api2/json/version").build() },
            // Answers 401 to strangers, which still proves it's Proxmox.
            verify = { code, _ -> if (code == 401 || code == 200) null else "Not Proxmox VE" },
        )

    override fun session(env: SessionEnv): NasSession = ProxmoxSession(env)
}

private class ProxmoxSession(private val env: SessionEnv) : NasSession {
    private val client = env.base.forSession(env.cfg)
    private val base get() = "${baseUrl(env.cfg, env.host)}/api2/json"
    private var ticket: String? = null
    private var csrf: String? = null
    private var node: String? = null

    private fun get(path: String) = Request.Builder().url(base + path).header("Cookie", "PVEAuthCookie=$ticket").build()

    private suspend fun data(r: Request): Any? {
        val (code, body) = client.text(r)
        if (code == 401) throw AuthException("Signed out by Proxmox")
        if (code >= 400) throw IOException("Proxmox error $code")
        return runCatching { JSONObject(body).opt("data") }.getOrNull()
    }

    private suspend fun post(path: String, form: FormBody? = null) {
        val r = Request.Builder().url(base + path).header("Cookie", "PVEAuthCookie=$ticket")
            .header("CSRFPreventionToken", csrf ?: "").post(form ?: "".toRequestBody()).build()
        data(r)
    }

    override suspend fun login() {
        val user = env.cfg.username.let { if ('@' in it) it else "$it@pam" }
        val form = FormBody.Builder().add("username", user).add("password", env.cfg.apiKey).build()
        val (_, body) = client.text(Request.Builder().url("$base/access/ticket").post(form).build())
        val d = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull()
        ticket = d?.str("ticket") ?: throw AuthException("Wrong username or password")
        csrf = d.str("CSRFPreventionToken")
        node = (data(get("/nodes")) as? JSONArray).objects().firstOrNull()?.str("node")
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
        val n = node ?: throw IOException("No Proxmox node found")
        val s = data(get("/nodes/$n/status")) as? JSONObject
        val mem = s?.optJSONObject("memory")
        val storage = if (full) runCatching { data(get("/nodes/$n/storage")) as? JSONArray }.getOrNull() else null
        val vms = if (full) guests("qemu", n) + guests("lxc", n) else null
        val pools = storage.objects().filter { it.optInt("active") == 1 && (it.long("total") ?: 0) > 0 }.map {
            Pool(it.optString("storage"), "ONLINE", true, it.long("used") ?: 0, it.long("total") ?: 0)
        }
        return { st ->
            st.copy(
                hostname = n,
                version = s?.str("pveversion")?.substringBefore('/')?.let { "Proxmox VE" } ?: st.version,
                uptimeSec = s?.long("uptime") ?: st.uptimeSec,
                cores = s?.optJSONObject("cpuinfo")?.num("cpus")?.toInt() ?: st.cores,
                cpu = s?.num("cpu")?.let { (it * 100).toFloat() } ?: st.cpu,
                memTotal = mem?.long("total") ?: st.memTotal,
                memUsed = mem?.long("used") ?: st.memUsed,
                pools = pools.ifEmpty { st.pools },
                apps = vms?.sortedBy { it.title.lowercase() } ?: st.apps,
            )
        }
    }

    private suspend fun guests(type: String, n: String) = runCatching { data(get("/nodes/$n/$type")) as? JSONArray }.getOrNull().objects().map { g ->
        val id = g.optString("vmid")
        NasApp(
            name = "$type/$id",
            title = g.str("name") ?: "$type $id",
            state = if (g.optString("status") == "running") AppState.RUNNING else AppState.STOPPED,
            icon = null,
            portals = emptyList(),
            version = if (type == "lxc") "Container" else "VM",
            upgrade = false,
        )
    }

    override suspend fun appAction(app: String, action: String) {
        val n = node ?: return
        val verb = when (action) {
            "stop" -> "shutdown"
            "restart" -> "reboot"
            else -> "start"
        }
        post("/nodes/$n/$app/status/$verb")
    }

    override suspend fun power(shutdown: Boolean) {
        val n = node ?: return
        post("/nodes/$n/status", FormBody.Builder().add("command", if (shutdown) "shutdown" else "reboot").build())
    }

    override fun close() {
        ticket = null
    }
}
