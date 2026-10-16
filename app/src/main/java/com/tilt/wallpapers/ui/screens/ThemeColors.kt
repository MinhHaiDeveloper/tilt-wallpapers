package com.tilt.wallpapers.ui.screens

import com.tilt.wallpapers.data.t

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.tilt.wallpapers.data.AppState
import com.tilt.wallpapers.data.ColorSource
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.ui.components.AppButton
import com.tilt.wallpapers.ui.components.BtnStyle
import com.tilt.wallpapers.ui.components.SegmentedPicker
import com.tilt.wallpapers.ui.components.bounceClick
import com.tilt.wallpapers.ui.theme.SeedScheme
import com.tilt.wallpapers.ui.theme.isDarkTheme
import com.tilt.wallpapers.ui.theme.schemeFor
import kotlin.math.roundToInt

private val slotLabels get() = listOf(t("Màu chính"), t("Phụ 1"), t("Phụ 2"))

internal val presets = listOf(
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB, 0xFF1E88E5, 0xFF039BE5, 0xFF00ACC1, 0xFF00897B,
    0xFF43A047, 0xFF7CB342, 0xFFC0CA33, 0xFFFFB300, 0xFFFB8C00, 0xFFF4511E, 0xFF6D4C41, 0xFF546E7A, 0xFF757575,
).map { it.toInt() }

/** The three seeds the app is using now (built-in scheme colours when there are none). */
@Composable
private fun currentSeeds(state: AppState): List<Int> {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    return SeedScheme.seedsFor(state, context)?.let { SeedScheme.effective(it, SeedScheme.harmonyFor(state)) }
        ?: listOf(cs.primary.toArgb(), cs.secondary.toArgb(), cs.tertiary.toArgb())
}

