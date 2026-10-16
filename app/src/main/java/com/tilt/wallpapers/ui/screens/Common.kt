package com.tilt.wallpapers.ui.screens

import com.tilt.wallpapers.data.t

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.SheetState
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tilt.wallpapers.data.Side
import com.tilt.wallpapers.data.WallImage
import com.tilt.wallpapers.data.WallSet
import com.tilt.wallpapers.ui.LocalNav
import com.tilt.wallpapers.ui.components.GlassIconButton
import com.tilt.wallpapers.ui.components.GlassSurface
import com.tilt.wallpapers.ui.components.bounceClick
import com.tilt.wallpapers.wallpaper.TiltWallpaperService

@Composable
fun statusTop(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

@Composable
fun navBottom(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

@Composable
fun rememberWallpaperStatus(): TiltWallpaperService.Companion.Status {
    val context = LocalContext.current
    var status by remember { mutableStateOf(TiltWallpaperService.status(context)) }
    LifecycleResumeEffect(Unit) {
        status = TiltWallpaperService.status(context)
        onPauseOrDispose { }
    }
    return status
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier, horizontalPadding: Dp = 20.dp) {
    Column(modifier.padding(horizontal = horizontalPadding, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 8.dp),
        content = content,
    )
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, icon: ImageVector? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val hf = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .bounceClick(enabled = enabled, pressedScale = 0.97f, haptic = false) {
                hf.performHapticFeedback(if (!checked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onChange(!checked)
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = {
                hf.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onChange(it)
            },
            thumbContent = if (checked) {
                { Icon(Icons.Filled.Check, null, Modifier.size(SwitchDefaults.IconSize)) }
            } else null,
        )
    }
}

@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    enabled: Boolean = true,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    val hf = LocalHapticFeedback.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = { onChange(it) },
            onValueChangeFinished = { hf.performHapticFeedback(HapticFeedbackType.GestureEnd) },
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

/** Floating glass top bar: back button, centred title pill, trailing actions. */
@Composable
fun GlassTopBar(title: String?, onBack: (() -> Unit)?, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, t("Quay lại"), onClick = onBack)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (title != null) GlassSurface(Modifier.height(40.dp), shape = CircleShape) {
                Text(
                    title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
        }
        actions()
    }
}

/** A filled-icon chip used for counts and positions. */
@Composable
fun Pill(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, selected: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(CircleShape)
            .background(if (selected) cs.primary else cs.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (selected) cs.onPrimary else cs.onSecondaryContainer
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = c)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = c)
    }
}

fun positionLabel(set: WallSet, img: WallImage): String = when (img.side) {
    Side.CENTER -> t("Giữ thẳng · ảnh giữa")
    Side.LEFT -> t("Nghiêng trái · bậc %1\$s", set.side(Side.LEFT).indexOfFirst { it.id == img.id } + 1)
    Side.RIGHT -> t("Nghiêng phải · bậc %1\$s", set.side(Side.RIGHT).indexOfFirst { it.id == img.id } + 1)
}

/** Scale used for selected/pressed cards. */
@Composable
fun cardScale(pressed: Boolean, selected: Boolean): Float {
    val s by animateFloatAsState(
        when { pressed -> 0.94f; selected -> 0.92f; else -> 1f },
        spring(dampingRatio = 0.5f, stiffness = 500f), label = "cardScale",
    )
    return s
}

val ContentPaddingBottom = PaddingValues(bottom = 140.dp)

fun Modifier.scaled(s: Float) = graphicsLayer { scaleX = s; scaleY = s }

@Composable
fun BackOnly() {
    val nav = LocalNav.current
    GlassTopBar(null, onBack = { nav.pop() })
}


/**
 * A bottom sheet that closes with its own motion: it slides down, fades and the dim behind it clears, then
 * [onDismiss] runs. Every way of closing (a button, Back, a tap outside, dragging it down) goes through
 * [close]; the library's own hide jumped straight to gone here.
 */
class SheetCloser(
    val state: SheetState,
    val close: () -> Unit,
    private val exit: State<Float>,
) {
    /** Fade for the sheet's content. */
    val alpha: State<Float> = androidx.compose.runtime.derivedStateOf { 1f - exit.value }
    /** Put on the ModalBottomSheet: slides the whole sheet down while closing. */
    val modifier: Modifier = Modifier.graphicsLayer { translationY = exit.value * size.height }
    val scrim: androidx.compose.ui.graphics.Color
        @Composable get() = androidx.compose.material3.BottomSheetDefaults.ScrimColor.let { it.copy(alpha = it.alpha * (1f - exit.value)) }
}

@Composable
fun rememberSheetCloser(onDismiss: () -> Unit): SheetCloser {
    val scope = rememberCoroutineScope()
    val done by rememberUpdatedState(onDismiss)
    val exit = remember { androidx.compose.animation.core.Animatable(0f) }
    val closing = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val close: () -> Unit = remember {
        {
            if (closing.compareAndSet(false, true)) scope.launch {
                exit.animateTo(1f, androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                done()
            }
        }
    }
    val state = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // Back, a tap outside or a drag ask to hide: play our close instead.
        confirmValueChange = { v -> if (v == androidx.compose.material3.SheetValue.Hidden && !closing.get()) { close(); false } else true },
    )
    return remember(state) { SheetCloser(state, close, exit.asState()) }
}
