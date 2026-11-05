package dev.dorki.harbor.net

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.dorki.harbor.backend.backendFor
import dev.dorki.harbor.backend.fullHost
import dev.dorki.harbor.data.ServerConfig
import dev.dorki.harbor.data.Store
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Every ~15 minutes, for each saved NAS: if the phone is on the same home subnet as the NAS and
 * the NAS was answering a moment ago but now misses two checks in a row, post one notification.
 */
class WatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("harbor", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("alerts", false)) return Result.success()
        val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).build()
        Store(ctx).profiles.value.forEachIndexed { n, cfg -> check(ctx, prefs, http, cfg, n) }
        return Result.success()
    }

    private suspend fun check(ctx: Context, prefs: android.content.SharedPreferences, http: OkHttpClient, cfg: ServerConfig, n: Int) {
        if (cfg.lanHost.isBlank()) return
        val up = "watch_up_${cfg.id}"
        val fl = "watch_fails_${cfg.id}"
        val told = "watch_told_${cfg.id}"
        if (!onHomeSubnet(ctx, cfg.lanHost)) {
            prefs.edit().putBoolean(up, false).putInt(fl, 0).apply()
            return
        }
        val r = backendFor(cfg.kind).probe(http, cfg, fullHost(cfg, cfg.lanHost), cfg.pin, 4_000)
        if (r.ok) {
            prefs.edit().putBoolean(up, true).putInt(fl, 0).putBoolean(told, false).apply()
        } else if (prefs.getBoolean(up, false)) {
            val fails = prefs.getInt(fl, 0) + 1
            prefs.edit().putInt(fl, fails).apply()
            if (fails >= 2 && !prefs.getBoolean(told, false)) {
                notify(ctx, cfg.title, n + 1)
                prefs.edit().putBoolean(told, true).apply()
            }
        }
    }

    private fun notify(ctx: Context, name: String, id: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nas", "NAS status", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(
            id,
            android.app.Notification.Builder(ctx, "nas")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("$name isn't responding")
                .setContentText("It stopped answering on your home network.")
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        /** True when a Wi-Fi/Ethernet network on this phone shares the NAS address's first three numbers. */
        fun onHomeSubnet(ctx: Context, lanHost: String): Boolean {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val parts = lanHost.substringBefore(":").split(".")
            val literal = parts.size == 4 && parts.all { it.toIntOrNull() != null }
            @Suppress("DEPRECATION")
            return cm.allNetworks.any { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@any false
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@any false
                if (!literal) return@any true
                val prefix = parts.take(3).joinToString(".") + "."
                cm.getLinkProperties(n)?.linkAddresses?.any { (it.address.hostAddress ?: "").startsWith(prefix) } == true
            }
        }
    }
}
