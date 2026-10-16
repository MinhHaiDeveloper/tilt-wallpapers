package com.tilt.wallpapers.tile

import com.tilt.wallpapers.data.t

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.tilt.wallpapers.R
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.ui.quick.QuickPanelHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Long-press menu on the app icon: "Đổi bộ ảnh" (the quick panel), "Ảnh tiếp", "Khóa / Bỏ khóa nghiêng".
 * Dynamic shortcuts so the lock label follows the current state, also on shortcuts pinned to the home screen.
 */
object Shortcuts {
    const val ACTION_PICK = "com.tilt.wallpapers.action.PICK_SET"
    const val ACTION_NEXT = "com.tilt.wallpapers.action.NEXT_IMAGE"
    const val ACTION_LOCK = "com.tilt.wallpapers.action.TOGGLE_LOCK"

    fun start(context: Context) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            // Labels follow the lock state and the app language.
            kotlinx.coroutines.flow.combine(
                Repo.state.map { it.settings.tiltLock }.distinctUntilChanged(),
                androidx.compose.runtime.snapshotFlow { com.tilt.wallpapers.data.I18n.lang },
            ) { locked, _ -> locked }.collect { locked ->
                runCatching { publish(app, locked) }
                TiltTileService.requestUpdate(app)
            }
        }
    }

    private fun publish(context: Context, locked: Boolean) {
        fun item(id: String, action: String, short: String, long: String, rank: Int) =
            ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(short).setLongLabel(long).setRank(rank)
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut))
                .setIntent(Intent(action).setClass(context, ShortcutActivity::class.java))
                .build()
        val list = listOf(
            item("pick", ACTION_PICK, t("Đổi bộ ảnh"), t("Đổi bộ ảnh"), 0),
            item("next", ACTION_NEXT, t("Ảnh tiếp"), t("Sang ảnh tiếp"), 1),
            item("lock", ACTION_LOCK, if (locked) t("Bỏ khóa nghiêng") else t("Khóa nghiêng"), if (locked) t("Bỏ khóa nghiêng") else t("Khóa nghiêng"), 2),
        )
        ShortcutManagerCompat.setDynamicShortcuts(context, list)
        ShortcutManagerCompat.updateShortcuts(context, list)   // keeps pinned copies' labels in step
    }
}

/** Translucent host for shortcuts: quick actions finish at once, "Đổi bộ ảnh" shows the quick panel. */
class ShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Repo.init(applicationContext)
        when (intent?.action) {
            Shortcuts.ACTION_NEXT -> {
                if (Repo.state.value.activeSet == null) toast(t("Chưa có bộ ảnh")) else { Repo.nextImage(); toast(t("Đã sang ảnh tiếp")) }
                finish()
            }
            Shortcuts.ACTION_LOCK -> {
                val on = !Repo.state.value.settings.tiltLock
                Repo.setTiltLock(on)
                toast(if (on) t("Đã khóa nghiêng") else t("Đã bỏ khóa nghiêng"))
                finish()
            }
            else -> setContent {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                        .clickable(remember { MutableInteractionSource() }, null) { finish() }
                        .navigationBarsPadding(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(Modifier.clickable(remember { MutableInteractionSource() }, null) {}) {
                        QuickPanelHost(onDismiss = { finish() }, launch = { startActivity(it) })
                    }
                }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
}
