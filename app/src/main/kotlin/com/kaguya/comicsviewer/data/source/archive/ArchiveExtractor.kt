package com.kaguya.comicsviewer.data.source.archive

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 压缩包图片提取器（仅支持 ZIP / CBZ，完全不支持 RAR / CBR）。
 *
 * 所有方法都要求传入一个**本地文件 File**（可被随机访问 / seek）。
 * 本地源通过 [com.kaguya.comicsviewer.data.repository.ComicRepository.resolveArchiveFile]
 * 取得真实路径直读文件或懒缓存副本；SMB 源使用已下载到本地的副本。
 * 因此本类不提供任何 InputStream 流模式实现。
 *
 * 解压主路径走 libarchive（对本地文件可 seek，随机访问单页，无需顺序重扫整包）；
 * 当 libarchive 对某本地 ZIP 列举/解压为空时，回退到 JDK [ZipFile]（对本地 ZIP 极可靠，
 * 支持子目录与中文路径）。
 */
@Singleton
class ArchiveExtractor @Inject constructor() {

    enum class ArchiveType { ZIP, UNKNOWN;
        /** 是否支持随机访问解压（仅 ZIP/CBZ）。 */
        val isZip: Boolean get() = this == ZIP
    }

    /** 仅识别 ZIP / CBZ；其余格式（含 RAR / CBR）一律返回 null（不支持）。 */
    fun detectType(fileName: String): ArchiveType? {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".zip") || lower.endsWith(".cbz") -> ArchiveType.ZIP
            else -> null
        }
    }

    /** 是否为可支持的压缩包（ZIP / CBZ）。 */
    val String.isZip: Boolean
        get() = detectType(this) == ArchiveType.ZIP

    // region 本地文件（File）读取

    /** 列举压缩包内所有图片条目（含子目录相对路径），按路径排序。 */
    suspend fun listImageEntries(archive: File): List<String> = withContext(Dispatchers.IO) {
        if (!archive.isFile) return@withContext emptyList()
        val libarchiveList = try {
            val list = mutableListOf<String>()
            open(archive) { ptr ->
                walkEntries(ptr) { entry ->
                    val path = ArchiveEntry.pathnameUtf8(entry) ?: return@walkEntries false
                    if (isImageName(path)) list.add(path)
                    false
                }
            }
            list
        } catch (e: Exception) {
            Log.w("ArchiveExtractor", "libarchive 列举失败，回退 JDK: ${e.message}")
            emptyList()
        }
        // libarchive 对部分本地 ZIP 可能列举为空，此时用 JDK ZipFile 兜底。
        if (libarchiveList.isEmpty()) {
            runCatching { listImageEntriesJdk(archive) }.getOrDefault(emptyList())
        } else {
            libarchiveList.sortedBy { it.lowercase() }
        }
    }

    private fun listImageEntriesJdk(archive: File): List<String> {
        ZipFile(archive).use { zf ->
            return zf.entries().toList()
                .filter { !it.isDirectory && isImageName(it.name) }
                .map { it.name }
                .sortedBy { it.lowercase() }
        }
    }

    /** 随机访问解压指定 entry 到目标文件（流式写入，适合大图）。 */
    suspend fun extractImageTo(archive: File, entryName: String, target: File): Boolean =
        withContext(Dispatchers.IO) {
            if (!archive.isFile) return@withContext false
            val ok = try {
                var found = false
                open(archive) { ptr ->
                    walkEntries(ptr) { entry ->
                        if (ArchiveEntry.pathnameUtf8(entry) == entryName) {
                            extractCurrentEntry(ptr, target)
                            found = true
                            true
                        } else {
                            false
                        }
                    }
                }
                found
            } catch (e: Exception) {
                Log.w("ArchiveExtractor", "libarchive 解压失败，回退 JDK: ${e.message}")
                false
            }
            if (ok) return@withContext true
            // JDK ZipFile 回退
            runCatching { extractImageToJdk(archive, entryName, target) }.getOrDefault(false)
        }

    private fun extractImageToJdk(archive: File, entryName: String, target: File): Boolean {
        ZipFile(archive).use { zf ->
            val ze = zf.getEntry(entryName) ?: return false
            zf.getInputStream(ze).use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out, 256 * 1024) }
            }
            return true
        }
    }

    /** 随机访问读取指定 entry 的原始字节（用于封面）。 */
    suspend fun readEntry(archive: File, entryName: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!archive.isFile) return@withContext null
        val bytes = try {
            var result: ByteArray? = null
            open(archive) { ptr ->
                walkEntries(ptr) { entry ->
                    if (ArchiveEntry.pathnameUtf8(entry) == entryName) {
                        result = readCurrentEntry(ptr)
                        true
                    } else {
                        false
                    }
                }
            }
            result
        } catch (e: Exception) {
            Log.w("ArchiveExtractor", "libarchive 读 entry 失败，回退 JDK: ${e.message}")
            null
        }
        bytes ?: runCatching { readEntryJdk(archive, entryName) }.getOrNull()
    }

    private fun readEntryJdk(archive: File, entryName: String): ByteArray? {
        ZipFile(archive).use { zf ->
            val ze = zf.getEntry(entryName) ?: return null
            zf.getInputStream(ze).use { return it.readBytes() }
        }
    }

    /** 读取压缩包封面（按图片名排序后的第一张）。 */
    suspend fun readCover(archive: File): ByteArray? = withContext(Dispatchers.IO) {
        if (!archive.isFile) return@withContext null
        val entries = listImageEntries(archive)
        val coverName = entries.firstOrNull() ?: return@withContext null
        readEntry(archive, coverName)
    }

    /** 读取本地 archive 文件的封面（带已知文件名）。 */
    suspend fun readCover(archive: File, fileName: String): ByteArray? {
        if (detectType(fileName) == null) return null
        return readCover(archive)
    }

    // endregion

    // region libarchive 遍历封装（仅本地 File，随机访问）

    private inline fun walkEntries(archivePtr: Long, onEntry: (Long) -> Boolean) {
        var entry: Long
        while (runCatching { Archive.readNextHeader(archivePtr) }
                .getOrNull()
                .also { entry = it ?: 0L } != 0L
        ) {
            if (ArchiveEntry.filetype(entry) and ArchiveEntry.AE_IFMT != ArchiveEntry.AE_IFREG) continue
            if (onEntry(entry)) break
        }
    }

    private inline fun open(archive: File, block: (Long) -> Unit) {
        val archivePtr = Archive.readNew()
        try {
            Archive.readSupportFormatAll(archivePtr)
            Archive.readSupportFilterAll(archivePtr)
            Archive.readOpenFileName(
                archivePtr,
                archive.absolutePath.toByteArray(),
                BLOCK_SIZE.toLong()
            )
            block(archivePtr)
        } catch (e: ArchiveException) {
            Log.e("ArchiveExtractor", "libarchive 读取失败: ${e.message}", e)
        } catch (e: IOException) {
            Log.e("ArchiveExtractor", "IO 错误: ${e.message}", e)
        } finally {
            try {
                Archive.free(archivePtr)
            } catch (_: Throwable) {
            }
        }
    }

    /** 将当前 entry 的数据流式写入文件（不整张载入内存，适合大图）。 */
    private fun extractCurrentEntry(archivePtr: Long, out: File) {
        FileOutputStream(out).use { fos ->
            val buffer = java.nio.ByteBuffer.allocateDirect(BLOCK_SIZE)
            while (true) {
                buffer.clear()
                Archive.readData(archivePtr, buffer)
                val read = buffer.position()
                if (read <= 0) break
                buffer.position(0)
                buffer.limit(read)
                val bytes = ByteArray(read)
                buffer.get(bytes)
                fos.write(bytes)
            }
        }
    }

    /** 将当前 entry 的数据读入内存。 */
    private fun readCurrentEntry(archivePtr: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = java.nio.ByteBuffer.allocateDirect(BLOCK_SIZE)
        while (true) {
            buffer.clear()
            Archive.readData(archivePtr, buffer)
            val read = buffer.position()
            if (read <= 0) break
            buffer.position(0)
            buffer.limit(read)
            val bytes = ByteArray(read)
            buffer.get(bytes)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    // endregion

    companion object {
        private const val BLOCK_SIZE = 256 * 1024

        private val IMAGE_EXT =
            setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "avif", "heic", "heif")

        fun isImageName(name: String): Boolean {
            val lower = name.lowercase()
            return IMAGE_EXT.any { lower.endsWith(".$it") }
        }
    }
}
