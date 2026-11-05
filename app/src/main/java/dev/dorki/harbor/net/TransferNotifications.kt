package dev.dorki.harbor.net

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.dorki.harbor.MainActivity
import dev.dorki.harbor.R
import dev.dorki.harbor.ui.components.formatBytes

/** Notifications for file transfers: the live one lives in [TransferService], these mark the end. */
object TransferNotifications {
    const val PROGRESS_CHANNEL = "transfer_progress"
    private const val DONE_CHANNEL = "transfers_done"
    private const val DONE_BASE_ID = 1_000

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("transfers") // older low-importance channel that tucked progress under "Silent"
        // Default importance keeps progress in the main section, expanded; no sound, and onlyAlertOnce stops repeats.
        nm.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL, "Transfer progress", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Live progress while files upload or download"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(DONE_CHANNEL, "Finished transfers", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When an upload or download completes or fails"
                setSound(null, null)
            }
        )
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    fun finished(context: Context, item: DownloadItem) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        channels(context)
        val ok = item.state == DownloadItem.State.DONE
        val title = when {
            ok && item.upload -> "Upload complete"
            ok -> "Download complete"
            item.upload -> "Upload failed"
            else -> "Download failed"
        }
        val text = when {
            !ok -> "${item.name} · ${item.error ?: "Something went wrong"}"
            item.upload -> "${item.name} · ${formatBytes(item.total.coerceAtLeast(item.done))} → ${item.dir?.substringAfterLast('/') ?: "NAS"}"
            else -> "${item.name} · ${formatBytes(item.total.coerceAtLeast(item.done))} · Tap to open"
        }
        // A finished download opens the file itself; everything else opens Harbor.
        val tap = item.uri?.takeIf { ok && !item.upload }?.let { uri ->
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, context.contentResolver.getType(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(context, (DONE_BASE_ID + item.id).toInt(), view, PendingIntent.FLAG_IMMUTABLE)
        } ?: openApp(context)
        val n = NotificationCompat.Builder(context, DONE_CHANNEL)
            .setSmallIcon(if (ok) R.drawable.ic_stat_done else R.drawable.ic_stat_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setGroup("harbor-transfers-done")
            .build()
        context.getSystemService(NotificationManager::class.java).notify((DONE_BASE_ID + item.id).toInt(), n)
    }
}
