package com.kaguya.comicsviewer.ui.reader

import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.kaguya.comicsviewer.data.cache.PageImageCache
import com.kaguya.comicsviewer.domain.model.ComicPage
import okio.buffer
import okio.source
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 作为 Coil 的 model 传给 [SubcomposeAsyncImage] / [coil.compose.AsyncImage]。
 * 表示"来自 ZIP 压缩包的某一页"，由 [ArchiveFetcher] 负责按需解压到页缓存并返回文件。
 */
data class ArchiveImage(val page: ComicPage)

/**
 * 将 [ArchiveImage] 解析为磁盘上的缓存文件并交给 Coil 解码。
 */
@Singleton
class ArchiveFetcher @Inject constructor(
    private val pageCache: PageImageCache
) : Fetcher.Factory<ArchiveImage> {

    override fun create(data: ArchiveImage, options: Options, imageLoader: coil.ImageLoader): Fetcher {
        return object : Fetcher {
            override suspend fun fetch(): FetchResult {
                val file: File = pageCache.getOrCreate(data.page.comicId, data.page)
                    ?: throw IllegalStateException("解压页失败: ${data.page.entryName}")
                pageCache.evictIfNeeded(data.page.comicId)
                return SourceResult(
                    source = ImageSource(file.source().buffer(), file),
                    mimeType = null,
                    dataSource = DataSource.DISK
                )
            }
        }
    }
}
