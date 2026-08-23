package com.kaguya.comicsviewer.work

import com.kaguya.comicsviewer.R
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kaguya.comicsviewer.MainActivity
import com.kaguya.comicsviewer.notification.NotificationChannels
import com.kaguya.comicsviewer.work.IndexKeepAliveService.Companion.stop

/**
 * 轻量前台保活服务：仅用于在前台显示一个「正在后台索引」通知，
 * 防止系统回收 App 进程导致后台 index 中断。索引逻辑本身运行在
 * [com.kaguya.comicsviewer.domain.usecase.ScanSourceUseCase] 的后台协程中，
 * 与本服务无关。index 全部结束后由 ScanSourceUseCase 调用 [stop]。
 *
 * 通知支持实时进度（多个源时显示总体进度百分比，不展示具体名字）。
 */
class IndexKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFY_ID, buildNotification())
        // 除非显式 stopSelf，否则不主动结束
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            NOTIFY_ID,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NotificationChannels.CHANNEL_GENERAL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.notify_indexing_title))
            .setContentText(getString(R.string.notify_indexing_scanning))
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val NOTIFY_ID = 5001

        fun start(context: Context) {
            val intent = Intent(context, IndexKeepAliveService::class.java)
            context.startForegroundService(intent)
        }

        /**
         * 更新正在运行的前台服务通知进度（不展示具体名字，只显示总体百分比）。
         * 通过同一通知 ID 直接 notify，复用前台通知而不打断 startForeground 状态。
         * 若服务未运行（通知不存在）则无操作，避免误建重复通知。
         */
        fun updateProgress(context: Context, current: Int, total: Int) {
            val manager = NotificationManagerCompat.from(context)
            if (!hasNotificationPermission(context)) return
            if (manager.activeNotifications.none { it.id == NOTIFY_ID }) return
            val pct = if (total > 0) (current * 100 / total).coerceIn(0, 100) else 0
            val pi = PendingIntent.getActivity(
                context,
                NOTIFY_ID,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_GENERAL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(context.getString(R.string.notify_indexing_title))
                .setContentText(context.getString(R.string.notify_indexing_progress, pct))
                .setProgress(100, pct, false)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            try {
                manager.notify(NOTIFY_ID, notification)
            } catch (_: SecurityException) {
                // 无 POST_NOTIFICATIONS 权限时忽略，不影响后台索引
            }
        }

        /** Android 13+ 需运行时 POST_NOTIFICATIONS 权限；低版本默认授予。 */
        fun hasNotificationPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IndexKeepAliveService::class.java))
            NotificationManagerCompat.from(context).cancel(NOTIFY_ID)
        }
    }
}
