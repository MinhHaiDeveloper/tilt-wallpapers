package com.tilt.wallpapers.ui.screens

import com.tilt.wallpapers.data.t

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Portrait
import androidx.compose.material.icons.filled.VerticalAlignBottom
import com.tilt.wallpapers.data.SubjectLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tilt.wallpapers.data.ImageEdit
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.Side
import com.tilt.wallpapers.data.SubjectTransform
import com.tilt.wallpapers.data.WallImage
import com.tilt.wallpapers.data.WallSet
import com.tilt.wallpapers.ui.LocalNav
import com.tilt.wallpapers.ui.Route
import com.tilt.wallpapers.ui.components.AppButton
import com.tilt.wallpapers.ui.components.BtnStyle
import com.tilt.wallpapers.ui.components.ConfirmDeleteDialog
import com.tilt.wallpapers.ui.components.TiltPreview
import com.tilt.wallpapers.ui.components.bounceClick
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val PREVIEW_SET = "editor-preview"

/** The image as the wallpaper will draw it (unsaved edits included), with subject gestures on top. */
@Composable
fun DepthPreview(img: WallImage, edit: ImageEdit, onEdit: (ImageEdit) -> Unit, modifier: Modifier) {
    val state by Repo.state.collectAsStateWithLifecycle()
    val preview = remember(state, img, edit) {
        state.copy(sets = listOf(WallSet(PREVIEW_SET, "", listOf(img.copy(edit = edit, side = Side.CENTER)))))
    }
    Box(modifier.background(Color.Black)) {
        TiltPreview(Modifier.fillMaxSize(), PREVIEW_SET, onTapStep = false, override = preview, tilt = false)
        if (img.hasSubject) {
            val current by rememberUpdatedState(edit)
            SubjectGestures(img.subjectAspect, edit.subject) { onEdit(current.copy(subject = it)) }
        }
    }
}

private data class Snap(val x: Boolean = false, val y: Boolean = false, val r: Boolean = false)

/**
 * One finger moves the subject, two fingers also scale and rotate it. It snaps to the screen's centre
 * lines and to right angles, with a haptic tick and a guide line each time it locks on.
 */
@Composable
private fun SubjectGestures(aspect: Float, t: SubjectTransform, onChange: (SubjectTransform) -> Unit) {
    val hf = LocalHapticFeedback.current
    val current by rememberUpdatedState(t)
    var guides by remember { mutableStateOf(Snap()) }
    var touching by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(if (touching) 1f else 0f, label = "guides")
    val guideColor = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fw = constraints.maxWidth.toFloat()
        val fh = constraints.maxHeight.toFloat()
        Box(
            Modifier.fillMaxSize().pointerInput(fw, fh) {
                val tolX = 12.dp.toPx() / fw
                val tolY = 12.dp.toPx() / fh
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    touching = true
                    var raw = current
                    var snap = Snap()
                    do {
                        val ev = awaitPointerEvent()
                        val pan = ev.calculatePan()
                        val zoom = ev.calculateZoom()
                        val rot = ev.calculateRotation()
                        if (pan != Offset.Zero || zoom != 1f || rot != 0f) {
                            raw = raw.copy(
                                offsetX = (raw.offsetX + pan.x / fw).coerceIn(-0.8f, 0.8f),
                                offsetY = (raw.offsetY + pan.y / fh).coerceIn(-0.8f, 0.8f),
                                scale = (raw.scale * zoom).coerceIn(0.15f, 5f),
                                rotation = normalizeDegrees(raw.rotation + rot),
                            )
                            val m = ((raw.rotation % 90f) + 90f) % 90f
                            val next = Snap(abs(raw.offsetX) < tolX, abs(raw.offsetY) < tolY, m < 3f || m > 87f)
                            if ((next.x && !snap.x) || (next.y && !snap.y) || (next.r && !snap.r)) {
                                hf.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            snap = next
                            guides = next
                            onChange(raw.copy(
                                offsetX = if (next.x) 0f else raw.offsetX,
                                offsetY = if (next.y) 0f else raw.offsetY,
                                rotation = if (next.r) normalizeDegrees((raw.rotation / 90f).roundToInt() * 90f) else raw.rotation,
                            ))
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    touching = false
                    hf.performHapticFeedback(HapticFeedbackType.GestureEnd)
                }
            }
        )
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = shown }) {
            val (bw, bh) = SubjectTransform.baseSize(aspect, size.width / size.height)
            val w = bw * t.scale * size.width
            val h = bh * t.scale * size.height
            val c = Offset((0.5f + t.offsetX) * size.width, (0.5f + t.offsetY) * size.height)
            rotate(t.rotation, pivot = c) {
                drawRect(Color.Black.copy(alpha = 0.35f), c - Offset(w / 2, h / 2), Size(w, h), style = Stroke(3.dp.toPx()))
                drawRect(Color.White.copy(alpha = 0.9f), c - Offset(w / 2, h / 2), Size(w, h), style = Stroke(1.5.dp.toPx()))
            }
            if (guides.x) drawLine(guideColor, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx())
            if (guides.y) drawLine(guideColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
        }
    }
}

