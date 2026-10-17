package com.tilt.wallpapers.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChange
import com.tilt.wallpapers.ui.components.PreviewPill
import com.tilt.wallpapers.ui.components.Studio
import com.tilt.wallpapers.ui.components.StudioTab
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tilt.wallpapers.data.ClockDate
import com.tilt.wallpapers.data.ClockFit
import com.tilt.wallpapers.data.ClockLayout
import com.tilt.wallpapers.data.ClockPlace
import com.tilt.wallpapers.data.ClockPlacement
import com.tilt.wallpapers.data.ClockShowOn
import com.tilt.wallpapers.data.ClockText
import com.tilt.wallpapers.data.I18n
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.Side
import com.tilt.wallpapers.data.TextAlign
import com.tilt.wallpapers.data.TextArt
import com.tilt.wallpapers.data.TextPlace
import com.tilt.wallpapers.data.t
import com.tilt.wallpapers.ui.LocalNav
import com.tilt.wallpapers.ui.components.AppButton
import com.tilt.wallpapers.ui.components.BtnStyle
import com.tilt.wallpapers.ui.components.EditedImage
import com.tilt.wallpapers.ui.components.Notices
import com.tilt.wallpapers.ui.components.SegmentedPicker
import com.tilt.wallpapers.ui.components.TiltPreview
import com.tilt.wallpapers.ui.components.bounceClick
import com.tilt.wallpapers.ui.components.screenAspect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private const val CLOCK = "clock"

/**
 * "Đồng hồ & chữ": the set's clock and up to two free texts, laid out like the phone's lock-screen editor (a
 * small phone, groups of settings under it). Style for the whole set, place per picture. Tap an object on the
 * preview to edit it (again on overlapping ones to go through them); drag anywhere to move the selected one,
 * near its frame's corners / sides to resize or stretch it; pinch to zoom the preview, double tap to reset.
 */
