package com.tilt.wallpapers.ui.screens

import com.tilt.wallpapers.data.t

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pinch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.runtime.rememberCoroutineScope
import com.tilt.wallpapers.data.SubjectLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tilt.wallpapers.data.FilterStyle
import com.tilt.wallpapers.data.Grading
import com.tilt.wallpapers.data.ImageEdit
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.Side
import com.tilt.wallpapers.data.WallImage
import com.tilt.wallpapers.ui.LocalNav
import com.tilt.wallpapers.ui.Route
import com.tilt.wallpapers.ui.components.AppButton
import com.tilt.wallpapers.ui.components.SegmentedPicker
import com.tilt.wallpapers.ui.components.BtnStyle
import com.tilt.wallpapers.ui.components.ConfirmDeleteDialog
import com.tilt.wallpapers.ui.components.EditedImage
import com.tilt.wallpapers.ui.components.GlassIconButton
import com.tilt.wallpapers.ui.components.bounceClick
import com.tilt.wallpapers.ui.components.screenAspect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Unsaved editor state, kept while the mask editor is open (the nav host drops off-screen screens). */
internal object EditorDraft {
    var imageId: String? = null; private set
    var edit = ImageEdit(); private set
    var side = Side.CENTER; private set
    var order = 0; private set
    internal var tab = EditTab.CROP; private set
    /** The group's panel was open (coming back from the mask editor opens it again). */
    var panelOpen = false; private set

    internal fun keep(imageId: String, edit: ImageEdit, side: Side, order: Int, tab: EditTab, panelOpen: Boolean) {
        this.imageId = imageId; this.edit = edit; this.side = side; this.order = order; this.tab = tab; this.panelOpen = panelOpen
    }

    fun clear() { imageId = null }

    /** Applies [f] to the draft's subject placement (after the subject's cut-out changed size). */
    fun adjustSubject(imageId: String, f: (com.tilt.wallpapers.data.SubjectTransform) -> com.tilt.wallpapers.data.SubjectTransform) {
        if (this.imageId == imageId) edit = edit.copy(subject = f(edit.subject))
    }
}

internal enum class EditTab(private val vi: String, val icon: ImageVector) {
    CROP("Cắt", Icons.Filled.Crop),
    STYLE("Kiểu", Icons.Filled.Palette),
    ADJUST("Chỉnh", Icons.Filled.Tune),
    POSITION("Vị trí", Icons.Filled.SwapHoriz),
    DEPTH("Depth", Icons.Filled.Pinch);

    /** Shown in the current language. */
    val label: String get() = t(vi)
}

