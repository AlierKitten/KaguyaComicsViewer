package com.kaguya.comicsviewer.ui.library

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.kaguya.comicsviewer.domain.model.CacheState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(
    nav: NavController,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loadingProgress by viewModel.loadingProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // 当加载完成(READY)时自动跳转阅读器
    LaunchedEffect(loadingProgress?.state) {
        if (loadingProgress?.state == CacheState.READY) {
            val comicId = loadingProgress!!.comicId
            viewModel.dismissLoading()
            nav.navigate("reader/$comicId")
        }
    }

    // 加载进度弹窗
    loadingProgress?.let { lp ->
        when (lp.state) {
            CacheState.DOWNLOADING, CacheState.EXTRACTING, CacheState.DOWNLOADED, CacheState.FAILED -> {
                LoadingDialog(
                    progress = lp,
                    onDismiss = { viewModel.dismissLoading() },
                    onCancel = { viewModel.cancelLoading(lp.comicId) }
                )
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("继续阅读") },
                actions = {
                    IconButton(onClick = { viewModel.clearProgress() }) {
                        Icon(Icons.Outlined.CleaningServices, contentDescription = "清除阅读记录")
                    }
                }
            )
        }
    ) { padding ->
        if (state.recent.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.AutoStories,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("没有阅读记录", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "开始阅读或加载漫画后这里会显示进度",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.recent, key = { it.comic.id }) { row ->
                    RecentListItem(row,
                        onClick = {
                            when (row.cache?.state) {
                                CacheState.READY -> nav.navigate("reader/${row.comic.id}")
                                CacheState.DOWNLOADING, CacheState.EXTRACTING, CacheState.PENDING, CacheState.DOWNLOADED ->
                                    viewModel.startLoading(row.comic.id, row.comic.title)
                                else -> viewModel.startLoading(row.comic.id, row.comic.title)
                            }
                        },
                        onCancel = { viewModel.cancelLoading(row.comic.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentListItem(
    row: ComicRow,
    onClick: () -> Unit,
    onCancel: () -> Unit
) {
    val isLoading = row.cache?.state in listOf(
        CacheState.PENDING, CacheState.DOWNLOADING, CacheState.DOWNLOADED, CacheState.EXTRACTING
    )

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面
            Box(
                modifier = Modifier
                    .size(width = 80.dp, height = 100.dp)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(row.comic.coverPath?.let { File(it) })
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    loading = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        }
                    },
                    error = {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.AutoStories, null)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 信息
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    row.comic.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                if (row.isFinished) {
                    Text(
                        "已读完",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        "已读 ${row.progress + 1} / ${row.comic.pageCount.takeIf { it > 0 } ?: "?"} 页",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (row.progress > 0 || row.isFinished) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (row.isFinished) 1f
                            else (row.progress.toFloat() / (row.comic.pageCount.takeIf { it > 0 } ?: 1))
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                }

                // 缓存状态
                row.cache?.let { cache ->
                    Spacer(Modifier.height(4.dp))
                    when (cache.state) {
                        CacheState.READY -> Text(
                            "已就绪",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50)
                        )
                        CacheState.DOWNLOADING -> Text(
                            "下载中 ${if (cache.totalBytes > 0) "${((cache.downloadedBytes * 100) / cache.totalBytes).toInt()}%" else "..."}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        CacheState.EXTRACTING -> Text(
                            "解压中...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        CacheState.DOWNLOADED -> Text(
                            "已下载，待解压",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        CacheState.PENDING -> Text(
                            "等待加载",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        CacheState.FAILED -> Text(
                            "加载失败",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            
                // 下载进度条
                row.cache?.let { cache ->
                    if (cache.state == CacheState.DOWNLOADING && cache.totalBytes > 0) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { (cache.downloadedBytes.toFloat() / cache.totalBytes.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                        )
                    }
                }
            }
            
            if (isLoading) {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Outlined.Cancel,
                        contentDescription = "取消加载",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
