package com.tilt.wallpapers.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.tilt.wallpapers.data.AppState
import com.tilt.wallpapers.data.ColorExtract
import com.tilt.wallpapers.data.ColorSource
import com.tilt.wallpapers.data.Repo
import kotlin.math.max
import kotlin.math.min

/**
 * Material 3 colour schemes from up to three seed colours (primary, secondary, tertiary). Every role
 * uses a fixed lightness (tone) for light or dark mode, so a white picture gives soft greyish colours,
 * never a light theme in dark mode, and a near-black one never a dark theme in light mode.
 */
object SeedScheme {

    /** The three seeds behind [scheme] roles: fills in missing ones the way Material's tonal spot does. */
    fun complete(seeds: List<Int>): List<Int> {
        val p = seeds.firstOrNull() ?: return emptyList()
        val c = ColorExtract.chroma(p); val h = ColorExtract.hue(p); val t = ColorExtract.tone(p)
        val s = seeds.getOrNull(1) ?: ColorExtract.fromHct(h, min(c, 24f), t)
        val te = seeds.getOrNull(2) ?: ColorExtract.fromHct((h + 60) % 360, min(c, 32f), t)
        return listOf(p, s, te)
    }

    /** How strongly secondary colours are pulled toward the primary's hue. */
    class Harmony(val factor: Float, val maxDegrees: Float)
    /** Colours picked from a picture: they only need to fit the picture's main colour. */
    val STRONG = Harmony(0.5f, 15f)
    /** Colours the user chose: keep them recognisable, just blend them a little. */
    val LIGHT = Harmony(0.25f, 8f)

    /** Below this chroma a colour reads as grey and its hue is just noise. */
    private const val GREY_CHROMA = 12f

    fun harmonyFor(state: AppState) = if (state.settings.colorSource == ColorSource.CUSTOM) LIGHT else STRONG

    /**
     * The three colours the theme is really built from: [complete], then the secondaries blended toward
     * the primary. Near-grey secondaries take the primary's hue (a cool grey next to a warm primary would
     * otherwise show up as a stray blue); colourful ones turn toward it by up to [Harmony.maxDegrees].
     */
    fun effective(seeds: List<Int>, harmony: Harmony): List<Int> {
        val all = complete(seeds)
        if (all.isEmpty()) return all
        val ph = ColorExtract.hue(all[0])
        return listOf(all[0]) + all.drop(1).map { c ->
            val chroma = ColorExtract.chroma(c); val tone = ColorExtract.tone(c)
            if (chroma < GREY_CHROMA) ColorExtract.fromHct(ph, chroma, tone)
            else {
                val h = ColorExtract.hue(c)
                val diff = ((ph - h + 540f) % 360f) - 180f          // signed shortest turn toward the primary
                val turn = kotlin.math.sign(diff) * min(kotlin.math.abs(diff) * harmony.factor, harmony.maxDegrees)
                ColorExtract.fromHct((h + turn + 360f) % 360f, chroma, tone)
            }
        }
    }

