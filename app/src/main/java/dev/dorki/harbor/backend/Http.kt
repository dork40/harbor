package dev.dorki.harbor.backend

import dev.dorki.harbor.data.ServerConfig
import dev.dorki.harbor.net.PinMismatchException
import dev.dorki.harbor.net.PinningTrustManager
import dev.dorki.harbor.net.ProbeResult
import dev.dorki.harbor.net.describe
import dev.dorki.harbor.net.hasCause
import dev.dorki.harbor.net.pinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { c ->
    c.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = c.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = c.resume(response)
    })
}

/** The client a session talks through: pinned to the NAS's certificate when it uses HTTPS. */
internal fun OkHttpClient.forSession(cfg: ServerConfig): OkHttpClient =
    (if (cfg.https) pinned(PinningTrustManager(cfg.pin)) else this).newBuilder()
        .followSslRedirects(false)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

internal class MemoryCookies : CookieJar {
    private val jar = HashMap<String, List<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val kept = (jar[url.host].orEmpty().filter { old -> cookies.none { it.name == old.name } }) + cookies
        jar[url.host] = kept
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = jar[url.host].orEmpty().filter { it.matches(url) || !it.secure }
}

internal suspend fun OkHttpClient.text(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
    newCall(req).await().use { it.code to it.body?.string().orEmpty() }
}

/**
 * Asks the NAS's web server for [path] and lets [verify] judge the answer (null means "this is the
 * right kind of NAS"). Records the TLS fingerprint for pinning on the way.
 */
internal suspend fun httpProbe(
    base: OkHttpClient, cfg: ServerConfig, host: String, pin: String?, timeoutMs: Long,
    request: (url: String) -> Request,
    verify: (code: Int, body: String) -> String?,
): ProbeResult = withContext(Dispatchers.IO) {
    val tm = PinningTrustManager(pin)
    val client = (if (cfg.https) base.pinned(tm) else base).newBuilder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()
    try {
        withTimeout(timeoutMs + 500) {
            val start = System.nanoTime()
            client.newCall(request(baseUrl(cfg, host))).await().use { resp ->
                val ms = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
                val body = resp.peekBody(64 * 1024).string()
                val err = verify(resp.code, body)
                if (err == null) ProbeResult(true, ms, tm.seen) else ProbeResult(false, ms, tm.seen, err)
            }
        }
    } catch (e: Throwable) {
        if (e is CancellationException && e !is TimeoutCancellationException) throw e
        ProbeResult(false, fingerprint = tm.seen, error = describe(e), mismatch = e.hasCause<PinMismatchException>())
    }
}

internal fun JSONObject.num(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }?.takeIf { !it.isNaN() }
}

internal fun JSONObject.long(key: String): Long? = num(key)?.toLong()

internal fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() && it != "null" } else null

internal fun org.json.JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
