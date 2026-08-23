package com.kaguya.comicsviewer.util

/**
 * 携带字符串资源 id 与格式化参数的 Toast 事件，便于国际化。
 * args 用于 String.format 风格的占位符填充。
 */
data class ToastEvent(val resId: Int, val args: Array<out Any>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ToastEvent
        if (resId != other.resId) return false
        if (!args.contentEquals(other.args)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = resId
        result = 31 * result + args.contentHashCode()
        return result
    }
}