private fun normalizeDegrees(d: Float): Float = ((d + 180f) % 360f + 360f) % 360f - 180f

@Composable
fun DepthPanel(img: WallImage, edit: ImageEdit, depthEnabled: Boolean, onEdit: (ImageEdit) -> Unit) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val currentEdit by rememberUpdatedState(edit)
    val currentOnEdit by rememberUpdatedState(onEdit)
    var showEnableDialog by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val t = edit.subject
    fun update(f: (SubjectTransform) -> SubjectTransform) = onEdit(edit.copy(subject = f(edit.subject)))

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = t("Đang chuẩn bị chủ thể…")
        scope.launch {
            val r = Repo.importSubject(img.id, uri)
            busy = false
            status = when (r) {
                Repo.SubjectResult.OK -> t("Đã thêm chủ thể")
                Repo.SubjectResult.CUT_BY_AI -> t("Ảnh không có nền trong suốt nên AI đã tự tách nền")
                Repo.SubjectResult.NO_SUBJECT -> t("Không tìm thấy chủ thể trong ảnh này")
                Repo.SubjectResult.FAILED -> t("Không thêm được chủ thể (ảnh lỗi hoặc thiếu mô hình AI)")
            }
            if (r == Repo.SubjectResult.OK || r == Repo.SubjectResult.CUT_BY_AI) {
                // A new subject is placed for the picture: over its own subject if it has one (hiding it),
                // otherwise standing on the bottom edge. Shadow / blur choices are kept.
                val fresh = Repo.state.value.sets.flatMap { it.images }.find { it.id == img.id } ?: img
                val box = withContext(Dispatchers.IO) { SubjectLayout.maskBox(fresh) }
                val mode = if (box != null) SubjectLayout.Placement.OVER_ORIGINAL else SubjectLayout.Placement.STAND
                val e = currentEdit
                currentOnEdit(e.copy(subject = SubjectLayout.place(e.subject, mode, fresh.subjectAspect, Repo.screenAspect(), fresh, e, box)))
            }
        }
    }
    val hasMaskSubject = depthEnabled && (img.hasMask || img.hasManualMask)
    val hasAnySubject = img.hasSubject || hasMaskSubject

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppButton(t("Chỉnh mặt nạ"), icon = Icons.Filled.Brush, style = BtnStyle.Tonal) {
                if (depthEnabled) nav.push(Route.MaskEditor(img.id)) else showEnableDialog = true
            }
            AppButton(if (img.hasSubject) t("Đổi chủ thể") else t("Thêm chủ thể"), icon = Icons.Filled.AddPhotoAlternate, style = BtnStyle.Tonal, enabled = !busy) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            if (img.hasSubject) {
                AppButton(t("Chỉnh chủ thể"), icon = Icons.Filled.ContentCut, style = BtnStyle.Tonal, enabled = !busy) {
                    nav.push(Route.MaskEditor(img.id, subject = true))
                }
                AppButton(t("Gỡ chủ thể"), icon = Icons.Filled.Delete, style = BtnStyle.Outlined, enabled = !busy) { confirmRemove = true }
            }
        }

        AnimatedVisibility(status != null || busy) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(status ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (img.hasSubject) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuickAction(Icons.Filled.FilterCenterFocus, t("Căn giữa")) { update { it.copy(offsetX = 0f, offsetY = 0f) } }
                QuickAction(Icons.Filled.Flip, t("Lật ngang")) { update { it.copy(flipHorizontal = !it.flipHorizontal) } }
                QuickAction(Icons.Filled.Flip, t("Lật dọc"), iconRotation = 90f) { update { it.copy(flipVertical = !it.flipVertical) } }
                QuickAction(Icons.Filled.RestartAlt, t("Đặt lại")) {
                    update { it.copy(offsetX = 0f, offsetY = 0f, scale = 1f, rotation = 0f, flipHorizontal = false, flipVertical = false) }
                }
            }
            Text(t("Tự đặt"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SubjectLayout.Placement.entries.forEach { mode ->
                    val icon = when (mode) {
                        SubjectLayout.Placement.OVER_ORIGINAL -> Icons.Filled.CenterFocusWeak
                        SubjectLayout.Placement.STAND -> Icons.Filled.VerticalAlignBottom
                        SubjectLayout.Placement.PORTRAIT -> Icons.Filled.Portrait
                    }
                    AppButton(mode.label, icon = icon, style = BtnStyle.Outlined, enabled = !busy && (mode != SubjectLayout.Placement.OVER_ORIGINAL || img.hasMask || img.hasManualMask)) {
                        scope.launch {
                            val box = if (mode == SubjectLayout.Placement.OVER_ORIGINAL) withContext(Dispatchers.IO) { SubjectLayout.maskBox(img) } else null
                            val e = currentEdit
                            currentOnEdit(e.copy(subject = SubjectLayout.place(e.subject, mode, img.subjectAspect, Repo.screenAspect(), img, e, box)))
                        }
                    }
                }
            }
            Text(
                t("Kéo chủ thể trên ảnh để di chuyển, chụm hoặc xoay hai ngón để đổi cỡ và góc."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else if (!depthEnabled) {
            Text(
                t("Bật Depth để AI tách chủ thể của ảnh này, hoặc thêm một chủ thể từ ảnh khác (ảnh PNG nền trong suốt là đẹp nhất)."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        } else if (!hasMaskSubject) {
            Text(
                t("AI đang tách chủ thể cho ảnh này. Bạn cũng có thể tự vẽ mặt nạ."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        SwitchRow(t("Đổ bóng"), t("Bóng mềm sau chủ thể, trôi theo góc nghiêng"), t.dropShadow, Icons.Filled.Layers, enabled = hasAnySubject) { v ->
            update { it.copy(dropShadow = v) }
        }
        AnimatedVisibility(t.dropShadow && hasAnySubject) {
            Column {
                SliderRow(t("Độ đậm bóng"), t.shadowOpacity, 0.1f..0.9f, { "${(it * 100).roundToInt()}%" }) { v -> update { it.copy(shadowOpacity = v) } }
                SliderRow(t("Độ mềm bóng"), t.shadowBlur, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> update { it.copy(shadowBlur = v) } }
            }
        }
        Row(Modifier.padding(start = 20.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.BlurOn, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(t("Lớp nền sau chủ thể"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SliderRow(t("Làm mờ nền"), t.backgroundBlur, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> update { it.copy(backgroundBlur = v) } }
    }

    if (showEnableDialog) {
        AlertDialog(
            onDismissRequest = { showEnableDialog = false },
            icon = { Icon(Icons.Filled.Layers, null) },
            title = { Text(t("Bật Depth?")) },
            text = { Text(t("Chỉnh mặt nạ cần bật Depth để AI tách chủ thể và để hình nền dùng mặt nạ này.")) },
            confirmButton = {
                AppButton(t("Bật")) {
                    Repo.updateSettings { it.copy(depth = true) }
                    showEnableDialog = false
                    nav.push(Route.MaskEditor(img.id))
                }
            },
            dismissButton = { AppButton(t("Hủy"), style = BtnStyle.Text) { showEnableDialog = false } },
        )
    }
    if (confirmRemove) ConfirmDeleteDialog(
        title = t("Gỡ chủ thể?"),
        message = t("Chủ thể thêm từ ảnh ngoài sẽ bị xóa khỏi ảnh này. Ảnh nền giữ nguyên."),
        confirmLabel = t("Gỡ"),
        onConfirm = { Repo.removeSubject(img.id); status = null },
        onDismiss = { confirmRemove = false },
    )
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, iconRotation: Float = 0f, onClick: () -> Unit) {
    Column(Modifier.bounceClick(pressedScale = 0.85f, onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, Modifier.size(22.dp).graphicsLayer { rotationZ = iconRotation }, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
