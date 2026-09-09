package com.kaguya.comicsviewer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaguya.comicsviewer.data.prefs.AppSettings
import com.kaguya.comicsviewer.data.prefs.SettingsRepository
import com.kaguya.comicsviewer.domain.model.ReadingMode
import com.kaguya.comicsviewer.ui.KaguyaApp
import com.kaguya.comicsviewer.ui.theme.KaguyaTheme
import com.kaguya.comicsviewer.ui.theme.LocalSpacing
import com.kaguya.comicsviewer.ui.theme.Spacing
import com.kaguya.comicsviewer.util.LocaleHelper
import com.kaguya.comicsviewer.util.NearbyDevicePermission
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private companion object {
        const val PREFS_STARTUP = "startup_permissions"
        const val KEY_NEARBY_PERMISSION_REQUESTED = "nearby_permission_requested"
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase, LocaleHelper.readStoredLanguage()))
    }

    private val startupPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        Log.d("MainActivity", "Startup permissions: $result")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("MainActivity", "onCreate started")
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestStartupPermissions()

        Log.d("MainActivity", "setting content")

        // 在内容绘制前根据已持久化的设置应用隐私策略，确保启动即生效
        applyRecentsPrivacy(settingsRepository.settings.value.hideFromRecents)

        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings(ReadingMode.PAGED, true, false, true, false, true)
            )
            val darkTheme = when {
                settings.followSystemTheme -> isSystemInDarkTheme()
                else -> settings.darkMode
            }

            // 根据设置隐藏/显示系统最近任务中的预览图：
            // FLAG_SECURE 禁止截图/录屏；API33+ setRecentsScreenshotEnabled(false) 才是真正隐藏最近任务缩略图（应用仍保留在最近任务中）
            DisposableEffect(settings.hideFromRecents) {
                applyRecentsPrivacy(settings.hideFromRecents)
                onDispose { applyRecentsPrivacy(false) }
            }

            KaguyaTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColor
            ) {
                CompositionLocalProvider(LocalSpacing provides Spacing()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        KaguyaApp()
                    }
                }
            }
        }
        Log.d("MainActivity", "onCreate done")
    }

    /**
     * 启动时的权限申请：
     * - Android 13+ 通知权限：未授予时申请（下载/索引进度通知依赖它）；
     * - 「附近的设备」权限（NEARBY_WIFI_DEVICES / ACCESS_LOCAL_NETWORK）：仅在首次启动申请一次，
     *   缺少时系统会屏蔽局域网连接，SMB 源将不可用；被拒后可在「添加 SMB 源」时再次触发申请。
     */
    private fun requestStartupPermissions() {
        val pending = LinkedHashSet<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pending.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val prefs = getSharedPreferences(PREFS_STARTUP, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_NEARBY_PERMISSION_REQUESTED, false) &&
            !NearbyDevicePermission.isGranted(this)
        ) {
            pending.addAll(NearbyDevicePermission.requiredPermissions())
            prefs.edit().putBoolean(KEY_NEARBY_PERMISSION_REQUESTED, true).apply()
        }

        if (pending.isNotEmpty()) {
            startupPermissionsLauncher.launch(pending.toTypedArray())
        }
    }

    /** 应用/取消「隐藏最近任务预览图」：FLAG_SECURE 禁止截图；API33+ setRecentsScreenshotEnabled 隐藏最近任务缩略图（应用仍保留在最近任务中）。 */
    @androidx.annotation.ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    private fun applyRecentsPrivacy(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setRecentsScreenshotEnabled(false)
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setRecentsScreenshotEnabled(true)
            }
        }
    }
}
