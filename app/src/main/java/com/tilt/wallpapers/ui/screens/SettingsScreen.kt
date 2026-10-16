package com.tilt.wallpapers.ui.screens

import com.tilt.wallpapers.data.t

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.ThemeMode
import com.tilt.wallpapers.data.TiltMode
import com.tilt.wallpapers.ui.components.AppButton
import com.tilt.wallpapers.ui.components.SegmentedPicker
import com.tilt.wallpapers.wallpaper.TiltWallpaperService
import kotlin.math.roundToInt

@Composable
fun SettingsScreen() {
    val state by Repo.state.collectAsStateWithLifecycle()
    val s = state.settings
    val context = LocalContext.current
    val status = rememberWallpaperStatus()
    val hf = LocalHapticFeedback.current

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = statusTop() + 12.dp, bottom = navBottom() + 120.dp),
    ) {
        item { ScreenHeader(t("Cài đặt")) }

        item { SectionTitle(t("Hình nền")) }
        item {
            SettingsCard {
                StatusRow(Icons.Filled.PhoneAndroid, t("Màn hình chính"), status.home)
                if (status.lock != null) StatusRow(Icons.Filled.Lock, t("Màn hình khóa"), status.lock)
                Text(
                    t("Khi hệ thống hỏi, chọn \"Màn hình chính và màn hình khóa\" để ảnh thay đổi ở cả hai nơi."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    AppButton(if (status.home) t("Đặt lại hình nền") else t("Đặt hình nền"), icon = Icons.Filled.Wallpaper) {
                        TiltWallpaperService.launchPicker(context)
                    }
                }
            }
        }

        item { SectionTitle(t("Giao diện")) }
        item {
            SettingsCard {
                Text(t("Chủ đề"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
                Segmented(
                    options = ThemeMode.entries,
                    selected = s.theme,
                    label = { it.label },
                    icon = { when (it) { ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto; ThemeMode.LIGHT -> Icons.Filled.LightMode; ThemeMode.DARK -> Icons.Filled.DarkMode } },
                ) { v -> Repo.updateSettings { it.copy(theme = v) } }
                Spacer(Modifier.height(8.dp))
                ThemeColorSection(state)
                Text(t("Ngôn ngữ · Language"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
                Segmented(
                    options = com.tilt.wallpapers.data.AppLanguage.entries,
                    selected = s.language,
                    label = {
                        when (it) {
                            com.tilt.wallpapers.data.AppLanguage.SYSTEM -> t("Theo máy")
                            com.tilt.wallpapers.data.AppLanguage.VI -> t("Tiếng Việt")
                            com.tilt.wallpapers.data.AppLanguage.EN -> "English"
                        }
                    },
                    icon = { if (it == com.tilt.wallpapers.data.AppLanguage.SYSTEM) Icons.Filled.Language else null },
                ) { v -> Repo.updateSettings { it.copy(language = v) } }
                if (s.language == com.tilt.wallpapers.data.AppLanguage.SYSTEM) {
                    val sysLang = com.tilt.wallpapers.data.I18n.systemLang(context)
                    val name = if (sysLang == com.tilt.wallpapers.data.Lang.VI) "Tiếng Việt" else "English"
                    Text(
                        if (com.tilt.wallpapers.data.I18n.systemIsOther(context))
                            t("Máy đang dùng ngôn ngữ khác nên app hiện %1\$s. Chọn Tiếng Việt hoặc English để cố định.", name)
                        else t("Đang theo ngôn ngữ của máy: %1\$s", name),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        item { SectionTitle(t("Nghiêng")) }
        item {
            SettingsCard {
                Text(t("Cách chuyển ảnh"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
                Segmented(TiltMode.entries, s.tiltMode, { it.label }, { null }) { v ->
                    Repo.updateSettings { it.copy(tiltMode = v) }
                }
                Text(
                    when (s.tiltMode) {
                        TiltMode.ZONES -> t("Mỗi góc nghiêng ứng với một ảnh: nghiêng càng nhiều, càng đi xa khỏi ảnh giữa. Về thẳng là về ảnh giữa.")
                        TiltMode.STEPS -> t("Mỗi lần nghiêng qua ngưỡng rồi về thẳng sẽ chuyển 1 ảnh và giữ nguyên. Hợp với bộ có rất nhiều ảnh.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                SliderRow(
                    if (s.tiltMode == TiltMode.ZONES) t("Góc nghiêng tối đa") else t("Góc kích hoạt (x2)"),
                    s.tiltRange, 15f..60f, { "${it.roundToInt()}°" },
                ) { v -> Repo.updateSettings { it.copy(tiltRange = v) } }
                SwitchRow(t("Đảo chiều nghiêng"), t("Dùng nếu trái/phải bị ngược trên máy của bạn"), s.invertTilt, Icons.Filled.SwapHoriz) { v ->
                    Repo.updateSettings { it.copy(invertTilt = v) }
                }
                TiltCalibration(s)
                SwitchRow(t("Lắc để đổi ngẫu nhiên"), t("Lắc máy để chuyển sang ảnh ngẫu nhiên trong bộ"), s.shake, Icons.Filled.PhoneAndroid) { v ->
                    Repo.updateSettings { it.copy(shake = v) }
                }
                SwitchRow(t("Khóa nghiêng"), t("Hình nền đứng yên ở ảnh hiện tại, nghiêng không đổi ảnh. Cũng bật/tắt được từ ô Tilt trong Cài đặt nhanh"), s.tiltLock, Icons.Filled.Lock) { v ->
                    Repo.setTiltLock(v)
                }
            }
        }

        item { SectionTitle(t("Tự động")) }
        item { ScheduleCard(state) }

        item { SectionTitle(t("Pin & ban đêm")) }
        item { PowerNightCard(state) }

        item { SectionTitle(t("Tiện ích")) }
        item { UtilitiesCard(state) }

        item { SectionTitle(t("Thông tin")) }
        item {
            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
            AboutCard(version)
        }

        item { SectionTitle(t("Quyền riêng tư")) }
        item { PrivacyCard() }
    }
}

@Composable
private fun StatusRow(icon: ImageVector, title: String, on: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Pill(if (on) t("Đang dùng") else t("Chưa đặt"), selected = on)
    }
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, icon: (T) -> ImageVector?, onPick: (T) -> Unit) =
    SegmentedPicker(options, selected, label, Modifier.padding(horizontal = 16.dp), icon, onPick)
