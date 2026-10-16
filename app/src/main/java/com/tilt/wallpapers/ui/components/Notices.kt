package com.tilt.wallpapers.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * App-wide stack of short notices ("Đã xóa · Hoàn tác", "Đã áp dụng"). Up to three show at once, newest at
 * the bottom; each has its own timer and action, and can be swiped away sideways.
 */
object Notices {
    class Notice(
        val id: Long,
        val message: String,
        val actionLabel: String? = null,
        val action: (() -> Unit)? = null,
        /** Trash items behind an undo notice: it closes by itself once they are restored elsewhere. */
        val trashIds: List<String> = emptyList(),
    ) { var leaving by mutableStateOf(false) }

    val items = mutableStateListOf<Notice>()

    /** Extra room a screen needs below the stack (a bottom action bar that is showing). */
    var lift by mutableStateOf(0.dp)

    private var nextId = 0L

    fun show(message: String, actionLabel: String? = null, trashIds: List<String> = emptyList(), action: (() -> Unit)? = null) {
        items.add(Notice(nextId++, message, actionLabel, action, trashIds))
        items.filter { !it.leaving }.let { live -> if (live.size > MAX) live.take(live.size - MAX).forEach { it.leaving = true } }
    }

    fun dismiss(n: Notice) { n.leaving = true }

    internal fun remove(n: Notice) { items.remove(n) }

    private const val MAX = 3
}

/** The stack itself; placed once at the bottom of the app above the tab bar. */
@Composable
fun NoticeStack(modifier: Modifier = Modifier) {
    val state by Repo.state.collectAsStateWithLifecycle()
    // Deletions announced by the repository become undo notices.
    LaunchedEffect(Unit) {
        Repo.deletions.collect { d ->
            Notices.show(d.message, t("Hoàn tác"), d.itemIds) { Repo.restoreTrash(d.itemIds) }
        }
    }
    // Restored from the trash screen (or by another notice): their undo notice has nothing left to do.
    val inTrash = state.trash.map { it.id }.toSet()
    LaunchedEffect(inTrash) {
        Notices.items.filter { it.trashIds.isNotEmpty() && it.trashIds.none { id -> id in inTrash } }.forEach { Notices.dismiss(it) }
    }
    val lift by animateDpAsState(Notices.lift, spring(dampingRatio = 0.8f, stiffness = 500f), label = "lift")
    Column(
        // The size animation clips: inner padding leaves room for the glass edge and shadow.
        modifier.padding(bottom = lift.coerceAtLeast(0.dp)).animateContentSize(spring(dampingRatio = 0.85f, stiffness = 500f)).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val live = Notices.items.toList()
        live.forEachIndexed { i, n ->
            key(n.id) { NoticePill(n, depth = live.size - 1 - i) }
        }
    }
}

@Composable
private fun NoticePill(n: Notices.Notice, depth: Int) {
    val hf = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val appear = remember { Animatable(0f) }
    val dragX = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 500f)) }
    LaunchedEffect(Unit) { delay(5000); Notices.dismiss(n) }
    LaunchedEffect(n.leaving) {
        if (n.leaving) { appear.animateTo(0f, tween(200)); Notices.remove(n) }
    }
    // Older notices sit above, a little smaller and fainter.
    val older by animateFloatAsState(depth.coerceAtMost(2).toFloat(), label = "depth")
    GlassSurface(
        Modifier
            .padding(horizontal = 16.dp)
            .graphicsLayer {
                val a = appear.value
                alpha = a * (1f - 0.18f * older) * (1f - (abs(dragX.value) / (size.width.coerceAtLeast(1f))).coerceIn(0f, 1f))
                translationY = (1f - a) * 40.dp.toPx()
                translationX = dragX.value
                val s = (0.9f + 0.1f * a) * (1f - 0.05f * older)
                scaleX = s; scaleY = s
            }
            .pointerInput(n.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(dragX.value) > size.width * 0.35f) {
                                hf.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                dragX.animateTo(if (dragX.value > 0) size.width.toFloat() else -size.width.toFloat(), tween(160))
                                Notices.remove(n)
                            } else dragX.animateTo(0f, spring(dampingRatio = 0.6f))
                        }
                    },
                    onDragCancel = { scope.launch { dragX.animateTo(0f) } },
                ) { change, amount -> change.consume(); scope.launch { dragX.snapTo(dragX.value + amount) } }
            },
        shape = CircleShape,
    ) {
        Row(
            Modifier.padding(start = 20.dp, end = if (n.actionLabel != null) 6.dp else 20.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                n.message, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(vertical = if (n.actionLabel == null) 12.dp else 0.dp),
            )
            if (n.actionLabel != null && n.action != null) AppButton(n.actionLabel, icon = Icons.Filled.Restore, style = BtnStyle.Text) {
                hf.performHapticFeedback(HapticFeedbackType.Confirm)
                n.action.invoke()
                Notices.dismiss(n)
            }
        }
    }
}

/** Room a bottom action bar of about this height needs; screens set it while the bar shows. */
val BOTTOM_BAR_LIFT: Dp = 68.dp
