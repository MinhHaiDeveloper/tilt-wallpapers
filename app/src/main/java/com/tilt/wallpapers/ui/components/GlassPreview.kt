package com.tilt.wallpapers.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tilt.wallpapers.data.Automation
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.WallImage
import com.tilt.wallpapers.data.t
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt

/**
 * A live sample of the app's glass for the "Độ mờ của kính" slider: a glass bar (the real [GlassSurface]) over a
 * backdrop that drifts back and forth, so the frost, the bending at the rim and the contrast keeping (bright and
 * dark areas both) all show. Backdrop: a pattern made for a small strip, or the active set's middle picture
 * scrolling top to bottom. Animates only while on screen; still with the phone's "remove animations".
 */
@Composable
fun GlassPreview(image: WallImage?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var usePhoto by rememberSaveable { mutableStateOf(false) }
    val photo = image?.takeIf { usePhoto }
    val still = remember { Automation.reduceMotion(context) }
    val drift by rememberInfiniteTransition(label = "glassPreview").animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (photo != null) 9000 else 6000, easing = LinearEasing), RepeatMode.Reverse), label = "drift",
    )
    val k = if (still) 0.5f else drift
    val haze = rememberHazeState()
    val bmp = photo?.let { rememberBitmap(Repo.imageFile(it.id), 1080) }
    Box(modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(24.dp))) {
        Canvas(Modifier.fillMaxSize().hazeSource(haze)) {
            val b = bmp
            if (photo != null && b != null) {
                // Fit the width, scroll down the picture and back.
                val scale = size.width / b.width
                val h = b.height * scale
                val y = -(h - size.height).coerceAtLeast(0f) * k
                drawImage(b, srcOffset = IntOffset.Zero, srcSize = IntSize(b.width, b.height),
                    dstOffset = IntOffset(0, y.roundToInt()), dstSize = IntSize(size.width.roundToInt(), h.roundToInt()))
            } else translate(left = -size.width * 0.6f * k) { pattern(size.width * 1.6f, size.height) }
        }
        GlassSurface(Modifier.align(Alignment.Center).padding(horizontal = 20.dp).fillMaxWidth().height(64.dp), shape = CircleShape, state = haze) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                listOf(Icons.Filled.Collections to t("Bộ ảnh"), Icons.Filled.AutoAwesome to t("Hiệu ứng"), Icons.Filled.Settings to t("Cài đặt")).forEach { (icon, label) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, null, Modifier.size(22.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        // Which backdrop: the pattern or the user's picture.
        if (image != null) PreviewPill("", if (usePhoto) Icons.Filled.Palette else Icons.Filled.Image,
            Modifier.align(Alignment.TopEnd).padding(8.dp)) { usePhoto = !usePhoto }
    }
}

/** White to warm to deep navy, coloured discs and vertical stripes: every kind of backdrop in a small strip. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.pattern(w: Float, h: Float) {
    drawRect(Brush.horizontalGradient(
        0f to Color(0xFFFFFFFF), 0.2f to Color(0xFFFFF1C4), 0.35f to Color(0xFFFFE08A),
        0.6f to Color(0xFF3A3F8F), 0.8f to Color(0xFF1B1E4A), 1f to Color(0xFF0B0B1A), endX = w,
    ), size = Size(w, h))
    drawCircle(Color(0xFFFF6B9A), radius = h * 0.55f, center = Offset(w * 0.1f, h * 0.85f))
    drawCircle(Color(0xFF1CC6B6), radius = h * 0.48f, center = Offset(w * 0.33f, h * 0.2f))
    drawCircle(Color(0xFFFFFFFF), radius = h * 0.42f, center = Offset(w * 0.62f, h * 0.9f))
    drawCircle(Color(0xFFFFC23A), radius = h * 0.32f, center = Offset(w * 0.85f, h * 0.25f))
    // Stripes in the lower half: where the rim bends them, the glass shows.
    val stripe = w / 60f
    var x = 0f
    while (x < w) { drawRect(Color.Black.copy(alpha = 0.18f), Offset(x, h * 0.5f), Size(stripe * 0.45f, h * 0.5f)); x += stripe }
}
