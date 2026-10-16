package com.tilt.wallpapers.ui.screens

import com.tilt.wallpapers.data.t

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tilt.wallpapers.data.AppState
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.WallSet
import com.tilt.wallpapers.ui.GlassLayer
import com.tilt.wallpapers.ui.LocalNav
import com.tilt.wallpapers.ui.Route
import com.tilt.wallpapers.ui.components.AppButton
import com.tilt.wallpapers.ui.components.BtnStyle
import com.tilt.wallpapers.ui.components.ConfirmDeleteDialog
import com.tilt.wallpapers.ui.components.EditedImage
import com.tilt.wallpapers.ui.components.GlassIconButton
import com.tilt.wallpapers.ui.components.GlassSurface
import com.tilt.wallpapers.ui.components.NameDialog
import com.tilt.wallpapers.ui.components.TiltPreview
import com.tilt.wallpapers.ui.components.bounce
import com.tilt.wallpapers.ui.components.bounceClick
import com.tilt.wallpapers.ui.components.pressGestures
import com.tilt.wallpapers.ui.components.screenAspect
import com.tilt.wallpapers.wallpaper.TiltWallpaperService

@Composable
fun SetsScreen() {
    val nav = LocalNav.current
    val state by Repo.state.collectAsStateWithLifecycle()
    val hf = LocalHapticFeedback.current
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }
    val aspect = screenAspect()
    val selecting = selection.isNotEmpty()
    BackHandler(selecting) { selection = emptySet() }
    BackHandler(fabExpanded && !selecting) { fabExpanded = false }
    fun toggle(id: String) { selection = if (id in selection) selection - id else selection + id }
    // The undo notices move up above the selection bar while it shows.
    androidx.compose.runtime.LaunchedEffect(selecting) {
        com.tilt.wallpapers.ui.components.Notices.lift = if (selecting) com.tilt.wallpapers.ui.components.BOTTOM_BAR_LIFT else 0.dp
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.tilt.wallpapers.ui.components.Notices.lift = 0.dp } }

    val asList = state.settings.setsList
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // List view: drag a row by its handle to reorder. [dragTop] is where the finger wants the row's top
    // (viewport y); the row is drawn there whatever its slot, so it never lags or jumps when it swaps.
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragTop by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val setIds by androidx.compose.runtime.rememberUpdatedState(state.sets.map { it.id })
    fun slotTop(key: String?) = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset?.y?.toFloat()
    // Letting go glides the row into its slot instead of snapping.
    var settleKey by remember { mutableStateOf<String?>(null) }
    val settle = remember { androidx.compose.animation.core.Animatable(0f) }
    val dragScope = androidx.compose.runtime.rememberCoroutineScope()
    fun endDrag() {
        val key = dragKey ?: return
        val off = dragTop - (slotTop(key) ?: dragTop)
        dragKey = null
        dragScope.launch {
            settle.snapTo(off); settleKey = key
            settle.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 500f))
            settleKey = null
        }
    }
    fun onDragged() {
        val key = dragKey ?: return
        val visible = gridState.layoutInfo.visibleItemsInfo
        val cur = visible.firstOrNull { it.key == key } ?: return
        val from = setIds.indexOf(key)
        // Wait until the layout shows the last move, or stale positions would swap twice.
        if (cur.index != SETS_HEADER_ITEMS + from) return
        val center = dragTop + cur.size.height / 2f
        val next = visible.firstOrNull { it.index == cur.index + 1 && it.key in setIds }
        val prev = visible.firstOrNull { it.index == cur.index - 1 && it.key in setIds }
        val to = when {
            next != null && center > next.offset.y + next.size.height / 2f -> from + 1
            prev != null && center < prev.offset.y + prev.size.height / 2f -> from - 1
            else -> return
        }
        Repo.moveSet(from, to)
        hf.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    GlassLayer(content = {
        LazyVerticalGrid(
            state = gridState,
            columns = if (asList) GridCells.Fixed(1) else GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = statusTop() + 12.dp, bottom = 190.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(if (asList) 10.dp else 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ScreenHeader("Tilt", t("Nghiêng máy để đổi hình nền"), horizontalPadding = 4.dp)
            }
            item(span = { GridItemSpan(maxLineSpan) }) { ActiveCard(state, aspect) { creating = true } }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Bộ ảnh của bạn"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (state.sets.isNotEmpty()) Pill("${state.sets.size}", Icons.Filled.Collections)
                    if (state.sets.isNotEmpty()) androidx.compose.material3.IconButton(onClick = {
                        hf.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        Repo.updateSettings { it.copy(setsList = !it.setsList) }
                    }) {
                        androidx.compose.animation.Crossfade(asList, label = "viewMode") { list ->
                            Icon(
                                if (list) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                                if (list) t("Xem dạng lưới") else t("Xem dạng danh sách"),
                            )
                        }
                    }
                }
            }
            items(state.sets, key = { it.id }) { set ->
                val onClick = { if (selecting) toggle(set.id) else nav.push(Route.SetDetail(set.id)) }
                val onLong = { hf.performHapticFeedback(HapticFeedbackType.LongPress); toggle(set.id) }
                if (asList) {
                    val dragging = dragKey == set.id
                    SetRow(
                        set = set,
                        aspect = aspect,
                        active = set.id == state.activeSet?.id,
                        next = nextFor(state, set.id),
                        selected = set.id in selection,
                        selecting = selecting,
                        dragging = dragging,
                        modifier = (if (dragging) Modifier.zIndex(1f) else Modifier.animateItem())
                            .graphicsLayer {
                                translationY = when {
                                    dragging -> dragTop - (slotTop(set.id) ?: dragTop)
                                    settleKey == set.id -> settle.value
                                    else -> 0f
                                }
                            }
                            .then(if (settleKey == set.id) Modifier.zIndex(1f) else Modifier),
                        handle = Modifier.pointerInput(set.id) {
                            detectDragGestures(
                                onDragStart = {
                                    hf.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    dragTop = slotTop(set.id) ?: 0f; dragKey = set.id
                                },
                                onDragEnd = { endDrag() },
                                onDragCancel = { endDrag() },
                            ) { change, amount -> change.consume(); dragTop += amount.y; onDragged() }
                        },
                        onClick = onClick,
                        onLongPress = onLong,
                    )
                } else SetCard(
                    set = set,
                    aspect = aspect,
                    active = set.id == state.activeSet?.id,
                    selected = set.id in selection,
                    selecting = selecting,
                    modifier = Modifier.animateItem(),
                    onClick = onClick,
                    onLongPress = onLong,
                )
            }
        }
    }) {
        AnimatedVisibility(
            visible = selecting,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            GlassTopBar(title = t("Đã chọn %1\$s", selection.size), onBack = null) {
                GlassIconButton(Icons.Filled.Close, t("Bỏ chọn")) { selection = emptySet() }
            }
        }
        AnimatedVisibility(
            visible = selecting,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
        ) {
            GlassSurface(shape = CircleShape) {
                Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppButton(t("Chọn tất cả"), icon = Icons.Filled.SelectAll, style = BtnStyle.Text) { selection = state.sets.map { it.id }.toSet() }
                    AppButton(t("Xóa"), icon = Icons.Filled.Delete, style = BtnStyle.Danger) { confirmDelete = true }
                }
            }
        }
        AnimatedVisibility(
            visible = !selecting,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 96.dp),
        ) {
            FabMenu(
                expanded = fabExpanded,
                onToggle = { fabExpanded = !fabExpanded },
                items = listOf(
                    FabItem(t("Nhập từ file"), Icons.Filled.FolderZip) { importing = true; fabExpanded = false },
                    FabItem(t("Bộ mới"), Icons.Filled.Add) { creating = true; fabExpanded = false },
                ),
            )
        }
    }

    if (creating) NameDialog(t("Bộ ảnh mới"), "", t("Tạo"), onConfirm = { nav.push(Route.SetDetail(Repo.createSet(it))) }, onDismiss = { creating = false })
    if (confirmDelete) {
        val n = selection.size
        val images = state.sets.filter { it.id in selection }.sumOf { it.images.size }
        ConfirmDeleteDialog(
            title = if (n == state.sets.size) t("Xóa tất cả %1\$s bộ ảnh?", n) else t("Xóa %1\$s bộ ảnh?", n),
            message = t("%1\$s ảnh trong các bộ này sẽ bị xóa. Có thể khôi phục trong Thùng rác 7 ngày.", images),
            onConfirm = { Repo.deleteSets(selection); selection = emptySet() },
            onDismiss = { confirmDelete = false },
        )
    }
    if (importing) {
        com.tilt.wallpapers.ui.components.ImportSetDialog(
            onDismiss = { importing = false },
            onSuccess = { newId -> nav.push(Route.SetDetail(newId)) }
        )
    }
}

