package com.kaguya.comicsviewer.ui.reader

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.kaguya.comicsviewer.domain.model.ReadingMode
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File

@Composable
fun ReaderScreen(
    comicId: Long,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = context as? ComponentActivity

    // 保持屏幕常亮
    DisposableEffect(state.keepScreenOn) {
        activity?.window?.let { w ->
            if (state.keepScreenOn) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    var showOverlay by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            state.isLoading -> LoadingState()
            state.error != null -> ErrorState(error = state.error!!, onRetry = viewModel::retry, onBack = onBack)
            state.pages.isEmpty() -> EmptyState()
            else -> {
                when (state.mode) {
                    ReadingMode.PAGED -> PagedReader(
                        state = state,
                        onPageChange = viewModel::goTo,
                        onTap = { showOverlay = !showOverlay }
                    )
                    ReadingMode.CONTINUOUS, ReadingMode.WEBTOON -> ContinuousReader(
                        state = state,
                        onPageChange = viewModel::goTo,
                        onTap = { showOverlay = !showOverlay }
                    )
                }
            }
        }

        if (showOverlay && state.pages.isNotEmpty() && !state.isLoading) {
            ReaderTopBar(
                title = state.comic?.title.orEmpty(),
                page = state.page + 1,
                total = state.pages.size,
                mode = state.mode,
                onBack = onBack,
                onModeChange = viewModel::setMode,
                onPageInput = viewModel::goTo
            )
        }
    }
}

@Composable
private fun PagedReader(
    state: ReaderUiState,
    onPageChange: (Int) -> Unit,
    onTap: () -> Unit
) {
    if (state.pages.isEmpty()) {
        EmptyState()
        return
    }
    val pagerState = rememberPagerState(
        initialPage = state.page.coerceIn(0, state.pages.size - 1)
    ) { state.pages.size }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { p ->
            onPageChange(p)
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            }
    ) { pageIndex ->
        PageView(state.pages[pageIndex].path)
    }
}

@Composable
private fun ContinuousReader(
    state: ReaderUiState,
    onPageChange: (Int) -> Unit,
    onTap: () -> Unit
) {
    if (state.pages.isEmpty()) {
        EmptyState()
        return
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.page)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { p ->
            onPageChange(p)
        }
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            }
    ) {
        items(state.pages, key = { it.path }) { p ->
            PageView(p.path)
        }
    }
}

@Composable
private fun PageView(path: String) {
    Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.Center) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(File(path))
                .crossfade(false)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            loading = { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp), color = Color.White.copy(alpha = 0.5f)) },
            error = { Text("加载失败", color = Color.White) },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("未找到页面", color = Color.White)
            Spacer(Modifier.size(8.dp))
            Text("请先在漫画库等待下载与解压完成", color = Color.White.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(40.dp))
            Spacer(Modifier.size(12.dp))
            Text("加载中...", color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun ErrorState(error: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("加载失败", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            Text(error, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f))
                ) {
                    Text("返回", color = Color.White)
                }
                Button(onClick = onRetry) {
                    Text("重试")
                }
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    page: Int,
    total: Int,
    mode: ReadingMode,
    onBack: () -> Unit,
    onModeChange: (ReadingMode) -> Unit,
    onPageInput: (Int) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        // 顶部半透明栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = Color.White)
                }
                Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onModeChange(if (mode == ReadingMode.PAGED) ReadingMode.CONTINUOUS else ReadingMode.PAGED) }) {
                    Icon(Icons.Outlined.SwapHoriz, null, tint = Color.White)
                }
            }
        }
        // 底部页码
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "第 $page / $total 页",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
