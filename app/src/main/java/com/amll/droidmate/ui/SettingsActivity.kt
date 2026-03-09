package com.amll.droidmate.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.amll.droidmate.ui.theme.DroidMateTheme
import com.amll.droidmate.update.GitHubUpdateChecker
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            DroidMateTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsPage(
                        onBack = { finish() },
                        onOpenNotificationSettings = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(
    onBack: () -> Unit,
    onOpenNotificationSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val loadExistingFonts = {
        val all = AppSettings.getAmllFontFiles(context)
        val existing = all.filter { File(it.absolutePath).exists() }
        if (existing.size != all.size) {
            AppSettings.setAmllFontFiles(context, existing)
        }
        existing
    }

    var selectedAction by remember { mutableStateOf(AppSettings.getCardClickAction(context)) }
    var lyricNotificationEnabled by remember { mutableStateOf(AppSettings.isLyricNotificationEnabled(context)) }
    var amllFontFamily by remember { mutableStateOf(AppSettings.getAmllFontFamily(context)) }
    var importedFonts by remember { mutableStateOf(loadExistingFonts()) }
    var enabledFontIds by remember { mutableStateOf(AppSettings.getEnabledAmllFontFileIds(context).toSet()) }
    var isFontSettingsPage by remember { mutableStateOf(false) }
    var fontStatusMessage by remember { mutableStateOf<String?>(null) }
    var autoCheckEnabled by remember { mutableStateOf(AppSettings.isAutoUpdateCheckEnabled(context)) }
    var updateChannel by remember { mutableStateOf(AppSettings.getUpdateChannel(context)) }
    var skipPreviousRewinds by remember { mutableStateOf(AppSettings.isSkipPreviousRewindsEnabled(context)) }
    var updateDialogTitle by remember { mutableStateOf("") }
    var updateDialogMessage by remember { mutableStateOf("") }
    var updateDialogUrl by remember { mutableStateOf<String?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val importFontLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult

        try {
            var importedCount = 0
            val newEnabled = enabledFontIds.toMutableSet()

            uris.forEach { uri ->
                val result = importFontToInternalStorage(context, uri)
                val updated = AppSettings.upsertAmllFontFile(
                    context = context,
                    absolutePath = result.absolutePath,
                    displayName = result.displayName
                )
                updated.firstOrNull { it.absolutePath == result.absolutePath }?.id?.let { newEnabled.add(it) }
                importedCount += 1
            }

            importedFonts = loadExistingFonts()
            enabledFontIds = newEnabled
            AppSettings.setEnabledAmllFontFileIds(context, newEnabled.toList())
            fontStatusMessage = "已导入 $importedCount 个字体文件"
        } catch (e: Exception) {
            fontStatusMessage = "导入失败: ${e.message ?: "未知错误"}"
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        lyricNotificationEnabled = granted
        AppSettings.setLyricNotificationEnabled(context, granted)
        if (!granted) {
            com.amll.droidmate.service.LyricNotificationManager(context).cancel()
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(
            title = { Text(if (isFontSettingsPage) "字体设置" else "设置") },
            navigationIcon = {
                IconButton(onClick = {
                    if (isFontSettingsPage) {
                        isFontSettingsPage = false
                    } else {
                        onBack()
                    }
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isFontSettingsPage) {
                val sortedFonts = importedFonts.sortedBy { it.fontFamilyName.lowercase() }

                OutlinedTextField(
                    value = amllFontFamily,
                    onValueChange = { amllFontFamily = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("font-family") },
                    supportingText = {
                        Text("启用字体会按 fontFamily 名称排序后拼接在最前，用于多语言覆盖")
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            AppSettings.setAmllFontFamily(context, amllFontFamily)
                            fontStatusMessage = "字体家族已保存"
                        }
                    ) {
                        Text("保存字体家族")
                    }

                    Button(
                        onClick = {
                            importFontLauncher.launch(arrayOf("font/*"))
                        }
                    ) {
                        Text("导入字体文件")
                    }
                }

                TextButton(
                    onClick = {
                        AppSettings.resetAmllFontSettings(context)
                        amllFontFamily = AppSettings.getDefaultAmllFontFamily()
                        enabledFontIds = emptySet()
                        fontStatusMessage = "已还原为默认字体设置"
                    }
                ) {
                    Text("一键还原默认")
                }

                if (sortedFonts.isEmpty()) {
                    Text(
                        text = "未导入字体文件，可直接使用上方 font-family。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                } else {
                    sortedFonts.forEach { font ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.fillMaxWidth(0.58f)) {
                                val enabledMark = if (enabledFontIds.contains(font.id)) " (已启用)" else ""
                                Text("${font.displayName}$enabledMark")
                                Text(
                                    text = font.fontFamilyName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Switch(
                                    checked = enabledFontIds.contains(font.id),
                                    onCheckedChange = { enabled ->
                                        val next = enabledFontIds.toMutableSet()
                                        if (enabled) {
                                            next.add(font.id)
                                        } else {
                                            next.remove(font.id)
                                        }
                                        enabledFontIds = next
                                        AppSettings.setEnabledAmllFontFileIds(context, next.toList())
                                        fontStatusMessage = if (enabled) {
                                            "已启用字体: ${font.displayName}"
                                        } else {
                                            "已停用字体: ${font.displayName}"
                                        }
                                    }
                                )

                                TextButton(
                                    onClick = {
                                        File(font.absolutePath).takeIf { it.exists() }?.delete()
                                        importedFonts = AppSettings.removeAmllFontFile(context, font.id)
                                        val next = enabledFontIds.toMutableSet().apply { remove(font.id) }
                                        enabledFontIds = next
                                        AppSettings.setEnabledAmllFontFileIds(context, next.toList())
                                        fontStatusMessage = "已删除字体: ${font.displayName}"
                                    }
                                ) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                }

                if (!fontStatusMessage.isNullOrBlank()) {
                    Text(
                        text = fontStatusMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
            Text(
                text = "实时歌词通知",
                style = MaterialTheme.typography.titleMedium
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.fillMaxWidth(0.78f)) {
                        Text("常驻通知显示当前句歌词")
                        Text(
                            text = "默认关闭，可在通知权限允许后实时更新。获得锁屏权限后可锁屏显示。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Switch(
                        checked = lyricNotificationEnabled,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                lyricNotificationEnabled = false
                                AppSettings.setLyricNotificationEnabled(context, false)
                                com.amll.droidmate.service.LyricNotificationManager(context).cancel()
                            } else {
                                if (needsNotificationPermission() && !hasNotificationPermission(context)) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    lyricNotificationEnabled = true
                                    AppSettings.setLyricNotificationEnabled(context, true)
                                }
                            }
                        }
                    )
                }
            }

            Text(                text = "“正在播放”卡片点击行为",
                style = MaterialTheme.typography.titleMedium
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CardClickActionOption(
                        title = "直接打开播放源应用",
                        selected = selectedAction == CardClickAction.DIRECT_OPEN,
                        onClick = {
                            selectedAction = CardClickAction.DIRECT_OPEN
                            AppSettings.setCardClickAction(context, CardClickAction.DIRECT_OPEN)
                        }
                    )
                    CardClickActionOption(
                        title = "询问 (推荐)",
                        selected = selectedAction == CardClickAction.ASK,
                        onClick = {
                            selectedAction = CardClickAction.ASK
                            AppSettings.setCardClickAction(context, CardClickAction.ASK)
                        }
                    )
                    CardClickActionOption(
                        title = "不操作",
                        selected = selectedAction == CardClickAction.NONE,
                        onClick = {
                            selectedAction = CardClickAction.NONE
                            AppSettings.setCardClickAction(context, CardClickAction.NONE)
                        }
                    )
                }
            }

            Text(
                text = "字体设置",
                style = MaterialTheme.typography.titleMedium
            )

            TextButton(
                onClick = {
                    importedFonts = loadExistingFonts()
                    enabledFontIds = AppSettings.getEnabledAmllFontFileIds(context).toSet()
                    isFontSettingsPage = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("打开字体设置（二级菜单）")
            }
            Text(                text = "辅助功能",
                style = MaterialTheme.typography.titleMedium
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.fillMaxWidth(0.75f)) {
                        Text("第一次点击上一首回到 0:00")
                        Text(
                            text = "启用后,点击上一首按钮会先回到歌曲开头,而不是直接跳转到上一首",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Switch(
                        checked = skipPreviousRewinds,
                        onCheckedChange = { enabled ->
                            skipPreviousRewinds = enabled
                            AppSettings.setSkipPreviousRewindsEnabled(context, enabled)
                        }
                    )
                }
            }


            

            Text(
                text = "版本更新",
                style = MaterialTheme.typography.titleMedium
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("当前版本: ${getCurrentVersionName(context)}")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.fillMaxWidth(0.75f)) {
                            Text("自动检查更新")
                            Text(
                                text = "启动后按频率自动检查 GitHub Release",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Switch(
                            checked = autoCheckEnabled,
                            onCheckedChange = { enabled ->
                                autoCheckEnabled = enabled
                                AppSettings.setAutoUpdateCheckEnabled(context, enabled)
                            }
                        )
                    }

                    Text(
                        text = "更新通道",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    CardClickActionOption(
                        title = "正式版 (vX.Y.Z)",
                        selected = updateChannel == UpdateChannel.STABLE,
                        onClick = {
                            updateChannel = UpdateChannel.STABLE
                            AppSettings.setUpdateChannel(context, UpdateChannel.STABLE)
                        }
                    )
                    CardClickActionOption(
                        title = "预览版 (Alpha 时间戳)",
                        selected = updateChannel == UpdateChannel.PREVIEW,
                        onClick = {
                            updateChannel = UpdateChannel.PREVIEW
                            AppSettings.setUpdateChannel(context, UpdateChannel.PREVIEW)
                        }
                    )

                    Button(
                        onClick = {
                            if (checkingUpdate) return@Button
                            checkingUpdate = true
                            scope.launch {
                                val result = GitHubUpdateChecker.check(context, updateChannel)
                                AppSettings.setLastUpdateCheckAt(context, System.currentTimeMillis())
                                checkingUpdate = false

                                if (result.hasUpdate) {
                                    updateDialogTitle = "发现新版本: ${result.resolvedReleaseTag ?: "未知版本"}"
                                    updateDialogMessage = buildString {
                                        append("当前版本: ${result.currentVersionName}\n")
                                        if (result.resolvedPublishedAt != null) {
                                            append("发布时间: ${formatReleaseTime(result.resolvedPublishedAt)}\n\n")
                                        }
                                        if (!result.resolvedReleaseNotes.isNullOrBlank()) {
                                            append(result.resolvedReleaseNotes)
                                        } else {
                                            append("暂无更新说明")
                                        }
                                    }
                                    updateDialogUrl = result.resolvedReleaseUrl
                                } else {
                                    updateDialogTitle = "检查更新"
                                    updateDialogMessage = result.reason ?: "当前已是最新版本"
                                    updateDialogUrl = null
                                }
                                showUpdateDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (checkingUpdate) "检查中..." else "检查更新")
                    }
                }
            }

            Text(
                text = "通知访问权限（获取正在播放）",
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(
                onClick = onOpenNotificationSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("打开通知访问设置")
            }

            Text(
                text = "项目与贡献",
                style = MaterialTheme.typography.titleMedium
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/Zeehan2005/AMLL-DroidMate")
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("项目仓库")
                    }

                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/amll-dev/amll-ttml-db/blob/main/README.md")
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("协助改进歌词")
                    }
                }
            }
            }
        }

        if (showUpdateDialog) {
            AlertDialog(
                onDismissRequest = { showUpdateDialog = false },
                title = { Text(updateDialogTitle) },
                text = { Text(updateDialogMessage) },
                confirmButton = {
                    if (!updateDialogUrl.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateDialogUrl)))
                                showUpdateDialog = false
                            }
                        ) {
                            Text("去更新")
                        }
                    } else {
                        TextButton(onClick = { showUpdateDialog = false }) {
                            Text("知道了")
                        }
                    }
                },
                dismissButton = {
                    if (!updateDialogUrl.isNullOrBlank()) {
                        TextButton(onClick = { showUpdateDialog = false }) {
                            Text("稍后")
                        }
                    }
                }
            )
        }
    }
}

