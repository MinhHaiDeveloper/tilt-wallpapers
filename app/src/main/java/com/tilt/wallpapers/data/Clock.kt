package com.tilt.wallpapers.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Serializable
enum class ClockFont(
    private val vi: String, val asset: String, val minWeight: Int, val maxWeight: Int, val vietnamese: Boolean,
    /** Width-axis range used by Mảnh ↔ Đậm (wide when thin, narrow when bold); 0 = no width axis. */
    val wideW: Float = 0f, val narrowW: Float = 0f,
) {
    ROBOTO_FLEX("Roboto Flex", "fonts/roboto_flex.ttf", 100, 1000, true, 112f, 28f),
    ANYBODY("Anybody", "fonts/anybody.ttf", 100, 900, true, 130f, 50f),
    SMOOCH("Smooch Sans", "fonts/smooch_sans.ttf", 100, 900, true),
    CONCERT("Concert One", "fonts/concert_one.ttf", 400, 400, false),
    ANTONIO("Antonio", "fonts/antonio.ttf", 100, 700, false),
    BIG_SHOULDERS("Big Shoulders", "fonts/big_shoulders.ttf", 100, 900, true);
    val label: String get() = t(vi)
    val variable get() = minWeight != maxWeight

    /** Font variation settings for [weight]: Mảnh = light and wide, Đậm = heavy and condensed. */
    fun variation(weight: Int): String {
        val w = weight.coerceIn(minWeight, maxWeight)
        if (!widthAxis) return "'wght' $w"
        return "'wght' $w, 'wdth' ${widthOf(w).roundToInt()}"
    }
    val widthAxis get() = wideW > 0f
    fun widthOf(weight: Int): Float = if (!widthAxis) 100f
        else wideW + (narrowW - wideW) * (weight.coerceIn(minWeight, maxWeight) - minWeight).toFloat() / (maxWeight - minWeight)
}

@Serializable
enum class ClockLayout(private val vi: String) { ONE_LINE("Ngang"), STACKED("Dọc"); val label: String get() = t(vi) }

@Serializable
enum class ClockDate(private val vi: String) { OFF("Tắt"), ABOVE("Trên"), BELOW("Dưới"); val label: String get() = t(vi) }

@Serializable
enum class ClockFill(private val vi: String) { SOLID("Màu đặc"), OUTLINE("Viền"), GLASS("Kính"); val label: String get() = t(vi) }

@Serializable
enum class ClockFit(private val vi: String) { BEHIND_HEAD("Sau đầu"), BIG("Phủ lớn"); val label: String get() = t(vi) }

@Serializable
enum class ClockShowOn(private val vi: String) { HOME("Màn hình chính"), LOCK("Màn hình khóa"), BOTH("Cả hai"); val label: String get() = t(vi) }

/** A set's clock: one style for every picture of the set. */
@Serializable
data class ClockStyle(
    val enabled: Boolean = false,
    val font: ClockFont = ClockFont.SMOOCH,
    val weight: Int = 600,
    val layout: ClockLayout = ClockLayout.ONE_LINE,
    val date: ClockDate = ClockDate.ABOVE,
    val fill: ClockFill = ClockFill.SOLID,
    /** ARGB; 0 = a light tint of the set's colours. */
    val color: Int = 0,
    val opacity: Float = 1f,
    val shadow: Boolean = true,
    /** How dark the soft shadow behind the digits is (0..1). */
    val shadowAmt: Float = 0.4f,
    /** 0..1: how much the subject turns see-through where it covers the digits. */
    val seeThrough: Float = 0f,
    /** 0 = fixed on screen, 1 = drifts like the subject. Independent of the pictures' parallax. */
    val depth: Float = 0.5f,
    /** Moves / flips with each picture instead of staying put while pictures change. */
    val followImage: Boolean = false,
    val fit: ClockFit = ClockFit.BEHIND_HEAD,
    /** Moving the clock resizes it to hug the subject: as big as fits there without hiding the digits. */
    val hug: Boolean = true,
    /** Top / middle / bottom colours of a gradient fill; empty = single colour. */
    val gradient: List<Int> = emptyList(),
    /** The gradient comes from the picture on screen (and changes with it); false = the colours set by hand. */
    val gradientAuto: Boolean = true,
    /** Default layer for the set's pictures: on top of everything instead of behind the subject. */
    val onTop: Boolean = false,
    /** A font the user added ([CustomFont] id), used instead of [font]. */
    val customFont: String? = null,
) {
    /** What changes the clock's shape (and so where it fits). */
    fun shapeKey() = "$font/${customFont ?: ""}/$weight/$layout/$date/$fit/$hug/sq8"
    /** Weight range of the font in use (a user font may have been deleted: then the built-in one). */
    val minWeight get() = Fonts.find(customFont)?.minWeight ?: font.minWeight
    val maxWeight get() = Fonts.find(customFont)?.maxWeight ?: font.maxWeight
    val variableWeight get() = minWeight != maxWeight
    /** The same look (font, weight, fill, colours, shadow) taken from [o], for "Dùng kiểu của đồng hồ". */
    fun lookOf(o: ClockStyle) = copy(font = o.font, customFont = o.customFont, weight = o.weight, fill = o.fill, color = o.color,
        opacity = o.opacity, shadow = o.shadow, shadowAmt = o.shadowAmt, seeThrough = o.seeThrough, gradient = o.gradient, gradientAuto = o.gradientAuto)
}

