package com.tilt.wallpapers.data

import android.graphics.BitmapFactory
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max

/**
 * Composition helpers built on the picture's own subject mask: where the subject is, a crop that puts it
 * in the middle of the screen, and where to place an uploaded subject.
 */
object SubjectLayout {

    /** Bounding box of the picture's own subject (manual mask first, then AI), in 0..1 image units. */
    fun maskBox(img: WallImage): RectF? {
        val file = when {
            img.hasManualMask && Repo.manualMaskFile(img.id).exists() -> Repo.manualMaskFile(img.id)
            Repo.maskFile(img.id).exists() -> Repo.maskFile(img.id)
            else -> return null
        }
        val bmp = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            var sample = 1
            while (max(o.outWidth, o.outHeight) / (sample * 2) >= 256) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h); bmp.recycle()
        var l = w; var t = h; var r = -1; var b = -1; var count = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (((px[y * w + x] shr 16) and 0xFF) > 128) {
                count++
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        // Ignore specks: a subject covers at least 0.5% of the picture.
        if (count < w * h / 200 || r < l || b < t) return null
        return RectF(l.toFloat() / w, t.toFloat() / h, (r + 1f) / w, (b + 1f) / h)
    }

    /**
     * A crop (normalized, screen aspect [viewAspect]) that centres [box] horizontally and sits it a little
     * above the middle, leaving room for the clock and icons. It zooms in only as far as the picture stays
     * sharp ([minCropPx] image pixels across); a subject too big for the frame keeps its top (head) in view.
     */
    fun crop(edit: ImageEdit, box: RectF, imgW: Int, imgH: Int, viewAspect: Float, minCropPx: Float): ImageEdit {
        val imageAspect = imgW.toFloat() / imgH
        // Largest crop with the screen's shape (no zoom).
        val (maxW, maxH) = if (imageAspect > viewAspect) viewAspect / imageAspect to 1f else 1f to imageAspect / viewAspect
        val mx = box.width() * 0.08f; val my = box.height() * 0.08f
        val bl = box.left - mx; val bt = box.top - my; val br = box.right + mx; val bb = box.bottom + my
        val minScale = (minCropPx / (maxW * imgW)).coerceIn(0.2f, 1f)

        fun place(s: Float): Pair<FloatArray, Float> {
            val w = maxW * s; val h = maxH * s
            val cx = (bl + br) / 2
            // Subject a bit above centre; a subject taller than the frame aligns its top instead.
            val idealTop = if (bb - bt > h) bt else (bt + bb) / 2 - h * 0.44f
            val x = (cx - w / 2).coerceIn(0f, 1f - w)
            val y = idealTop.coerceIn(0f, 1f - h)
            val err = abs((x + w / 2) - cx) / w + abs(y - idealTop) / h * 0.5f
            return floatArrayOf(x, y, w, h) to err
        }

        var best = place(1f)
        var s = 1f
        while (best.second > 0.02f && s > minScale) {
            s = max(minScale, s - 0.05f)
            val w = maxW * s; val h = maxH * s
            if (w < br - bl || h < (bb - bt) * 0.6f) break       // never crop into the subject to centre it
            val c = place(s)
            if (c.second < best.second - 0.005f) best = c
        }
        val (x, y, w, h) = best.first.toList()
        return edit.copy(cropX = x, cropY = y, cropW = w, cropH = h)
    }

    enum class Placement(private val vi: String) { OVER_ORIGINAL("Chỗ chủ thể gốc"), STAND("Sát đáy"), PORTRAIT("Chân dung"); val label: String get() = t(vi) }

    /** Where to put an uploaded subject: over the picture's own subject, standing on the bottom edge, or as a portrait. */
    fun place(
        t: SubjectTransform, mode: Placement, subjectAspect: Float, viewAspect: Float,
        img: WallImage, edit: ImageEdit, originalBox: RectF?,
    ): SubjectTransform {
        val (bw, bh) = SubjectTransform.baseSize(subjectAspect, viewAspect)
        val base = t.copy(rotation = 0f, flipHorizontal = false, flipVertical = false)
        return when (mode) {
            Placement.OVER_ORIGINAL -> {
                val box = originalBox?.let { screenBox(it, img, edit, viewAspect) } ?: return place(t, Placement.STAND, subjectAspect, viewAspect, img, edit, null)
                // Cover the old subject completely, bottoms aligned (feet stay where they were).
                val scale = max(box.width() / bw, box.height() / bh).coerceIn(0.15f, 1.0f / bh)
                val h = bh * scale
                base.copy(scale = scale, offsetX = (box.centerX() - 0.5f).coerceIn(-0.8f, 0.8f), offsetY = (box.bottom - h / 2 - 0.5f).coerceIn(-0.8f, 0.8f))
            }
            Placement.STAND -> {
                val scale = (0.7f / bh).coerceIn(0.15f, 5f)
                base.copy(scale = scale, offsetX = 0f, offsetY = 0.5f - bh * scale / 2)
            }
            Placement.PORTRAIT -> {
                // Head and shoulders: the subject runs off the bottom, its top just under the clock.
                val scale = (1.3f / bh).coerceIn(0.15f, 5f)
                base.copy(scale = scale, offsetX = 0f, offsetY = (0.18f + bh * scale / 2 - 0.5f).coerceIn(-0.8f, 0.8f))
            }
        }
    }

    /** [box] (image units) as fractions of the screen, through the picture's current crop. */
    private fun screenBox(box: RectF, img: WallImage, edit: ImageEdit, viewAspect: Float): RectF {
        val c = Grading.fitCrop(edit, img.width.toFloat() / img.height, viewAspect)
        return RectF(
            (box.left - c[0]) / c[2], (box.top - c[1]) / c[3],
            (box.right - c[0]) / c[2], (box.bottom - c[1]) / c[3],
        ).apply { intersect(0f, 0f, 1f, 1f) }
    }
}
