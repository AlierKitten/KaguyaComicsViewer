package com.kaguya.comicsviewer.data.source.archive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.kaguya.comicsviewer.data.source.archive.ArchiveExtractor.Companion.COVER_TARGET_SIZE
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

    /** 读取压缩包封面（按图片名排序后的第一张）。封面会降采样 + JPEG 压缩后再返回，缩小索引体积。 */
    suspend fun readCover(archive: File): ByteArray? = withContext(Dispatchers.IO) {
        if (!archive.isFile) return@withContext null
        val entries = listImageEntries(archive)
        val coverName = entries.firstOrNull() ?: return@withContext null
        readEntry(archive, coverName)?.let { downscaleCover(it) }
    }

    /** 读取本地 archive 文件的封面（带已知文件名）。 */
    suspend fun readCover(archive: File, fileName: String): ByteArray? {
        if (detectType(fileName) == null) return null
        return readCover(archive)
    }

    /**
     * 从输入流读取封面（按图片名排序后的第一张），**单次顺序扫描**命中第一张图即停止并读出。
     * 不要求随机访问，适合 SMB / SAF 等无法 seek 的流；且不会二次消费同一个已耗尽的流
     * （listImageEntries 与 readEntry 若分两次调用会各自从头扫，对不可重置的远程流会失效）。
     * 封面同样降采样 + JPEG 压缩。
     */
    suspend fun readCoverStream(input: InputStream, fileName: String): ByteArray? {
        if (detectType(fileName) == null) return null
        val coverBytes = readFirstImageEntry(input) ?: return null
        return downscaleCover(coverBytes)
    }

    /**
     * 从「可重开的流」读取封面（按图片名排序后的第一张），与 [readCover]（File 版）及阅读器
     * [listPages] 的封面选取保持一致（均是按路径排序后的首图）。
     *
     * 因 SAF/SMB 流不可 seek，无法一遍扫描同时「按名排序取首图 + 读其字节」，故分两遍：
     * 第一遍 [listImageEntries] 取排序后首名，第二遍 [readEntryStream] 读其字节。
     * [openStream] 在每遍各自重新打开原始压缩包流，避免消费已耗尽的流。
     */
    suspend fun readCoverStreamSorted(openStream: () -> InputStream?, fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        if (detectType(fileName) == null) return@withContext null
        val first = openStream()?.use { runCatching { listImageEntries(it) }.getOrElse { emptyList() } }?.firstOrNull()
            ?: return@withContext null
        val raw = runCatching { openStream()?.use { readEntryStream(it, first) } }.getOrNull() ?: return@withContext null
        return@withContext downscaleCover(raw)
    }

    /** 单次顺序扫描流，返回第一个图片 entry 的原始字节；命中即停止（不再继续扫后续条目）。 */
    private suspend fun readFirstImageEntry(input: InputStream): ByteArray? = withContext(Dispatchers.IO) {
        // 优先 libarchive 回调式流读取
        val libarchiveResult = try {
            var result: ByteArray? = null
            openFromStream(input) { ptr ->
                walkEntries(ptr) { entry ->
                    val path = ArchiveEntry.pathnameUtf8(entry) ?: return@walkEntries false
                    if (isImageName(path)) {
                        result = readCurrentEntry(ptr)
                        true // 命中第一张图即停止扫描
                    } else {
                        false
                    }
                }
            }
            result
        } catch (e: Exception) {
            Log.w("ArchiveExtractor", "libarchive 流读封面失败，回退 JDK: ${e.message}")
            null
        }
        if (libarchiveResult != null) return@withContext libarchiveResult
        // 回退 JDK ZipInputStream：同样只需一次扫描，命中首图即停
        runCatching { readFirstImageEntryJdkStream(input) }.getOrNull()
    }

    private fun readFirstImageEntryJdkStream(input: InputStream): ByteArray? {
        ZipInputStream(input).use { zis ->
            var ze = zis.nextEntry
            while (ze != null) {
                if (!ze.isDirectory && isImageName(ze.name)) {
                    return zis.readBytes()
                }
                ze = zis.nextEntry
            }
        }
        return null
    }

    /**
     * 封面降采样：先按 [inJustDecodeBounds] 探测原始尺寸，再以 [COVER_TARGET_SIZE] 为最长边
     * 计算 [BitmapFactory.Options.inSampleSize] 降采样，最后以 JPEG quality 80 压缩输出。
     * 直接返回原始 entry 字节会导致索引/列表加载大量原始大图（如 20MB 单图），故必须压缩。
     */
    internal fun downscaleCover(bytes: ByteArray): ByteArray? {
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val maxDim = maxOf(opts.outWidth, opts.outHeight).takeIf { it > 0 } ?: return@runCatching null
            val sample = if (maxDim > COVER_TARGET_SIZE) maxDim / COVER_TARGET_SIZE else 1
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return@runCatching null
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                bmp.recycle()
                out.toByteArray()
            }
        }.getOrNull()
    }

    /**
     * 与阅读器 [ReaderViewModel.generateThumbnail] 完全一致的封面缩略图生成：
     * 以 [maxWidth] 为最长边目标、取 2 的幂次 inSampleSize 降采样、JPEG quality=[quality] 输出。
     * 用于扫描阶段「特殊尝试」生成封面，确保与阅读器产物一致。
     */
    internal fun generateCoverThumbnail(bytes: ByteArray, maxWidth: Int = 300, quality: Int = 75): ByteArray? {
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            var sampleSize = 1
            if (options.outWidth > maxWidth) {
                sampleSize = (options.outWidth.toFloat() / maxWidth).toInt()
            }
            var power = 1
            while (power * 2 <= sampleSize) power *= 2
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = power }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return@runCatching null
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bmp.recycle()
                out.toByteArray()
            }
        }.getOrNull()
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
        // readNextHeader 返回 entry 指针（非 0 表示有效条目，0 表示 EOF）。
        // 关键：用 runCatching 包裹，因为遇到损坏条目时会抛 ArchiveException 而非返回 0。
        // 若直接写 `runCatching{...}.getOrNull() != 0L`，异常时 getOrNull() 为 null，
        // 而 `null != 0L` 在 Kotlin 中恒为 true，会带着 entry=0L 进入循环体并调用
        // ArchiveEntry.filetype(0) —— 触发原生 SIGSEGV（fault addr 0x430）。
        // 因此必须显式判断结果非 null 且非 0 才进入。
        var entry: Long
        while (true) {
            val result = runCatching { Archive.readNextHeader(archivePtr) }.getOrNull()
            if (result == null || result == 0L) break
            entry = result
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

        /** 封面最长边目标像素。 */
        private const val COVER_TARGET_SIZE = 480

        private val IMAGE_EXT =
            setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "avif", "heic", "heif")

        fun isImageName(name: String): Boolean {
            val lower = name.lowercase()
            return IMAGE_EXT.any { lower.endsWith(".$it") }
        }
    }
}