/** Where one picture shows the clock: centre and height as fractions of the screen. */
@Serializable
data class ClockPlacement(
    val cx: Float,
    val cy: Float,
    val h: Float,
    /** Placed by "Tự đặt"; recomputed when the subject or the clock shape changes. */
    val auto: Boolean = true,
    val key: Int = 0,
    /** Largest share of one digit hidden by the subject (0..1). */
    val cover: Float = 0f,
    /** "Bám chủ thể": height kept per digit column (0.55..1), so digits stop short of the subject. */
    val sq: List<Float> = emptyList(),
    /** Width kept for the whole time (0.5..1), squeezed toward the side away from the subject. */
    val hx: Float = 1f,
    val hxRight: Boolean = false,
    /** This picture's own layout (null = the set's). */
    val layout: ClockLayout? = null,
    /** Horizontal stretch from dragging a side edge (1 = the font's own proportions). */
    val sx: Float = 1f,
    /** Layer for this picture: 0 = the set's, 1 = behind the subject, 2 = on top. */
    val layer: Int = 0,
    /** Put on top automatically: the subject left no room behind it. */
    val forcedTop: Boolean = false,
    /** "Bám chủ thể" for this picture (null = the set's). */
    val hug: Boolean? = null,
    /** A free text switched off on this picture only ("Ẩn trên ảnh này"). */
    val hidden: Boolean = false,
) {
    fun hugOn(style: ClockStyle) = hug ?: style.hug
    fun topOn(style: ClockStyle) = when (layer) { 1 -> false; 2 -> true; else -> style.onTop || forcedTop }
}

/**
 * Draws the clock into a texture (red = glyphs, green = soft halo for the shadow and the glass edge) and
 * measures its shape. Typefaces are cached; everything here is cheap enough to run once a minute.
 */
object ClockText {
    private val faces = HashMap<String, Typeface>()

    /** The style's font at its weight: a user font when it has one (and it still exists). */
    fun typeface(ctx: Context, style: ClockStyle, weight: Int = style.weight): Typeface {
        val cf = Fonts.find(style.customFont) ?: return typeface(ctx, style.font, weight)
        val w = weight.coerceIn(cf.minWeight, cf.maxWeight)
        return synchronized(faces) {
            faces.getOrPut("${cf.id}/$w") {
                runCatching {
                    Typeface.Builder(Fonts.file(ctx, cf.id)).apply { if (cf.variable) setFontVariationSettings("'wght' $w") }.build()
                }.getOrDefault(Typeface.DEFAULT_BOLD)
            }
        }
    }

    /** Whether the style's font has the Vietnamese letters. */
    fun vietnamese(style: ClockStyle) = Fonts.find(style.customFont)?.vietnamese ?: style.font.vietnamese

    fun typeface(ctx: Context, font: ClockFont, weight: Int): Typeface = synchronized(faces) {
        val w = weight.coerceIn(font.minWeight, font.maxWeight)
        faces.getOrPut("${font.name}/$w") {
            runCatching {
                Typeface.Builder(ctx.assets, font.asset).apply { if (font.variable) setFontVariationSettings(font.variation(w)) }.build()
            }.getOrDefault(Typeface.DEFAULT_BOLD)
        }
    }

