package dev.dorki.harbor

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.dorki.harbor.net.WatchWorker
import dev.dorki.harbor.data.ServerConfig
import dev.dorki.harbor.data.Shortcut
import dev.dorki.harbor.data.Store
import dev.dorki.harbor.net.NetState
import dev.dorki.harbor.net.NetworkMonitor
import dev.dorki.harbor.net.PinningTrustManager
import dev.dorki.harbor.backend.AuthException
import dev.dorki.harbor.backend.NasSession
import dev.dorki.harbor.backend.SessionEnv
import dev.dorki.harbor.backend.StatsUpdate
import dev.dorki.harbor.backend.backendFor
import dev.dorki.harbor.backend.baseUrl
import dev.dorki.harbor.backend.fullHost
import dev.dorki.harbor.data.NasKind
import dev.dorki.harbor.net.ProbeResult
import dev.dorki.harbor.net.describe
import dev.dorki.harbor.net.pinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class Route { LAN, TAILSCALE }

enum class OfflineReason { NO_NETWORK, NOT_HOME, UNREACHABLE, CERT_MISMATCH }

sealed interface Conn {
    data object Setup : Conn
    data class Resolving(val vpn: Boolean, val prev: Online?) : Conn
    data class Online(val route: Route, val host: String, val latencyMs: Long) : Conn
    data class Offline(val reason: OfflineReason, val detail: String?) : Conn
}

sealed interface ApiState {
    data object Off : ApiState
    data object Connecting : ApiState
    data object Ready : ApiState
    data class Error(val message: String) : ApiState
}

enum class AppState(val label: String) {
    RUNNING("Running"), DEPLOYING("Deploying"), STOPPING("Stopping"),
    STOPPED("Stopped"), CRASHED("Crashed"), UNKNOWN("");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.name == raw?.uppercase() } ?: UNKNOWN
    }
}

data class NasApp(
    val name: String,
    val title: String,
    val state: AppState,
    val icon: String?,
    val portals: List<String>,
    val version: String?,
    val upgrade: Boolean,
)

data class Pool(val name: String, val status: String, val healthy: Boolean, val used: Long, val total: Long)

data class Disk(val name: String, val model: String, val size: Long, val tempC: Int?, val pool: String?)

/** One recent look at the network: bytes per second, down and up. */
data class NetSample(val down: Float, val up: Float)

data class LogView(val title: String, val lines: List<String> = emptyList(), val loading: Boolean = true, val error: String? = null)

data class SnapView(val loading: Boolean = true, val count: Int? = null, val recent: List<String> = emptyList(), val error: String? = null)

data class NasStats(
    val hostname: String? = null,
    val version: String? = null,
    val uptimeSec: Long? = null,
    val cores: Int? = null,
    val memTotal: Long? = null,
    val memUsed: Long? = null,
    val cpu: Float? = null,
    val pools: List<Pool> = emptyList(),
    val apps: List<NasApp> = emptyList(),
    val disks: List<Disk> = emptyList(),
    val net: List<NetSample> = emptyList(),
)

enum class TileKind { SYSTEM, APP, SHORTCUT }

data class Tile(
    val id: String,
    val name: String,
    val subtitle: String,
    val url: String?,
    val icons: List<String>,
    val kind: TileKind,
    val state: AppState = AppState.UNKNOWN,
    val reachable: Boolean? = null,
    val appName: String? = null,
    val shortcut: Shortcut? = null,
    val upgrade: Boolean = false,
)

