package dev.dorki.harbor.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class RpcException(message: String, val errname: String?) : IOException(message)
class PinMismatchException : CertificateException("Certificate doesn't match your NAS")

/**
 * TrueNAS ships a self-signed certificate, so instead of CA validation Harbor pins the
 * certificate it saw the first time the NAS answered at home. A different device that
 * happens to sit at the same IP on someone else's network will fail this check.
 */
class PinningTrustManager(private val expected: String?) : X509TrustManager {
    @Volatile var seen: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        val fp = fingerprint(chain.first())
        seen = fp
        if (expected != null && expected != fp) throw PinMismatchException()
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) =
        throw CertificateException("Client certificates are not supported")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) }
    }
}

fun OkHttpClient.pinned(tm: PinningTrustManager): OkHttpClient {
    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
    return newBuilder()
        .sslSocketFactory(ctx.socketFactory, tm)
        .hostnameVerifier { _, _ -> true }
        .build()
}

/** JSON-RPC 2.0 over the TrueNAS websocket at /api/current. */
class RpcSocket private constructor() {
    private lateinit var ws: WebSocket
    private val opened = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Any?>>()
    private val nextId = AtomicInteger(1)
    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 1024)
    val events: SharedFlow<JSONObject> = _events

    /** Completes when the socket goes away, with the failure if there was one. */
    val closed = CompletableDeferred<Throwable?>()

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (o.has("id") && !o.isNull("id")) {
                val d = pending[o.optInt("id")] ?: return
                val err = o.optJSONObject("error")
                if (err != null) {
                    val data = err.optJSONObject("data")
                    val reason = data?.optString("reason")?.takeIf { it.isNotBlank() } ?: err.optString("message")
                    d.completeExceptionally(RpcException(reason.removePrefix("[${data?.optString("errname")}] "), data?.optString("errname")))
                } else {
                    d.complete(o.opt("result"))
                }
            } else if (o.optString("method") == "collection_update") {
                o.optJSONObject("params")?.let { _events.tryEmit(it) }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            finish(null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(null)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = finish(t)
    }

    private fun finish(t: Throwable?) {
        val err = t ?: IOException("Connection closed")
        opened.completeExceptionally(err)
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
        closed.complete(t)
    }

    suspend fun call(method: String, vararg params: Any?, timeoutMs: Long = 15_000): Any? {
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<Any?>()
        pending[id] = d
        val msg = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", JSONArray().apply { params.forEach { put(it) } })
        if (closed.isCompleted || !ws.send(msg.toString())) {
            pending.remove(id)
            throw IOException("Connection closed")
        }
        return try {
            withTimeout(timeoutMs) { d.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun close() {
        if (::ws.isInitialized) ws.close(1000, null)
        finish(null)
    }

    companion object {
        suspend fun open(client: OkHttpClient, host: String, https: Boolean = true): RpcSocket {
            val sock = RpcSocket()
            sock.ws = client.newWebSocket(Request.Builder().url("${if (https) "wss" else "ws"}://$host/api/current").build(), sock.listener)
            try {
                sock.opened.await()
            } catch (e: Throwable) {
                sock.ws.cancel()
                throw e
            }
            return sock
        }
    }
}

data class ProbeResult(
    val ok: Boolean,
    val latencyMs: Long = 0,
    val fingerprint: String? = null,
    val error: String? = null,
    val mismatch: Boolean = false,
)

/** Opens the API socket and asks for a pong; proves both reachability and identity. */
suspend fun probe(base: OkHttpClient, host: String, pin: String?, timeoutMs: Long = 2_500, https: Boolean = true): ProbeResult =
    withContext(Dispatchers.IO) {
        val tm = PinningTrustManager(pin)
        val client = (if (https) base.pinned(tm) else base).newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        try {
            withTimeout(timeoutMs + 500) {
                val sock = RpcSocket.open(client, host, https)
                try {
                    val start = System.nanoTime()
                    val pong = sock.call("core.ping", timeoutMs = timeoutMs)
                    val ms = (System.nanoTime() - start) / 1_000_000
                    if (pong == "pong") ProbeResult(true, ms.coerceAtLeast(1), tm.seen)
                    else ProbeResult(false, fingerprint = tm.seen, error = "Not a TrueNAS")
                } finally {
                    sock.close()
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            ProbeResult(false, fingerprint = tm.seen, error = describe(e), mismatch = e.hasCause<PinMismatchException>())
        }
    }

internal inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var c: Throwable? = this
    while (c != null) {
        if (c is T) return true
        c = c.cause
    }
    return false
}

fun describe(e: Throwable): String = when {
    e.hasCause<PinMismatchException>() -> "Certificate mismatch"
    e is kotlinx.coroutines.TimeoutCancellationException || e.hasCause<SocketTimeoutException>() -> "Timed out"
    e.hasCause<UnknownHostException>() -> "Can't resolve host"
    e.hasCause<NoRouteToHostException>() -> "No route"
    e.hasCause<ConnectException>() -> "Refused"
    e is RpcException -> e.message ?: "API error"
    else -> e.message?.take(60) ?: e.javaClass.simpleName
}
