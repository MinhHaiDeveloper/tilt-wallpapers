package com.tilt.wallpapers.ui.screens

import com.tilt.wallpapers.data.t

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isFinite
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.SubjectMasks
import com.tilt.wallpapers.ui.LocalNav
import com.tilt.wallpapers.ui.components.AppButton
import com.tilt.wallpapers.ui.components.BtnStyle
import com.tilt.wallpapers.ui.components.ConfirmDeleteDialog
import com.tilt.wallpapers.ui.components.GlassIconButton
import com.tilt.wallpapers.ui.components.GlassSurface
import com.tilt.wallpapers.ui.components.SegmentedPicker
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class MaskTool(private val vi: String) { BRUSH("Cọ"), ERASER("Tẩy"), SELECT("Chọn vùng"); val label: String get() = t(vi) }

private fun MaskTool.icon() = when (this) {
    MaskTool.BRUSH -> Icons.Filled.Brush
    MaskTool.ERASER -> Icons.Filled.CleaningServices
    MaskTool.SELECT -> Icons.Filled.Gesture
}

/** Longest edge of the editable mask; the wallpaper samples masks in image space so any size works. */
private const val MASK_MAX = 1024

/**
 * Edits the subject mask (white = subject). Undo stores only the changed rectangle of each
 * operation, before and after, within a memory budget, so long sessions stay light.
 */
