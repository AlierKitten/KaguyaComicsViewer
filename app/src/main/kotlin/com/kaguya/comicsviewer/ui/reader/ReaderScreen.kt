package com.kaguya.comicsviewer.ui.reader

import com.kaguya.comicsviewer.R
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.kaguya.comicsviewer.domain.model.ComicPage
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
    var showJumpDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            state.isLoading -> LoadingState()
            state.error != null -> ErrorState(error = state.error!!, onRetry = viewModel::retry, onBack = onBack)
            state.pages.isEmpty() -> EmptyState(onRetry = viewModel::retry, onBack = onBack)
            else -> {
                when (state.mode) {
                    ReadingMode.PAGED -> PagedReader(
                        state = state,
                        viewModel = viewModel,
                        onPageChange = viewModel::goTo,
                        onTap = { showOverlay = !showOverlay },
                        zoomEnabled = showOverlay
                    )
                    ReadingMode.CONTINUOUS -> VerticalPagedReader(
                        state = state,
                        viewModel = viewModel,
                        onPageChange = viewModel::goTo,
                        onTap = { showOverlay = !showOverlay },
                        zoomEnabled = showOverlay
                    )
                    ReadingMode.WEBTOON -> ContinuousReader(
                        state = state,
                        viewModel = viewModel,
                        onPageChange = viewModel::goTo,
                        onTap = { showOverlay = !showOverlay }
                    )
                }
            }
        }

        if (showOverlay && state.pages.isNotEmpty() && !state.isLoading) {
            // 顶部半透明栏
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = Color.White)
                    }
                    Text(
                        state.comic?.title.orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val nextMode = when (state.mode) {
                            ReadingMode.PAGED -> ReadingMode.CONTINUOUS
                            ReadingMode.CONTINUOUS -> ReadingMode.WEBTOON
                            ReadingMode.WEBTOON -> ReadingMode.PAGED
                        }
                        viewModel.setMode(nextMode)
                        val modeName = when (nextMode) {
                            ReadingMode.PAGED -> context.getString(R.string.mode_paged)
                            ReadingMode.CONTINUOUS -> context.getString(R.string.mode_continuous)
                            ReadingMode.WEBTOON -> context.getString(R.string.mode_webtoon)
                        }
                        Toast.makeText(context, context.getString(R.string.current_mode, modeName), Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Outlined.SwapHoriz, null, tint = Color.White)
                    }
                }
            }
            // 底部页码（点击可跳转）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { showJumpDialog = true }
                    .padding(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.page_indicator_click, state.page + 1, state.pages.size),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    if (showJumpDialog) {
        JumpDialog(
            total = state.pages.size,
            current = state.page + 1,
            onDismiss = { showJumpDialog = false },
            onConfirm = { target ->
                viewModel.jumpTo(target - 1)
                showJumpDialog = false
            }
        )
    }
}

@Composable
private fun JumpDialog(
    total: Int,
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(current.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.jump_title)) },
        text = {
            Column {
                Text(stringResource(R.string.jump_total, total), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val target = text.toIntOrNull()?.coerceIn(1, total) ?: current
                onConfirm(target)
            }) { Text(stringResource(R.string.jump)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun PagedReader(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    onPageChange: (Int) -> Unit,
    onTap: () -> Unit,
    zoomEnabled: Boolean
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
    // 响应跳转事件
    LaunchedEffect(Unit) {
        viewModel.jumpEvent.collect { target ->
            pagerState.scrollToPage(target)
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
        PageView(state.pages[pageIndex], zoomEnabled = zoomEnabled)
    }
}

@Composable
private fun VerticalPagedReader(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    onPageChange: (Int) -> Unit,
    onTap: () -> Unit,
    zoomEnabled: Boolean
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
    // 响应跳转事件
    LaunchedEffect(Unit) {
        viewModel.jumpEvent.collect { target ->
            pagerState.scrollToPage(target)
        }
    }

    VerticalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            }
    ) { pageIndex ->
        PageView(state.pages[pageIndex], zoomEnabled = zoomEnabled)
    }
}

@Composable
private fun ContinuousReader(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
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
    // 响应跳转事件
    LaunchedEffect(Unit) {
        viewModel.jumpEvent.collect { target ->
            listState.scrollToItem(target)
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
        items(state.pages, key = { it.index }) { p ->
            PageView(p)
        }
    }
}

@Composable
private fun PageView(page: ComicPage, zoomEnabled: Boolean = false) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // 遮罩隐藏时重置缩放和平移
    LaunchedEffect(zoomEnabled) {
        if (!zoomEnabled) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    val model: Any = if (page.isArchive) {
        ArchiveImage(page)
    } else {
        ImageRequest.Builder(LocalContext.current)
            .data(File(page.path!!))
            .crossfade(false)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .pointerInput(zoomEnabled) {
                if (zoomEnabled) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offset = offset + pan
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            loading = { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp), color = Color.White.copy(alpha = 0.5f)) },
            error = { Text(stringResource(R.string.load_failed), color = Color.White) },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}

@Composable
private fun EmptyState(onRetry: () -> Unit = {}, onBack: () -> Unit = {}) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.no_pages), color = Color.White)
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.no_pages_hint),
                color = Color.White.copy(alpha = 0.6f)
            )
            Spacer(Modifier.size(16.dp))
            Row {
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.back), color = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(40.dp))
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.loading), color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun ErrorState(error: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.load_failed), color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            Text(error, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f))
                ) {
                    Text(stringResource(R.string.back), color = Color.White)
                }
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}


