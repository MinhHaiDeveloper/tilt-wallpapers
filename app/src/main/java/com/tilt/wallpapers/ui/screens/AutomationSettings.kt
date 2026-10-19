package com.tilt.wallpapers.ui.screens

import com.tilt.wallpapers.data.t

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tilt.wallpapers.data.AppState
import com.tilt.wallpapers.data.Automation
import com.tilt.wallpapers.data.NightMode
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.Schedule
import com.tilt.wallpapers.ui.components.AppButton
import com.tilt.wallpapers.ui.components.BtnStyle
import com.tilt.wallpapers.ui.components.SegmentedPicker
import com.tilt.wallpapers.ui.components.bounceClick
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** "Đổi bộ ảnh theo giờ": a list of time → set entries, applied on the minute by the wallpaper itself. */
@Composable
fun ScheduleCard(state: AppState) {
    val s = state.settings
    var editingTime by remember { mutableStateOf<Int?>(null) }   // index, or -1 for a new entry
    fun set(list: List<Schedule>) = Repo.updateSettings { it.copy(schedules = list.sortedBy { e -> e.minuteOfDay }) }

    SettingsCard(Modifier.animateContentSize()) {
        SwitchRow(
            t("Đổi bộ ảnh theo giờ"),
            t("Hình nền tự đổi bộ đúng giờ đã đặt, không cần mở app"),
            s.scheduleEnabled, Icons.Filled.EventRepeat,
        ) { v ->
            Repo.updateSettings {
                // Turning it on with nothing set yet: start from "now → the set in use".
                if (v && it.schedules.isEmpty() && state.activeSet != null) it.copy(scheduleEnabled = true, schedules = listOf(Schedule(8, state.activeSet!!.id)))
                else it.copy(scheduleEnabled = v)
            }
        }
        AnimatedVisibility(s.scheduleEnabled) {
            Column {
                s.schedules.forEachIndexed { i, e ->
                    ScheduleRow(
                        state, e,
                        onTime = { editingTime = i },
                        onSet = { id -> set(s.schedules.toMutableList().also { it[i] = e.copy(setId = id) }) },
                        onToggle = { on -> set(s.schedules.toMutableList().also { it[i] = e.copy(enabled = on) }) },
                        onDelete = { set(s.schedules.toMutableList().also { it.removeAt(i) }) },
                    )
                }
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    AppButton(t("Thêm mốc giờ"), icon = Icons.Filled.Add, style = BtnStyle.Tonal, enabled = state.sets.isNotEmpty()) { editingTime = -1 }
                }
                NextLine(state)
                Text(
                    t("Chọn bộ khác bằng tay vẫn được: bộ đó giữ tới mốc giờ tiếp theo. Không cần quyền gì thêm: hình nền tự kiểm tra lịch mỗi khi bạn bật màn hình."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
            }
        }
    }

    editingTime?.let { idx ->
        val initial = if (idx >= 0) s.schedules[idx].minuteOfDay else 19 * 60
        TimeDialog(t("Giờ đổi bộ"), initial, onDismiss = { editingTime = null }) { m ->
            if (idx >= 0) set(s.schedules.toMutableList().also { it[idx] = it[idx].copy(hour = m / 60, minute = m % 60) })
            else {
                val setId = state.sets.firstOrNull { st -> s.schedules.none { it.setId == st.id } }?.id ?: state.sets.first().id
                set(s.schedules + Schedule(m / 60, setId, minute = m % 60))
            }
            editingTime = null
        }
    }
}

/** "Tiếp theo: Anime lúc 19:00", refreshed every minute. */
@Composable
fun NextLine(state: AppState, modifier: Modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
    if (!state.settings.scheduleEnabled) return
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(Automation.msToNextMinute()); tick++ } }
    tick
    val next = Automation.next(state.settings.schedules) ?: return
    // Only worth saying when the next slot actually changes the set.
    if (next.first.setId == state.activeSet?.id) return
    val name = state.sets.find { it.id == next.first.setId }?.name ?: return
    val h = next.second / 60; val m = next.second % 60
    val inText = if (h > 0) t("%1\$s giờ %2\$s phút nữa", h, m) else t("%1\$s phút nữa", m)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(
            t("Tiếp theo: %1\$s lúc %2\$s · %3\$s", name, Automation.fmt(next.first.minuteOfDay), inText),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ScheduleRow(state: AppState, e: Schedule, onTime: () -> Unit, onSet: (String) -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val alpha = if (e.enabled) 1f else 0.5f
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            Automation.fmt(e.minuteOfDay),
            style = MaterialTheme.typography.titleLarge, color = cs.onPrimaryContainer.copy(alpha = alpha),
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(cs.primaryContainer.copy(alpha = alpha))
                .bounceClick(pressedScale = 0.9f, onClick = onTime).padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.clip(CircleShape).background(cs.surfaceContainerHighest).bounceClick(pressedScale = 0.95f) { menu = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Collections, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    state.sets.find { it.id == e.setId }?.name ?: t("Chọn bộ"),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = cs.onSurface.copy(alpha = alpha),
                )
            }
            DropdownMenu(menu, { menu = false }) {
                state.sets.forEach { st -> DropdownMenuItem({ Text(st.name) }, { menu = false; onSet(st.id) }) }
            }
        }
        Switch(e.enabled, onToggle, Modifier.padding(horizontal = 4.dp))
        Icon(
            Icons.Filled.Close, t("Xóa mốc"), tint = cs.onSurfaceVariant,
            modifier = Modifier.clip(CircleShape).bounceClick(pressedScale = 0.8f, onClick = onDelete).padding(8.dp),
        )
    }
}