    class Lines(val time: List<String>, val date: String?)

    fun lines(style: ClockStyle, now: Date, is24: Boolean): Lines {
        val hh = if (is24) SimpleDateFormat("HH", Locale.US).format(now) else SimpleDateFormat("hh", Locale.US).format(now)
        val mm = SimpleDateFormat("mm", Locale.US).format(now)
        val time = if (style.layout == ClockLayout.STACKED) listOf(hh, mm) else listOf("$hh:$mm")
        val date = if (style.date == ClockDate.OFF) null else if (I18n.lang == Lang.VI)
            SimpleDateFormat("EEEE, d 'tháng' M", Locale("vi")).format(now).replaceFirstChar { it.uppercase() }
        else SimpleDateFormat("EEE, d MMM", Locale.US).format(now)
        return Lines(time, date)
    }

    /**
     * Layout of the block at a given digit size, in pixels. Boxes cover each glyph's real ink as well as its
     * advance, and the block leaves a margin for the glow: heavy or slanted digits reach past their advance
     * and used to be cut off.
     */
    class Layout(
        val w: Float, val h: Float, val origins: List<android.graphics.PointF>, val digitBoxes: List<RectF>,
        val colonBox: RectF?, val dateBox: RectF?, val dateOrigin: android.graphics.PointF?, val pad: Float,
    )

