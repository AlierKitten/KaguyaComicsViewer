package com.kaguya.comicsviewer.work

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.kaguya.comicsviewer.MainActivity
import com.kaguya.comicsviewer.notification.NotificationChannels
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ComicNotifications @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun showProgress(comicId: Long, title: String, progress: Int, totalBytes: Long, downloadedBytes: Long) {
        val nm: NotificationManager = context.getSystemService() ?: return
        val pi = PendingIntent.getActivity(
            context,
            comicId.toInt(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (totalBytes > 0) {
            "${humanBytes(downloadedBytes)} / ${humanBytes(totalBytes)}"
        } else {
            "$progress%"
        }
        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("下载：$title")
            .setContentText(text)
            .setProgress(100, progress.coerceIn(0, 100), totalBytes <= 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(notificationId(comicId), notification)
    }

    fun showReady(comicId: Long, title: String) {
        val nm: NotificationManager = context.getSystemService() ?: return
        val pi = PendingIntent.getActivity(
            context,
            comicId.toInt(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("已就绪：$title")
            .setContentText("可以开始阅读")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(notificationId(comicId), notification)
    }

    fun showFailed(comicId: Long, title: String, error: String?) {
        val nm: NotificationManager = context.getSystemService() ?: return
        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("下载失败：$title")
            .setContentText(error?.take(120) ?: "未知错误")
            .setAutoCancel(true)
            .build()
        nm.notify(notificationId(comicId), notification)
    }

    fun cancel(comicId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(comicId))
    }

    private fun notificationId(comicId: Long): Int = ((comicId % Int.MAX_VALUE).toInt() + 1000)

    private fun humanBytes(b: Long): String {
        if (b <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.getDefault(), "%.1f %s", v, units[i])
    }
}
