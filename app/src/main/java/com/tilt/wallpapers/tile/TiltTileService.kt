package com.tilt.wallpapers.tile

import com.tilt.wallpapers.data.t

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.tilt.wallpapers.R
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.ui.quick.QuickPanelHost
import com.tilt.wallpapers.wallpaper.TiltWallpaperService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The "Tilt" quick-settings tile. An action tile (like screen record), not a toggle: tapping opens the
 * quick panel over the notification shade; long-press opens the app (QS_TILE_PREFERENCES on MainActivity).
 */
class TiltTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        Repo.init(applicationContext)
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
            s.launch {
                Repo.state.map { it.activeSet?.name to it.settings.tiltLock }.distinctUntilChanged().collect { refresh() }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel(); scope = null
        super.onStopListening()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val state = Repo.state.value
        tile.label = "Tilt"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.state = if (TiltWallpaperService.status(this).home) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val name = state.activeSet?.name ?: t("Chưa có bộ ảnh")
        val sub = if (state.settings.tiltLock) t("%1\$s · Đã khóa", name) else name
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = sub
        tile.contentDescription = "Tilt, $sub"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        Repo.init(applicationContext)
        // A full-screen, see-through window from the very start: the panel is centred by Compose, so the window
        // never resizes. (A normal dialog window opens narrow and is widened after showing, which made the panel
        // jump in from the side.)
        val dialog = ComponentDialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.32f)
            setWindowAnimations(0)   // the panel animates itself (zoom)
            // Draw under the status / navigation bars and the cutout, so the dim and blur cover the whole screen
            // instead of leaving a black status-bar strip.
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this, false)
            @Suppress("DEPRECATION")
            run { statusBarColor = Color.TRANSPARENT; navigationBarColor = Color.TRANSPARENT }
            if (Build.VERSION.SDK_INT >= 29) isNavigationBarContrastEnforced = false
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            if (Build.VERSION.SDK_INT >= 30) attributes = attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                // Dialog windows shrink to stay clear of the system bars by default; cover them instead.
                fitInsetsTypes = 0
                fitInsetsSides = 0
            } else attributes = attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // Android 12+: blur what is behind (the wallpaper) where the phone allows cross-window blur.
            if (Build.VERSION.SDK_INT >= 31) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes = attributes.apply { blurBehindRadius = (28 * resources.displayMetrics.density).toInt() }
            }
        }
        dialog.setContentView(ComposeView(this).apply {
            setContent {
                // Tapping outside the panel closes it, like a dialog.
                Box(
                    Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { dialog.dismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.clickable(remember { MutableInteractionSource() }, null) {}) {
                        QuickPanelHost(onDismiss = { dialog.dismiss() }, launch = ::launch)
                    }
                }
            }
        })
        showDialog(dialog)
    }

    /** Starts an activity from the tile and collapses the shade (PendingIntent form required on 14+). */
    private fun launch(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun component(context: Context) = ComponentName(context, TiltTileService::class.java)

        /** Asks the system to refresh the tile (after a change made elsewhere). */
        fun requestUpdate(context: Context) = runCatching { requestListeningState(context, component(context)) }
    }
}