@Stable
private class MaskDoc(
    val image: Bitmap,
    val mask: Bitmap,
    private val original: Bitmap?,
    private val density: Float,
    private val context: Context,
    /** Editing an external subject's cut-out (offers a whole-picture AI cut). */
    val subjectMode: Boolean,
) {
    val w = mask.width
    val h = mask.height
    private val scratch = mask.copy(Bitmap.Config.ARGB_8888, true)  // the mask as of the last commit
    private val canvas = Canvas(mask)
    private val scratchCanvas = Canvas(scratch)

    var version by mutableIntStateOf(0); private set
    var tool by mutableStateOf(MaskTool.BRUSH); private set
    var brushDp by mutableFloatStateOf(36f)
    var softness by mutableFloatStateOf(0.35f)
    var overlay by mutableStateOf(true)
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var dirty by mutableStateOf(false); private set
    var undoCount by mutableIntStateOf(0); private set
    var redoCount by mutableIntStateOf(0); private set

    // ---- view: canvas = mask * scale + offset
    var scale by mutableFloatStateOf(1f); private set
    var ox by mutableFloatStateOf(0f); private set
    var oy by mutableFloatStateOf(0f); private set
    private var fitScale = 1f
    private var viewSize = IntSize.Zero
    val zoomed get() = scale > fitScale * 1.02f

    fun layout(size: IntSize) {
        if (size == viewSize || size.width == 0 || size.height == 0) return
        viewSize = size
        fitScale = min(size.width.toFloat() / w, size.height.toFloat() / h)
        fit()
    }

    fun fit() {
        scale = fitScale
        ox = (viewSize.width - w * scale) / 2f
        oy = (viewSize.height - h * scale) / 2f
    }

    fun zoom(centroid: Offset, pan: Offset, factor: Float) {
        if (!centroid.isFinite || !pan.isFinite || !factor.isFinite() || factor <= 0f) return
        val ns = (scale * factor).coerceIn(fitScale, fitScale * 10f)
        var nx = centroid.x - (centroid.x - ox) * (ns / scale) + pan.x
        var ny = centroid.y - (centroid.y - oy) * (ns / scale) + pan.y
        // Keep the picture over the middle of the view.
        nx = nx.coerceIn(min(viewSize.width / 2f - w * ns, (viewSize.width - w * ns) / 2f), max(viewSize.width / 2f, (viewSize.width - w * ns) / 2f))
        ny = ny.coerceIn(min(viewSize.height / 2f - h * ns, (viewSize.height - h * ns) / 2f), max(viewSize.height / 2f, (viewSize.height - h * ns) / 2f))
        if (nx.isFinite() && ny.isFinite()) { scale = ns; ox = nx; oy = ny }
    }

    private fun toMask(p: Offset) = Offset((p.x - ox) / scale, (p.y - oy) / scale)

    // ---- input: brush strokes and lasso paths
    var cursor by mutableStateOf<Offset?>(null)
    val lasso = Path()
    private var lassoPoints = 0
    private val stroke = Path()
    private var last = Offset.Zero
    private val strokeBounds = RectF()
    private val touched = Rect()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var active = false

    fun start(p: Offset) {
        if (busy != null) return
        val m = toMask(p)
        active = true
        when (tool) {
            MaskTool.BRUSH, MaskTool.ERASER -> {
                cursor = p
                paint.reset()
                paint.isAntiAlias = true
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = if (tool == MaskTool.BRUSH) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                val width = brushDp * density / scale
                paint.strokeWidth = width
                paint.maskFilter = if (softness > 0.02f) BlurMaskFilter(max(0.5f, width * 0.5f * softness), BlurMaskFilter.Blur.NORMAL) else null
                stroke.reset()
                stroke.moveTo(m.x, m.y)
                stroke.lineTo(m.x + 0.01f, m.y)
                last = m
                touched.setEmpty()
                drawStroke()
            }
            MaskTool.SELECT -> {
                // The current selection survives until the finger really draws (a pinch must not drop it).
                lasso.reset(); lasso.moveTo(m.x, m.y); lassoPoints = 1; last = m
                version++
            }
        }
    }

    fun move(p: Offset) {
        if (!active) return
        val m = toMask(p)
        when (tool) {
            MaskTool.BRUSH, MaskTool.ERASER -> {
                cursor = p
                if ((m - last).getDistance() * scale < 1.5f) return
                val mid = (last + m) / 2f
                stroke.quadTo(last.x, last.y, mid.x, mid.y)
                last = m
                drawStroke()
            }
            MaskTool.SELECT -> {
                if ((m - last).getDistance() * scale < 3f) return
                lasso.lineTo(m.x, m.y); lassoPoints++; last = m
                if (lassoPoints == 3 && hasSelection) clearSelection()  // a new lasso replaces the selection
                version++
            }
        }
    }

    /** Second finger landed: drop the half-made stroke and let the view zoom instead. */
    fun cancel() {
        if (!active) return
        active = false
        cursor = null
        when (tool) {
            MaskTool.BRUSH, MaskTool.ERASER -> if (!touched.isEmpty) { restore(touched); version++ }
            else -> { lasso.reset(); lassoPoints = 0; version++ }
        }
    }

    /** Finger lifted. */
    fun end() {
        if (!active) return
        active = false
        cursor = null
        when (tool) {
            MaskTool.BRUSH, MaskTool.ERASER -> { stroke.lineTo(last.x, last.y); drawStroke(); commit(Rect(touched)) }
            MaskTool.SELECT -> {
                val region = if (lassoPoints >= 3) { lasso.close(); SelectionMath.region(lasso, w, h) } else null
                if (region == null) { lasso.reset(); lassoPoints = 0; version++; return }
                selLasso.set(lasso)
                lasso.reset(); lassoPoints = 0
                selRegion = region
                ai = null
                hasSelection = true
                version++
                if (snap) runAi() else rebuild()
            }
        }
    }

    private fun drawStroke() {
        stroke.computeBounds(strokeBounds, true)
        val pad = paint.strokeWidth * (0.5f + softness) + 2f
        val r = Rect(
            (strokeBounds.left - pad).toInt(), (strokeBounds.top - pad).toInt(),
            (strokeBounds.right + pad).toInt() + 1, (strokeBounds.bottom + pad).toInt() + 1,
        )
        if (!r.intersect(0, 0, w, h)) return
        touched.union(r)
        // Redraw the whole stroke over the pre-stroke pixels so soft edges never stack up.
        restore(touched)
        canvas.drawPath(stroke, paint)
        version++
    }

    private fun restore(r: Rect) = canvas.drawBitmap(scratch, r, r, null)

    private fun bounds(path: Path, pad: Float): Rect {
        val b = RectF(); path.computeBounds(b, true)
        return Rect((b.left - pad - 2).toInt(), (b.top - pad - 2).toInt(), (b.right + pad + 2).toInt() + 1, (b.bottom + pad + 2).toInt() + 1)
    }

    // ---- selection ("Chọn vùng"): lasso, optional AI snap, edge shift and feather, then add or remove.
    private val scope = MainScope()
    private val selLasso = Path()
    private var selRegion: Rect? = null
    private var ai: ByteArray? = null          // full-mask AI confidence for the current lasso
    private var buildJob: Job? = null
    var hasSelection by mutableStateOf(false); private set
    var snap by mutableStateOf(false); private set
    var edgePx by mutableIntStateOf(0); private set
    var feather by mutableFloatStateOf(0.15f); private set
    var selection by mutableStateOf<SelectionMath.Result?>(null); private set
    var selectionReady by mutableStateOf(false); private set
    /** The lasso that produced the selection, in mask coordinates (drawn faintly as the search area). */
    val selectionOutline: Path get() = selLasso

    fun pickTool(t: MaskTool) {
        if (t == tool) return
        if (hasSelection) clearSelection()
        tool = t
    }

    fun setSnapToObject(on: Boolean) {
        snap = on
        if (!hasSelection) return
        if (on && ai == null) runAi() else rebuild()
    }

    fun setEdge(px: Int) { if (px != edgePx) { edgePx = px; rebuild() } }
    fun setFeatherAmount(f: Float) { if (f != feather) { feather = f; rebuild() } }

    fun clearSelection() {
        buildJob?.cancel()
        hasSelection = false; selection = null; selectionReady = false; ai = null; selRegion = null
        selLasso.reset()
        if (!active) { lasso.reset(); lassoPoints = 0 }
        version++
    }

    /** Paints the selection into the mask: white adds it to the subject, black removes it. Undoable. */
    fun applySelection(add: Boolean) {
        val s = selection ?: return
        if (!selectionReady || busy != null) return
        canvas.drawBitmap(s.fill, s.rect.left.toFloat(), s.rect.top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG).apply {
            color = if (add) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        })
        commit(Rect(s.rect))
        clearSelection()
    }

    /** Rebuilds the preview off the main thread; quick slider moves cancel the previous build. */
    private fun rebuild() {
        val region = selRegion ?: return
        buildJob?.cancel()
        selectionReady = false
        val path = Path(selLasso)
        val aiNow = if (snap) ai else null
        val e = edgePx; val f = feather
        buildJob = scope.launch {
            delay(30)
            val res = withContext(Dispatchers.Default) { SelectionMath.build(path, region, aiNow, w, e, f) }
            if (released) return@launch
            selection = res
            selectionReady = true
            version++
        }
    }

    /** Runs the subject model on the lasso area and keeps its confidence for snapping. */
    private fun runAi() {
        val b = RectF(); selLasso.computeBounds(b, true)
        val pad = max(b.width(), b.height()) * 0.08f
        val r = Rect((b.left - pad).toInt(), (b.top - pad).toInt(), (b.right + pad).toInt(), (b.bottom + pad).toInt())
        if (!r.intersect(0, 0, w, h) || r.width() < 8 || r.height() < 8) { message = t("Vùng khoanh quá nhỏ"); snap = false; rebuild(); return }
        busy = t("AI đang tìm vật thể…")
        scope.launch {
            var crop: Bitmap? = null
            var seg: Bitmap? = null
            try {
                crop = withContext(Dispatchers.Default) { cropForModel(r) }
                if (!SubjectMasks.ensureModule(context)) {
                    message = t("Không tải được mô hình AI (cần Google Play Services và mạng)")
                    snap = false
                    return@launch
                }
                seg = SubjectMasks.segment(crop)
                // segment() swallows cancellation; never touch state after the screen has closed.
                currentCoroutineContext().ensureActive()
                if (released) return@launch
                if (seg == null) { message = t("Không tìm thấy vật thể trong vùng này"); snap = false; return@launch }
                val conf = seg
                ai = withContext(Dispatchers.Default) { confidenceMap(conf, r) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("MaskEditor", "snap to object failed", e)
                if (!released) { message = t("AI gặp lỗi, hãy thử khoanh lại"); snap = false }
            } finally {
                crop?.recycle(); seg?.recycle()
                if (!released) { busy = null; rebuild() }
            }
        }
    }

    /** Replaces the whole mask with the model's cut of the whole picture (undoable). */
    fun aiCutWhole() {
        if (busy != null) return
        if (hasSelection) clearSelection()
        busy = t("AI đang tách nền…")
        scope.launch {
            var crop: Bitmap? = null
            var seg: Bitmap? = null
            try {
                val r = Rect(0, 0, w, h)
                crop = withContext(Dispatchers.Default) { cropForModel(r) }
                if (!SubjectMasks.ensureModule(context)) { message = t("Không tải được mô hình AI (cần Google Play Services và mạng)"); return@launch }
                seg = SubjectMasks.segment(crop)
                currentCoroutineContext().ensureActive()
                if (released) return@launch
                if (seg == null) { message = t("Không tìm thấy chủ thể trong ảnh này"); return@launch }
                val conf = withContext(Dispatchers.Default) { confidenceMap(seg, r) }
                val px = IntArray(w * h) { i ->
                    val a = ((((conf[i].toInt() and 0xFF) - 51) * 255) / 153).coerceIn(0, 255)
                    (0xFF shl 24) or (a shl 16) or (a shl 8) or a
                }
                mask.setPixels(px, 0, w, 0, 0, w, h)
                commit(Rect(r))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("MaskEditor", "ai cut failed", e)
                if (!released) message = t("AI gặp lỗi, hãy thử lại")
            } finally {
                crop?.recycle(); seg?.recycle()
                if (!released) busy = null
            }
        }
    }

    /** Spreads [seg] (the model output for mask rect [r]) into a full-mask byte map. */
    private fun confidenceMap(seg: Bitmap, r: Rect): ByteArray {
        val scaled = if (seg.width == r.width() && seg.height == r.height()) seg else Bitmap.createScaledBitmap(seg, r.width(), r.height(), true)
        val px = IntArray(r.width() * r.height())
        scaled.getPixels(px, 0, r.width(), 0, 0, r.width(), r.height())
        if (scaled !== seg) scaled.recycle()
        val out = ByteArray(w * h)
        for (y in 0 until r.height()) {
            val o = (y + r.top) * w + r.left
            for (x in 0 until r.width()) out[o + x] = ((px[y * r.width() + x] shr 16) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Copies the picture under mask rect [r] into a new bitmap of at most 1024px for the model.
     * Always a fresh bitmap: Bitmap.createBitmap can hand back [image] itself, which must never be recycled.
     */
    private fun cropForModel(r: Rect): Bitmap {
        val kx = image.width.toFloat() / w
        val ky = image.height.toFloat() / h
        val ir = Rect(
            (r.left * kx).toInt().coerceIn(0, image.width - 1), (r.top * ky).toInt().coerceIn(0, image.height - 1),
            (r.right * kx).roundToInt().coerceIn(1, image.width), (r.bottom * ky).roundToInt().coerceIn(1, image.height),
        )
        if (ir.right <= ir.left) ir.right = ir.left + 1
        if (ir.bottom <= ir.top) ir.bottom = ir.top + 1
        val s = min(1f, 1024f / max(ir.width(), ir.height()))
        val out = Bitmap.createBitmap(max(1, (ir.width() * s).roundToInt()), max(1, (ir.height() * s).roundToInt()), Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(image, ir, Rect(0, 0, out.width, out.height), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    // ---- history
    private class Patch(val x: Int, val y: Int, val before: Bitmap, val after: Bitmap) {
        val bytes get() = before.byteCount + after.byteCount
        fun recycle() { before.recycle(); after.recycle() }
    }
    private val undo = ArrayDeque<Patch>()
    private val redo = ArrayDeque<Patch>()
    private val budget = Runtime.getRuntime().maxMemory() / 5
    private var used = 0L

    private fun commit(r: Rect) {
        if (!r.intersect(0, 0, w, h) || r.isEmpty) return
        val patch = Patch(
            r.left, r.top,
            Bitmap.createBitmap(scratch, r.left, r.top, r.width(), r.height()),
            Bitmap.createBitmap(mask, r.left, r.top, r.width(), r.height()),
        )
        scratchCanvas.drawBitmap(patch.after, r.left.toFloat(), r.top.toFloat(), null)
        redo.forEach { used -= it.bytes; it.recycle() }
        redo.clear()
        undo.addLast(patch); used += patch.bytes
        while (used > budget && undo.size > 1) undo.removeFirst().let { used -= it.bytes; it.recycle() }
        changed()
    }

    private fun apply(p: Patch, bmp: Bitmap) {
        canvas.drawBitmap(bmp, p.x.toFloat(), p.y.toFloat(), null)
        scratchCanvas.drawBitmap(bmp, p.x.toFloat(), p.y.toFloat(), null)
    }

    fun undo() { val p = undo.removeLastOrNull() ?: return; apply(p, p.before); redo.addLast(p); changed() }
    fun redo() { val p = redo.removeLastOrNull() ?: return; apply(p, p.after); undo.addLast(p); changed() }

    /** Back to the AI mask (or an empty one); undoable. */
    fun reset() {
        if (original != null) canvas.drawBitmap(original, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        else canvas.drawColor(android.graphics.Color.BLACK)
        commit(Rect(0, 0, w, h))
    }

    private fun changed() {
        dirty = true
        undoCount = undo.size; redoCount = redo.size
        version++
    }

    private var released = false

    fun release() {
        released = true
        scope.cancel()
        undo.forEach { it.recycle() }; redo.forEach { it.recycle() }
        undo.clear(); redo.clear()
        // A running smart lasso may still be reading these on another thread; leave them to the GC then.
        if (busy == null) { scratch.recycle(); mask.recycle(); image.recycle(); original?.recycle() }
    }

    companion object {
        /**
         * Loads the picture for display, the mask to edit and the mask "Khôi phục gốc" returns to: for the
         * picture's own subject manual > AI > empty with the AI mask as original; for an external subject
         * its source picture, current cut-out mask and first cut-out.
         */
        suspend fun load(context: Context, imageId: String, maxDisplay: Int, density: Float, subject: Boolean): MaskDoc? {
            if (subject) Repo.ensureSubjectSources(imageId)
            val picture = if (subject) Repo.subjectSrcFile(imageId) else Repo.imageFile(imageId)
            val image = decode(picture.path, maxDisplay) ?: return null
            val originalFile = if (subject) Repo.subjectMaskOrigFile(imageId) else Repo.maskFile(imageId)
            val editFile = if (subject) Repo.subjectMaskFile(imageId) else Repo.manualMaskFile(imageId)
            val ai = originalFile.takeIf { it.exists() }?.let { decode(it.path, MASK_MAX) }
            val manual = editFile.takeIf { it.exists() }?.let { decode(it.path, MASK_MAX) }
            val s = MASK_MAX.toFloat() / max(image.width, image.height)
            val mw = (manual ?: ai)?.width ?: (image.width * min(1f, s)).roundToInt()
            val mh = (manual ?: ai)?.height ?: (image.height * min(1f, s)).roundToInt()
            val mask = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
            Canvas(mask).apply {
                drawColor(android.graphics.Color.BLACK)
                (manual ?: ai)?.let { drawBitmap(it, null, Rect(0, 0, mw, mh), Paint(Paint.FILTER_BITMAP_FLAG)) }
            }
            manual?.recycle()
            return MaskDoc(image, mask, ai, density, context.applicationContext, subject)
        }

        private fun decode(path: String, maxDim: Int): Bitmap? = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var sample = 1
            while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample; inMutable = false })
        }.getOrNull()
    }
}

@Composable
fun MaskEditorScreen(imageId: String, subject: Boolean = false) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val maxDisplay = com.tilt.wallpapers.ui.components.screenMaxPx()
    val aspect = com.tilt.wallpapers.ui.components.screenAspect()
    var doc by remember { mutableStateOf<MaskDoc?>(null) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(imageId) {
        val d = withContext(Dispatchers.IO) { MaskDoc.load(context, imageId, maxDisplay, density, subject) }
        if (d == null) failed = true else doc = d
    }
    DisposableEffect(Unit) { onDispose { doc?.release() } }

    val back = { if (doc?.dirty == true) confirmDiscard = true else nav.pop() }
    BackHandler(enabled = !saving) { back() }

    fun save() {
        val d = doc ?: return
        if (!d.dirty) { nav.pop(); return }
        saving = true
        scope.launch {
            val ok = if (subject) {
                Repo.saveSubjectMask(imageId, d.mask, aspect)?.also { change ->
                    // The editor behind us holds an unsaved copy of the placement: keep it in step too.
                    EditorDraft.adjustSubject(imageId) { it.compensated(change.oldBox, change.newBox, aspect) }
                } != null
            } else Repo.saveManualMask(imageId, d.mask)
            if (ok) nav.pop()
            else { d.message = if (subject) t("Chủ thể trống, hãy giữ lại ít nhất một vùng") else t("Không lưu được mặt nạ"); saving = false }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Filled.Close, t("Hủy"), onClick = back)
            Text(if (subject) t("Chỉnh chủ thể") else t("Chỉnh mặt nạ"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
            val d = doc
            GlassIconButton(Icons.AutoMirrored.Filled.Undo, t("Hoàn tác"), enabled = (d?.undoCount ?: 0) > 0 && d?.busy == null, size = 44.dp) { d?.undo() }
            Spacer(Modifier.width(8.dp))
            GlassIconButton(Icons.AutoMirrored.Filled.Redo, t("Làm lại"), enabled = (d?.redoCount ?: 0) > 0 && d?.busy == null, size = 44.dp) { d?.redo() }
            Spacer(Modifier.width(8.dp))
            AppButton(t("Lưu"), enabled = d != null && !saving && d.busy == null, onClick = ::save)
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(28.dp)).background(Color(0xFF101010)),
            contentAlignment = Alignment.Center,
        ) {
            val d = doc
            when {
                failed -> Text(t("Không mở được ảnh"), color = Color.White)
                d == null -> LoadingIndicator()
                else -> MaskCanvas(d)
            }
            if (d != null) CanvasOverlays(d, Modifier.fillMaxSize())
        }

        doc?.let { MaskToolbar(it) }
    }

    if (confirmDiscard) ConfirmDeleteDialog(
        title = t("Bỏ thay đổi?"),
        message = t("Các nét vẽ chưa lưu trên mặt nạ sẽ bị mất."),
        confirmLabel = t("Bỏ"),
        onConfirm = { nav.pop() },
        onDismiss = { confirmDiscard = false },
    )
}

private val overlayFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
    0f, 0f, 0f, 0f, 235f,
    0f, 0f, 0f, 0f, 45f,
    0f, 0f, 0f, 0f, 70f,
    -0.6f, 0f, 0f, 0f, 153f,   // red veil where the mask is black (not subject)
)))

@Composable
private fun MaskCanvas(doc: MaskDoc) {
    val hf = LocalHapticFeedback.current
    val imagePaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val maskPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = overlayFilter } }
    val density = LocalDensity.current.density
    val antsWhite = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = android.graphics.Color.WHITE; strokeWidth = 2f * density } }
    val antsBlack = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = android.graphics.Color.BLACK; strokeWidth = 2f * density
            pathEffect = DashPathEffect(floatArrayOf(6f * density, 6f * density), 0f)
        }
    }
    val screenPath = remember { Path() }
    val matrix = remember { android.graphics.Matrix() }
    val dst = remember { RectF() }
    val src = remember { Rect() }
    val selFill = remember { Paint(Paint.FILTER_BITMAP_FLAG).apply { color = android.graphics.Color.argb(120, 66, 133, 244) } }
    val selEdge = remember { Paint(Paint.FILTER_BITMAP_FLAG).apply { color = android.graphics.Color.WHITE } }
    val lassoFaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = android.graphics.Color.argb(150, 255, 255, 255); strokeWidth = 1.5f * density; pathEffect = DashPathEffect(floatArrayOf(5f * density, 5f * density), 0f) } }
    // The selection border breathes so it reads as "selected, not applied yet".
    val pulse = if (doc.hasSelection) {
        rememberInfiniteTransition(label = "sel").animateFloat(0.55f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse").value
    } else 1f

    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { doc.layout(it) }
            .pointerInput(doc) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (doc.busy != null) return@awaitEachGesture
                    var zooming = false
                    doc.start(down.position)
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } >= 2 || zooming) {
                            if (!zooming) { doc.cancel(); zooming = true; hf.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
                            // The final lift has no pressed pointer, so its centroid is unspecified (NaN): skip it.
                            val centroid = ev.calculateCentroid(useCurrent = true)
                            if (centroid.isSpecified) doc.zoom(centroid, ev.calculatePan(), ev.calculateZoom())
                        } else {
                            ev.changes.firstOrNull { it.id == down.id && it.pressed }?.let { doc.move(it.position) }
                        }
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                    if (!zooming) doc.end()
                }
            }
    ) {
        doc.version // redraw whenever the document changes
        drawIntoCanvas { c ->
            val n = c.nativeCanvas
            n.save()
            n.clipRect(0f, 0f, size.width, size.height)
            n.drawVisible(doc.image, doc, size.width, size.height, imagePaint, src, dst)
            if (doc.overlay) n.drawVisible(doc.mask, doc, size.width, size.height, maskPaint, src, dst)
            doc.selection?.let { sel ->
                n.drawVisible(sel.fill, doc, size.width, size.height, selFill, src, dst, sel.rect)
                selEdge.alpha = (255 * pulse).roundToInt()
                n.drawVisible(sel.edge, doc, size.width, size.height, selEdge, src, dst, sel.rect)
            }
            n.restore()
            matrix.setScale(doc.scale, doc.scale)
            matrix.postTranslate(doc.ox, doc.oy)
            if (doc.hasSelection) {
                // Once a selection exists its lasso is only the search area.
                doc.selectionOutline.transform(matrix, screenPath)
                n.drawPath(screenPath, lassoFaint)
            }
            if (!doc.lasso.isEmpty) {
                doc.lasso.transform(matrix, screenPath)
                n.drawPath(screenPath, antsWhite)
                n.drawPath(screenPath, antsBlack)
            }
        }
        doc.cursor?.let { p ->
            val r = doc.brushDp * density / 2f
            drawCircle(Color.White, r, p, style = Stroke(1.5f * density))
            drawCircle(Color.Black.copy(alpha = 0.5f), r + 1.5f * density, p, style = Stroke(1f * density))
        }
    }
}

