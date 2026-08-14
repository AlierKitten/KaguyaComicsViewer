package com.kaguya.comicsviewer.data.local.entity

import com.kaguya.comicsviewer.domain.model.CacheState
import com.kaguya.comicsviewer.domain.model.Comic
import com.kaguya.comicsviewer.domain.model.ComicCache
import com.kaguya.comicsviewer.domain.model.ComicSource
import com.kaguya.comicsviewer.domain.model.ComicSourceType
import com.kaguya.comicsviewer.domain.model.IndexStatus
import com.kaguya.comicsviewer.domain.model.ReadingProgress

fun ComicSourceEntity.toDomain(): ComicSource = ComicSource(
    id = id,
    type = runCatching { ComicSourceType.valueOf(type) }.getOrDefault(ComicSourceType.LOCAL),
    name = name,
    localUri = localUri,
    host = host,
    share = share,
    path = path,
    username = username,
    password = password,
    domain = domain,
    enabled = enabled,
    lastScannedAt = lastScannedAt,
    indexStatus = runCatching { IndexStatus.valueOf(indexStatus ?: "IDLE") }.getOrDefault(IndexStatus.IDLE),
    indexCurrent = indexCurrent,
    indexTotal = indexTotal
)

fun ComicSource.toEntity(): ComicSourceEntity = ComicSourceEntity(
    id = id,
    type = type.name,
    name = name,
    localUri = localUri,
    host = host,
    share = share,
    path = path,
    username = username,
    password = password,
    domain = domain,
    enabled = enabled,
    lastScannedAt = lastScannedAt,
    indexStatus = indexStatus.name,
    indexCurrent = indexCurrent,
    indexTotal = indexTotal
)

fun ComicEntity.toDomain(): Comic = Comic(
    id = id,
    sourceId = sourceId,
    title = title,
    filePath = filePath,
    sizeBytes = sizeBytes,
    pageCount = pageCount,
    coverPath = coverPath,
    addedAt = addedAt,
    updatedAt = updatedAt
)

fun Comic.toEntity(): ComicEntity = ComicEntity(
    id = id,
    sourceId = sourceId,
    title = title,
    filePath = filePath,
    sizeBytes = sizeBytes,
    pageCount = pageCount,
    coverPath = coverPath,
    addedAt = addedAt,
    updatedAt = updatedAt
)

fun ComicCacheEntity.toDomain(): ComicCache = ComicCache(
    comicId = comicId,
    state = runCatching { CacheState.valueOf(state) }.getOrDefault(CacheState.PENDING),
    archiveFile = archiveFile,
    extractedDir = extractedDir,
    totalBytes = totalBytes,
    downloadedBytes = downloadedBytes,
    lastError = lastError,
    isExternalArchive = isExternalArchive
)

fun ComicCache.toEntity(comicId: Long): ComicCacheEntity = ComicCacheEntity(
    comicId = comicId,
    state = state.name,
    archiveFile = archiveFile,
    extractedDir = extractedDir,
    totalBytes = totalBytes,
    downloadedBytes = downloadedBytes,
    lastError = lastError,
    isExternalArchive = isExternalArchive
)

fun ReadingProgressEntity.toDomain(): ReadingProgress = ReadingProgress(
    comicId = comicId,
    page = page,
    updatedAt = updatedAt,
    isFinished = isFinished
)

fun ReadingProgress.toEntity(): ReadingProgressEntity = ReadingProgressEntity(
    comicId = comicId,
    page = page,
    updatedAt = updatedAt,
    isFinished = isFinished
)
