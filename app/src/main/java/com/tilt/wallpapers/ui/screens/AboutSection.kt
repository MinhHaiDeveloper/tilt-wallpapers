package com.tilt.wallpapers.ui.screens

import com.tilt.wallpapers.data.t

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.ui.components.AppButton
import com.tilt.wallpapers.ui.components.bounceClick

/** In-app release notes, newest first. Keep in step with CHANGELOG.md: highlights only, older ones short. */
private class Release(val version: String, val date: String, val notes: List<String>)

private val releases = listOf(
    Release("1.5.2.1", "19/10/2026", listOf("Chữ trên kính trong luôn đọc được, kể cả giao diện tối trên ảnh sáng")),
    Release("1.5.2.0", "19/10/2026", listOf("Kính kiểu Liquid Glass: chỉnh độ mờ, kính trong hơn có khúc xạ ở mép như giọt nước")),
    Release("1.5.1.9", "19/10/2026", listOf("Chế độ hiệu năng: tự giảm hiệu ứng khi máy lag hoặc nóng, hoặc chọn mức cố định", "Tiết kiệm pin dùng mức Rất nhẹ (tắt cả hiệu ứng kính)")),
    Release("1.5.1.8", "19/10/2026", listOf("Hiệu ứng mở khóa chạy đúng lúc trên Oppo, không còn bụp và không chạy hai lần", "Chỉnh được thời gian hiệu ứng mở khóa và lúc nó chạy")),
    Release("1.5.1.6", "17/10/2026", listOf("Sửa văng app khi kéo giãn ngang đồng hồ chạm mép màn hình", "Đồng hồ không còn rộng quá màn hình với font lớn", "Sửa bóng của đồng hồ bị cắt thành khối khi chuyển ảnh với font hẹp")),
    Release("1.5.1.5", "16/10/2026", listOf("Hàng nhóm cài đặt lướt ngang khi có hơn 5 nhóm", "Quay lại từ chỉnh mặt nạ / chủ thể vẫn mở tab Depth")),
    Release(
        "1.5.1.4", "16/10/2026",
        listOf(
            "Chỉnh đồng hồ, chữ và ảnh kiểu màn hình khóa: preview lớn, nhóm cài đặt bên dưới",
            "Chụm hai ngón để phóng to preview, kéo ở đâu cũng dời được, chạm để chọn đối tượng",
            "Chỉnh gradient ngay trong bảng, không còn giật",
            "Xem trước tự ẩn nút sau 2 giây, có nút Ẩn giao diện",
        ),
    ),
    Release("1.5.1.3", "16/10/2026", listOf("File sao lưu ra đúng đuôi .tilt trên cả Oppo", "Chữ mới thêm mặc định là \"Text\"")),
    Release("1.5.1.2", "16/10/2026", listOf("Sửa bóng của chữ bị cắt thẳng và lệch khi chữ co quanh đồng hồ", "File sao lưu giữ đúng đuôi .tilt, tên file theo ngôn ngữ", "Các ô chọn font cùng một cỡ; dịch nốt phần còn thiếu")),
    Release("1.5.1.1", "15/10/2026", listOf("Sửa đồng hồ thành khối màu khi chuyển ảnh lúc bộ có chữ")),
    Release(
        "1.5.1", "15/10/2026",
        listOf(
            "Chữ tự do: thêm tối đa 2 chữ, chỉnh font, màu, bóng, vị trí như đồng hồ",
            "Chữ né chủ thể, đồng hồ và nhau, tự co quanh vật cản; hết chỗ thì tự lên trên cùng",
            "Tải font của riêng bạn (.ttf, .otf) cho đồng hồ và chữ",
            "Hiệu ứng mở khóa: Tắt / Nhẹ / Rõ",
            "Xuất bộ ảnh (.zip) mang theo đồng hồ, chữ và font",
            "Kéo giãn đồng hồ dọc dài tới gần kín màn hình",
        ),
    ),
    Release("1.5.0.20", "15/10/2026", listOf("Nhẹ và mượt hơn: hình nền không vẽ lại khi máy để yên, bản xem trước và cuộn trang ít giật hơn", "Ứng dụng chạy nhanh ngay từ lần mở đầu")),
    Release("1.5.0.19", "15/10/2026", listOf("Đổi đồng hồ ngang sang dọc không còn giật thêm một cái sau khi tới nơi")),
    Release("1.5.0.18", "15/10/2026", listOf("Đổi chiều đồng hồ giữa hai ảnh chuyển một mạch, không còn giật khựng lúc bắt đầu")),
    Release("1.5.0.17", "15/10/2026", listOf("Bóng không còn tụt xuống dưới chữ số khi co quanh chủ thể", "Khoảng cách giữa đồng hồ và chủ thể sát hơn")),
    Release("1.5.0.16", "15/10/2026", listOf("Mỗi chữ số co trong đúng ô của nó, không còn bị tụt lệch ở đồng hồ dọc")),
    Release("1.5.0.15", "15/10/2026", listOf("Bóng của mỗi chữ số đi theo đúng chữ số khi co quanh chủ thể")),
    Release("1.5.0.14", "15/10/2026", listOf("Đồng hồ luôn nằm gọn trong màn hình, không còn bị cắt ở mép", "Bóng giữa hai cột số liền mạch")),
    Release("1.5.0.13", "15/10/2026", listOf("Sửa dấu hai chấm bị lệch ở đồng hồ ngang")),
    Release("1.5.0.12", "15/10/2026", listOf("Bóng mờ sau chữ không còn bị cắt thẳng, chỉnh được độ đậm", "Hình nền thật khớp bản xem trước khi dừng ở một ảnh")),
    Release("1.5.0.11", "15/10/2026", listOf("Sửa chữ số bị cắt khi đổi chiều đồng hồ", "Đồng hồ Trên cùng kéo tự do, không vướng tường chủ thể")),
    Release(
        "1.5.0.10", "15/10/2026",
        listOf(
            "Màn hình Đồng hồ chia 2 tab Chung / Ảnh này, chọn lớp Sau chủ thể / Trên cùng",
            "Kéo cạnh khung để kéo giãn đồng hồ một chiều, nút góc nhỏ gọn",
            "Bám chủ thể có tường vô hình: đồng hồ trượt quanh chủ thể, không chui vào sau",
            "Sửa chữ bị xén khi dày, bóng kép và vệt khi đổi chiều, ngày bị cắt; thêm font Anybody",
        ),
    ),
    Release("1.5.0.9", "15/10/2026", listOf("Sửa vệt khi chuyển sang đồng hồ dọc", "Flip kèm đồng hồ mượt hơn nhiều")),
    Release("1.5.0.8", "15/10/2026", listOf("Sửa chữ số bị hình kép khi đồng hồ đổi chiều")),
    Release("1.5.0.7", "15/10/2026", listOf("Sửa vệt kẻ quanh khối số khi đồng hồ đổi chiều", "Tự đặt ôm sát chủ thể ở mọi ảnh, chữ số co giãn như khi kéo tay", "Giữ co giãn theo chủ thể khi chuyển giữa đồng hồ ngang và dọc")),
    Release("1.5.0.6", "15/10/2026", listOf("Sửa vệt ngang mảnh khi đồng hồ đổi chiều và khi chữ số co")),
    Release("1.5.0.5", "15/10/2026", listOf("Đổi chiều đồng hồ: các cặp số trượt sang chỗ mới thay vì mờ dần", "Gọi là đồng hồ Ngang / Dọc")),
    Release("1.5.0.4", "15/10/2026", listOf("Dấu hai chấm co theo chữ số", "Đồng hồ chuyển mượt giữa ngang và dọc", "Gradient đổi theo ảnh đang hiện")),
    Release("1.5.0.3", "15/10/2026", listOf("Đồng hồ bóp ngang và co dọc để né chủ thể, chọn bố cục riêng từng ảnh", "Màu gradient cho đồng hồ, lấy từ ảnh giữa và chỉnh được")),
    Release("1.5.0.2", "15/10/2026", listOf("Đồng hồ co từng chữ số quanh chủ thể, luôn chừa khoảng cách", "Font Roboto Flex: thanh Mảnh ↔ Đậm đổi dáng số từ rộng sang cao gầy")),
    Release("1.5.0.1", "15/10/2026", listOf("Khung kéo và góc đổi cỡ cho đồng hồ", "Bám chủ thể: đồng hồ co giãn quanh nhân vật khi kéo", "Sửa đồng hồ mất ở màn hình chính sau khi tắt và bật màn hình")),
    Release(
        "1.5.0", "15/10/2026",
        listOf(
            "Đồng hồ sau chủ thể: nhân vật che lên chữ số",
            "Một dòng hoặc hai tầng, 4 font, kiểu đặc / viền / kính",
            "Tự đặt theo chủ thể của từng ảnh, chuyển mượt khi đổi ảnh",
            "Hiện ở màn hình chính, khóa hoặc cả hai, xem thử với màn hình khóa",
        ),
    ),
    Release("1.4.4.1", "14/10/2026", listOf("Kéo sắp xếp bộ ảnh mượt, dòng bám đúng ngón tay", "Đổi ngôn ngữ không còn chớp màn hình đen", "Theo máy: chọn đúng khi máy dùng nhiều ngôn ngữ")),
    Release(
        "1.4.4", "14/10/2026",
        listOf(
            "Tiếng Anh: đổi ngôn ngữ trong Cài đặt, mặc định theo máy",
            "Xem bộ ảnh dạng danh sách, kéo để sắp xếp",
            "Thông báo xếp chồng, lần xóa nào cũng hoàn tác được",
            "Chạm lại bộ đã chọn để bỏ chọn",
            "Xem trước Flip tự lật khi chỉnh, bảng đóng mượt, cử chỉ Back dự đoán",
        ),
    ),
    Release("1.4.3.8", "13/10/2026", listOf("Tùy chỉnh Flip: độ tối ảnh dưới, độ mờ, mờ lên nhanh/chậm, mép trong suốt", "Ảnh dưới mặc định tối hơn")),
    Release("1.4.3.7", "13/10/2026", listOf("Cập nhật Flip: mờ lên sớm, mép ngoài trong dần lộ ảnh kế tiếp, ảnh mới nét ở cuối")),
    Release("1.4.3.6", "13/10/2026", listOf("Cập nhật Flip: lớp mờ dải chuyển, rõ ở bản lề, mờ ở mép, lấn vào chậm")),
    Release("1.4.3.5", "13/10/2026", listOf("Cập nhật Flip: mờ mịn không hạt, giữ màu tươi")),
    Release("1.4.3.4", "13/10/2026", listOf("Cập nhật Flip: kính mờ lan dần từ mép vào trong theo tay")),
    Release("1.4.3.3", "13/10/2026", listOf("Cập nhật Flip: liền mạch hơn, không còn viền ở mép trang", "Sửa văng app khi mở Xem tất cả phiên bản")),
    Release("1.4.3.2", "13/10/2026", listOf("Cập nhật Flip: ảnh phía dưới nhòe rồi rõ dần theo tay")),
    Release("1.4.3.1", "13/10/2026", listOf("Cập nhật Flip: kính mờ, càng lật càng nhòe")),
    Release(
        "1.4.3", "13/10/2026",
        listOf(
            "Hiệu ứng chuyển cảnh mới: Flip",
            "Chuyển theo tay: ảnh chuyển đúng bằng góc bạn nghiêng (chế độ Theo góc)",
            "Nghiêng ngược lại giữa chừng thì chuyển cảnh chạy lùi mượt, không giật",
            "Sửa danh sách phiên bản bị giật khi cuộn lên đầu",
        ),
    ),
    Release(
        "1.4.2", "13/10/2026",
        listOf(
            "Hiệu chỉnh góc cầm máy, tự cân bằng khi nằm nghiêng",
            "Căn theo chủ thể: khung cắt tự đưa nhân vật vào giữa màn hình",
            "Ảnh mới thêm vào tự căn theo chủ thể",
            "Chủ thể tải lên tự đặt vào chỗ nhân vật cũ, sát đáy hoặc chân dung",
        ),
    ),
    Release("1.4.1.3", "13/10/2026", listOf("Bảng chọn của ô Tilt không còn thanh đen ở trên cùng")),
    Release("1.4.1.2", "13/10/2026", listOf("Bảng chọn của ô Tilt mở từ giữa, không còn giật từ cạnh vào")),
    Release("1.4.1.1", "13/10/2026", listOf("Bảng chọn của ô Tilt mở mượt hơn (phóng to từ giữa)", "Nút đóng màu đỏ dễ nhìn, nền phía sau được làm mờ", "Lưu ảnh an toàn hơn khi máy tắt đột ngột")),
    Release(
        "1.4.1", "13/10/2026",
        listOf(
            "Ô Tilt trong Cài đặt nhanh: đổi bộ ảnh, khóa nghiêng, sang ảnh tiếp ngay trên thanh thông báo",
            "Khóa nghiêng: giữ nguyên ảnh đang hiện",
            "Nhấn giữ icon app để đổi bộ, sang ảnh tiếp, khóa nghiêng",
            "Sao lưu tất cả ra một file và khôi phục trên máy mới",
            "Thùng rác 7 ngày và nút Hoàn tác khi lỡ xóa",
        ),
    ),
    Release("1.4.0", "13/10/2026", listOf("Đổi bộ ảnh theo giờ, đúng phút, không cần quyền", "Tiết kiệm pin, ban đêm dịu mắt", "Độ mượt parallax, giảm chuyển động theo máy")),
    Release("1.3.5", "13/10/2026", listOf("Màu phụ hài hòa theo màu chính, hết mảng xanh lạc tông", "Màu tự chọn được hòa nhẹ")),
    Release("1.3.4", "12/10/2026", listOf("Bóng đổ mịn ở mọi độ mềm, không còn khung vuông")),
    Release("1.3.3", "12/10/2026", listOf("Chỉnh tay chủ thể ngoài, AI tách nền cả ảnh", "Màu giao diện theo ảnh giữa của bộ, tự chọn màu")),
    Release("1.3.2", "12/10/2026", listOf("Công cụ Chọn vùng: khoanh, bám vật thể bằng AI, nới / thu viền")),
    Release("1.3.1", "12/10/2026", listOf("Hộp thoại nhập / xuất mới, nút \"+\" mới", "Mục Có gì mới và Quyền riêng tư", "Sửa văng Lasso AI và màn hình đen khi phóng to")),
    Release("1.3.0", "12/10/2026", listOf("Sửa văng app khi chỉnh mặt nạ", "Chủ thể từ ảnh ngoài, đổ bóng, làm mờ nền")),
)

