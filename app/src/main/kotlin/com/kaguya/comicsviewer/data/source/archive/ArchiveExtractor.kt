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
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 libarchive（原生 so）的压缩包读取器。
 * libarchive 用 C 实现，支持 ZIP / CBZ / RAR / RAR5 / 7z 等格式，
 */
@Singleton
@Suppress("unused")
class ArchiveExtractor @Inject constructor() {

    enum class ArchiveType { ZIP, RAR;
        val isZip get() = this == ZIP
        val isRar get() = this == RAR
    }

    // region 公共接口（对外签名保持不变）

    fun detectType(name: String): ArchiveType? = when {
        name.endsWith(".cbz", true) || name.endsWith(".zip", true) -> ArchiveType.ZIP
        name.endsWith(".cbr", true) || name.endsWith(".rar", true) -> ArchiveType.RAR
        else -> null
    }

    /** 列出压缩包中所有图片条目（按文件名），用于"下载即 READY"的 ZIP 直接读取路径。 */
    fun listImageEntries(archive: File): List<String> {
        val result = mutableListOf<String>()
        open(archive) { archivePtr ->
            walkEntries(archivePtr) { entry ->
                val path = ArchiveEntry.pathnameUtf8(entry) ?: return@walkEntries false
                if (isImageName(path)) result.add(path)
                false
            }
        }
        return result
    }

    /**
     * 将单个图片条目解压到 outFile（流式写，避免整张图片载入内存）。
     */
    fun extractImageTo(archive: File, entryName: String, outFile: File): Boolean {
        var extracted = false
        open(archive) { archivePtr ->
            walkEntries(archivePtr) { entry ->
                val path = ArchiveEntry.pathnameUtf8(entry) ?: return@walkEntries false
                if (path == entryName) {
                    extractCurrentEntry(archivePtr, outFile)
                    extracted = true
                    true
                } else {
                    false
                }
            }
        }
        return extracted
    }

    /**
     * 将整个压缩包（仅图片条目）解压到 target 目录。
     * 目前 RAR 整包下载后走此路径；ZIP 已改为按需读取，不再调用。
     * @return 解压出的图片数量
     */
    fun extractImages(archive: File, target: File): Int {
        var count = 0
        open(archive) { archivePtr ->
            walkEntries(archivePtr) { entry ->
                val path = ArchiveEntry.pathnameUtf8(entry) ?: return@walkEntries false
                if (!isImageName(path)) return@walkEntries false
                val name = path.substringAfterLast('/').substringAfterLast('\\')
                extractCurrentEntry(archivePtr, File(target, name))
                count++
                false
            }
        }
        Log.d("ArchiveExtractor", "解压完成，共 $count 张图片到 ${target.absolutePath}")
        return count
    }

    /** 读取单个图片条目到内存（用于首图/封面场景）。 */
    fun readEntry(archive: File, entryName: String): ByteArray? {
        var bytes: ByteArray? = null
        open(archive) { archivePtr ->
            walkEntries(archivePtr) { entry ->
                val path = ArchiveEntry.pathnameUtf8(entry) ?: return@walkEntries false
                if (path == entryName) {
                    bytes = readCurrentEntry(archivePtr)
                    true
                } else {
                    false
                }
            }
        }
        return bytes
    }

    // endregion

    // region 封面（通过本地文件或任意 InputStream 读取）

    /** 读取本地 archive 文件的封面（解压第一张图片的原始字节）。 */
    suspend fun readCover(archive: File): ByteArray? = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("cover_", ".tmp")
        try {
            if (!extractFirstImage(archive, temp)) return@withContext null
            temp.readBytes()
        } finally {
            temp.delete()
        }
    }

    /** 读取本地 archive 文件的封面（带已知文件名）。 */
    suspend fun readCover(archive: File, fileName: String): ByteArray? {
        if (detectType(fileName) == null) return null
        return readCover(archive)
    }

    /**
     * 从任意 InputStream（SAF / SMB 等远程或 ContentProvider 源）读取封面原始字节。
     * 使用 libarchive 的回调式 readOpen 直接从流解压，无需本地文件路径。
     */
    suspend fun readCoverFromStream(
        input: InputStream,
        fileName: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (detectType(fileName) == null) return@withContext null
        val temp = File.createTempFile("cover_", ".tmp")
        try {
            val ok = openFromStream(input) { archivePtr ->
                extractFirstImageFromOpen(archivePtr, temp)
            }
            if (!ok) return@withContext null
            temp.readBytes()
        } finally {
            temp.delete()
        }
    }

    // endregion

    // region libarchive 遍历封装

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
        openFromStream(null, archive, block)
    }

    private inline fun openFromStream(
        stream: InputStream?,
        archiveFile: File? = null,
        block: (Long) -> Unit
    ): Boolean {
        val archivePtr = Archive.readNew()
        try {
            Archive.readSupportFormatAll(archivePtr)
            Archive.readSupportFilterAll(archivePtr)
            if (stream != null) {
                readOpenStream(archivePtr, stream)
            } else if (archiveFile != null) {
                Archive.readOpenFileName(
                    archivePtr,
                    archiveFile.absolutePath.toByteArray(),
                    BLOCK_SIZE.toLong()
                )
            } else {
                return false
            }
            block(archivePtr)
            return true
        } catch (e: ArchiveException) {
            Log.w("ArchiveExtractor", "libarchive 读取失败: ${e.message}")
            return false
        } catch (e: IOException) {
            Log.w("ArchiveExtractor", "IO 错误: ${e.message}")
            return false
        } finally {
            try {
                Archive.free(archivePtr)
            } catch (_: Throwable) {
            }
        }
    }

    private fun readOpenStream(archivePtr: Long, stream: InputStream) {
        val buffer = java.nio.ByteBuffer.allocateDirect(BLOCK_SIZE)
        val tmp = ByteArray(BLOCK_SIZE)
        val readCallback = object : Archive.ReadCallback<Any?> {
            override fun onRead(archive: Long, clientData: Any?): java.nio.ByteBuffer? {
                buffer.clear()
                val read = try {
                    stream.read(tmp, 0, BLOCK_SIZE)
                } catch (e: IOException) {
                    Log.w("ArchiveExtractor", "流读取失败: ${e.message}")
                    -1
                }
                if (read <= 0) return null
                buffer.put(tmp, 0, read)
                buffer.flip()
                return buffer
            }
        }
        val closeCallback = object : Archive.CloseCallback<Any?> {
            override fun onClose(archive: Long, clientData: Any?) {
                try {
                    stream.close()
                } catch (_: Throwable) {
                }
            }
        }
        Archive.readOpen<Any?>(archivePtr, null, null, readCallback, closeCallback)
    }

    private fun extractFirstImage(archive: File, out: File): Boolean {
        var ok = false
        open(archive) { archivePtr ->
            ok = extractFirstImageFromOpen(archivePtr, out)
        }
        return ok
    }

    private fun extractFirstImageFromOpen(archivePtr: Long, out: File): Boolean {
        var found = false
        walkEntries(archivePtr) { entry ->
            val path = ArchiveEntry.pathnameUtf8(entry) ?: return@walkEntries false
            if (!isImageName(path)) return@walkEntries false
            extractCurrentEntry(archivePtr, out)
            found = true
            true
        }
        return found
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
