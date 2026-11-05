package dev.dorki.harbor.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.dorki.harbor.ui.theme.Appearance
import dev.dorki.harbor.ui.theme.Schemes
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The NAS software Harbor knows how to talk to. */
enum class NasKind(
    val label: String,
    val blurb: String,
    val secretLabel: String,
    val usesUser: Boolean,
    val defaultHttps: Boolean,
    /** Where the admin web UI lives, relative to the host. */
    val uiPath: String,
    val icon: String,
    val portHttps: Int?,
    val portHttp: Int?,
    val experimental: Boolean = false,
    /** No API to talk to: Harbor offers status, the web UI and shortcuts. */
    val generic: Boolean = false,
) {
    TRUENAS("TrueNAS", "SCALE and Community: apps, pools, files, power", "API key", false, true, "/ui/", "truenas-scale", null, null),
    SYNOLOGY("Synology DSM", "Stats, Docker, files, power", "Password", true, true, "/", "synology-dsm", 5001, 5000, experimental = true),
    OMV("OpenMediaVault", "Stats, drives, shares, power", "Password", true, false, "/", "openmediavault", null, null, experimental = true),
    UNRAID("Unraid", "Array, Docker containers (needs the Unraid API key)", "API key", false, false, "/", "unraid", null, null, experimental = true),
    UMBREL("umbrelOS", "Apps, stats, updates, logs, power", "Password", false, false, "/", "umbrel", null, null, experimental = true),
    ZIMAOS("ZimaOS", "Apps, stats, files, power", "Password", true, false, "/", "zimaos", null, null, experimental = true),
    CASAOS("CasaOS", "Apps, stats, files, power", "Password", true, false, "/", "casaos", null, null, experimental = true),
    QNAP("QNAP QTS", "Stats and power", "Password", true, true, "/", "qnap", 443, 8080, experimental = true),
    PROXMOX("Proxmox VE", "Storage, VMs and containers (sign in as root@pam)", "Password", true, true, "/", "proxmox", 8006, null, experimental = true),
    ASUSTOR("ASUSTOR ADM", "Status, web UI and shortcuts", "Password", false, true, "/", "asustor", 8001, 8000, generic = true),
    TERRAMASTER("TerraMaster TOS", "Status, web UI and shortcuts", "Password", false, true, "/", "terramaster", 5443, 8181, generic = true),
    UGREEN("UGREEN UGOS", "Status, web UI and shortcuts", "Password", false, true, "/", "ugreen", 9443, 9999, generic = true),
    ROCKSTOR("Rockstor", "Status, web UI and shortcuts", "Password", false, true, "/", "rockstor", null, null, generic = true),
    XIGMANAS("XigmaNAS", "Status, web UI and shortcuts", "Password", false, true, "/", "xigmanas", null, null, generic = true),
    OTHER("Other NAS", "Anything with a web UI: status and shortcuts", "Password", false, true, "/", "nas", null, null, generic = true);

    fun defaultPort(https: Boolean): Int? = if (https) portHttps else portHttp
}

data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val kind: NasKind = NasKind.TRUENAS,
    val name: String = "",
    val lanHost: String = "",
    val tailscaleHost: String = "",
    val username: String = "",
    /** API key or password, depending on [kind]. */
    val apiKey: String = "",
    val https: Boolean = kind.defaultHttps,
    val preferLan: Boolean = true,
    /** SHA-256 of the NAS's TLS certificate, recorded the first time it answers. */
    val pin: String? = null,
) {
    val configured get() = lanHost.isNotBlank() || tailscaleHost.isNotBlank()
    val title get() = name.ifBlank { kind.label }
    val hasLogin get() = apiKey.isNotBlank() && (!kind.usesUser || username.isNotBlank())
}