private class FabItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * "+" button that turns slowly into "x" and unfolds its actions one by one from the bottom up
 * (folding back top first). Tapping outside is handled by the caller closing [expanded].
 */
@Composable
private fun FabMenu(expanded: Boolean, onToggle: () -> Unit, items: List<FabItem>) {
    val hf = LocalHapticFeedback.current
    val turn by animateFloatAsState(
        if (expanded) 135f else 0f,
        tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "fabTurn",
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End) {
        items.forEachIndexed { i, item ->
            val fromBottom = items.lastIndex - i
            val inDelay = 40 + fromBottom * 70
            val outDelay = i * 45
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(220, delayMillis = inDelay)) +
                    slideInVertically(spring(dampingRatio = 0.7f, stiffness = 500f)) { it / 2 } +
                    scaleIn(tween(260, delayMillis = inDelay), initialScale = 0.85f, transformOrigin = TransformOrigin(1f, 1f)),
                exit = fadeOut(tween(140, delayMillis = outDelay)) +
                    slideOutVertically(tween(180, delayMillis = outDelay)) { it / 3 } +
                    scaleOut(tween(180, delayMillis = outDelay), targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 1f)),
            ) {
                GlassSurface(
                    Modifier.height(48.dp).bounceClick(haptic = true, onClick = item.onClick),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(item.icon, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(item.label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        androidx.compose.material3.FloatingActionButton(
            onClick = {
                hf.performHapticFeedback(if (expanded) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                onToggle()
            },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            interactionSource = interaction,
            modifier = Modifier.bounce(interaction, 0.92f),
        ) {
            // One "+" turned by 135°: reads as "x" when open, and the slow turn is the animation.
            Icon(Icons.Filled.Add, if (expanded) t("Đóng") else t("Thêm"), Modifier.size(28.dp).graphicsLayer { rotationZ = turn })
        }
    }
}

@Composable
private fun ActiveCard(state: AppState, aspect: Float, onCreate: () -> Unit) {
    val set = state.activeSet
    val context = LocalContext.current
    val status = rememberWallpaperStatus()
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(cs.surfaceContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(118.dp).aspectRatio(aspect).clip(RoundedCornerShape(22.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (set != null && set.images.isNotEmpty()) TiltPreview(Modifier.fillMaxSize(), set.id)
            else Icon(Icons.Filled.Wallpaper, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (set == null) {
                Text(t("Bắt đầu"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                Text(t("Tạo bộ ảnh đầu tiên rồi thêm ảnh trái, giữa, phải."), style = MaterialTheme.typography.bodyMedium)
                AppButton(t("Tạo bộ ảnh"), icon = Icons.Filled.Add, onClick = onCreate)
            } else {
                Text(t("ĐANG DÙNG"), style = MaterialTheme.typography.labelMedium, color = cs.primary)
                Text(set.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t("%1\$s ảnh · %2\$s", set.images.size, state.settings.effect.label), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                NextLine(state, Modifier)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(t("Chính"), Icons.Filled.PhoneAndroid, selected = status.home)
                    if (status.lock != null) Pill(t("Khóa"), Icons.Filled.Lock, selected = status.lock)
                }
                AppButton(
                    if (status.home) t("Đặt lại") else t("Đặt hình nền"),
                    icon = Icons.Filled.Wallpaper,
                    style = if (status.home) BtnStyle.Tonal else BtnStyle.Filled,
                ) { TiltWallpaperService.launchPicker(context) }
            }
        }
    }
}

@Composable
private fun SetCard(
    set: WallSet,
    aspect: Float,
    active: Boolean,
    selected: Boolean,
    selecting: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val pressed = remember { mutableStateOf(false) }
    val scale = cardScale(pressed.value, selected)
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .scaled(scale)
            .pressGestures(set.id, pressed, onTap = onClick, onLongPressStart = onLongPress, onLongPressEnd = {})
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.8f)
                .clip(RoundedCornerShape(28.dp))
                .background(cs.surfaceContainerHigh)
                .then(if (selected) Modifier.border(3.dp, cs.primary, RoundedCornerShape(28.dp)) else Modifier),
        ) {
            FanCover(set, aspect)
            if (active) GlassSurface(Modifier.align(Alignment.TopStart).padding(10.dp).height(28.dp), shape = CircleShape) {
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp), tint = cs.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(t("Đang dùng"), style = MaterialTheme.typography.labelSmall)
                }
            }
            androidx.compose.animation.AnimatedVisibility(selecting, Modifier.align(Alignment.TopEnd).padding(10.dp), enter = scaleIn(), exit = scaleOut()) {
                SelectMark(selected)
            }
        }
        Text(set.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp, top = 8.dp))
        Text(t("%1\$s ảnh", set.images.size), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Items above the first set in the Bộ ảnh grid: header, active card, "Bộ ảnh của bạn" row. */
private const val SETS_HEADER_ITEMS = 3

/** "19:00" when the schedule's next slot switches to [setId]. */
@Composable
private fun nextFor(state: AppState, setId: String): String? {
    if (!state.settings.scheduleEnabled || state.activeSet?.id == setId) return null
    val next = com.tilt.wallpapers.data.Automation.next(state.settings.schedules) ?: return null
    if (next.first.setId != setId) return null
    return "%02d:%02d".format(next.first.hour, next.first.minute)
}

/** One set as a row of the list view: fanned cover, name, count, status; a handle to drag it. */
@Composable
private fun SetRow(
    set: WallSet,
    aspect: Float,
    active: Boolean,
    next: String?,
    selected: Boolean,
    selecting: Boolean,
    dragging: Boolean,
    modifier: Modifier,
    handle: Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val pressed = remember { mutableStateOf(false) }
    val scale = cardScale(pressed.value, selected)
    val lift by animateFloatAsState(if (dragging) 1.03f else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "lift")
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .scaled(scale)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .shadow(if (dragging) 12.dp else 0.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(if (dragging) cs.surfaceContainerHighest else cs.surfaceContainerHigh)
            .then(if (selected) Modifier.border(3.dp, cs.primary, RoundedCornerShape(24.dp)) else Modifier)
            .pressGestures(set.id, pressed, onTap = onClick, onLongPressStart = onLongPress, onLongPressEnd = {})
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(76.dp).height(84.dp)) { FanCover(set, aspect) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(set.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t("%1\$s ảnh", set.images.size), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (active) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp), tint = cs.primary)
                Spacer(Modifier.width(4.dp))
                Text(t("Đang dùng"), style = MaterialTheme.typography.labelSmall, color = cs.primary)
            } else if (next != null) Text(t("Tiếp theo lúc %1\$s", next), style = MaterialTheme.typography.labelSmall, color = cs.primary)
        }
        androidx.compose.animation.AnimatedContent(selecting, label = "rowEnd") { sel ->
            if (sel) Box(Modifier.padding(8.dp)) { SelectMark(selected) }
            else Icon(
                Icons.Filled.DragHandle, t("Kéo để sắp xếp"),
                handle.padding(12.dp), tint = cs.onSurfaceVariant,
            )
        }
    }
}

/** Neutral image in front with its left/right neighbours fanned behind — a hint of the tilt. */
@Composable
private fun FanCover(set: WallSet, aspect: Float) {
    val (seq, neutral) = remember(set.images) { set.tiltSequence() }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val w = maxWidth * 0.5f
        if (seq.isEmpty()) {
            Icon(Icons.Filled.Collections, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(40.dp))
            return@BoxWithConstraints
        }
        listOf(-1, 1, 0).forEach { d ->
            val img = seq.getOrNull(neutral + d) ?: return@forEach
            EditedImage(
                img,
                Modifier
                    .offset(x = maxWidth * 0.2f * d, y = if (d == 0) 0.dp else 6.dp)
                    .graphicsLayer { rotationZ = 9f * d; val s = if (d == 0) 1f else 0.9f; scaleX = s; scaleY = s }
                    .width(w)
                    .aspectRatio(aspect)
                    .shadow(if (d == 0) 10.dp else 4.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp)),
            )
        }
    }
}

@Composable
fun SelectMark(selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (selected) cs.primary else Color.Black.copy(alpha = 0.25f))
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.CheckCircle, null, tint = cs.onPrimary, modifier = Modifier.size(22.dp))
    }
}
