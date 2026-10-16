package com.tilt.wallpapers.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pixel work for the "Chọn vùng" tool. A selection starts as the lasso's coverage, can be narrowed to
 * the AI's subject confidence, then its edge is moved in or out and softened. Everything runs on the
 * part of the mask the lasso can reach, so it stays quick at 1024px.
 */
internal object SelectionMath {
    const val MAX_EDGE = 20
    const val MAX_FEATHER = 16

    /** [fill] and [edge] are ALPHA_8 bitmaps covering [rect] (mask coordinates). */
    class Result(val rect: Rect, val fill: Bitmap, val edge: Bitmap)

    /** Area a selection from [lasso] may touch: its bounds plus the widest edge shift and feather. */
    fun region(lasso: Path, w: Int, h: Int): Rect? {
        val b = RectF(); lasso.computeBounds(b, true)
        val pad = MAX_EDGE + MAX_FEATHER + 2
        val r = Rect(b.left.toInt() - pad, b.top.toInt() - pad, b.right.toInt() + pad + 1, b.bottom.toInt() + pad + 1)
        return if (r.intersect(0, 0, w, h) && r.width() > 2 && r.height() > 2) r else null
    }

    /**
     * [ai] is a full-mask confidence map (w * h bytes) or null. [edgePx] > 0 grows the selection,
     * < 0 shrinks it. [feather] 0..1 softens the final edge.
     */
    fun build(lasso: Path, region: Rect, ai: ByteArray?, w: Int, edgePx: Int, feather: Float): Result {
        val rw = region.width(); val rh = region.height(); val n = rw * rh

        // 1. Lasso coverage, anti-aliased.
        val cover = Bitmap.createBitmap(rw, rh, Bitmap.Config.ALPHA_8)
        Canvas(cover).apply {
            translate(-region.left.toFloat(), -region.top.toFloat())
            drawPath(lasso, Paint(Paint.ANTI_ALIAS_FLAG))
        }
        val stride = cover.rowBytes
        val raw = ByteArray(stride * rh)
        cover.copyPixelsToBuffer(ByteBuffer.wrap(raw))
        cover.recycle()
        val a = FloatArray(n)
        for (y in 0 until rh) for (x in 0 until rw) a[y * rw + x] = (raw[y * stride + x].toInt() and 0xFF) / 255f

        // 2. Keep only what the AI sees as subject (same soft threshold the wallpaper uses).
        if (ai != null) {
            for (y in 0 until rh) {
                val row = (y + region.top) * w + region.left
                for (x in 0 until rw) {
                    val c = (((ai[row + x].toInt() and 0xFF) - 51) / 153f).coerceIn(0f, 1f)
                    val i = y * rw + x
                    a[i] = min(a[i], c)
                }
            }
        }

        // 3. Move the edge: box blur, then threshold low (grow) or high (shrink). The blur reaches |edgePx|
        //    pixels, so the 50% contour lands that far out or in; the band keeps one pixel of anti-aliasing.
        val tmp = FloatArray(n)
        if (edgePx != 0) {
            val r = abs(edgePx)
            boxBlur(a, rw, rh, r, tmp)
            val step = 0.5f / (2 * r + 1)
            val t = if (edgePx > 0) step else 1f - step
            for (i in 0 until n) a[i] = smooth(t - step, t + step, a[i])
        }

        // 4. Outline of the hard selection, two pixels wide, for the on-screen border.
        val edge = ByteArray(n)
        for (y in 0 until rh) for (x in 0 until rw) {
            val i = y * rw + x
            if (a[i] < 0.5f) continue
            var border = false
            for (d in 1..2) {
                if (x - d < 0 || a[i - d] < 0.5f || x + d >= rw || a[i + d] < 0.5f ||
                    y - d < 0 || a[i - d * rw] < 0.5f || y + d >= rh || a[i + d * rw] < 0.5f
                ) { border = true; break }
            }
            if (border) edge[i] = 0xFF.toByte()
        }

        // 5. Feather.
        val f = (feather * MAX_FEATHER).roundToInt()
        if (f > 0) { val r = max(1, f / 2); boxBlur(a, rw, rh, r, tmp); boxBlur(a, rw, rh, r, tmp) }

        val fill = ByteArray(n) { (a[it] * 255f).roundToInt().coerceIn(0, 255).toByte() }
        return Result(Rect(region), alpha8(fill, rw, rh), alpha8(edge, rw, rh))
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun alpha8(bytes: ByteArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val stride = bmp.rowBytes
        val buf = if (stride == w) bytes else ByteArray(stride * h).also { out ->
            for (y in 0 until h) System.arraycopy(bytes, y * w, out, y * stride, w)
        }
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(buf))
        return bmp
    }

    /** Separable box blur of radius [r] in place; outside the array counts as 0. */
    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int, tmp: FloatArray) {
        val norm = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val o = y * w
            var sum = 0f
            for (x in 0..min(r, w - 1)) sum += a[o + x]
            for (x in 0 until w) {
                tmp[o + x] = sum * norm
                val add = x + r + 1; val sub = x - r
                if (add < w) sum += a[o + add]
                if (sub >= 0) sum -= a[o + sub]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (y in 0..min(r, h - 1)) sum += tmp[y * w + x]
            for (y in 0 until h) {
                a[y * w + x] = sum * norm
                val add = y + r + 1; val sub = y - r
                if (add < h) sum += tmp[add * w + x]
                if (sub >= 0) sum -= tmp[sub * w + x]
            }
        }
    }
}
