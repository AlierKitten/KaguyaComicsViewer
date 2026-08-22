package com.kaguya.comicsviewer.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.kaguya.comicsviewer.data.local.dao.ComicDao
import com.kaguya.comicsviewer.data.local.dao.ComicSourceDao
import com.kaguya.comicsviewer.data.local.entity.toDomain
import com.kaguya.comicsviewer.data.local.entity.toEntity
import com.kaguya.comicsviewer.data.source.archive.ArchiveExtractor
import com.kaguya.comicsviewer.data.source.smb.SmbClient
import com.kaguya.comicsviewer.domain.model.CacheState
import com.kaguya.comicsviewer.domain.model.Comic
import com.kaguya.comicsviewer.domain.model.ComicCache
import com.kaguya.comicsviewer.domain.model.ComicPage
import com.kaguya.comicsviewer.domain.model.ComicSource
import com.kaguya.comicsviewer.domain.model.ComicSourceType
import com.kaguya.comicsviewer.domain.model.ReadingProgress
import com.kaguya.comicsviewer.util.CacheDirectories
import com.kaguya.comicsviewer.util.FileDescriptorCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ComicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourceDao: ComicSourceDao,
    private val comicDao: ComicDao,
    private val cacheDirs: CacheDirectories,
    private val extractor: ArchiveExtractor,
    private val smbClient: SmbClient
) : ComicRepository {

    private companion object {
        const val TAG = "ComicRepositoryImpl"
    }

    override fun observeSources(): Flow<List<ComicSource>> =
        sourceDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeSource(id: Long): Flow<ComicSource?> =
        sourceDao.observeById(id).map { it?.toDomain() }

    override suspend fun listEnabledSources(): List<ComicSource> =
        sourceDao.listEnabled().map { it.toDomain() }

    override suspend fun upsertSource(source: ComicSource): Long {
        // 不能用 sourceDao.upsert(REPLACE)：REPLACE = DELETE + INSERT 同一源行，
        // 而 comics 表对 source_id 定义了 onDelete=CASCADE，会导致该源下所有已 index 的
        // 漫画被级联删除（典型表现：关闭/重新启用源后退出重启，漫画库变 0 需重新扫描）。
        // 改为"已存在则 update（保留原行，不触级联），不存在才 insert"。
        val entity = source.toEntity()
        val existing = sourceDao.findById(entity.id)
        return if (existing != null) {
            sourceDao.update(entity)
            entity.id
        } else {
            sourceDao.upsert(entity)
        }
    }

    override suspend fun deleteSource(id: Long) = sourceDao.deleteById(id)

    override suspend fun markSourceScanned(id: Long, timestamp: Long) =
        sourceDao.updateLastScanned(id, timestamp)

    override suspend fun updateSourceIndex(id: Long, status: String, current: Int, total: Int) =
        sourceDao.updateIndexProgress(id, status, current, total)

    override suspend fun resetStaleIndexingStatuses() =
        sourceDao.resetStaleIndexingStatuses()

    override fun observeComicsBySources(sourceIds: List<Long>): Flow<List<Comic>> =
        comicDao.observeBySources(sourceIds).map { list -> list.map { it.toDomain() } }

    override fun observeAllComics(): Flow<List<Comic>> =
        comicDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeComicCountBySources(sourceIds: List<Long>): Flow<Map<Long, Int>> =
        comicDao.countBySources(sourceIds).map { list -> list.associate { it.sourceId to it.count } }

    override suspend fun listComicsBySources(sourceIds: List<Long>): List<Comic> =
        comicDao.observeBySources(sourceIds).first().map { it.toDomain() }

    override suspend fun deleteComic(id: Long) = comicDao.deleteById(id)

    override fun observeComic(id: Long): Flow<Comic?> =
        comicDao.observeById(id).map { it?.toDomain() }

    override fun observeRecent(limit: Int): Flow<List<Comic>> =
        comicDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    override fun observeLoading(): Flow<List<Comic>> =
        comicDao.observeLoading().map { list -> list.map { it.toDomain() } }

    override suspend fun findComic(id: Long): Comic? = comicDao.findById(id)?.toDomain()

    override suspend fun upsertComic(comic: Comic): Long = comicDao.upsertComic(comic.toEntity())

    override suspend fun upsertComics(comics: List<Comic>) =
        comicDao.upsertComics(comics.map { it.toEntity() })

    override suspend fun removeComicsNotIn(sourceId: Long, keepIds: List<Long>) {
        if (keepIds.isEmpty()) {
            comicDao.deleteMissing(sourceId, listOf(-1))
        } else {
            comicDao.deleteMissing(sourceId, keepIds)
        }
    }

    override fun observeCache(comicId: Long): Flow<ComicCache?> =
        comicDao.observeCache(comicId).map { it?.toDomain() }

    override fun observeAllCaches(): Flow<List<ComicCache>> =
        comicDao.observeAllCaches().map { list -> list.map { it.toDomain() } }

    override suspend fun listAllCaches(): List<ComicCache> =
        comicDao.listAllCaches().map { it.toDomain() }

    override suspend fun findCache(comicId: Long): ComicCache? = comicDao.findCache(comicId)?.toDomain()

    override suspend fun upsertCache(cache: ComicCache) {
        comicDao.upsertCache(
            ComicCache(
                comicId = cache.comicId,
                state = cache.state,
                archiveFile = cache.archiveFile,
                extractedDir = cache.extractedDir,
                totalBytes = cache.totalBytes,
                downloadedBytes = cache.downloadedBytes,
                lastError = cache.lastError
            ).toEntity(cache.comicId)
        )
    }

    override suspend fun deleteCache(comicId: Long) {
        val entity = comicDao.findCache(comicId)
        if (entity == null) {
            cacheDirs.clearPageCache(comicId)
            return
        }
        // 仅删除磁盘缓存产物；外部存储上的原始压缩包（本地源直接读取）不得删除。
        if (!entity.isExternalArchive) {
            entity.archiveFile?.let { p -> runCatching { java.io.File(p).delete() } }
            entity.extractedDir?.let { p -> runCatching { java.io.File(p).deleteRecursively() } }
            cacheDirs.clearPageCache(comicId)
            comicDao.deleteCache(comicId)
        } else {
            // 本地源（SAF 流直读）：原始压缩包始终在用户存储中可读，"缓存"只是解压出来的页。
            // 清缓存时只删磁盘文件，必须保留 cache 行（READY + "saf:<id>" 标识），
            // 否则重新阅读时 listPages 因找不到 READY 缓存而返回空页（"未找到页面"）。
            cacheDirs.archiveFile(comicId)?.let { f ->
                runCatching { if (f.exists() && f.name.startsWith("comic_$comicId")) f.delete() }
            }
            entity.extractedDir?.let { p -> runCatching { java.io.File(p).deleteRecursively() } }
            cacheDirs.clearPageCache(comicId)
            val reset = entity.copy(
                state = CacheState.READY.name,
                extractedDir = null,
                downloadedBytes = 0,
                lastError = null
            )
            comicDao.upsertCache(reset)
        }
    }

    override fun observeProgress(comicId: Long): Flow<ReadingProgress?> =
        comicDao.observeProgress(comicId).map { it?.toDomain() }

    override suspend fun saveProgress(progress: ReadingProgress) =
        comicDao.upsertProgress(progress.toEntity())

    /**
     * 统一解析出可读的本地压缩包文件（本地源与 SMB 源共用，实现效果一致）。
     * - 本地源：优先用 SAF DocumentFile 的真实文件路径 [FileDescriptorCompat.path] 直读原始压缩包；
     *   不可读（scoped storage 无权限）则懒复制压缩包副本到缓存目录再读。
     * - SMB 源：压缩包已由下载 Worker 落到本地缓存（archiveFile 即本地副本路径），直接返回。
     * 返回的 File 一定可被随机访问（seek），供 ArchiveExtractor 直接解压单页。
     */
    override suspend fun resolveArchiveFile(comicId: Long): java.io.File? {
        val cache = comicDao.findCache(comicId) ?: return null
        val comic = findComic(comicId) ?: return null
        val source = sourceDao.findById(comic.sourceId)?.toDomain() ?: return null

        return if (source.type == ComicSourceType.LOCAL) {
            resolveLocalArchive(comic, source)
        } else {
            // SMB 源：archiveFile 已是本地下载副本路径
            val path = cache.archiveFile
            if (path.isNullOrBlank()) null else java.io.File(path).takeIf { it.isFile }
        }
    }

    /**
     * 本地源解析：优先真实路径直读；不可读则懒复制副本到 archives/。
     * 返回的 File 若为原始文件（直读），调用方在删除缓存时不得删除它。
     */
    private suspend fun resolveLocalArchive(
        comic: Comic,
        source: ComicSource
    ): java.io.File? {
        val localUri = source.localUri ?: return null
        val root = DocumentFile.fromTreeUri(context, Uri.parse(localUri)) ?: return null
        val parts = comic.filePath.split('/').filter { it.isNotBlank() }
        var cur: DocumentFile? = root
        for (p in parts) {
            cur = cur?.listFiles()?.firstOrNull { it.name == p } ?: return null
        }
        val doc = cur ?: return null

        // 1) 优先真实路径直读（外部存储上的真实文件，可被 libarchive seek）
        val realPath = FileDescriptorCompat.path(doc)
        if (realPath != null) {
            val f = java.io.File(realPath)
            if (f.isFile && f.canRead()) return f
        }
        // 2) 回退：懒复制压缩包副本到 archives/ 目录（仅副本，非解压全部图片）
        val fileName = comic.filePath.substringAfterLast('/')
        val cacheFile = cacheDirs.archiveFile(comic.id, fileName)
        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile
        return try {
            cacheFile.parentFile?.mkdirs()
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                java.io.FileOutputStream(cacheFile).use { out -> input.copyTo(out, 256 * 1024) }
            }
            if (cacheFile.exists() && cacheFile.length() > 0) cacheFile else null
        } catch (e: Exception) {
            android.util.Log.w("ComicRepositoryImpl", "本地源复制压缩包失败: ${e.message}")
            null
        }
    }

    override suspend fun openArchiveStream(comicId: Long): Pair<InputStream, String>? {
        val comic = findComic(comicId) ?: run {
            Log.e(TAG, "openArchiveStream: comic not found: $comicId")
            return null
        }
        val source = sourceDao.findById(comic.sourceId)?.toDomain() ?: run {
            Log.e(TAG, "openArchiveStream: source not found for comicId=$comicId")
            return null
        }
        // 仅本地源支持 SAF 流直读（不复制压缩包）；SMB 源走已下载副本。
        if (source.type != ComicSourceType.LOCAL) {
            Log.w(TAG, "openArchiveStream: 非本地源，跳过: comicId=$comicId")
            return null
        }
        val localUri = source.localUri ?: run {
            Log.e(TAG, "openArchiveStream: localUri 为空 for comicId=$comicId")
            return null
        }
        val root = DocumentFile.fromTreeUri(context, Uri.parse(localUri)) ?: run {
            Log.e(TAG, "openArchiveStream: DocumentFile.fromTreeUri 失败 localUri=$localUri")
            return null
        }
        val parts = comic.filePath.split('/').filter { it.isNotBlank() }
        var cur: DocumentFile? = root
        for (p in parts) {
            cur = cur?.listFiles()?.firstOrNull { it.name == p } ?: run {
                Log.e(TAG, "openArchiveStream: 遍历路径失败 at '$p' (comicId=$comicId, filePath=${comic.filePath})")
                return null
            }
        }
        val file = cur ?: run {
            Log.e(TAG, "openArchiveStream: file 为 null comicId=$comicId")
            return null
        }
        val input = runCatching { context.contentResolver.openInputStream(file.uri) }
            .onFailure { Log.e(TAG, "openArchiveStream: openInputStream 失败 comicId=$comicId, ${it.message}", it) }
            .getOrNull()
            ?: run {
                Log.e(TAG, "openArchiveStream: openInputStream 返回 null comicId=$comicId")
                return null
            }
        val fileName = comic.filePath.substringAfterLast('/')
        Log.d(TAG, "openArchiveStream: comicId=$comicId, fileName=$fileName (SAF 流直读)")
        return input to fileName
    }

    override suspend fun listPages(comicId: Long): List<ComicPage> {
        val comicEntity = comicDao.findById(comicId)
        if (comicEntity == null) {
            Log.e(TAG, "listPages: comic row MISSING for comicId=$comicId -> 返回空页")
            return emptyList()
        }
        val source = sourceDao.findById(comicEntity.sourceId)?.toDomain()
            ?: run {
                Log.e(TAG, "listPages: source MISSING (sourceId=${comicEntity.sourceId}) for comicId=$comicId -> 返回空页")
                return emptyList()
            }

        // 本地源：SAF 流直读，**不依赖 cache 行是否存在/状态**（清缓存后 cache 行可能被重置，
        // 但不应影响本地源阅读——原始压缩包始终在用户存储中可读）。只要 comic+source 存在即可读。
        // 扫描整个 SAF 流耗时较长，期间若 UI 协程被取消会抛 CancellationException，必须**重新抛出**；
        // 且 SAF 直读失败时**绝不** fall through 到"复制整包"（超大文件失败点，违背"不复制"诉求）。
        if (source.type == ComicSourceType.LOCAL) {
            val streamPair = openArchiveStream(comicId)
            if (streamPair != null) {
                val (input, _) = streamPair
                try {
                    val entries = extractor.listImageEntries(input)
                    if (entries.isNotEmpty()) {
                        return entries.mapIndexed { index, entryName ->
                            ComicPage(
                                comicId = comicId,
                                index = index,
                                // "saf:<id>" 标识：PageImageCache 据此从 SAF 流按需解压单页。
                                archivePath = "saf:$comicId",
                                entryName = entryName
                            )
                        }
                    }
                } catch (ce: CancellationException) {
                    runCatching { input.close() }
                    throw ce
                } catch (e: Exception) {
                    Log.e(TAG, "SAF 流列举图片失败: ${e.message}", e)
                }
                runCatching { input.close() }
                // SAF 流直读失败（非取消）：不再复制整包，直接返回空列表。
                return emptyList()
            }
            // 连 SAF 流都打不开（极少见，如权限被撤），同样不复制，返回空。
            return emptyList()
        }

        // 通用回退（SMB 源等）：解析出可读的本地压缩包文件（已下载副本）。
        val archiveFile = resolveArchiveFile(comicId) ?: return emptyList()
        val entries = extractor.listImageEntries(archiveFile)
        if (entries.isEmpty()) return emptyList()
        return entries.mapIndexed { index, entryName ->
            ComicPage(
                comicId = comicId,
                index = index,
                archivePath = archiveFile.absolutePath,
                entryName = entryName
            )
        }
    }

    override suspend fun updatePageCount(comicId: Long, count: Int) {
        comicDao.updatePageCount(comicId, count)
    }

    override suspend fun tryGenerateCoverViaStream(comicId: Long): ByteArray? = withContext(Dispatchers.IO) {
        Log.d(TAG, "tryGenerateCoverViaStream: 被调用 comicId=$comicId")
        val comic = findComic(comicId) ?: run {
            Log.w(TAG, "tryGenerateCoverViaStream: comic 不存在 comicId=$comicId")
            return@withContext null
        }
        val source = sourceDao.findById(comic.sourceId)?.toDomain() ?: run {
            Log.w(TAG, "tryGenerateCoverViaStream: source 不存在 comicId=$comicId")
            return@withContext null
        }

        // SMB（非本地）源：扫描阶段往往尚未下载压缩包，resolveArchiveFile 为空会导致无法确定首图。
        // 这里先确保压缩包已落到本地缓存（未下载则同步下载），与「点开阅读→下载→出封面」同款路径。
        // 下载得到的副本作为正常阅读缓存保留，不算封面加载产生的临时缓存。
        if (source.type != ComicSourceType.LOCAL) {
            val ready = ensureArchiveDownloaded(comic)
            if (ready == null) {
                Log.w(TAG, "tryGenerateCoverViaStream: SMB 源确保下载失败，comicId=$comicId")
                return@withContext null
            }
            Log.d(TAG, "tryGenerateCoverViaStream: SMB 源已就绪 archiveFile=${ready.absolutePath}, comicId=$comicId")
        }

        // 复用阅读器同款列举：按名排序首图（与阅读器 ensureCover 一致）
        val firstEntryName = runCatching { pickCoverEntryName(comicId) }.getOrNull()
        if (firstEntryName.isNullOrBlank()) {
            Log.w(TAG, "tryGenerateCoverViaStream: 无法确定首图 entryName，comicId=$comicId")
            return@withContext null
        }
        Log.d(TAG, "tryGenerateCoverViaStream: 首图 entry=$firstEntryName, source.type=${source.type}, comicId=$comicId")

        // 临时目录：整包顺序解压首图到这里，读字节后立即删除，不残留任何封面外缓存
        // （满足「清除因加载封面产生的缓存」诉求）。
        val tmpDir = File(context.cacheDir, "cover_tmp_$comicId").apply { mkdirs() }
        val target = File(tmpDir, "first.bin")
        try {
            var thumb: ByteArray? = null

            if (source.type == ComicSourceType.LOCAL) {
                // 本地源：SAF 流直读（不复制压缩包），与阅读器体验一致
                openArchiveStream(comicId)?.let { (input, _) ->
                    try {
                        val ok = extractor.extractImagesToStream(input, listOf(firstEntryName to target))
                        Log.d(TAG, "tryGenerateCoverViaStream: 本地源整包解压首图 ok=$ok, target=${target.length()}, comicId=$comicId")
                        if (ok.isNotEmpty() && target.exists() && target.length() > 0) {
                            thumb = extractor.generateCoverThumbnail(target.readBytes(), maxWidth = 300, quality = 75)
                        }
                        if (thumb == null) {
                            // 整包解压失败，回退 SAF 流直读单页
                            val raw = extractor.readEntryStream(input, firstEntryName)
                            Log.d(TAG, "tryGenerateCoverViaStream: 本地源回退 readEntryStream raw=${raw?.size ?: -1}, comicId=$comicId")
                            if (raw != null && raw.isNotEmpty()) {
                                thumb = extractor.generateCoverThumbnail(raw, maxWidth = 300, quality = 75)
                            }
                        }
                    } finally {
                        input.close()
                    }
                }
            } else {
                // SMB 源（及其他非本地源）：压缩包已下载到本地缓存，用 File 随机访问解压，
                // extractImageTo 含 libarchive + JDK ZipFile 双重回退，对特殊/损坏 ZIP 最稳。
                val archiveFile = resolveArchiveFile(comicId)
                if (archiveFile == null) {
                    Log.w(TAG, "tryGenerateCoverViaStream: SMB 源 resolveArchiveFile 为空（未下载？），comicId=$comicId")
                } else {
                    val ok = extractor.extractImageTo(archiveFile, firstEntryName, target)
                    Log.d(TAG, "tryGenerateCoverViaStream: SMB 源整包解压首图 ok=$ok, target=${target.length()}, comicId=$comicId")
                    if (ok && target.exists() && target.length() > 0) {
                        thumb = extractor.generateCoverThumbnail(target.readBytes(), maxWidth = 300, quality = 75)
                    }
                }
            }

            Log.d(TAG, "tryGenerateCoverViaStream: 生成 thumb=${thumb?.size ?: -1} 字节, comicId=$comicId")
            thumb
        } catch (e: Exception) {
            Log.w(TAG, "tryGenerateCoverViaStream: 异常 comicId=$comicId: ${e.message}", e)
            null
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /** 按名排序取首图 entry 名（与阅读器 listPages 封面选取一致）。 */
    private suspend fun pickCoverEntryName(comicId: Long): String? {
        val pages = listPages(comicId)
        return pages.firstOrNull()?.entryName
    }

    /**
     * 确保 SMB（非本地）漫画的压缩包已落地到本地缓存。
     * - 已下载（resolveArchiveFile 非空）直接返回该文件；
     * - 未下载则同步从 SMB 拉取压缩包到 [CacheDirectories.archiveFile]，下载完成后写缓存行为 READY。
     * 返回落地后的文件，失败返回 null。
     */
    private suspend fun ensureArchiveDownloaded(comic: Comic): File? = withContext(Dispatchers.IO) {
        val existing = resolveArchiveFile(comic.id)
        if (existing != null) {
            Log.d(TAG, "ensureArchiveDownloaded: 已存在，直接复用 comicId=${comic.id}, file=${existing.absolutePath}")
            return@withContext existing
        }
        val source = sourceDao.findById(comic.sourceId)?.toDomain() ?: run {
            Log.w(TAG, "ensureArchiveDownloaded: source 不存在 comicId=${comic.id}")
            return@withContext null
        }
        if (source.type == ComicSourceType.LOCAL) {
            // 本地源不下载，交给 openArchiveStream 流直读
            return@withContext null
        }
        val remote = comic.filePath
        if (remote.isBlank()) {
            Log.w(TAG, "ensureArchiveDownloaded: remote path 为空 comicId=${comic.id}")
            return@withContext null
        }
        val outFile = cacheDirs.archiveFile(comic.id, comic.filePath)
        outFile.parentFile?.mkdirs()
        Log.d(TAG, "ensureArchiveDownloaded: 开始下载 SMB 压缩包 comicId=${comic.id}, out=${outFile.absolutePath}, remote='$remote'")
        val ok = runCatching {
            smbClient.readFile(source, remote) { input ->
                FileOutputStream(outFile).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        }.onFailure { Log.e(TAG, "ensureArchiveDownloaded: 下载异常 comicId=${comic.id}: ${it.message}", it) }
            .isSuccess
        if (!ok || !outFile.exists() || outFile.length() == 0L) {
            Log.w(TAG, "ensureArchiveDownloaded: 下载失败或文件为空 comicId=${comic.id}")
            outFile.delete()
            return@withContext null
        }
        // 写缓存行，使后续阅读/解压都能命中（与 DownloadComicWorker 同款落点）
        val isZip = extractor.detectType(outFile.name)?.isZip == true
        if (isZip) {
            upsertCache(
                ComicCache(
                    comicId = comic.id,
                    state = CacheState.READY,
                    archiveFile = outFile.absolutePath,
                    extractedDir = null,
                    totalBytes = outFile.length(),
                    downloadedBytes = outFile.length(),
                    lastError = null
                )
            )
        }
        Log.d(TAG, "ensureArchiveDownloaded: 下载完成 comicId=${comic.id}, size=${outFile.length()}")
        outFile
    }

    override suspend fun clearAllProgress() {
        comicDao.clearAllProgress()
    }

    override suspend fun deleteProgress(comicId: Long) {
        comicDao.deleteProgress(comicId)
    }

    override suspend fun clearAllCoverPaths() {
        comicDao.clearAllCoverPaths()
    }

    private fun java.io.File.isImageFile(): Boolean {
        val n = name.lowercase()
        return n.endsWith(".jpg") || n.endsWith(".jpeg") ||
            n.endsWith(".png") || n.endsWith(".webp") ||
            n.endsWith(".gif") || n.endsWith(".bmp") ||
            n.endsWith(".avif") || n.endsWith(".heic")
    }
}
