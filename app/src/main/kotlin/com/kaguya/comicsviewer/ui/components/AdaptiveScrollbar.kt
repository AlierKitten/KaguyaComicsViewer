package com.kaguya.comicsviewer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SCROLLBAR_WIDTH = 10.dp
private val SCROLLBAR_THUMB_WIDTH = 6.dp
private val MIN_THUMB_HEIGHT = 40.dp

@Composable
fun AdaptiveScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var dragAccum by remember { mutableStateOf(0f) }
    var dragStartTop by remember { mutableStateOf(0f) }

    val layoutInfo = state.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val totalItemsCount = layoutInfo.totalItemsCount
    val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(0)

    if (visibleItems.isEmpty() || totalItemsCount <= visibleItems.size || viewportHeight <= 0) return

    var total = 0
    for (item in visibleItems) total += item.size
    val averageItemHeight = (total.toFloat() / visibleItems.size).coerceAtLeast(1f)

    val totalContentHeight = averageItemHeight * totalItemsCount
    val totalScrollable = (totalContentHeight - viewportHeight).coerceAtLeast(1f)
    val thumbHeightPx = (viewportHeight / totalContentHeight * viewportHeight)
        .coerceAtLeast(MIN_THUMB_HEIGHT.value * density.density)
    val maxThumbTop = (viewportHeight - thumbHeightPx).coerceAtLeast(0f)

    val scrollOffsetPx = state.firstVisibleItemIndex * averageItemHeight + state.firstVisibleItemScrollOffset
    val thumbTopPx = (scrollOffsetPx / totalScrollable * maxThumbTop).coerceIn(0f, maxThumbTop)
    val effectiveTopPx = if (dragging) (dragStartTop + dragAccum).coerceIn(0f, maxThumbTop) else thumbTopPx

    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dragging) 0.85f else 0.5f)
    val thumbHeightDp = Dp(thumbHeightPx / density.density)
    val thumbTopDp = Dp(effectiveTopPx / density.density)

    Box(
        modifier = modifier
            .width(SCROLLBAR_WIDTH)
            .fillMaxHeight()
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    dragAccum += delta
                    val newTop = (dragStartTop + dragAccum).coerceIn(0f, maxThumbTop)
                    val ratio = if (maxThumbTop > 0f) newTop / maxThumbTop else 0f
                    val targetOffset = (ratio * totalScrollable).coerceIn(0f, totalScrollable)
                    val targetIndex = (targetOffset / averageItemHeight).toInt()
                    val targetInner = (targetOffset - targetIndex * averageItemHeight).toInt()
                    scope.launch { state.scrollToItem(targetIndex, targetInner) }
                },
                onDragStarted = {
                    dragging = true
                    dragAccum = 0f
                    dragStartTop = thumbTopPx
                },
                onDragStopped = {
                    dragging = false
                    dragAccum = 0f
                }
            )
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(SCROLLBAR_THUMB_WIDTH)
                .offset { IntOffset(0, (thumbTopDp.value * density.density).roundToInt()) }
                .height(thumbHeightDp)
                .background(thumbColor, RoundedCornerShape(SCROLLBAR_THUMB_WIDTH / 2))
        )
    }
}

@Composable
fun AdaptiveScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var dragAccum by remember { mutableStateOf(0f) }
    var dragStartTop by remember { mutableStateOf(0f) }

    val layoutInfo = state.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val totalItemsCount = layoutInfo.totalItemsCount
    val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(0)

    if (visibleItems.isEmpty() || totalItemsCount <= visibleItems.size || viewportHeight <= 0) return

    var total = 0
    for (item in visibleItems) total += item.size.height
    val averageItemHeight = (total.toFloat() / visibleItems.size).coerceAtLeast(1f)

    val totalContentHeight = averageItemHeight * totalItemsCount
    val totalScrollable = (totalContentHeight - viewportHeight).coerceAtLeast(1f)
    val thumbHeightPx = (viewportHeight / totalContentHeight * viewportHeight)
        .coerceAtLeast(MIN_THUMB_HEIGHT.value * density.density)
    val maxThumbTop = (viewportHeight - thumbHeightPx).coerceAtLeast(0f)

    val scrollOffsetPx = state.firstVisibleItemIndex * averageItemHeight + state.firstVisibleItemScrollOffset
    val thumbTopPx = (scrollOffsetPx / totalScrollable * maxThumbTop).coerceIn(0f, maxThumbTop)
    val effectiveTopPx = if (dragging) (dragStartTop + dragAccum).coerceIn(0f, maxThumbTop) else thumbTopPx

    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dragging) 0.85f else 0.5f)
    val thumbHeightDp = Dp(thumbHeightPx / density.density)
    val thumbTopDp = Dp(effectiveTopPx / density.density)

    Box(
        modifier = modifier
            .width(SCROLLBAR_WIDTH)
            .fillMaxHeight()
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    dragAccum += delta
                    val newTop = (dragStartTop + dragAccum).coerceIn(0f, maxThumbTop)
                    val ratio = if (maxThumbTop > 0f) newTop / maxThumbTop else 0f
                    val targetOffset = (ratio * totalScrollable).coerceIn(0f, totalScrollable)
                    val targetIndex = (targetOffset / averageItemHeight).toInt()
                    val targetInner = (targetOffset - targetIndex * averageItemHeight).toInt()
                    scope.launch { state.scrollToItem(targetIndex, targetInner) }
                },
                onDragStarted = {
                    dragging = true
                    dragAccum = 0f
                    dragStartTop = thumbTopPx
                },
                onDragStopped = {
                    dragging = false
                    dragAccum = 0f
                }
            )
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(SCROLLBAR_THUMB_WIDTH)
                .offset { IntOffset(0, (thumbTopDp.value * density.density).roundToInt()) }
                .height(thumbHeightDp)
                .background(thumbColor, RoundedCornerShape(SCROLLBAR_THUMB_WIDTH / 2))
        )
    }
}
