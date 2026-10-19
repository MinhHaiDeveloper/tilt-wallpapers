package com.tilt.wallpapers.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30.*
import android.opengl.GLUtils
import android.util.Log
import com.tilt.wallpapers.data.AppState
import com.tilt.wallpapers.data.Direction
import com.tilt.wallpapers.data.Effect
import com.tilt.wallpapers.data.Grading
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.Settings
import com.tilt.wallpapers.data.Transition
import com.tilt.wallpapers.data.WallImage
import com.tilt.wallpapers.tilt.TiltNavigator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.pow

/**
 * Draws the active set: tilt picks the image, transitions animate between them, and the glass
 * effect / parallax / depth layers react to the smoothed tilt. Used by the live wallpaper and by
 * every in-app preview. Public methods are thread-safe; GL work happens on [host]'s thread.
 */
class TiltRenderer(private val maxTexture: Int) : GlScene {

    lateinit var host: GlThread

    // ---------------------------------------------------------------- caller side
    private val nav = TiltNavigator()
    @Volatile private var settings = Settings()
    @Volatile private var rollTarget = 0f
    @Volatile private var pitch = 0f

    /** Shows [setId] (or the active set) from [state]. */
    /** Conditions outside the app's settings that change how the wallpaper behaves. */
    data class Env(val lowPower: Boolean = false, val reduceMotion: Boolean = false, val dim: Float = 0f)
    @Volatile private var env = Env()
    @Volatile private var lastState: AppState? = null
    @Volatile private var lastSetId: String? = null

    fun setEnv(e: Env) {
        if (e == env) return
        env = e
        host.minFrameNanos = if (e.lowPower) 33_000_000L else 0L
        host.post { dimTarget = e.dim }
        lastState?.let { setState(it, lastSetId) }
        host.requestRender()
    }

    /** Settings as they apply now: battery saver and "remove animations" turn motion down. */
    private fun effective(st: Settings): Settings {
        var s = st
        if (env.lowPower || env.reduceMotion) s = s.copy(parallax = false, rotateSubject = false)
        if (env.reduceMotion) s = s.copy(transition = Transition.CROSSFADE, durationMs = minOf(s.durationMs, 250), followTilt = false)
        return s
    }

    fun setState(state: AppState, setId: String? = null) {
        lastState = state; lastSetId = setId
        val set = setId?.let { id -> state.sets.find { it.id == id } } ?: state.activeSet
        val (seq, neutral) = set?.tiltSequence() ?: (emptyList<WallImage>() to 0)
        val st = effective(state.settings)
        // Unlocking re-reads the current angle inside configure(), so the picture glides to where the phone points.
        synchronized(nav) { nav.locked = st.tiltLock; nav.configure(seq.size, neutral, st.tiltMode, st.tiltRange) }
        val target = nav.index
        settings = st
        clockStyle = set?.clock ?: com.tilt.wallpapers.data.ClockStyle()
        textLayer = set?.texts ?: com.tilt.wallpapers.data.TextLayer()
        clockShowOn = state.settings.clockShowOn
        clockAuto = Repo.centerImage(state.copy(activeSetId = set?.id ?: state.activeSetId))?.palette?.firstOrNull()?.let { lightTint(it) } ?: 0xFFF2F2F2.toInt()
        host.post { neutralIdx = neutral; applyConfig(seq, st, target) }
        host.requestRender()
    }

    // ---------------------------------------------------------------- clock layer
    @Volatile private var clockStyle = com.tilt.wallpapers.data.ClockStyle()
    @Volatile private var textLayer = com.tilt.wallpapers.data.TextLayer()
    private val textGl by lazy { TextGl(host, io) { b, w, h -> uploadRgba(b, w, h) } }
    @Volatile private var clockShowOn = com.tilt.wallpapers.data.ClockShowOn.HOME
    @Volatile private var clockAuto = 0xFFF2F2F2.toInt()
    /** The live wallpaper (previews always show the clock); [locked] = the lock screen is up. */
    @Volatile var isWallpaper = false
    @Volatile private var locked = false
    fun setLocked(l: Boolean) { if (l != locked) { locked = l; host.requestRender() } }

    // One texture per layout (pictures may pick their own), with its digit columns and time box.
    private val clockTex = IntArray(2)
    private val clockAspect = floatArrayOf(1f, 1f)
    private val colEdges = Array(2) { FloatArray(20) { if (it < 8) 2f else 0f } }   // L4 R4 T4 B4, time (l, t, r, b)
    private val clockKeys = arrayOfNulls<String>(2)
    private val digitBoxes = Array(2) { FloatArray(16) { 2f } }   // each digit's cell (l, t, r, b) per layout
    private val cores = Array(2) { FloatArray(8) }      // date, colon (l, t, r, b; l = 2 when none); per layout
    private val clockBuilding = BooleanArray(2)
    private val sqNow = FloatArray(5) { 1f }   // 4 columns + width, eased like rubber
    private val sqA = FloatArray(5); private val sqB = FloatArray(5)
    private var clockVis = -1f
    private var clockTimerFor = -1L

    /** A light, readable tint of [c] (the set's main colour) for "auto" clock colour. */
    private fun lightTint(c: Int): Int {
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c, hsv)
        hsv[1] = minOf(hsv[1], 0.28f); hsv[2] = 0.97f
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun clockVisible(): Boolean = (clockStyle.enabled || textLayer.items.any { it.text.isNotBlank() }) && (!isWallpaper || when (clockShowOn) {
        com.tilt.wallpapers.data.ClockShowOn.HOME -> !locked
        com.tilt.wallpapers.data.ClockShowOn.LOCK -> locked
        com.tilt.wallpapers.data.ClockShowOn.BOTH -> true
    })

    private fun layoutOf(img: WallImage) = img.clock?.layout ?: clockStyle.layout

    /** Placement of the clock on [img], as a screen box (x, y, w, h). */
    private fun clockBox(img: WallImage, out: FloatArray, at: Int) {
        val l = layoutOf(img)
        val p = img.clock ?: com.tilt.wallpapers.data.ClockPlace.fallback(clockStyle.copy(layout = l))
        val h = p.h * typeZoom; val w = h * clockAspect[l.ordinal] * p.sx * height / width
        out[at] = p.cx - w / 2; out[at + 1] = p.cy - h / 2; out[at + 2] = w; out[at + 3] = h
    }
    private val clk = FloatArray(8)

