package com.kaguya.comicsviewer.domain.usecase

import android.util.Log
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.data.source.LocalFileScanner
import com.kaguya.comicsviewer.data.source.SmbFileScanner
import com.kaguya.comicsviewer.domain.model.Comic
import com.kaguya.comicsviewer.domain.model.ComicSource
import com.kaguya.comicsviewer.domain.model.ComicSourceType
import javax.inject.Inject

/** 扫描一个源：发现所有漫画并同步到数据库。 */
class ScanSourceUseCase @Inject constructor(
    private val repository: ComicRepository,
    private val localScanner: LocalFileScanner,
    private val smbScanner: SmbFileScanner
) {
    companion object {
        private const val TAG = "ScanSourceUseCase"
    }

    suspend operator fun invoke(source: ComicSource): Int {
        Log.d(TAG, "scan start: source='${source.name}', type=${source.type}, host=${source.host}, share=${source.share}, path=${source.path}")
        val scanner = when (source.type) {
            ComicSourceType.LOCAL -> localScanner
            ComicSourceType.SMB -> smbScanner
        }
        val found = try {
            scanner.scan(source)
        } catch (e: Exception) {
            Log.e(TAG, "scan failed for source '${source.name}'", e)
            return 0
        }
        Log.d(TAG, "scan found ${found.size} comics for source '${source.name}'")
        val now = System.currentTimeMillis()

        val existing = repository.listComicsBySources(listOf(source.id))
        val existingByPath = existing.associateBy { it.filePath }
        val keepIds = mutableListOf<Long>()

        // Upsert found
        for (d in found) {
            val prev = existingByPath[d.relativePath]
            val comic = Comic(
                id = prev?.id ?: 0L,
                sourceId = source.id,
                title = d.title,
                filePath = d.relativePath,
                sizeBytes = d.sizeBytes,
                pageCount = prev?.pageCount ?: 0,
                coverPath = prev?.coverPath,
                addedAt = prev?.addedAt ?: now,
                updatedAt = now
            )
            val id = repository.upsertComic(comic)
            keepIds += id
        }

        // 删除不在 found 内的
        repository.removeComicsNotIn(source.id, keepIds)
        repository.markSourceScanned(source.id, now)
        Log.d(TAG, "scan complete for source '${source.name}': upserted=${found.size}, total kept=${keepIds.size}")
        return found.size
    }
}