@Composable
fun EditorScreen(setId: String, imageId: String) {
    val nav = LocalNav.current
    val state by Repo.state.collectAsStateWithLifecycle()
    val set = state.sets.find { it.id == setId }
    val img = set?.images?.find { it.id == imageId }
    if (set == null || img == null) { LaunchedEffect(Unit) { nav.pop() }; return }

    val originalOrder = remember { set.side(img.side).indexOfFirst { it.id == imageId } }
    val draft = remember { EditorDraft.takeIf { it.imageId == imageId } }
    var edit by remember { mutableStateOf(draft?.edit ?: img.edit) }
    var side by remember { mutableStateOf(draft?.side ?: img.side) }
    var order by remember { mutableIntStateOf(draft?.order ?: originalOrder) }
    var tab by remember { mutableStateOf(draft?.tab ?: EditTab.CROP) }
    var centring by remember { mutableStateOf(false) }
    var centreMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val hfEditor = LocalHapticFeedback.current
    var open by remember { mutableStateOf(draft?.takeIf { it.panelOpen }?.tab) }
    SideEffect { EditorDraft.keep(imageId, edit, side, order, tab, open != null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val aspect = screenAspect()
    val dirty = edit != img.edit || side != img.side || order != originalOrder
    val leave = { EditorDraft.clear(); nav.pop() }
    val back = { if (dirty) confirmDiscard = true else leave() }
    BackHandler { back() }

    fun save() {
        Repo.updateImage(setId, imageId) { it.copy(edit = edit) }
        if (side != img.side || order != originalOrder) Repo.moveImage(setId, imageId, side, order)
        leave()
    }

    // The lock-screen-editor layout: a small phone with the picture, groups of settings under it.
    com.tilt.wallpapers.ui.components.Studio(
        title = t("Chỉnh sửa"),
        onBack = back,
        aspect = aspect,
        tabs = EditTab.entries.map { com.tilt.wallpapers.ui.components.StudioTab(it.name, it.label, it.icon) },
        open = open?.name,
        onOpen = { k -> open = k?.let { EditTab.valueOf(it) }; if (k != null) tab = EditTab.valueOf(k) },
        actions = {
            // Back to the untouched picture: crop, colours and subject layer (nothing is saved until "Lưu").
            if (edit != ImageEdit()) {
                com.tilt.wallpapers.ui.components.PreviewPill("", Icons.Filled.RestartAlt, onClick = { edit = ImageEdit() })
                Spacer(Modifier.width(6.dp))
            }
            com.tilt.wallpapers.ui.components.PreviewPill(t("Lưu"), Icons.Filled.Check, onClick = ::save)
        },
        preview = {
            if (tab == EditTab.DEPTH) DepthPreview(img, edit, onEdit = { edit = it }, modifier = Modifier.fillMaxSize())
            else CropCanvas(img, edit, onEdit = { edit = it }, modifier = Modifier.fillMaxSize())
        },
        panelScrolls = true,
    ) { key ->
        // Each panel scrolls on its own, filling the panel.
        Box(Modifier.fillMaxSize()) {
                when (EditTab.valueOf(key)) {
                    EditTab.CROP -> CropPanel(
                        centring = centring,
                        onReset = { edit = edit.copy(cropX = 0f, cropY = 0f, cropW = 1f, cropH = 1f) },
                        onCentre = {
                            centring = true
                            scope.launch {
                                val ok = Repo.ensureMaskFor(imageId)
                                val fresh = Repo.state.value.sets.flatMap { it.images }.find { it.id == imageId }
                                val box = if (ok && fresh != null) withContext(Dispatchers.IO) { SubjectLayout.maskBox(fresh) } else null
                                if (box == null) {
                                    centreMessage = if (ok) t("Không tìm thấy chủ thể trong ảnh này") else t("Không tải được mô hình AI (cần Google Play Services và mạng)")
                                } else {
                                    val target = SubjectLayout.crop(edit, box, img.width, img.height, aspect, Repo.minCropPx())
                                    // Glide there instead of jumping.
                                    val from = edit
                                    val anim = Animatable(0f)
                                    anim.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) {
                                        val k = value
                                        edit = from.copy(
                                            cropX = from.cropX + (target.cropX - from.cropX) * k, cropY = from.cropY + (target.cropY - from.cropY) * k,
                                            cropW = from.cropW + (target.cropW - from.cropW) * k, cropH = from.cropH + (target.cropH - from.cropH) * k,
                                        )
                                    }
                                    hfEditor.performHapticFeedback(HapticFeedbackType.Confirm)
                                    centreMessage = null
                                }
                                centring = false
                            }
                        },
                        message = centreMessage,
                    )
                    EditTab.STYLE -> StylePanel(img, edit, aspect) { edit = edit.copy(filter = it) }
                    EditTab.ADJUST -> AdjustPanel(edit) { edit = it }
                    EditTab.POSITION -> PositionPanel(
                        side = side,
                        order = order,
                        count = set.side(side).count { it.id != imageId } + 1,
                        onSide = { s ->
                            side = s
                            order = if (s == img.side) originalOrder else set.side(s).count { it.id != imageId }
                        },
                        onOrder = { order = it },
                    )
                    EditTab.DEPTH -> DepthPanel(img, edit, state.settings.depth) { edit = it }
                }
        }
    }

    if (confirmDiscard) ConfirmDeleteDialog(
        title = t("Bỏ thay đổi?"),
        message = t("Các chỉnh sửa chưa lưu sẽ bị mất."),
        confirmLabel = t("Bỏ"),
        onConfirm = { leave() },
        onDismiss = { confirmDiscard = false },
    )
}

/**
 * Pinch to zoom, drag to pan. The frame has the screen's aspect, so the crop is what the wallpaper shows.
 * The crop snaps to the picture's centre lines with a haptic tick and a guide line.
 */
@Composable
private fun CropCanvas(img: WallImage, edit: ImageEdit, onEdit: (ImageEdit) -> Unit, modifier: Modifier) {
    val hf = LocalHapticFeedback.current
    var touching by remember { mutableStateOf(false) }
    var snapX by remember { mutableStateOf(false) }
    var snapY by remember { mutableStateOf(false) }
    val grid by animateFloatAsState(if (touching) 1f else 0f, label = "grid")
    val current by rememberUpdatedState(edit)
    val guide = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier.background(Color.Black)) {
        val fw = constraints.maxWidth.toFloat()
        val fh = constraints.maxHeight.toFloat()
        Box(
            Modifier.fillMaxSize().pointerInput(img.id, fw, fh) {
                val tol = 12.dp.toPx()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    touching = true
                    var raw = current
                    do {
                        val ev = awaitPointerEvent()
                        val zoom = ev.calculateZoom()
                        val pan = ev.calculatePan()
                        if (zoom != 1f || pan != Offset.Zero) {
                            raw = transformCrop(raw, img.width.toFloat() / img.height, fw, fh, zoom, pan, ev.calculateCentroid())
                            // Snapping works on a copy so small moves can still pull the crop out of a snap.
                            val sx = raw.cropW < 0.995f && abs(raw.cropX + raw.cropW / 2 - 0.5f) < tol / fw * raw.cropW
                            val sy = raw.cropH < 0.995f && abs(raw.cropY + raw.cropH / 2 - 0.5f) < tol / fh * raw.cropH
                            if ((sx && !snapX) || (sy && !snapY)) hf.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            snapX = sx; snapY = sy
                            onEdit(raw.copy(
                                cropX = if (sx) 0.5f - raw.cropW / 2 else raw.cropX,
                                cropY = if (sy) 0.5f - raw.cropH / 2 else raw.cropY,
                            ))
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    touching = false
                    snapX = false; snapY = false
                }
            }
        ) {
            EditedImage(img, Modifier.fillMaxSize(), edit = edit, full = true)
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = grid }) {
                val c = Color.White.copy(alpha = 0.6f)
                for (i in 1..2) {
                    drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.5f)
                    drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.5f)
                }
                if (snapX) drawLine(guide, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx())
                if (snapY) drawLine(guide, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
            }
        }
    }
}

