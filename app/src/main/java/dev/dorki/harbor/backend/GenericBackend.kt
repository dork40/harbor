package dev.dorki.harbor.backend

import dev.dorki.harbor.data.NasKind
import dev.dorki.harbor.data.ServerConfig
import dev.dorki.harbor.net.ProbeResult
import okhttp3.OkHttpClient
import okhttp3.Request

/** Any NAS without an API Harbor knows (Asustor, TerraMaster, a Pi with Samba…): reachability, the web UI and shortcuts. */
class GenericBackend private constructor(override val kind: NasKind) : NasBackend {
    override val caps = Caps()

    override suspend fun probe(base: OkHttpClient, cfg: ServerConfig, host: String, pin: String?, timeoutMs: Long): ProbeResult =
        httpProbe(base, cfg, host, pin, timeoutMs, request = { Request.Builder().url("$it/").build() }, verify = { _, _ -> null })

    override fun session(env: SessionEnv): NasSession? = null

    companion object {
        private val all = HashMap<NasKind, GenericBackend>()
        @Synchronized fun of(kind: NasKind) = all.getOrPut(kind) { GenericBackend(kind) }
    }
}
