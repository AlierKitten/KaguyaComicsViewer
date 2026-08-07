package com.kaguya.comicsviewer.data.source

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.kaguya.comicsviewer.data.source.archive.ArchiveExtractor
import com.kaguya.comicsviewer.domain.model.ComicSource
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val sizeBytes: Long
)

/** 扫描器抽象。 */
interface ComicScanner {
    /** 扫描一个源，得到所有发现的漫画。 */
    suspend fun scan(source: ComicSource): List<DiscoveredComic>

    /** 计算缩略图（封面）字节。 */
    suspend fun cover(source: ComicSource, discovered: DiscoveredComic): ByteArray?
}

/** 本地文件源扫描器（基于 SAF DocumentFile）。 */
@Singleton
class LocalFileScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val extractor: ArchiveExtractor
) : ComicScanner {

    override suspend fun scan(source: ComicSource): List<DiscoveredComic> = withContext(Dispatchers.IO) {
        val treeUri = source.localUri ?: return@withContext emptyList()
        val root = DocumentFile.fromTreeUri(context, android.net.Uri.parse(treeUri)) ?: return@withContext emptyList()
        val results = mutableListOf<DiscoveredComic>()
        walk(root, source.name, "", results)
        results
    }

    private fun walk(dir: DocumentFile, sourceName: String, prefix: String, out: MutableList<DiscoveredComic>) {
        for (file in dir.listFiles()) {
            val name = file.name ?: continue
            if (file.isDirectory) {
                walk(file, sourceName, if (prefix.isEmpty()) name else "$prefix/$name", out)
            } else {
                if (extractor.detectType(name) == null) continue
                val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                val size = runCatching { FileDescriptorCompat.length(file) }.getOrDefault(0L)
                out += DiscoveredComic(
                    title = name.substringBeforeLast('.'),
                    relativePath = relative,
                    absoluteUri = file.uri.toString(),
                    sizeBytes = size
                )
            }
        }
    }

    override suspend fun cover(source: ComicSource, discovered: DiscoveredComic): ByteArray? = withContext(Dispatchers.IO) {
        // 封面由 Worker 在解压阶段写入，扫描阶段返回 null。
        null
    }
}

private object FileDescriptorCompat {
    fun length(file: DocumentFile): Long = file.length()
}
