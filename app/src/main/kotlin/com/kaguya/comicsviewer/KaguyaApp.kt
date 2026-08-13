package com.kaguya.comicsviewer

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import com.kaguya.comicsviewer.notification.NotificationChannels
import com.kaguya.comicsviewer.ui.reader.ArchiveFetcher
import com.tencent.mmkv.MMKV
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import javax.inject.Inject

@HiltAndroidApp
class KaguyaApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var notificationChannels: NotificationChannels

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ImageLoaderEntryPoint {
        fun archiveFetcher(): ArchiveFetcher
    }

    override fun onCreate() {
        super.onCreate()

        // 配置全局 ImageLoader，注册压缩包单页 Fetcher
        val entryPoint = EntryPointAccessors.fromApplication(this, ImageLoaderEntryPoint::class.java)
        val imageLoader = ImageLoader.Builder(this)
            .components { add(entryPoint.archiveFetcher()) }
            .build()
        coil.Coil.setImageLoader(imageLoader)

        // 设置全局未捕获异常处理器，将崩溃日志写入文件
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("KaguyaApp", "FATAL CRASH in thread '${thread.name}'", throwable)
            // 写入 crash 文件方便查看
            try {
                val crashFile = File(cacheDir, "crash_${System.currentTimeMillis()}.txt")
                crashFile.parentFile?.mkdirs()
                PrintWriter(crashFile).use { pw ->
                    pw.println("Thread: ${thread.name}")
                    pw.println("Time: ${System.currentTimeMillis()}")
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    pw.println(sw.toString())
                }
                Log.e("KaguyaApp", "Crash log written to: ${crashFile.absolutePath}")
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Log.d("KaguyaApp", "App onCreate started")
        val mmkvDir = MMKV.initialize(this)
        Log.d("KaguyaApp", "MMKV initialized, dir=$mmkvDir")
        notificationChannels.ensureCreated()
        Log.d("KaguyaApp", "Notification channels created")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
}
