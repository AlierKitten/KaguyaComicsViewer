package com.kaguya.comicsviewer.data.source.archive

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 压缩包图片提取器（支持 ZIP / CBZ；本地源额外支持 InputStream 流直读）。
 *
 * 本地文件模式：所有方法传入本地 [File]（可随机访问 / seek），用于 SMB 下载副本与本地源可直读路径。
 * 解压主路径走 libarchive（可 seek，随机访问单页）；libarchive 列举为空时回退 JDK [ZipFile]。
 *
 * 流模式（InputStream）：本地源无存储权限时，经由 SAF `ContentResolver` 输入流顺序读取原始压缩包，
 * 不复制压缩包本身（仅解压单页到页缓存）。ZIP 用 libarchive 回调式读取，失败回退 JDK [ZipInputStream]。
 * 顺序扫描对超大压缩包/大图友好，不会把整个压缩包读入内存。
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

    // region InputStream（SAF 流）读取 —— 本地源无存储权限时直读原始压缩包

    /**
     * 从输入流列举压缩包内所有图片条目（含子目录相对路径），按路径排序。
     * 适用于本地源 SAF [InputStream]（无法随机访问，只能顺序扫描）。
     * ZIP/CBZ 用 libarchive 回调式流读取，失败回退 JDK [ZipInputStream]（对中文路径/子目录可靠）。
     */
    suspend fun listImageEntries(input: InputStream): List<String> = withContext(Dispatchers.IO) {
        val libarchiveList = try {
            val list = mutableListOf<String>()
            openFromStream(input) { ptr ->
                walkEntries(ptr) { entry ->
                    val path = ArchiveEntry.pathnameUtf8(entry) ?: return@walkEntries false
                    if (isImageName(path)) list.add(path)
                    false
                }
            }
            list
        } catch (e: Exception) {
            Log.w("ArchiveExtractor", "libarchive 流列举失败，回退 JDK: ${e.message}")
            emptyList()
        }
        if (libarchiveList.isNotEmpty()) {
            libarchiveList.sortedBy { it.lowercase() }
        } else {
            runCatching { listImageEntriesJdkStream(input) }.getOrDefault(emptyList())
        }
    }

    /** 从流随机解压指定 entry 到目标文件（顺序扫描匹配 entryName）。 */
    suspend fun extractImageToStream(
        input: InputStream, entryName: String, target: File
    ): Boolean = withContext(Dispatchers.IO) {
        val ok = try {
            var found = false
            openFromStream(input) { ptr ->
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
            Log.w("ArchiveExtractor", "libarchive 流解压失败，回退 JDK: ${e.message}")
            false
        }
        if (ok) return@withContext true
        runCatching { extractImageToJdkStream(input, entryName, target) }.getOrDefault(false)
    }

    /**
     * 单次顺序扫描整个压缩包，把 [requests] 中所有尚未落盘的页一次性解压到各自目标文件。
     * 相比每页单独开流重扫全包，本方法只打开一次 SAF 流、只扫一遍，极大减少超大压缩包下
     * 的并发流数量与取消风暴（Compose 同时展示多页时不再 N 倍扫描）。
     *
     * [requests] 为 (entryName, target)，仅当 target 不存在或为空时才解压；返回成功解压的 entry 名集合。
     */
    suspend fun extractImagesToStream(
        input: InputStream,
        requests: List<Pair<String, File>>
    ): Set<String> = withContext(Dispatchers.IO) {
        val pending = requests.filter { (_, target) -> !(target.exists() && target.length() > 0) }
            .toMutableList()
        if (pending.isEmpty()) return@withContext requests.map { it.first }.toSet()

        val ok = mutableSetOf<String>()
        val wanted = pending.map { it.first }.toSet()
        try {
            openFromStream(input) { ptr ->
                walkEntries(ptr) { entry ->
                    // 扫描循环为同步 native 调用，无挂起点；主动检查取消，确保协程被取消时
                    // 能立即中断扫描（而非一直读到包尾），尽快让 finally 释放 native 资源与流。
                    coroutineContext.ensureActive()
                    val name = ArchiveEntry.pathnameUtf8(entry) ?: return@walkEntries false
                    if (name in wanted) {
                        val idx = pending.indexOfFirst { it.first == name }
                        if (idx >= 0) {
                            val target = pending[idx].second
                            extractCurrentEntry(ptr, target)
                            ok.add(name)
                            pending.removeAt(idx)
                            if (pending.isEmpty()) return@walkEntries true // 全部解完，提前停止
                        }
                    }
                    false
                }
            }
        } catch (e: Exception) {
            Log.w("ArchiveExtractor", "libarchive 批量流解压失败，部分回退 JDK: ${e.message}")
        }
        // 仍缺失的页用 JDK ZipInputStream 逐条回退（每条重新从头扫，仅命中缺失页）
        val missing = pending.filter { (name, _) -> name !in ok }
        for ((name, target) in missing) {
            if (runCatching { extractImageToJdkStream(input, name, target) }.getOrDefault(false)) {
                ok.add(name)
            }
        }
        ok
    }

    /** 从流读取指定 entry 的原始字节（用于封面）。 */
    suspend fun readEntryStream(input: InputStream, entryName: String): ByteArray? = withContext(Dispatchers.IO) {
        val bytes = try {
            var result: ByteArray? = null
            openFromStream(input) { ptr ->
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
            Log.w("ArchiveExtractor", "libarchive 流读 entry 失败，回退 JDK: ${e.message}")
            null
        }
        bytes ?: runCatching { readEntryJdkStream(input, entryName) }.getOrNull()
    }

    private fun listImageEntriesJdkStream(input: InputStream): List<String> {
        ZipInputStream(input).use { zis ->
            val list = mutableListOf<String>()
            var ze = zis.nextEntry
            while (ze != null) {
                if (!ze.isDirectory && isImageName(ze.name)) list.add(ze.name)
                ze = zis.nextEntry
            }
            return list.sortedBy { it.lowercase() }
        }
    }

    private fun extractImageToJdkStream(input: InputStream, entryName: String, target: File): Boolean {
        ZipInputStream(input).use { zis ->
            var ze = zis.nextEntry
            while (ze != null) {
                if (ze.name == entryName && !ze.isDirectory) {
                    FileOutputStream(target).use { out -> zis.copyTo(out, 256 * 1024) }
                    return true
                }
                ze = zis.nextEntry
            }
        }
        return false
    }

    private fun readEntryJdkStream(input: InputStream, entryName: String): ByteArray? {
        ZipInputStream(input).use { zis ->
            var ze = zis.nextEntry
            while (ze != null) {
                if (ze.name == entryName && !ze.isDirectory) {
                    return zis.readBytes()
                }
                ze = zis.nextEntry
            }
        }
        return null
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

    /** 从 InputStream 打开 libarchive（回调式，无需文件 seek，适合 SAF 流）。 */
    private inline fun openFromStream(input: InputStream, block: (Long) -> Unit) {
        val archivePtr = Archive.readNew()
        try {
            Archive.readSupportFormatAll(archivePtr)
            Archive.readSupportFilterAll(archivePtr)
            val buf = ByteArray(BLOCK_SIZE)
            // onRead 返回包含数据的 ByteBuffer；返回 null 表示 EOF。
            // 必须返回 direct ByteBuffer，且不能复用同一 buffer 实例（libarchive 可能异步持有）。
            val readCallback = object : Archive.ReadCallback<InputStream> {
                override fun onRead(archive: Long, clientData: InputStream): java.nio.ByteBuffer? {
                    val n = clientData.read(buf, 0, BLOCK_SIZE)
                    if (n <= 0) return null
                    val direct = java.nio.ByteBuffer.allocateDirect(n)
                    direct.put(buf, 0, n)
                    direct.flip()
                    return direct
                }
            }
            val openCallback = object : Archive.OpenCallback<InputStream> {
                override fun onOpen(archive: Long, clientData: InputStream) = Unit
            }
            val closeCallback = object : Archive.CloseCallback<InputStream> {
                override fun onClose(archive: Long, clientData: InputStream) {
                    runCatching { clientData.close() }
                }
            }
            Archive.readOpen(archivePtr, input, openCallback, readCallback, closeCallback)
            block(archivePtr)
        } catch (e: ArchiveException) {
            Log.e("ArchiveExtractor", "libarchive 流读取失败: ${e.message}", e)
        } catch (e: IOException) {
            Log.e("ArchiveExtractor", "IO 错误: ${e.message}", e)
        } finally {
            try {
                Archive.free(archivePtr)
            } catch (_: Throwable) {
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
