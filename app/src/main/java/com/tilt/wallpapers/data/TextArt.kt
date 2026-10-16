package com.tilt.wallpapers.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextDirectionHeuristics
import kotlinx.serialization.Serializable
import java.text.BreakIterator
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Serializable
enum class TextAlign(private val vi: String) { LEFT("Trái"), CENTER("Giữa"), RIGHT("Phải"); val label: String get() = t(vi) }

/** One free text: its words, alignment and look (the clock's style fields; clock-only ones are unused). */
@Serializable
data class TextItem(
    val id: String,
    val text: String = "",
    val align: TextAlign = TextAlign.CENTER,
    val style: ClockStyle = ClockStyle(enabled = true, weight = 700),
)

/** A set's free texts. Both share one layer; Chữ 1 (index 0) has priority, Chữ 2 makes way for it. */
@Serializable
data class TextLayer(
    val items: List<TextItem> = emptyList(),
    val onTop: Boolean = false,
    val avoidSubject: Boolean = true,
    val avoidClock: Boolean = true,
    val avoidEach: Boolean = true,
    /** "Bám": characters with something below shrink from the bottom instead of the text stopping at the wall. */
    val hug: Boolean = true,
    /** Index of the text drawn over the other where they overlap. */
    val front: Int = 1,
)

/**
 * Free texts: cleaning what is typed, laying it out (per character cells, so each can shrink on its own),
 * drawing it into a texture and measuring its shape for placement. Same rules as the clock's digits: red =
 * glyphs, green / blue / alpha = halos of every third character (with two layers, a thin letter like "l" put
 * two same-layer neighbours within reach and one showed the other's unshrunk shadow), each line in its own
 * strip so lines never share halos.
 */
object TextArt {
    const val MAX_LINES = 2
    const val MAX_CHARS = 12
    /** How far a character's glow reaches, in em (the shader uses the same): the whole halo, no more. */
    const val REACH = 0.2f

    private fun isEmoji(cp: Int) = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x1F1E6..0x1F1FF ||
        cp == 0xFE0F || cp == 0x200D || cp == 0x20E3 || cp in 0xE0020..0xE007F || cp in 0x2B00..0x2BFF
    fun hasEmoji(s: String) = s.codePoints().anyMatch { isEmoji(it) }

    /** What may be typed: composed accents (so "ể" counts once however it was typed), no emoji, 2 lines of 12. */
    fun clean(raw: String): String {
        val s = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val noEmoji = buildString { s.codePoints().forEach { if (!isEmoji(it)) appendCodePoint(it) } }
        return noEmoji.split('\n').take(MAX_LINES).joinToString("\n") { line -> line.take(clusterEnd(line, MAX_CHARS)) }
    }

    /** Characters as the reader sees them (a letter with its marks = 1). */
    fun count(line: String): Int {
        val bi = BreakIterator.getCharacterInstance().apply { setText(line) }
        var n = 0
        while (bi.next() != BreakIterator.DONE) n++
        return n
    }

    private fun clusterEnd(line: String, n: Int): Int {
        val bi = BreakIterator.getCharacterInstance().apply { setText(line) }
        var end = 0
        repeat(n) { val e = bi.next(); if (e == BreakIterator.DONE) return line.length; end = e }
        return end
    }

    private val JOINING = setOf(
        Character.UnicodeScript.ARABIC, Character.UnicodeScript.SYRIAC, Character.UnicodeScript.NKO, Character.UnicodeScript.MONGOLIAN,
        Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI, Character.UnicodeScript.GURMUKHI, Character.UnicodeScript.GUJARATI,
        Character.UnicodeScript.ORIYA, Character.UnicodeScript.TAMIL, Character.UnicodeScript.TELUGU, Character.UnicodeScript.KANNADA,
        Character.UnicodeScript.MALAYALAM, Character.UnicodeScript.SINHALA, Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
        Character.UnicodeScript.TIBETAN, Character.UnicodeScript.MYANMAR, Character.UnicodeScript.KHMER,
    )
    /** Scripts whose letters join or combine: they shrink a word at a time, so no join is cut apart. */
    private fun joining(s: String) = s.codePoints().anyMatch { runCatching { Character.UnicodeScript.of(it) in JOINING }.getOrDefault(false) }

