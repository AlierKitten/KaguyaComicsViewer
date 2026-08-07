package com.kaguya.comicsviewer.util

import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {
    private val bytesFormat = DecimalFormat("#,##0.#")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return "${bytesFormat.format(value)} ${units[unit]}"
    }

    fun formatDate(epoch: Long?): String {
        if (epoch == null || epoch <= 0) return "—"
        return dateFormat.format(Date(epoch))
    }
}
