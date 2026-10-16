package com.tilt.wallpapers.gl

import android.opengl.GLES30.*
import com.tilt.wallpapers.data.ClockPlacement
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.TextArt
import com.tilt.wallpapers.data.TextLayer
import com.tilt.wallpapers.data.WallImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * The free texts on the GL side: one texture for both texts (a strip per line), a small float texture with
 * every character's cell (row 0) and its strip, halo channel and kept height per image (row 1), and the
 * per-frame uniforms. Rebuilt off the GL thread only when the texts or their size change.
 */
internal class TextGl(private val host: GlThread, private val io: Executor, private val upload: (ByteBuffer, Int, Int) -> Int) {
    private var tex = 0
    private var data = 0
    private var art: TextArt.Art? = null
    private var key: String? = null
    private var building = false
    private var lastLayer: TextLayer? = null; private var lastSeq: List<WallImage>? = null; private var lastHeight = 0
    /** Cell index range of each text in the data texture. */
    private val first = IntArray(2); private val count = IntArray(2)
    private var cells = 0
    private val row1 = FloatArray(4 * MAX_CELLS)
    private val row1Sent = FloatArray(4 * MAX_CELLS) { -1f }
    private val sqNow = FloatArray(MAX_CELLS) { 1f }
    private val boxA = FloatArray(8); private val boxB = FloatArray(8)
    private val em = FloatArray(8)
    private val stripU = FloatArray(16)
    private val color = FloatArray(8); private val fx = FloatArray(8); private val grad = FloatArray(18)

    /** Keeps the texture current for [layer] on [seq]; true when there is something to draw. */
    fun ensure(layer: TextLayer, seq: List<WallImage>, height: Int): Boolean {
        // Same texts, pictures and size as last frame: nothing to check (the key below builds strings).
        if (layer === lastLayer && seq === lastSeq && height == lastHeight) return tex != 0 && art != null
        lastLayer = layer; lastSeq = seq; lastHeight = height
        val items = layer.items.filter { it.text.isNotBlank() }
        if (items.isEmpty()) return false
        // Drawn at the largest size any picture shows each text, so no picture shows it blurred.
        val sizes = items.map { item -> seq.maxOfOrNull { it.texts[item.id]?.h ?: 0.1f } ?: 0.1f }
        // Only what changes the glyphs (colours and shadow are uniforms): a colour slider must not redraw it.
        val k = "${items.map { "${it.id}/${it.text}/${it.align}/${it.style.font}/${it.style.customFont}/${it.style.weight}/${it.style.fill}" }}|" +
            "${sizes.joinToString { ((it * 20).toInt()).toString() }}|$height"
        if (building) { lastLayer = null; return tex != 0 && art != null }
        if (k != key) {
            building = true
            io.execute {
                val ctx = Repo.context
                val px = items.indices.maxOf { i ->
                    val emH = TextArt.shape(ctx, items[i])?.second ?: 1.5f
                    sizes[i] * height / emH
                }.coerceIn(24f, 320f)
                val out = runCatching { TextArt.render(ctx, layer.items, (px / 16f).toInt() * 16f + 16f) }.getOrNull()
                host.post {
                    building = false
                    if (tex != 0) glDeleteTextures(2, intArrayOf(tex, data), 0)
                    tex = 0; data = 0; art = out
                    if (out != null) {
                        tex = upload(out.pixels, out.width, out.height)
                        data = makeData(out)
                    }
                    key = k
                    lastLayer = null      // look again: the texts may have changed while this was drawn
                    host.requestRender()
                }
            }
        }
        return tex != 0 && art != null
    }