    class Line(val text: String, val rtl: Boolean, val x: Float, val base: Float, val top: Float, val bottom: Float)
    /** A character (or word) cell in block pixels; [runX] = where its run starts, for drawing its halo alone. */
    class Cell(val box: RectF, val line: Int, val start: Int, val end: Int, val runX: Float, val parity: Int)
    class Laid(val w: Float, val h: Float, val size: Float, val lines: List<Line>, val cells: List<Cell>)

    private fun paint(ctx: Context, item: TextItem, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ClockText.typeface(ctx, item.style); textSize = size
    }

    /** The block at font size [size] px (null if there is nothing to show). */
    fun layout(ctx: Context, item: TextItem, size: Float): Laid? {
        val texts = item.text.split('\n').take(MAX_LINES).dropLastWhile { it.isBlank() }
        if (texts.isEmpty()) return null
        val p = paint(ctx, item, size)
        val fm = p.fontMetrics
        val r = Rect()
        class M(val adv: Float, val inkL: Float, val inkR: Float, val top: Float, val bottom: Float, val rtl: Boolean)
        val ms = texts.map { s ->
            val adv = p.measureText(s)
            if (s.isNotEmpty()) p.getTextBounds(s, 0, s.length, r) else r.setEmpty()
            // Stacked accents (ể, ặ) and descenders reach past the font's own line box: the ink decides.
            M(adv, min(0f, r.left.toFloat()), max(adv, r.right.toFloat()), min(fm.ascent, r.top.toFloat()), max(fm.descent, r.bottom.toFloat()),
                TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(s, 0, s.length))
        }
        val contentW = ms.maxOf { it.inkR - it.inkL }.coerceAtLeast(size * 0.2f)
        val m = size * 0.13f
        var y = m
        val lines = ArrayList<Line>(); val cells = ArrayList<Cell>()
        texts.forEachIndexed { li, s ->
            val mm = ms[li]
            val base = y - mm.top
            val x = m + when (item.align) {
                TextAlign.LEFT -> -mm.inkL
                TextAlign.CENTER -> (contentW - (mm.inkR - mm.inkL)) / 2f - mm.inkL
                TextAlign.RIGHT -> contentW - mm.inkR
            }
            lines += Line(s, mm.rtl, x, base, base + mm.top, base + mm.bottom)
            // Cells at the run's cursor positions (shaping kept: positions come from the whole line).
            val n = s.length
            fun pos(i: Int) = if (mm.rtl) x + mm.adv - p.getRunAdvance(s, 0, n, 0, n, true, i) else x + p.getRunAdvance(s, 0, n, 0, n, false, i)
            val bi = (if (joining(s)) BreakIterator.getWordInstance() else BreakIterator.getCharacterInstance()).apply { setText(s) }
            val first = cells.size
            var a = bi.first(); var b = bi.next(); var k = 0
            while (b != BreakIterator.DONE) {
                if (s.substring(a, b).isNotBlank()) {
                    val pa = pos(a); val pb = pos(b)
                    cells += Cell(RectF(min(pa, pb), base + mm.top, max(pa, pb), base + mm.bottom), li, a, b, min(pa, pb), k % 3)
                    k++
                }
                a = b; b = bi.next()
            }
            // The outermost cells take any ink that hangs past the advances.
            cells.subList(first, cells.size).let { lc ->
                lc.minByOrNull { it.box.left }?.box?.let { it.left = min(it.left, x + mm.inkL) }
                lc.maxByOrNull { it.box.right }?.box?.let { it.right = max(it.right, x + mm.inkR) }
            }
            y = base + mm.bottom + size * 0.06f
        }
        return Laid(contentW + 2 * m, y - size * 0.06f + m, size, lines, cells)
    }