private data class ImportedFontResult(
    val absolutePath: String,
    val displayName: String
)

@Throws(IOException::class)
private fun importFontToInternalStorage(context: android.content.Context, sourceUri: Uri): ImportedFontResult {
    val resolver = context.contentResolver
    val displayName = queryDisplayName(context, sourceUri) ?: "custom_font_${System.currentTimeMillis()}.ttf"
    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")

    val fontDir = File(context.filesDir, "amll_fonts")
    if (!fontDir.exists()) {
        fontDir.mkdirs()
    }

    val outFile = File(fontDir, "${System.currentTimeMillis()}_$safeName")
    resolver.openInputStream(sourceUri).use { input ->
        if (input == null) throw IOException("无法打开字体文件")
        outFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }

    return ImportedFontResult(
        absolutePath = outFile.absolutePath,
        displayName = displayName
    )
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    val resolver = context.contentResolver
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            return cursor.getString(nameIndex)
        }
    }
    return null
}

private fun formatReleaseTime(instant: java.time.Instant): String {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(instant)
}

private fun getCurrentVersionName(context: android.content.Context): String {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    return packageInfo.versionName ?: "unknown"
}

private fun needsNotificationPermission(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

private fun hasNotificationPermission(context: android.content.Context): Boolean {
    if (!needsNotificationPermission()) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun CardClickActionOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = title, modifier = Modifier.padding(start = 8.dp))
    }
}
