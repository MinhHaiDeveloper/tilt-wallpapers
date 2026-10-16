package com.tilt.wallpapers.ui.screens

import com.tilt.wallpapers.data.t

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.tilt.wallpapers.data.Settings
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.tilt.TiltSensor
import com.tilt.wallpapers.ui.components.AppButton
import com.tilt.wallpapers.ui.components.BtnStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Live gauge of how the app reads the phone's sideways angle against the "level" mark, with the
 * manual "set this angle as level" control and the automatic levelling switch.
 */
@Composable
fun TiltCalibration(s: Settings) {
    val context = LocalContext.current
    val hf = LocalHapticFeedback.current
    var raw by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sensor = TiltSensor(context) { roll, _ -> raw = roll }
        sensor.start()
        onDispose { sensor.stop() }
    }
    val rel = raw - s.tiltOffset
    val shown by animateFloatAsState(rel.coerceIn(-90f, 90f), spring(dampingRatio = 0.8f, stiffness = 300f), label = "needle")
    val cs = MaterialTheme.colorScheme

    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(t("Góc đang cầm"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            val deg = rel.roundToInt()
            Text(
                when {
                    abs(deg) <= 2 -> t("Thẳng")
                    deg > 0 -> t("Nghiêng trái %1\$s°", deg)
                    else -> t("Nghiêng phải %1\$s°", -deg)
                },
                style = MaterialTheme.typography.labelLarge, color = cs.primary,
            )
        }
        // -90..90 track, the tilt range highlighted, a needle for the current angle.
        Canvas(Modifier.fillMaxWidth().height(36.dp).padding(top = 8.dp)) {
            val w = size.width; val mid = w / 2; val h = 10.dp.toPx(); val top = (size.height - h) / 2
            fun x(d: Float) = mid - d / 90f * mid      // left tilt to the left of the gauge
            drawRoundRect(cs.surfaceContainerHighest, Offset(0f, top), Size(w, h), CornerRadius(h / 2))
            val r = s.tiltRange
            drawRoundRect(cs.primaryContainer, Offset(x(r), top), Size(x(-r) - x(r), h), CornerRadius(h / 2))
            drawLine(cs.outline, Offset(mid, top - 4.dp.toPx()), Offset(mid, top + h + 4.dp.toPx()), 2.dp.toPx())
            val nx = x(shown)
            drawCircle(cs.primary, 9.dp.toPx(), Offset(nx, size.height / 2))
            drawCircle(cs.onPrimary, 3.dp.toPx(), Offset(nx, size.height / 2))
        }
        Text(
            t("Vùng tô màu là khoảng nghiêng dùng để đổi ảnh. Nếu lúc cầm bình thường kim lệch khỏi vạch giữa, hãy cầm như mọi khi rồi bấm \"Đặt làm thẳng\"."),
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(t("Đặt làm thẳng"), icon = Icons.Filled.Straighten, style = BtnStyle.Tonal, modifier = Modifier.weight(1f)) {
                hf.performHapticFeedback(HapticFeedbackType.Confirm)
                Repo.updateSettings { it.copy(tiltOffset = raw) }
            }
            if (s.tiltOffset != 0f) AppButton(t("Về mặc định"), icon = Icons.Filled.RestartAlt, style = BtnStyle.Text) {
                Repo.updateSettings { it.copy(tiltOffset = 0f) }
            }
        }
    }
    SwitchRow(
        t("Tự cân bằng khi nằm nghiêng"),
        t("Giữ máy nghiêng hẳn sang một bên khoảng 4 giây (nằm nghiêng) thì góc đó thành góc thẳng mới. Nghiêng bình thường để đổi ảnh không bị ảnh hưởng"),
        s.tiltAutoLevel, Icons.Filled.Hotel,
    ) { v -> Repo.updateSettings { it.copy(tiltAutoLevel = v) } }
}