    private fun paints(ctx: Context, style: ClockStyle, size: Float): Pair<Paint, Paint> {
        val face = typeface(ctx, style)
        // Tabular figures: every digit as wide as the others, so the clock (and its frame) keeps one width
        // whatever the time.
        val time = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size; fontFeatureSettings = "'tnum'" }
        val dateFace = if (vietnamese(style) || I18n.lang == Lang.EN) typeface(ctx, style, min(style.weight, style.maxWeight))
            else typeface(ctx, ClockFont.SMOOCH, 500)
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = dateFace
            textSize = size * if (style.layout == ClockLayout.STACKED) 0.11f else 0.17f
        }
        return time to datePaint
    }

    fun layout(ctx: Context, style: ClockStyle, lines: Lines, size: Float): Layout {
        val (tp, dp) = paints(ctx, style, size)
        val digit = Rect().also { tp.getTextBounds("0123456789", 0, 10, it) }
        val zero = Rect().also { tp.getTextBounds("0", 0, 1, it) }
        val digitH = digit.height().toFloat()
        val gap = digitH * 0.07f
        val pad = digitH * 0.12f
        val widths = lines.time.map { tp.measureText(it) }
        val dateBounds = lines.date?.let { Rect().also { r -> dp.getTextBounds(it, 0, it.length, r) } }
        val dateW = lines.date?.let { dp.measureText(it) } ?: 0f
        val dateH = dateBounds?.let { it.height().toFloat() * 1.25f } ?: 0f
        val dateGap = if (lines.date != null) digitH * if (style.layout == ClockLayout.STACKED) 0.08f else 0.12f else 0f
        val contentW = max(widths.maxOrNull() ?: 0f, dateW)
        val timeH = digitH * lines.time.size + gap * (lines.time.size - 1)
        val origins = ArrayList<android.graphics.PointF>(); val digits = ArrayList<RectF>()
        var colon: RectF? = null
        val all = RectF(0f, 0f, contentW, 0f)
        var y = if (style.date == ClockDate.ABOVE && lines.date != null) dateH + dateGap else 0f
        val r = Rect()
        lines.time.forEachIndexed { i, s ->
            val x0 = (contentW - widths[i]) / 2
            val base = y + digitH - zero.bottom
            origins += android.graphics.PointF(x0, base)
            var x = x0
            for (ch in s) {
                val cw = tp.measureText(ch.toString())
                tp.getTextBounds(ch.toString(), 0, 1, r)
                val box = RectF(x, y, x + cw, y + digitH).apply { union(RectF(r).apply { offset(x, base) }) }
                if (ch.isDigit()) digits += box else if (ch == ':') colon = box
                all.union(box)
                x += cw
            }
            y += digitH + gap
        }
        var dateBox: RectF? = null; var dateOrigin: android.graphics.PointF? = null
        if (lines.date != null && dateBounds != null) {
            val top = if (style.date == ClockDate.ABOVE) 0f else timeH + dateGap
            val ox = (contentW - dateW) / 2; val oy = top + dateH * 0.8f
            dateOrigin = android.graphics.PointF(ox, oy)
            dateBox = RectF(ox, top, ox + dateW, top + dateH).apply { union(RectF(dateBounds).apply { offset(ox, oy) }) }
            all.union(dateBox)
        }
        // Room for the glow all round, then everything moved into place.
        val m = pad * 1.3f
        val dx = m - all.left; val dy = m - all.top
        origins.forEach { it.offset(dx, dy) }; dateOrigin?.offset(dx, dy)
        digits.forEach { it.offset(dx, dy) }; colon?.offset(dx, dy); dateBox?.offset(dx, dy)
        return Layout(all.width() + 2 * m, all.height() + 2 * m, origins, digits, colon, dateBox, dateOrigin, pad)
    }

    /**
     * Block width / height and digit boxes as fractions of the block (for placement), from a sample time.
     * [columns] are the digit columns that shrink together: each digit on one line, left / right in "Hai tầng".
     */
    class Shape(val aspect: Float, val digits: List<RectF>, val columns: List<RectF>, val digitColumn: IntArray, val date: RectF? = null) {
        /** The time's box (all digit columns). */
        val time: RectF = RectF(columns.first()).apply { columns.forEach { union(it) } }
    }

    fun columnsOf(digits: List<RectF>, stacked: Boolean): Pair<List<RectF>, IntArray> {
        if (!stacked || digits.size < 4) return digits.map { RectF(it) } to IntArray(digits.size) { it }
        val cols = listOf(RectF(digits[0]).apply { union(digits[2]) }, RectF(digits[1]).apply { union(digits[3]) })
        return cols to intArrayOf(0, 1, 0, 1)
    }

    fun shape(ctx: Context, style: ClockStyle): Shape {
        // Measured on the time being shown (digits differ in width in most fonts), so the frame matches the clock.
        val l = layout(ctx, style, lines(style, Date(), android.text.format.DateFormat.is24HourFormat(ctx)), 400f)
        val d = l.digitBoxes.map { RectF(it.left / l.w, it.top / l.h, it.right / l.w, it.bottom / l.h) }
        val (cols, idx) = columnsOf(d, style.layout == ClockLayout.STACKED)
        return Shape(l.w / l.h, d, cols, idx, l.dateBox?.let { RectF(it.left / l.w, it.top / l.h, it.right / l.w, it.bottom / l.h) })
    }

    /** A rendered clock and its digit columns in texture units (left, top, right, bottom). */
    /** Interleaves four same-size ALPHA_8 layers into raw RGBA bytes (and recycles them). */
    fun pack(layers: Array<Bitmap>): java.nio.ByteBuffer {
        val bw = layers[0].width; val bh = layers[0].height
        val rows = layers.map { b -> ByteArray(b.rowBytes * bh).also { b.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(it)) } }
        val out = ByteArray(bw * bh * 4)
        val stride = layers[0].rowBytes
        val r0 = rows[0]; val r1 = rows[1]; val r2 = rows[2]; val r3 = rows[3]
        var o = 0
        for (y in 0 until bh) {
            val r = y * stride
            for (x in r until r + bw) { out[o] = r0[x]; out[o + 1] = r1[x]; out[o + 2] = r2[x]; out[o + 3] = r3[x]; o += 4 }
        }
        layers.forEach { it.recycle() }
        return java.nio.ByteBuffer.allocateDirect(out.size).put(out).also { it.position(0) }
    }

    /** [cores]: the date's and colon's boxes in texture units (empty rect = none); [digits]: each digit's box. */
    class Rendered(val pixels: java.nio.ByteBuffer, val width: Int, val height: Int, val columns: List<RectF>, val cores: List<RectF> = emptyList(), val digits: List<RectF> = emptyList()) {
        val time: RectF = RectF(columns.first()).apply { columns.forEach { union(it) } }
    }

    /** The texture: [heightPx] tall, RGBA bytes: glyphs (or their outline), then the three halo layers. */
    fun render(ctx: Context, style: ClockStyle, lines: Lines, heightPx: Int): Rendered {
        val unit = layout(ctx, style, lines, 100f)
        var size = 100f * heightPx / unit.h
        if (unit.w * size / 100f > 2048f) size = 2048f * 100f / unit.w
        val l = layout(ctx, style, lines, size)
        val bw = max(1, l.w.roundToInt()); val bh = max(1, l.h.roundToInt())
        // Four layers: glyphs; the halo of digits 0 and 2; of digits 1 and 3; of the colon and date. Neighbouring
        // digits shrink by different amounts, and with one shared halo a digit kept its neighbour's unshrunk shadow.
        val layers = Array(4) { Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8) }
        val cv = layers.map { Canvas(it) }
        val (tp, dp) = paints(ctx, style, size)
        val blur = BlurMaskFilter(max(2f, l.pad * 0.7f), BlurMaskFilter.Blur.NORMAL)
        val haloT = Paint(tp).apply { color = 0xFF000000.toInt(); maskFilter = blur; this.style = Paint.Style.FILL_AND_STROKE; strokeWidth = size * 0.02f }
        val haloD = Paint(dp).apply { color = 0xFF000000.toInt(); maskFilter = blur }
        val outline = style.fill == ClockFill.OUTLINE
        val fillT = Paint(tp).apply {
            color = 0xFF000000.toInt()
            if (outline) { this.style = Paint.Style.STROKE; strokeWidth = max(1.5f, size * 0.022f) }
        }
        val fillD = Paint(dp).apply { color = 0xFF000000.toInt() }
        // Character by character, at the same advances the layout measured.
        var digit = 0
        lines.time.forEachIndexed { i, s ->
            val o = l.origins[i]; var x = o.x
            for (ch in s) {
                val t = ch.toString()
                cv[if (ch.isDigit()) 1 + digit % 2 else 3].drawText(t, x, o.y, haloT)
                cv[0].drawText(t, x, o.y, fillT)
                if (ch.isDigit()) digit++
                x += tp.measureText(t)
            }
        }
        lines.date?.let { s -> l.dateOrigin?.let { o -> cv[3].drawText(s, o.x, o.y, haloD); cv[0].drawText(s, o.x, o.y, fillD) } }
        val px = pack(layers)
        val W = bw.toFloat(); val H = bh.toFloat()
        fun norm(r: RectF) = RectF(r.left / W, r.top / H, r.right / W, r.bottom / H)
        val db = l.digitBoxes
        val stacked = style.layout == ClockLayout.STACKED
        val glow = l.pad * 1.1f
        // Columns for "Bám chủ thể": down to the glow under the digits, but never into a date below them.
        val raw = columnsOf(db, stacked).first
        val cols = raw.mapIndexed { i, col ->
            var bottom = col.bottom + glow
            l.dateBox?.let { d -> if (d.top >= col.bottom - 1f && d.left < col.right && d.right > col.left) bottom = min(bottom, (col.bottom + d.top) / 2f) }
            // Wide enough to take the glow either side (a shrunk column cut its own shadow off in a straight line),
            // meeting the neighbour halfway when they are close.
            // Never into the colon: it is its own piece, shrinking with the shorter digit beside it (split between
            // two columns its halves shrank differently and came apart).
            // Between two columns (the gap, and the colon in it) the shader blends their heights, so inner sides
            // stay at the ink; outer sides reach past the glow.
            val left = if (i == 0) col.left - glow * 1.6f else col.left
            val right = if (i == raw.lastIndex) col.right + glow * 1.6f else col.right
            norm(RectF(left, col.top, right, bottom))
        }
        // The date and the colon's own boxes, for the layout change (empty rect = none).
        val cores = listOf(l.dateBox?.let { norm(it) } ?: RectF(), l.colonBox?.takeIf { !stacked }?.let { norm(it) } ?: RectF())
        return Rendered(px, bw, bh, cols, cores, db.map { norm(it) })
    }
}

