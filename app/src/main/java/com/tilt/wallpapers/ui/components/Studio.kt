package com.tilt.wallpapers.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tilt.wallpapers.data.t

/** One group of settings in a [Studio]: its icon in the bar under the preview. */
class StudioTab(val key: String, val label: String, val icon: ImageVector)

/**
 * Editing screens laid out like the phone's own lock-screen editor: the preview is a small phone (screen
 * aspect, as big as fits), with back / title and actions floating on it; a bar of setting groups under it.
 * Tapping a group slides its panel up and the preview scales down above it so it stays whole (scaled, not
 * resized: the live preview's surface keeps its size). Swipe the panel down, tap its group again or press back
 * to close it.
 */
@Composable
fun Studio(
    title: String,
    onBack: () -> Unit,
    aspect: Float,
    tabs: List<StudioTab>,
    open: String?,
    onOpen: (String?) -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    /** Under the preview, always shown (e.g. which object is edited). */
    strip: @Composable () -> Unit = {},
    /** Floating on the preview, over [preview] (tags, pickers). */
    overlay: @Composable BoxScope.() -> Unit = {},
    preview: @Composable BoxScope.() -> Unit,
    /** The panels scroll themselves (fill the panel) instead of being put in one scrolling column. */
    panelScrolls: Boolean = false,
    panel: @Composable ColumnScope.(String) -> Unit,
) {
    BackHandler(open != null) { onOpen(null) }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val panelH = maxHeight * 0.42f
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // The preview at its largest; the panel floats over the lower part of this area and the preview
            // scales down from the top to stay whole above it.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val full = maxHeight
                val target = if (open != null) ((full - panelH - 16.dp) / (full - 16.dp)).coerceIn(0.3f, 1f) else 1f
                val scale by animateFloatAsState(target, spring(dampingRatio = 0.9f, stiffness = 420f), label = "studioScale")
                Box(
                    Modifier.padding(vertical = 8.dp).fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true)
                        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0.5f, 0f) }
                        .clip(RoundedCornerShape(28.dp)).background(Color.Black),
                ) {
                    preview()
                    overlay()
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.38f))
                                .bounceClick(pressedScale = 0.94f, onClick = onBack).padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Quay lại"), tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(title, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.weight(1f))
                        actions()
                    }
                }
                // The open group's panel.
                androidx.compose.animation.AnimatedVisibility(
                    open != null,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200)),
                ) {
                    var drag by remember { mutableFloatStateOf(0f) }
                    Column(
                        Modifier.fillMaxWidth().height(panelH)
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        // Handle: swipe down to close.
                        Box(
                            Modifier.fillMaxWidth().height(24.dp).draggable(
                                rememberDraggableState { d -> drag += d },
                                Orientation.Vertical,
                                onDragStarted = { drag = 0f },
                                onDragStopped = { if (drag > 60f) onOpen(null); drag = 0f },
                            ),
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) }
                        AnimatedContent(open ?: "", transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "studioPanel") { key ->
                            Column(if (panelScrolls) Modifier.fillMaxSize() else Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                                if (key.isNotEmpty()) panel(key)
                            }
                        }
                    }
                }
            }
            strip()
            StudioBar(tabs, open, onOpen, Modifier.height(76.dp))
        }
    }
}

@Composable
private fun StudioBar(tabs: List<StudioTab>, open: String?, onOpen: (String?) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    // Up to five groups share the width; more scroll sideways, about three and a half showing so the one
    // cut at the edge says there is more.
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val scroll = tabs.size > 5
    val itemW = (maxWidth - 12.dp) / 3.4f
    Row(
        Modifier.fillMaxWidth().then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val sel = tab.key == open
            val bg by animateFloatAsState(if (sel) 1f else 0f, label = "studioTab")
            Column(
                (if (scroll) Modifier.width(itemW) else Modifier.weight(1f)).padding(horizontal = 2.dp).clip(RoundedCornerShape(18.dp)).background(cs.primary.copy(alpha = 0.14f * bg))
                    .bounceClick(pressedScale = 0.9f) { onOpen(if (sel) null else tab.key) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(tab.icon, null, tint = if (sel) cs.primary else cs.onSurface, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(4.dp))
                Text(tab.label, style = MaterialTheme.typography.labelMedium,
                    color = if (sel) cs.primary else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    }
}

/** A dark translucent pill floating on a preview (actions, tags). */
@Composable
fun PreviewPill(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.38f))
            .then(if (onClick != null) Modifier.bounceClick(pressedScale = 0.94f, onClick = onClick) else Modifier)
            .padding(horizontal = if (text.isEmpty()) 9.dp else 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, text.ifEmpty { null }, tint = Color.White, modifier = Modifier.size(18.dp))
        if (icon != null && text.isNotEmpty()) Spacer(Modifier.width(6.dp))
        if (text.isNotEmpty()) Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
