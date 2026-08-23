package com.kaguya.comicsviewer.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.tencent.mmkv.MMKV
import java.util.Locale

/**
 * 语言代码（小写带 region）与 Android Locale / 资源限定符的映射工具。
 * 存储值：zh-cn / en-us / ja-jp
 */
object LocaleHelper {
    const val LANG_ZH_CN = "zh-cn"
    const val LANG_EN_US = "en-us"
    const val LANG_JA_JP = "ja-jp"
    val SUPPORTED = listOf(LANG_ZH_CN, LANG_EN_US, LANG_JA_JP)
    const val DEFAULT = LANG_EN_US

    private val TAG_TO_LOCALE = mapOf(
        LANG_ZH_CN to "zh-CN",
        LANG_EN_US to "en-US",
        LANG_JA_JP to "ja-JP"
    )

    fun toLocale(lang: String): Locale {
        val tag = TAG_TO_LOCALE[lang] ?: TAG_TO_LOCALE[DEFAULT]!!
        return Locale.forLanguageTag(tag)
    }

    /** 根据系统首选语言推断应用语言，未适配则回退英语。 */
    fun resolveSystemLanguage(): String {
        val locales = LocaleList.getAdjustedDefault()
        for (i in 0 until locales.size()) {
            val lang = locales[i].language.lowercase()
            when (lang) {
                "zh" -> return LANG_ZH_CN
                "ja" -> return LANG_JA_JP
                "en" -> return LANG_EN_US
            }
        }
        return DEFAULT
    }

    fun applyLocale(base: Context, lang: String): Context {
        val locale = toLocale(lang)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * 直接从 MMKV 读取已存储的语言（不经过 Hilt 注入的 Repository）。
     * 用于 attachBaseContext 等注入尚未完成的早期生命周期阶段。
     * 若未设置或非法，返回 [DEFAULT]。
     */
    fun readStoredLanguage(): String {
        val stored = runCatching { MMKV.defaultMMKV()?.decodeString("language") }.getOrNull()
        return if (!stored.isNullOrEmpty() && stored in SUPPORTED) stored else DEFAULT
    }
}
