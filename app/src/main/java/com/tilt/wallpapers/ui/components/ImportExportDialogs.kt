package com.tilt.wallpapers.ui.components

import com.tilt.wallpapers.data.t

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.data.WallSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State of an import / export. */
private sealed interface Transfer {
    data class Working(val text: String) : Transfer
    data class Done(val set: WallSet, val fileName: String?) : Transfer
    data class Failed(val message: String) : Transfer
}

@Composable
fun ExportSetDialog(setId: String, setName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<Transfer?>(null) }
    var target by remember { mutableStateOf<Uri?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) { onDismiss(); return@rememberLauncherForActivityResult }
        target = uri
        phase = Transfer.Working(t("Đang xuất bộ ảnh…"))
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { Repo.exportSet(setId, it) } ?: false }.getOrDefault(false)
            }
            val set = Repo.state.value.sets.find { it.id == setId }
            phase = if (ok && set != null) Transfer.Done(set, displayName(context, uri))
                else Transfer.Failed(t("Không ghi được file. Hãy thử chọn thư mục khác hoặc kiểm tra dung lượng trống."))
        }
    }
    LaunchedEffect(Unit) { launcher.launch(safeFileName(setName) + ".zip") }

    when (val p = phase) {
        null -> Unit
        is Transfer.Working -> WorkingDialog(p.text)
        is Transfer.Failed -> FailedDialog(t("Xuất không thành công"), p.message, onDismiss)
        is Transfer.Done -> SuccessDialog(
            title = t("Đã xuất bộ ảnh"),
            message = t("Đã lưu %1\$s. Mang file này sang máy khác rồi chọn Nhập để dùng lại nguyên bộ, kể cả chỉnh sửa và chiều sâu.", p.fileName?.let { "“$it”" } ?: "file .zip"),
            set = p.set,
            primaryLabel = t("Chia sẻ"),
            primaryIcon = Icons.Filled.Share,
            onPrimary = {
                target?.let { uri ->
                    val send = Intent(Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(Intent.createChooser(send, t("Chia sẻ bộ ảnh"))) }
                }
                onDismiss()
            },
            secondaryLabel = t("Xong"),
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun ImportSetDialog(onDismiss: () -> Unit, onSuccess: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<Transfer?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { onDismiss(); return@rememberLauncherForActivityResult }
        phase = Transfer.Working(t("Đang kiểm tra file…"))
        scope.launch {
            val isZip = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { inp ->
                        val h = ByteArray(2)
                        inp.read(h) == 2 && h[0] == 0x50.toByte() && h[1] == 0x4B.toByte()
                    } ?: false
                }.getOrDefault(false)
            }
            if (!isZip) {
                phase = Transfer.Failed(t("File này không phải bộ ảnh .zip được xuất từ Tilt."))
                return@launch
            }
            phase = Transfer.Working(t("Đang nhập bộ ảnh…"))
            val newId = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { Repo.importSet(it) } }.getOrNull()
            }
            val set = newId?.let { id -> Repo.state.value.sets.find { it.id == id } }
            phase = if (set != null) Transfer.Done(set, null)
                else Transfer.Failed(t("File bị hỏng hoặc không đúng định dạng bộ ảnh của Tilt."))
        }
    }
    LaunchedEffect(Unit) { launcher.launch(arrayOf("application/zip", "application/octet-stream")) }

    when (val p = phase) {
        null -> Unit
        is Transfer.Working -> WorkingDialog(p.text)
        is Transfer.Failed -> FailedDialog(t("Nhập không thành công"), p.message, onDismiss)
        is Transfer.Done -> SuccessDialog(
            title = t("Đã nhập bộ ảnh"),
            message = t("“%1\$s” đã được thêm vào danh sách bộ ảnh của bạn.", p.set.name),
            set = p.set,
            primaryLabel = t("Mở bộ ảnh"),
            primaryIcon = Icons.Filled.Collections,
            onPrimary = { onSuccess(p.set.id); onDismiss() },
            secondaryLabel = t("Để sau"),
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun WorkingDialog(text: String) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = { LoadingIndicator(Modifier.size(56.dp)) },
        title = { Text(text, textAlign = TextAlign.Center) },
        confirmButton = {},
    )
}

@Composable
private fun SuccessDialog(
    title: String,
    message: String,
    set: WallSet,
    primaryLabel: String,
    primaryIcon: ImageVector,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onDismiss: () -> Unit,
) {
    val hf = LocalHapticFeedback.current
    LaunchedEffect(Unit) { hf.performHapticFeedback(HapticFeedbackType.Confirm) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { PopBadge(Icons.Filled.Check, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) },
        title = { Text(title, textAlign = TextAlign.Center) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                SetStats(set)
            }
        },
        confirmButton = { AppButton(primaryLabel, icon = primaryIcon, onClick = onPrimary) },
        dismissButton = { AppButton(secondaryLabel, style = BtnStyle.Text, onClick = onDismiss) },
    )
}

@Composable
private fun FailedDialog(title: String, message: String, onDismiss: () -> Unit) {
    val hf = LocalHapticFeedback.current
    LaunchedEffect(Unit) { hf.performHapticFeedback(HapticFeedbackType.Reject) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { PopBadge(Icons.Filled.Error, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) },
        title = { Text(title, textAlign = TextAlign.Center) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) },
        confirmButton = { AppButton(t("Đóng"), style = BtnStyle.Tonal, onClick = onDismiss) },
    )
}

/** Round badge that pops in with an overshoot, the icon following a beat later. */
@Composable
private fun PopBadge(icon: ImageVector, container: Color, content: Color) {
    val ring = remember { Animatable(0.4f) }
    val glyph = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { ring.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f)) }
        kotlinx.coroutines.delay(90)
        glyph.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 420f))
    }
    Box(
        Modifier.size(64.dp).graphicsLayer { scaleX = ring.value; scaleY = ring.value }.clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(34.dp).graphicsLayer { scaleX = glyph.value; scaleY = glyph.value; alpha = glyph.value }, tint = content)
    }
}

/** What the zip carries: images plus every depth layer. */
@Composable
private fun SetStats(set: WallSet) {
    val chips = buildList {
        add(Icons.Filled.Collections to t("%1\$s ảnh", set.images.size))
        set.images.count { it.hasMask }.takeIf { it > 0 }?.let { add(Icons.Filled.AutoFixHigh to t("%1\$s mặt nạ AI", it)) }
        set.images.count { it.hasManualMask }.takeIf { it > 0 }?.let { add(Icons.Filled.Brush to t("%1\$s mặt nạ tự vẽ", it)) }
        set.images.count { it.hasSubject }.takeIf { it > 0 }?.let { add(Icons.Filled.Layers to t("%1\$s chủ thể", it)) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { (icon, label) ->
            Row(
                Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

private fun safeFileName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Bo anh" }

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
