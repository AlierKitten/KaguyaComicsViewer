package com.kaguya.comicsviewer.domain.model

/** 漫画数据源类型。 */
enum class ComicSourceType {
    /** 本地文件夹（通过 SAF 取得 DocumentFile）。 */
    LOCAL,

    /** SMB 远程共享。 */
    SMB
}

/** 漫画库中一本书的元数据。 */
data class Comic(
    val id: Long,
    val sourceId: Long,
    val title: String,
    val filePath: String,
    val sizeBytes: Long,
    val pageCount: Int,
    val coverPath: String?,
    val addedAt: Long,
    val updatedAt: Long
)

/** 漫画的本地缓存状态。 */
data class ComicCache(
    val comicId: Long,
    val state: CacheState,
    val archiveFile: String?,
    val extractedDir: String?,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val lastError: String?
)

enum class CacheState {
    /** 未缓存，需要下载/复制。 */
    PENDING,

    /** 正在下载/复制。 */
    DOWNLOADING,

    /** 已下载但未解压。 */
    DOWNLOADED,

    /** 正在解压。 */
    EXTRACTING,

    /** 已解压，可直接阅读。 */
    READY,

    /** 失败。 */
    FAILED
}

/** 阅读进度。 */
data class ReadingProgress(
    val comicId: Long,
    val page: Int,
    val updatedAt: Long,
    val isFinished: Boolean = false
)

/** 漫画页的展示模型（来自解压目录）。 */
data class ComicPage(
    val comicId: Long,
    val index: Int,
    val path: String
)
