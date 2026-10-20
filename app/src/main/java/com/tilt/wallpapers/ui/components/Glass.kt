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
import androidx.compose.ui.graphics.asComposeRenderEffect
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

/** "Độ mờ của kính": 1 frosted, 0 the clearest allowed. */
val LocalGlassFrost = staticCompositionLocalOf { 0.5f }

/**
 * The glass's backdrop (AGSL, Android 13+). Refraction: near the rim the picture behind bends inward and is
 * magnified, like the edge of a lens or a drop of water, with a hint of colour split and a highlight on the
 * edges facing the light. Contrast: wherever the picture behind would fight the text on the glass (bright under
 * white text on a dark theme, dark under black text on a light one), that spot alone is toned down / lifted
 * just enough to read; elsewhere the glass stays as clear as set.
 */
private const val REFRACTION = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float band;
uniform float strength;
uniform float adapt;
float sdRound(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
half4 main(float2 xy) {
    float2 c = size * 0.5;
    float2 p = xy - c;
    float d = -sdRound(p, c, radius);
    half4 col;
    if (d >= band || strength <= 0.0) {
        col = content.eval(xy);
    } else {
        float2 g = float2(sdRound(p + float2(1.0, 0.0), c, radius) - sdRound(p - float2(1.0, 0.0), c, radius),
                          sdRound(p + float2(0.0, 1.0), c, radius) - sdRound(p - float2(0.0, 1.0), c, radius));
        float2 n = normalize(g + float2(1e-5, 1e-5));
        float t = 1.0 - clamp(d / band, 0.0, 1.0);
        // A thick lens: the rim pulls the picture from well inside, strongly near the edge, with a clear
        // colour fringe.
        float k = pow(t, 1.6) * strength * band * 1.5;
        col = content.eval(xy - n * k);
        col.r = content.eval(xy - n * k * 1.2).r;
        col.b = content.eval(xy - n * k * 0.8).b;
        float spec = t * t * t * max(0.0, dot(n, normalize(float2(-1.0, -1.0)))) * 0.5 * strength;
        col.rgb += half3(spec) * col.a;
    }
    if (col.a > 0.0 && adapt != 0.0) {
        half3 rgb = col.rgb / col.a;
        float l = dot(rgb, half3(0.299, 0.587, 0.114));
        if (adapt > 0.0 && l > 0.5) {
            // Dark theme, white text: anything brighter than 0.5 is squeezed down toward it.
            float nl = 0.5 + (l - 0.5) * 0.25;
            rgb *= half(nl / l);
        } else if (adapt < 0.0 && l < 0.66) {
            // Light theme, black text: anything darker than 0.66 is lifted toward it.
            float nl = 0.66 - (0.66 - l) * 0.12;
            rgb = mix(rgb, half3(1.0), half((nl - l) / max(1.0 - l, 0.001)));
        }
        col.rgb = rgb * col.a;
    }
    return col;
}
"""

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
    // Frost 1 = the frosted glass as it always was; lower = less blur and tint, more bending at the rim. The
    // clearest still keeps some blur and tint: see-through glass over a busy picture can't be read.
    val frost = LocalGlassFrost.current.coerceIn(0f, 1f)
    val style = remember(tint, dark, frost) {
        // Without the contrast shader (before Android 13) the clearest glass keeps a heavier tint to stay readable.
        val lo = if (android.os.Build.VERSION.SDK_INT >= 33) (if (dark) 0.12f else 0.18f) else (if (dark) 0.32f else 0.38f)
        val a = lo + ((if (dark) 0.45f else 0.62f) - lo) * frost
        HazeStyle(
            backgroundColor = tint,
            tints = listOf(HazeTint(tint.copy(alpha = a))),
            blurRadius = (6f + 22f * frost).dp,
            noiseFactor = 0.05f * frost,
            fallbackTint = HazeTint(tint.copy(alpha = 0.94f)),
        )
    }
    val rimK = 1f + 0.6f * (1f - frost)
    val rim = remember(dark, rimK) {
        Brush.linearGradient(
            0f to Color.White.copy(alpha = ((if (dark) 0.34f else 1f) * rimK).coerceAtMost(1f)),
            0.45f to Color.White.copy(alpha = if (dark) 0.05f else 0.5f),
            1f to Color.White.copy(alpha = ((if (dark) 0.16f else 0.85f) * rimK).coerceAtMost(1f)),
        )
    }
    val sheen = Color.White.copy(alpha = (if (dark) 0.07f else 0.35f) * (0.6f + 0.4f * frost))
    val bend = 1f - frost
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
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        // The backdrop on its own layer, so only it bends (never the icons and text on the glass).
        Box(
            Modifier.matchParentSize()
                .then(if (state != null && android.os.Build.VERSION.SDK_INT >= 33) Modifier.refraction(shape, bend, if (dark) 1f else -1f) else Modifier)
                .then(if (state != null) Modifier.hazeEffect(state, style) else Modifier.background(tint.copy(alpha = 0.94f)))
                .drawBehind { drawRect(Brush.verticalGradient(0f to sheen, 0.55f to Color.Transparent)) },
        )
        Box(
            Modifier.matchParentSize()
                .then(if (dark) Modifier else Modifier.border(1.dp, hairline, shape).padding(1.dp))
                .border(1.dp, rim, shape),
        )
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/** Bends this layer's content at the rim of [shape] ([strength] 0..1) and keeps text readable ([adapt]: 1 dark theme, -1 light). Android 13+. */
@androidx.annotation.RequiresApi(33)
private fun Modifier.refraction(shape: Shape, strength: Float, adapt: Float): Modifier = composed {
    // A shader the device can't compile leaves the plain frosted glass rather than closing the app.
    val shader = remember { runCatching { android.graphics.RuntimeShader(REFRACTION) }.onFailure { android.util.Log.e("Glass", "shader", it) }.getOrNull() }
        ?: return@composed this
    val dir = androidx.compose.ui.platform.LocalLayoutDirection.current
    graphicsLayer {
        val w = size.width; val h = size.height
        if (w <= 0f || h <= 0f) return@graphicsLayer
        val r = when (val o = shape.createOutline(size, dir, this)) {
            is androidx.compose.ui.graphics.Outline.Rounded -> o.roundRect.topLeftCornerRadius.x
            else -> 0f
        }.coerceAtMost(minOf(w, h) / 2f)
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("radius", r)
        shader.setFloatUniform("band", minOf(minOf(w, h) * 0.5f, 40.dp.toPx()))
        // Square root: already plainly bent in the middle of the slider, not only at its clear end.
        shader.setFloatUniform("strength", kotlin.math.sqrt(strength.coerceIn(0f, 1f)))
        shader.setFloatUniform("adapt", adapt)
        renderEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
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
