package com.tilt.wallpapers.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/**
 * Horizontal list where a long press lifts an item so it can be dragged to a new position
 * (the item follows the finger, neighbours glide out of the way). A long press without moving
 * calls [onLongPress] instead.
 */
@Composable
fun <T> ReorderableRow(
    items: List<T>,
    key: (T) -> String,
    onMove: (from: Int, to: Int) -> Unit,
    onTap: (T) -> Unit,
    onLongPress: (T) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (item: T, index: Int, dragging: Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val hf = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val move by rememberUpdatedState(onMove)

    fun onDragged() {
        val info = listState.layoutInfo
        val cur = info.visibleItemsInfo.firstOrNull { it.key == draggingKey } ?: return
        val center = cur.offset + cur.size / 2f + dragOffset
        info.visibleItemsInfo.firstOrNull { it.key != draggingKey && center >= it.offset && center <= it.offset + it.size }?.let { target ->
            move(cur.index, target.index)
            hf.performHapticFeedback(HapticFeedbackType.SegmentTick)
            dragOffset -= (target.offset - cur.offset)
        }
        val auto = when {
            center > info.viewportEndOffset - edge -> 14f
            center < info.viewportStartOffset + edge -> -14f
            else -> 0f
        }
        if (auto != 0f) scope.launch { dragOffset += listState.scrollBy(auto) }
    }

    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items, key = { _, it -> key(it) }) { index, item ->
            val k = key(item)
            val dragging = k == draggingKey
            val pressed = remember { mutableStateOf(false) }
            val scale by animateFloatAsState(
                when { dragging -> 1.08f; pressed.value -> 0.94f; else -> 1f },
                spring(dampingRatio = 0.55f, stiffness = 600f), label = "lift",
            )
            Box(
                Modifier
                    .then(if (dragging) Modifier.zIndex(1f) else Modifier.animateItem())
                    .graphicsLayer {
                        translationX = if (dragging) dragOffset else 0f
                        scaleX = scale; scaleY = scale
                        rotationZ = if (dragging) (dragOffset / 60f).coerceIn(-4f, 4f) else 0f
                    }
                    .pressGestures(
                        key = k,
                        pressed = pressed,
                        onTap = { onTap(item) },
                        onLongPressStart = {
                            hf.performHapticFeedback(HapticFeedbackType.LongPress)
                            draggingKey = k; dragOffset = 0f
                        },
                        onDrag = { d -> dragOffset += d.x; onDragged() },
                        onLongPressEnd = { moved ->
                            if (!moved) onLongPress(item)
                            draggingKey = null; dragOffset = 0f
                        },
                    )
            ) { content(item, index, dragging) }
        }
    }
}
