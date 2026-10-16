package com.tilt.wallpapers.ui.screens

import com.tilt.wallpapers.data.t

import android.app.StatusBarManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RestorePage
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tilt.wallpapers.R
import com.tilt.wallpapers.data.AppState
import com.tilt.wallpapers.data.Backup
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.TrashItem
import com.tilt.wallpapers.tile.TiltTileService
import com.tilt.wallpapers.ui.components.AppButton
import com.tilt.wallpapers.ui.components.BtnStyle
import com.tilt.wallpapers.ui.components.EditedImage
import com.tilt.wallpapers.ui.components.GlassSurface
import com.tilt.wallpapers.ui.components.bounceClick
import com.tilt.wallpapers.ui.components.screenAspect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface Job {
    data class Working(val title: String, val progress: Float) : Job
    data class Done(val title: String, val message: String) : Job
    data class Failed(val message: String) : Job
}

/** Quick-settings tile, backup / restore and trash. */
@Composable
fun UtilitiesCard(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var summary by remember { mutableStateOf<Backup.Summary?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    val estimate = remember(state.sets) { Backup.estimate() }

    // A type with no extension of its own: with octet-stream the file manager added ".bin" to the name, and some
    // (ColorOS) take the type's last word as the extension, so the type ends in "tilt" itself.
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-tilt")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        job = Job.Working(t("Đang sao lưu…"), 0f)
        scope.launch {
            val ok = Backup.export(context, uri) { p -> job = Job.Working(t("Đang sao lưu…"), p) }
            job = if (ok) Job.Done(t("Đã sao lưu"), t("%1\$s bộ ảnh, %2\$s ảnh cùng cài đặt, lịch và màu theme đã nằm trong file. Mở file này bằng Khôi phục trên máy mới.", state.sets.size, state.sets.sumOf { it.images.size }))
                else Job.Failed(t("Không ghi được file sao lưu. Hãy thử chọn nơi lưu khác."))
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val s = Backup.summary(context, uri)
            if (s == null) job = Job.Failed(t("File này không phải bản sao lưu của Tilt."))
            else { restoreUri = uri; summary = s }
        }
    }
    fun restore(replace: Boolean) {
        val uri = restoreUri ?: return
        restoreUri = null; summary = null; confirmReplace = false
        job = Job.Working(t("Đang khôi phục…"), 0f)
        scope.launch {
            val ok = Backup.restore(context, uri, replace) { p -> job = Job.Working(t("Đang khôi phục…"), p) }
            job = if (ok) Job.Done(t("Đã khôi phục"), if (replace) t("Toàn bộ bộ ảnh và cài đặt đã được thay bằng bản sao lưu.") else t("Các bộ ảnh trong bản sao lưu đã được thêm vào danh sách."))
                else Job.Failed(t("Không khôi phục được. File có thể bị hỏng."))
        }
    }

    SettingsCard {
        SwitchRow(
            t("Tự căn theo chủ thể"),
            t("Ảnh mới thêm vào tự đặt khung cắt để nhân vật nằm giữa màn hình (cần bật Depth). Ảnh cũ: tab Cắt → Căn theo chủ thể"),
            state.settings.autoCropSubject, Icons.Filled.CenterFocusWeak,
        ) { v -> Repo.updateSettings { it.copy(autoCropSubject = v) } }
        if (Build.VERSION.SDK_INT >= 33) {
            UtilityRow(Icons.Filled.SettingsSuggest, t("Thêm ô Tilt vào Cài đặt nhanh"), t("Chạm ô để đổi bộ ảnh, khóa nghiêng, sang ảnh tiếp")) {
                val sbm = context.getSystemService(StatusBarManager::class.java)
                sbm?.requestAddTileService(
                    TiltTileService.component(context), "Tilt", Icon.createWithResource(context, R.drawable.ic_tile),
                    context.mainExecutor,
                ) { }
            }
        }
        UtilityRow(
            Icons.Filled.Backup, t("Sao lưu tất cả"),
            t("Mọi bộ ảnh, chỉnh sửa, mặt nạ, cài đặt, lịch và màu theme · khoảng %1\$s", Formatter.formatShortFileSize(context, estimate)),
            enabled = state.sets.isNotEmpty(),
        ) {
            exporter.launch("${t("Tilt-sao-luu")}-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.tilt")
        }
        UtilityRow(Icons.Filled.RestorePage, t("Khôi phục từ bản sao lưu"), t("Chọn file .tilt để gộp vào hoặc thay toàn bộ")) {
            importer.launch(arrayOf("*/*"))
        }
        UtilityRow(
            Icons.Filled.DeleteSweep, t("Thùng rác"),
            if (state.trash.isEmpty()) t("Trống · mục đã xóa được giữ %1\$s ngày", TrashItem.TRASH_DAYS) else t("%1\$s mục · tự xóa hẳn sau %2\$s ngày", state.trash.size, TrashItem.TRASH_DAYS),
        ) { showTrash = true }
    }

    summary?.let { s ->
        AlertDialog(
            onDismissRequest = { summary = null; restoreUri = null },
            icon = { Icon(Icons.Filled.RestorePage, null) },
            title = { Text(t("Khôi phục bản sao lưu?")) },
            text = {
                Text(
                    t("Bản sao lưu ngày %1\$s: %2\$s bộ ảnh, %3\$s ảnh.\n\n", SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date(s.createdAt)), s.sets, s.images) +
                        t("Gộp vào: thêm các bộ này, giữ nguyên bộ ảnh và cài đặt hiện có.\nThay thế toàn bộ: xóa hết bộ ảnh hiện có, dùng cả cài đặt trong bản sao lưu."),
                )
            },
            confirmButton = { AppButton(t("Gộp vào")) { restore(replace = false) } },
            dismissButton = { AppButton(t("Thay thế toàn bộ"), style = BtnStyle.Text) { confirmReplace = true } },
        )
    }
    if (confirmReplace) AlertDialog(
        onDismissRequest = { confirmReplace = false },
        icon = { Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(t("Thay thế toàn bộ?")) },
        text = { Text(t("%1\$s bộ ảnh hiện có và thùng rác sẽ bị xóa hẳn, không vào Thùng rác. Không thể hoàn tác.", state.sets.size)) },
        confirmButton = { AppButton(t("Thay thế"), style = BtnStyle.Danger) { restore(replace = true) } },
        dismissButton = { AppButton(t("Hủy"), style = BtnStyle.Text) { confirmReplace = false } },
    )
    job?.let { j -> JobDialog(j) { job = null } }
    if (showTrash) TrashSheet { showTrash = false }
}

