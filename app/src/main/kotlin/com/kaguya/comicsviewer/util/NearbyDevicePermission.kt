package com.kaguya.comicsviewer.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 「附近的设备」权限：SMB 走局域网，缺少该组权限时系统会直接屏蔽本地网络连接
 * （Android 13+ 为 NEARBY_WIFI_DEVICES，Android 17+ 为 ACCESS_LOCAL_NETWORK，两者同属 NEARBY_DEVICES 组）。
 */
object NearbyDevicePermission {

    /** 当前系统版本下需要的「附近的设备」权限（低版本无需申请，返回空数组）。 */
    fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }.toTypedArray()

    /** 是否已具备当前系统所需的「附近的设备」权限。 */
    fun isGranted(context: Context): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