data class Shortcut(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val port: Int,
    val path: String = "/",
    val https: Boolean = false,
    /** The NAS app this shortcut points at, once Harbor has seen it; set so it can go when the app does. */
    val app: String? = null,
)

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("harbor", Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(readProfiles())
    /** Every NAS Harbor knows about, in the order they were added. */
    val profiles: StateFlow<List<ServerConfig>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(
        prefs.getString("active", null)?.takeIf { id -> _profiles.value.any { it.id == id } } ?: _profiles.value.firstOrNull()?.id ?: ""
    )
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    private val _config = MutableStateFlow(activeProfile())
    /** The NAS currently shown on the home screen. */
    val config: StateFlow<ServerConfig> = _config.asStateFlow()

    private val _shortcuts = MutableStateFlow(readShortcuts(_config.value.id))
    val shortcuts: StateFlow<List<Shortcut>> = _shortcuts.asStateFlow()

    private val _alerts = MutableStateFlow(prefs.getBoolean("alerts", false))
    /** Whether the background "NAS went offline" check is on. */
    val alerts: StateFlow<Boolean> = _alerts.asStateFlow()

    private val _appearance = MutableStateFlow(readAppearance())
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    fun setAppearance(a: Appearance) {
        prefs.edit().putString("scheme", a.scheme).putBoolean("black", a.black).putFloat("blur", a.blur)
            .putInt("radius", a.radius).putInt("black_radius", a.blackRadius).apply()
        _appearance.value = a
    }

    private fun readAppearance() = Appearance(
        scheme = prefs.getString("scheme", null) ?: Schemes.Harbor.id,
        black = prefs.getBoolean("black", false),
        blur = prefs.getFloat("blur", 0.7f),
        radius = prefs.getInt("radius", 22),
        blackRadius = prefs.getInt("black_radius", 12),
    )

    fun setAlerts(on: Boolean) {
        val e = prefs.edit().putBoolean("alerts", on)
        prefs.all.keys.filter { it.startsWith("watch_") }.forEach(e::remove)
        e.apply()
        _alerts.value = on
    }

    private fun activeProfile() = _profiles.value.firstOrNull { it.id == _activeId.value } ?: ServerConfig(id = "")

    /** Switches which NAS the home screen shows. */
    fun select(id: String) {
        if (_profiles.value.none { it.id == id }) return
        prefs.edit().putString("active", id).apply()
        _activeId.value = id
        publish()
    }

    private fun publish() {
        _config.value = activeProfile()
        _shortcuts.value = readShortcuts(_config.value.id)
    }

    fun setPin(value: String?) = setPin(_config.value.id, value)

    fun setPin(id: String, value: String?) {
        val list = _profiles.value.map { if (it.id == id) it.copy(pin = value) else it }
        writeProfiles(list)
    }

    /** Adds or updates a NAS (matched by id). The first one added becomes the active NAS. */
    fun saveConfig(c: ServerConfig) {
        val old = _profiles.value.firstOrNull { it.id == c.id }
        val clean = c.copy(
            name = c.name.trim(),
            lanHost = normalizeHost(c.lanHost),
            tailscaleHost = normalizeHost(c.tailscaleHost),
            username = c.username.trim(),
            apiKey = c.apiKey.trim(),
            // A different NAS (or software) at the same profile invalidates the old certificate pin.
            pin = if (old == null || old.kind != c.kind) null else old.pin,
        )
        val list = if (old == null) _profiles.value + clean else _profiles.value.map { if (it.id == clean.id) clean else it }
        if (old == null && _profiles.value.isEmpty() || _activeId.value.isEmpty()) {
            _activeId.value = clean.id
            prefs.edit().putString("active", clean.id).apply()
        }
        writeProfiles(list)
    }

    fun removeProfile(id: String) {
        val list = _profiles.value.filterNot { it.id == id }
        prefs.edit().remove("shortcuts_$id").apply()
        if (_activeId.value == id) {
            _activeId.value = list.firstOrNull()?.id ?: ""
            prefs.edit().putString("active", _activeId.value).apply()
        }
        writeProfiles(list)
    }

    fun saveShortcuts(list: List<Shortcut>) {
        saveShortcutsOf(_config.value.id, list)
    }

    fun shortcutsOf(id: String) = readShortcuts(id)

    fun saveShortcutsOf(id: String, list: List<Shortcut>) {
        if (id.isEmpty()) return
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("port", it.port)
                    .put("path", it.path).put("https", it.https).put("app", it.app ?: "")
            )
        }
        prefs.edit().putString("shortcuts_$id", arr.toString()).apply()
        if (id == _config.value.id) _shortcuts.value = list
    }

    private fun writeProfiles(list: List<ServerConfig>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id).put("kind", c.kind.name).put("name", c.name)
                    .put("lan", c.lanHost).put("ts", c.tailscaleHost).put("user", c.username)
                    .put("key", if (c.apiKey.isBlank()) "" else KeyCrypto.encrypt(c.apiKey))
                    .put("https", c.https).put("prefer_lan", c.preferLan).put("pin", c.pin ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString("profiles", arr.toString()).apply()
        _profiles.value = list
        publish()
    }

    private fun readProfiles(): List<ServerConfig> {
        val raw = prefs.getString("profiles", null)
        if (raw != null) {
            return runCatching {
                val arr = JSONArray(raw)
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    val kind = NasKind.entries.firstOrNull { it.name == o.optString("kind") } ?: NasKind.TRUENAS
                    ServerConfig(
                        id = o.getString("id"), kind = kind, name = o.optString("name"),
                        lanHost = o.optString("lan"), tailscaleHost = o.optString("ts"), username = o.optString("user"),
                        apiKey = o.optString("key").takeIf { it.isNotBlank() }?.let(KeyCrypto::decrypt) ?: "",
                        https = o.optBoolean("https", kind.defaultHttps), preferLan = o.optBoolean("prefer_lan", true),
                        pin = if (o.isNull("pin")) null else o.optString("pin").takeIf { it.isNotBlank() },
                    )
                }
            }.getOrDefault(emptyList())
        }
        // Harbor used to hold a single TrueNAS; carry it over as the first profile.
        val lan = prefs.getString("lan", null) ?: ""
        val ts = prefs.getString("ts", null) ?: ""
        if (lan.isBlank() && ts.isBlank()) return emptyList()
        val legacy = ServerConfig(
            id = "default", kind = NasKind.TRUENAS,
            name = prefs.getString("name", null) ?: "TrueNAS", lanHost = lan, tailscaleHost = ts,
            apiKey = prefs.getString("key", null)?.takeIf { it.isNotBlank() }?.let(KeyCrypto::decrypt) ?: "",
            preferLan = prefs.getBoolean("prefer_lan", true), pin = prefs.getString("cert_pin", null),
        )
        val arr = JSONArray().put(
            JSONObject().put("id", legacy.id).put("kind", legacy.kind.name).put("name", legacy.name)
                .put("lan", lan).put("ts", ts).put("user", "")
                .put("key", if (legacy.apiKey.isBlank()) "" else KeyCrypto.encrypt(legacy.apiKey))
                .put("https", true).put("prefer_lan", legacy.preferLan).put("pin", legacy.pin ?: JSONObject.NULL)
        )
        prefs.getString("shortcuts", null)?.let { prefs.edit().putString("shortcuts_default", it).apply() }
        prefs.edit().putString("profiles", arr.toString()).apply()
        return listOf(legacy)
    }

    private fun readShortcuts(id: String): List<Shortcut> {
        if (id.isEmpty()) return emptyList()
        val raw = prefs.getString("shortcuts_$id", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Shortcut(
                    id = o.getString("id"), name = o.getString("name"), port = o.getInt("port"),
                    path = o.optString("path", "/"), https = o.optBoolean("https"), app = o.optString("app").ifBlank { null },
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        /** Accepts "http://host:port/whatever" and returns "host:port". */
        fun normalizeHost(raw: String): String =
            raw.trim().substringAfter("://").substringBefore("/").trim()
    }
}

/** Keeps NAS credentials encrypted with a hardware-backed key. */
private object KeyCrypto {
    private const val ALIAS = "harbor.apikey"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String? = runCatching {
        val b = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        String(c.doFinal(b, 12, b.size - 12))
    }.getOrNull()
}