    /** Block aspect and per-character cells (as fractions of the block) for placing the text; null = empty. */
    fun shape(ctx: Context, item: TextItem): Pair<ClockText.Shape, Float>? {
        val l = layout(ctx, item, 100f) ?: return null
        val c = l.cells.map { RectF(it.box.left / l.w, it.box.top / l.h, it.box.right / l.w, it.box.bottom / l.h) }
        if (c.isEmpty()) return null
        return ClockText.Shape(l.w / l.h, c, c, IntArray(c.size) { it }) to l.h / l.size
    }

    /** One text in the texture: block size and cells in em, each cell's strip and halo channel, strips. */
    class ArtText(val emW: Float, val emH: Float, val cells: List<RectF>, val strip: IntArray, val parity: IntArray)
    /** [strips]: per line, its origin in its block (em) and in the texture (0..1): l, t, u, v. */
    class Art(val pixels: java.nio.ByteBuffer, val width: Int, val height: Int, val scaleU: Float, val scaleV: Float,
              val texts: List<ArtText?>, val strips: List<FloatArray>)

    /** Draws every text of [items] at font size [size] px, one strip per line. */
    fun render(ctx: Context, items: List<TextItem>, size0: Float): Art? {
        var size = size0
        var laid = items.map { layout(ctx, it, size) }
        fun dims(): Pair<Int, Int> {
            val g = REACH * size
            var w = 1f; var h = 0f
            laid.forEach { l -> l?.lines?.forEach { ln -> w = max(w, l.w + 2 * g); h += ln.bottom - ln.top + 2 * g + 2 } }
            return w.roundToInt() + 2 to h.roundToInt().coerceAtLeast(1) + 2
        }
        var (aw, ah) = dims()
        if (aw > 2048 || ah > 2048) {
            size *= 2048f / max(aw, ah); laid = items.map { layout(ctx, it, size) }
            dims().let { aw = it.first; ah = it.second }
        }
        if (laid.all { it == null }) return null
        val layers = Array(4) { Bitmap.createBitmap(aw, ah, Bitmap.Config.ALPHA_8) }
        val cv = layers.map { Canvas(it) }
        val g = REACH * size
        val strips = ArrayList<FloatArray>()
        val out = ArrayList<ArtText?>()
        var v0 = 1f
        items.forEachIndexed { ti, item ->
            val l = laid[ti] ?: run { out += null; return@forEachIndexed }
            val p = paint(ctx, item, size)
            val halo = Paint(p).apply {
                color = 0xFF000000.toInt(); maskFilter = BlurMaskFilter(max(2f, size * 0.07f), BlurMaskFilter.Blur.NORMAL)
                style = Paint.Style.FILL_AND_STROKE; strokeWidth = size * 0.02f
            }
            val fill = Paint(p).apply {
                color = 0xFF000000.toInt()
                if (item.style.fill == ClockFill.OUTLINE) { style = Paint.Style.STROKE; strokeWidth = max(1.5f, size * 0.022f) }
            }
            val stripOf = IntArray(l.lines.size)
            l.lines.forEachIndexed { li, ln ->
                stripOf[li] = strips.size
                // This line's region of the block (with room for its glow) lands at (1, v0) in the texture.
                val ox = -g; val oy = ln.top - g
                strips += floatArrayOf(ox / size, oy / size, 1f / aw, v0 / ah)
                val dx = 1f - ox; val dy = v0 - oy
                cv[0].drawText(ln.text, ln.x + dx, ln.base + dy, fill)
                l.cells.filter { it.line == li }.forEach { c ->
                    cv[1 + c.parity].drawTextRun(ln.text, c.start, c.end, 0, ln.text.length, c.runX + dx, ln.base + dy, ln.rtl, halo)
                }
                v0 += ln.bottom - ln.top + 2 * g + 2
            }
            out += ArtText(l.w / size, l.h / size, l.cells.map { RectF(it.box.left / size, it.box.top / size, it.box.right / size, it.box.bottom / size) },
                IntArray(l.cells.size) { stripOf[l.cells[it].line] }, IntArray(l.cells.size) { l.cells[it].parity })
        }
        return Art(ClockText.pack(layers), aw, ah, size / aw, size / ah, out, strips)
    }
}

