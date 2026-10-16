package com.tilt.wallpapers.data

import android.graphics.Bitmap
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Colour maths for the app theme, in CIELAB (L* is Material's "tone"). Picks representative colours from
 * a picture and re-tones a colour to any lightness while keeping its hue.
 */
object ColorExtract {

    // ---------------------------------------------------------------- sRGB <-> Lab (D65)
    private fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
    private fun gam(v: Double): Double = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
    private fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
    private fun fInv(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) / (24389.0 / 27)
    private const val XN = 0.95047; private const val ZN = 1.08883

    /** L* (0..100), a*, b*. */
    fun lab(argb: Int): DoubleArray {
        val r = lin((argb shr 16) and 0xFF); val g = lin((argb shr 8) and 0xFF); val b = lin(argb and 0xFF)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / XN
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / ZN
        val fy = f(y)
        return doubleArrayOf(116 * fy - 16, 500 * (f(x) - fy), 200 * (fy - f(z)))
    }

    /** ARGB for Lab, or null when it falls outside sRGB. */
    private fun fromLab(l: Double, a: Double, b: Double): Int? {
        val fy = (l + 16) / 116
        val x = fInv(fy + a / 500) * XN; val y = fInv(fy); val z = fInv(fy - b / 200) * ZN
        val r = gam(3.2406 * x - 1.5372 * y - 0.4986 * z)
        val g = gam(-0.9689 * x + 1.8758 * y + 0.0415 * z)
        val bl = gam(0.0557 * x - 0.2040 * y + 1.0570 * z)
        val e = 0.002
        if (r < -e || r > 1 + e || g < -e || g > 1 + e || bl < -e || bl > 1 + e) return null
        fun c(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(bl)
    }

    fun chroma(argb: Int): Float { val l = lab(argb); return hypot(l[1], l[2]).toFloat() }
    fun tone(argb: Int): Float = lab(argb)[0].toFloat()
    /** Hue angle in degrees (0..360). */
    fun hue(argb: Int): Float { val l = lab(argb); return ((Math.toDegrees(atan2(l[2], l[1])) + 360) % 360).toFloat() }

    /**
     * [argb]'s hue at lightness [tone] (0..100) with [chroma] (default: its own), lowering the chroma
     * until it fits sRGB. Tone, not the source colour's lightness, decides how light the result is.
     */
    fun toned(argb: Int, tone: Float, chroma: Float = chroma(argb)): Int = fromHct(hue(argb), chroma, tone)

    fun fromHct(hue: Float, chroma: Float, tone: Float): Int {
        val h = Math.toRadians(hue.toDouble())
        var lo = 0.0; var hi = chroma.toDouble()
        fromLab(tone.toDouble(), hi * cos(h), hi * sin(h))?.let { return it }
        var best = fromLab(tone.toDouble(), 0.0, 0.0) ?: 0xFF000000.toInt()
        repeat(14) {
            val mid = (lo + hi) / 2
            val c = fromLab(tone.toDouble(), mid * cos(h), mid * sin(h))
            if (c != null) { best = c; lo = mid } else hi = mid
        }
        return best
    }

    // ---------------------------------------------------------------- picking colours
    /**
     * Up to [count] distinct colours from [bmp], best first. Hues are bucketed; each bucket is scored by
     * how much of the colourful area it covers and how vivid it is (like Material's score), and picked
     * colours are kept apart in hue. A colourless picture yields one grey.
     */
    fun swatches(bmp: Bitmap, count: Int): List<Int> {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val buckets = 36
        val weight = DoubleArray(buckets); val chromaSum = DoubleArray(buckets)
        val sumL = DoubleArray(buckets); val sumA = DoubleArray(buckets); val sumB = DoubleArray(buckets)
        var colourful = 0; var grayL = 0.0
        for (p in px) {
            val l = lab(p)
            val c = hypot(l[1], l[2])
            grayL += l[0]
            if (c < 6 || l[0] < 12 || l[0] > 96) continue
            val h = (Math.toDegrees(atan2(l[2], l[1])) + 360) % 360
            val k = (h / (360.0 / buckets)).toInt().coerceIn(0, buckets - 1)
            weight[k] += 1.0; chromaSum[k] += c
            sumL[k] += l[0] * c; sumA[k] += l[1] * c; sumB[k] += l[2] * c
            colourful++
        }
        if (colourful < px.size / 50) {
            val t = (grayL / px.size).toFloat()
            return listOf(fromHct(0f, 0f, t))
        }
        data class Cand(val color: Int, val hue: Float, val score: Double)
        val cands = (0 until buckets).filter { weight[it] > 0 }.map { k ->
            val avgC = chromaSum[k] / weight[k]
            val share = weight[k] / colourful
            val score = share * 70 + (avgC - 48) * (if (avgC < 48) 0.1 else 0.3)
            val w = chromaSum[k]
            val color = fromLabClamped(sumL[k] / w, sumA[k] / w, sumB[k] / w)
            Cand(color, ((k + 0.5) * 360.0 / buckets).toFloat(), score)
        }.filter { it.score > -10 }.sortedByDescending { it.score }
        val picked = ArrayList<Cand>()
        for (minGap in listOf(45f, 25f, 12f)) {
            for (c in cands) {
                if (picked.size >= count) break
                if (picked.none { hueGap(it.hue, c.hue) < minGap }) picked += c
            }
        }
        return picked.map { it.color }.ifEmpty { listOf(fromHct(0f, 0f, (grayL / px.size).toFloat())) }
    }

    private fun fromLabClamped(l: Double, a: Double, b: Double): Int {
        val h = ((Math.toDegrees(atan2(b, a)) + 360) % 360).toFloat()
        return fromHct(h, hypot(a, b).toFloat(), l.toFloat())
    }

    fun hueGap(a: Float, b: Float): Float { val d = kotlin.math.abs(a - b) % 360; return if (d > 180) 360 - d else d }
}
