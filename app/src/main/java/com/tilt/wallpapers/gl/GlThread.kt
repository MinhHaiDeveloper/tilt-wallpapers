package com.tilt.wallpapers.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Minimal EGL wrapper with an ES 3.0 context. The display is shared in-process, so it is never terminated. */
class EglCore {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    val context: EGLContext

    init {
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0)
        config = checkNotNull(configs[0]) { "No ES3 EGL config" }
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
    }

    fun windowSurface(window: Any): EGLSurface = EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
    fun pbuffer(w: Int, h: Int): EGLSurface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
    fun makeCurrent(s: EGLSurface) = EGL14.eglMakeCurrent(display, s, s, context)
    fun swap(s: EGLSurface) = EGL14.eglSwapBuffers(display, s)
    fun destroy(s: EGLSurface) = EGL14.eglDestroySurface(display, s)

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
    }

    private companion object { const val EGL_OPENGL_ES3_BIT = 0x40 }
}

interface GlScene {
    fun onSurface(width: Int, height: Int)
    /** Draws a frame; returns true to keep animating. */
    fun onDraw(frameTimeNanos: Long): Boolean
    fun onRelease()
}

/**
 * Render thread driven by its own Choreographer: frames are only produced on request (or while the
 * scene says it is animating), which keeps the live wallpaper cheap when the phone is still.
 */
class GlThread(name: String, private val scene: GlScene) {
    private val thread = HandlerThread(name, android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var egl: EglCore
    private lateinit var idleSurface: EGLSurface
    private var window: EGLSurface? = null
    private var frameQueued = false
    private var lastFrame = 0L
    /** Battery saver: skip vsyncs so frames come at most this often. */
    @Volatile var minFrameNanos = 0L
    private val frameCallback = Choreographer.FrameCallback { t ->
        frameQueued = false
        if (minFrameNanos > 0 && t - lastFrame < minFrameNanos - 2_000_000L) scheduleFrame()
        else { lastFrame = t; drawFrame(t) }
    }

    init {
        handler.post {
            egl = EglCore()
            idleSurface = egl.pbuffer(1, 1)
            egl.makeCurrent(idleSurface)
        }
    }

    fun post(block: () -> Unit) { handler.post(block) }
    fun postDelayed(ms: Long, block: () -> Unit) { handler.postDelayed(block, ms) }

    fun attach(window: Any, width: Int, height: Int) = handler.post {
        this.window?.let { egl.destroy(it) }
        val s = egl.windowSurface(window)
        this.window = s
        egl.makeCurrent(s)
        scene.onSurface(width, height)
        scheduleFrame()
    }

    fun resize(width: Int, height: Int) = handler.post {
        if (window != null) { scene.onSurface(width, height); scheduleFrame() }
    }

    /** Blocks until the window surface is released (required by SurfaceHolder/TextureView callbacks). */
    fun detach() {
        val latch = CountDownLatch(1)
        handler.post {
            window?.let { egl.makeCurrent(idleSurface); egl.destroy(it) }
            window = null
            latch.countDown()
        }
        latch.await(1, TimeUnit.SECONDS)
    }

    fun requestRender() { handler.post { scheduleFrame() } }

    fun quit() = handler.post {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        scene.onRelease()
        window?.let { egl.destroy(it) }
        egl.destroy(idleSurface)
        egl.release()
        thread.quitSafely()
    }

    private fun scheduleFrame() {
        if (frameQueued || window == null) return
        frameQueued = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun drawFrame(t: Long) {
        val s = window ?: return
        val more = scene.onDraw(t)
        egl.swap(s)
        if (more) scheduleFrame()
    }
}