/**
 * Draws only the part of [bmp] that falls inside the view. [bmp] covers [region] of the document (the
 * whole document by default). When zoomed the full picture would be many times the screen size, and some
 * phone GPUs drop such draws (black view). The source rect is snapped outwards to whole pixels and the
 * destination matched to it, so nothing shifts.
 */
private fun android.graphics.Canvas.drawVisible(
    bmp: Bitmap, doc: MaskDoc, vw: Float, vh: Float, paint: Paint, src: Rect, dst: RectF, region: Rect? = null,
) {
    val rl = region?.left ?: 0; val rt = region?.top ?: 0
    val kx = bmp.width.toFloat() / (region?.width() ?: doc.w)
    val ky = bmp.height.toFloat() / (region?.height() ?: doc.h)
    val l = floor((-doc.ox / doc.scale - rl) * kx).coerceAtLeast(0f)
    val t = floor((-doc.oy / doc.scale - rt) * ky).coerceAtLeast(0f)
    val r = ceil(((vw - doc.ox) / doc.scale - rl) * kx).coerceAtMost(bmp.width.toFloat())
    val b = ceil(((vh - doc.oy) / doc.scale - rt) * ky).coerceAtMost(bmp.height.toFloat())
    if (r <= l || b <= t) return
    src.set(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    dst.set(
        doc.ox + (rl + l / kx) * doc.scale, doc.oy + (rt + t / ky) * doc.scale,
        doc.ox + (rl + r / kx) * doc.scale, doc.oy + (rt + b / ky) * doc.scale,
    )
    drawBitmap(bmp, src, dst, paint)
}

@Composable
private fun CanvasOverlays(doc: MaskDoc, modifier: Modifier) {
    Box(modifier.padding(12.dp)) {
        // Busy / message pill
        val text = doc.busy ?: doc.message
        LaunchedEffect(doc.message) { if (doc.message != null) { delay(2600); doc.message = null } }
        AnimatedVisibility(text != null, Modifier.align(Alignment.TopCenter), enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
            GlassSurface(Modifier.height(40.dp), shape = CircleShape) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (doc.busy != null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Info, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(text ?: "", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
        AnimatedVisibility(doc.zoomed, Modifier.align(Alignment.BottomEnd), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            GlassIconButton(Icons.Filled.FitScreen, t("Vừa khung"), size = 44.dp) { doc.fit() }
        }
    }
}

@Composable
private fun MaskToolbar(doc: MaskDoc) {
    var sizePreview by remember { mutableStateOf(false) }
    val previewAlpha by animateFloatAsState(if (sizePreview) 1f else 0f, label = "sizePreview")
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        Box(Modifier.padding(horizontal = 12.dp)) {
            SegmentedPicker(MaskTool.entries, doc.tool, { it.label }, icon = { it.icon() }) { doc.pickTool(it) }
        }
        val selecting = doc.tool == MaskTool.SELECT && doc.hasSelection
        AnimatedContent(
            doc.tool to selecting, label = "toolPanel",
            transitionSpec = { (fadeIn() togetherWith fadeOut()).using(SizeTransform(clip = false)) },
        ) { (tool, sel) ->
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                when {
                    tool != MaskTool.SELECT -> {
                        SliderRow(t("Kích thước cọ"), doc.brushDp, 8f..120f, { "${it.roundToInt()} dp" }) { doc.brushDp = it; sizePreview = true }
                        SliderRow(t("Độ mềm"), doc.softness, 0f..1f, { "${(it * 100).roundToInt()}%" }) { doc.softness = it; sizePreview = true }
                    }
                    !sel -> Hint(t("Khoanh một vòng quanh vùng muốn chọn. Sau đó có thể cho AI bám theo vật thể, nới hoặc thu viền, rồi chọn Thêm hay Bỏ."))
                    else -> SelectionPanel(doc)
                }
            }
        }
        if (!selecting && doc.subjectMode) Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            AppButton(t("AI tách nền cả ảnh"), icon = Icons.Filled.AutoAwesome, style = BtnStyle.Tonal, enabled = doc.busy == null, modifier = Modifier.fillMaxWidth()) {
                doc.aiCutWhole()
            }
        }
        if (!selecting) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton(
                if (doc.overlay) t("Quick Mask: bật") else t("Quick Mask: tắt"),
                icon = if (doc.overlay) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                style = BtnStyle.Tonal,
                modifier = Modifier.weight(1f),
            ) { doc.overlay = !doc.overlay }
            AppButton(t("Khôi phục gốc"), icon = Icons.Filled.RestartAlt, style = BtnStyle.Outlined, enabled = doc.busy == null) { doc.reset() }
        }
        Text(
            t("Một ngón để vẽ · hai ngón để phóng to và di chuyển"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
        )
    }
    // Live brush size preview while a slider is dragged.
    LaunchedEffect(doc.brushDp, doc.softness) { if (sizePreview) { delay(700); sizePreview = false } }
    if (previewAlpha > 0f) BrushPreview(doc, previewAlpha)
}

/** Step 2 and 3 of "Chọn vùng": refine the selection, then add it to or remove it from the subject. */
@Composable
private fun SelectionPanel(doc: MaskDoc) {
    SwitchRow(t("Bám theo vật thể (AI)"), t("AI ôm sát mép vật thể trong vòng khoanh"), doc.snap, Icons.Filled.AutoAwesome, enabled = doc.busy == null) {
        doc.setSnapToObject(it)
    }
    SliderRow(
        t("Nới / thu viền"), doc.edgePx.toFloat(), -SelectionMath.MAX_EDGE.toFloat()..SelectionMath.MAX_EDGE.toFloat(),
        { val i = it.roundToInt(); when { i > 0 -> t("Nới %1\$s px", i); i < 0 -> "Thu ${-i} px"; else -> "0 px" } },
    ) { doc.setEdge(it.roundToInt()) }
    SliderRow(t("Độ mềm viền"), doc.feather, 0f..1f, { "${(it * 100).roundToInt()}%" }) { doc.setFeatherAmount(it) }
    val ready = doc.selectionReady && doc.busy == null
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(Icons.Filled.Close, t("Hủy vùng chọn"), size = 44.dp) { doc.clearSelection() }
        AppButton(t("Bỏ vùng"), icon = Icons.Filled.RemoveCircle, style = BtnStyle.Outlined, enabled = ready, modifier = Modifier.weight(1f)) { doc.applySelection(add = false) }
        AppButton(t("Thêm vùng"), icon = Icons.Filled.AddCircle, enabled = ready, modifier = Modifier.weight(1f)) { doc.applySelection(add = true) }
    }
}

@Composable
private fun BrushPreview(doc: MaskDoc, alpha: Float) {
    androidx.compose.ui.window.Popup(alignment = Alignment.Center) {
        Canvas(Modifier.size(140.dp)) {
            val r = doc.brushDp.dp.toPx() / 2f
            drawCircle(Color.Black.copy(alpha = 0.35f * alpha), r + 10.dp.toPx())
            val soft = doc.softness
            drawCircle(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    0f to Color.White.copy(alpha = alpha),
                    (1f - soft * 0.8f).coerceIn(0.01f, 1f) to Color.White.copy(alpha = alpha),
                    1f to Color.White.copy(alpha = 0f),
                    center = center, radius = r,
                ),
                r,
            )
            drawCircle(Color.White.copy(alpha = alpha), r, style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun Hint(text: String) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