    private fun makeData(a: TextArt.Art): Int {
        var n = 0
        for (t in 0..1) {
            val at = a.texts.getOrNull(t)
            first[t] = n; count[t] = at?.cells?.size ?: 0; n += count[t]
        }
        cells = n.coerceAtMost(MAX_CELLS)
        val buf = ByteBuffer.allocateDirect(MAX_CELLS * 2 * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val row0 = FloatArray(4 * MAX_CELLS)
        for (t in 0..1) a.texts.getOrNull(t)?.let { at ->
            at.cells.forEachIndexed { i, c ->
                val j = first[t] + i
                if (j >= MAX_CELLS) return@forEachIndexed
                row0[j * 4] = c.left; row0[j * 4 + 1] = c.top; row0[j * 4 + 2] = c.right; row0[j * 4 + 3] = c.bottom
                row1[j * 4] = at.strip[i].toFloat(); row1[j * 4 + 1] = at.parity[i].toFloat(); row1[j * 4 + 2] = 1f; row1[j * 4 + 3] = 1f
            }
        }
        buf.put(row0).put(row1).position(0)
        row1.copyInto(row1Sent)
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glActiveTexture(GL_TEXTURE9)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, MAX_CELLS, 2, 0, GL_RGBA, GL_FLOAT, buf)
        stripU.fill(0f)
        a.strips.forEachIndexed { i, s -> if (i < 4) s.copyInto(stripU, i * 4) }
        return ids[0]
    }

    private fun box(p: ClockPlacement?, t: Int, aspectHW: Float, zoom: Float, out: FloatArray) {
        val at = art?.texts?.getOrNull(t)
        if (p == null || at == null) { out[t * 4 + 2] = 0f; return }
        val h = p.h * zoom; val w = h * (at.emW / at.emH) * p.sx * aspectHW
        out[t * 4] = p.cx - w / 2; out[t * 4 + 1] = p.cy - h / 2; out[t * 4 + 2] = w; out[t * 4 + 3] = h
    }

