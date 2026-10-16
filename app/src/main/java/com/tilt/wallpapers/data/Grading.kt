package com.tilt.wallpapers.data

/**
 * Colour grading shared by the Compose previews (ColorMatrix) and the GL renderer (mat4),
 * so what the user sees in the editor is exactly what the wallpaper draws.
 * Matrices are 4x5 row-major with offsets in 0..255, like android.graphics.ColorMatrix.
 */
object Grading {

    fun matrix(edit: ImageEdit): FloatArray {
        var m = filter(edit.filter)
        if (edit.brightness != 0f) m = concat(m, offset(edit.brightness * 80f))
        if (edit.contrast != 1f) m = concat(m, contrast(edit.contrast))
        if (edit.saturation != 1f) m = concat(m, saturation(edit.saturation))
        if (edit.warmth != 0f) m = concat(m, warmth(edit.warmth))
        return m
    }

    /** Column-major mat4 for GLSL: rgb coefficients plus the offset folded into column 3 (input w = 1). */
    fun glMatrix(edit: ImageEdit, out: FloatArray = FloatArray(16)): FloatArray {
        val a = matrix(edit)
        for (row in 0..2) {
            for (col in 0..2) out[col * 4 + row] = a[row * 5 + col]
            out[12 + row] = a[row * 5 + 4] / 255f
        }
        out[3] = 0f; out[7] = 0f; out[11] = 0f; out[15] = 1f
        return out
    }

    /**
     * Shrinks the crop rect (normalized) so it has the viewport's aspect ratio, keeping it centred.
     * Returns [x, y, w, h] normalized to the image.
     */
    fun fitCrop(edit: ImageEdit, imageAspect: Float, viewAspect: Float, out: FloatArray = FloatArray(4)): FloatArray {
        var x = edit.cropX; var y = edit.cropY; var w = edit.cropW; var h = edit.cropH
        val cropAspect = w * imageAspect / h
        if (cropAspect > viewAspect) {
            val nw = w * viewAspect / cropAspect
            x += (w - nw) / 2f; w = nw
        } else {
            val nh = h * cropAspect / viewAspect
            y += (h - nh) / 2f; h = nh
        }
        out[0] = x; out[1] = y; out[2] = w; out[3] = h
        return out
    }

    private fun filter(style: FilterStyle): FloatArray = when (style) {
        FilterStyle.NONE -> identity()
        FilterStyle.VIVID -> concat(saturation(1.35f), contrast(1.08f))
        FilterStyle.WARM -> scale(1.08f, 1.01f, 0.86f, 8f, 2f, -4f)
        FilterStyle.COOL -> scale(0.9f, 1f, 1.1f, -4f, 0f, 10f)
        FilterStyle.FADE -> concat(concat(saturation(0.75f), contrast(0.78f)), offset(14f))
        FilterStyle.SEPIA -> floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        FilterStyle.MONO -> saturation(0f)
        FilterStyle.NOIR -> concat(saturation(0f), contrast(1.4f))
        FilterStyle.DRAMA -> concat(concat(contrast(1.25f), saturation(0.85f)), offset(-10f))
    }

    private fun identity() = scale(1f, 1f, 1f, 0f, 0f, 0f)

    private fun scale(r: Float, g: Float, b: Float, or: Float, og: Float, ob: Float) = floatArrayOf(
        r, 0f, 0f, 0f, or,
        0f, g, 0f, 0f, og,
        0f, 0f, b, 0f, ob,
        0f, 0f, 0f, 1f, 0f,
    )

    private fun offset(v: Float) = scale(1f, 1f, 1f, v, v, v)

    private fun contrast(c: Float): FloatArray {
        val t = (1f - c) * 127.5f
        return scale(c, c, c, t, t, t)
    }

    private fun warmth(w: Float) = scale(1f + w * 0.12f, 1f + w * 0.03f, 1f - w * 0.14f, w * 6f, 0f, -w * 6f)

    private fun saturation(s: Float): FloatArray {
        val lr = 0.2126f * (1 - s); val lg = 0.7152f * (1 - s); val lb = 0.0722f * (1 - s)
        return floatArrayOf(
            lr + s, lg, lb, 0f, 0f,
            lr, lg + s, lb, 0f, 0f,
            lr, lg, lb + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Applies [a] first, then [b]. */
    private fun concat(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(20)
        for (i in 0..3) {
            for (j in 0..4) {
                var v = if (j == 4) b[i * 5 + 4] else 0f
                for (k in 0..3) v += b[i * 5 + k] * a[k * 5 + j]
                r[i * 5 + j] = v
            }
        }
        return r
    }
}