private const val CARD_BULLETS = 4

@Composable
fun AboutCard(version: String?) {
    var showAll by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    SettingsCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Tilt Wallpapers", style = MaterialTheme.typography.titleMedium)
                Text(t("Phiên bản %1\$s", version ?: "-"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val latest = releases.first()
        ReleaseNotes(latest, Icons.Filled.NewReleases, t("Có gì mới · %1\$s", latest.version), limit = CARD_BULLETS)
        Row(
            Modifier.fillMaxWidth().bounceClick(pressedScale = 0.97f) { showAll = true }.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t("Xem tất cả phiên bản"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.primary)
        }
        Row(
            Modifier.fillMaxWidth().bounceClick(pressedScale = 0.97f) { showLicenses = true }.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t("Giấy phép mã nguồn mở"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (showAll) VersionHistorySheet { showAll = false }
    if (showLicenses) LicensesSheet { showLicenses = false }
}

private class OssLicense(val name: String, val holder: String, val license: String, val asset: String? = null)

private val licenses = listOf(
    OssLicense("iphone-duo", "Copyright (c) 2026 jadon7", "MIT License", "licenses/iphone-duo-MIT.txt"),
    OssLicense("Roboto Flex", "The Roboto Flex Project Authors", "SIL Open Font License 1.1", "licenses/roboto-flex-OFL.txt"),
    OssLicense("Anybody", "The Anybody Project Authors", "SIL Open Font License 1.1", "licenses/anybody-OFL.txt"),
    OssLicense("Smooch Sans", "The Smooch Sans Project Authors", "SIL Open Font License 1.1", "licenses/smooch-sans-OFL.txt"),
    OssLicense("Concert One", "Johan Kallas, Mihkel Virkus", "SIL Open Font License 1.1", "licenses/concert-one-OFL.txt"),
    OssLicense("Antonio", "The Antonio Project Authors", "SIL Open Font License 1.1", "licenses/antonio-OFL.txt"),
    OssLicense("Big Shoulders", "The Big Shoulders Project Authors", "SIL Open Font License 1.1", "licenses/big-shoulders-OFL.txt"),
    OssLicense("AndroidX, Jetpack Compose, Material 3", "The Android Open Source Project", "Apache License 2.0"),
    OssLicense("Kotlin, kotlinx.coroutines, kotlinx.serialization", "JetBrains s.r.o.", "Apache License 2.0"),
    OssLicense("Haze", "Chris Banes", "Apache License 2.0"),
    OssLicense("ML Kit Subject Segmentation", "Google LLC", "ML Kit Terms of Service"),
)

/** Third-party code in the app and its licences; MIT texts open in full. */
@Composable
private fun LicensesSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val closer = rememberSheetCloser(onDismiss)
    var open by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = closer.close, sheetState = closer.state, modifier = closer.modifier,
        scrimColor = closer.scrim, sheetGesturesEnabled = false,
    ) {
        Column(Modifier.graphicsLayer { alpha = closer.alpha.value }) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("Giấy phép mã nguồn mở"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = closer.close) { Icon(Icons.Filled.Close, t("Đóng")) }
            }
            LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(licenses, key = { it.name }) { l ->
                    Column(
                        Modifier.fillMaxWidth().animateContentSize()
                            .then(if (l.asset != null) Modifier.bounceClick(pressedScale = 0.98f) { open = if (open == l.name) null else l.name } else Modifier)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Text(l.name, style = MaterialTheme.typography.titleSmall)
                        Text("${l.holder} · ${l.license}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (open == l.name && l.asset != null) {
                            val text = remember(l.asset) { runCatching { context.assets.open(l.asset).bufferedReader().readText() }.getOrDefault("") }
                            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Every release as a collapsible row; only the newest starts open. */
@Composable
private fun VersionHistorySheet(onDismiss: () -> Unit) {
    var open by remember { mutableStateOf(setOf(releases.first().version)) }
    // X, Back and a tap outside slide the sheet down and fade it instead of removing it at once.
    val closer = rememberSheetCloser(onDismiss)
    // Sheet gestures off: the list scrolls on its own and a fling reaching an end can no longer drag the sheet
    // and bounce it. Closes with the X button, a tap outside or Back.
    ModalBottomSheet(
        onDismissRequest = closer.close,
        sheetState = closer.state,
        modifier = closer.modifier,
        scrimColor = closer.scrim,
        sheetGesturesEnabled = false,
    ) {
      Column(Modifier.graphicsLayer { alpha = closer.alpha.value }) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Lịch sử phiên bản"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            androidx.compose.material3.IconButton(onClick = closer.close) { Icon(Icons.Filled.Close, t("Đóng")) }
        }
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(releases, key = { it.version }) { r ->
                val isOpen = r.version in open
                val turn by animateFloatAsState(if (isOpen) 180f else 0f, label = "turn")
                Column(Modifier.animateContentSize()) {
                    Row(
                        Modifier.fillMaxWidth().bounceClick(pressedScale = 0.98f) { open = if (isOpen) open - r.version else open + r.version }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t("Phiên bản %1\$s", r.version), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(com.tilt.wallpapers.data.I18n.date(r.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.ExpandMore, null, Modifier.graphicsLayer { rotationZ = turn }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isOpen) Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) { r.notes.forEach { Bullet(t(it)) } }
                }
            }
        }
      }
    }
}

/**
 * Shown once after the app is updated: the new version's highlights, which also confirms that the phone
 * really runs the new build. Fresh installs skip it.
 */
@Composable
fun WhatsNewOnUpdate() {
    val context = LocalContext.current
    val state by Repo.state.collectAsStateWithLifecycle()
    val code = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt() }.getOrDefault(0)
    }
    if (code == 0 || state.settings.seenVersion >= code) return
    val markSeen = { Repo.updateSettings { it.copy(seenVersion = code) } }
    if (state.sets.isEmpty()) { LaunchedEffect(Unit) { markSeen() }; return }
    val latest = releases.first()
    AlertDialog(
        onDismissRequest = markSeen,
        icon = { Icon(Icons.Filled.NewReleases, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(t("Đã cập nhật lên %1\$s", latest.version)) },
        text = { Column { latest.notes.take(3).forEach { Bullet(t(it)) } } },
        confirmButton = { AppButton(t("Đã hiểu"), onClick = markSeen) },
    )
}

@Composable
private fun ReleaseNotes(release: Release, icon: ImageVector?, title: String, limit: Int = Int.MAX_VALUE) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
            }
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(com.tilt.wallpapers.data.I18n.date(release.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(6.dp))
        release.notes.take(limit).forEach { Bullet(t(it)) }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Plain statement of what stays on the phone and what the AI features do. */
@Composable
fun PrivacyCard() {
    SettingsCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(t("Làm ra vì quyền riêng tư"), style = MaterialTheme.typography.titleMedium)
                Text(t("Ảnh của bạn là của bạn"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        PrivacyPoint(
            Icons.Filled.PhonelinkLock, t("Mọi thứ nằm trên máy"),
            t("Ảnh, bộ ảnh, chỉnh sửa và mặt nạ chỉ lưu trong bộ nhớ riêng của app. Không tài khoản, không đồng bộ đám mây, không quảng cáo, không theo dõi."),
        )
        PrivacyPoint(
            Icons.Filled.Memory, t("AI chạy ngay trên điện thoại"),
            t("Tách chủ thể (Depth), Lasso AI và tự tách nền ảnh ngoài đều dùng chung một mô hình: ML Kit Subject Segmentation của Google, chạy offline trên máy. App không gọi API AI của bên thứ ba và không gửi ảnh đi đâu."),
        )
        PrivacyPoint(
            Icons.Filled.CloudOff, t("Mạng chỉ dùng để tải mô hình"),
            t("Lần đầu bật Depth, Google Play Services tải mô hình về máy. Play Services có thể gửi thống kê ẩn danh về hiệu năng của ML Kit, không bao gồm ảnh. App không tự kết nối tới máy chủ nào."),
        )
        PrivacyPoint(
            Icons.Filled.FolderZip, t("Bạn quyết định khi chia sẻ"),
            t("File .zip chỉ được tạo khi bạn bấm Xuất, và lưu ở nơi bạn chọn."),
        )
    }
}

@Composable
private fun ColumnScope.PrivacyPoint(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
