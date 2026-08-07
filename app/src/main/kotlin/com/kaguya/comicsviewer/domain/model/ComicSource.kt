package com.kaguya.comicsviewer.domain.model

/** 文件源配置（本地或 SMB）。 */
data class ComicSource(
    val id: Long,
    val type: ComicSourceType,
    val name: String,
    /** 本地：DocumentFile tree URI（content://...）。SMB：空。 */
    val localUri: String?,
    /** SMB：host:port，例 "192.168.1.10:445"。本地：空。 */
    val host: String?,
    val share: String?,
    val path: String?,
    val username: String?,
    val password: String?,
    val domain: String?,
    val enabled: Boolean,
    val lastScannedAt: Long?
)