@Composable
fun ClockScreen(setId: String) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val hf = LocalHapticFeedback.current
    val state by Repo.state.collectAsStateWithLifecycle()
    val set = state.sets.find { it.id == setId } ?: run { BackOnly(); return }
    val style = set.clock
    val layer = set.texts
    val aspect = screenAspect()
    val (seq, neutral) = remember(set.images) { set.tiltSequence() }
    var imgId by rememberSaveable { mutableStateOf(seq.getOrNull(neutral)?.id) }
    val img = seq.find { it.id == imgId } ?: seq.getOrNull(neutral)
    var mock by rememberSaveable { mutableStateOf(false) }
    var sel by rememberSaveable { mutableStateOf(CLOCK) }
    val textIdx = layer.items.indexOfFirst { it.id == sel }
    val item = layer.items.getOrNull(textIdx)
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    fun update(f: (com.tilt.wallpapers.data.ClockStyle) -> com.tilt.wallpapers.data.ClockStyle) = Repo.updateClock(setId, f)

    // This picture may use its own layout.
    val imgStyle = style.copy(layout = img?.clock?.layout ?: style.layout, hug = img?.clock?.hug ?: style.hug)
    // Re-measured each minute: the frame follows the digits being shown.
    val minute by produceState(System.currentTimeMillis() / 60_000L) { while (true) { delay(60_000L - System.currentTimeMillis() % 60_000L + 50L); value = System.currentTimeMillis() / 60_000L } }
    val shape = remember(imgStyle.shapeKey(), I18n.lang, minute) { ClockText.shape(ctx, imgStyle) }
    val textShapes = remember(layer.items) { layer.items.associate { it.id to TextArt.shape(ctx, it) } }
    val cov by produceState<ClockPlace.Coverage?>(null, img?.id, img?.subjectRev, img?.edit, img?.hasMask, img?.hasManualMask, state.settings.depth) {
        value = img?.let { withContext(Dispatchers.Default) { ClockPlace.coverage(it, state.settings.depth, aspect) } }
    }
    // Drags in progress; written to the picture once the fingers rest.
    var draft by remember(img?.id) { mutableStateOf<ClockPlacement?>(null) }
    var textDraft by remember(img?.id) { mutableStateOf<Pair<String, ClockPlacement>?>(null) }
    val placement = draft ?: img?.clock ?: ClockPlace.fallback(imgStyle)
    fun textPlacement(id: String) = textDraft?.takeIf { it.first == id }?.second ?: img?.texts?.get(id)
    LaunchedEffect(draft) { val d = draft ?: return@LaunchedEffect; delay(300); img?.let { Repo.setClockPlacement(it.id, d) } }
    LaunchedEffect(textDraft) { val (id, p) = textDraft ?: return@LaunchedEffect; delay(300); img?.let { Repo.setTextPlacement(it.id, id, p) } }

    // Texts share one layer: on top for this picture if the set says so or there was no room behind the subject.
    val textTop = layer.onTop || layer.items.any { img?.texts?.get(it.id)?.forcedTop == true }
    LaunchedEffect(img?.id, textTop && !layer.onTop) {
        if (textTop && !layer.onTop) Notices.show(t("Ảnh này hết chỗ sau chủ thể nên chữ tự nằm trên cùng"))
    }
    /** What text [i] makes way for on this picture: the subject (behind it), the clock, Chữ 1. */
    fun textCov(i: Int): ClockPlace.Coverage? {
        val clockRects = if (layer.avoidClock && style.enabled) TextPlace.rects(placement, shape, aspect) else emptyList()
        val firstRects = layer.items.getOrNull(0)?.takeIf { i > 0 && layer.avoidEach }?.let { f ->
            val p0 = textPlacement(f.id); val s0 = textShapes[f.id]?.first
            if (p0 != null && s0 != null && !p0.hidden) TextPlace.rects(p0, s0, aspect) else null
        }.orEmpty()
        return TextPlace.grid(if (!textTop && layer.avoidSubject) cov else null, aspect, clockRects + firstRects)
    }

    // The object being edited, as something the preview's gestures can move.
    val clockHug = placement.hugOn(style) && !placement.topOn(style)
    val first = layer.items.getOrNull(0)?.let { textPlacement(it.id) }
    val tc = remember(cov, placement, shape, layer, textTop, textIdx, first, textShapes) { if (item != null) textCov(textIdx) else null }
    val target: EditTarget? = when {
        item != null -> {
            val p = textPlacement(item.id); val ts = textShapes[item.id]?.first
            if (p == null || ts == null || p.hidden) null else EditTarget(
                p, ts, tc,
                land = { q, c -> TextPlace.land(c, q, ts, aspect, layer.hug) },
                fits = { q, c -> TextPlace.fits(c, q, ts, aspect) },
                draft = { textDraft = item.id to it },
                save = { q -> img?.let { Repo.setTextPlacement(it.id, item.id, q) }; textDraft = null },
            )
        }
        style.enabled -> EditTarget(
            placement, shape, cov,
            land = { p, c -> val q = ClockPlace.onScreen(p, shape, aspect); if (clockHug) ClockPlace.hug(c, q, shape, aspect) else q.copy(sq = emptyList(), hx = 1f) },
            fits = { p, c -> !clockHug || c == null || ClockPlace.cover(c, p, shape, aspect) <= ClockPlace.WALL },
            draft = { draft = it },
            save = { q -> img?.let { Repo.setClockPlacement(it.id, q) }; draft = null },
        )
        else -> null
    }
    // Every object on this picture (tap to select).
    val boxes = buildList {
        layer.items.forEach { ti ->
            val p = textPlacement(ti.id); val s = textShapes[ti.id]?.first
            if (p != null && s != null && !p.hidden) add(ti.id to frameBox(p, s, aspect))
        }
        if (style.enabled) add(CLOCK to frameBox(placement, shape, aspect))
    }

    val tabs = if (item == null) listOfNotNull(
        StudioTab("clock", t("Đồng hồ"), Icons.Filled.Schedule),
        StudioTab("look", t("Kiểu"), Icons.Filled.TextFields).takeIf { style.enabled },
        StudioTab("color", t("Màu"), Icons.Filled.Palette).takeIf { style.enabled },
        StudioTab("subject", t("Chủ thể"), Icons.Filled.Layers).takeIf { style.enabled },
        StudioTab("image", t("Ảnh này"), Icons.Filled.Image).takeIf { style.enabled && img != null },
        StudioTab("motion", t("Chuyển động"), Icons.Filled.Animation).takeIf { style.enabled || layer.items.isNotEmpty() },
    ) else listOfNotNull(
        StudioTab("content", t("Nội dung"), Icons.Filled.EditNote),
        StudioTab("look", t("Kiểu"), Icons.Filled.TextFields),
        StudioTab("color", t("Màu"), Icons.Filled.Palette),
        StudioTab("layer", t("Lớp & né"), Icons.Filled.Layers),
        StudioTab("image", t("Ảnh này"), Icons.Filled.Image).takeIf { img != null },
        StudioTab("motion", t("Chuyển động"), Icons.Filled.Animation),
    )
    // A group that the newly selected object doesn't have closes.
    val openNow = open?.takeIf { k -> tabs.any { it.key == k } }

    Studio(
        title = t("Đồng hồ & chữ"),
        onBack = { nav.pop() },
        aspect = aspect,
        tabs = tabs,
        open = openNow,
        onOpen = { open = it },
        actions = { PreviewPill(t("Xong"), onClick = { nav.pop() }) },
        strip = {
            // Which object: the clock, Chữ 1, Chữ 2, or add a text.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ObjectChip(t("Đồng hồ"), Icons.Filled.Schedule, item == null) { sel = CLOCK }
                layer.items.forEachIndexed { i, ti ->
                    ObjectChip(t("Chữ %1\$s", i + 1) + (ti.text.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), Icons.Filled.TextFields, sel == ti.id) { sel = ti.id }
                }
                if (layer.items.size < 2) ObjectChip(t("Thêm chữ"), Icons.Filled.Add, false) {
                    hf.performHapticFeedback(HapticFeedbackType.Confirm)
                    Repo.addText(setId)?.let { sel = it; open = "content" }
                }
            }
        },
        overlay = {
            if (img != null) Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tag = when {
                    item != null -> { val p = textPlacement(item.id); val ts = textShapes[item.id]?.first
                        if (p == null || ts == null) null else if (textTop && tc == null) t("Trên cùng") to false
                        else ClockPlace.cover(tc, p, ts, aspect).let { t("Che %1\$s%%", (it * 100).roundToInt()) to (it > ClockPlace.WALL) } }
                    style.enabled -> if (placement.topOn(style)) t("Trên cùng") to false
                        else ClockPlace.cover(cov, placement, shape, aspect).let { t("Che %1\$s%%", (it * 100).roundToInt()) to (it > (if (clockHug) ClockPlace.WALL else ClockPlace.MAX_COVER)) }
                    else -> null
                }
                tag?.let { (text, warn) ->
                    Text(text, style = MaterialTheme.typography.labelMedium, color = if (warn) Color.Black else Color.White,
                        modifier = Modifier.clip(CircleShape).background(if (warn) Color(0xFFFFB74D) else Color.Black.copy(alpha = 0.45f)).padding(horizontal = 10.dp, vertical = 5.dp))
                }
                Spacer(Modifier.weight(1f))
                // Which picture is edited.
                if (seq.size > 1) {
                    val i = seq.indexOfFirst { it.id == img.id }
                    Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, t("Ảnh trước"), tint = Color.White,
                            modifier = Modifier.size(34.dp).bounceClick(pressedScale = 0.85f) { seq.getOrNull(i - 1)?.let { imgId = it.id } }.padding(5.dp))
                        Text(t("Ảnh %1\$s/%2\$s", i + 1, seq.size), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, t("Ảnh sau"), tint = Color.White,
                            modifier = Modifier.size(34.dp).bounceClick(pressedScale = 0.85f) { seq.getOrNull(i + 1)?.let { imgId = it.id } }.padding(5.dp))
                    }
                }
            }
        },
        preview = {
            if (img == null) Text(t("Thêm ảnh vào bộ để đặt đồng hồ"), color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            else EditablePreview(
                key = img.id, target = target, boxes = boxes, sel = if (item == null) CLOCK else item.id, aspect = aspect,
                onSelect = { id -> hf.performHapticFeedback(HapticFeedbackType.SegmentTick); sel = id },
                onTapEmpty = { open = null },
                onDragStart = { open = null },
            ) {
                val override = remember(state, img.id, placement, textDraft) {
                    val texts = textDraft?.let { (id, p) -> img.texts + (id to p) } ?: img.texts
                    state.copy(
                        activeSetId = setId,
                        sets = state.sets.map { if (it.id == setId) it.copy(images = listOf(img.copy(side = Side.CENTER, clock = placement, texts = texts))) else it },
                    )
                }
                TiltPreview(Modifier.fillMaxSize(), setId, onTapStep = false, override = override)
                if (mock || state.settings.clockShowOn != ClockShowOn.HOME) LockMock(Modifier.fillMaxSize(), full = mock)
            }
        },
    ) { key ->
        when (key) {
            "clock" -> SettingsCard(Modifier.padding(top = 4.dp)) {
                SwitchRow(t("Hiện đồng hồ"), t("Đồng hồ lớn nằm sau chủ thể"), style.enabled, Icons.Filled.Schedule) { v -> update { it.copy(enabled = v) } }
                if (style.enabled) {
                    Label(t("Chiều đồng hồ"))
                    SegmentedPicker(ClockLayout.entries, style.layout, { it.label }, Modifier.padding(horizontal = 16.dp)) { v -> update { it.copy(layout = v) } }
                    Label(t("Ngày"))
                    SegmentedPicker(ClockDate.entries, style.date, { it.label }, Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) { v -> update { it.copy(date = v) } }
                }
            }
            "look" -> if (item == null) TypeLookCard(state, style, "10:20") { f -> update(f) }
                else TypeLookCard(state, item.style, "Aa") { f -> Repo.updateText(setId, item.id) { it.copy(style = f(it.style)) } }
            "color" -> if (item == null) TypeColorCard(state, setId, style) { f -> update(f) }
                else TypeColorCard(state, setId, item.style) { f -> Repo.updateText(setId, item.id) { it.copy(style = f(it.style)) } }
            "subject" -> SettingsCard {
                SwitchRow(
                    t("Bám chủ thể"),
                    t("Chữ số nào có chủ thể bên dưới thì tự co ngắn lại, luôn chừa một khoảng cách. Dùng cả chủ thể AI và chủ thể ngoài"),
                    style.hug,
                ) { v -> update { it.copy(hug = v) } }
                Label(t("Lớp đồng hồ"))
                SegmentedPicker(listOf(false, true), style.onTop, { if (it) t("Trên cùng") else t("Sau chủ thể") }, Modifier.padding(horizontal = 16.dp)) { v ->
                    update { it.copy(onTop = v) }
                }
                SliderRow(t("Xuyên chủ thể"), style.seeThrough, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> update { it.copy(seeThrough = v) } }
                Label(t("Tự đặt"))
                SegmentedPicker(ClockFit.entries, style.fit, { it.label }, Modifier.padding(horizontal = 16.dp)) { v -> update { it.copy(fit = v) } }
                Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    AppButton(t("Tự đặt tất cả"), icon = Icons.Filled.AutoFixHigh, style = BtnStyle.Tonal) {
                        hf.performHapticFeedback(HapticFeedbackType.Confirm); draft = null; Repo.refitClock(setId)
                    }
                }
            }
            "image" -> if (img != null) Column {
                // Fine moves and size for the selected object, for what is too small to drag precisely.
                target?.let { tg -> NudgeCard(tg) }
                if (item == null) ClockImageCard(img, style, imgStyle) { draft = null }
                else TextImageCard(img.id, item.id, img.texts[item.id], textTop && !layer.onTop) { textDraft = null }
            }
            "content" -> if (item != null) TextContentCard(setId, item, style.enabled) { sel = CLOCK }
            "layer" -> if (item != null) TextLayerCard(setId, layer, textIdx, style.enabled) { textDraft = null }
            "motion" -> SettingsCard {
                SliderRow(t("Độ sâu"), style.depth, 0f..1f, {
                    when { it < 0.05f -> t("Đứng yên"); it < 0.4f -> t("Theo nền"); it < 0.75f -> t("Giữa"); else -> t("Theo chủ thể") }
                }) { v -> update { it.copy(depth = v) } }
                Label(t("Khi chuyển ảnh"))
                SegmentedPicker(listOf(false, true), style.followImage, { if (it) t("Theo ảnh") else t("Đứng yên") }, Modifier.padding(horizontal = 16.dp)) { v ->
                    update { it.copy(followImage = v) }
                }
                Text(
                    (if (style.followImage) t("Đồng hồ và chữ như in lên từng ảnh, lật và trượt cùng ảnh.")
                    else t("Đồng hồ và chữ ở yên trên màn hình và trượt mượt tới vị trí của ảnh mới.")) +
                        (if (state.settings.parallax) " " + t("Hiệu ứng khi chuyển ảnh thật sẽ khác bản xem trước nếu bật parallax") else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                Label(t("Hiện ở"))
                SegmentedPicker(ClockShowOn.entries, state.settings.clockShowOn, { it.label }, Modifier.padding(horizontal = 16.dp)) { v ->
                    Repo.updateSettings { it.copy(clockShowOn = v) }
                }
                AnimatedVisibility(state.settings.clockShowOn != ClockShowOn.HOME) {
                    Text(
                        if (isSamsung()) t("Hình nền không ẩn được đồng hồ của máy trên màn hình khóa. Trên Samsung, ẩn nó bằng Good Lock → LockStar để không bị hai đồng hồ. Vùng mờ trên ảnh là chỗ đồng hồ của máy.")
                        else t("Hình nền không ẩn được đồng hồ của máy trên màn hình khóa, nên sẽ có hai đồng hồ. Vùng mờ trên ảnh là chỗ đồng hồ của máy."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
                SwitchRow(t("Xem với màn hình khóa"), t("Phủ đồng hồ và nút của máy lên ảnh xem trước"), mock) { mock = it }
            }
        }
    }
}

/** The selected object for the preview's gestures: where it is, its shape, and how it lands, fits and is saved. */
private class EditTarget(
    val placement: ClockPlacement, val shape: ClockText.Shape, val cov: ClockPlace.Coverage?,
    val land: (ClockPlacement, ClockPlace.Coverage?) -> ClockPlacement,
    val fits: (ClockPlacement, ClockPlace.Coverage?) -> Boolean,
    /** While dragging (saved shortly after the fingers rest). */
    val draft: (ClockPlacement) -> Unit,
    /** Right away (nudges, size). */
    val save: (ClockPlacement) -> Unit,
) {
    /** The landed move from here to [wanted]; with a wall, a move into an obstacle keeps only the part that slides along it. */
    fun step(good: ClockPlacement, wanted: ClockPlacement): ClockPlacement {
        val cand = land(wanted, cov)
        if (fits(cand, cov) || !fits(good, cov)) return cand
        land(wanted.copy(cy = good.cy), cov).let { if (fits(it, cov)) return it }
        land(wanted.copy(cx = good.cx), cov).let { if (fits(it, cov)) return it }
        return good
    }
}

/**
 * The preview with the selected object's frame. One touch area for everything: a tap selects (going through
 * overlapping objects), a drag moves the selected object from anywhere (or resizes / stretches it from near
 * its frame's corners / sides, with a 48 dp reach however small the frame), two fingers zoom the view (up to
 * 3x) and a double tap resets it.
 */
@Composable
private fun EditablePreview(
    key: Any, target: EditTarget?, boxes: List<Pair<String, FloatArray>>, sel: String, aspect: Float,
    onSelect: (String) -> Unit, onTapEmpty: () -> Unit, onDragStart: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val hf = LocalHapticFeedback.current
    val density = LocalDensity.current
    var zoom by remember(key) { mutableFloatStateOf(1f) }
    var pan by remember(key) { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var snapped by remember { mutableStateOf(false) }
    val targetNow by rememberUpdatedState(target)
    val boxesNow by rememberUpdatedState(boxes)
    val selNow by rememberUpdatedState(sel)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        Box(
            Modifier.fillMaxSize().pointerInput(key, w, h) {
                val reach = 24.dp.toPx()
                val margin = 18.dp.toPx()
                var lastTap = 0L
                fun clampPan(p: Offset) = Offset(p.x.coerceIn(w - w * zoom, 0f), p.y.coerceIn(h - h * zoom, 0f))
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = (down.position - pan) / zoom
                    val tg = targetNow
                    var work = tg?.placement; var good = work; var rawCx = work?.cx ?: 0f
                    // Where the finger landed decides the drag: near a corner resize, near a side stretch, else move.
                    var mode = 0; var sxSign = 1f; var sySign = 1f
                    if (tg != null) {
                        val p = tg.placement
                        val fw = ClockPlace.bwOf(p, tg.shape, aspect) * w; val fh = p.h * h
                        val fx = start.x - (p.cx * w - fw / 2); val fy = start.y - (p.cy * h - fh / 2)
                        val near = maxOf(reach / zoom, minOf(margin * 1.2f, 0.28f * minOf(fw, fh)))
                        val nl = abs(fx) < near; val nr = abs(fx - fw) < near
                        val nt = abs(fy) < near; val nb = abs(fy - fh) < near
                        sxSign = if (nl && !nr || (nl && nr && fx < fw / 2)) -1f else 1f
                        sySign = if (nt && !nb || (nt && nb && fy < fh / 2)) -1f else 1f
                        mode = when {
                            (nl || nr) && (nt || nb) -> 1
                            (nl || nr) && fy in 0f..fh -> 2
                            (nt || nb) && fx in 0f..fw -> 3
                            else -> 0
                        }
                    }
                    var moved = false; var zooming = false
                    var total = Offset.Zero
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            // Two fingers: zoom the view around them.
                            zooming = true; moved = true
                            val c = ev.calculateCentroid(useCurrent = true)
                            val nz = (zoom * ev.calculateZoom()).coerceIn(1f, 3f)
                            val under = (c - pan) / zoom
                            pan = clampPan(c - under * nz + ev.calculatePan())
                            zoom = nz
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        if (zooming) { ev.changes.forEach { it.consume() }; continue }
                        val ch = pressed.first()
                        val d = ch.positionChange()
                        total += d
                        if (!moved && total.getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                            if (tg != null) { dragging = true; onDragStart() }
                        }
                        if (!moved || tg == null || work == null || good == null) continue
                        ch.consume()
                        val dx = d.x / zoom; val dy = d.y / zoom
                        when (mode) {
                            0 -> {
                                rawCx = (rawCx + dx / w).coerceIn(0.03f, 0.97f)
                                val snap = abs(rawCx - 0.5f) < 0.015f
                                if (snap && !snapped) hf.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                snapped = snap
                                work = work.copy(cx = if (snap) 0.5f else rawCx, cy = (work.cy + dy / h).coerceIn(0.03f, 0.97f), auto = false)
                                good = tg.step(good, work)
                            }
                            else -> {
                                val bwNow = ClockPlace.bwOf(work, tg.shape, aspect)
                                work = when (mode) {
                                    1 -> work.copy(h = (work.h + (dx * sxSign / w * work.h / bwNow + dy * sySign / h) * 2f).coerceIn(0.04f, 0.85f), auto = false)
                                    2 -> {
                                        // Wider / narrower, never past the screen. (A wide clock touching both edges made this range
                                        // empty, which threw and closed the app.)
                                        val natural = bwNow / work.sx
                                        val nb = (bwNow + 2f * sxSign * dx / w).safeIn(maxOf(0.06f, natural * 0.2f), minOf(0.98f, natural * 3f))
                                        work.copy(sx = (work.sx * nb / bwNow).coerceIn(0.2f, 3f), auto = false)
                                    }
                                    else -> {
                                        // Taller with the width kept: up to five times the font's own height, 96% of the screen.
                                        val nh = (work.h + 2f * sySign * dy / h).safeIn(maxOf(0.04f, work.h * work.sx / 3f), minOf(0.96f, work.h * work.sx / 0.2f))
                                        work.copy(h = nh, sx = (work.sx * work.h / nh).coerceIn(0.2f, 3f), auto = false)
                                    }
                                }
                                val c2 = tg.land(work, tg.cov)
                                if (tg.fits(c2, tg.cov) || !tg.fits(good, tg.cov)) good = c2
                            }
                        }
                        good?.let { tg.draft(it) }
                    }
                    dragging = false; snapped = false
                    if (!moved) {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 300 && zoom > 1f) { zoom = 1f; pan = Offset.Zero; lastTap = 0L; return@awaitEachGesture }
                        lastTap = now
                        // Tap: the objects under the finger; again on the selected one goes to the next one under it.
                        val u = start.x / w; val v = start.y / h
                        val hits = boxesNow.filter { (_, b) -> u in b[0]..b[2] && v in b[1]..b[3] }.map { it.first }
                        when {
                            hits.isEmpty() -> onTapEmpty()
                            selNow in hits -> hits[(hits.indexOf(selNow) + 1) % hits.size].let { if (it != selNow) onSelect(it) }
                            else -> onSelect(hits.first())
                        }
                    }
                }
            },
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y; transformOrigin = TransformOrigin(0f, 0f) }) {
                content()
                target?.let { tg -> FrameVisual(tg.placement, tg.shape, aspect, w, h, dragging, density) }
                if (snapped && dragging) Box(Modifier.fillMaxHeight().width(1.dp).align(Alignment.Center).background(Color.White.copy(alpha = 0.6f)))
            }
        }
    }
}

