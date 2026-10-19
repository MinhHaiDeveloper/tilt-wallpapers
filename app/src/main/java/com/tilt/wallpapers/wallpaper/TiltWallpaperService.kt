package com.tilt.wallpapers.wallpaper

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.tilt.wallpapers.data.Automation
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.gl.GlThread
import com.tilt.wallpapers.gl.TiltRenderer
import com.tilt.wallpapers.tilt.TiltSensor
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TiltWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = TiltEngine()

    private inner class TiltEngine : Engine() {
        private val renderer = TiltRenderer(maxTexture = 2560)
        private val gl = GlThread("tilt-wallpaper", renderer).also { renderer.host = it; renderer.isWallpaper = true }
        private val sensor = TiltSensor(applicationContext) { roll, pitch -> 
            renderer.onTilt(roll, pitch)
            if (Repo.state.value.settings.shake) shakeDetector.update(roll, pitch, 0f)
        }
        private val shakeDetector = com.tilt.wallpapers.tilt.ShakeDetector {
            if (Repo.state.value.settings.shake) {
                val set = Repo.state.value.activeSet
                if (set != null && set.images.size > 1) {
                    val delta = (-(set.images.size / 2)..(set.images.size / 2)).random()
                    if (delta != 0) renderer.step(delta)
                }
            }
        }
        private val scope = MainScope()
        private var attached = false
        private val main = Handler(Looper.getMainLooper())

        /** Every minute while shown: schedule slots, night hours and battery all change on minute boundaries. */
        private val tick = object : Runnable {
            override fun run() {
                Repo.applySchedule()
                refreshEnv()
                main.postDelayed(this, Automation.msToNextMinute())
            }
        }

        private val powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) onUnlocked()
                // Some phones (ColorOS) keep the wallpaper "visible" while the screen is off: no visibility change
                // comes either way. So the tilt sensor stops with the screen (it kept running, draining the
                // battery), and the screen-on broadcast restarts it and plays the motion.
                if (intent.action == Intent.ACTION_SCREEN_OFF) { screenWasOff = true; playedThisWake = false; sensor.stop() }
                if (intent.action == Intent.ACTION_SCREEN_ON && visibleNow) {
                    sensor.start(); renderer.snap(); gl.requestRender()
                    if (screenWasOff) { screenWasOff = false; onScreenOn() }
                }
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON -> refreshLock()
                    else -> refreshEnv()
                }
            }
        }

        /**
         * Lock screen up or not, for the clock's "Hiện ở" (no permission needed). While it is up and the wallpaper
         * shows, check again every half second: the unlock broadcast is late or missing on some phones, which
         * left the clock hidden on the home screen.
         */
        private var visibleNow = false
        private var screenWasOff = true
        private var wasLocked = false
        private val lockPoll = Runnable { refreshLock() }
        private fun refreshLock() {
            main.removeCallbacks(lockPoll)
            val km = applicationContext.getSystemService(android.app.KeyguardManager::class.java)
            val locked = km?.isKeyguardLocked == true
            renderer.setLocked(locked)
            // The poll also catches the unlock itself where its broadcast is late or missing.
            if (wasLocked && !locked) onUnlocked()
            wasLocked = locked
            if (locked && visibleNow) main.postDelayed(lockPoll, 500)
        }

        /**
         * Unlock motion, once per wake. "Khi bật màn hình": as the screen turns on (on the lock screen, if that
         * shows Tilt; with no lock it is the home screen anyway). "Khi mở khóa": as the lock screen goes away, for
         * phones whose lock screen shows another picture. A fingerprint unlock straight from the off screen sends
         * both moments together: still one motion.
         */
        private var playedThisWake = false
        private fun onScreenOn() {
            val km = applicationContext.getSystemService(android.app.KeyguardManager::class.java)
            val locked = km?.isKeyguardLocked == true
            if (locked && !Repo.state.value.settings.unlockOnWake) return     // waits for the unlock
            renderer.playUnlock(force = true)
            playedThisWake = true
        }
        /** This phone sends "lock screen going away": the late broadcasts no longer start the motion. */
        private var goingAwaySeen = false
        private fun onUnlocked(exact: Boolean = false) {
            if (!exact && goingAwaySeen) return
            if (playedThisWake) return
            renderer.playUnlock(force = true)
            playedThisWake = true
        }

        private fun refreshEnv() {
            val st = Repo.state.value.settings
            val low = Automation.lowPower(applicationContext, st)
            val thermal = applicationContext.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0
            renderer.setEnv(TiltRenderer.Env(low, Automation.reduceMotion(applicationContext), Automation.nightDim(applicationContext, st), thermal))
        }

        /** The phone heating up or cooling down: "Chế độ hiệu năng" (automatic) follows it. */
        private val thermalListener = PowerManager.OnThermalStatusChangedListener { refreshEnv() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // Sensor readings follow the performance level (none at all for "Ảnh tĩnh").
            renderer.onLevel = { l -> main.post { sensor.setLevel(l, visibleNow && !screenWasOff) } }
            applicationContext.getSystemService(PowerManager::class.java)?.addThermalStatusListener(thermalListener)
            setTouchEventsEnabled(false)
            Repo.init(applicationContext)
            renderer.setState(Repo.state.value)
            scope.launch {
                // Hidden (the app open on top, a game...): changes wait until it shows again. Editing in the app
                // no longer makes the hidden wallpaper decode pictures and query the battery on every change.
                Repo.state.collect {
                    if (!visibleNow) return@collect
                    renderer.setState(it)
                    refreshEnv()
                }
            }
            scope.launch { Repo.commands.collect { if (it == Repo.Command.NEXT_IMAGE) renderer.step(1) } }
            ContextCompat.registerReceiver(
                applicationContext, powerReceiver,
                IntentFilter().apply {
                    addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED); addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
                    addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (attached) gl.resize(width, height) else gl.attach(holder.surface, width, height)
            attached = true
        }

        /**
         * The system tells the wallpaper the exact moments: waking up, and the lock screen starting to go away
         * (the unlock animation). The broadcasts come later (the unlock one about a second after the home screen
         * shows, so the motion played on a picture already settled) and stay only as a fallback.
         */
        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: android.os.Bundle?, resultRequested: Boolean): android.os.Bundle? {
            when (action) {
                "android.wallpaper.wakingup" -> if (screenWasOff) { screenWasOff = false; onScreenOn() }
                "android.wallpaper.keyguardgoingaway" -> { goingAwaySeen = true; onUnlocked(exact = true) }
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            attached = false
            gl.detach()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                // Before the first frame: switch to the scheduled set and current conditions, so a change
                // that happened while the screen was off is already in place when it lights up.
                Repo.applySchedule()
                visibleNow = true
                renderer.setState(Repo.state.value)
                refreshEnv()
                refreshLock()
                sensor.start()
                renderer.snap()
                // Screen turned on (not just back from an app): the unlock motion.
                if (screenWasOff) { screenWasOff = false; onScreenOn() }
                gl.requestRender()
                main.removeCallbacks(tick)
                main.postDelayed(tick, Automation.msToNextMinute())
            } else {
                visibleNow = false
                main.removeCallbacks(lockPoll)
                sensor.stop()
                main.removeCallbacks(tick)
            }
        }

        override fun onDestroy() {
            applicationContext.getSystemService(PowerManager::class.java)?.removeThermalStatusListener(thermalListener)
            main.removeCallbacks(tick)
            runCatching { applicationContext.unregisterReceiver(powerReceiver) }
            sensor.stop()
            scope.cancel()
            gl.quit()
            super.onDestroy()
        }
    }

    companion object {
        fun component(context: Context) = ComponentName(context, TiltWallpaperService::class.java)

        /** System preview for our live wallpaper. */
        fun pickerIntent(context: Context): Intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Opens the system preview for our live wallpaper; the user picks home / lock / both there. */
        fun launchPicker(context: Context) {
            val direct = pickerIntent(context)
            try {
                context.startActivity(direct)
            } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        data class Status(val home: Boolean, val lock: Boolean?)

        fun status(context: Context): Status {
            val wm = WallpaperManager.getInstance(context)
            val pkg = context.packageName
            val home = wm.wallpaperInfo?.packageName == pkg
            val lock = if (Build.VERSION.SDK_INT >= 34) runCatching {
                // A null lock-screen info means the lock screen mirrors the home wallpaper.
                val info = wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK)
                if (info == null) home && wm.getWallpaperId(WallpaperManager.FLAG_LOCK) < 0 else info.packageName == pkg
            }.getOrNull() else null
            return Status(home, lock)
        }
    }
}
