package com.tilt.wallpapers.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tilt.wallpapers.data.t

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.ui.GlassLayer
import com.tilt.wallpapers.ui.LocalNav
import com.tilt.wallpapers.ui.Route
import com.tilt.wallpapers.ui.components.AppButton
import com.tilt.wallpapers.ui.components.ConfirmDeleteDialog
import com.tilt.wallpapers.ui.components.EditedImage
import com.tilt.wallpapers.ui.components.GlassIconButton
import com.tilt.wallpapers.ui.components.GlassSurface
import com.tilt.wallpapers.ui.components.TiltPreview
import com.tilt.wallpapers.wallpaper.TiltWallpaperService
import kotlin.math.absoluteValue

/** Full-screen look at each image as it appears on the wallpaper; swipe through the tilt order. */
@Composable
fun ViewerScreen(setId: String, imageId: String) {
    val nav = LocalNav.current
    val state by Repo.state.collectAsStateWithLifecycle()
    val set = state.sets.find { it.id == setId }
    val seq = set?.tiltSequence()?.first.orEmpty()
    if (set == null || seq.isEmpty()) { LaunchedEffect(Unit) { nav.pop() }; return }
    val pager = rememberPagerState(seq.indexOfFirst { it.id == imageId }.coerceAtLeast(0)) { seq.size }
    var chrome by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    val current = seq.getOrNull(pager.currentPage) ?: seq.last()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        GlassLayer(scrim = false, content = {
            HorizontalPager(pager, key = { seq[it].id }, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
                val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                EditedImage(
                    seq[page],
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val s = 1f - 0.12f * offset
                            scaleX = s; scaleY = s
                            alpha = 1f - 0.4f * offset
                            shape = RoundedCornerShape((offset * 48).dp)
                            clip = true
                        }
                        .pointerInput(Unit) { detectTapGestures { chrome = !chrome } },
                    full = true,
                )
            }
        }) {
            AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
                GlassTopBar("${pager.currentPage + 1} / ${seq.size}", onBack = { nav.pop() }) {
                    GlassIconButton(Icons.Filled.Edit, t("Chỉnh sửa")) { nav.push(Route.Editor(setId, current.id)) }
                    GlassIconButton(Icons.Filled.Delete, t("Xóa")) { confirm = true }
                }
            }
            AnimatedVisibility(
                chrome,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
                enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it },
            ) {
                GlassSurface(shape = CircleShape) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(current.side.icon(), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(positionLabel(set, current), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    if (confirm) ConfirmDeleteDialog(
        title = t("Xóa ảnh này?"),
        message = t("Ảnh sẽ bị xóa khỏi bộ \"%1\$s\". Có thể khôi phục trong Thùng rác 7 ngày.", set.name),
        onConfirm = { Repo.deleteImages(setId, setOf(current.id)) },
        onDismiss = { confirm = false },
    )
}

/**
 * Full-screen live preview of a set, exactly as the wallpaper renders it. The buttons fade out after 2 s (and
 * the status bar hides) so it looks like the real screen; swipe down from the top edge to bring them back.
 * "Ẩn giao diện" opens it with them hidden from then on.
 */
@Composable
fun PreviewScreen(setId: String) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val state by Repo.state.collectAsStateWithLifecycle()
    var mock by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val hideUi = state.settings.previewHideUi
    var shown by remember { mutableStateOf(!hideUi) }
    var poke by remember { mutableIntStateOf(0) }
    LaunchedEffect(shown, poke) { if (shown) { kotlinx.coroutines.delay(2000); shown = false } }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 180 else 450), label = "chrome")
    // The status bar goes with the buttons.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(shown) {
        val window = (view.context as? android.app.Activity)?.window
        val ctl = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (shown) ctl?.show(androidx.core.view.WindowInsetsCompat.Type.statusBars()) else ctl?.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        onDispose { ctl?.show(androidx.core.view.WindowInsetsCompat.Type.statusBars()) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        GlassLayer(scrim = false, content = {
            TiltPreview(Modifier.fillMaxSize(), setId)
            if (mock) LockMock(Modifier.fillMaxSize(), full = true)
        }) {
            // Swipe down from the top edge: the buttons come back.
            Box(Modifier.fillMaxWidth().height(56.dp).align(Alignment.TopCenter).pointerInput(Unit) {
                detectVerticalDragGestures { _, d -> if (d > 6f) { shown = true; poke++ } }
            })
            if (alpha > 0.01f) {
                GlassTopBar(t("Nghiêng máy hoặc chạm để thử"), onBack = { nav.pop() }, Modifier.align(Alignment.TopCenter).graphicsLayer { this.alpha = alpha }) {
                    com.tilt.wallpapers.ui.components.GlassIconButton(
                        if (mock) Icons.Filled.LockOpen else Icons.Filled.Lock, t("Xem với màn hình khóa"),
                    ) { mock = !mock; poke++ }
                    com.tilt.wallpapers.ui.components.GlassIconButton(
                        if (hideUi) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (hideUi) t("Hiện giao diện khi mở") else t("Ẩn giao diện"),
                    ) {
                        val now = !hideUi
                        Repo.updateSettings { it.copy(previewHideUi = now) }
                        if (now) shown = false else poke++
                    }
                }
                GlassSurface(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp).graphicsLayer { this.alpha = alpha },
                    shape = CircleShape,
                ) {
                    Column(Modifier.padding(6.dp)) {
                        AppButton(t("Dùng làm hình nền"), icon = Icons.Filled.Wallpaper) { applySet(context, setId) }
                    }
                }
            }
        }
    }
}

fun applySet(context: Context, setId: String) {
    Repo.setActive(setId)
    if (!TiltWallpaperService.status(context).home) TiltWallpaperService.launchPicker(context)
    else com.tilt.wallpapers.ui.components.Notices.show(t("Đã áp dụng lên hình nền"))
}