/** The selected object's frame: small rounded-square corners and short side bars (drawn only; the preview handles touch). */
@Composable
private fun BoxScope.FrameVisual(p: ClockPlacement, shape: ClockText.Shape, aspect: Float, w: Float, h: Float, dragging: Boolean, density: androidx.compose.ui.unit.Density) {
    val accent = MaterialTheme.colorScheme.primary
    val bw = ClockPlace.bwOf(p, shape, aspect)
    val left = (p.cx - bw / 2) * w; val top = (p.cy - p.h / 2) * h
    Box(
        Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .requiredSize(with(density) { (bw * w).toDp() }, with(density) { (p.h * h).toDp() })
            .border(1.dp, Color.White.copy(alpha = if (dragging) 0.95f else 0.6f), RoundedCornerShape(6.dp)),
    ) {
        listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd).forEach { corner ->
            val lx = corner == Alignment.TopStart || corner == Alignment.BottomStart
            val ty = corner == Alignment.TopStart || corner == Alignment.TopEnd
            Box(Modifier.align(corner).offset(x = if (lx) (-6).dp else 6.dp, y = if (ty) (-6).dp else 6.dp)
                .size(12.dp).clip(RoundedCornerShape(4.dp)).background(Color.White).border(1.5.dp, accent, RoundedCornerShape(4.dp)))
        }
        listOf(Alignment.CenterStart, Alignment.CenterEnd, Alignment.TopCenter, Alignment.BottomCenter).forEach { side ->
            val horizontal = side == Alignment.CenterStart || side == Alignment.CenterEnd
            val sign = if (side == Alignment.CenterStart || side == Alignment.TopCenter) -1f else 1f
            Box(Modifier.align(side).offset(x = if (horizontal) (sign * 3f).dp else 0.dp, y = if (!horizontal) (sign * 3f).dp else 0.dp)
                .size(if (horizontal) 6.dp else 22.dp, if (horizontal) 22.dp else 6.dp)
                .clip(RoundedCornerShape(3.dp)).background(Color.White).border(1.dp, accent, RoundedCornerShape(3.dp)))
        }
    }
}

