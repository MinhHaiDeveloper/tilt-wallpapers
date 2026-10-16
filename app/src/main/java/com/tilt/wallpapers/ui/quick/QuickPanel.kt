package com.tilt.wallpapers.ui.quick

import com.tilt.wallpapers.data.t

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tilt.wallpapers.MainActivity
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.WallSet
import com.tilt.wallpapers.ui.components.AppButton
import com.tilt.wallpapers.ui.components.BtnStyle
import com.tilt.wallpapers.ui.components.EditedImage
import com.tilt.wallpapers.ui.components.bounce
import com.tilt.wallpapers.ui.components.bounceClick
import com.tilt.wallpapers.ui.components.screenAspect
import com.tilt.wallpapers.ui.theme.TiltTheme
import com.tilt.wallpapers.wallpaper.TiltWallpaperService

/** Intent that opens the app itself. */
fun appIntent(context: Context): Intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * The quick panel behind the "Tilt" tile and the "Đổi bộ ảnh" shortcut: pick a set, lock tilt, next
 * picture, open the app. [launch] starts an activity in a way the host allows (tile vs activity).
 */
@Composable
fun QuickPanelHost(onDismiss: () -> Unit, launch: (Intent) -> Unit) {
    val state by Repo.state.collectAsStateWithLifecycle()
    // Zooms out from the centre with a little bounce; closing shrinks it first, then the window goes.
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    var closing by remember { mutableStateOf<(() -> Unit)?>(null) }
    val close: (() -> Unit) -> Unit = { after -> if (closing == null) { closing = after; shown.targetState = false } }
    LaunchedEffect(shown.isIdle, shown.currentState) {
        if (shown.isIdle && !shown.currentState) closing?.invoke()
    }
    TiltTheme(state) {
        AnimatedVisibility(
            visibleState = shown,
            enter = scaleIn(spring(dampingRatio = 0.72f, stiffness = 420f), initialScale = 0.86f) + fadeIn(tween(160)),
            exit = scaleOut(tween(140), targetScale = 0.92f) + fadeOut(tween(140)),
        ) {
            QuickPanel(
                onDismiss = { close(onDismiss) },
                launch = { intent -> close { launch(intent); onDismiss() } },
            )
        }
    }
}

@Composable
private fun QuickPanel(onDismiss: () -> Unit, launch: (Intent) -> Unit) {
    val context = LocalContext.current
    val hf = LocalHapticFeedback.current
    val state by Repo.state.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val active = state.activeSet
    // The set in use first, the rest in their usual order.
    val sets = listOfNotNull(active) + state.sets.filter { it.id != active?.id }
    val isOurs = remember { TiltWallpaperService.status(context).home }
    val shape = RoundedCornerShape(32.dp)

    Column(
        Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(16.dp)
            .clip(shape).background(cs.surfaceContainer).border(1.dp, cs.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tilt", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            // Solid red so it reads on any panel colour.
            val closeInteraction = remember { MutableInteractionSource() }
            FilledIconButton(
                onClick = { hf.performHapticFeedback(HapticFeedbackType.ContextClick); onDismiss() },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = cs.error, contentColor = cs.onError),
                interactionSource = closeInteraction,
                modifier = Modifier.size(40.dp).bounce(closeInteraction, 0.86f),
            ) { Icon(Icons.Filled.Close, t("Đóng"), Modifier.size(20.dp)) }
        }
        if (!isOurs) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                AppButton(t("Đặt Tilt làm hình nền"), icon = Icons.Filled.Wallpaper, modifier = Modifier.fillMaxWidth()) {
                    launch(TiltWallpaperService.pickerIntent(context))
                }
            }
        }
        if (sets.isEmpty()) {
            Text(
                t("Chưa có bộ ảnh nào. Mở app để tạo bộ đầu tiên."),
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                items(sets, key = { it.id }) { set ->
                    SetRow(set, set.id == active?.id) {
                        hf.performHapticFeedback(HapticFeedbackType.Confirm)
                        Repo.setActive(set.id)
                        onDismiss()
                    }
                }
            }
        }
        Spacer(Modifier.size(4.dp))
        Row(
            Modifier.fillMaxWidth().bounceClick(pressedScale = 0.97f, haptic = false) {
                hf.performHapticFeedback(if (state.settings.tiltLock) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                Repo.setTiltLock(!state.settings.tiltLock)
            }.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Lock, null, tint = cs.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Khóa nghiêng"), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.settings.tiltLock) t("Hình nền đứng yên ở ảnh hiện tại") else t("Nghiêng máy để đổi ảnh"),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
            Switch(state.settings.tiltLock, { Repo.setTiltLock(it) })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(t("Ảnh tiếp"), icon = Icons.AutoMirrored.Filled.ArrowForward, style = BtnStyle.Tonal, modifier = Modifier.weight(1f), enabled = active != null) {
                Repo.nextImage()
            }
            AppButton(t("Mở app"), icon = Icons.Filled.OpenInNew, style = BtnStyle.Outlined, modifier = Modifier.weight(1f)) {
                launch(appIntent(context))
            }
        }
    }
}

@Composable
private fun SetRow(set: WallSet, current: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val aspect = screenAspect()
    Row(
        Modifier.fillMaxWidth().bounceClick(pressedScale = 0.97f, haptic = false, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (current) cs.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (seq, neutral) = set.tiltSequence()
        Box(Modifier.width(28.dp).aspectRatio(aspect).clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerHighest)) {
            seq.getOrNull(neutral)?.let { EditedImage(it, Modifier.matchParentSize()) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(set.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t("%1\$s ảnh", set.images.size), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (current) Icon(Icons.Filled.CheckCircle, t("Đang dùng"), Modifier.size(22.dp).clip(CircleShape), tint = cs.primary)
    }
}