@Composable
private fun UtilityRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().bounceClick(enabled = enabled, pressedScale = 0.97f, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = cs.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface.copy(alpha = if (enabled) 1f else 0.4f))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f))
        }
    }
}

@Composable
private fun JobDialog(job: Job, onDismiss: () -> Unit) {
    val hf = LocalHapticFeedback.current
    when (job) {
        is Job.Working -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(job.title) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearWavyProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(job.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                }
            },
            confirmButton = {},
        )
        is Job.Done -> {
            LaunchedEffect(job) { hf.performHapticFeedback(HapticFeedbackType.Confirm) }
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = { Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text(job.title) },
                text = { Text(job.message, textAlign = TextAlign.Center) },
                confirmButton = { AppButton(t("Xong"), onClick = onDismiss) },
            )
        }
        is Job.Failed -> {
            LaunchedEffect(job) { hf.performHapticFeedback(HapticFeedbackType.Reject) }
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = { Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error) },
                title = { Text(t("Không thành công")) },
                text = { Text(job.message, textAlign = TextAlign.Center) },
                confirmButton = { AppButton(t("Đóng"), style = BtnStyle.Tonal, onClick = onDismiss) },
            )
        }
    }
}

/** Deleted sets and pictures with days left; restore one by one or empty it. */
@Composable
private fun TrashSheet(onDismiss: () -> Unit) {
    val state by Repo.state.collectAsStateWithLifecycle()
    var confirmEmpty by remember { mutableStateOf(false) }
    val aspect = screenAspect()
    val closer = rememberSheetCloser(onDismiss)
    ModalBottomSheet(onDismissRequest = closer.close, sheetState = closer.state, modifier = closer.modifier, scrimColor = closer.scrim) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Thùng rác"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (state.trash.isNotEmpty()) AppButton(t("Xóa hẳn tất cả"), icon = Icons.Filled.Delete, style = BtnStyle.Text) { confirmEmpty = true }
        }
        if (state.trash.isEmpty()) {
            Text(
                t("Không có gì trong thùng rác. Bộ ảnh hay ảnh bạn xóa sẽ nằm ở đây %1\$s ngày trước khi bị xóa hẳn.", TrashItem.TRASH_DAYS),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp).navigationBarsPadding(),
            )
        } else LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(state.trash, key = { it.id }) { item ->
                val daysLeft = (TrashItem.TRASH_DAYS - (System.currentTimeMillis() - item.deletedAt) / 86_400_000L).coerceAtLeast(0)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(44.dp).aspectRatio(aspect).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        item.images.firstOrNull()?.let { EditedImage(it, Modifier.matchParentSize()) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.set?.name ?: t("Ảnh từ \"%1\$s\"", item.fromSetName ?: t("bộ đã xóa")),
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            (if (item.set != null) t("Bộ ảnh · %1\$s ảnh · ", item.set.images.size) else "") + t("còn %1\$s ngày", daysLeft),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppButton(t("Khôi phục"), icon = Icons.Filled.Restore, style = BtnStyle.Tonal) { Repo.restoreTrash(listOf(item.id)) }
                }
            }
        }
    }
    if (confirmEmpty) AlertDialog(
        onDismissRequest = { confirmEmpty = false },
        icon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(t("Xóa hẳn %1\$s mục?", state.trash.size)) },
        text = { Text(t("Các bộ ảnh và ảnh này sẽ bị xóa khỏi máy. Không thể hoàn tác.")) },
        confirmButton = { AppButton(t("Xóa hẳn"), style = BtnStyle.Danger) { Repo.emptyTrash(); confirmEmpty = false } },
        dismissButton = { AppButton(t("Hủy"), style = BtnStyle.Text) { confirmEmpty = false } },
    )
}

/** "Đã xóa · Hoàn tác" for 5 seconds after any deletion, above the navigation bar. */
