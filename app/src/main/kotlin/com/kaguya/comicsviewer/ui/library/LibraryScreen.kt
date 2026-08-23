package com.kaguya.comicsviewer.ui.library

import com.kaguya.comicsviewer.R
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.kaguya.comicsviewer.domain.model.CacheState
import com.kaguya.comicsviewer.domain.model.ComicSortField
import com.kaguya.comicsviewer.ui.components.AdaptiveScrollbar
import com.kaguya.comicsviewer.util.FormatUtils
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    nav: NavController,
    scrollToTopSignal: Long = 0L,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loadingProgress by viewModel.loadingProgress.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showSortMenu by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 点击底部导航已选中的"漫画库"时，自动滚动到顶部
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal > 0) {
            when (state.displayMode) {
                LibraryDisplayMode.GRID -> scope.launch { gridState.scrollToItem(0) }
                LibraryDisplayMode.LIST -> scope.launch { listState.scrollToItem(0) }
            }
        }
    }

    // Toast 事件监听
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { event ->
            val msg = if (event.args.isEmpty()) context.getString(event.resId)
            else context.getString(event.resId, *event.args)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // 当加载完成(READY)时自动跳转阅读器，不弹 dialog
    LaunchedEffect(loadingProgress?.state) {
        if (loadingProgress?.state == CacheState.READY) {
            val comicId = loadingProgress!!.comicId
            viewModel.dismissLoading()
            nav.navigate("reader/$comicId")
        }
    }

    // 加载进度弹窗（READY 不弹，失败/加载中时弹出）
    loadingProgress?.let { lp ->
        when (lp.state) {
            CacheState.DOWNLOADING, CacheState.EXTRACTING, CacheState.DOWNLOADED,
            CacheState.PENDING, CacheState.FAILED -> {
                LoadingDialog(
                    progress = lp,
                    onDismiss = { viewModel.dismissLoading() },
                    onCancel = { viewModel.cancelLoading(lp.comicId) }
                )
            }
            else -> Unit // READY 不弹 dialog
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_library)) },
                actions = {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.Outlined.SortByAlpha, contentDescription = stringResource(R.string.sort))
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        val fieldLabel = mapOf(
                            ComicSortField.NAME to stringResource(R.string.sort_name),
                            ComicSortField.SIZE to stringResource(R.string.sort_size),
                            ComicSortField.DATE to stringResource(R.string.sort_date)
                        )
                        ComicSortField.entries.forEach { field ->
                            DropdownMenuItem(
                                text = { Text(fieldLabel[field] ?: field.name) },
                                trailingIcon = {
                                    if (state.sortField == field) {
                                        Icon(
                                            if (state.sortAscending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                                            contentDescription = null
                                        )
                                    }
                                },
                                onClick = {
                                    if (state.sortField == field) {
                                        viewModel.toggleSortDirection()
                                    } else {
                                        viewModel.setSortField(field)
                                    }
                                }
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.toggleDisplayMode() }) {
                        Icon(
                            if (state.displayMode == LibraryDisplayMode.GRID) Icons.Outlined.ViewList else Icons.Outlined.ViewAgenda,
                            contentDescription = stringResource(R.string.toggle_view)
                        )
                    }
                    IconButton(onClick = { viewModel.scanAll() }) {
                        if (state.isScanning) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        else Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.scan))
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
                shape = RoundedCornerShape(12.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearQuery() }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "清空")
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.search_comics)) }
            )

            if (state.comics.isEmpty() && state.recent.isEmpty()) {
                EmptyLibrary()
            } else if (state.displayMode == LibraryDisplayMode.GRID) {
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(120.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.comics, key = { it.comic.id }) { row ->
                            ComicGridItem(row, showCover = state.showCovers, onClick = {
                                handleComicClick(row, nav, viewModel)
                            })
                        }
                    }
                    AdaptiveScrollbar(
                        state = gridState,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.comics, key = { it.comic.id }) { row ->
                            ComicListItem(row, showCover = state.showCovers, onClick = {
                                handleComicClick(row, nav, viewModel)
                            })
                        }
                    }
                    AdaptiveScrollbar(
                        state = listState,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
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
fun LoadingDialog(
    progress: LoadingProgress,
    onDismiss: () -> Unit,
    onCancel: () -> Unit = {}
) {
    val stateText = when (progress.state) {
        CacheState.PENDING -> stringResource(R.string.loading_title_pending)
        CacheState.DOWNLOADING, CacheState.EXTRACTING -> stringResource(R.string.loading_title_loading)
        CacheState.DOWNLOADED -> stringResource(R.string.loading_title_extracting)
        CacheState.READY -> stringResource(R.string.loading_title_done)
        CacheState.FAILED -> stringResource(R.string.loading_title_failed)
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
                        Text(stringResource(R.string.please_wait), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            if (progress.state == CacheState.FAILED || progress.state == CacheState.READY) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.background_loading)) }
            }
        },
        dismissButton = {
            if (progress.state == CacheState.DOWNLOADING || progress.state == CacheState.EXTRACTING || progress.state == CacheState.PENDING || progress.state == CacheState.DOWNLOADED) {
                TextButton(onClick = {
                    onCancel()
                    onDismiss()
                }) { Text(stringResource(R.string.cancel_loading), color = MaterialTheme.colorScheme.error) }
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
            Text(stringResource(R.string.empty_library), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.add_source_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ComicGridItem(row: ComicRow, showCover: Boolean, onClick: () -> Unit) {
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
                        .data(if (showCover) row.comic.coverPath?.let { File(it) } else null)
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        FormatUtils.formatBytes(row.comic.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (row.isFinished) {
                        Text(
                            stringResource(R.string.finished),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComicListItem(row: ComicRow, showCover: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 60.dp, height = 80.dp)
                    .clip(RoundedCornerShape(4.dp))
            ) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(if (showCover) row.comic.coverPath?.let { File(it) } else null)
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
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.AutoStories, null)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp)
            ) {
                Text(
                    row.comic.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        FormatUtils.formatBytes(row.comic.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (row.isFinished) {
                        Text(
                            stringResource(R.string.finished),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                row.cache?.let { cache ->
                    when (cache.state) {
                        CacheState.DOWNLOADING, CacheState.EXTRACTING -> {
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.cache_loading), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        CacheState.PENDING -> {
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.cache_waiting), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}