/** Settings block: where colours come from, the three colours in use, and the editor. */
@Composable
fun ColumnScope.ThemeColorSection(state: AppState) {
    val s = state.settings
    val seeds = currentSeeds(state)
    var editing by remember { mutableStateOf<Int?>(null) }
    val sources = ColorSource.entries.filter { it != ColorSource.SYSTEM || Build.VERSION.SDK_INT >= 31 }
    Text(t("Màu chủ đề"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
    SegmentedPicker(sources, s.colorSource, { it.label }, Modifier.padding(horizontal = 16.dp)) { src ->
        Repo.updateSettings {
            // Starting "Tự chọn" from scratch would be grey: begin from the colours on screen.
            if (src == ColorSource.CUSTOM && it.customColors.isEmpty()) it.copy(colorSource = src, customColors = seeds) else it.copy(colorSource = src)
        }
    }
    val setName = state.activeSet?.name
    Text(
        when (s.colorSource) {
            ColorSource.SET -> if (Repo.centerImage(state) != null) t("Lấy từ ảnh giữa của bộ \"%1\$s\". Đổi bộ ảnh hay chỉnh ảnh giữa là màu đổi theo.", setName)
                else t("Thêm ảnh vào bộ đang dùng để lấy màu từ ảnh.")
            ColorSource.SYSTEM -> t("Lấy theo hình nền đang đặt trên máy (Material You).")
            ColorSource.CUSTOM -> t("Màu bạn tự chọn. Chạm vào một màu để sửa.")
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        seeds.forEachIndexed { i, c -> SlotSwatch(slotLabels[i], Color(c), selected = false) { editing = i } }
    }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        AppButton(t("Chỉnh màu"), icon = Icons.Filled.Palette, style = BtnStyle.Tonal) { editing = 0 }
    }
    editing?.let { slot -> ThemeColorSheet(state, seeds, slot, onDismiss = { editing = null }) }
}

/**
 * Edits the three seeds with presets, the picture's colours, HSV sliders and hex, previewing the current
 * and the new theme side by side (in light or dark).
 */
@Composable
internal fun ThemeColorSheet(
    state: AppState, start: List<Int>, startSlot: Int, onDismiss: () -> Unit,
    /** Edits other colours (the clock's gradient) with the same tools; no theme preview. */
    onApplyColors: ((List<Int>) -> Unit)? = null,
) {
    val context = LocalContext.current
    val hf = LocalHapticFeedback.current
    val draft = remember { mutableStateListOf(*start.toTypedArray()) }
    var slot by remember { mutableIntStateOf(startSlot) }
    val appDark = isDarkTheme(state.settings.theme)
    var previewDark by remember { mutableStateOf(appDark) }
    // HSV of the colour being edited, kept apart so hue survives when saturation hits 0.
    val hsv = remember(slot) { FloatArray(3).also { android.graphics.Color.colorToHSV(draft[slot], it) } }
    var hsvTick by remember { mutableIntStateOf(0) }
    fun set(c: Int, fromSliders: Boolean = false) {
        draft[slot] = c
        if (!fromSliders) { android.graphics.Color.colorToHSV(c, hsv); hsvTick++ }
    }
    val imageColors = Repo.centerImage(state)?.palette.orEmpty()

    val closer = rememberSheetCloser(onDismiss)
    ModalBottomSheet(onDismissRequest = closer.close, sheetState = closer.state, modifier = closer.modifier, scrimColor = closer.scrim) {
        Column(Modifier.graphicsLayer { alpha = closer.alpha.value }.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(if (onApplyColors == null) t("Màu chủ đề") else t("Màu gradient"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (onApplyColors == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PreviewCard(t("Đang dùng"), schemeFor(state, context, previewDark), Modifier.weight(1f))
                    PreviewCard(t("Sẽ dùng"), SeedScheme.scheme(draft.toList(), previewDark, SeedScheme.LIGHT), Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                SegmentedPicker(listOf(false, true), previewDark, { if (it) t("Xem bản tối") else t("Xem bản sáng") }) { previewDark = it }
            } else Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(draft.map { Color(it) })),
            )

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val labels = if (onApplyColors == null) slotLabels else listOf(t("Trên"), t("Giữa"), t("Dưới"))
                draft.forEachIndexed { i, c -> SlotSwatch(labels[i], Color(c), selected = i == slot) { slot = i } }
            }

            if (imageColors.isNotEmpty()) {
                SheetLabel(t("Từ ảnh giữa của bộ"), Icons.Filled.Collections)
                SwatchRow(imageColors, draft[slot]) { set(it) }
            }
            SheetLabel(t("Bảng màu"), Icons.Filled.Palette)
            SwatchRow(presets, draft[slot]) { set(it) }

            Spacer(Modifier.height(8.dp))
            hsvTick // re-read sliders after a palette pick
            val h = hsv[0]; val sat = hsv[1]; val v = hsv[2]
            GradientSlider(
                t("Sắc độ"), h, 0f..360f, "${h.roundToInt()}°",
                (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }, Color.hsv(h, 1f, 1f),
            ) { hsv[0] = it; set(android.graphics.Color.HSVToColor(hsv), fromSliders = true) }
            GradientSlider(
                t("Độ đậm"), sat, 0f..1f, "${(sat * 100).roundToInt()}%",
                listOf(Color.hsv(h, 0f, v), Color.hsv(h, 1f, v)), Color(draft[slot]),
            ) { hsv[1] = it; set(android.graphics.Color.HSVToColor(hsv), fromSliders = true) }
            GradientSlider(
                t("Độ sáng"), v, 0f..1f, "${(v * 100).roundToInt()}%",
                listOf(Color.Black, Color.hsv(h, sat, 1f)), Color(draft[slot]),
            ) { hsv[2] = it; set(android.graphics.Color.HSVToColor(hsv), fromSliders = true) }

            HexField(draft[slot]) { set(it) }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onApplyColors == null && state.activeSet != null) AppButton(t("Theo ảnh"), style = BtnStyle.Text) {
                    Repo.updateSettings { it.copy(colorSource = ColorSource.SET) }
                    closer.close()
                }
                Spacer(Modifier.weight(1f))
                AppButton(t("Hủy"), style = BtnStyle.Text, onClick = closer.close)
                Spacer(Modifier.width(8.dp))
                AppButton(t("Áp dụng"), icon = Icons.Filled.Check) {
                    hf.performHapticFeedback(HapticFeedbackType.Confirm)
                    if (onApplyColors != null) onApplyColors(draft.toList())
                    else Repo.updateSettings { it.copy(colorSource = ColorSource.CUSTOM, customColors = draft.toList()) }
                    closer.close()
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun SheetLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SlotSwatch(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    val ring by animateDpAsState(if (selected) 3.dp else 0.dp, spring(dampingRatio = 0.5f), label = "ring")
    Column(Modifier.bounceClick(pressedScale = 0.88f, onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(52.dp).border(ring, MaterialTheme.colorScheme.onSurface, CircleShape).padding(5.dp)
                .clip(CircleShape).background(color).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(hex(color.toArgb()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SwatchRow(colors: List<Int>, current: Int, onPick: (Int) -> Unit) {
    val hf = LocalHapticFeedback.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { c ->
            val sel = (c and 0xFFFFFF) == (current and 0xFFFFFF)
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Color(c))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .bounceClick(pressedScale = 0.85f, haptic = false) { hf.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(c) },
                contentAlignment = Alignment.Center,
            ) {
                if (sel) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = if (Color(c).luminance() > 0.5f) Color.Black else Color.White)
            }
        }
    }
}

/** A small slice of the app drawn with [scheme], so a new theme can be judged before applying it. */
@Composable
private fun PreviewCard(title: String, scheme: androidx.compose.material3.ColorScheme, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        MaterialTheme(colorScheme = scheme) {
            val shape = RoundedCornerShape(20.dp)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(scheme.background).border(1.dp, scheme.outlineVariant, shape).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Tilt", style = MaterialTheme.typography.titleMedium, color = scheme.onBackground)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surfaceContainerHigh).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Collections, null, Modifier.size(16.dp), tint = scheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(t("Bộ ảnh"), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                        Text(t("5 ảnh"), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MiniPill(t("Đặt"), scheme.primary, scheme.onPrimary)
                    MiniPill(t("Phụ"), scheme.secondaryContainer, scheme.onSecondaryContainer)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiniPill(t("Phụ 2"), scheme.tertiaryContainer, scheme.onTertiaryContainer)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = true, onCheckedChange = null, modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun MiniPill(text: String, bg: Color, fg: Color) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** Slider whose track shows the colours it moves through. */
@Composable
internal fun GradientSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String,
    track: List<Color>, thumb: Color, onChange: (Float) -> Unit,
) {
    val hf = LocalHapticFeedback.current
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp)) {
            val wPx = constraints.maxWidth.toFloat()
            fun at(x: Float) = range.start + ((x / wPx).coerceIn(0f, 1f)) * (range.endInclusive - range.start)
            val outline = MaterialTheme.colorScheme.outline
            Canvas(
                Modifier.fillMaxWidth().height(40.dp).pointerInput(wPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        onChange(at(down.position.x))
                        horizontalDrag(down.id) { change -> change.consume(); onChange(at(change.position.x)) }
                        hf.performHapticFeedback(HapticFeedbackType.GestureEnd)
                    }
                }
            ) {
                val h = 14.dp.toPx(); val r = 13.dp.toPx()
                val top = (size.height - h) / 2
                drawRoundRect(Brush.horizontalGradient(track), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
                val x = ((value - range.start) / (range.endInclusive - range.start)) * size.width
                val c = Offset(x.coerceIn(r, size.width - r), size.height / 2)
                drawCircle(Color.Black.copy(alpha = 0.18f), r + 1.dp.toPx(), c + Offset(0f, 1.dp.toPx()))
                drawCircle(Color.White, r, c)
                drawCircle(thumb, r - 3.dp.toPx(), c)
                drawCircle(outline.copy(alpha = 0.3f), r, c, style = Stroke(1.dp.toPx()))
            }
        }
    }
}

@Composable
internal fun HexField(color: Int, onColor: (Int) -> Unit) {
    var text by remember(color) { mutableStateOf(hex(color).removePrefix("#")) }
    val valid = text.length == 6 && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
            if (text.length == 6) text.toLongOrNull(16)?.let { onColor((0xFF000000 or it).toInt()) }
        },
        prefix = { Text("#") },
        label = { Text(t("Mã màu (hex)")) },
        isError = text.isNotEmpty() && !valid && text.length == 6,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        leadingIcon = { Box(Modifier.size(22.dp).clip(CircleShape).background(Color(color))) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = MaterialTheme.shapes.medium,
    )
}

private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

/**
 * A clock's or text's gradient edited in place, live (no sheet over the preview): the three stops, the
 * picture's colours, presets, hue / saturation / brightness and hex.
 */
@Composable
internal fun GradientEditor(state: AppState, colors: List<Int>, onChange: (List<Int>) -> Unit) {
    val draft = remember { mutableStateListOf(*List(3) { colors.getOrElse(it) { colors.lastOrNull() ?: 0xFFFFFFFF.toInt() } }.toTypedArray()) }
    var slot by remember { mutableIntStateOf(0) }
    val hsv = remember(slot) { FloatArray(3).also { android.graphics.Color.colorToHSV(draft[slot], it) } }
    var hsvTick by remember { mutableIntStateOf(0) }
    fun set(c: Int, fromSliders: Boolean = false) {
        draft[slot] = c
        if (!fromSliders) { android.graphics.Color.colorToHSV(c, hsv); hsvTick++ }
        onChange(draft.toList())
    }
    val imageColors = Repo.centerImage(state)?.palette.orEmpty()
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            val labels = listOf(t("Trên"), t("Giữa"), t("Dưới"))
            draft.forEachIndexed { i, c -> SlotSwatch(labels[i], Color(c), selected = i == slot) { slot = i } }
        }
        if (imageColors.isNotEmpty()) {
            SheetLabel(t("Từ ảnh giữa của bộ"), Icons.Filled.Collections)
            SwatchRow(imageColors, draft[slot]) { set(it) }
        }
        SheetLabel(t("Bảng màu"), Icons.Filled.Palette)
        SwatchRow(presets, draft[slot]) { set(it) }
        Spacer(Modifier.height(8.dp))
        hsvTick // re-read sliders after a palette pick
        val h = hsv[0]; val sat = hsv[1]; val v = hsv[2]
        GradientSlider(
            t("Sắc độ"), h, 0f..360f, "${h.roundToInt()}°",
            (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }, Color.hsv(h, 1f, 1f),
        ) { hsv[0] = it; set(android.graphics.Color.HSVToColor(hsv), fromSliders = true) }
        GradientSlider(
            t("Độ đậm"), sat, 0f..1f, "${(sat * 100).roundToInt()}%",
            listOf(Color.hsv(h, 0f, v), Color.hsv(h, 1f, v)), Color(draft[slot]),
        ) { hsv[1] = it; set(android.graphics.Color.HSVToColor(hsv), fromSliders = true) }
        GradientSlider(
            t("Độ sáng"), v, 0f..1f, "${(v * 100).roundToInt()}%",
            listOf(Color.Black, Color.hsv(h, sat, 1f)), Color(draft[slot]),
        ) { hsv[2] = it; set(android.graphics.Color.HSVToColor(hsv), fromSliders = true) }
        HexField(draft[slot]) { set(it) }
    }
}
