package com.kaguya.comicsviewer.domain.model

/** 索引进度状态（落库，用于被杀后判断真实状态，避免误报完成）。 */
enum class IndexStatus {
    IDLE,       // 无索引记录
    SCANNING,   // 正在索引
    DONE,       // 已正常完成
    CANCELLED,  // 被用户取消
    FAILED      // 出错终止
}

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
    val lastScannedAt: Long?,
    /** 索引进度状态（落库）。 */
    val indexStatus: IndexStatus = IndexStatus.IDLE,
    /** 已处理的漫画数（落库，供被杀后判断）。 */
    val indexCurrent: Int = 0,
    /** 本次扫描预计处理的漫画总数（落库）。 */
    val indexTotal: Int = 0
)
