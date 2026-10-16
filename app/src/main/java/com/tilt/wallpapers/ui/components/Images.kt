package com.tilt.wallpapers.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.tilt.wallpapers.data.Grading
import com.tilt.wallpapers.data.ImageEdit
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.WallImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Small decoded-bitmap cache for grids, the viewer and the editor. */
object ImageCache {
    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun peek(key: String): ImageBitmap? = cache.get(key)

    suspend fun load(file: File, maxDim: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "${file.path}@$maxDim"
        cache.get(key)?.let { return@withContext it }
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            var sample = 1
            while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2
            // Straight into GPU memory: nothing to upload on the first draw (scrolling a grid of new
            // thumbnails stuttered on those uploads). Only drawn, never read back.
            (BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.HARDWARE })
                ?: BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))?.asImageBitmap()
        }.getOrNull()?.also { cache.put(key, it) }
    }
}

@Composable
fun rememberBitmap(file: File, maxDim: Int): ImageBitmap? {
    val key = "${file.path}@$maxDim"
    var bmp by remember(key) { mutableStateOf(ImageCache.peek(key)) }
    LaunchedEffect(key) { if (bmp == null) bmp = ImageCache.load(file, maxDim) }
    return bmp
}

/** Pixel size of the longest screen edge, used for full-quality images. */
@Composable
fun screenMaxPx(): Int {
    val c = LocalConfiguration.current
    return with(LocalDensity.current) { (max(c.screenWidthDp, c.screenHeightDp) * density).roundToInt() }
}

/**
 * Draws an image the way the wallpaper renders it (crop fitted to the box, colour grade,
 * vignette, opacity over black) without the GL pipeline.
 */
@Composable
fun EditedImage(
    image: WallImage,
    modifier: Modifier = Modifier,
    edit: ImageEdit = image.edit,
    full: Boolean = false,
) {
    val maxDim = if (full) screenMaxPx() else 480
    val bmp = rememberBitmap(if (full) Repo.imageFile(image.id) else Repo.thumbFile(image.id), maxDim)
        ?: if (full) rememberBitmap(Repo.thumbFile(image.id), 480) else null
    val appear by animateFloatAsState(if (bmp != null) 1f else 0f, tween(260), label = "appear")
    val filter = remember(edit) { ColorFilter.colorMatrix(ColorMatrix(Grading.matrix(edit))) }
    val rect = remember { FloatArray(4) }
    Canvas(modifier.background(Color.Black)) {
        val b = bmp ?: return@Canvas
        Grading.fitCrop(edit, b.width.toFloat() / b.height, size.width / size.height, rect)
        drawImage(
            image = b,
            srcOffset = IntOffset((rect[0] * b.width).roundToInt(), (rect[1] * b.height).roundToInt()),
            srcSize = IntSize(
                (rect[2] * b.width).roundToInt().coerceIn(1, b.width),
                (rect[3] * b.height).roundToInt().coerceIn(1, b.height),
            ),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            alpha = edit.opacity * appear,
            colorFilter = filter,
            filterQuality = FilterQuality.Medium,
        )
        if (edit.vignette > 0f) {
            drawRect(
                Brush.radialGradient(
                    0.45f to Color.Transparent,
                    1f to Color.Black.copy(alpha = edit.vignette),
                    center = Offset(size.width / 2, size.height / 2),
                    radius = size.maxDimension * 0.75f,
                )
            )
        }
    }
}
