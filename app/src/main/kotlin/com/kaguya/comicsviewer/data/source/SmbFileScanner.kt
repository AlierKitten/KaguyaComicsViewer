package com.kaguya.comicsviewer.data.source

import com.kaguya.comicsviewer.data.source.archive.ArchiveExtractor
import com.kaguya.comicsviewer.data.source.smb.SmbClient
import com.kaguya.comicsviewer.domain.model.ComicSource
import javax.inject.Inject
import javax.inject.Singleton

/** SMB 远程源扫描器。 */
@Singleton
class SmbFileScanner @Inject constructor(
    private val smb: SmbClient,
    private val extractor: ArchiveExtractor
) : ComicScanner {

    override suspend fun scan(source: ComicSource): List<DiscoveredComic> {
        val paths = smb.scanRecursive(source, source.path)
        return paths.map { relPath ->
            val fileName = relPath.substringAfterLast('/')
            DiscoveredComic(
                title = fileName.substringBeforeLast('.'),
                relativePath = relPath,
                absoluteUri = relPath,
                sizeBytes = 0 // 扫描时不获取大小，下载时再获取
            )
        }
    }

    override suspend fun cover(source: ComicSource, discovered: DiscoveredComic): ByteArray? {
        return null
    }
}