/** Arrows that move the selected object a little, and its size: for what is too small to drag precisely. */
@Composable
private fun NudgeCard(tg: EditTarget) {
    val p = tg.placement
    fun go(f: (ClockPlacement) -> ClockPlacement) = tg.save(tg.step(p, f(p).copy(auto = false)))
    SettingsCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Dời từng chút"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            listOf(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft to { q: ClockPlacement -> q.copy(cx = (q.cx - 0.004f).coerceIn(0.03f, 0.97f)) },
                Icons.Filled.KeyboardArrowUp to { q: ClockPlacement -> q.copy(cy = (q.cy - 0.003f).coerceIn(0.03f, 0.97f)) },
                Icons.Filled.KeyboardArrowDown to { q: ClockPlacement -> q.copy(cy = (q.cy + 0.003f).coerceIn(0.03f, 0.97f)) },
                Icons.AutoMirrored.Filled.KeyboardArrowRight to { q: ClockPlacement -> q.copy(cx = (q.cx + 0.004f).coerceIn(0.03f, 0.97f)) },
            ).forEach { (icon, f) ->
                Icon(icon, null, Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .bounceClick(pressedScale = 0.85f) { go(f) }.padding(10.dp))
                Spacer(Modifier.width(6.dp))
            }
        }
        SliderRow(t("Cỡ"), p.h, 0.04f..0.85f, { "${(it * 100).roundToInt()}%" }) { v -> go { it.copy(h = v) } }
    }
}