private fun transformCrop(edit: ImageEdit, imageAspect: Float, fw: Float, fh: Float, zoom: Float, pan: Offset, centroid: Offset): ImageEdit {
    val r = Grading.fitCrop(edit, imageAspect, fw / fh)
    var (x, y, w, h) = r.toList()
    var nw = w / zoom
    var nh = h / zoom
    val over = max(nw, nh)
    if (over > 1f) { nw /= over; nh /= over }
    if (nw < 0.05f) { nh *= 0.05f / nw; nw = 0.05f }
    x += centroid.x / fw * (w - nw)
    y += centroid.y / fh * (h - nh)
    x -= pan.x / fw * nw
    y -= pan.y / fh * nh
    w = nw; h = nh
    return edit.copy(cropX = x.coerceIn(0f, 1f - w), cropY = y.coerceIn(0f, 1f - h), cropW = w, cropH = h)
}

@Composable
private fun TabBar(selected: EditTab, onSelect: (EditTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
    ) {
        EditTab.entries.forEach { t ->
            val sel = t == selected
            val bg by animateFloatAsState(if (sel) 1f else 0f, spring(dampingRatio = 0.7f), label = "tab")
            Row(
                Modifier.weight(1f).height(44.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f * bg))
                    .bounceClick(pressedScale = 0.88f) { onSelect(t) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val c = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(t.icon, t.label, tint = c, modifier = Modifier.size(20.dp))
                if (sel) {
                    Spacer(Modifier.width(6.dp))
                    Text(t.label, style = MaterialTheme.typography.labelLarge, color = c, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun CropPanel(centring: Boolean, onReset: () -> Unit, onCentre: () -> Unit, message: String?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Pinch, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                t("Chụm hai ngón để phóng to, kéo để căn chỉnh. Khung xem trước có đúng tỉ lệ màn hình của bạn."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(if (centring) t("Đang tìm chủ thể…") else t("Căn theo chủ thể"), icon = Icons.Filled.CenterFocusWeak, enabled = !centring, onClick = onCentre)
            AppButton(t("Đặt lại"), icon = Icons.Filled.Refresh, style = BtnStyle.Tonal, onClick = onReset)
        }
        if (message != null) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun StylePanel(img: WallImage, edit: ImageEdit, aspect: Float, onPick: (FilterStyle) -> Unit) {
    val hf = LocalHapticFeedback.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(FilterStyle.entries) { f ->
            val sel = edit.filter == f
            Column(
                Modifier.bounceClick(haptic = false) { hf.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(f) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EditedImage(
                    img,
                    Modifier
                        .height(132.dp)
                        .aspectRatio(aspect)
                        .clip(RoundedCornerShape(18.dp))
                        .then(if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)) else Modifier),
                    edit = edit.copy(filter = f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    f.label, style = MaterialTheme.typography.labelMedium,
                    color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AdjustPanel(edit: ImageEdit, onEdit: (ImageEdit) -> Unit) {
    fun pct(v: Float) = "${(v * 100).roundToInt()}%"
    fun signed(v: Float) = (if (v > 0) "+" else "") + (v * 100).roundToInt()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
        SliderRow(t("Độ trong suốt"), edit.opacity, 0.1f..1f, ::pct) { onEdit(edit.copy(opacity = it)) }
        SliderRow(t("Độ sáng"), edit.brightness, -0.5f..0.5f, ::signed) { onEdit(edit.copy(brightness = it)) }
        SliderRow(t("Tương phản"), edit.contrast, 0.5f..1.6f, ::pct) { onEdit(edit.copy(contrast = it)) }
        SliderRow(t("Độ bão hòa"), edit.saturation, 0f..2f, ::pct) { onEdit(edit.copy(saturation = it)) }
        SliderRow(t("Nhiệt độ màu"), edit.warmth, -1f..1f, ::signed) { onEdit(edit.copy(warmth = it)) }
        SliderRow(t("Viền tối"), edit.vignette, 0f..1f, ::pct) { onEdit(edit.copy(vignette = it)) }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            AppButton(t("Đặt lại chỉnh màu"), icon = Icons.Filled.Refresh, style = BtnStyle.Tonal) {
                val d = ImageEdit()
                onEdit(edit.copy(opacity = d.opacity, brightness = d.brightness, contrast = d.contrast, saturation = d.saturation, warmth = d.warmth, vignette = d.vignette))
            }
        }
    }
}

@Composable
private fun PositionPanel(side: Side, order: Int, count: Int, onSide: (Side) -> Unit, onOrder: (Int) -> Unit) {
    val hf = LocalHapticFeedback.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SegmentedPicker(Side.entries, side, { it.label }, icon = { it.icon() }, onPick = onSide)
        if (side == Side.CENTER) {
            Text(
                t("Ảnh giữa hiện khi máy để thẳng. Ảnh giữa hiện tại (nếu có) sẽ chuyển sang bậc 1 bên phải."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, t("Gần hơn"), enabled = order > 0, state = null) { onOrder(order - 1) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t("Bậc %1\$s / %2\$s", order + 1, count), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (side == Side.LEFT) t("nghiêng trái càng nhiều, bậc càng cao") else t("nghiêng phải càng nhiều, bậc càng cao"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GlassIconButton(Icons.AutoMirrored.Filled.ArrowForward, t("Xa hơn"), enabled = order < count - 1, state = null) { onOrder(order + 1) }
            }
        }
    }
}

