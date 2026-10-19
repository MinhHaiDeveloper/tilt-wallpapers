package com.tilt.wallpapers.ui.screens

import com.tilt.wallpapers.data.t

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SwipeLeft
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ImportContacts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tilt.wallpapers.data.DepthStatus
import com.tilt.wallpapers.data.Direction
import com.tilt.wallpapers.data.Effect
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.Settings
import com.tilt.wallpapers.data.Transition
import com.tilt.wallpapers.data.WallImage
import com.tilt.wallpapers.gl.EffectThumbs
import com.tilt.wallpapers.ui.components.EditedImage
import com.tilt.wallpapers.ui.components.TiltPreview
import com.tilt.wallpapers.ui.components.bounceClick
import com.tilt.wallpapers.ui.components.screenAspect
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun EffectsScreen() {
    val state by Repo.state.collectAsStateWithLifecycle()
    val depth by Repo.depthStatus.collectAsStateWithLifecycle()
    val s = state.settings
    val set = state.activeSet
    val aspect = screenAspect()
    val neutral = set?.tiltSequence()?.let { (seq, n) -> seq.getOrNull(n) }
    fun update(f: (Settings) -> Settings) = Repo.updateSettings(f)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = statusTop() + 12.dp, bottom = navBottom() + 120.dp),
    ) {
        item { ScreenHeader(t("Hiệu ứng"), t("Áp dụng cho toàn bộ hình nền")) }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.height(400.dp).aspectRatio(aspect).clip(RoundedCornerShape(32.dp)).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    if (set != null && set.images.isNotEmpty()) TiltPreview(Modifier.fillMaxSize(), set.id)
                    else Text(t("Thêm ảnh vào một bộ để xem trước"), color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(24.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(t("Nghiêng máy hoặc chạm vào ảnh để thử"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item { SectionTitle(t("Hiệu ứng kính")) }
        item { EffectPicker(neutral, s, aspect) { e -> update { it.copy(effect = e) } } }
        item {
            AnimatedVisibility(s.effect != Effect.ORIGINAL) {
                SliderRow(t("Cường độ"), s.effectStrength, 0.2f..1.5f, { "${(it * 100).roundToInt()}%" }) { v -> update { it.copy(effectStrength = v) } }
            }
        }

        item { SectionTitle(t("Chiều sâu")) }
        item {
            SettingsCard {
                Text(t("Chủ thể"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
                com.tilt.wallpapers.ui.components.SegmentedPicker(
                    options = listOf("Parallax", "Xoay", "Tắt"),
                    selected = when {
                        s.parallax && !s.rotateSubject -> "Parallax"
                        s.rotateSubject && !s.parallax -> "Xoay"
                        else -> "Tắt"
                    },
                    label = { t(it) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    icon = { opt -> when(opt) {
                        "Parallax" -> Icons.Filled.ViewInAr
                        "Xoay" -> Icons.Filled.ScreenRotation
                        else -> null
                    }},
                    onPick = { opt ->
                        update {
                            it.copy(
                                parallax = opt == "Parallax",
                                rotateSubject = opt == "Xoay"
                            )
                        }
                    }
                )
                Text(
                    when {
                        s.parallax && !s.rotateSubject -> t("Chủ thể trượt nhiều hơn nền → cảm giác chiều sâu")
                        s.rotateSubject && !s.parallax -> t("Chủ thể xoay nhẹ theo góc nghiêng máy")
                        else -> t("Không dùng hiệu ứng chủ thể")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                SwitchRow(t("Depth – tách chủ thể"), t("AI trên máy tách người/vật lên trên lớp kính"), s.depth, Icons.Filled.Layers) { v ->
                    update { it.copy(depth = v) }
                }
                AnimatedVisibility(s.depth) {
                    Column {
                        val total = set?.images?.size ?: 0
                        val ready = set?.images?.count { it.hasMask } ?: 0
                        val (text, error) = when (val d = depth) {
                            DepthStatus.Downloading -> t("Đang tải mô hình AI…") to false
                            is DepthStatus.Processing -> t("Đang tách chủ thể %1\$s/%2\$s…", d.done + 1, d.total) to false
                            is DepthStatus.Failed -> d.message to true
                            DepthStatus.Idle -> t("Sẵn sàng · %1\$s/%2\$s ảnh của bộ hiện tại có chủ thể", ready, total) to false
                        }
                        Text(
                            text, style = MaterialTheme.typography.bodySmall,
                            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                        if (depth is DepthStatus.Failed) Box(Modifier.padding(horizontal = 12.dp)) {
                            com.tilt.wallpapers.ui.components.AppButton(t("Thử lại"), style = com.tilt.wallpapers.ui.components.BtnStyle.Text) { Repo.ensureMasks() }
                        }
                    }
                }
                AnimatedVisibility(s.parallax || s.rotateSubject || s.depth) {
                    SliderRow(t("Độ sâu"), s.depthStrength, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> update { it.copy(depthStrength = v) } }
                }
                AnimatedVisibility(s.parallax || s.rotateSubject) {
                    SliderRow(
                        t("Độ mượt"), s.smoothing, 0f..1f,
                        { when { it < 0.3f -> t("Nhanh"); it > 0.7f -> t("Êm"); else -> t("Vừa") } },
                    ) { v -> update { it.copy(smoothing = v) } }
                }
                val context = androidx.compose.ui.platform.LocalContext.current
                if (com.tilt.wallpapers.data.Automation.reduceMotion(context)) Text(
                    t("Máy đang bật \"Xóa hiệu ứng động\": hình nền tắt parallax và dùng chuyển cảnh mờ dần ngắn."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }

        item { SectionTitle(t("Đồng hồ & chữ")) }
        item {
            val nav = com.tilt.wallpapers.ui.LocalNav.current
            val clock = set?.clock
            SettingsCard {
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (set != null) Modifier.bounceClick(pressedScale = 0.97f) { nav.push(com.tilt.wallpapers.ui.Route.Clock(set.id)) } else Modifier)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t("Đồng hồ & chữ sau chủ thể"), style = MaterialTheme.typography.titleMedium)
                        val texts = set?.texts?.items?.size ?: 0
                        Text(
                            when {
                                set == null -> t("Tạo một bộ ảnh trước")
                                clock?.enabled == true && texts > 0 -> t("Đồng hồ %1\$s · %2\$s chữ", clock.layout.label, texts)
                                clock?.enabled == true -> t("Đang bật · %1\$s · %2\$s", clock.font.label, clock.layout.label)
                                texts > 0 -> t("%1\$s chữ · đồng hồ tắt", texts)
                                else -> t("Tắt · chạm để thêm đồng hồ và chữ cho bộ \"%1\$s\"", set.name)
                            },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { SectionTitle(t("Hiệu ứng mở khóa")) }
        item {
            SettingsCard {
                com.tilt.wallpapers.ui.components.SegmentedPicker(com.tilt.wallpapers.data.UnlockAnim.entries, s.unlockAnim, { it.label }, Modifier.padding(16.dp)) { v ->
                    update { it.copy(unlockAnim = v) }
                    Repo.previewUnlock()
                }
                Text(
                    t("Khi bật màn hình hoặc mở khóa: nền hết mờ và thu về từ hơi phóng to, chủ thể hiện trước nửa nhịp, đồng hồ và chữ nhẹ nhàng vào chỗ."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                )
                if (s.unlockAnim != com.tilt.wallpapers.data.UnlockAnim.OFF) {
                    SliderRow(t("Thời gian"), s.unlockSeconds, 0.6f..3f, { "%.1f s".format(it) },
                        onFinished = { Repo.previewUnlock() }) { v -> update { it.copy(unlockSeconds = (v * 10).roundToInt() / 10f) } }
                    Text(t("Chạy khi"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp))
                    com.tilt.wallpapers.ui.components.SegmentedPicker(listOf(true, false), s.unlockOnWake, { if (it) t("Bật màn hình") else t("Mở khóa") },
                        Modifier.padding(horizontal = 16.dp)) { v -> update { it.copy(unlockOnWake = v) } }
                    Text(
                        if (s.unlockOnWake) t("Chạy một lần ngay khi bật màn hình. Hợp khi màn hình khóa cũng dùng Tilt.")
                        else t("Chạy một lần lúc mở khóa vào màn hình chính. Hợp khi màn hình khóa dùng ảnh khác."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
                    )
                }
            }
        }
        item { SectionTitle(t("Chuyển cảnh khi nghiêng")) }
        item { TransitionPicker(s.transition) { t -> update { it.copy(transition = t) } } }
        item {
            AnimatedVisibility(s.transition.directional) {
                Column {
                    Text(t("Hướng"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
                    DirectionPicker(s.direction) { d -> update { it.copy(direction = d) } }
                }
            }
        }
        item {
            val zones = s.tiltMode == com.tilt.wallpapers.data.TiltMode.ZONES
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                t("Chuyển theo tay"),
                if (zones) t("Ảnh chuyển đúng bằng góc bạn nghiêng: nghiêng một nửa thì dừng ở giữa, nghiêng ngược lại thì quay về. Đẹp nhất với Flip")
                else t("Chỉ dùng được khi Cài đặt → Cách chuyển ảnh là \"Theo góc\""),
                s.followTilt && zones, Icons.Filled.TouchApp, enabled = zones,
            ) { v -> update { it.copy(followTilt = v) } }
            AnimatedVisibility(s.transition == Transition.FOLD) {
                Column {
                    Text(t("Tùy chỉnh Flip"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                    // Plays the flip back and forth with the current settings, no tilting needed.
                    val demoAspect = com.tilt.wallpapers.ui.components.screenAspect()
                    if ((set?.images?.size ?: 0) < 2) Text(
                        t("Thêm ít nhất 2 ảnh vào bộ đang dùng để xem trước Flip"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    ) else Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        com.tilt.wallpapers.ui.components.TiltPreview(
                            Modifier.height(220.dp).aspectRatio(demoAspect).clip(RoundedCornerShape(22.dp)),
                            onTapStep = false, tilt = false, demo = true,
                        )
                    }
                    SliderRow(t("Độ tối ảnh dưới"), s.foldDim, 0f..0.8f, { "${(it * 100).roundToInt()}%" }) { v -> update { it.copy(foldDim = v) } }
                    SliderRow(t("Độ mờ"), s.foldBlur, 0.5f..1.5f, { "${(it * 100).roundToInt()}%" }) { v -> update { it.copy(foldBlur = v) } }
                    SliderRow(t("Mờ lên"), s.foldOnset, 0.1f..0.8f, { if (it < 0.3f) t("Nhanh") else if (it < 0.55f) t("Vừa") else t("Chậm") }) { v -> update { it.copy(foldOnset = v) } }
                    SliderRow(t("Mép trong suốt"), s.foldClear, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> update { it.copy(foldClear = v) } }
                    val d = Settings()
                    if (s.foldDim != d.foldDim || s.foldBlur != d.foldBlur || s.foldOnset != d.foldOnset || s.foldClear != d.foldClear)
                        com.tilt.wallpapers.ui.components.AppButton(t("Đặt lại"), icon = Icons.Filled.Refresh, style = com.tilt.wallpapers.ui.components.BtnStyle.Text, modifier = Modifier.padding(start = 12.dp)) {
                            update { it.copy(foldDim = d.foldDim, foldBlur = d.foldBlur, foldOnset = d.foldOnset, foldClear = d.foldClear) }
                        }
                }
            }
            AnimatedVisibility(!(s.followTilt && zones)) {
                SliderRow(t("Thời lượng"), s.durationMs.toFloat(), 250f..1500f, { "${it.roundToInt()} ms" }) { v -> update { it.copy(durationMs = v.roundToInt()) } }
            }
        }
    }
}

@Composable
private fun EffectPicker(image: WallImage?, s: Settings, aspect: Float, onPick: (Effect) -> Unit) {
    val hf = LocalHapticFeedback.current
    var thumbs by remember { mutableStateOf<Map<Effect, ImageBitmap>>(emptyMap()) }
    val key = image?.let { listOf(it, (s.effectStrength * 10).roundToInt(), s.depth) }
    LaunchedEffect(key) {
        if (image == null) return@LaunchedEffect
        delay(250) // let slider drags settle
        thumbs = runCatching { EffectThumbs.render(image, s, aspect) }.getOrDefault(emptyMap()).mapValues { it.value.asImageBitmap() }
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(Effect.entries) { e ->
            val sel = s.effect == e
            val ring by animateDpAsState(if (sel) 3.dp else 0.dp, spring(dampingRatio = 0.5f), label = "ring")
            Column(
                Modifier.bounceClick(haptic = false) { hf.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(e) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .height(150.dp)
                        .aspectRatio(aspect)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(ring, MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp)),
                ) {
                    val t = thumbs[e]
                    when {
                        t != null -> Image(t, e.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        image != null -> EditedImage(image, Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    e.label, style = MaterialTheme.typography.labelMedium,
                    color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun Transition.icon(): ImageVector = when (this) {
    Transition.CROSSFADE -> Icons.Filled.Gradient
    Transition.FADE_BLACK -> Icons.Filled.Contrast
    Transition.SOFT_WIPE -> Icons.Filled.WbTwilight
    Transition.SLIDE -> Icons.Filled.SwipeLeft
    Transition.ZOOM -> Icons.Filled.ZoomIn
    Transition.CIRCLE -> Icons.Filled.Circle
    Transition.LIQUID -> Icons.Filled.Water
    Transition.DISSOLVE -> Icons.Filled.Grain
    Transition.FOLD -> Icons.Filled.ImportContacts
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransitionPicker(selected: Transition, onPick: (Transition) -> Unit) {
    val hf = LocalHapticFeedback.current
    FlowRow(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Transition.entries.forEach { t ->
            Choice(t.label, t.icon(), t == selected) { hf.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(t) }
        }
    }
}

private fun Direction.icon(): ImageVector = when (this) {
    Direction.AUTO -> Icons.Filled.ScreenRotation
    Direction.LEFT_TO_RIGHT -> Icons.AutoMirrored.Filled.ArrowForward
    Direction.RIGHT_TO_LEFT -> Icons.AutoMirrored.Filled.ArrowBack
    Direction.TOP_TO_BOTTOM -> Icons.Filled.ArrowDownward
    Direction.BOTTOM_TO_TOP -> Icons.Filled.ArrowUpward
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DirectionPicker(selected: Direction, onPick: (Direction) -> Unit) {
    val hf = LocalHapticFeedback.current
    FlowRow(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Direction.entries.forEach { d ->
            Choice(d.label, d.icon(), d == selected) { hf.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(d) }
        }
    }
}

/** Expressive selectable chip: morphs from rounded-rect to pill when selected. */
@Composable
fun Choice(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val radius by animateDpAsState(if (selected) 24.dp else 12.dp, spring(dampingRatio = 0.6f), label = "radius")
    Row(
        Modifier
            .bounceClick(pressedScale = 0.9f, haptic = false, onClick = onClick)
            .clip(RoundedCornerShape(radius))
            .background(if (selected) cs.primary else cs.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (selected) cs.onPrimary else cs.onSurface
        Icon(icon, null, Modifier.size(18.dp), tint = c)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = c)
    }
}
