package com.tilt.wallpapers.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.tilt.wallpapers.data.AppState
import com.tilt.wallpapers.data.ColorSource
import com.tilt.wallpapers.data.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF1F5FD6), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E3FF), onPrimaryContainer = Color(0xFF001A43),
    secondary = Color(0xFFB0643A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCC8), onSecondaryContainer = Color(0xFF341100),
    tertiary = Color(0xFF6A53A8),
    background = Color(0xFFF7F8FC), onBackground = Color(0xFF14171D),
    surface = Color(0xFFF7F8FC), onSurface = Color(0xFF14171D),
    surfaceVariant = Color(0xFFE1E4EE), onSurfaceVariant = Color(0xFF444852),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F3F9),
    surfaceContainer = Color(0xFFEBEEF5), surfaceContainerHigh = Color(0xFFE5E8F0),
    surfaceContainerHighest = Color(0xFFDFE2EB), outline = Color(0xFF757A86), outlineVariant = Color(0xFFC5C8D3),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAFC6FF), onPrimary = Color(0xFF002D6C),
    primaryContainer = Color(0xFF0E449E), onPrimaryContainer = Color(0xFFD9E3FF),
    secondary = Color(0xFFFFB68D), onSecondary = Color(0xFF532202),
    secondaryContainer = Color(0xFF713716), onSecondaryContainer = Color(0xFFFFDCC8),
    tertiary = Color(0xFFCDBDFF),
    background = Color(0xFF0E1116), onBackground = Color(0xFFE3E5EC),
    surface = Color(0xFF0E1116), onSurface = Color(0xFFE3E5EC),
    surfaceVariant = Color(0xFF2E323B), onSurfaceVariant = Color(0xFFC4C7D1),
    surfaceContainerLowest = Color(0xFF090B0F), surfaceContainerLow = Color(0xFF151920),
    surfaceContainer = Color(0xFF1A1E25), surfaceContainerHigh = Color(0xFF242830),
    surfaceContainerHighest = Color(0xFF2F333B), outline = Color(0xFF8E929C), outlineVariant = Color(0xFF444852),
)

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

val CaptionStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)

@Composable
fun isDarkTheme(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** The scheme the app uses for [state]: from the set's picture, the system wallpaper, custom seeds, or built-in. */
fun schemeFor(state: AppState, context: android.content.Context, dark: Boolean): ColorScheme =
    when (state.settings.colorSource) {
        ColorSource.SYSTEM -> SeedScheme.systemScheme(context, dark)
        else -> SeedScheme.seedsFor(state, context)?.let { SeedScheme.scheme(it, dark, SeedScheme.harmonyFor(state)) }
    } ?: if (dark) Dark else Light

@Composable
fun TiltTheme(state: AppState, content: @Composable () -> Unit) {
    val dark = isDarkTheme(state.settings.theme)
    val context = LocalContext.current
    val seedsKey = SeedScheme.seedsFor(state, context)
    val target = remember(state.settings.colorSource, seedsKey, dark) { schemeFor(state, context, dark) }
    val scheme = animateScheme(target)
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        // Also used inside the quick-settings dialog, which has no activity window to style.
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialExpressiveTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes) {
        // Screens are not wrapped in a Surface, so give default text/icons the theme colour.
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}
