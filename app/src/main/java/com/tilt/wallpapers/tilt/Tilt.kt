package com.tilt.wallpapers.tilt

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.tilt.wallpapers.data.TiltMode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reads gravity and reports roll (degrees, positive = left edge down) and pitch (degrees).
 * Uses the fused gravity sensor when present, otherwise a low-passed accelerometer.
 */
class TiltSensor(context: Context, private val onTilt: (roll: Float, pitch: Float) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravity = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val sensor = gravity ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val g = FloatArray(3)
    private var primed = false
    private var running = false

    /** Microseconds between readings. */
    private var rate = 20_000
    /** "Ảnh tĩnh": no readings at all until a lighter level. */
    private var paused = false

    fun start() {
        if (running || sensor == null || paused) return
        running = true
        primed = false
        // Delivered on a background thread: readings never queue up behind the UI's frames (or delay them).
        sm.registerListener(this, sensor, rate, handler)
    }

    /**
     * Readings for a performance level (0 full … 4 still): fewer as it lightens, none at all for 4. Restarts
     * the listener if the rate changed. [wanted]: whether it should run now (the wallpaper shows).
     */
    fun setLevel(level: Int, wanted: Boolean) {
        val r = when (level) { 0 -> 20_000; 1 -> 25_000; 2 -> 33_000; else -> 66_000 }
        val p = level >= 4
        if (r == rate && p == paused) return
        rate = r; paused = p
        stop()
        if (wanted) start()
    }

    fun stop() {
        if (!running) return
        running = false
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (gravity != null || !primed) {
            System.arraycopy(e.values, 0, g, 0, 3)
            primed = true
        } else {
            for (i in 0..2) g[i] += (e.values[i] - g[i]) * 0.15f
        }
        val roll = Math.toDegrees(atan2(g[0], sqrt(g[1] * g[1] + g[2] * g[2])).toDouble()).toFloat()
        val pitch = Math.toDegrees(atan2(g[1], g[2]).toDouble()).toFloat()
        onTilt(roll, pitch)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** One thread for every tilt listener in the process (wallpaper and previews). */
        val handler by lazy {
            android.os.Handler(android.os.HandlerThread("tilt-sensor", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }.looper)
        }
    }
}

/**
 * Detects shake gestures from accelerometer data.
 */
class ShakeDetector(private val onShake: () -> Unit) {
    private var lastUpdate = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f

    fun update(x: Float, y: Float, z: Float) {
        val now = System.currentTimeMillis()
        if (lastUpdate == 0L) {
            lastUpdate = now
            lastX = x; lastY = y; lastZ = z
            return
        }
        val diff = now - lastUpdate
        if (diff < 100) return
        
        val speed = (abs(x + y + z - lastX - lastY - lastZ) / diff) * 10000
        if (speed > 800) {
            onShake()
        }
        
        lastUpdate = now
        lastX = x; lastY = y; lastZ = z
    }
}

/**
 * Turns a roll angle into an index in the tilt sequence.
 *  - ZONES: the tilt range on each side is split evenly between that side's images (with hysteresis).
 *  - STEPS: each tilt past the threshold moves one image; return to level to re-arm. Wraps around.
 */
class TiltNavigator {
    var index = 0; private set
    private var count = 0
    private var neutral = 0
    private var mode = TiltMode.ZONES
    private var range = 35f
    private var zone = 0
    private var armed = true
    private var lastRoll = 0f
    private var manualRoll = Float.NaN // set by step(): tilt control pauses until the phone actually moves
    /** Khóa nghiêng: readings are remembered but never change the picture. */
    var locked = false

    fun configure(count: Int, neutral: Int, mode: TiltMode, range: Float) {
        val changed = count != this.count || neutral != this.neutral || mode != this.mode
        this.count = count; this.neutral = neutral; this.mode = mode; this.range = range
        if (changed) { zone = 0; armed = true; index = neutral; manualRoll = Float.NaN }
        if (!manualRoll.isNaN()) { index = index.coerceIn(0, max(0, count - 1)); return }
        index = index.coerceIn(0, max(0, count - 1))
        if (mode == TiltMode.ZONES) update(lastRoll)
    }

    /** Returns (newIndex, direction) where direction is -1 for a move to the left, +1 to the right. */
    fun update(roll: Float): Pair<Int, Int>? {
        lastRoll = roll
        if (locked || count <= 1) return null
        if (!manualRoll.isNaN()) {
            if (abs(roll - manualRoll) < 6f) return null
            manualRoll = Float.NaN
        }
        val old = index
        var dir = 0
        when (mode) {
            TiltMode.ZONES -> {
                val f = if (roll >= 0) level(roll, neutral) else -level(-roll, count - 1 - neutral)
                if (f > zone + 0.5f + HYSTERESIS || f < zone - 0.5f - HYSTERESIS) zone = f.roundToInt()
                zone = zone.coerceIn(-(count - 1 - neutral), neutral)
                index = neutral - zone
                dir = if (index < old) -1 else 1
            }
            TiltMode.STEPS -> {
                val threshold = range * 0.5f
                when {
                    armed && roll > threshold -> { index = (index - 1 + count) % count; armed = false; dir = -1 }
                    armed && roll < -threshold -> { index = (index + 1) % count; armed = false; dir = 1 }
                    abs(roll) < threshold * 0.45f -> armed = true
                }
            }
        }
        return if (index != old) index to dir else null
    }

    /** True when the tilt drives the picture (not paused by a tap or the lock). */
    val tiltDriven get() = !locked && manualRoll.isNaN() && count > 1 && mode == TiltMode.ZONES

    /**
     * "Theo tay": the two pictures [roll] sits between and how far along it is (0 = first, 1 = second).
     * Each side's range is split into one step per picture; the first 80% of a step is the transition and
     * the rest holds the picture, so it rests steadily. The first of the pair is the one nearer the middle.
     */
    fun between(roll: Float): Triple<Int, Int, Float> {
        val left = roll >= 0
        val images = if (left) neutral else count - 1 - neutral
        val angle = abs(roll)
        if (images == 0 || angle < DEAD_ZONE) return Triple(neutral, neutral, 0f)
        val step = max((range - DEAD_ZONE) / images, 2.5f)
        val x = ((angle - DEAD_ZONE) / step).coerceIn(0f, images.toFloat())
        val k = minOf(x.toInt(), images - 1)
        val p = ((x - k) / 0.8f).coerceIn(0f, 1f)
        val s = if (left) -1 else 1
        return Triple(neutral + s * k, neutral + s * (k + 1), p)
    }

    /** Manual step (used by in-app previews when tapped). */
    fun step(delta: Int): Pair<Int, Int>? {
        if (count <= 1) return null
        index = (index + delta + count) % count
        manualRoll = lastRoll
        zone = neutral - index
        return index to delta.coerceIn(-1, 1)
    }

    /** 0 inside the dead zone, then +1 for each image step. */
    private fun level(angle: Float, images: Int): Float {
        if (images == 0) return 0f
        if (angle < DEAD_ZONE) return angle / DEAD_ZONE * 0.5f
        val step = max((range - DEAD_ZONE) / images, 2.5f)
        return 0.5f + (angle - DEAD_ZONE) / step
    }

    private companion object {
        const val DEAD_ZONE = 6f
        const val HYSTERESIS = 0.18f
    }
}
