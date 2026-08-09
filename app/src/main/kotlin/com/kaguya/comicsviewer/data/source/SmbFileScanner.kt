package com.kaguya.comicsviewer.data.source

import com.kaguya.comicsviewer.data.source.smb.SmbClient
import com.kaguya.comicsviewer.domain.model.ComicSource
import javax.inject.Inject
import javax.inject.Singleton

/** SMB 远程源扫描器。 */
@Singleton
class SmbFileScanner @Inject constructor(
    private val smb: SmbClient,
) : ComicScanner {

    override suspend fun scan(source: ComicSource, shouldCancel: () -> Boolean): List<DiscoveredComic> {
        return smb.scanRecursive(source, source.path, shouldCancel)
    }

    override suspend fun fetchSizes(source: ComicSource, comics: List<DiscoveredComic>): Map<String, Long> {
        return smb.fetchSizes(source, comics.map { it.relativePath })
    }

    override suspend fun cover(source: ComicSource, discovered: DiscoveredComic): ByteArray? {
        return smb.readCover(source, discovered.absoluteUri)
    }
}
