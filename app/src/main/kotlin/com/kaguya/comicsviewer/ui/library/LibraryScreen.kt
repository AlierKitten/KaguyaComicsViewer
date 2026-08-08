package com.kaguya.comicsviewer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.kaguya.comicsviewer.util.FormatUtils
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    nav: NavController,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loadingProgress by viewModel.loadingProgress.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 当加载完成(READY)时自动跳转阅读器，不弹 dialog
    LaunchedEffect(loadingProgress?.state) {
        if (loadingProgress?.state == CacheState.READY) {
            val comicId = loadingProgress!!.comicId
            viewModel.dismissLoading()
            nav.navigate("reader/$comicId")
        }
    }

    // 加载进度弹窗（READY 和 PENDING 初始状态不弹，失败时弹出）
    loadingProgress?.let { lp ->
        when (lp.state) {
            CacheState.DOWNLOADING, CacheState.EXTRACTING, CacheState.DOWNLOADED, CacheState.FAILED -> {
                LoadingDialog(
                    progress = lp,
                    onDismiss = { viewModel.dismissLoading() }
                )
            }
            else -> Unit // PENDING / READY 不弹 dialog
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("漫画库") },
                actions = {
                    IconButton(onClick = { viewModel.scanAll() }) {
                        if (state.isScanning) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        else Icon(Icons.Outlined.Refresh, contentDescription = "扫描")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("搜索漫画") }
            )

            if (state.comics.isEmpty() && state.recent.isEmpty()) {
                EmptyLibrary()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.comics, key = { it.comic.id }) { row ->
                        ComicGridItem(row, onClick = {
                            handleComicClick(row, nav, viewModel)
                        })
                    }
                }
            }
        }
    }
}

private fun handleComicClick(
    row: ComicRow,
    nav: NavController,
    viewModel: LibraryViewModel
) {
    when (row.cache?.state) {
        CacheState.READY -> nav.navigate("reader/${row.comic.id}")
        CacheState.FAILED -> viewModel.startLoading(row.comic.id, row.comic.title)
        CacheState.PENDING, CacheState.DOWNLOADED, null -> viewModel.startLoading(row.comic.id, row.comic.title)
        CacheState.DOWNLOADING, CacheState.EXTRACTING -> {
            // 已经在加载中，重新显示进度弹窗
            viewModel.startLoading(row.comic.id, row.comic.title)
        }
    }
}

@Composable
private fun LoadingDialog(progress: LoadingProgress, onDismiss: () -> Unit) {
    val stateText = when (progress.state) {
        CacheState.PENDING -> "准备加载..."
        CacheState.DOWNLOADING, CacheState.EXTRACTING -> "正在加载..."
        CacheState.DOWNLOADED -> "正在解压..."
        CacheState.READY -> "加载完成！"
        CacheState.FAILED -> "加载失败"
    }

    val showProgress = progress.totalBytes > 0 && progress.state != CacheState.FAILED && progress.state != CacheState.READY
    val progressValue = if (showProgress) progress.downloadedBytes.toFloat() / progress.totalBytes.toFloat() else 0f

    AlertDialog(
        onDismissRequest = {
            if (progress.state == CacheState.FAILED) onDismiss()
        },
        title = {
            Text(progress.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column {
                Text(stateText)
                if (showProgress) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { progressValue }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${FormatUtils.formatBytes(progress.downloadedBytes)} / ${FormatUtils.formatBytes(progress.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (progress.state == CacheState.FAILED && progress.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        progress.error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (progress.state == CacheState.DOWNLOADING || progress.state == CacheState.EXTRACTING || progress.state == CacheState.PENDING) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("请稍候...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            if (progress.state == CacheState.FAILED || progress.state == CacheState.READY) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            } else {
                TextButton(onClick = onDismiss) { Text("后台加载") }
            }
        }
    )
}

@Composable
private fun EmptyLibrary() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.AutoStories, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("还没有漫画", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("去文件源页面添加一个本地或 SMB 源", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ComicGridItem(row: ComicRow, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
            ) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(row.comic.coverPath?.let { File(it) })
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp)) } },
                    error = {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.AutoStories, null, modifier = Modifier.size(40.dp))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                row.cache?.let { cache ->
                    when (cache.state) {
                        CacheState.DOWNLOADING, CacheState.EXTRACTING -> {
                            Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.BottomEnd) {
                                Surface(color = Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(50)) {
                                    Icon(Icons.Outlined.CloudDownload, null, tint = Color.White, modifier = Modifier.padding(6.dp).size(16.dp))
                                }
                            }
                        }
                        CacheState.PENDING -> {
                            Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.BottomEnd) {
                                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50)) {
                                    Icon(Icons.Outlined.CloudDownload, null, tint = Color.White, modifier = Modifier.padding(6.dp).size(16.dp))
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            }
            Column(Modifier.padding(8.dp)) {
                Text(
                    row.comic.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    FormatUtils.formatBytes(row.comic.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
