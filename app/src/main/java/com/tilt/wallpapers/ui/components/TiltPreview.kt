package com.tilt.wallpapers.ui.components

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.DisplayMetrics
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tilt.wallpapers.data.AppState
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.gl.GlThread
import com.tilt.wallpapers.gl.TiltRenderer
import com.tilt.wallpapers.tilt.TiltSensor

private class PreviewEngine(context: Context) : TextureView.SurfaceTextureListener {
    val renderer = TiltRenderer(maxTexture = 1440)
    val gl = GlThread("tilt-preview", renderer).also { renderer.host = it }
    val sensor = TiltSensor(context) { roll, pitch -> renderer.onTilt(roll, pitch) }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile var sensing = false
    init { renderer.onLevel = { l -> main.post { sensor.setLevel(l, sensing) } } }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { gl.attach(st, w, h) }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { gl.resize(w, h) }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { gl.detach(); return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    fun release() { sensor.stop(); gl.quit() }
}

/**
 * Live preview using the exact wallpaper renderer: reacts to tilt, tapping steps to the next image.
 * Shows [setId] or the active set, from [override] instead of the saved state when given (unsaved edits).
 */
@Composable
fun TiltPreview(
    modifier: Modifier = Modifier,
    setId: String? = null,
    onTapStep: Boolean = true,
    override: AppState? = null,
    tilt: Boolean = true,
    /** Loops the current transition between the middle picture and a neighbour (no sensor). */
    demo: Boolean = false,
) {
    val context = LocalContext.current
    val engine = remember { PreviewEngine(context).also { it.renderer.demo = demo } }
    val state by Repo.state.collectAsStateWithLifecycle()
    LaunchedEffect(override ?: state, setId) { engine.renderer.setState(override ?: state, setId) }
    LifecycleResumeEffect(engine, tilt) {
        if (tilt) { engine.sensing = true; engine.sensor.start() }
        onPauseOrDispose { engine.sensing = false; engine.sensor.stop() }
    }
    DisposableEffect(engine) { onDispose { engine.release() } }
    LaunchedEffect(engine) { Repo.commands.collect { if (it == Repo.Command.PREVIEW_UNLOCK) engine.renderer.playUnlock(force = true) } }
    Box(if (onTapStep) modifier.bounceClick(pressedScale = 0.96f) { engine.renderer.step(1) } else modifier) {
        AndroidView(
            factory = { TextureView(it).apply { surfaceTextureListener = engine } },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Width / height of the physical screen in portrait, i.e. the wallpaper's aspect ratio. */
@Composable
fun screenAspect(): Float {
    val context = LocalContext.current
    return remember {
        val wm = context.getSystemService(WindowManager::class.java)
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
        }
        minOf(w, h).toFloat() / maxOf(w, h)
    }
}
