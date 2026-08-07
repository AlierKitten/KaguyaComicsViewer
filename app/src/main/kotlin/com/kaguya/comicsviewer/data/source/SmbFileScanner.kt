package com.kaguya.comicsviewer.data.source

import com.kaguya.comicsviewer.data.source.archive.ArchiveExtractor
import com.kaguya.comicsviewer.data.source.smb.SmbClient
import com.kaguya.comicsviewer.data.source.smb.SmbSession
import com.kaguya.comicsviewer.domain.model.ComicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** SMB 远程源扫描器。 */
@Singleton
class SmbFileScanner @Inject constructor(
    private val smb: SmbClient,
    private val extractor: ArchiveExtractor
) : ComicScanner {

    override suspend fun scan(source: ComicSource): List<DiscoveredComic> = withContext(Dispatchers.IO) {
        val session = smb.open(source)
        try {
            val results = mutableListOf<DiscoveredComic>()
            walk(session, source.path.orEmpty(), "", results)
            results
        } finally {
            session.close()
        }
    }

    private suspend fun walk(session: SmbSession, path: String, prefix: String, out: MutableList<DiscoveredComic>) {
        val entries = smb.listDir(session, path)
        for (e in entries) {
            val rel = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
            if (e.isDirectory) {
                val sub = if (path.isBlank()) e.name else "$path/${e.name}"
                walk(session, sub, rel, out)
            } else {
                if (extractor.detectType(e.name) == null) continue
                val full = if (path.isBlank()) e.name else "$path/${e.name}"
                out += DiscoveredComic(
                    title = e.name.substringBeforeLast('.'),
                    relativePath = rel,
                    absoluteUri = full,
                    sizeBytes = e.size
                )
            }
        }
    }

    override suspend fun cover(source: ComicSource, discovered: DiscoveredComic): ByteArray? {
        // 封面在下载解压阶段生成。
        return null
    }
}
