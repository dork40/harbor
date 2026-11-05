package dev.dorki.harbor.backend

import dev.dorki.harbor.FsEntry
import dev.dorki.harbor.NasStats
import dev.dorki.harbor.Share
import dev.dorki.harbor.SnapView
import dev.dorki.harbor.data.NasKind
import dev.dorki.harbor.data.ServerConfig
import dev.dorki.harbor.net.ProbeResult
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import java.io.IOException

/** The NAS answered but refused the credentials. */
class AuthException(message: String) : IOException(message)

/** This kind of NAS can't do what was asked. */
class UnsupportedException(message: String) : IOException(message)

typealias StatsUpdate = (NasStats) -> NasStats

/** What Harbor can offer for each kind of NAS; the UI hides everything else. */
data class Caps(
    val apps: Boolean = false,
    val appUpdates: Boolean = false,
    val logs: Boolean = false,
    val snapshots: Boolean = false,
    val power: Boolean = false,
    val files: Boolean = false,
    val stats: Boolean = false,
)

/** Everything a live session needs to know about where it is talking to. */
class SessionEnv(
    val cfg: ServerConfig,
    /** Host (with port) this session was opened against. */
    val host: String,
    /** Unpinned client; the session pins it itself. */
    val base: OkHttpClient,
    /** Host to use for transfers right now (it can change while a session lives on). */
    val hostNow: () -> String?,
    /** Client for downloads/uploads to the given bare host. */
    val transferClient: (String) -> OkHttpClient,
    /** The session Harbor is using right now; a long upload outlives the one that started it. */
    val current: () -> NasSession?,
)

interface FileApi {
    suspend fun shares(): List<Share>
    suspend fun list(path: String): List<FsEntry>
    suspend fun mkdir(parent: String, name: String)

    /** A URL that streams the file; any token it needs is part of the URL. */
    suspend fun downloadUrl(e: FsEntry): String

    /** Sends [body] to [dir]/[name] and returns when the NAS has the whole file. */
    suspend fun upload(dir: String, name: String, mime: String, size: Long, body: RequestBody, track: (Call) -> Unit)
}

interface NasSession {
    /** Opens the connection and signs in. Throws [AuthException] if the credentials are wrong. */
    suspend fun login()

    /** Keeps [push]ing fresh numbers until the connection drops (returns) or breaks (throws). */
    suspend fun run(push: (StatsUpdate) -> Unit)

    /** One look at the NAS, outside the regular schedule (after an action). */
    suspend fun poll(): StatsUpdate

    suspend fun appAction(app: String, action: String): Unit = throw UnsupportedException("Not supported on this NAS")
    suspend fun upgradeApps(names: List<String>): Unit = throw UnsupportedException("Not supported on this NAS")
    suspend fun power(shutdown: Boolean): Unit = throw UnsupportedException("Not supported on this NAS")
    suspend fun logs(app: String): List<String> = throw UnsupportedException("Not supported on this NAS")
    suspend fun snapshots(): SnapView = throw UnsupportedException("Not supported on this NAS")
    suspend fun takeSnapshot(pool: String): String = throw UnsupportedException("Not supported on this NAS")
    val files: FileApi? get() = null

    fun close()
}

interface NasBackend {
    val kind: NasKind
    val caps: Caps

    /** Is something that looks like this NAS answering at [host]? */
    suspend fun probe(base: OkHttpClient, cfg: ServerConfig, host: String, pin: String?, timeoutMs: Long): ProbeResult

    /** Null when Harbor has no API to sign in to for this kind. */
    fun session(env: SessionEnv): NasSession?
}

fun backendFor(kind: NasKind): NasBackend = when (kind) {
    NasKind.TRUENAS -> TrueNasBackend
    NasKind.SYNOLOGY -> SynologyBackend
    NasKind.OMV -> OmvBackend
    NasKind.UNRAID -> UnraidBackend
    NasKind.UMBREL -> UmbrelBackend
    NasKind.ZIMAOS, NasKind.CASAOS -> casaBackends.getValue(kind)
    NasKind.QNAP -> QnapBackend
    NasKind.PROXMOX -> ProxmoxBackend
    else -> GenericBackend.of(kind)
}

private val casaBackends = listOf(NasKind.ZIMAOS, NasKind.CASAOS).associateWith { CasaBackend(it) }

/** Adds the NAS software's usual port when the address doesn't name one. */
fun fullHost(cfg: ServerConfig, host: String): String {
    if (host.isBlank()) return host
    val hasPort = if (host.startsWith("[")) host.contains("]:") else host.contains(":")
    if (hasPort) return host
    val port = cfg.kind.defaultPort(cfg.https) ?: return host
    return "$host:$port"
}

fun baseUrl(cfg: ServerConfig, host: String) = "${if (cfg.https) "https" else "http"}://$host"
