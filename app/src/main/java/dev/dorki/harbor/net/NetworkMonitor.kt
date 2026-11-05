package dev.dorki.harbor.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class NetState(
    val online: Boolean = true,
    val vpn: Boolean = false,
    val wifi: Boolean = false,
    val cellular: Boolean = false,
    /** Changes whenever the set of underlying networks does, e.g. home Wi-Fi to café Wi-Fi. */
    val key: String = "",
)

/** Watches every network the app can use, VPNs included, so Tailscale toggles are seen. */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val nets = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val _state = MutableStateFlow(NetState())
    val state: StateFlow<NetState> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            nets[network] = caps
            publish()
        }

        override fun onLost(network: Network) {
            nets.remove(network)
            publish()
        }
    }

    init {
        @Suppress("DEPRECATION")
        cm.allNetworks.forEach { n -> cm.getNetworkCapabilities(n)?.let { nets[n] = it } }
        publish()
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    private fun publish() {
        val all = nets.entries.toList()
        val (vpns, real) = all.partition { it.value.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        _state.value = NetState(
            online = real.any { it.value.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } || vpns.isNotEmpty(),
            vpn = vpns.isNotEmpty(),
            wifi = real.any {
                it.value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    it.value.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            },
            cellular = real.any { it.value.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
            key = real.map { it.key.toString() }.sorted().joinToString(","),
        )
    }

    fun close() = runCatching { cm.unregisterNetworkCallback(callback) }
}