@Composable
private fun TimeDialog(title: String, initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val t = rememberTimePickerState(initial / 60, initial % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(t) },
        confirmButton = { AppButton(com.tilt.wallpapers.data.t("Xong")) { onPick(t.hour * 60 + t.minute) } },
        dismissButton = { AppButton(t("Hủy"), style = BtnStyle.Text, onClick = onDismiss) },
    )
}

/**
 * "Chế độ hiệu năng": Tự động (lightens only while the phone struggles) or Thủ công with a 4-step slider.
 * Title centred, description justified, both following the choice.
 */
@Composable
fun PerformanceCard(state: AppState) {
    val s = state.settings
    val hf = androidx.compose.ui.platform.LocalHapticFeedback.current
    val steps = listOf(
        t("Đầy đủ") to t("Luôn giữ đầy đủ hiệu ứng, kể cả khi máy lag."),
        t("Nhẹ") to t("Luôn ở 30 khung/giây, parallax yếu hơn, không hiệu ứng mở khóa. Hợp với máy tầm trung."),
        t("Rất nhẹ") to t("Tắt parallax và hiệu ứng kính, chuyển ảnh chỉ còn mờ dần. Nghiêng vẫn đổi ảnh. Hợp với máy yếu."),
        t("Ảnh tĩnh") to t("Như ảnh tĩnh kèm đồng hồ và chữ, nghiêng không đổi ảnh. Tốn pin ít nhất."),
    )
    val step = s.perfStep.coerceIn(0, 3)
    val (title, desc) = if (s.perfAuto) t("Tự động") to t("Chỉ giảm hiệu ứng khi máy lag hoặc nóng (ví dụ mở video thu nhỏ trên màn hình chính), ổn lại thì tự trở về. Máy mạnh gần như không thấy khác.")
        else steps[step]
    SettingsCard(Modifier.animateContentSize()) {
        SegmentedPicker(listOf(true, false), s.perfAuto, { if (it) t("Tự động") else t("Thủ công") }, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) { v ->
            Repo.updateSettings { it.copy(perfAuto = v) }
        }
        AnimatedVisibility(!s.perfAuto) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, t("Đầy đủ"), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.Slider(
                    value = step.toFloat(),
                    onValueChange = { v ->
                        val n = v.roundToInt().coerceIn(0, 3)
                        if (n != s.perfStep) { hf.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick); Repo.updateSettings { it.copy(perfStep = n) } }
                    },
                    valueRange = 0f..3f, steps = 2,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Icon(Icons.Filled.Eco, t("Ảnh tĩnh"), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            title, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp),
        )
        Text(
            desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Justify,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
        )
    }
}

/** Battery saver and night dimming. */
@Composable
fun PowerNightCard(state: AppState) {
    val s = state.settings
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Int?>(null) }   // 0 = start, 1 = end
    SettingsCard(Modifier.animateContentSize()) {
        SwitchRow(
            t("Tiết kiệm pin"),
            t("Khi máy bật Tiết kiệm pin hoặc pin dưới %1\$s%%: hình nền dùng mức Rất nhẹ (30 khung hình/giây, tắt parallax và hiệu ứng kính)", s.batteryThreshold),
            s.batterySaver, Icons.Filled.BatterySaver,
        ) { v -> Repo.updateSettings { it.copy(batterySaver = v) } }
        AnimatedVisibility(s.batterySaver) {
            Column {
                SliderRow(t("Ngưỡng pin"), s.batteryThreshold.toFloat(), 5f..30f, { "${it.roundToInt()}%" }) { v ->
                    Repo.updateSettings { it.copy(batteryThreshold = v.roundToInt()) }
                }
                if (Automation.lowPower(context, s)) Text(
                    t("Đang tiết kiệm pin"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
        Row(Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bedtime, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(t("Ban đêm dịu mắt"), style = MaterialTheme.typography.titleMedium)
        }
        SegmentedPicker(NightMode.entries, s.night, { it.label }, Modifier.padding(horizontal = 16.dp)) { v ->
            Repo.updateSettings { it.copy(night = v) }
        }
        AnimatedVisibility(s.night == NightMode.HOURS) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TimeChip(t("Từ"), s.nightStart) { editing = 0 }
                TimeChip(t("Đến"), s.nightEnd) { editing = 1 }
            }
        }
        AnimatedVisibility(s.night != NightMode.OFF) {
            Column {
                SliderRow(t("Mức giảm sáng"), s.nightDim, 0.1f..0.6f, { "${(it * 100).roundToInt()}%" }) { v ->
                    Repo.updateSettings { it.copy(nightDim = v) }
                }
                Text(
                    t("Hình nền tối dần trong khoảng 2 giây khi vào giờ đêm, ở cả màn hình khóa và màn hình chính."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
    editing?.let { which ->
        TimeDialog(if (which == 0) t("Bắt đầu ban đêm") else t("Kết thúc ban đêm"), if (which == 0) s.nightStart else s.nightEnd, { editing = null }) { m ->
            Repo.updateSettings { if (which == 0) it.copy(nightStart = m) else it.copy(nightEnd = m) }
            editing = null
        }
    }
}

@Composable
private fun TimeChip(label: String, minute: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(cs.primaryContainer).bounceClick(pressedScale = 0.92f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label ", style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
        Text(Automation.fmt(minute), style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
    }
}
