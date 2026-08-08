package com.kaguya.comicsviewer.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 集中管理 App 缓存目录布局。 */
@Singleton
class CacheDirectories @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 缓存根目录。 */
    val root: File
        get() = File(context.cacheDir, "comics").apply { if (!exists()) mkdirs() }

    /** 临时下载的压缩包目录。 */
    val archives: File
        get() = File(root, "archives").apply { if (!exists()) mkdirs() }

    /** 解压后的图片目录。 */
    val extracted: File
        get() = File(root, "extracted").apply { if (!exists()) mkdirs() }

    /** 工作数据（封面、缩略图）目录。 */
    val covers: File
        get() = File(root, "covers").apply { if (!exists()) mkdirs() }

    fun archiveFile(comicId: Long, originalName: String? = null): File {
        val ext = originalName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
        val suffix = if (ext != null) ".$ext" else ".archive"
        return File(archives, "comic_$comicId$suffix")
    }
    fun extractedDir(comicId: Long): File = File(extracted, "comic_$comicId")
    fun coverFile(comicId: Long): File = File(covers, "comic_$comicId.jpg")

    /** 缓存总占用（字节），不包含封面。 */
    fun totalSizeBytes(): Long {
        var size = 0L
        if (archives.exists()) size += archives.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (extracted.exists()) size += extracted.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return size
    }

    /** 封面占用（字节）。 */
    fun coverSizeBytes(): Long = if (covers.exists()) covers.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    /** 清除所有封面文件。 */
    fun clearCovers() {
        if (covers.exists()) {
            covers.listFiles()?.forEach { it.delete() }
        }
    }
}
