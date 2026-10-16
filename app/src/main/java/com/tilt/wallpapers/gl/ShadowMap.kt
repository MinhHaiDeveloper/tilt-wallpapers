package com.tilt.wallpapers.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A subject's drop shadow, blurred on the CPU at low resolution. Sampling a properly blurred map with
 * plain bilinear filtering gives a smooth shadow at any softness, unlike reading coarse mip levels,
 * whose few big texels show up as squares that grow and shrink while the softness slider moves.
 *
 * [alpha] covers exactly the subject texture's UV space (including its transparent padding).
 */
internal class ShadowSource(val alpha: FloatArray, val w: Int, val h: Int, private val subjectDim: Float) {

    /** Gaussian sigma (in this map's pixels) for the softness slider value [soft] 0..1. */
    fun sigma(soft: Float) = max(0.6f, sigmaFraction(soft) * subjectDim)

    /** ARGB bitmap whose alpha is the shadow at softness [soft]: four box blurs, close to a Gaussian. */
    fun render(soft: Float): Bitmap {
        val a = alpha.copyOf()
        val tmp = FloatArray(a.size)
        // n box passes of radius r give variance n * r(r+1)/3; pick r for the wanted sigma.
        val s = sigma(soft)
        val r = ((kotlin.math.sqrt(1f + 3f * s * s) - 1f) / 2f).roundToInt().coerceAtLeast(1)
        repeat(4) { boxBlur(a, w, h, r, tmp) }
        val bytes = ByteArray(w * h * 4)
        for (i in a.indices) bytes[i * 4 + 3] = (a[i] * 255f).roundToInt().coerceIn(0, 255).toByte()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
    }

    companion object {
        private const val SIZE = 256

        /** Sigma as a fraction of the subject's longest side. */
        fun sigmaFraction(soft: Float) = 0.003f + 0.035f * soft

        /**
         * Transparent margin a subject of longest side [dim] needs so the softest shadow fades to zero
         * before the texture edge: 3 sigma of blur plus the largest shadow offset, with room to spare.
         */
        fun padFor(dim: Int) = (dim * (3.5f * sigmaFraction(1f) + 0.06f)).roundToInt().coerceAtLeast(16)

        /** From an external subject [s] placed with [pad] transparent pixels on every side. */
        fun ofSubject(s: Bitmap, pad: Int): ShadowSource {
            val pw = s.width + pad * 2; val ph = s.height + pad * 2
            val k = SIZE.toFloat() / max(pw, ph)
            val w = max(1, (pw * k).roundToInt()); val h = max(1, (ph * k).roundToInt())
            val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(small).drawBitmap(s, null, android.graphics.RectF(pad * k, pad * k, (pad + s.width) * k, (pad + s.height) * k), Paint(Paint.FILTER_BITMAP_FLAG))
            val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h); small.recycle()
            return ShadowSource(FloatArray(w * h) { (px[it] ushr 24) / 255f }, w, h, max(s.width, s.height) * k)
        }

        /** From a grayscale subject mask (white = subject) covering the whole picture. */
        fun ofMask(m: Bitmap): ShadowSource {
            val k = min(1f, SIZE.toFloat() / max(m.width, m.height))
            val w = max(1, (m.width * k).roundToInt()); val h = max(1, (m.height * k).roundToInt())
            val small = Bitmap.createScaledBitmap(m, w, h, true)
            val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
            if (small !== m) small.recycle()
            // Same soft threshold the shader uses for mask subjects.
            val a = FloatArray(w * h) { val v = ((px[it] shr 16) and 0xFF) / 255f; val t = ((v - 0.2f) / 0.6f).coerceIn(0f, 1f); t * t * (3 - 2 * t) }
            return ShadowSource(a, w, h, max(w, h).toFloat())
        }

        /** Separable box blur of radius [r] in place; outside counts as 0. */
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
}
