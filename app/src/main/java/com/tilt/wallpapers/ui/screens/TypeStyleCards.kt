package com.tilt.wallpapers.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tilt.wallpapers.data.AppState
import com.tilt.wallpapers.data.ClockFill
import com.tilt.wallpapers.data.ClockFont
import com.tilt.wallpapers.data.ClockStyle
import com.tilt.wallpapers.data.CustomFont
import com.tilt.wallpapers.data.Fonts
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.t
import com.tilt.wallpapers.ui.components.AppButton
import com.tilt.wallpapers.ui.components.BtnStyle
import com.tilt.wallpapers.ui.components.ConfirmDeleteDialog
import com.tilt.wallpapers.ui.components.Notices
import com.tilt.wallpapers.ui.components.SegmentedPicker
import com.tilt.wallpapers.ui.components.bounceClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Font, weight and fill of a clock or a free text (each object keeps its own values). [sample] is what the chips show. */
@Composable
internal fun TypeLookCard(state: AppState, style: ClockStyle, sample: String, update: ((ClockStyle) -> ClockStyle) -> Unit) {
    SettingsCard {
        Label(t("Font"))
        FontRow(state, style, sample, update)
        Spacer(Modifier.height(8.dp))
        SliderRow(
            t("Độ dày"), style.weight.toFloat().coerceIn(style.minWeight.toFloat(), style.maxWeight.toFloat()),
            style.minWeight.toFloat()..maxOf(style.maxWeight, style.minWeight + 1).toFloat(),
            { if (style.variableWeight) "${it.roundToInt()}" else t("Cố định") }, enabled = style.variableWeight,
        ) { v -> update { it.copy(weight = (v / 50f).roundToInt() * 50) } }
        Label(t("Kiểu tô"))
        SegmentedPicker(ClockFill.entries, style.fill, { it.label }, Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) { v -> update { it.copy(fill = v) } }
    }
}

/** Colour (or gradient, edited right here), opacity and shadow of a clock or a free text. */
@Composable
internal fun TypeColorCard(state: AppState, setId: String, style: ClockStyle, update: ((ClockStyle) -> ClockStyle) -> Unit) {
    val hf = LocalHapticFeedback.current
    var editGradient by remember { mutableStateOf(false) }
    SettingsCard {
        val palette = Repo.centerImage(state.copy(activeSetId = setId))?.palette.orEmpty()
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Swatch(null, style.color == 0 && style.gradient.isEmpty()) { editGradient = false; update { it.copy(color = 0, gradient = emptyList()) } }
            // Gradient from the middle picture's first three colours.
            val auto3 = (palette + listOf(0xFFFFFFFF.toInt(), 0xFFB0B0B0.toInt(), 0xFF606060.toInt())).take(3)
            GradientSwatch(style.gradient.ifEmpty { auto3 }, style.gradient.isNotEmpty()) { update { it.copy(gradient = auto3, gradientAuto = true) } }
            (palette + listOf(0xFFFFFFFF.toInt(), 0xFF101010.toInt())).distinct().forEach { c ->
                Swatch(c, style.color == c && style.gradient.isEmpty()) {
                    hf.performHapticFeedback(HapticFeedbackType.SegmentTick); editGradient = false; update { it.copy(color = c, gradient = emptyList()) }
                }
            }
        }
        if (style.gradient.isNotEmpty()) {
            Box(Modifier.padding(start = 16.dp, bottom = 8.dp)) {
                AppButton(if (editGradient) t("Xong") else t("Chỉnh gradient"), icon = Icons.Filled.Palette, style = BtnStyle.Tonal) { editGradient = !editGradient }
            }
            if (editGradient) GradientEditor(state, style.gradient) { cols -> update { it.copy(gradient = cols, gradientAuto = false) } }
        }
        SliderRow(t("Độ đậm"), style.opacity, 0.3f..1f, { "${(it * 100).roundToInt()}%" }) { v -> update { it.copy(opacity = v) } }
        SwitchRow(t("Bóng mờ sau chữ"), t("Giúp chữ dễ đọc trên ảnh sáng"), style.shadow) { v -> update { it.copy(shadow = v) } }
        if (style.shadow) SliderRow(t("Độ đậm bóng"), style.shadowAmt, 0.1f..1f, { "${(it * 100).roundToInt()}%" }) { v -> update { it.copy(shadowAmt = v) } }
    }
}