@OptIn(FlowPreview::class)
class HarborViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    private val monitor = NetworkMonitor(app)

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val config: StateFlow<ServerConfig> = store.config
    val profiles: StateFlow<List<ServerConfig>> = store.profiles
    val shortcuts: StateFlow<List<Shortcut>> = store.shortcuts
    val net: StateFlow<NetState> = monitor.state

    private val _conn = MutableStateFlow<Conn>(if (store.config.value.configured) Conn.Resolving(false, null) else Conn.Setup)
    val conn: StateFlow<Conn> = _conn.asStateFlow()

    private val _probes = MutableStateFlow<Map<Route, ProbeResult>>(emptyMap())
    val probes: StateFlow<Map<Route, ProbeResult>> = _probes.asStateFlow()

    private val _api = MutableStateFlow<ApiState>(ApiState.Off)
    val api: StateFlow<ApiState> = _api.asStateFlow()

    private val _stats = MutableStateFlow(NasStats())
    val stats: StateFlow<NasStats> = _stats.asStateFlow()

    private val _reach = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    val alerts: StateFlow<Boolean> = store.alerts
    val appearance: StateFlow<dev.dorki.harbor.ui.theme.Appearance> = store.appearance
    fun setAppearance(a: dev.dorki.harbor.ui.theme.Appearance) = store.setAppearance(a)

    private val _logs = MutableStateFlow<LogView?>(null)
    val logs: StateFlow<LogView?> = _logs.asStateFlow()

    private val _snaps = MutableStateFlow<SnapView?>(null)
    val snaps: StateFlow<SnapView?> = _snaps.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _status = MutableStateFlow<Map<String, Boolean?>>(emptyMap())
    /** Quick up/down check for every saved NAS, shown in the NAS list. null = still checking. */
    val status: StateFlow<Map<String, Boolean?>> = _status.asStateFlow()

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val tiles: StateFlow<List<Tile>> =
        combine(config, conn, shortcuts, stats, _reach) { c, cn, sc, st, r -> buildTiles(c, cn, sc, st, r) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var resolveJob: Job? = null
    private var lastResolve = 0L
    private var apiJob: Job? = null
    private var apiHost: String? = null
    private var apiDrops = 0
    @Volatile private var session: NasSession? = null
    private var reachJob: Job? = null
    private var statusJob: Job? = null

    init {
        if (store.alerts.value) scheduleWatch(true)
        resolve()
        viewModelScope.launch {
            // Network changes are the only thing that triggers a new attempt: when the home
            // network doesn't answer and Tailscale is off, Harbor stops rather than retrying.
            net.drop(1).debounce(800).collect { resolve() }
        }
    }

    fun onResume() {
        val c = _conn.value
        if (c is Conn.Resolving || c is Conn.Setup) return
        val age = SystemClock.elapsedRealtime() - lastResolve
        if (age > 3_000 && (c is Conn.Offline || age > 60_000)) resolve()
    }

    fun refresh() {
        _refreshing.value = true
        resolve(force = true)
    }

    fun resolve(force: Boolean = false) {
        val cfg = config.value
        if (!cfg.configured) {
            _conn.value = Conn.Setup
            _refreshing.value = false
            return
        }
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            try {
                doResolve(cfg, force)
            } finally {
                _refreshing.value = false
            }
        }
    }

    private suspend fun doResolve(cfg: ServerConfig, force: Boolean) = coroutineScope {
        val ns = net.value
        val prev = when (val c = _conn.value) {
            is Conn.Online -> c
            is Conn.Resolving -> c.prev
            else -> null
        }
        _conn.value = Conn.Resolving(ns.vpn, prev)
        lastResolve = SystemClock.elapsedRealtime()

        if (!ns.online) {
            _probes.value = emptyMap()
            goOffline(OfflineReason.NO_NETWORK, null)
            return@coroutineScope
        }

        val be = backendFor(cfg.kind)
        val lanHost = fullHost(cfg, cfg.lanHost)
        val tsHost = fullHost(cfg, cfg.tailscaleHost)
        val lan = if (lanHost.isNotBlank()) async { be.probe(http, cfg, lanHost, cfg.pin, 2_500) } else null
        val ts = if (ns.vpn && tsHost.isNotBlank()) async { be.probe(http, cfg, tsHost, cfg.pin, 2_500) } else null
        val l = lan?.await()
        val t = ts?.await()
        _probes.value = buildMap {
            l?.let { put(Route.LAN, it) }
            t?.let { put(Route.TAILSCALE, it) }
        }

        val pick: Pair<Route, ProbeResult>? = when {
            ns.vpn && cfg.preferLan && l?.ok == true -> Route.LAN to l
            ns.vpn && t?.ok == true -> Route.TAILSCALE to t
            l?.ok == true -> Route.LAN to l
            else -> null
        }

        if (pick == null) {
            val reason = when {
                l?.mismatch == true || t?.mismatch == true -> OfflineReason.CERT_MISMATCH
                !ns.vpn -> OfflineReason.NOT_HOME
                else -> OfflineReason.UNREACHABLE
            }
            goOffline(reason, (t ?: l)?.error)
            return@coroutineScope
        }

        val (route, result) = pick
        if (cfg.pin == null && cfg.https && result.fingerprint != null) store.setPin(cfg.id, result.fingerprint)
        val host = if (route == Route.LAN) lanHost else tsHost
        _conn.value = Conn.Online(route, host, result.latencyMs)
        if (prev?.host != host || force) apiDrops = 0
        startApi(host, restart = force)
        checkReach()
    }

    private fun goOffline(reason: OfflineReason, detail: String?) {
        stopApi()
        _reach.value = emptyMap()
        _stats.update { it.copy(cpu = null, memUsed = null) }
        _conn.value = Conn.Offline(reason, detail)
    }

    // --- NAS API session -----------------------------------------------------------------

    private fun stopApi() {
        apiJob?.cancel()
        apiJob = null
        apiHost = null
        session?.close()
        session = null
        if (_api.value !is ApiState.Error) _api.value = ApiState.Off
    }

    private fun liveHost(): String? = when (val c = _conn.value) {
        is Conn.Online -> c.host
        is Conn.Resolving -> c.prev?.host
        else -> null
    }

    private fun sessionEnv(cfg: ServerConfig, host: String) = SessionEnv(
        cfg = cfg, host = host, base = http, hostNow = ::liveHost, transferClient = ::downloadClient, current = { session },
    )

    private fun startApi(host: String, restart: Boolean) {
        val cfg = config.value
        val sess = if (cfg.hasLogin) backendFor(cfg.kind).session(sessionEnv(cfg, host)) else null
        if (sess == null) {
            stopApi()
            _api.value = ApiState.Off
            _stats.value = NasStats()
            return
        }
        if (!restart && apiJob?.isActive == true && apiHost == host) {
            sess.close()
            return
        }
        stopApi()
        apiHost = host
        apiJob = viewModelScope.launch(Dispatchers.IO) {
            _api.value = ApiState.Connecting
            session = sess
            try {
                sess.login()
                _api.value = ApiState.Ready
                apiDrops = 0
                if (backendFor(cfg.kind).caps.files) launch { runCatching { files.loadShares() } }
                sess.run(::applyStats)
                // Connection dropped while we thought we were online: re-check the route, a few times at most.
                if (_conn.value is Conn.Online && apiDrops++ < 3) {
                    _api.value = ApiState.Connecting
                    delay(1_500)
                    apiHost = null
                    resolve()
                } else {
                    _api.value = ApiState.Error("Lost connection to the API")
                }
            } catch (e: AuthException) {
                _api.value = ApiState.Error(e.message ?: "Sign-in was refused")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _api.value = ApiState.Error(describe(e))
            } finally {
                sess.close()
                if (session === sess) session = null
            }
        }
    }

    private fun applyStats(u: StatsUpdate) {
        val before = _stats.value.apps.map { it.name to it.portals }
        _stats.update(u)
        reconcileShortcuts()
        if (_stats.value.apps.map { it.name to it.portals } != before) checkReach()
    }

    private fun ready(what: String): NasSession? {
        val s = session
        if (s == null || _api.value != ApiState.Ready) {
            messages.tryEmit("Sign in to ${config.value.kind.label} in Settings to $what")
            return null
        }
        return s
    }

    fun appAction(tile: Tile, action: String) {
        val name = tile.appName ?: return
        val sess = ready("control apps") ?: return
        val (verb, interim) = when (action) {
            "start" -> "Starting" to AppState.DEPLOYING
            "stop" -> "Stopping" to AppState.STOPPING
            "upgrade" -> "Updating" to AppState.DEPLOYING
            else -> "Restarting" to AppState.DEPLOYING
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sess.appAction(name, action)
                messages.emit("$verb ${tile.name}…")
                _stats.update { s -> s.copy(apps = s.apps.map { if (it.name == name) it.copy(state = interim) else it }) }
                delay(4_000)
                applyStats(sess.poll())
                delay(8_000)
                applyStats(sess.poll())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                messages.emit("Couldn't ${action} ${tile.name}: ${describe(e)}")
            }
        }
    }

    /** Restart ("reboot") or shut down ("shutdown") the NAS itself. */
    fun power(action: String) {
        val name = config.value.title
        val sess = ready("restart or shut down") ?: return
        val off = action == "shutdown"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sess.power(off)
                messages.emit(if (off) "Shutting down $name…" else "Restarting $name… pull to refresh in a few minutes")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                messages.emit("Couldn't ${if (off) "shut down" else "restart"} $name: ${describe(e)}")
            }
        }
    }

    fun upgradeAll() {
        val sess = ready("update apps") ?: return
        val names = _stats.value.apps.filter { it.upgrade }.map { it.name }
        if (names.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sess.upgradeApps(names)
                messages.emit("Updating ${names.size} app${if (names.size == 1) "" else "s"}…")
                delay(5_000); applyStats(sess.poll())
                delay(15_000); applyStats(sess.poll())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                messages.emit("Couldn't update apps: ${describe(e)}")
            }
        }
    }

    // --- Logs ----------------------------------------------------------------------------

    fun closeLogs() { _logs.value = null }

    fun showLogs(tile: Tile) {
        val app = tile.appName ?: return
        val sess = ready("read logs") ?: return
        _logs.value = LogView(tile.name)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lines = sess.logs(app)
                _logs.value = LogView(tile.name, lines, loading = false, error = if (lines.isEmpty()) "No log output" else null)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _logs.value = LogView(tile.name, loading = false, error = describe(e))
            }
        }
    }

    // --- Snapshots -----------------------------------------------------------------------

    fun closeSnaps() { _snaps.value = null }

    fun showSnapshots() {
        val sess = ready("see snapshots") ?: return
        _snaps.value = SnapView()
        viewModelScope.launch(Dispatchers.IO) { loadSnapshots(sess) }
    }

    private suspend fun loadSnapshots(sess: NasSession) {
        try {
            _snaps.value = sess.snapshots()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _snaps.value = SnapView(loading = false, error = describe(e))
        }
    }

    fun takeSnapshot(pool: String) {
        val sess = session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                messages.emit("Snapshot taken: ${sess.takeSnapshot(pool)}")
                loadSnapshots(sess)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                messages.emit("Couldn't take snapshot: ${describe(e)}")
            }
        }
    }

    // --- Offline alerts ------------------------------------------------------------------

    fun setAlerts(on: Boolean) {
        store.setAlerts(on)
        scheduleWatch(on)
    }

    private fun scheduleWatch(on: Boolean) {
        val wm = WorkManager.getInstance(getApplication())
        if (on) wm.enqueueUniquePeriodicWork("nas-watch", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<WatchWorker>(15, TimeUnit.MINUTES).build())
        else wm.cancelUniqueWork("nas-watch")
    }

    // --- Tiles ---------------------------------------------------------------------------

    private fun buildTiles(cfg: ServerConfig, conn: Conn, sc: List<Shortcut>, st: NasStats, reach: Map<String, Boolean>): List<Tile> {
        if (!cfg.configured) return emptyList()
        val host = when (conn) {
            is Conn.Online -> conn.host
            is Conn.Resolving -> conn.prev?.host
            else -> null
        }
        val bare = host?.let(::bareHost)
        val out = mutableListOf<Tile>()
        out += Tile(
            id = "system", name = cfg.title, subtitle = st.version?.removePrefix("TrueNAS-") ?: "Web UI",
            url = host?.let { baseUrl(cfg, it) + cfg.kind.uiPath },
            icons = listOf(dashIcon(cfg.kind.icon)), kind = TileKind.SYSTEM,
            reachable = if (conn is Conn.Online) true else null,
        )
        val apiPorts = mutableSetOf<Int>()
        st.apps.forEach { a ->
            val portal = a.portals.firstOrNull()
            portal?.let { runCatching { URI(it).port }.getOrNull()?.takeIf { p -> p > 0 }?.let(apiPorts::add) }
            val id = "app:${a.name}"
            out += Tile(
                id = id, name = a.title,
                subtitle = a.state.label.ifBlank { a.version ?: "" },
                url = if (portal != null && bare != null) rewriteHost(portal, bare, cfg) else null,
                icons = listOfNotNull(a.icon, dashIcon(slug(a.name))),
                kind = TileKind.APP, state = a.state, reachable = reach[id], appName = a.name, upgrade = a.upgrade,
            )
        }
        // A linked app that's left the NAS's list: its shortcut is on the way out, so don't show it meanwhile.
        val installed = st.apps.map { it.name }.toSet()
        sc.filter { it.port !in apiPorts && (it.app == null || installed.isEmpty() || it.app in installed) }.forEach { s ->
            val id = "sc:${s.id}"
            val scheme = if (s.https) "https" else "http"
            val url = bare?.let { "$scheme://$it:${s.port}${s.path.ifBlank { "/" }}" }
            out += Tile(
                id = id, name = s.name, subtitle = "Port ${s.port}", url = url,
                icons = listOfNotNull(dashIcon(slug(s.name)), url?.let { "$scheme://$bare:${s.port}/favicon.ico" }),
                kind = TileKind.SHORTCUT, reachable = reach[id], shortcut = s,
            )
        }
        return out
    }

    private fun checkReach() {
        reachJob?.cancel()
        reachJob = viewModelScope.launch(Dispatchers.IO) {
            val conn = _conn.value as? Conn.Online ?: return@launch
            val targets = buildTiles(config.value, conn, shortcuts.value, _stats.value, emptyMap())
                .filter { it.kind != TileKind.SYSTEM && it.url != null }
            val results = targets.map { t ->
                async {
                    val u = URI(t.url!!)
                    val port = if (u.port > 0) u.port else if (u.scheme == "https") 443 else 80
                    t.id to tcpOpen(u.host, port)
                }
            }.awaitAll()
            _reach.value = results.toMap()
        }
    }

    private fun tcpOpen(host: String, port: Int) = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 1_500) }
        true
    }.getOrDefault(false)

    // --- Settings ------------------------------------------------------------------------

    /** Tries each address of a draft NAS; used by the "Test connection" button. */
    suspend fun test(c: ServerConfig): Pair<ProbeResult?, ProbeResult?> = coroutineScope {
        val be = backendFor(c.kind)
        val pin = profiles.value.firstOrNull { it.id == c.id }?.pin
        val l = fullHost(c, Store.normalizeHost(c.lanHost)).takeIf { it.isNotBlank() }?.let { async { be.probe(http, c, it, pin, 3_000) } }
        val t = fullHost(c, Store.normalizeHost(c.tailscaleHost)).takeIf { it.isNotBlank() }?.let { async { be.probe(http, c, it, pin, 3_000) } }
        l?.await() to t?.await()
    }

    /** Signs in with a draft NAS's credentials. Returns null on success, otherwise what went wrong. */
    suspend fun testLogin(c: ServerConfig, host: String): String? {
        val sess = backendFor(c.kind).session(sessionEnv(c.copy(pin = profiles.value.firstOrNull { it.id == c.id }?.pin), host)) ?: return null
        return try {
            withContext(Dispatchers.IO) { sess.login() }
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            describe(e)
        } finally {
            sess.close()
        }
    }

    /** Saves a NAS. With [activate] the home screen switches to it. */
    fun saveConfig(c: ServerConfig, activate: Boolean = true) {
        val isNew = profiles.value.none { it.id == c.id }
        store.saveConfig(c)
        if (isNew && activate && store.activeId.value != c.id) store.select(c.id)
        if (store.activeId.value == c.id) restartActive()
        checkStatus()
    }

    fun switchTo(id: String) {
        if (id == store.activeId.value) return
        store.select(id)
        restartActive()
    }

    fun removeProfile(id: String) {
        val wasActive = store.activeId.value == id
        store.removeProfile(id)
        if (wasActive) restartActive()
        _status.update { it - id }
    }

    private fun restartActive() {
        resolveJob?.cancel()
        stopApi()
        _api.value = ApiState.Off
        _stats.value = NasStats()
        _reach.value = emptyMap()
        _probes.value = emptyMap()
        _logs.value = null
        _snaps.value = null
        files.reset()
        _conn.value = if (config.value.configured) Conn.Resolving(net.value.vpn, null) else Conn.Setup
        resolve(force = true)
    }

    fun forgetPin() {
        store.setPin(null)
        resolve(force = true)
    }

    /** Quick reachability check for every saved NAS (for the list in Settings). */
    fun checkStatus() {
        statusJob?.cancel()
        val list = profiles.value
        _status.value = list.associate { it.id to null }
        statusJob = viewModelScope.launch {
            list.map { c ->
                async {
                    val be = backendFor(c.kind)
                    val hosts = listOf(c.lanHost).plus(if (net.value.vpn) c.tailscaleHost else "").filter { it.isNotBlank() }.map { fullHost(c, it) }
                    val ok = hosts.map { h -> async { be.probe(http, c, h, c.pin, 2_500).ok } }.awaitAll().any { it }
                    _status.update { it + (c.id to ok) }
                }
            }.awaitAll()
        }
    }

    /** When each linked app was first missing; one odd answer from the NAS must not delete anything. */
    private val appMissingSince = HashMap<String, Long>()

    /**
     * Shortcuts follow the NAS: one that points at an app gets linked to it, and when that app is
     * uninstalled the shortcut goes too. Stopped or crashed apps stay in the NAS's list, so they keep theirs.
     */
    private fun reconcileShortcuts() {
        val apps = _stats.value.apps
        if (apps.isEmpty()) return // nothing loaded yet, or the list couldn't be read: never treat that as "all gone"
        val byPort = apps.flatMap { a -> a.portals.mapNotNull { p -> runCatching { URI(p).port }.getOrNull()?.takeIf { it > 0 }?.let { it to a.name } } }.toMap()
        val names = apps.map { it.name }.toSet()
        val current = shortcuts.value
        val gone = mutableListOf<Shortcut>()
        val next = current.mapNotNull { s ->
            val linked = s.app
            when {
                linked == null -> byPort[s.port]?.let { s.copy(app = it) } ?: s
                linked in names -> { appMissingSince.remove(s.id); s }
                SystemClock.elapsedRealtime() - appMissingSince.getOrPut(s.id) { SystemClock.elapsedRealtime() } > 45_000 -> {
                    appMissingSince.remove(s.id)
                    gone += s
                    null
                }
                else -> s
            }
        }
        if (next != current) store.saveShortcuts(next)
        gone.forEach { messages.tryEmit("${it.name} was uninstalled, so its shortcut was removed") }
    }

    fun upsertShortcut(s: Shortcut) {
        val list = shortcuts.value
        store.saveShortcuts(if (list.any { it.id == s.id }) list.map { if (it.id == s.id) s else it } else list + s)
        checkReach()
    }

    fun removeShortcut(s: Shortcut) {
        store.saveShortcuts(shortcuts.value.filterNot { it.id == s.id })
    }

    override fun onCleared() {
        stopApi()
        monitor.close()
    }

    fun nasHosts(): Set<String> {
        val c = config.value
        return setOf(bareHost(c.lanHost), bareHost(c.tailscaleHost)).filter { it.isNotBlank() }.toSet()
    }

    val files: NasFiles by lazy {
        NasFiles(
            getApplication(), viewModelScope,
            api = { if (_api.value == ApiState.Ready) session?.files else null },
            ns = { config.value.id },
            onError = { messages.tryEmit(it) },
            client = ::downloadClient,
        )
    }

    /** Same trust rules as the WebView: the NAS's self-signed certs are accepted, nothing else is. */
    fun downloadClient(host: String): OkHttpClient {
        val base = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
        return if (host in nasHosts()) base.pinned(PinningTrustManager(null)) else base
    }

    companion object {
        fun bareHost(h: String) = if (h.startsWith("[")) h.substringBefore("]") + "]" else h.substringBefore(":")

        private val aliases = mapOf("file-browser" to "filebrowser", "truenas" to "truenas-scale")
        fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').let { aliases[it] ?: it }
        fun dashIcon(slug: String) = "https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png/$slug.png"

        /** Portals come back as http://0.0.0.0:port; point them at whichever route is live. */
        fun rewriteHost(url: String, host: String, cfg: ServerConfig): String = runCatching {
            val u = URI(url)
            val local = u.host == null || u.host == "0.0.0.0" || u.host == "localhost" ||
                u.host == bareHost(cfg.lanHost) || u.host == bareHost(cfg.tailscaleHost)
            if (!local) url else URI(u.scheme, u.userInfo, host, u.port, u.path, u.query, u.fragment).toString()
        }.getOrDefault(url)

        /** Keeps the page you're on when the route changes underneath an open app. */
        fun swapBase(current: String, target: String): String = runCatching {
            val c = URI(current)
            val t = URI(target)
            URI(t.scheme, c.userInfo, t.host, t.port, c.path, c.query, c.fragment).toString()
        }.getOrDefault(target)
    }
}
