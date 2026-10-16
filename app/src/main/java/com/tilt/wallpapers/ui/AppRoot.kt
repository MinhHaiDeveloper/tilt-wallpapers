package com.tilt.wallpapers.ui

import com.tilt.wallpapers.data.t

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tilt.wallpapers.ui.components.GlassSurface
import com.tilt.wallpapers.ui.components.LocalHaze
import com.tilt.wallpapers.ui.components.bounceClick
import com.tilt.wallpapers.ui.screens.EditorScreen
import com.tilt.wallpapers.ui.screens.EffectsScreen
import com.tilt.wallpapers.ui.screens.MaskEditorScreen
import com.tilt.wallpapers.ui.screens.PreviewScreen
import com.tilt.wallpapers.ui.screens.SetScreen
import com.tilt.wallpapers.ui.screens.SetsScreen
import com.tilt.wallpapers.ui.screens.SettingsScreen
import com.tilt.wallpapers.ui.screens.ViewerScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

sealed interface Route {
    data object Tabs : Route
    data class SetDetail(val setId: String) : Route
    data class Viewer(val setId: String, val imageId: String) : Route
    data class Editor(val setId: String, val imageId: String) : Route
    data class Preview(val setId: String) : Route
    /** [subject] edits the external subject's cut-out instead of the picture's own subject mask. */
    data class MaskEditor(val imageId: String, val subject: Boolean = false) : Route
    data class Clock(val setId: String) : Route
}

class Nav(private val stack: SnapshotStateList<Route>) {
    fun push(route: Route) { stack.add(route) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav not provided") }

enum class Tab(private val vi: String, val icon: ImageVector) {
    SETS("Bộ ảnh", Icons.Filled.Collections),
    EFFECTS("Hiệu ứng", Icons.Filled.AutoAwesome),
    SETTINGS("Cài đặt", Icons.Filled.Settings);

    /** Shown in the current language. */
    val label: String get() = t(vi)
}

@Composable
fun AppRoot() {
    val stack = remember { mutableStateListOf<Route>(Route.Tabs) }
    val nav = remember { Nav(stack) }
    var tab by rememberSaveable { mutableStateOf(Tab.SETS) }
    // Predictive back (Android 14+): the screen shrinks and slides with the finger; letting go goes back,
    // swiping back in cancels. Older versions get a plain back.
    val backProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    var backEdge by remember { mutableStateOf(0) }
    val backScope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.activity.compose.PredictiveBackHandler(stack.size > 1) { events ->
        try {
            events.collect { e -> backEdge = e.swipeEdge; backProgress.snapTo(e.progress) }
            nav.pop()
            backProgress.snapTo(0f)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            backScope.launch { backProgress.animateTo(0f) }
            throw e
        }
    }
    BackHandler(stack.size == 1 && tab != Tab.SETS) { tab = Tab.SETS }

    CompositionLocalProvider(LocalNav provides nav) {
        AnimatedContent(
            targetState = stack.last() to stack.size,
            contentKey = { it.first },
            transitionSpec = {
                val forward = targetState.second >= initialState.second
                val enter = slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f)) { if (forward) it / 3 else -it / 4 } +
                    fadeIn(tween(220)) + scaleIn(initialScale = if (forward) 1f else 0.96f)
                val exit = slideOutHorizontally(tween(260)) { if (forward) -it / 6 else it / 3 } +
                    fadeOut(tween(200)) + scaleOut(targetScale = if (forward) 0.96f else 1f)
                enter togetherWith exit
            },
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .graphicsLayer {
                    val p = backProgress.value
                    val s = 1f - 0.1f * p
                    scaleX = s; scaleY = s
                    translationX = (if (backEdge == 0) 1f else -1f) * size.width * 0.04f * p
                    shape = androidx.compose.foundation.shape.RoundedCornerShape((28 * p).dp)
                    clip = p > 0f
                },
            label = "nav",
        ) { (route, _) ->
            when (route) {
                Route.Tabs -> TabsHost(tab) { tab = it }
                is Route.SetDetail -> SetScreen(route.setId)
                is Route.Viewer -> ViewerScreen(route.setId, route.imageId)
                is Route.Editor -> EditorScreen(route.setId, route.imageId)
                is Route.Preview -> PreviewScreen(route.setId)
                is Route.MaskEditor -> MaskEditorScreen(route.imageId, route.subject)
                is Route.Clock -> com.tilt.wallpapers.ui.screens.ClockScreen(route.setId)
            }
        }
        com.tilt.wallpapers.ui.screens.WhatsNewOnUpdate()
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 80.dp), contentAlignment = Alignment.BottomCenter) {
            com.tilt.wallpapers.ui.components.NoticeStack()
        }
    }
}

@Composable
private fun TabsHost(tab: Tab, onTab: (Tab) -> Unit) {
    val haze = rememberHazeState()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(haze)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f)) { dir * it / 5 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(220)) { -dir * it / 8 } + fadeOut(tween(160)))
                },
                label = "tabs",
            ) { t ->
                when (t) {
                    Tab.SETS -> SetsScreen()
                    Tab.EFFECTS -> EffectsScreen()
                    Tab.SETTINGS -> SettingsScreen()
                }
            }
            if (tab != Tab.SETS) StatusScrim()
        }
        CompositionLocalProvider(LocalHaze provides haze) {
            GlassNavBar(tab, onTab, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp))
        }
    }
}

/** Floating glass pill; the selection blob stretches like liquid as it moves (edges use different springs). */
@Composable
private fun GlassNavBar(selected: Tab, onTab: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val itemW = 92.dp
    val pad = 6.dp
    val target = itemW * selected.ordinal
    val lastRef = remember { intArrayOf(selected.ordinal) }
    val movingRight = remember(selected) { (selected.ordinal >= lastRef[0]).also { lastRef[0] = selected.ordinal } }
    val fast = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.7f, stiffness = 700f)
    val slow = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.75f, stiffness = 260f)
    val left by animateDpAsState(target, if (movingRight) slow else fast, label = "l")
    val right by animateDpAsState(target + itemW, if (movingRight) fast else slow, label = "r")
    GlassSurface(modifier.height(68.dp), shape = CircleShape) {
        Box(Modifier.padding(pad)) {
            Box(
                Modifier
                    .offset(x = left)
                    .width(right - left)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            )
            Row {
                Tab.entries.forEach { t ->
                    NavItem(t, t == selected, Modifier.width(itemW).fillMaxHeight()) { onTab(t) }
                }
            }
        }
    }
}

@Composable
private fun NavItem(tab: Tab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(
        modifier.bounceClick(pressedScale = 0.85f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(tab.icon, tab.label, tint = color, modifier = Modifier.size(24.dp))
        Text(tab.label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Content blurred by glass overlays placed on top of it. */
@Composable
fun GlassLayer(content: @Composable () -> Unit, scrim: Boolean = true, overlay: @Composable BoxScope.() -> Unit) {
    val haze = rememberHazeState()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(haze)) { content() }
        if (scrim) StatusScrim()
        CompositionLocalProvider(LocalHaze provides haze) { overlay() }
    }
}

/** Fades scrolling content out under the status bar so the clock stays readable. */
@Composable
fun StatusScrim() {
    val bg = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0.85f))))
    )
}