/** Built-in fonts, the user's fonts (hold to delete) and "+ Font của bạn". */
@Composable
private fun FontRow(state: AppState, style: ClockStyle, sample: String, update: ((ClockStyle) -> ClockStyle) -> Unit) {
    val ctx = LocalContext.current
    val hf = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<CustomFont?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                val name = runCatching {
                    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()
                Fonts.import(ctx, uri, name)
            }
            when (r) {
                is Fonts.Result.Ok -> {
                    Repo.addFont(r.font)
                    update { it.copy(customFont = r.font.id, weight = it.weight.coerceIn(r.font.minWeight, r.font.maxWeight)) }
                }
                Fonts.Result.Bad -> Notices.show(t("File này không phải font .ttf hoặc .otf dùng được"))
            }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ClockFont.entries.forEach { f ->
            FontChip(f.label, sample, style.customFont == null && f == style.font, remember(f, style.weight) { builtInFamily(ctx, f, style.weight) }) {
                hf.performHapticFeedback(HapticFeedbackType.SegmentTick); update { it.copy(font = f, customFont = null) }
            }
        }
        state.fonts.forEach { cf ->
            val w = style.weight.coerceIn(cf.minWeight, cf.maxWeight)
            val family = remember(cf.id, w) {
                runCatching {
                    FontFamily(Font(Fonts.file(ctx, cf.id), FontWeight(w), variationSettings = if (cf.variable) FontVariation.Settings(FontVariation.weight(w)) else FontVariation.Settings()))
                }.getOrDefault(FontFamily.Default)
            }
            FontChip(cf.name, sample, style.customFont == cf.id, family, onLong = { hf.performHapticFeedback(HapticFeedbackType.LongPress); deleting = cf }) {
                hf.performHapticFeedback(HapticFeedbackType.SegmentTick)
                update { it.copy(customFont = cf.id, weight = it.weight.coerceIn(cf.minWeight, cf.maxWeight)) }
            }
        }
        val cs = MaterialTheme.colorScheme
        Column(
            Modifier.size(CHIP_W, CHIP_H).clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerHigh).border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp))
                .bounceClick(pressedScale = 0.92f) { picker.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt", "application/octet-stream")) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Add, null, Modifier.size(36.dp), tint = cs.primary)
            Text(t("Font của bạn"), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
    deleting?.let { cf ->
        ConfirmDeleteDialog(
            title = t("Xóa font \"%1\$s\"?", cf.name),
            message = t("Đồng hồ và chữ đang dùng font này sẽ quay về Smooch Sans."),
            onConfirm = { Repo.deleteFont(cf.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

private fun builtInFamily(ctx: android.content.Context, font: ClockFont, weight: Int): FontFamily {
    val w = weight.coerceIn(font.minWeight, font.maxWeight)
    return FontFamily(
        Font(
            font.asset, ctx.assets, FontWeight(w),
            variationSettings = when {
                font.widthAxis -> FontVariation.Settings(FontVariation.weight(w), FontVariation.width(font.widthOf(w)))
                font.variable -> FontVariation.Settings(FontVariation.weight(w))
                else -> FontVariation.Settings()
            },
        ),
    )
}

@Composable
private fun FontChip(label: String, sample: String, selected: Boolean, family: FontFamily, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.size(CHIP_W, CHIP_H).clip(RoundedCornerShape(18.dp)).background(if (selected) cs.primaryContainer else cs.surfaceContainerHigh)
            .then(if (selected) Modifier.border(2.dp, cs.primary, RoundedCornerShape(18.dp)) else Modifier)
            .then(if (onLong != null) Modifier.combinedClickable(onLongClick = onLong, onClick = onClick) else Modifier.bounceClick(pressedScale = 0.92f, haptic = false, onClick = onClick))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(sample, style = TextStyle(fontFamily = family, fontSize = 30.sp), color = if (selected) cs.onPrimaryContainer else cs.onSurface, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Every font chip the same size, whatever its name or how tall its sample. */
private val CHIP_W = 112.dp
private val CHIP_H = 84.dp

@Composable
internal fun Label(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 8.dp))

@Composable
private fun GradientSwatch(colors: List<Int>, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Brush.verticalGradient(colors.map { Color(it) }))
            .border(if (selected) BorderStroke(3.dp, cs.primary) else BorderStroke(1.dp, cs.outlineVariant), CircleShape)
            .bounceClick(pressedScale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("G", style = MaterialTheme.typography.labelLarge, color = Color.White) }
}

/** A colour choice; null = automatic (a light tint of the set's colours). */
@Composable
private fun Swatch(color: Int?, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(if (color == null) Brush.sweepGradient(listOf(Color(0xFFFFD1DC), Color(0xFFD1E8FF), Color(0xFFE6FFD1), Color(0xFFFFD1DC))) else Brush.linearGradient(listOf(Color(color), Color(color))))
            .border(if (selected) BorderStroke(3.dp, cs.primary) else BorderStroke(1.dp, cs.outlineVariant), CircleShape)
            .bounceClick(pressedScale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (color == null) Text("A", style = MaterialTheme.typography.labelLarge, color = Color.Black) }
}