    /** Keeps the clock textures current (style, minute, language, size); re-rendered off the GL thread. */
    private fun ensureClock(): Boolean {
        val style = clockStyle
        if (!style.enabled) return false
        val now = System.currentTimeMillis()
        val minute = now / 60_000L
        // Wake for the next minute even when nothing else moves.
        if (clockTimerFor != minute) {
            clockTimerFor = minute
            host.postDelayed(60_000L - now % 60_000L + 30L) { host.requestRender() }
        }
        val lang = com.tilt.wallpapers.data.I18n.lang
        // Every texture was found current for exactly this: skip the checks below (they built strings and read
        // the 24-hour setting every frame). A new minute re-checks everything.
        if (style === settledStyle && seq === settledSeq && minute == settledMinute && height == settledHeight && lang == settledLang) return true
        var pending = false
        val ctx = Repo.context
        val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
        val needed = HashSet<com.tilt.wallpapers.data.ClockLayout>().apply { add(style.layout); seq.forEach { add(layoutOf(it)) } }
        for (layout in needed) {
            val li = layout.ordinal
            val ls = style.copy(layout = layout)
            val maxH = seq.filter { layoutOf(it) == layout }.maxOfOrNull { (it.clock ?: com.tilt.wallpapers.data.ClockPlace.fallback(ls)).h } ?: 0.2f
            val px = ((maxH * height / 64f).toInt() + 1).coerceIn(2, 36) * 64
            // Only what changes the glyphs: colours, opacity and shadow are applied when drawing (a colour slider
            // used to redraw the texture on every step).
            val key = "${ls.font}|${ls.customFont}|${ls.weight}|${ls.layout}|${ls.date}|${ls.fill}|$minute|${com.tilt.wallpapers.data.I18n.lang}|$is24|$px"
            if (key == clockKeys[li]) continue
            pending = true
            if (clockBuilding[li]) continue
            clockBuilding[li] = true
            io.execute {
                val out = runCatching {
                    com.tilt.wallpapers.data.ClockText.render(ctx, ls, com.tilt.wallpapers.data.ClockText.lines(ls, java.util.Date(now), is24), px)
                }.getOrNull()
                host.post {
                    clockBuilding[li] = false
                    if (out != null) {
                        if (clockTex[li] != 0) glDeleteTextures(1, intArrayOf(clockTex[li]), 0)
                        clockTex[li] = uploadRgba(out.pixels, out.width, out.height)
                        clockAspect[li] = out.width.toFloat() / out.height
                        val e = colEdges[li]
                        for (k in 0..3) {
                            val c = out.columns.getOrNull(k)
                            e[k] = c?.left ?: 2f; e[4 + k] = c?.right ?: 2f; e[8 + k] = c?.top ?: 0f; e[12 + k] = c?.bottom ?: 0f
                        }
                        e[16] = out.time.left; e[17] = out.time.top; e[18] = out.time.right; e[19] = out.time.bottom
                        val dg = digitBoxes[li]
                        for (k in 0..3) {
                            val r = out.digits.getOrNull(k)
                            dg[k * 4] = r?.left ?: 2f; dg[k * 4 + 1] = r?.top ?: 2f; dg[k * 4 + 2] = r?.right ?: 2f; dg[k * 4 + 3] = r?.bottom ?: 2f
                        }
                        val co = cores[li]
                        for (k in 0..1) {
                            val r = out.cores.getOrNull(k)?.takeIf { it.right > it.left }
                            co[k * 4] = r?.left ?: 2f; co[k * 4 + 1] = r?.top ?: 2f; co[k * 4 + 2] = r?.right ?: 2f; co[k * 4 + 3] = r?.bottom ?: 2f
                        }
                        clockKeys[li] = key
                    }
                    host.requestRender()
                }
            }
        }
        if (!pending) { settledStyle = style; settledSeq = seq; settledMinute = minute; settledHeight = height; settledLang = lang }
        return clockTex[style.layout.ordinal] != 0 || clockTex.any { it != 0 }
    }
    private var settledStyle: com.tilt.wallpapers.data.ClockStyle? = null
    private var settledSeq: List<WallImage>? = null
    private var settledMinute = -1L
    private var settledHeight = -1
    private var settledLang: Any? = null

    private fun shapeOf(img: WallImage, out: FloatArray) {
        val p = img.clock
        for (k in 0..3) out[k] = p?.sq?.getOrNull(k) ?: 1f
        out[4] = p?.hx ?: 1f
    }

    /** Per-image uniform names, built once (not every frame). */
    private class Names(s: String) {
        val colB = "uColB$s"
        val colL = "uColL$s"
        val colR = "uColR$s"
        val colT = "uColT$s"
        val fx = "uFx$s"
        val grade = "uGrade$s"
        val hx = "uHx$s"
        val look = "uLook$s"
        val mask = "uMask$s"
        val rect = "uRect$s"
        val sh = "uSh$s"
        val shadow = "uShadow$s"
        val sq = "uSq$s"
        val sub0 = "uSub${s}0"
        val sub1 = "uSub${s}1"
        val tex = "uTex$s"
        val time = "uTime$s"
    }
    private val namesA = Names("A"); private val namesB = Names("B")
    private val gradNames = Array(3) { "uGrad$it" }

    private fun bindColumns(p: Program, s: String, li: Int, sq: FloatArray, right: Boolean) {
        val n = if (s == "A") namesA else namesB
        val e = colEdges[li]
        glUniform4f(p.u(n.colL), e[0], e[1], e[2], e[3]); glUniform4f(p.u(n.colR), e[4], e[5], e[6], e[7])
        glUniform4f(p.u(n.colT), e[8], e[9], e[10], e[11]); glUniform4f(p.u(n.colB), e[12], e[13], e[14], e[15])
        glUniform4f(p.u(n.time), e[16], e[17], e[18], e[19])
        glUniform4f(p.u(n.sq), sq[0], sq[1], sq[2], sq[3])
        glUniform2f(p.u(n.hx), sq[4], if (right) 1f else 0f)
    }

