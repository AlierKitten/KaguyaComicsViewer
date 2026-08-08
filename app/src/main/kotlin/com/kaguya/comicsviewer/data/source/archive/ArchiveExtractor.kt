package com.kaguya.comicsviewer.data.source.archive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 漫画压缩包解压器，支持 ZIP/CBZ/RAR。
 *
 * - ZIP/CBZ：流式解压，按需写入磁盘
 * - RAR：使用 junrar 解压
 */
@Singleton
class ArchiveExtractor @Inject constructor() {

    /** 识别压缩包格式。 */
    fun detectType(name: String): ArchiveType? {
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.endsWith(".cbz") || n.endsWith(".zip") -> ArchiveType.ZIP
            n.endsWith(".cbr") || n.endsWith(".rar") -> ArchiveType.RAR
            else -> null
        }
    }

    /**
     * 列出压缩包内图片条目（按自然顺序）。
     * 用于：在下载/解压前估算页数；以及解压阶段建立索引。
     */
    suspend fun listImageEntries(archive: File): List<String> = withContext(Dispatchers.IO) {
        val type = detectType(archive.name) ?: return@withContext emptyList()
        val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic")
        when (type) {
            ArchiveType.ZIP -> {
                val names = mutableListOf<String>()
                archive.inputStream().use { input ->
                    BufferedInputStream(input).use { buffered ->
                        ZipArchiveInputStream(buffered).use { zip ->
                            while (true) {
                                val entry = zip.nextEntry ?: break
                                if (entry.isDirectory) continue
                                val name = entry.name.substringAfterLast('/')
                                if (imageExts.any { name.lowercase().endsWith(".$it") }) {
                                    names += entry.name
                                }
                            }
                        }
                    }
                }
                names
            }
            ArchiveType.RAR -> {
                val names = mutableListOf<String>()
                Archive(archive).use { a ->
                    for (header in a.fileHeaders) {
                        if (header.isDirectory) continue
                        val fn = header.getFileNameString()
                        val name = fn.substringAfterLast('/')
                        if (imageExts.any { name.lowercase().endsWith(".$it") }) {
                            names += fn
                        }
                    }
                }
                names
            }
        }.sortedWith(NATURAL_ORDER)
    }

    /**
     * 解压压缩包到目标目录，仅写入图片文件。
     * 返回写入的文件数。
     */
    suspend fun extractImages(archive: File, targetDir: File): Int = withContext(Dispatchers.IO) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val type = detectType(archive.name) ?: return@withContext 0
        val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic")
        when (type) {
            ArchiveType.ZIP -> {
                var count = 0
                archive.inputStream().use { input ->
                    BufferedInputStream(input).use { buffered ->
                        ZipArchiveInputStream(buffered).use { zip ->
                            while (true) {
                                val entry: ArchiveEntry = zip.nextEntry ?: break
                                if (entry.isDirectory) continue
                                val baseName = entry.name.substringAfterLast('/')
                                if (baseName.isBlank()) continue
                                if (imageExts.none { baseName.lowercase().endsWith(".$it") }) continue
                                val outFile = safeOutputFile(targetDir, baseName)
                                outFile.outputStream().use { out -> zip.copyTo(out) }
                                count++
                            }
                        }
                    }
                }
                count
            }
            ArchiveType.RAR -> {
                var count = 0
                Archive(archive).use { a ->
                    for (header in a.fileHeaders as List<FileHeader>) {
                        if (header.isDirectory) continue
                        val baseName = header.getFileNameString().substringAfterLast('/')
                        if (baseName.isBlank()) continue
                        if (imageExts.none { baseName.lowercase().endsWith(".$it") }) continue
                        val outFile = safeOutputFile(targetDir, baseName)
                        FileOutputStream(outFile).use { out -> a.extractFile(header, out) }
                        count++
                    }
                }
                count
            }
        }
    }

    /**
     * 解压单个文件到磁盘外的输出流。
     */
    suspend fun extractImage(archive: File, entryName: String, out: File): Boolean =
        withContext(Dispatchers.IO) {
            val type = detectType(archive.name) ?: return@withContext false
            when (type) {
                ArchiveType.ZIP -> {
                    archive.inputStream().use { input ->
                        BufferedInputStream(input).use { buffered ->
                            ZipArchiveInputStream(buffered).use { zip ->
                                while (true) {
                                    val entry = zip.nextEntry ?: return@withContext false
                                    if (entry.isDirectory) continue
                                    if (entry.name == entryName || entry.name.endsWith("/$entryName")) {
                                        out.outputStream().use { o -> zip.copyTo(o) }
                                        return@withContext true
                                    }
                                }
                            }
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }
                ArchiveType.RAR -> {
                    Archive(archive).use { a ->
                        for (header in a.fileHeaders as List<FileHeader>) {
                            if (!header.isDirectory && header.getFileNameString() == entryName) {
                                java.io.FileOutputStream(out).use { fos -> a.extractFile(header, fos) }
                                return@withContext true
                            }
                        }
                    }
                    false
                }
            }
        }

    /** 读取第一张图片作为封面（流式解码 + 缩放，避免 OOM）。 */
    suspend fun readCover(archive: File): ByteArray? = withContext(Dispatchers.IO) {
        decodeCoverBitmap(archive, archive.name)
    }

    /** 读取第一张图片作为封面。[fileName] 用于检测压缩包类型（当 [archive] 是临时文件时扩展名可能不正确）。 */
    suspend fun readCover(archive: File, fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        decodeCoverBitmap(archive, fileName)
    }

    suspend fun readEntry(archive: File, entryName: String): ByteArray? = withContext(Dispatchers.IO) {
        readEntryInternal(archive, entryName, archive.name)
    }

    private suspend fun listImageEntriesInternal(archive: File, fileName: String): List<String> = withContext(Dispatchers.IO) {
        val type = detectType(fileName) ?: return@withContext emptyList()
        val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic")
        when (type) {
            ArchiveType.ZIP -> {
                val names = mutableListOf<String>()
                archive.inputStream().use { input ->
                    BufferedInputStream(input).use { buffered ->
                        ZipArchiveInputStream(buffered).use { zip ->
                            while (true) {
                                val entry = zip.nextEntry ?: break
                                if (entry.isDirectory) continue
                                val name = entry.name.substringAfterLast('/')
                                if (imageExts.any { name.lowercase().endsWith(".$it") }) {
                                    names += entry.name
                                }
                            }
                        }
                    }
                }
                names
            }
            ArchiveType.RAR -> {
                val names = mutableListOf<String>()
                Archive(archive).use { a ->
                    for (header in a.fileHeaders) {
                        if (header.isDirectory) continue
                        val fn = header.getFileNameString()
                        val name = fn.substringAfterLast('/')
                        if (imageExts.any { name.lowercase().endsWith(".$it") }) {
                            names += fn
                        }
                    }
                }
                names
            }
        }.sortedWith(NATURAL_ORDER)
    }

    private suspend fun readEntryInternal(archive: File, entryName: String, fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val type = detectType(fileName) ?: return@withContext null
        when (type) {
            ArchiveType.ZIP -> {
                archive.inputStream().use { input ->
                    BufferedInputStream(input).use { buffered ->
                        ZipArchiveInputStream(buffered).use { zip ->
                            while (true) {
                                val entry = zip.nextEntry ?: return@withContext null
                                if (entry.isDirectory) continue
                                if (entry.name == entryName || entry.name.endsWith("/$entryName")) {
                                    return@withContext zip.readBytes()
                                }
                            }
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
            ArchiveType.RAR -> {
                var result: ByteArray? = null
                Archive(archive).use { a ->
                    for (header in a.fileHeaders as List<FileHeader>) {
                        if (!header.isDirectory && header.getFileNameString() == entryName) {
                            result = a.getInputStream(header).use { it.readBytes() }
                            break
                        }
                    }
                }
                result
            }
        }
    }

    /**
     * 流式解码封面：将第一张图片条目写入临时文件，再用 BitmapFactory + inSampleSize 解码，
     * 最后压缩为 JPEG。避免将整张原图加载到内存。
     */
    private fun decodeCoverBitmap(archive: File, fileName: String): ByteArray? {
        val type = detectType(fileName) ?: return null
        val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic")
        val tempImg = File.createTempFile("cover_img_", ".tmp")
        try {
            // 将第一张图片条目写入临时文件
            val found = when (type) {
                ArchiveType.ZIP -> {
                    var found = false
                    archive.inputStream().use { input ->
                        BufferedInputStream(input, 256 * 1024).use { buffered ->
                            ZipArchiveInputStream(buffered).use { zip ->
                                while (!found) {
                                    val entry = zip.nextEntry ?: break
                                    if (entry.isDirectory) continue
                                    val name = entry.name.substringAfterLast('/')
                                    if (imageExts.any { name.lowercase().endsWith(".$it") }) {
                                        FileOutputStream(tempImg).use { out -> zip.copyTo(out) }
                                        found = true
                                    }
                                }
                            }
                        }
                    }
                    found
                }
                ArchiveType.RAR -> {
                    var ok = false
                    Archive(archive).use { a ->
                        for (header in a.fileHeaders as List<FileHeader>) {
                            if (header.isDirectory) continue
                            val fn = header.getFileNameString()
                            val name = fn.substringAfterLast('/')
                            if (imageExts.any { name.lowercase().endsWith(".$it") }) {
                                FileOutputStream(tempImg).use { out -> a.extractFile(header, out) }
                                ok = true
                                break
                            }
                        }
                    }
                    ok
                }
            }
            if (!found) return null

            // 先解码边界
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tempImg.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

            // 计算 inSampleSize，目标最长边 480px
            val targetSize = 480
            val maxDim = maxOf(opts.outWidth, opts.outHeight)
            opts.inSampleSize = if (maxDim > targetSize) maxDim / targetSize else 1
            opts.inJustDecodeBounds = false

            val bitmap = BitmapFactory.decodeFile(tempImg.absolutePath, opts) ?: return null

            // 压缩为 JPEG
            val baos = ByteArrayOutputStream(64 * 1024)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            bitmap.recycle()
            return baos.toByteArray()
        } catch (e: Exception) {
            return null
        } finally {
            tempImg.delete()
        }
    }

    private fun safeOutputFile(dir: File, name: String): File {
        val safe = name.replace("..", "_").replace("/", "_").replace("\\", "_")
        return File(dir, safe)
    }

    private fun InputStream.copyTo(out: java.io.OutputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
    }

    companion object {
        private val NATURAL_ORDER: Comparator<String> = Comparator { a, b ->
            val ia = a.naturalKey()
            val ib = b.naturalKey()
            compareNatural(ia, ib)
        }

        private fun String.naturalKey(): List<Any> {
            val parts = mutableListOf<Any>()
            var i = 0
            while (i < length) {
                val c = this[i]
                if (c.isDigit()) {
                    var j = i
                    while (j < length && this[j].isDigit()) j++
                    parts += this.substring(i, j).toLongOrNull() ?: 0L
                    i = j
                } else {
                    var j = i
                    while (j < length && !this[j].isDigit()) j++
                    parts += this.substring(i, j).lowercase()
                    i = j
                }
            }
            return parts
        }

        private fun compareNatural(a: List<Any>, b: List<Any>): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val x = a[i]; val y = b[i]
                val cmp = when {
                    x is Long && y is Long -> x.compareTo(y)
                    else -> (x.toString()).compareTo(y.toString())
                }
                if (cmp != 0) return cmp
            }
            return a.size.compareTo(b.size)
        }
    }
}

enum class ArchiveType { ZIP, RAR }