    fun scheme(seeds: List<Int>, dark: Boolean, harmony: Harmony = STRONG): ColorScheme {
        val (p, s, t) = effective(seeds, harmony)
        // Keep picked colours vivid enough to read as accents; leave genuinely grey seeds grey.
        fun accent(seed: Int, min: Float): (Float) -> Color {
            val c = ColorExtract.chroma(seed).let { if (it < GREY_CHROMA) it else max(it, min) }
            val h = ColorExtract.hue(seed)
            return { tone -> Color(ColorExtract.fromHct(h, c, tone)) }
        }
        val pc = ColorExtract.chroma(p); val ph = ColorExtract.hue(p)
        val P = accent(p, 36f); val S = accent(s, 20f); val T = accent(t, 24f)
        val N: (Float) -> Color = { tone -> Color(ColorExtract.fromHct(ph, min(pc / 8f, 5f), tone)) }
        val NV: (Float) -> Color = { tone -> Color(ColorExtract.fromHct(ph, min(pc / 5f, 9f), tone)) }
        return if (dark) darkColorScheme(
            primary = P(80f), onPrimary = P(20f), primaryContainer = P(30f), onPrimaryContainer = P(90f), inversePrimary = P(40f),
            secondary = S(80f), onSecondary = S(20f), secondaryContainer = S(30f), onSecondaryContainer = S(90f),
            tertiary = T(80f), onTertiary = T(20f), tertiaryContainer = T(30f), onTertiaryContainer = T(90f),
            background = N(6f), onBackground = N(90f), surface = N(6f), onSurface = N(90f),
            surfaceVariant = NV(30f), onSurfaceVariant = NV(80f), surfaceTint = P(80f),
            inverseSurface = N(90f), inverseOnSurface = N(20f), outline = NV(60f), outlineVariant = NV(30f),
            surfaceBright = N(24f), surfaceDim = N(6f), surfaceContainerLowest = N(4f), surfaceContainerLow = N(10f),
            surfaceContainer = N(12f), surfaceContainerHigh = N(17f), surfaceContainerHighest = N(22f),
        ) else lightColorScheme(
            primary = P(40f), onPrimary = P(100f), primaryContainer = P(90f), onPrimaryContainer = P(10f), inversePrimary = P(80f),
            secondary = S(40f), onSecondary = S(100f), secondaryContainer = S(90f), onSecondaryContainer = S(10f),
            tertiary = T(40f), onTertiary = T(100f), tertiaryContainer = T(90f), onTertiaryContainer = T(10f),
            background = N(98f), onBackground = N(10f), surface = N(98f), onSurface = N(10f),
            surfaceVariant = NV(90f), onSurfaceVariant = NV(30f), surfaceTint = P(40f),
            inverseSurface = N(20f), inverseOnSurface = N(95f), outline = NV(50f), outlineVariant = NV(80f),
            surfaceBright = N(98f), surfaceDim = N(87f), surfaceContainerLowest = N(100f), surfaceContainerLow = N(96f),
            surfaceContainer = N(94f), surfaceContainerHigh = N(92f), surfaceContainerHighest = N(90f),
        )
    }

    /** Seeds for the current settings, or null for the app's built-in colours. */
    fun seedsFor(state: AppState, context: Context): List<Int>? = when (state.settings.colorSource) {
        ColorSource.SET -> Repo.centerImage(state)?.palette?.take(3)?.takeIf { it.isNotEmpty() }
        ColorSource.CUSTOM -> state.settings.customColors.takeIf { it.isNotEmpty() }
        ColorSource.SYSTEM -> if (Build.VERSION.SDK_INT >= 31) systemSeeds(context) else null
    }

    /** Material You (wallpaper) colours expressed as seeds, so they can be shown and edited like the others. */
    fun systemSeeds(context: Context): List<Int> {
        val s = if (Build.VERSION.SDK_INT >= 31) dynamicLightColorScheme(context) else return emptyList()
        return listOf(s.primary.toArgb(), s.secondary.toArgb(), s.tertiary.toArgb())
    }

    fun systemScheme(context: Context, dark: Boolean): ColorScheme? =
        if (Build.VERSION.SDK_INT >= 31) (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)) else null
}

/** Cross-fades every role so a new set or colour choice glides in instead of snapping. */
@Composable
fun animateScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(450)
    @Composable fun a(c: Color) = animateColorAsState(c, spec, label = "scheme").value
    return target.copy(
        primary = a(target.primary), onPrimary = a(target.onPrimary),
        primaryContainer = a(target.primaryContainer), onPrimaryContainer = a(target.onPrimaryContainer),
        inversePrimary = a(target.inversePrimary),
        secondary = a(target.secondary), onSecondary = a(target.onSecondary),
        secondaryContainer = a(target.secondaryContainer), onSecondaryContainer = a(target.onSecondaryContainer),
        tertiary = a(target.tertiary), onTertiary = a(target.onTertiary),
        tertiaryContainer = a(target.tertiaryContainer), onTertiaryContainer = a(target.onTertiaryContainer),
        background = a(target.background), onBackground = a(target.onBackground),
        surface = a(target.surface), onSurface = a(target.onSurface),
        surfaceVariant = a(target.surfaceVariant), onSurfaceVariant = a(target.onSurfaceVariant),
        outline = a(target.outline), outlineVariant = a(target.outlineVariant),
        surfaceContainerLowest = a(target.surfaceContainerLowest), surfaceContainerLow = a(target.surfaceContainerLow),
        surfaceContainer = a(target.surfaceContainer), surfaceContainerHigh = a(target.surfaceContainerHigh),
        surfaceContainerHighest = a(target.surfaceContainerHighest),
        inverseSurface = a(target.inverseSurface), inverseOnSurface = a(target.inverseOnSurface),
    )
}