    private fun bindClock(p: Program, a: WallImage, b: WallImage?, progress: Float) {
        val st = clockStyle
        val bb = b ?: a
        val la = layoutOf(a).ordinal; val lb = layoutOf(bb).ordinal
        clockBox(a, clk, 0); clockBox(bb, clk, 4)
        // Stays on the screen: glide from this picture's spot and size to the next one's. Between layouts both
        // clocks move together (each keeps its own shape) and cross-fade.
        val still = !st.followImage && b != null && la == lb
        val morph = !st.followImage && b != null && la != lb
        if (still) for (i in 0..3) { val v = clk[i] + (clk[4 + i] - clk[i]) * progress; clk[i] = v; clk[4 + i] = v }
        glUniform1f(p.u("uClockMix"), if (morph) progress else -1f)
        glUniform2f(p.u("uStk"), la.toFloat(), lb.toFloat())
        glUniform2f(p.u("uCkAsp"), clockAspect[la], clockAspect[lb])
        glUniform4fv(p.u("uDigA"), 4, digitBoxes[la], 0)
        glUniform4fv(p.u("uDigB"), 4, digitBoxes[lb], 0)
        glUniform2f(p.u("uTop"), if (a.clock?.topOn(st) == true) 1f else 0f, if (bb.clock?.topOn(st) == true) 1f else 0f)
        if (morph) {
            glUniform4fv(p.u("uCoreA"), 2, cores[la], 0)
            glUniform4fv(p.u("uCoreB"), 2, cores[lb], 0)
            glUniform2f(p.u("uLay"), la.toFloat(), lb.toFloat())
        }
        val texA = clockTex[la].takeIf { it != 0 } ?: clockTex.first { it != 0 }
        val texB = clockTex[lb].takeIf { it != 0 } ?: clockTex.first { it != 0 }
        glActiveTexture(GL_TEXTURE6); glBindTexture(GL_TEXTURE_2D, texA); glUniform1i(p.u("uClockA"), 6)
        glActiveTexture(GL_TEXTURE7); glBindTexture(GL_TEXTURE_2D, texB); glUniform1i(p.u("uClockB"), 7)
        glUniform4f(p.u("uClkA"), clk[0], clk[1], clk[2], clk[3])
        glUniform4f(p.u("uClkB"), clk[4], clk[5], clk[6], clk[7])
        // Digit columns and width ease like rubber toward where the subject leaves room.
        shapeOf(a, sqA); shapeOf(bb, sqB)
        val rightA = a.clock?.hxRight == true; val rightB = bb.clock?.hxRight == true
        if (b == null || still) {
            var moving = false
            for (i in 0..4) {
                val target = sqA[i] + (sqB[i] - sqA[i]) * (if (b != null) progress else 0f)
                sqNow[i] += (target - sqNow[i]) * 0.25f
                if (abs(target - sqNow[i]) > 0.002f) moving = true else sqNow[i] = target
            }
            if (moving) host.requestRender()
            val right = if (b != null && progress > 0.5f) rightB else rightA
            bindColumns(p, "A", la, sqNow, right); bindColumns(p, "B", lb, sqNow, right)
        } else {
            // Arriving already shaped for the new picture after a layout change: no jump afterwards.
            // Scrubbed by the tilt, the pair can end on either picture: take the one it is nearer to (always the
            // second one let a return to the first ease in from the wrong shape, a jolt after landing).
            (if (progress >= 0.5f) sqB else sqA).copyInto(sqNow)
            bindColumns(p, "A", la, sqA, rightA); bindColumns(p, "B", lb, sqB, rightB)
        }
        val c = if (st.color != 0) st.color else clockAuto
        glUniform4f(
            p.u("uClockColor"),
            android.graphics.Color.red(c) / 255f, android.graphics.Color.green(c) / 255f, android.graphics.Color.blue(c) / 255f,
            st.opacity * clockVis.coerceIn(0f, 1f),
        )
        val g = st.gradient
        glUniform4f(p.u("uClockFx"), st.fill.ordinal.toFloat(), st.seeThrough, if (st.shadow) st.shadowAmt * 2.5f else 0f, if (g.size >= 2) 1f else 0f)
        if (g.size >= 2) {
            // "Theo ảnh": the colours of the picture on screen, blending into the next one's as it comes in.
            fun cols(img: WallImage): List<Int> = if (st.gradientAuto && img.palette.isNotEmpty()) List(3) { img.palette.getOrElse(it) { img.palette.last() } }
                else List(3) { g.getOrElse(it) { g.last() } }
            val ca = cols(a); val cb = cols(bb); val t = if (b != null) progress else 0f
            for (k in 0..2) {
                val x = ca[k]; val y = cb[k]
                glUniform3f(
                    p.u(gradNames[k]),
                    (android.graphics.Color.red(x) + (android.graphics.Color.red(y) - android.graphics.Color.red(x)) * t) / 255f,
                    (android.graphics.Color.green(x) + (android.graphics.Color.green(y) - android.graphics.Color.green(x)) * t) / 255f,
                    (android.graphics.Color.blue(x) + (android.graphics.Color.blue(y) - android.graphics.Color.blue(x)) * t) / 255f,
                )
            }
        }
    }

    /** Drift and "Khi chuyển ảnh" of the clock, shared by the free texts. */
    private fun bindTypeMisc(p: Program) {
        val st = clockStyle
        val amp = if (env.lowPower || env.reduceMotion) 0f else st.depth * 0.05f
        glUniform3f(p.u("uClockMisc"), -tiltX * amp, -tiltY * amp * 0.7f, if (st.followImage) 1f else 0f)
    }

    /** Sideways angle currently treated as level by "Tự cân bằng" (degrees, after the manual offset). */
    @Volatile private var autoBase = 0f
    private var heldSince = 0L
    private var heldRef = 0f
    private var leveling = false
    private var lastTiltNanos = 0L

    /**
     * Calibrated roll: minus the user's "level" offset, and minus an automatic baseline that follows the
     * phone only when it is held far beyond any tilt the zones use (lying on one side) for 4 seconds.
     * Normal tilting to look at the side pictures never moves it.
     */
    private fun calibrated(rawRoll: Float): Float {
        val st = settings
        val rel = rawRoll - st.tiltOffset
        val now = System.nanoTime()
        val dt = if (lastTiltNanos == 0L) 0.02f else ((now - lastTiltNanos) / 1e9f).coerceIn(0f, 0.2f)
        lastTiltNanos = now
        if (!st.tiltAutoLevel) { autoBase = 0f; leveling = false; return rel }
        val dev = rel - autoBase
        val far = max(st.tiltRange * 1.25f, 30f)
        if (leveling) {
            autoBase += (rel - autoBase) * (1f - exp(-dt / 0.5f))
            if (abs(rel - autoBase) < 1f) leveling = false
        } else if (abs(dev) > far) {
            if (heldSince == 0L || abs(rel - heldRef) > 8f) { heldSince = now; heldRef = rel }
            if (now - heldSince > 4_000_000_000L) { leveling = true; heldSince = 0L }
        } else heldSince = 0L
        return rel - autoBase
    }

