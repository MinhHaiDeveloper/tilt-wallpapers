package com.tilt.wallpapers.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.launch

/** The blur source for glass surfaces drawn above the current screen. */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun isDarkUi() = MaterialTheme.colorScheme.surface.luminance() < 0.4f

/**
 * "Liquid glass": a frosted blur of whatever is behind, a thin specular rim that is brighter at the
 * top-left, and a soft inner sheen. On light themes white glass would vanish into a white page, so it
 * also floats on a soft shadow and gets a faint dark hairline outside the bright rim.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    state: HazeState? = LocalHaze.current,
    tint: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = isDarkUi()
    val style = remember(tint, dark) {
        HazeStyle(
            backgroundColor = tint,
            tints = listOf(HazeTint(tint.copy(alpha = if (dark) 0.45f else 0.62f))),
            blurRadius = 28.dp,
            noiseFactor = 0.05f,
            fallbackTint = HazeTint(tint.copy(alpha = 0.94f)),
        )
    }
    val rim = remember(dark) {
        Brush.linearGradient(
            0f to Color.White.copy(alpha = if (dark) 0.34f else 1f),
            0.45f to Color.White.copy(alpha = if (dark) 0.05f else 0.5f),
            1f to Color.White.copy(alpha = if (dark) 0.16f else 0.85f),
        )
    }
    val sheen = Color.White.copy(alpha = if (dark) 0.07f else 0.35f)
    val hairline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    Box(
        modifier
            .then(
                if (dark) Modifier
                else Modifier.shadow(
                    elevation = 10.dp, shape = shape, clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.16f),
                )
            )
            .clip(shape)
            .then(if (state != null) Modifier.hazeEffect(state, style) else Modifier.background(tint.copy(alpha = 0.94f)))
            .drawBehind { drawRect(Brush.verticalGradient(0f to sheen, 0.55f to Color.Transparent)) }
            .then(if (dark) Modifier else Modifier.border(1.dp, hairline, shape).padding(1.dp))
            .border(1.dp, rim, shape),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/** Round glass button with a liquid squash on press. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
    state: HazeState? = LocalHaze.current,
    onClick: () -> Unit,
) {
    GlassSurface(
        modifier = modifier.size(size).bounceClick(enabled = enabled, squash = true, onClick = onClick),
        shape = CircleShape,
        state = state,
    ) {
        Icon(icon, contentDescription, tint = tint.copy(alpha = if (enabled) 1f else 0.38f), modifier = Modifier.size(size * 0.46f))
    }
}

/**
 * Springy press feedback for anything tappable: shrinks while held (or squashes like liquid),
 * then overshoots back on release. Always plays a full bounce even for very quick taps.
 */
fun Modifier.bounceClick(
    enabled: Boolean = true,
    pressedScale: Float = 0.9f,
    squash: Boolean = false,
    rippleShape: Shape? = null,
    haptic: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val hf = LocalHapticFeedback.current
    this
        .bounce(interaction, pressedScale, squash)
        .then(if (rippleShape != null) Modifier.clip(rippleShape) else Modifier)
        .combinedClickable(
            interactionSource = interaction,
            indication = if (rippleShape != null) ripple() else null,
            enabled = enabled,
            onLongClick = onLongClick?.let { lc -> { if (haptic) hf.performHapticFeedback(HapticFeedbackType.LongPress); lc() } },
            onClick = { if (haptic) hf.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() },
        )
}

/** Press animation driven by an existing interaction source (e.g. a Material button's). */
fun Modifier.bounce(interaction: InteractionSource, pressedScale: Float = 0.92f, squash: Boolean = false): Modifier = composed {
    val press = remember { Animatable(0f) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> launch { press.animateTo(1f, spring(stiffness = Spring.StiffnessMedium)) }
                is PressInteraction.Release, is PressInteraction.Cancel -> launch {
                    if (press.value < 0.7f) press.animateTo(1f, tween(70))
                    press.animateTo(0f, spring(dampingRatio = 0.38f, stiffness = 480f))
                }
            }
        }
    }
    graphicsLayer {
        val p = press.value
        if (squash) {
            scaleX = 1f + (1f - pressedScale) * 0.6f * p
            scaleY = 1f - (1f - pressedScale) * p
        } else {
            scaleX = 1f - (1f - pressedScale) * p
            scaleY = scaleX
        }
    }
}

enum class BtnStyle { Filled, Tonal, Outlined, Text, Danger }

/** Material 3 button with the app's bounce + haptic tick. */
@Composable
fun AppButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: BtnStyle = BtnStyle.Filled,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hf = LocalHapticFeedback.current
    val click = { hf.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() }
    val m = modifier.bounce(interaction)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, maxLines = 1)
    }
    val cs = MaterialTheme.colorScheme
    when (style) {
        BtnStyle.Filled -> Button(click, m, enabled, interactionSource = interaction, content = content)
        BtnStyle.Tonal -> FilledTonalButton(click, m, enabled, interactionSource = interaction, content = content)
        BtnStyle.Outlined -> OutlinedButton(click, m, enabled, interactionSource = interaction, content = content)
        BtnStyle.Text -> TextButton(click, m, enabled, interactionSource = interaction, content = content)
        BtnStyle.Danger -> Button(
            click, m, enabled, interactionSource = interaction,
            colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError), content = content,
        )
    }
}

/**
 * Tap / long-press / long-press-then-drag on one element, cooperating with parent scrolling.
 * [onLongPressEnd] receives whether the finger moved (a drag) or not (a plain long press).
 */
@Composable
fun Modifier.pressGestures(
    key: Any?,
    pressed: MutableState<Boolean>,
    onTap: () -> Unit,
    onLongPressStart: () -> Unit = {},
    onDrag: (Offset) -> Unit = {},
    onLongPressEnd: (moved: Boolean) -> Unit,
): Modifier {
    // The gesture loop lives as long as [key]; read the callbacks fresh so a tap after entering
    // selection mode selects instead of running the handler from before (which opened the set).
    val tap = androidx.compose.runtime.rememberUpdatedState(onTap)
    val longStart = androidx.compose.runtime.rememberUpdatedState(onLongPressStart)
    val dragCb = androidx.compose.runtime.rememberUpdatedState(onDrag)
    val longEnd = androidx.compose.runtime.rememberUpdatedState(onLongPressEnd)
    return pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        pressed.value = true
        val tapped: Boolean? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                if (change.changedToUp()) return@withTimeoutOrNull true
                if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    return@withTimeoutOrNull false
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        when (tapped) {
            true -> { pressed.value = false; tap.value() }
            false -> pressed.value = false
            null -> {
                longStart.value()
                var travelled = 0f
                drag(down.id) { change ->
                    val d = change.positionChange()
                    travelled += d.getDistance()
                    dragCb.value(d)
                    change.consume()
                }
                pressed.value = false
                longEnd.value(travelled > viewConfiguration.touchSlop)
            }
        }
    }
    }
}
