package dev.dorki.harbor.net

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.dorki.harbor.R
import dev.dorki.harbor.ui.components.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps Harbor in the foreground while files are moving, and shows their live progress.
 * Android cuts network access for background apps, so without this a transfer dies the
 * moment you switch apps or lock the phone.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    // For speed/ETA: bytes moved at the previous update, smoothed so the numbers don't jitter.
    private var lastBytes = -1L
    private var lastAt = 0L
    private var speed = 0.0

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val transfers = Downloads.get(this)
        if (intent?.action == ACTION_CANCEL) transfers.cancelAll()

        TransferNotifications.channels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID, build(transfers.items.value), type)

        if (watcher == null) {
            val nm = getSystemService(NotificationManager::class.java)
            // sample(), not debounce(): progress changes constantly, and debounce would never fire mid-transfer.
            watcher = scope.launch {
                transfers.items.sample(500).collect { items ->
                    if (items.none { it.state == DownloadItem.State.RUNNING }) {
                        ServiceCompat.stopForeground(this@TransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        nm.notify(ID, build(items))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(items: List<DownloadItem>): Notification {
        val running = items.filter { it.state == DownloadItem.State.RUNNING }
        val ups = running.count { it.upload }
        val downs = running.size - ups
        val verb = when {
            ups > 0 && downs > 0 -> "Transferring ${running.size} files"
            ups > 0 -> if (ups == 1) "Uploading" else "Uploading $ups files"
            downs > 0 -> if (downs == 1) "Downloading" else "Downloading $downs files"
            else -> "Preparing transfer"
        }

        val known = running.isNotEmpty() && running.all { it.total > 0 }
        val total = running.sumOf { it.total.coerceAtLeast(0) }
        val done = running.sumOf { it.done }
        val pct = if (known && total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0

        val now = SystemClock.elapsedRealtime()
        if (lastBytes in 0..done && now > lastAt) {
            val instant = (done - lastBytes) * 1000.0 / (now - lastAt)
            speed = if (speed == 0.0) instant else speed * 0.6 + instant * 0.4
        } else {
            speed = 0.0
        }
        lastBytes = done
        lastAt = now

        val parts = mutableListOf(if (known) "${formatBytes(done)} of ${formatBytes(total)}" else formatBytes(done))
        if (speed > 1024) parts += "${formatBytes(speed.toLong())}/s"
        if (known && speed > 1024) parts += eta(((total - done) / speed).toLong())
        val summary = parts.joinToString(" · ")

        val title = if (running.size == 1) "$verb ${running[0].name}" else verb
        val style = if (running.size > 1) {
            NotificationCompat.InboxStyle().also { s ->
                running.take(6).forEach { item ->
                    val f = item.fraction
                    s.addLine((if (item.upload) "↑ " else "↓ ") + item.name + (f?.let { " — ${(it * 100).toInt()}%" } ?: ""))
                }
                if (running.size > 6) s.addLine("+${running.size - 6} more")
                s.setSummaryText(summary)
            }
        } else null

        val cancel = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, TransferNotifications.PROGRESS_CHANNEL)
            .setSmallIcon(if (ups > 0 && downs == 0) R.drawable.ic_stat_upload else R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(if (known) "$pct% · $summary" else summary)
            .setSubText(if (known) "$pct%" else null)
            .setProgress(100, pct, !known)
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(TransferNotifications.openApp(this))
            .addAction(R.drawable.ic_stat_error, "Cancel", cancel)
            .build()
    }

    private fun eta(sec: Long): String = when {
        sec < 60 -> "${sec.coerceAtLeast(1)}s left"
        sec < 3600 -> "${sec / 60}m ${sec % 60}s left"
        else -> "${sec / 3600}h ${(sec % 3600) / 60}m left"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ID = 7
        private const val ACTION_CANCEL = "dev.dorki.harbor.CANCEL_TRANSFERS"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java)) }
        }
    }
}