    fun onTilt(roll: Float, pitch: Float) {
        val c = calibrated(roll)
        val r = if (settings.invertTilt) -c else c
        rollTarget = r
        this.pitch = pitch
        val step = synchronized(nav) { nav.update(r) }
        step?.let { (index, dir) -> host.post { goTo(index, dir) } }
        // Only when this reading moves something on screen (the same thresholds the frame loop settles at): a
        // phone lying still or barely moving no longer redraws at the sensor's rate.
        val tx = (r / 28f).coerceIn(-1f, 1f)
        val pb = pitchBase
        if (step != null || pb.isNaN() || abs(tx - tiltX) > 0.0015f || abs(((pitch - pb) / 22f).coerceIn(-1f, 1f) - tiltY) > 0.0015f ||
            (settings.parallax && abs(pitch - pb) > 0.5f) || (abs(r - rollNow) > 0.05f && follow())) host.requestRender()
    }

    /** Steps to the neighbouring image (preview taps). */
    fun step(delta: Int) {
        synchronized(nav) { nav.step(delta) }?.let { (index, dir) -> host.post { goTo(index, dir) } }
        host.requestRender()
    }

    /** Jump without animation to whatever the tilt currently selects (e.g. screen turned on). */
    fun snap() = host.post {
        val img = seq.getOrNull(nav.index) ?: return@post
        animating = false; queued = null; shown = img
        host.requestRender()
    }

    // ---------------------------------------------------------------- GL side state
    private var seq: List<WallImage> = emptyList()
    private var cfg = Settings()
    private var width = 1
    private var height = 1

    private var shown: WallImage? = null
    private var from: WallImage? = null
    private var to: WallImage? = null
    private var animating = false
    /** The running transition is heading back to [from] (tilted back mid-way): t runs down to 0. */
    private var backward = false
    private var t = 0f
    private var speed = 1f
    private val dir = FloatArray(2)
    private var queued: Pair<Int, Int>? = null

    private var dimTarget = 0f
    private var dimNow = 0f
    // Read by onTilt on the sensor thread to skip readings that would not move anything.
    @Volatile private var tiltX = 0f
    @Volatile private var tiltY = 0f
    /** Smoothed calibrated roll in degrees, for "Theo tay". */
    @Volatile private var rollNow = 0f
    /** Offscreen effect thumbnails: no [host], no clock or texts. */
    private var stills = false

    // Unlock motion in progress (0 = none): background zoom, subject zoom, background blur, clock / text scale.
    private var unlockStart = 0L
    private var unlockBg = 0f; private var unlockFg = 0f; private var unlockBlur = 0f; private var typeZoom = 1f

    /** Plays the screen-on / unlock motion; [force] replays it even if it just ran (settings preview). */
    fun playUnlock(force: Boolean = false) = host.post {
        val now = System.nanoTime()
        if (!force && now - lastUnlock < 1_500_000_000L) return@post
        // The clock starts at the first frame actually drawn: the display is still waking up when this comes,
        // and counting from here let the first part (the blur) pass unseen, so it looked like a pop.
        lastUnlock = now; unlockStart = -1L
        host.requestRender()
    }
    private var lastUnlock = 0L

    /** Plays the transition between the middle picture and its neighbour back and forth (settings preview). */
    @Volatile var demo = false
    private var demoStart = 0L
    private var neutralIdx = 0
    @Volatile private var pitchBase = Float.NaN
    private var lastFrame = 0L

