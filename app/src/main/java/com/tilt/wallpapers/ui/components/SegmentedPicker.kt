package com.tilt.wallpapers.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single-choice pill selector with the same feel as the navigation bar: the selection blob slides
 * and stretches like liquid (its edges follow different springs), options bounce and tick on tap.
 */
@Composable
fun <T> SegmentedPicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: (T) -> ImageVector? = { null },
    onPick: (T) -> Unit,
) {
    val hf = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val index = options.indexOf(selected).coerceAtLeast(0)
    val lastRef = remember { intArrayOf(index) }
    val movingRight = remember(index) { (index >= lastRef[0]).also { lastRef[0] = index } }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(cs.surfaceContainerHighest)
            .padding(4.dp)
    ) {
        val itemW: Dp = maxWidth / options.size
        val fast = spring<Dp>(dampingRatio = 0.7f, stiffness = 700f)
        val slow = spring<Dp>(dampingRatio = 0.75f, stiffness = 260f)
        val left by animateDpAsState(itemW * index, if (movingRight) slow else fast, label = "segL")
        val right by animateDpAsState(itemW * (index + 1), if (movingRight) fast else slow, label = "segR")
        Box(
            Modifier
                .offset(x = left)
                .width(right - left)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(cs.primary)
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, o ->
                val sel = i == index
                val color by animateColorAsState(if (sel) cs.onPrimary else cs.onSurfaceVariant, label = "segC")
                Row(
                    Modifier
                        .width(itemW)
                        .fillMaxHeight()
                        .bounceClick(pressedScale = 0.86f, haptic = false) {
                            if (!sel) {
                                hf.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onPick(o)
                            }
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    icon(o)?.let {
                        Icon(it, null, Modifier.size(18.dp), tint = color)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(label(o), style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
