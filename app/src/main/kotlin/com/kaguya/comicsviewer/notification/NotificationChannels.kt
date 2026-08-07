package com.kaguya.comicsviewer.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationChannels @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun ensureCreated() {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_DOWNLOAD,
                    "下载与同步",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "显示漫画下载与解压进度"
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_GENERAL,
                    "通用",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        )
    }

    companion object {
        const val CHANNEL_DOWNLOAD = "download"
        const val CHANNEL_GENERAL = "general"
    }
}
