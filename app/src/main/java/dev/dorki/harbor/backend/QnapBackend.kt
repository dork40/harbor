package dev.dorki.harbor.backend

import android.util.Base64
import dev.dorki.harbor.data.NasKind
import dev.dorki.harbor.data.ServerConfig
import dev.dorki.harbor.net.ProbeResult
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** QNAP QTS / QuTS hero, through the CGI endpoints its web desktop uses. Stats and power only. */
object QnapBackend : NasBackend {
    override val kind = NasKind.QNAP
    override val caps = Caps(power = true, stats = true)

    override suspend fun probe(base: OkHttpClient, cfg: ServerConfig, host: String, pin: String?, timeoutMs: Long): ProbeResult =
        httpProbe(
            base, cfg, host, pin, timeoutMs,
            request = { Request.Builder().url("$it/cgi-bin/authLogin.cgi").build() },
            verify = { code, body -> if (code == 200 && body.contains("<QDocRoot")) null else "Not QNAP" },
        )

    override fun session(env: SessionEnv): NasSession = QnapSession(env)
}

private class QnapSession(private val env: SessionEnv) : NasSession {
    private val client = env.base.forSession(env.cfg)
    private val base get() = baseUrl(env.cfg, env.host)
    private var sid: String? = null

    /** QNAP answers in XML with values wrapped in CDATA. */
    private fun tag(xml: String, name: String): String? =
        Regex("<$name>\\s*(?:<!\\[CDATA\\[)?([^<\\]]*)").find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun get(path: String, vararg q: Pair<String, String>): String {
        val url = (base + path).toHttpUrl().newBuilder().apply {
            q.forEach { (k, v) -> addQueryParameter(k, v) }
            sid?.let { addQueryParameter("sid", it) }
        }.build()
        val (code, body) = client.text(Request.Builder().url(url).build())
        if (code >= 400) throw IOException("QNAP error $code")
        return body
    }

    override suspend fun login() {
        sid = null
        val pwd = Base64.encodeToString(env.cfg.apiKey.toByteArray(), Base64.NO_WRAP)
        val xml = get("/cgi-bin/authLogin.cgi", "user" to env.cfg.username, "pwd" to pwd, "serviceKey" to "1")
        if (tag(xml, "authPassed") != "1") throw AuthException("Wrong username or password")
        sid = tag(xml, "authSid") ?: throw AuthException("QNAP didn't return a session")
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
            delay(5_000)
        }
    }

    override suspend fun poll(): StatsUpdate {
        val x = get("/cgi-bin/management/manaRequest.cgi", "subfunc" to "sysinfo", "hd" to "no", "multicpu" to "1")
        if (tag(x, "authPassed") == "0") throw AuthException("Signed out by the NAS")
        val cpu = tag(x, "cpu_usage")?.substringBefore('%')?.trim()?.toFloatOrNull()
        val mib = 1024L * 1024
        val total = tag(x, "total_memory")?.toDoubleOrNull()?.let { (it * mib).toLong() }
        val free = tag(x, "free_memory")?.toDoubleOrNull()?.let { (it * mib).toLong() }
        val up = listOf("uptime_day" to 86_400L, "uptime_hour" to 3_600L, "uptime_min" to 60L).sumOf { (k, s) -> (tag(x, k)?.toLongOrNull() ?: 0) * s }
        return { st ->
            st.copy(
                hostname = tag(x, "hostname") ?: st.hostname,
                version = tag(x, "version")?.let { "QTS $it" } ?: st.version,
                uptimeSec = up.takeIf { it > 0 } ?: st.uptimeSec,
                cpu = cpu ?: st.cpu,
                memTotal = total ?: st.memTotal,
                memUsed = if (total != null && free != null) total - free else st.memUsed,
            )
        }
    }

    override suspend fun power(shutdown: Boolean) {
        get("/cgi-bin/sys/sysRequest.cgi", "subfunc" to "power_mgmt", "apply" to if (shutdown) "shutdown" else "restart")
    }

    override fun close() {
        sid = null
    }
}
