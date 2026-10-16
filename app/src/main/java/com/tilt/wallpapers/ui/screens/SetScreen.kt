package com.tilt.wallpapers.ui.screens

import com.tilt.wallpapers.data.t

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.Side
import com.tilt.wallpapers.data.WallImage
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
import com.tilt.wallpapers.ui.components.ReorderableRow
import com.tilt.wallpapers.ui.components.bounceClick
import com.tilt.wallpapers.ui.components.screenAspect
import kotlinx.coroutines.launch

private sealed interface SetDialog {
    data object Rename : SetDialog
    data object DeleteSelected : SetDialog
    data object DeleteAllImages : SetDialog
    data object DeleteSet : SetDialog
}

fun Side.icon(): ImageVector = when (this) {
    Side.LEFT -> Icons.Filled.RotateLeft
    Side.CENTER -> Icons.Filled.StayCurrentPortrait
    Side.RIGHT -> Icons.Filled.RotateRight
}

@Composable
fun SetScreen(setId: String) {
    val nav = LocalNav.current
    val state by Repo.state.collectAsStateWithLifecycle()
    val set = state.sets.find { it.id == setId }
    if (set == null) { androidx.compose.runtime.LaunchedEffect(Unit) { nav.pop() }; return }
    val scope = rememberCoroutineScope()
    val aspect = screenAspect()
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var importing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pickSide by remember { mutableStateOf<Side?>(null) }
    var dialog by remember { mutableStateOf<SetDialog?>(null) }
    var menu by remember { mutableStateOf(false) }
    var moveMenu by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()
    val isActive = state.activeSet?.id == setId

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val side = pickSide
        scope.launch {
            importing = 0 to uris.size
            Repo.importImages(setId, uris, side) { importing = it to uris.size }
            importing = null
        }
    }
    fun pick(side: Side?) { pickSide = side; picker.launch("image/*") }
    fun toggle(id: String) { selection = if (id in selection) selection - id else selection + id }
    BackHandler(selecting) { selection = emptySet() }

    GlassLayer(content = {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = statusTop() + 72.dp, bottom = navBottom() + 120.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(
                        Modifier.bounceClick(pressedScale = 0.97f) { dialog = SetDialog.Rename },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(set.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.Edit, t("Đổi tên"), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    Text(t("%1\$s ảnh", set.images.size), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton(
                            if (isActive) t("Đang dùng") else t("Dùng bộ này"),
                            icon = Icons.Filled.CheckCircle,
                            style = if (isActive) BtnStyle.Tonal else BtnStyle.Filled,
                        ) { Repo.setActive(setId) }
                        AppButton(t("Xem thử"), icon = Icons.Filled.PlayArrow, style = BtnStyle.Outlined, enabled = set.images.isNotEmpty()) {
                            nav.push(Route.Preview(setId))
                        }
                    }
                }
            }
            item { TiltGuide() }
            Side.entries.forEach { side ->
                item(key = side.name) {
                    SideSection(
                        set = set, side = side, aspect = aspect, selection = selection,
                        onTap = { img -> if (selecting) toggle(img.id) else nav.push(Route.Viewer(setId, img.id)) },
                        onLongPress = { img -> toggle(img.id) },
                        onAdd = { pick(side) },
                    )
                }
            }
        }
    }) {
        // Top bar: normal / selection
        AnimatedContent(selecting, Modifier.align(Alignment.TopCenter), label = "topbar",
            transitionSpec = { (fadeIn() + slideInVertically { -it / 2 }) togetherWith fadeOut() }) { sel ->
            if (!sel) {
                GlassTopBar(title = null, onBack = { nav.pop() }) {
                    Box {
                        GlassIconButton(Icons.Filled.MoreVert, t("Thêm")) { menu = true }
                        DropdownMenu(menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
                            DropdownMenuItem({ Text(t("Đổi tên")) }, { menu = false; dialog = SetDialog.Rename }, leadingIcon = { Icon(Icons.Filled.Edit, null) })
                            DropdownMenuItem({ Text(t("Xuất bộ ảnh")) }, { menu = false; exporting = true }, leadingIcon = { Icon(Icons.Filled.FolderZip, null) }, enabled = set.images.isNotEmpty())
                            DropdownMenuItem({ Text(t("Chọn ảnh")) }, { menu = false; selection = set.images.map { it.id }.toSet() }, leadingIcon = { Icon(Icons.Filled.SelectAll, null) }, enabled = set.images.isNotEmpty())
                            DropdownMenuItem({ Text(t("Xóa tất cả ảnh")) }, { menu = false; dialog = SetDialog.DeleteAllImages }, leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) }, enabled = set.images.isNotEmpty())
                            DropdownMenuItem({ Text(t("Xóa bộ ảnh")) }, { menu = false; dialog = SetDialog.DeleteSet }, leadingIcon = { Icon(Icons.Filled.DeleteForever, null) })
                        }
                    }
                }
            } else {
                GlassTopBar(title = t("Đã chọn %1\$s/%2\$s", selection.size, set.images.size), onBack = null) {
                    GlassIconButton(Icons.Filled.Close, t("Bỏ chọn")) { selection = emptySet() }
                }
            }
        }

        // Bottom: selection actions / import progress / add button
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp)) {
            AnimatedContent(
                targetState = when { importing != null -> 2; selecting -> 1; else -> 0 },
                transitionSpec = { (scaleIn(initialScale = 0.8f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.8f) + fadeOut()) },
                label = "bottom",
            ) { mode ->
                when (mode) {
                    2 -> GlassSurface(Modifier.width(260.dp), shape = RoundedCornerShape(24.dp)) {
                        val (done, total) = importing ?: (0 to 1)
                        Column(Modifier.padding(16.dp)) {
                            Text(t("Đang thêm ảnh %1\$s/%2\$s", done, total), style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { done / total.toFloat() }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    1 -> GlassSurface(shape = CircleShape) {
                        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppButton(t("Tất cả"), icon = Icons.Filled.SelectAll, style = BtnStyle.Text) { selection = set.images.map { it.id }.toSet() }
                            Box {
                                AppButton(t("Chuyển"), icon = Icons.Filled.DriveFileMove, style = BtnStyle.Text) { moveMenu = true }
                                DropdownMenu(moveMenu, onDismissRequest = { moveMenu = false }, shape = RoundedCornerShape(20.dp)) {
                                    Side.entries.forEach { side ->
                                        DropdownMenuItem(
                                            { Text("Sang ${side.label.lowercase()}") },
                                            { moveMenu = false; Repo.moveImages(setId, selection, side); selection = emptySet() },
                                            leadingIcon = { Icon(side.icon(), null) },
                                        )
                                    }
                                }
                            }
                            AppButton(t("Xóa"), icon = Icons.Filled.Delete, style = BtnStyle.Danger) { dialog = SetDialog.DeleteSelected }
                        }
                    }
                    else -> GlassSurface(
                        Modifier.height(56.dp).bounceClick(squash = true) { pick(null) },
                        shape = CircleShape,
                        tint = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AddPhotoAlternate, null)
                            Spacer(Modifier.width(8.dp))
                            Text(t("Thêm ảnh"), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        SetDialog.Rename -> NameDialog(t("Đổi tên bộ ảnh"), set.name, t("Lưu"), onConfirm = { Repo.renameSet(setId, it) }, onDismiss = { dialog = null })
        SetDialog.DeleteSelected -> ConfirmDeleteDialog(
            title = t("Xóa %1\$s ảnh?", selection.size),
            message = if (selection.size == set.images.size) t("Tất cả ảnh trong bộ \"%1\$s\" sẽ bị xóa. Có thể khôi phục trong Thùng rác 7 ngày.", set.name) else t("Các ảnh đã chọn sẽ bị xóa khỏi bộ \"%1\$s\". Có thể khôi phục trong Thùng rác 7 ngày.", set.name),
            onConfirm = { Repo.deleteImages(setId, selection); selection = emptySet() },
            onDismiss = { dialog = null },
        )
        SetDialog.DeleteAllImages -> ConfirmDeleteDialog(
            title = t("Xóa tất cả %1\$s ảnh?", set.images.size),
            message = t("Bộ \"%1\$s\" sẽ được giữ lại nhưng không còn ảnh nào. Có thể khôi phục trong Thùng rác 7 ngày.", set.name),
            confirmLabel = t("Xóa tất cả"),
            onConfirm = { Repo.deleteImages(setId, set.images.map { it.id }.toSet()) },
            onDismiss = { dialog = null },
        )
        SetDialog.DeleteSet -> ConfirmDeleteDialog(
            title = t("Xóa bộ \"%1\$s\"?", set.name),
            message = t("Bộ ảnh và %1\$s ảnh bên trong sẽ bị xóa. Có thể khôi phục trong Thùng rác 7 ngày.", set.images.size),
            onConfirm = { nav.pop(); Repo.deleteSets(setOf(setId)) },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
    
    if (exporting) {
        com.tilt.wallpapers.ui.components.ExportSetDialog(setId, set.name) { exporting = false }
    }
}

/** Small animated explainer: a phone rocking left and right. */
@Composable
private fun TiltGuide() {
    val rock by rememberInfiniteTransition(label = "rock").animateFloat(
        -18f, 18f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "angle",
    )
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Smartphone, null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(36.dp).graphicsLayer { rotationZ = rock },
        )
        Spacer(Modifier.width(16.dp))
        Text(
            t("Giữ thẳng để thấy ảnh Giữa. Nghiêng trái/phải để đi lần lượt qua bậc 1, 2, 3… của mỗi bên. Giữ và kéo ảnh để đổi thứ tự."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun SideSection(
    set: WallSet,
    side: Side,
    aspect: Float,
    selection: Set<String>,
    onTap: (WallImage) -> Unit,
    onLongPress: (WallImage) -> Unit,
    onAdd: () -> Unit,
) {
    val items = set.side(side)
    val title = when (side) { Side.LEFT -> t("Nghiêng trái"); Side.CENTER -> t("Giữa (giữ thẳng)"); Side.RIGHT -> t("Nghiêng phải") }
    val subtitle = when (side) {
        Side.CENTER -> t("Ảnh hiển thị khi máy để thẳng")
        else -> if (items.isEmpty()) t("Chưa có ảnh") else t("%1\$s ảnh · bậc 1 gần nhất", items.size)
    }
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.padding(start = 20.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(side.icon(), null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (side != Side.CENTER || items.isEmpty()) {
                AppButton(t("Thêm"), icon = Icons.Filled.AddPhotoAlternate, style = BtnStyle.Text, onClick = onAdd)
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.padding(horizontal = 20.dp)) { EmptySlot(aspect, onAdd) }
        } else {
            ReorderableRow(
                items = items,
                key = { it.id },
                onMove = { from, to -> Repo.reorder(set.id, side, from, to) },
                onTap = onTap,
                onLongPress = onLongPress,
            ) { img, index, _ ->
                ImageTile(img, aspect, if (side == Side.CENTER) t("Giữa") else "${index + 1}", img.id in selection, selection.isNotEmpty())
            }
        }
    }
}

@Composable
private fun ImageTile(img: WallImage, aspect: Float, label: String, selected: Boolean, selecting: Boolean) {
    val shape = RoundedCornerShape(20.dp)
    val cs = MaterialTheme.colorScheme
    val s = cardScale(false, selected)
    Box(
        Modifier
            .width(92.dp)
            .aspectRatio(aspect)
            .scaled(s)
            .clip(shape)
            .then(if (selected) Modifier.border(3.dp, cs.primary, shape) else Modifier)
    ) {
        EditedImage(img, Modifier.fillMaxSize())
        GlassSurface(Modifier.align(Alignment.BottomStart).padding(8.dp).height(24.dp), shape = CircleShape, state = null) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp))
        }
        androidx.compose.animation.AnimatedVisibility(selecting, Modifier.align(Alignment.TopEnd).padding(8.dp), enter = scaleIn(), exit = scaleOut()) {
            SelectMark(selected)
        }
    }
}

@Composable
private fun EmptySlot(aspect: Float, onAdd: () -> Unit) {
    val color = MaterialTheme.colorScheme.outline
    Column(
        Modifier
            .width(92.dp)
            .aspectRatio(aspect)
            .bounceClick(onClick = onAdd)
            .drawBehind {
                drawRoundRect(
                    color, cornerRadius = CornerRadius(20.dp.toPx()),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.AddPhotoAlternate, null, tint = color, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(6.dp))
        Text(t("Thêm ảnh"), style = MaterialTheme.typography.labelMedium, color = color)
    }
}