    /**
     * Binds the texts for images [a] → [b] at [progress]. [still]: the texts stay on screen and glide between
     * the two pictures' spots (else each picture carries its own). Returns true if it asked for another frame.
     */
    fun bind(p: TiltRenderer.Program, layer: TextLayer, a: WallImage, b: WallImage?, progress: Float, still: Boolean,
             aspectHW: Float, vis: Float, zoom: Float, autoColor: Int): Boolean {
        val bb = b ?: a
        val items = layer.items
        var moving = false
        val alpha = FloatArray(4)
        for (t in 0..1) {
            val item = items.getOrNull(t)?.takeIf { it.text.isNotBlank() }
            val pa = item?.let { a.texts[it.id] }; val pb = item?.let { bb.texts[it.id] }
            box(pa, t, aspectHW, zoom, boxA); box(pb, t, aspectHW, zoom, boxB)
            var aa = if (pa != null && !pa.hidden) 1f else 0f; var ab = if (pb != null && !pb.hidden) 1f else 0f
            // A text missing on one side fades in place rather than flying in from nowhere.
            if (boxA[t * 4 + 2] == 0f) System.arraycopy(boxB, t * 4, boxA, t * 4, 4)
            if (boxB[t * 4 + 2] == 0f) System.arraycopy(boxA, t * 4, boxB, t * 4, 4)
            if (still) {
                val k = if (b != null) progress else 0f
                for (i in 0..3) { val v = boxA[t * 4 + i] + (boxB[t * 4 + i] - boxA[t * 4 + i]) * k; boxA[t * 4 + i] = v; boxB[t * 4 + i] = v }
                val v = aa + (ab - aa) * k; aa = v; ab = v
            }
            alpha[t * 2] = aa; alpha[t * 2 + 1] = ab
            val at = art?.texts?.getOrNull(t)
            em[t * 4] = at?.emW ?: 1f; em[t * 4 + 1] = at?.emH ?: 1f; em[t * 4 + 2] = first[t].toFloat(); em[t * 4 + 3] = if (item == null) 0f else count[t].toFloat()
            // Kept heights: eased like rubber when still (dragging, refits), per picture otherwise.
            for (i in 0 until count[t]) {
                val j = first[t] + i
                if (j >= MAX_CELLS) break
                val fa = pa?.sq?.getOrNull(i) ?: 1f; val fb = pb?.sq?.getOrNull(i) ?: 1f
                if (still || b == null) {
                    val target = fa + (fb - fa) * (if (b != null) progress else 0f)
                    sqNow[j] += (target - sqNow[j]) * 0.25f
                    if (abs(target - sqNow[j]) > 0.002f) moving = true else sqNow[j] = target
                    row1[j * 4 + 2] = sqNow[j]; row1[j * 4 + 3] = sqNow[j]
                } else {
                    sqNow[j] = if (progress >= 0.5f) fb else fa
                    row1[j * 4 + 2] = fa; row1[j * 4 + 3] = fb
                }
            }
            item?.style?.let { st ->
                val c = if (st.color != 0) st.color else autoColor
                color[t * 4] = android.graphics.Color.red(c) / 255f; color[t * 4 + 1] = android.graphics.Color.green(c) / 255f
                color[t * 4 + 2] = android.graphics.Color.blue(c) / 255f; color[t * 4 + 3] = st.opacity * vis
                fx[t * 4] = st.fill.ordinal.toFloat(); fx[t * 4 + 1] = st.seeThrough
                fx[t * 4 + 2] = if (st.shadow) st.shadowAmt * 2.5f else 0f; fx[t * 4 + 3] = if (st.gradient.size >= 2) 1f else 0f
                if (st.gradient.size >= 2) {
                    fun cols(img: WallImage) = if (st.gradientAuto && img.palette.isNotEmpty()) List(3) { img.palette.getOrElse(it) { img.palette.last() } }
                        else List(3) { st.gradient.getOrElse(it) { st.gradient.last() } }
                    val ca = cols(a); val cb = cols(bb); val k = if (b != null) progress else 0f
                    for (g in 0..2) {
                        val x = ca[g]; val y = cb[g]
                        grad[t * 9 + g * 3] = (android.graphics.Color.red(x) + (android.graphics.Color.red(y) - android.graphics.Color.red(x)) * k) / 255f
                        grad[t * 9 + g * 3 + 1] = (android.graphics.Color.green(x) + (android.graphics.Color.green(y) - android.graphics.Color.green(x)) * k) / 255f
                        grad[t * 9 + g * 3 + 2] = (android.graphics.Color.blue(x) + (android.graphics.Color.blue(y) - android.graphics.Color.blue(x)) * k) / 255f
                    }
                }
            }
        }
        if (!row1.contentEquals(row1Sent)) {
            // On the texts' own unit: binding it on whatever unit was active (the clock's) swapped the clock texture
            // for this float table mid-transition, and the clock showed as solid blocks.
            glActiveTexture(GL_TEXTURE9)
            glBindTexture(GL_TEXTURE_2D, data)
            val buf = ByteBuffer.allocateDirect(MAX_CELLS * 16).order(ByteOrder.nativeOrder()).asFloatBuffer().put(row1).also { it.position(0) }
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 1, MAX_CELLS, 1, GL_RGBA, GL_FLOAT, buf)
            row1.copyInto(row1Sent)
        }
        fun top(img: WallImage) = layer.onTop || items.any { img.texts[it.id]?.forcedTop == true }
        glActiveTexture(GL_TEXTURE8); glBindTexture(GL_TEXTURE_2D, tex); glUniform1i(p.u("uTxtTex"), 8)
        glActiveTexture(GL_TEXTURE9); glBindTexture(GL_TEXTURE_2D, data); glUniform1i(p.u("uTxtData"), 9)
        glUniform4fv(p.u("uTxtBoxA"), 2, boxA, 0); glUniform4fv(p.u("uTxtBoxB"), 2, boxB, 0)
        glUniform4fv(p.u("uTxtEm"), 2, em, 0)
        glUniform4fv(p.u("uTxtStrip"), 4, stripU, 0)
        art?.let { glUniform2f(p.u("uTxtScale"), it.scaleU, it.scaleV) }
        glUniform4fv(p.u("uTxtColor"), 2, color, 0); glUniform4fv(p.u("uTxtFx"), 2, fx, 0)
        glUniform3fv(p.u("uTxtGrad"), 6, grad, 0)
        glUniform4f(p.u("uTxtAlpha"), alpha[0], alpha[1], alpha[2], alpha[3])
        glUniform4f(p.u("uTxtMisc"), if (top(a)) 1f else 0f, if (top(bb)) 1f else 0f, layer.front.coerceIn(0, 1).toFloat(), 1f)
        return moving
    }

    fun release() {
        if (tex != 0) glDeleteTextures(2, intArrayOf(tex, data), 0)
        tex = 0; data = 0; art = null; key = null; lastLayer = null
    }

    companion object { const val MAX_CELLS = 48 }
}