/**
 * Where the clock fits a picture: the subject's coverage of the screen on a coarse grid (computed once per
 * picture), then a search for the biggest clock whose digits stay readable.
 */
object ClockPlace {
    const val GW = 96
    const val MAX_COVER = 0.35f

    class Coverage(val w: Int, val h: Int, val a: FloatArray)

    /** The subject on the screen at rest (no parallax), or null if the picture has none. */
    fun coverage(img: WallImage, depthOn: Boolean, screenAspect: Float): Coverage? {
        val external = img.hasSubject
        val file = when {
            external -> Repo.subjectFile(img.id)
            depthOn && img.hasManualMask -> Repo.manualMaskFile(img.id)
            depthOn && img.hasMask -> Repo.maskFile(img.id)
            else -> return null
        }
        if (!file.exists()) return null
        val bmp = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            var s = 1
            while (max(o.outWidth, o.outHeight) / (s * 2) >= 192) s *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
        }.getOrNull() ?: return null
        val bw = bmp.width; val bh = bmp.height
        val px = IntArray(bw * bh); bmp.getPixels(px, 0, bw, 0, 0, bw, bh); bmp.recycle()
        fun alpha(u: Float, v: Float): Float {
            if (u < 0f || v < 0f || u >= 1f || v >= 1f) return 0f
            val p = px[(v * bh).toInt() * bw + (u * bw).toInt()]
            return if (external) (p ushr 24) / 255f else ((p shr 16) and 0xFF) / 255f
        }
        val gw = GW; val gh = (GW / screenAspect).roundToInt()
        val out = FloatArray(gw * gh)
        if (external) {
            val t = img.edit.subject
            val (bW, bH) = SubjectTransform.baseSize(img.subjectAspect, screenAspect)
            // Screen fractions; rotation is done in a square space (x scaled by the screen aspect).
            val sw = bW * t.scale; val sh = bH * t.scale
            val th = Math.toRadians(t.rotation.toDouble()); val c = kotlin.math.cos(th).toFloat(); val s = kotlin.math.sin(th).toFloat()
            val cx = 0.5f + t.offsetX; val cy = 0.5f + t.offsetY
            for (y in 0 until gh) for (x in 0 until gw) {
                val dx = ((x + 0.5f) / gw - cx) * screenAspect; val dy = (y + 0.5f) / gh - cy
                var u = (c * dx + s * dy) / (sw * screenAspect) + 0.5f
                var v = (-s * dx + c * dy) / sh + 0.5f
                if (t.flipHorizontal) u = 1f - u
                if (t.flipVertical) v = 1f - v
                out[y * gw + x] = alpha(u, v)
            }
        } else {
            val r = Grading.fitCrop(img.edit, img.width.toFloat() / img.height, screenAspect)
            for (y in 0 until gh) for (x in 0 until gw) out[y * gw + x] = alpha(r[0] + r[2] * (x + 0.5f) / gw, r[1] + r[3] * (y + 0.5f) / gh)
        }
        return Coverage(gw, gh, out)
    }

    /** Largest share of any digit hidden by the subject. */
    fun cover(cov: Coverage?, p: ClockPlacement, shape: ClockText.Shape, screenAspect: Float): Float {
        if (cov == null) return 0f
        val bh = p.h; val bw = bwOf(p, shape, screenAspect)
        var worst = 0f
        for ((i, d) in shape.digits.withIndex()) {
            val col = shape.columns[shape.digitColumn[i]]
            val f = p.sq.getOrElse(shape.digitColumn[i]) { 1f }
            // A shrunk column keeps its top; its digits move up and get shorter.
            val dt = col.top + (d.top - col.top) * f; val db = col.top + (d.bottom - col.top) * f
            val l = p.cx - bw / 2 + hxMap(d.left, shape.time, p) * bw; val r = p.cx - bw / 2 + hxMap(d.right, shape.time, p) * bw
            val t = p.cy - bh / 2 + dt * bh; val b = p.cy - bh / 2 + db * bh
            val x0 = (l * cov.w).toInt().coerceIn(0, cov.w - 1); val x1 = (r * cov.w).toInt().coerceIn(0, cov.w - 1)
            val y0 = (t * cov.h).toInt().coerceIn(0, cov.h - 1); val y1 = (b * cov.h).toInt().coerceIn(0, cov.h - 1)
            var hit = 0; var n = 0
            for (y in y0..y1) for (x in x0..x1) { n++; if (cov.a[y * cov.w + x] > 0.5f) hit++ }
            if (n > 0) worst = max(worst, hit.toFloat() / n)
        }
        return worst
    }

    /**
     * "Bám chủ thể": the biggest clock centred at [p] that stays on screen and keeps every digit readable;
     * it shrinks as it nears the subject and grows back in open space. Cheap (coarse grid), fine per drag step.
     * Never below 10% of the screen: deep inside the subject it keeps its size instead of collapsing.
     */
    /** Block x after the horizontal squeeze. */
    fun hxMap(x: Float, time: RectF, p: ClockPlacement): Float =
        if (p.hx >= 0.999f) x else if (!p.hxRight) time.left + (x - time.left) * p.hx else time.right - (time.right - x) * p.hx

    private const val MIN_HX = 0.5f

    /**
     * "Bám chủ thể": the best of shrinking digit columns from below and squeezing the whole time sideways
     * (away from a subject beside it, then shrinking from below), always keeping a gap. Score = area kept.
     */
    fun hug(cov: Coverage?, p0: ClockPlacement, shape: ClockText.Shape, screenAspect: Float): ClockPlacement {
        val p = p0.copy(hx = 1f, hxRight = false)
        if (cov == null) return p.copy(sq = emptyList())
        var best = p.copy(sq = squash(cov, p, shape, screenAspect))
        var bestScore = best.sq.average().toFloat()
        val bh = p.h; val bw = bwOf(p, shape, screenAspect)
        val left0 = p.cx - bw / 2; val top0 = p.cy - bh / 2
        val L = left0 + shape.time.left * bw; val R = left0 + shape.time.right * bw
        val y0 = ((top0 + shape.time.top * bh) * cov.h).toInt().coerceIn(0, cov.h - 1)
        val y1 = ((top0 + shape.time.bottom * bh + PAD_Y) * cov.h).toInt().coerceIn(0, cov.h - 1)
        val x0 = ((L - PAD_X) * cov.w).toInt().coerceIn(0, cov.w - 1); val x1 = ((R + PAD_X) * cov.w).toInt().coerceIn(0, cov.w - 1)
        var minX = 2f; var maxX = -1f
        for (y in y0..y1) for (x in x0..x1) if (cov.a[y * cov.w + x] > 0.5f) {
            val fx = (x + 0.5f) / cov.w
            if (fx < minX) minX = fx
            if (fx > maxX) maxX = fx
        }
        if (maxX >= 0f) for ((right, free) in listOf(false to (minX - 1f / cov.w - PAD_X - L), true to (R - maxX - 1f / cov.w - PAD_X))) {
            val f = free / (R - L)
            if (f < MIN_HX || f >= 0.999f) continue
            val q = p.copy(hx = f, hxRight = right)
            val s = squash(cov, q, shape, screenAspect)
            val score = f * s.average().toFloat()
            if (score > bestScore) { bestScore = score; best = q.copy(sq = s) }
        }
        return best
    }

    const val MIN_SQUASH = 0.5f
    /** Gap kept between the digits and the subject, an invisible wall (fraction of the screen height / width). */
    private const val PAD_Y = 0.022f
    private const val PAD_X = 0.011f
    /** Behind the subject with "Bám chủ thể", digits may not go under it at all (allowing for the coarse grid). */
    const val WALL = 0.02f

    /** The whole clock inside the screen (moving or resizing could push part of it off an edge). */
    fun onScreen(p0: ClockPlacement, shape: ClockText.Shape, screenAspect: Float): ClockPlacement {
        // Never wider than the screen (a big clock, or a wide user font): smaller instead, so its frame and
        // side handles always stay inside the preview.
        val w0 = bwOf(p0, shape, screenAspect)
        val p = if (w0 > MAX_W) p0.copy(h = p0.h * MAX_W / w0) else p0
        val bw = min(bwOf(p, shape, screenAspect), 1f)
        val hw = bw / 2f; val hh = min(p.h, 1f) / 2f
        return p.copy(cx = p.cx.coerceIn(hw, 1f - hw), cy = p.cy.coerceIn(hh, 1f - hh))
    }
    private const val MAX_W = 0.98f

    /** Block width on screen (fraction of the screen width). */
    fun bwOf(p: ClockPlacement, shape: ClockText.Shape, screenAspect: Float) = p.h * shape.aspect * p.sx / screenAspect


    /**
     * Each digit column stops a fixed gap above the subject below it, keeping its top
     * and width; columns with nothing below keep their full height. Never below [MIN_SQUASH].
     */
    fun squash(cov: Coverage?, p: ClockPlacement, shape: ClockText.Shape, screenAspect: Float): List<Float> {
        if (cov == null) return emptyList()
        val bh = p.h; val bw = bwOf(p, shape, screenAspect)
        val left0 = p.cx - bw / 2; val top0 = p.cy - bh / 2
        return shape.columns.map { c ->
            val x0 = ((left0 + hxMap(c.left, shape.time, p) * bw - PAD_X) * cov.w).toInt().coerceIn(0, cov.w - 1)
            val x1 = ((left0 + hxMap(c.right, shape.time, p) * bw + PAD_X) * cov.w).toInt().coerceIn(0, cov.w - 1)
            val t = top0 + c.top * bh; val b = top0 + c.bottom * bh
            val y0 = (t * cov.h).toInt().coerceIn(0, cov.h - 1)
            val y1 = ((b + PAD_Y) * cov.h).toInt().coerceIn(0, cov.h - 1)
            var hit = -1f
            loop@ for (y in y0..y1) for (x in x0..x1) if (cov.a[y * cov.w + x] > 0.5f) { hit = y.toFloat() / cov.h; break@loop }
            if (hit < 0f) 1f else ((hit - PAD_Y - t) / (b - t)).coerceIn(MIN_SQUASH, 1f)
        }
    }

    /** The default spot when a picture has no subject. */
    fun fallback(style: ClockStyle): ClockPlacement =
        if (style.layout == ClockLayout.STACKED || style.fit == ClockFit.BIG) ClockPlacement(0.5f, 0.36f, 0.44f)
        else ClockPlacement(0.5f, 0.2f, 0.17f)

    /**
     * Biggest readable clock. "Sau đầu" prefers one the subject's head just overlaps, high on the screen;
     * "Phủ lớn" lets a big clock fill the middle with the subject standing in front.
     */
    fun fit(cov: Coverage?, style: ClockStyle, shape: ClockText.Shape, screenAspect: Float): ClockPlacement {
        if (cov == null) return fallback(style).let { it.copy(cover = 0f) }
        val big = style.fit == ClockFit.BIG || style.layout == ClockLayout.STACKED
        val heights = if (big) generateSequence(0.62f) { it - 0.03f }.takeWhile { it >= if (style.hug) 0.14f else 0.26f }
            else generateSequence(if (style.hug) 0.36f else 0.26f) { it - 0.015f }.takeWhile { it >= 0.09f }
        val limit = if (style.hug) WALL else MAX_COVER
        var best: ClockPlacement? = null; var bestScore = -1f
        for (h in heights) {
            val w = h * shape.aspect / screenAspect
            if (w > 0.94f) continue
            for (cx in floatArrayOf(0.5f, 0.42f, 0.58f)) {
                if (cx - w / 2 < 0.03f || cx + w / 2 > 0.97f) continue
                var top = 0.06f
                while (top + h <= 0.8f) {
                    var p = ClockPlacement(cx, top + h / 2, h)
                    if (style.hug) p = hug(cov, p, shape, screenAspect)
                    val c = cover(cov, p, shape, screenAspect)
                    if (c <= limit) {
                        // Bigger wins; a little overlap reads as "behind the subject"; centred and high is calmer.
                        val overlap = if (c > 0.08f || p.sq.any { it < 0.98f } || p.hx < 0.98f) 0.06f else 0f
                        val score = if (style.hug) {
                            // Hugging: the most digit area that stays visible, nestled right against the subject
                            // (digits shrinking around it), like a clock dragged into place by hand.
                            val kept = p.hx * (if (p.sq.isEmpty()) 1f else p.sq.average().toFloat())
                            val touch = if (p.sq.any { it < 0.97f } || p.hx < 0.97f) 0.05f else 0f
                            h * kept + touch - abs(cx - 0.5f) * 0.2f - (if (big) abs(top + h / 2 - 0.42f) else top) * 0.04f
                        } else h + overlap - abs(cx - 0.5f) * 0.3f - (if (big) abs(top + h / 2 - 0.42f) else top) * 0.12f
                        if (score > bestScore) { bestScore = score; best = p.copy(cover = c) }
                    }
                    top += 0.012f
                }
            }
            if (best != null && !big && !style.hug) break   // the largest size that fits is enough
        }
        // No room behind the subject: the clock goes on top for this picture.
        return best ?: fallback(style).copy(forcedTop = true)
    }
}