/**
 * Where free texts go on a picture. Obstacles are one coarse grid: the subject (behind it only), the clock,
 * and Chữ 1 for Chữ 2. The clock never makes way for a text, and Chữ 1 never for Chữ 2.
 */
object TextPlace {
    /** [base] (the subject, or none) plus [rects] (screen fractions) marked as taken. */
    fun grid(base: ClockPlace.Coverage?, aspect: Float, rects: List<RectF>): ClockPlace.Coverage? {
        if (base == null && rects.isEmpty()) return null
        val gw = ClockPlace.GW; val gh = base?.h ?: (gw / aspect).roundToInt()
        val a = base?.a?.copyOf() ?: FloatArray(gw * gh)
        for (r in rects) {
            val x0 = (r.left * gw).toInt().coerceIn(0, gw - 1); val x1 = (r.right * gw).toInt().coerceIn(0, gw - 1)
            val y0 = (r.top * gh).toInt().coerceIn(0, gh - 1); val y1 = (r.bottom * gh).toInt().coerceIn(0, gh - 1)
            for (y in y0..y1) for (x in x0..x1) a[y * gw + x] = 1f
        }
        return ClockPlace.Coverage(gw, gh, a)
    }

    /** Screen boxes of the digits / characters (and the date) as placed, shrunk columns included. */
    fun rects(p: ClockPlacement, shape: ClockText.Shape, aspect: Float): List<RectF> {
        val bw = ClockPlace.bwOf(p, shape, aspect); val l0 = p.cx - bw / 2; val t0 = p.cy - p.h / 2
        val out = shape.digits.mapIndexed { i, d ->
            val col = shape.columns[shape.digitColumn[i]]
            val f = p.sq.getOrElse(shape.digitColumn[i]) { 1f }
            RectF(l0 + ClockPlace.hxMap(d.left, shape.time, p) * bw, t0 + (col.top + (d.top - col.top) * f) * p.h,
                l0 + ClockPlace.hxMap(d.right, shape.time, p) * bw, t0 + (col.top + (d.bottom - col.top) * f) * p.h)
        }
        return out + listOfNotNull(shape.date?.let { RectF(l0 + it.left * bw, t0 + it.top * p.h, l0 + it.right * bw, t0 + it.bottom * p.h) })
    }

    fun land(cov: ClockPlace.Coverage?, p: ClockPlacement, shape: ClockText.Shape, aspect: Float, hug: Boolean): ClockPlacement {
        val q = ClockPlace.onScreen(p, shape, aspect)
        return if (hug && cov != null) q.copy(sq = squash(cov, q, shape, aspect)) else q.copy(sq = emptyList())
    }

    /**
     * "Bám": each character stops a gap above what lies below it. Only obstacles reaching into its lower half
     * count (shrinking from the bottom cannot clear one at its top, like the clock above a text): those stay
     * for the wall. Never below half height.
     */
    private fun squash(cov: ClockPlace.Coverage, p: ClockPlacement, shape: ClockText.Shape, aspect: Float): List<Float> {
        val bw = ClockPlace.bwOf(p, shape, aspect); val l0 = p.cx - bw / 2; val t0 = p.cy - p.h / 2
        return shape.columns.map { c ->
            val x0 = ((l0 + c.left * bw - 0.011f) * cov.w).toInt().coerceIn(0, cov.w - 1)
            val x1 = ((l0 + c.right * bw + 0.011f) * cov.w).toInt().coerceIn(0, cov.w - 1)
            val t = t0 + c.top * p.h; val b = t0 + c.bottom * p.h
            val y0 = ((t + (b - t) * 0.5f) * cov.h).toInt().coerceIn(0, cov.h - 1)
            val y1 = ((b + 0.022f) * cov.h).toInt().coerceIn(0, cov.h - 1)
            var hit = -1f
            loop@ for (y in y0..y1) for (x in x0..x1) if (cov.a[y * cov.w + x] > 0.5f) { hit = y.toFloat() / cov.h; break@loop }
            if (hit < 0f) 1f else ((hit - 0.022f - t) / (b - t)).coerceIn(ClockPlace.MIN_SQUASH, 1f)
        }
    }

