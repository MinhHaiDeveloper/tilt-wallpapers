package com.tilt.wallpapers.gl

import android.graphics.Bitmap
import com.tilt.wallpapers.data.Effect
import com.tilt.wallpapers.data.Settings
import com.tilt.wallpapers.data.WallImage
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Renders the effect picker thumbnails with the real shaders on a private offscreen context. */
object EffectThumbs {
    private const val W = 300
    private const val H = 640
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "effect-thumbs") }.asCoroutineDispatcher()
    private var egl: EglCore? = null
    private var renderer: TiltRenderer? = null

    suspend fun render(img: WallImage, settings: Settings, aspect: Float): Map<Effect, Bitmap> = withContext(dispatcher) {
        val core = egl ?: EglCore().also { core ->
            core.makeCurrent(core.pbuffer(W, H))
            egl = core
        }
        val r = renderer ?: TiltRenderer(1024).also { renderer = it }
        val w = (H * aspect).toInt().coerceIn(64, W)
        val effects = Effect.entries
        effects.zip(r.renderStills(img, settings, effects, w, H)).toMap()
    }
}