    /** [mask] holds the subject: an AI/manual mask (grayscale) or, when [rgba], an external cut-out. */
    private class Tex(
        val id: Int, val w: Int, val h: Int, val mask: Int = 0, val mw: Int = 1, val mh: Int = 1, val rgba: Boolean = false,
        /** For an external subject: its transparent margin inside the texture, as a fraction of the texture. */
        val padU: Float = 0f, val padV: Float = 0f,
        /** Low-res alpha the drop shadow is blurred from (covers the subject texture's UV space). */
        val shadowSrc: ShadowSource? = null,
    ) {
        var shadow = 0
        var shadowSoft = -1f
        var shadowBusy = false
        var deleted = false
    }
    private val textures = LinkedHashMap<String, Tex>(8, 0.75f, true)
    private val loading = HashSet<String>()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "tilt-decode").apply { priority = Thread.NORM_PRIORITY - 1 } }

    private val programs = android.util.SparseArray<Program>()
    private var quad = 0
    private var blackTex = 0

    private fun applyConfig(newSeq: List<WallImage>, st: Settings, target: Int) {
        val oldShown = shown
        seq = newSeq; cfg = st
        keyCache.clear()
        // Refresh image objects so edits apply instantly, drop ones that were deleted.
        fun refresh(img: WallImage?) = img?.let { o -> newSeq.find { it.id == o.id } }
        shown = refresh(shown); from = refresh(from); to = refresh(to)
        if (animating && (from == null || to == null)) { animating = false; shown = to ?: from ?: shown }
        val targetImg = newSeq.getOrNull(target)
        when {
            targetImg == null -> { shown = null; animating = false }
            shown == null && oldShown == null -> shown = targetImg
            shown == null -> shown = targetImg
            !animating && shown!!.id != targetImg.id -> start(shown!!, targetImg, 1)
        }
        trimTextures()
    }

    private fun goTo(index: Int, direction: Int) {
        val img = seq.getOrNull(index) ?: return
        if (!animating) {
            val cur = shown ?: run { shown = img; return }
            if (cur.id != img.id) start(cur, img, direction)
        } else {
            val target = if (backward) from else to
            val other = if (backward) to else from
            when (img.id) {
                // Tilted back mid-way: run the same transition backwards, so nothing jumps.
                other?.id -> { backward = !backward; queued = null }
                target?.id -> queued = null
                else -> queued = index to direction
            }
        }
    }

    private fun start(a: WallImage, b: WallImage, direction: Int) {
        from = a; to = b; t = 0f; animating = true; backward = false
        speed = 1f
        setDir(direction)
    }

    private fun setDir(direction: Int) {
        val d = when (cfg.direction) {
            Direction.AUTO -> if (direction < 0) Direction.LEFT_TO_RIGHT else Direction.RIGHT_TO_LEFT
            else -> cfg.direction
        }
        when (d) {
            Direction.LEFT_TO_RIGHT -> { dir[0] = 1f; dir[1] = 0f }
            Direction.RIGHT_TO_LEFT -> { dir[0] = -1f; dir[1] = 0f }
            Direction.TOP_TO_BOTTOM -> { dir[0] = 0f; dir[1] = 1f }
            else -> { dir[0] = 0f; dir[1] = -1f }
        }
    }

    // ---------------------------------------------------------------- GlScene
    override fun onSurface(width: Int, height: Int) {
        this.width = max(1, width); this.height = max(1, height)
        if (quad == 0) initGl()
        glViewport(0, 0, this.width, this.height)
    }

    override fun onDraw(frameTimeNanos: Long): Boolean {
        if (quad == 0) initGl()
        val dt = if (lastFrame == 0L) 0.016f else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0f, 0.1f)
        lastFrame = frameTimeNanos
        var more = false

        // Critically-damped follow of the sensor: smooth at any refresh rate.
        // Smoothing 0..1 maps to a follow rate of 18..4.5 per second (9 at the default 0.5).
        val k = 1f - exp(-dt * 9f * 2f.pow(1f - 2f * cfg.smoothing))
        if (abs(dimTarget - dimNow) > 0.002f) {
            dimNow += (dimTarget - dimNow).coerceIn(-dt / 2f, dt / 2f)   // full range in ~2 s
            more = true
        } else dimNow = dimTarget
        val tx = (rollTarget / 28f).coerceIn(-1f, 1f)
        if (pitchBase.isNaN()) pitchBase = pitch
        pitchBase += (pitch - pitchBase) * (1f - exp(-dt * 0.35f))
        val ty = ((pitch - pitchBase) / 22f).coerceIn(-1f, 1f)
        tiltX += (tx - tiltX) * k
        tiltY += (ty - tiltY) * k
        rollNow += (rollTarget - rollNow) * k
        if (abs(rollTarget - rollNow) > 0.05f && follow()) more = true
        if (abs(tx - tiltX) > 0.0015f || abs(ty - tiltY) > 0.0015f) more = true
        if (abs(pitch - pitchBase) > 0.5f) more = more || cfg.parallax

        // The clock fades in / out with the lock screen (Hiện ở).
        val visTarget = if (clockVisible()) 1f else 0f
        if (clockVis < 0f) clockVis = visTarget
        if (clockVis != visTarget) {
            clockVis = if (clockVis < visTarget) minOf(visTarget, clockVis + dt / 0.35f) else maxOf(visTarget, clockVis - dt / 0.35f)
            more = true
        }
        if (unlockStart != 0L) {
            // Screen on / unlocked: the picture settles from a little zoomed and blurred, the subject first.
            if (unlockStart == -1L) unlockStart = frameTimeNanos + UNLOCK_HOLD
            // Held fully blurred for a moment first (the screen's own fade-in), then the motion.
            val u = ((frameTimeNanos - unlockStart) / (cfg.unlockSeconds.coerceIn(0.5f, 3f) * 1e9f)).coerceAtLeast(0f)
            val amt = if (env.lowPower || env.reduceMotion) 0f else cfg.unlockAnim.amount
            if (u >= 1f || amt == 0f) { unlockStart = 0L; unlockBg = 0f; unlockFg = 0f; unlockBlur = 0f; typeZoom = 1f }
            else {
                fun out(x: Float) = 1f - (1f - x.coerceIn(0f, 1f)).pow(3)
                // Zoom settles quickly; the blur clears evenly over the whole motion (eased out it was gone
                // almost at once).
                val kb = out(u); val ks = out(u / 0.6f); val kc = u * u * (3f - 2f * u)
                unlockBg = amt * 0.08f * (1f - kb); unlockBlur = amt * 3f * (1f - kc)
                unlockFg = amt * 0.05f * (1f - ks); typeZoom = 1f + amt * 0.06f * (1f - kb)
                more = true
            }
        }
        if (demo) return drawDemo(frameTimeNanos)
        if (follow()) return drawFollowing() || more
        if (animating) {
            val ready = texture(from!!) != null && texture(to!!) != null
            if (ready) {
                val step = dt * speed * 1000f / cfg.durationMs.coerceAtLeast(100)
                if (backward) { t -= step; if (t <= 0f) finishTransition() }
                else { t += step; if (t >= 1f) finishTransition() }
            }
            more = true
        }
        // Warm the neighbours so the next tilt has its texture ready.
        val center = seq.indexOfFirst { it.id == (if (animating) to else shown)?.id }
        if (center >= 0) {
            seq.getOrNull(center - 1)?.let { texture(it) }
            seq.getOrNull(center + 1)?.let { texture(it) }
        }

        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        val a = (if (animating) from else shown) ?: return more
        val texA = texture(a) ?: return more
        val b = if (animating) to else null
        val texB = b?.let { texture(it) }
        if (b != null && texB != null) draw(a, texA, b, texB, ease(t)) else draw(a, texA, null, null, 0f)
        return more
    }

    override fun onRelease() {
        textGl.release()
        textures.values.forEach { deleteTex(it) }
        textures.clear()
        glDeleteTextures(2, clockTex, 0); clockTex.fill(0); clockKeys.fill(null); settledStyle = null
        for (i in 0 until programs.size()) glDeleteProgram(programs.valueAt(i).id)
        programs.clear()
        if (quad != 0) glDeleteBuffers(1, intArrayOf(quad), 0)
        if (blackTex != 0) glDeleteTextures(1, intArrayOf(blackTex), 0)
        io.shutdownNow()
    }

    /** Offscreen: renders [img] once per effect. Call on a thread with a current context and a w*h surface. */
    fun renderStills(img: WallImage, base: Settings, effects: List<Effect>, w: Int, h: Int): List<Bitmap> {
        stills = true
        if (quad == 0) initGl()
        width = w; height = h
        glViewport(0, 0, w, h)
        cfg = base
        keyCache.clear()
        val bmp = decode(Repo.imageFile(img.id).path, max(w, h) * 2) ?: return emptyList()
        val sub = subject(img)?.let { (kind, file) -> decode(file.path, if (kind == 's') 1024 else 512)?.let { it to (kind == 's') } }
        val tex = makeTex(bmp, sub?.let { prepareSubject(it.first, it.second) })
        if (img.edit.subject.dropShadow && tex.shadowSrc != null) {
            tex.shadow = tex.shadowSrc.render(img.edit.subject.shadowBlur).let { b -> upload(b, mipmaps = false).also { b.recycle() } }
            tex.shadowSoft = img.edit.subject.shadowBlur
        }
        tiltX = 0.3f; tiltY = 0f
        val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
        val out = effects.map { e ->
            cfg = base.copy(effect = e, parallax = false)
            draw(img, tex, null, null, 0f)
            pixels.rewind()
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
            val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(pixels) }
            Bitmap.createBitmap(raw, 0, 0, w, h, flip, false).also { raw.recycle() }
        }
        deleteTex(tex)
        return out
    }

    private fun drawDemo(now: Long): Boolean {
        if (demoStart == 0L) demoStart = now
        val a = seq.getOrNull(neutralIdx) ?: return true
        val b = seq.getOrNull(neutralIdx - 1) ?: seq.getOrNull(neutralIdx + 1)
        // 3.2 s loop: rest, flip over, rest, flip back.
        val ph = ((now - demoStart) / 1e9f % 3.2f) / 3.2f
        val p = when {
            ph < 0.15f -> 0f
            ph < 0.5f -> ease((ph - 0.15f) / 0.35f)
            ph < 0.65f -> 1f
            else -> 1f - ease((ph - 0.65f) / 0.35f)
        }
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        val texA = texture(a) ?: return true
        val texB = b?.let { texture(it) }
        if (b != null && texB != null && p > 0f) {
            setDir(if (seq.indexOf(b) < neutralIdx) -1 else 1)
            draw(a, texA, b, texB, p)
        } else draw(a, texA, null, null, 0f)
        return true
    }

    /** "Theo tay" applies: Theo góc, tilt not paused by the lock or a tap. */
    private fun follow(): Boolean = cfg.followTilt && seq.size > 1 && synchronized(nav) { nav.tiltDriven }

    /** The transition scrubbed by the smoothed angle: the pair the phone sits between, at that point. */
    private fun drawFollowing(): Boolean {
        val (ia, ib, pr) = synchronized(nav) { nav.between(rollNow) }
        val a = seq.getOrNull(ia) ?: return false
        val b = seq.getOrNull(ib)
        animating = false; backward = false; queued = null; from = null; to = null
        shown = if (b != null && pr >= 0.5f) b else a
        // Neighbours of the pair stay warm for the next step.
        seq.getOrNull(minOf(ia, ib) - 1)?.let { texture(it) }
        seq.getOrNull(maxOf(ia, ib) + 1)?.let { texture(it) }
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        val texA = texture(a)
        val texB = b?.let { texture(it) }
        when {
            // Resting on the second picture: draw it on its own, exactly as when nothing is moving (the pair at
            // full progress took the layout-change path and looked different from the preview).
            b != null && b.id != a.id && pr >= 0.999f && texB != null -> draw(b, texB, null, null, 0f)
            b != null && b.id != a.id && pr > 0f && texA != null && texB != null -> {
                setDir(if (ib < ia) -1 else 1)
                draw(a, texA, b, texB, pr)
            }
            texA != null -> draw(a, texA, null, null, 0f)
            else -> return true   // still loading
        }
        return false
    }

    private fun finishTransition() {
        if (backward) to = from
        shown = to; from = null; to = null; animating = false; t = 0f; backward = false
        val q = queued ?: return
        queued = null
        val img = seq.getOrNull(q.first) ?: return
        if (img.id != shown?.id) { start(shown!!, img, q.second); speed = 1.6f }
    }

    private fun ease(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f

    // ---------------------------------------------------------------- drawing
    private val rect = FloatArray(4)
    // Colour matrix per image slot, rebuilt only when that slot's edit changes (not every frame).
    private val gradeEdit = arrayOfNulls<com.tilt.wallpapers.data.ImageEdit>(2)
    private val gradeMat = Array(2) { FloatArray(16) }
    private fun gradeOf(e: com.tilt.wallpapers.data.ImageEdit, slot: Int): FloatArray {
        if (gradeEdit[slot] !== e) { Grading.glMatrix(e, gradeMat[slot]); gradeEdit[slot] = e }
        return gradeMat[slot]
    }

    private fun draw(a: WallImage, ta: Tex, b: WallImage?, tb: Tex?, progress: Float) {
        val st = cfg
        val depth = ta.mask != 0 || (tb?.mask ?: 0) != 0
        // Effect thumbnails (offscreen, no render thread) leave out the clock and texts.
        val clockOn = !stills && ensureClock()
        val textOn = !stills && textGl.ensure(textLayer, seq, height)
        val clock = (clockOn || textOn) && clockVis > 0.001f
        val p = program(st.effect, if (b != null) st.transition else null, depth, clock)
        glUseProgram(p.id)

        val rotateMode = st.rotateSubject && !st.parallax
        val margin = when {
            st.parallax && !st.rotateSubject -> 0.025f + 0.045f * st.depthStrength
            st.rotateSubject -> 0.03f
            else -> 0f
        }
        val scale = 1f / (1f + 2f * margin)
        val fgShift = if (st.parallax && !st.rotateSubject) 1.9f else 0f
        layout.scale = scale
        layout.fgScale = (if (rotateMode) 1.2f else 1f) / (1f + unlockFg)
        layout.shiftX = -tiltX * margin * scale * fgShift
        layout.shiftY = -tiltY * margin * scale * fgShift
        layout.angle = if (rotateMode) tiltX * 0.35f else 0f

        bindImage(p, 0, a, ta, isA = true)
        if (b != null && tb != null) bindImage(p, 2, b, tb, isA = false)

        glUniform3f(p.u("uBg"), -tiltX * margin * scale, -tiltY * margin * scale, scale / (1f + unlockBg))
        glUniform2f(p.u("uRes"), width.toFloat(), height.toFloat())
        glUniform2f(p.u("uTilt"), tiltX, tiltY)
        glUniform1f(p.u("uStrength"), st.effectStrength)
        glUniform1f(p.u("uProgress"), progress)
        glUniform2f(p.u("uDir"), dir[0], dir[1])
        glUniform1f(p.u("uDim"), dimNow)
        glUniform4f(p.u("uFold"), st.foldDim, st.foldBlur, st.foldOnset, st.foldClear)
        if (clock) {
            glUniform1f(p.u("uClockOn"), if (clockOn) 1f else 0f)
            if (clockOn) bindClock(p, a, b, progress)
            bindTypeMisc(p)
            if (!textOn) glUniform4f(p.u("uTxtMisc"), 0f, 0f, 0f, 0f)
            else if (textGl.bind(p, textLayer, a, b, progress, !clockStyle.followImage && b != null, height.toFloat() / width,
                    clockVis.coerceIn(0f, 1f), typeZoom, clockAuto)) host.requestRender()
        }

        glBindBuffer(GL_ARRAY_BUFFER, quad)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Per-frame subject-layer motion shared by both images of a transition. */
    private class Layout { var scale = 1f; var fgScale = 1f; var shiftX = 0f; var shiftY = 0f; var angle = 0f }
    private val layout = Layout()
    private val sub = FloatArray(6)

    private fun bindImage(p: Program, unit: Int, img: WallImage, tex: Tex, isA: Boolean) {
        val n = if (isA) namesA else namesB
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindTexture(GL_TEXTURE_2D, tex.id)
        glUniform1i(p.u(n.tex), unit)
        glActiveTexture(GL_TEXTURE0 + unit + 1)
        glBindTexture(GL_TEXTURE_2D, if (tex.mask != 0) tex.mask else blackTex)
        glUniform1i(p.u(n.mask), unit + 1)
        Grading.fitCrop(img.edit, tex.w.toFloat() / tex.h, width.toFloat() / height, rect)
        glUniform4f(p.u(n.rect), rect[0], rect[1], rect[2], rect[3])
        glUniformMatrix4fv(p.u(n.grade), 1, false, gradeOf(img.edit, if (isA) 0 else 1), 0)
        glUniform3f(p.u(n.look), img.edit.opacity, img.edit.vignette, 0f)

        val t = img.edit.subject
        val mode = when { tex.mask == 0 -> 0f; tex.rgba -> 2f; else -> 1f }
        if (tex.rgba) {
            externalSubject(t, tex.mw.toFloat() / tex.mh, sub)
            // Into the padded texture: u' = pad + u * (1 - 2 pad).
            val su = 1f - 2f * tex.padU; val sv = 1f - 2f * tex.padV
            sub[0] *= su; sub[1] *= su; sub[2] = sub[2] * su + tex.padU
            sub[3] *= sv; sub[4] *= sv; sub[5] = sub[5] * sv + tex.padV
        } else maskSubject(sub)
        glUniform3f(p.u(n.sub0), sub[0], sub[1], sub[2])
        glUniform3f(p.u(n.sub1), sub[3], sub[4], sub[5])
        val shadowOn = t.dropShadow && mode > 0f
        if (shadowOn) ensureShadow(tex, t.shadowBlur)
        glActiveTexture(GL_TEXTURE0 + 4 + if (isA) 0 else 1)
        glBindTexture(GL_TEXTURE_2D, if (shadowOn && tex.shadow != 0) tex.shadow else blackTex)
        glUniform1i(p.u(n.shadow), 4 + if (isA) 0 else 1)
        glUniform4f(
            p.u(n.fx),
            t.backgroundBlur * 6f + unlockBlur,
            if (shadowOn && tex.shadow != 0) t.shadowOpacity else 0f,
            0f,
            mode,
        )
        // The shadow drifts against the tilt so the subject seems to float above it.
        glUniform2f(p.u(n.sh), t.shadowOffsetX - tiltX * 0.012f, t.shadowOffsetY - tiltY * 0.008f)
    }

    /** Mask subjects live in image space: same crop as the image, plus the parallax / rotate motion. */
    private val rotM = FloatArray(6); private val layM = FloatArray(6); private val cropM = FloatArray(6); private val tmpM = FloatArray(6)
    private fun maskSubject(out: FloatArray) {
        val w = width.toFloat(); val h = height.toFloat()
        val c = kotlin.math.cos(layout.angle); val s = kotlin.math.sin(layout.angle)
        rotM[0] = c; rotM[1] = -s * h / w; rotM[2] = 0.5f - 0.5f * c + 0.5f * s * h / w
        rotM[3] = s * w / h; rotM[4] = c; rotM[5] = 0.5f - 0.5f * s * w / h - 0.5f * c
        val fs = layout.scale * layout.fgScale
        layM[0] = fs; layM[1] = 0f; layM[2] = 0.5f - 0.5f * fs + layout.shiftX
        layM[3] = 0f; layM[4] = fs; layM[5] = 0.5f - 0.5f * fs + layout.shiftY
        cropM[0] = rect[2]; cropM[1] = 0f; cropM[2] = rect[0]; cropM[3] = 0f; cropM[4] = rect[3]; cropM[5] = rect[1]
        compose(layM, rotM, tmpM); compose(cropM, tmpM, out)
    }

    /** External subjects are placed on the screen by [t]: screen uv -> subject uv. */
    private fun externalSubject(t: com.tilt.wallpapers.data.SubjectTransform, aspect: Float, out: FloatArray) {
        val w = width.toFloat(); val h = height.toFloat()
        val (bw, bh) = com.tilt.wallpapers.data.SubjectTransform.baseSize(aspect, w / h)
        val sw = bw * t.scale * (1f + unlockFg) * w; val sh = bh * t.scale * (1f + unlockFg) * h
        val cx = 0.5f + t.offsetX - layout.shiftX
        val cy = 0.5f + t.offsetY - layout.shiftY
        val th = Math.toRadians(t.rotation.toDouble()).toFloat() - layout.angle
        val c = kotlin.math.cos(th); val s = kotlin.math.sin(th)
        out[0] = c * w / sw; out[1] = s * h / sw; out[2] = 0.5f - (c * w * cx + s * h * cy) / sw
        out[3] = -s * w / sh; out[4] = c * h / sh; out[5] = 0.5f - (-s * w * cx + c * h * cy) / sh
        if (t.flipHorizontal) { out[0] = -out[0]; out[1] = -out[1]; out[2] = 1f - out[2] }
        if (t.flipVertical) { out[3] = -out[3]; out[4] = -out[4]; out[5] = 1f - out[5] }
    }

    /** Affine maps as rows [a b c; d e f]: [out] = outer ∘ inner ([out] must be neither). */
    private fun compose(o: FloatArray, i: FloatArray, out: FloatArray) {
        out[0] = o[0] * i[0] + o[1] * i[3]; out[1] = o[0] * i[1] + o[1] * i[4]; out[2] = o[0] * i[2] + o[1] * i[5] + o[2]
        out[3] = o[3] * i[0] + o[4] * i[3]; out[4] = o[3] * i[1] + o[4] * i[4]; out[5] = o[3] * i[2] + o[4] * i[5] + o[5]
    }

    // ---------------------------------------------------------------- textures
    /** The subject source for [img]: 's' external cut-out, 'm' manual mask, 'a' AI mask. */
    private fun subject(img: WallImage): Pair<Char, java.io.File>? = when {
        img.hasSubject -> 's' to Repo.subjectFile(img.id)
        cfg.depth && img.hasManualMask -> 'm' to Repo.manualMaskFile(img.id)
        cfg.depth && img.hasMask -> 'a' to Repo.maskFile(img.id)
        else -> null
    }

    // Texture key per image object (several lookups a frame); cleared whenever the images or settings change.
    private val keyCache = java.util.IdentityHashMap<WallImage, String>()
    private fun key(img: WallImage) = keyCache.getOrPut(img) { subject(img)?.let { "${img.id}+${it.first}${img.subjectRev}" } ?: img.id }

    private fun texture(img: WallImage): Tex? {
        val k = key(img)
        textures[k]?.let { return it }
        if (loading.add(k)) {
            val src = subject(img)
            io.execute {
                val bmp = decode(Repo.imageFile(img.id).path, maxTexture)
                val sub = src?.let { (kind, file) ->
                    decode(file.path, if (kind == 's') minOf(maxTexture, 1600) else 1024)?.let { prepareSubject(it, kind == 's') }
                }
                host.post {
                    loading.remove(k)
                    if (bmp == null) { sub?.bitmap?.recycle(); return@post }
                    textures[k] = makeTex(bmp, sub)
                    trimTextures()
                    host.requestRender()
                }
            }
        }
        // While a new variant loads keep showing whatever version of this image is ready.
        return textures.entries.firstOrNull { it.key == img.id || it.key.startsWith(img.id + "+") }?.value
    }

    /** A decoded subject ready to upload: padded if external, plus its shadow source. */
    private class PreparedSubject(val bitmap: Bitmap, val rgba: Boolean, val pad: Int, val w: Int, val h: Int, val shadow: ShadowSource)

    /**
     * Runs off the GL thread. External subjects are trimmed tight, so they get a transparent margin wide
     * enough for the softest drop shadow to spread past the subject's own box.
     */
    private fun prepareSubject(s: Bitmap, rgba: Boolean): PreparedSubject {
        if (!rgba) return PreparedSubject(s, false, 0, s.width, s.height, ShadowSource.ofMask(s))
        val pad = ShadowSource.padFor(max(s.width, s.height))
        val padded = Bitmap.createBitmap(s.width + pad * 2, s.height + pad * 2, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(padded).drawBitmap(s, pad.toFloat(), pad.toFloat(), null)
        val shadow = ShadowSource.ofSubject(s, pad)
        val w = s.width; val h = s.height
        s.recycle()
        return PreparedSubject(padded, true, pad, w, h, shadow)
    }

    /** Uploads and recycles the bitmaps. */
    private fun makeTex(bmp: Bitmap, sub: PreparedSubject?): Tex {
        val tex = Tex(
            upload(bmp, mipmaps = true), bmp.width, bmp.height,
            sub?.let { upload(it.bitmap, mipmaps = true) } ?: 0,
            sub?.w ?: 1, sub?.h ?: 1, sub?.rgba ?: false,
            if (sub != null) sub.pad.toFloat() / (sub.w + sub.pad * 2) else 0f,
            if (sub != null) sub.pad.toFloat() / (sub.h + sub.pad * 2) else 0f,
            sub?.shadow,
        )
        bmp.recycle(); sub?.bitmap?.recycle()
        return tex
    }

    /** Keeps [tex]'s shadow map in step with the softness slider, rebuilding it off the GL thread. */
    private fun ensureShadow(tex: Tex, soft: Float) {
        // Stills have no render thread to come back to (their shadow is made up front): it crashed the decoder.
        if (stills) return
        val src = tex.shadowSrc ?: return
        val want = (soft * 50).roundToInt() / 50f
        if (tex.shadowBusy || want == tex.shadowSoft) return
        tex.shadowBusy = true
        io.execute {
            val bmp = runCatching { src.render(want) }.getOrNull()
            host.post {
                tex.shadowBusy = false
                if (bmp == null) return@post
                if (!tex.deleted) {
                    if (tex.shadow != 0) glDeleteTextures(1, intArrayOf(tex.shadow), 0)
                    tex.shadow = upload(bmp, mipmaps = false)
                    tex.shadowSoft = want
                }
                bmp.recycle()
                host.requestRender()
            }
        }
    }

    private fun trimTextures() {
        val center = seq.indexOfFirst { it.id == (to ?: shown)?.id }
        val keep = (listOfNotNull(shown, from, to, seq.getOrNull(center - 1), seq.getOrNull(center + 1)))
            .flatMap { if (textures.containsKey(key(it))) listOf(key(it)) else listOf(key(it), it.id) }.toSet()
        val it = textures.entries.iterator()
        var excess = textures.size - MAX_TEXTURES
        while (it.hasNext() && excess > 0) {
            val e = it.next()
            if (e.key !in keep) { deleteTex(e.value); it.remove(); excess-- }
        }
    }

    private fun deleteTex(t: Tex) {
        t.deleted = true
        glDeleteTextures(3, intArrayOf(t.id, t.mask, t.shadow), 0)
    }

    private fun decode(path: String, maxDim: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Raw RGBA bytes (every channel its own data, never premultiplied), mipmapped. */
    private fun uploadRgba(px: ByteBuffer, w: Int, h: Int): Int {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, px)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
        glGenerateMipmap(GL_TEXTURE_2D)
        return ids[0]
    }

    private fun upload(bmp: Bitmap, mipmaps: Boolean): Int {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, if (mipmaps) GL_LINEAR_MIPMAP_LINEAR else GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
        if (mipmaps) glGenerateMipmap(GL_TEXTURE_2D)
        return ids[0]
    }

    // ---------------------------------------------------------------- programs
    internal class Program(val id: Int) {
        private val cache = HashMap<String, Int>()
        fun u(name: String) = cache.getOrPut(name) { glGetUniformLocation(id, name) }
    }

    private fun program(effect: Effect, transition: Transition?, depth: Boolean, clock: Boolean = false): Program {
        val k = ((effect.ordinal * 32 + (transition?.let { it.ordinal + 1 } ?: 0)) * 2 + (if (depth) 1 else 0)) * 2 + (if (clock) 1 else 0)
        return programs.get(k) ?: Program(link(Shaders.VERTEX, Shaders.fragment(effect, transition, depth, clock))).also { programs.put(k, it) }
    }

    private fun link(vs: String, fs: String): Int {
        val p = glCreateProgram()
        val v = compile(GL_VERTEX_SHADER, vs)
        val f = compile(GL_FRAGMENT_SHADER, fs)
        glAttachShader(p, v); glAttachShader(p, f)
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "link: " + glGetProgramInfoLog(p))
        glDeleteShader(v); glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "compile: " + glGetShaderInfoLog(s))
        return s
    }

    private fun initGl() {
        val buf = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        quad = ids[0]
        glBindBuffer(GL_ARRAY_BUFFER, quad)
        glBufferData(GL_ARRAY_BUFFER, 8 * 4, buf, GL_STATIC_DRAW)
        glGenTextures(1, ids, 0)
        blackTex = ids[0]
        glBindTexture(GL_TEXTURE_2D, blackTex)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4))
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
    }

    private companion object {
        const val TAG = "TiltRenderer"
        const val MAX_TEXTURES = 5
        const val UNLOCK_HOLD = 120_000_000L
    }
}