    fun fits(cov: ClockPlace.Coverage?, p: ClockPlacement, shape: ClockText.Shape, aspect: Float) =
        cov == null || ClockPlace.cover(cov, p, shape, aspect) <= ClockPlace.WALL

    /** Default size: lines about 6% of the screen tall, never wider than 86% of it. */
    fun defaultH(shape: ClockText.Shape, emH: Float, aspect: Float) = min(0.06f * emH, 0.86f * aspect / shape.aspect)

    /** First free spot from [fromY] down (centre, then either side); null if none. */
    private fun search(cov: ClockPlace.Coverage?, shape: ClockText.Shape, h: Float, aspect: Float, hug: Boolean, fromY: Float): ClockPlacement? {
        for (start in listOf(fromY, 0.06f).distinct()) {
            var cy = start + h / 2
            while (cy + h / 2 <= 0.95f) {
                for (cx in floatArrayOf(0.5f, 0.32f, 0.68f)) {
                    val q = land(cov, ClockPlacement(cx, cy, h), shape, aspect, hug)
                    if (fits(cov, q, shape, aspect)) return q
                }
                cy += 0.015f
            }
        }
        return null
    }

    /**
     * Places every text of [set] on [img] (whose clock is already placed). Hand placements keep their spot and
     * only re-shrink around what is there now. If any text finds no room behind the subject, both go on top.
     */
    fun fitImage(set: WallSet, img: WallImage, subject: ClockPlace.Coverage?, aspect: Float, key: Int,
                 textShape: (TextItem) -> Pair<ClockText.Shape, Float>?, clockShape: () -> ClockText.Shape?): Map<String, ClockPlacement> {
        val layer = set.texts
        val clockRects = img.clock?.takeIf { layer.avoidClock && set.clock.enabled }?.let { p -> clockShape()?.let { rects(p, it, aspect) } }.orEmpty()
        fun attempt(top: Boolean): Map<String, ClockPlacement>? {
            val out = LinkedHashMap<String, ClockPlacement>()
            var firstRects = emptyList<RectF>(); var firstBottom = 0.06f
            for ((i, item) in layer.items.withIndex()) {
                val old = img.texts[item.id]
                val (shape, emH) = textShape(item) ?: continue
                val cov = grid(if (!top && layer.avoidSubject) subject else null, aspect, clockRects + if (i > 0 && layer.avoidEach) firstRects else emptyList())
                // A hand placement keeps its spot; behind the subject it must still clear what it avoids, or both texts
                // go on top (it was dragged there while they were on top).
                val p = if (old != null && !old.auto) land(cov, old, shape, aspect, layer.hug).let { q ->
                    if (old.hidden || fits(cov, q, shape, aspect)) q
                    else if (!top) return null
                    // On top, something it avoids came over it (the clock never makes way): the nearest free spot below.
                    else search(cov, shape, old.h, aspect, layer.hug, maxOf(0.06f, old.cy - old.h / 2))?.copy(sx = old.sx) ?: q
                } else search(cov, shape, defaultH(shape, emH, aspect), aspect, layer.hug, if (i > 0) firstBottom else 0.06f)
                        ?: if (!top) return null else land(cov, ClockPlacement(0.5f, min(0.9f, firstBottom + 0.1f), defaultH(shape, emH, aspect)), shape, aspect, false)
                val placed = p.copy(auto = old?.auto ?: true, key = key, hidden = old?.hidden ?: false, forcedTop = top && !layer.onTop)
                if (i == 0 && !placed.hidden) { firstRects = rects(placed, shape, aspect); firstBottom = placed.cy + placed.h / 2 }
                out[item.id] = placed
            }
            return out
        }
        return if (layer.onTop) attempt(true)!! else attempt(false) ?: attempt(true)!!
    }
}
