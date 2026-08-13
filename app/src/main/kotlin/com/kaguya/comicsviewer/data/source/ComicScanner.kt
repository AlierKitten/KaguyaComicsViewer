package com.kaguya.comicsviewer.data.source

import android.content.Context
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.kaguya.comicsviewer.data.source.archive.ArchiveExtractor
import com.kaguya.comicsviewer.domain.model.ComicSource
import com.kaguya.comicsviewer.util.FileDescriptorCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 扫描源得到的发现结果。
 */
data class DiscoveredComic(
    val title: String,
    val relativePath: String,
    val absoluteUri: String,
    val sizeBytes: Long = 0,
    /**
     * 本地源：若压缩包位于外部存储，提取出的真实文件系统路径（如 /storage/emulated/0/...）。
     * 非空时可直接读取原始压缩包，无需复制一份到缓存目录。
     */
    val localFilePath: String? = null,
    /** 压缩包类型是否为 ZIP/CBZ（true=可直接按需解压，false=RAR 需整包解压）。 */
    val isZip: Boolean = false
)

/** 扫描器抽象。 */
interface ComicScanner {
    /**
     * 快速扫描一个源，只返回漫画名和路径（不含文件大小）。
     * @param shouldCancel 在扫描过程中周期性调用，返回 true 时扫描应中止并抛出 CancellationException。
     */
    suspend fun scan(source: ComicSource, shouldCancel: () -> Boolean = { false }): List<DiscoveredComic>

    /** 批量获取文件大小，返回 relativePath → sizeBytes 映射。 */
    suspend fun fetchSizes(source: ComicSource, comics: List<DiscoveredComic>): Map<String, Long>

    /** 计算缩略图（封面）字节。 */
    suspend fun cover(source: ComicSource, discovered: DiscoveredComic): ByteArray?
}

/** 本地文件源扫描器（基于 SAF DocumentFile）。 */
@Singleton
class LocalFileScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val extractor: ArchiveExtractor
) : ComicScanner {

    override suspend fun scan(source: ComicSource, shouldCancel: () -> Boolean): List<DiscoveredComic> = withContext(Dispatchers.IO) {
        val treeUri = source.localUri ?: return@withContext emptyList()
        val root = DocumentFile.fromTreeUri(context, android.net.Uri.parse(treeUri)) ?: return@withContext emptyList()
        val results = mutableListOf<DiscoveredComic>()
        walk(root, "", results, shouldCancel)
        results
    }

    private fun walk(dir: DocumentFile, prefix: String, out: MutableList<DiscoveredComic>, shouldCancel: () -> Boolean) {
        if (shouldCancel()) throw CancellationException("scan cancelled")
        for (file in dir.listFiles()) {
            val name = file.name ?: continue
            if (file.isDirectory) {
                walk(file, if (prefix.isEmpty()) name else "$prefix/$name", out, shouldCancel)
            } else {
                val lower = name.lowercase()
                // 仅收集压缩包（ZIP/CBZ/RAR/CBR）；RAR/CBR 不在本软件支持范围内，
                // 仍收集以便 ScanSourceUseCase 统计并提示用户跳过了多少个。
                if (!lower.endsWith(".zip") && !lower.endsWith(".cbz") &&
                    !lower.endsWith(".rar") && !lower.endsWith(".cbr")
                ) continue
                val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                out += DiscoveredComic(
                    title = name.substringBeforeLast('.'),
                    relativePath = relative,
                    absoluteUri = file.uri.toString(),
                    localFilePath = FileDescriptorCompat.path(file),
                    isZip = extractor.detectType(name)?.isZip == true
                )
            }
        }
    }

    override suspend fun fetchSizes(source: ComicSource, comics: List<DiscoveredComic>): Map<String, Long> = withContext(Dispatchers.IO) {
        val treeUri = source.localUri ?: return@withContext emptyMap()
        val root = DocumentFile.fromTreeUri(context, android.net.Uri.parse(treeUri)) ?: return@withContext emptyMap()
        val result = mutableMapOf<String, Long>()
        for (comic in comics) {
            val parts = comic.relativePath.split('/').filter { it.isNotBlank() }
            var cur: DocumentFile? = root
            for (p in parts) {
                cur = cur?.listFiles()?.firstOrNull { it.name == p } ?: break
            }
            val file = cur
            if (file != null && file.isFile) {
                result[comic.relativePath] = FileDescriptorCompat.length(context, file)
            }
        }
        result
    }

    override suspend fun cover(source: ComicSource, discovered: DiscoveredComic): ByteArray? = withContext(Dispatchers.IO) {
        // 优先：能取到真实本地文件路径时直接读文件，更快更可靠
        val localPath = discovered.localFilePath
        if (localPath != null) {
            val f = java.io.File(localPath)
            if (f.isFile) {
                extractor.readCover(f)?.let { return@withContext it }
            }
        }
        // 回退：通过 SAF 流读取，先复制到临时文件再用 libarchive 随机访问
        val treeUri = source.localUri ?: return@withContext null
        val root = DocumentFile.fromTreeUri(context, android.net.Uri.parse(treeUri)) ?: return@withContext null
        val parts = discovered.relativePath.split('/').filter { it.isNotBlank() }
        var cur: DocumentFile? = root
        for (p in parts) {
            cur = cur?.listFiles()?.firstOrNull { it.name == p } ?: return@withContext null
        }
        val file = cur ?: return@withContext null
        val fileName = discovered.relativePath.substringAfterLast('/')
        val tmp = java.io.File.createTempFile("local_cover_", "_$fileName")
        return@withContext try {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                java.io.FileOutputStream(tmp).use { out -> input.copyTo(out, 256 * 1024) }
            }
            extractor.readCover(tmp)
        } catch (e: Exception) {
            Log.w("ComicScanner", "本地源封面回退失败: ${e.message}")
            null
        } finally {
            runCatching { tmp.delete() }
        }
    }
}