/** [coerceIn] that never throws: when the limits cross (min > max), the max wins. */
private fun Float.safeIn(min: Float, max: Float) = if (min > max) max else coerceIn(min, max)

/** An object's box on screen (l, t, r, b as fractions). */
private fun frameBox(p: ClockPlacement, shape: ClockText.Shape, aspect: Float): FloatArray {
    val bw = ClockPlace.bwOf(p, shape, aspect)
    return floatArrayOf(p.cx - bw / 2, p.cy - p.h / 2, p.cx + bw / 2, p.cy + p.h / 2)
}

@Composable
private fun ObjectChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(CircleShape).background(if (selected) cs.primaryContainer else cs.surfaceContainerHigh)
            .bounceClick(pressedScale = 0.93f, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) cs.onPrimaryContainer else cs.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
    }
}

/** "Ảnh này" for the clock. */
@Composable
private fun ClockImageCard(img: com.tilt.wallpapers.data.WallImage, style: com.tilt.wallpapers.data.ClockStyle, imgStyle: com.tilt.wallpapers.data.ClockStyle, clearDraft: () -> Unit) {
    val hf = LocalHapticFeedback.current
    val base = img.clock ?: ClockPlace.fallback(imgStyle)
    fun setImg(f: (ClockPlacement) -> ClockPlacement) { clearDraft(); Repo.setClockPlacement(img.id, f(base)) }
    Column {
        SectionTitle(t("Ảnh này"))
        SettingsCard {
            Text(
                t("Kéo ở đâu trên ảnh để dời, kéo gần góc khung để đổi cỡ, gần cạnh để kéo giãn. Chụm hai ngón để phóng to."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Label(t("Chiều đồng hồ"))
            SegmentedPicker(listOf<ClockLayout?>(null) + ClockLayout.entries, img.clock?.layout, { it?.label ?: t("Theo bộ") }, Modifier.padding(horizontal = 16.dp)) { v ->
                // A new layout is a new shape: place it afresh.
                setImg { b -> ClockPlace.fallback(style.copy(layout = v ?: style.layout)).copy(layout = v, auto = true, key = 0, layer = b.layer, hug = b.hug) }
            }
            Label(t("Bám chủ thể"))
            SegmentedPicker(listOf<Boolean?>(null, true, false), base.hug, { when (it) { null -> t("Theo bộ"); true -> t("Bật"); false -> t("Tắt") } }, Modifier.padding(horizontal = 16.dp)) { v ->
                setImg { b -> b.copy(hug = v, key = 0) }
            }
            Label(t("Lớp đồng hồ"))
            SegmentedPicker(listOf(0, 1, 2), base.layer, { when (it) { 0 -> t("Theo bộ"); 1 -> t("Sau chủ thể"); else -> t("Trên cùng") } }, Modifier.padding(horizontal = 16.dp)) { v ->
                setImg { b -> b.copy(layer = v) }
            }
            if (base.forcedTop && base.layer == 0) Text(
                t("Ảnh này ít chỗ trống nên đồng hồ tự nằm trên cùng."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(t("Tự đặt ảnh này"), icon = Icons.Filled.AutoFixHigh, style = BtnStyle.Tonal) {
                    hf.performHapticFeedback(HapticFeedbackType.Confirm)
                    setImg { b -> b.copy(auto = true, key = 0, sx = 1f) }
                }
                if (base.sx != 1f) AppButton(t("Bỏ kéo giãn"), style = BtnStyle.Text) { setImg { b -> b.copy(sx = 1f, key = 0) } }
            }
        }
    }
}

/** "Ảnh này" for a text: hide it here, place it again, undo the stretch. */
@Composable
private fun TextImageCard(imageId: String, textId: String, p: ClockPlacement?, forcedTop: Boolean, clearDraft: () -> Unit) {
    val hf = LocalHapticFeedback.current
    fun set(f: (ClockPlacement) -> ClockPlacement) { clearDraft(); p?.let { Repo.setTextPlacement(imageId, textId, f(it)) } }
    Column {
        SectionTitle(t("Ảnh này"))
        SettingsCard {
            Text(
                t("Kéo ở đâu trên ảnh để dời, kéo gần góc khung để đổi cỡ, gần cạnh để kéo giãn. Chụm hai ngón để phóng to."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            SwitchRow(t("Ẩn trên ảnh này"), t("Chữ vẫn ở trong bộ, chỉ không hiện trên ảnh này"), p?.hidden == true, enabled = p != null) { v -> set { it.copy(hidden = v) } }
            if (forcedTop) Text(
                t("Ảnh này hết chỗ sau chủ thể nên chữ tự nằm trên cùng."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(t("Tự đặt ảnh này"), icon = Icons.Filled.AutoFixHigh, style = BtnStyle.Tonal) {
                    hf.performHapticFeedback(HapticFeedbackType.Confirm)
                    set { it.copy(auto = true, sx = 1f) }
                }
                if (p != null && p.sx != 1f) AppButton(t("Bỏ kéo giãn"), style = BtnStyle.Text) { set { it.copy(sx = 1f) } }
            }
        }
    }
}

/** The words of a text (2 lines of 12, Enter for a new line), alignment, copying the clock's look, deleting. */
@Composable
private fun TextContentCard(setId: String, item: com.tilt.wallpapers.data.TextItem, clockOn: Boolean, onDeleted: () -> Unit) {
    var field by remember(item.id) { mutableStateOf(TextFieldValue(item.text, TextRange(item.text.length))) }
    val clock = Repo.state.collectAsStateWithLifecycle().value.sets.find { it.id == setId }?.clock
    Column {
        SectionTitle(t("Nội dung"))
        SettingsCard {
            OutlinedTextField(
                value = field,
                onValueChange = { v ->
                    if (TextArt.hasEmoji(v.text)) Notices.show(t("Chữ không dùng được emoji"))
                    val clean = TextArt.clean(v.text)
                    field = v.copy(text = clean, selection = TextRange(v.selection.start.coerceAtMost(clean.length), v.selection.end.coerceAtMost(clean.length)))
                    if (clean != item.text) Repo.updateText(setId, item.id) { it.copy(text = clean) }
                },
                minLines = 2, maxLines = 2,
                supportingText = {
                    Text(field.text.split('\n').joinToString("  ·  ") { "${TextArt.count(it)}/${TextArt.MAX_CHARS}" } + "   " +
                        t("Tối đa %1\$s dòng", TextArt.MAX_LINES))
                },
                textStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Label(t("Căn lề"))
            SegmentedPicker(TextAlign.entries, item.align, { it.label }, Modifier.padding(horizontal = 16.dp)) { v -> Repo.updateText(setId, item.id) { it.copy(align = v) } }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (clockOn && clock != null) AppButton(t("Dùng kiểu của đồng hồ"), icon = Icons.Filled.Schedule, style = BtnStyle.Tonal) {
                    Repo.updateText(setId, item.id) { it.copy(style = it.style.lookOf(clock)) }
                }
                AppButton(t("Xóa chữ"), icon = Icons.Filled.Delete, style = BtnStyle.Danger) { Repo.removeText(setId, item.id); onDeleted() }
            }
        }
    }
}

/** Layer and what the texts make way for (shared by both texts), order, refitting. */
@Composable
private fun TextLayerCard(setId: String, layer: com.tilt.wallpapers.data.TextLayer, idx: Int, clockOn: Boolean, clearDraft: () -> Unit) {
    val hf = LocalHapticFeedback.current
    fun upd(f: (com.tilt.wallpapers.data.TextLayer) -> com.tilt.wallpapers.data.TextLayer) = Repo.updateTexts(setId, f)
    val item = layer.items[idx]
    Column {
        SectionTitle(t("Lớp & né"))
        SettingsCard {
            Label(t("Lớp của chữ"))
            SegmentedPicker(listOf(false, true), layer.onTop, { if (it) t("Trên cùng") else t("Sau chủ thể") }, Modifier.padding(horizontal = 16.dp)) { v -> upd { it.copy(onTop = v) } }
            Text(
                t("Hai chữ luôn chung một lớp. Hết chỗ sau chủ thể thì chữ tự lên trên cùng ở ảnh đó."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (!layer.onTop) SwitchRow(t("Né chủ thể"), t("Chữ không chui vào sau chủ thể"), layer.avoidSubject) { v -> upd { it.copy(avoidSubject = v) } }
            if (clockOn) SwitchRow(t("Né đồng hồ"), t("Tắt thì chữ chồng lên đồng hồ được (đồng hồ nằm trên)"), layer.avoidClock) { v -> upd { it.copy(avoidClock = v) } }
            if (layer.items.size == 2) SwitchRow(t("Hai chữ né nhau"), t("Chữ 2 nhường chỗ cho Chữ 1. Tắt thì hai chữ chồng lên nhau được"), layer.avoidEach) { v -> upd { it.copy(avoidEach = v) } }
            SwitchRow(t("Bám"), t("Ký tự nào có vật cản bên dưới thì tự co ngắn lại, luôn chừa một khoảng cách"), layer.hug) { v -> upd { it.copy(hug = v) } }
            if (!layer.onTop) SliderRow(t("Xuyên chủ thể"), item.style.seeThrough, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v ->
                Repo.updateText(setId, item.id) { it.copy(style = it.style.copy(seeThrough = v)) }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (layer.items.size == 2) {
                    AppButton(t("Đổi thứ tự"), icon = Icons.Filled.SwapVert, style = BtnStyle.Tonal) {
                        hf.performHapticFeedback(HapticFeedbackType.SegmentTick); upd { it.copy(items = it.items.reversed(), front = 1 - it.front) }
                    }
                    if (layer.front != idx) AppButton(t("Lên trên"), style = BtnStyle.Tonal) { upd { it.copy(front = idx) } }
                }
                AppButton(t("Tự đặt tất cả"), icon = Icons.Filled.AutoFixHigh, style = BtnStyle.Tonal) {
                    hf.performHapticFeedback(HapticFeedbackType.Confirm); clearDraft(); Repo.refitTexts(setId)
                }
            }
        }
    }
}

fun isSamsung() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

private fun isColorOs() = Build.MANUFACTURER.lowercase().let { it == "oppo" || it == "realme" || it == "oneplus" }

/**
 * The phone's own lock screen drawn over a preview: its clock area (by maker) and, with [full], the clock,
 * date and corner shortcuts, so the user sees whether the layout works with them.
 */
@Composable
fun LockMock(modifier: Modifier, full: Boolean) {
    // Clock area as fractions of the screen: left, top, right, bottom.
    val zone = when {
        isSamsung() -> floatArrayOf(0.12f, 0.1f, 0.88f, 0.3f)
        isColorOs() -> floatArrayOf(0.06f, 0.07f, 0.72f, 0.24f)
        else -> floatArrayOf(0.15f, 0.09f, 0.85f, 0.25f)
    }
    BoxWithConstraints(modifier) {
        val w = maxWidth; val h = maxHeight
        Box(
            Modifier.offset(w * zone[0], h * zone[1]).size(w * (zone[2] - zone[0]), h * (zone[3] - zone[1]))
                .clip(RoundedCornerShape(12.dp))
                .then(if (full) Modifier else Modifier.background(Color.White.copy(alpha = 0.16f)).border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp))),
            contentAlignment = if (isColorOs()) Alignment.CenterStart else Alignment.Center,
        ) {
            if (full) {
                val now = remember { java.util.Date() }
                Column(horizontalAlignment = if (isColorOs()) Alignment.Start else Alignment.CenterHorizontally) {
                    Text(java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(now), color = Color.White, fontSize = (h.value * 0.022f).sp)
                    Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(now), color = Color.White, fontWeight = FontWeight.Light, fontSize = (h.value * 0.1f).sp)
                }
            } else Text(t("Đồng hồ máy"), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        if (full) {
            val d = w * 0.12f
            Box(Modifier.offset(w * 0.08f, h * 0.9f - d / 2).size(d).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)))
            Box(Modifier.offset(w * 0.92f - d, h * 0.9f - d / 2).size(d).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)))
        }
    }
}
