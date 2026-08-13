package com.kaguya.comicsviewer.data.source

import android.content.Context
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.kaguya.comicsviewer.data.source.archive.ArchiveExtractor
import com.kaguya.comicsviewer.domain.model.ComicSource
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
    val sizeBytes: Long = 0
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
                if (extractor.detectType(name) == null) continue
                val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                out += DiscoveredComic(
                    title = name.substringBeforeLast('.'),
                    relativePath = relative,
                    absoluteUri = file.uri.toString()
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
        val treeUri = source.localUri ?: return@withContext null
        val root = DocumentFile.fromTreeUri(context, android.net.Uri.parse(treeUri)) ?: return@withContext null
        val parts = discovered.relativePath.split('/').filter { it.isNotBlank() }
        var cur: DocumentFile? = root
        for (p in parts) {
            cur = cur?.listFiles()?.firstOrNull { it.name == p } ?: return@withContext null
        }
        val file = cur ?: return@withContext null
        val fileName = discovered.relativePath.substringAfterLast('/')
        val input = context.contentResolver.openInputStream(file.uri) ?: return@withContext null
        val buf = java.io.BufferedInputStream(input, 256 * 1024)
        extractor.readCoverFromStream(buf, fileName)
    }
}

private object FileDescriptorCompat {
    fun length(context: Context, file: DocumentFile): Long {
        // 1. 最可靠：对外部存储 provider，直接提取文件路径用 java.io.File 获取大小
        val filePathLen = runCatching {
            val uri = file.uri
            if (uri.authority == "com.android.externalstorage.documents") {
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("primary:")) {
                    val path = docId.removePrefix("primary:")
                    java.io.File("/storage/emulated/0/$path").length()
                } else 0L
            } else 0L
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