private object FileDescriptorCompat {
    /**
     * 提取外部存储上文档的真实文件系统路径，如 /storage/emulated/0/...。
     * 仅对 com.android.externalstorage.documents 且 docId 以 "primary:" 开头的文档有效，
     * 其它情况返回 null（需走 SAF 流）。
     */
    fun path(file: DocumentFile): String? {
        val uri = file.uri
        if (uri.authority != "com.android.externalstorage.documents") {
            Log.d("FileDescriptorCompat", "path: unsupported authority=${uri.authority}")
            return null
        }
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        if (docId == null) {
            Log.d("FileDescriptorCompat", "path: getDocumentId failed, uri=$uri")
            return null
        }
        // docId 形如 "primary:<relative>" 或 "<uuid>:<relative>"
        val (volume, relative) = docId.split(":", limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
        val base = when {
            volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
            else -> "/storage/$volume"
        }
        val full = "$base/$relative".replace("//", "/")
        Log.d("FileDescriptorCompat", "path: docId='$docId' -> '$full'")
        // 验证文件确实可读，否则回退到 SAF 流（复制）路径
        return if (java.io.File(full).canRead()) {
            Log.d("FileDescriptorCompat", "path: verified readable: '$full'")
            full
        } else {
            Log.w("FileDescriptorCompat", "path: file not readable, fallback to copy: '$full'")
            null
        }
    }

    fun length(context: Context, file: DocumentFile): Long {
        // 1. 最可靠：对外部存储 provider，直接提取文件路径用 java.io.File 获取大小
        val filePathLen = runCatching {
            val p = path(file)
            if (p != null) java.io.File(p).length() else 0L
        }.getOrDefault(0L)
        if (filePathLen > 0) return filePathLen
        // 2. 尝试 DocumentFile.length()
        val docLen = runCatching { file.length() }.getOrDefault(0L)
        if (docLen > 0) return docLen
        // 3. 回退：使用 ContentResolver 查询 OpenableColumns.SIZE
        val resolverSize = runCatching {
            context.contentResolver.query(
                file.uri,
                arrayOf(OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) cursor.getLong(idx) else 0L
                } else 0L
            } ?: 0L
        }.getOrDefault(0L)
        if (resolverSize > 0) return resolverSize
        // 4. 回退：通过 InputStream 读取字节计数
        return runCatching {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                var n: Int
                while (input.read(buf).also { n = it } > 0) {
                    total += n
                }
                total
            } ?: 0L
        }.getOrDefault(0L)
    }
}

