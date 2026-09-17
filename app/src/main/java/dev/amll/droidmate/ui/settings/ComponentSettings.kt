package dev.amll.droidmate.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.amll.droidmate.global.AMLLSettings
import io.github.zeehan2005.scoremuse.global.theme.DynamicThemeManager
import io.github.zeehan2005.scoremuse.ui.BaseComposeActivity
import io.github.zeehan2005.scoremuse.ui.components.SwitchWithIcon
import io.github.zeehan2005.scoremuse.ui.LyricsVisualLayer
import kotlinx.coroutines.delay
import io.github.zeehan2005.scoremuse.global.LyricLine
import io.github.zeehan2005.scoremuse.global.LyricWord
import io.github.zeehan2005.scoremuse.global.LyricsMetadata
import io.github.zeehan2005.scoremuse.global.NowPlayingMusic
import io.github.zeehan2005.scoremuse.global.UnifiedLyrics
import java.io.File
import java.io.IOException

class ComponentSettings : BaseComposeActivity() {
    @Composable
    override fun RenderContent() {
        ComponentSettingsPage(onBack = { finish() })
    }
}

@Composable
private fun ComponentSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current

    val dynamicColorScheme by DynamicThemeManager.observeColorScheme()
    val rippleColor = dynamicColorScheme?.primary ?: MaterialTheme.colorScheme.primary
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = rippleColor,
        checkedTrackColor = rippleColor.copy(alpha = 0.5f),
        checkedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
        uncheckedTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.24f)
    )

    var blurEnabled by remember {
        mutableStateOf(AMLLSettings.isAmllAnimationBlurEnabled(context) ?: false)
    }
    var springEnabled by remember {
        mutableStateOf(AMLLSettings.isAmllAnimationSpringEnabled(context) ?: true)
    }
    var backgroundRendererEnabled by remember {
        mutableStateOf(AMLLSettings.isAmllBackgroundRendererEnabled(context) ?: false)
    }

    // 字体设置状态
    var amllFontFamily by remember { mutableStateOf(AMLLSettings.getAmllFontFamily(context)) }
    // 字重与字号状态
    var fontWeight by remember { mutableStateOf(AMLLSettings.getAmllFontWeight(context) ?: AMLLSettings.DEFAULT_AMLL_FONT_WEIGHT) }
    var useDefaultFontSize by remember { mutableStateOf(AMLLSettings.getAmllLyricFontSize(context) == null) }
    var lyricFontSize by remember { mutableStateOf(AMLLSettings.getAmllLyricFontSize(context) ?: AMLLSettings.DEFAULT_AMLL_LYRIC_FONT_SIZE) }
    var importedFonts by remember {
        mutableStateOf(
            AMLLSettings.getAmllFontFiles(context).filter { File(it.absolutePath).exists() }
        )
    }
    var enabledFontIds by remember { mutableStateOf(AMLLSettings.getEnabledAmllFontFileIds(context).toSet()) }
    var fontStatusMessage by remember { mutableStateOf<String?>(null) }

    val importFontLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult

        try {
            var importedCount = 0
            val newEnabled = enabledFontIds.toMutableSet()

            uris.forEach { uri ->
                val result = importFontToInternalStorage(context, uri)
                val updated = AMLLSettings.upsertAmllFontFile(
                    context = context,
                    absolutePath = result.absolutePath,
                    displayName = result.displayName
                )
                updated.firstOrNull { it.absolutePath == result.absolutePath }?.id?.let { newEnabled.add(it) }
                importedCount += 1
            }

            importedFonts = AMLLSettings.getAmllFontFiles(context).filter { File(it.absolutePath).exists() }
            enabledFontIds = newEnabled
            AMLLSettings.setEnabledAmllFontFileIds(context, newEnabled.toList())
            fontStatusMessage = "已导入 $importedCount 个字体文件"
        } catch (e: Exception) {
            fontStatusMessage = "导入失败: ${e.message ?: "未知错误"}"
        }
    }

    val previewWordDurationMs = 500L
    val previewLongWordDurationMs = 1000L
    val previewDurationMs = 4 * previewWordDurationMs + 2 * previewLongWordDurationMs
    var previewTimeMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100L)
            previewTimeMs = (previewTimeMs + 100L) % previewDurationMs
        }
    }

    val previewAlbumArtUri = remember(context) {
        "android.resource://${context.packageName}/drawable/background_blue_black_light_1591226"
    }
    val previewLyrics = remember {
        UnifiedLyrics(
            metadata = LyricsMetadata(
                title = "Preview",
                artist = "AMLL",
                duration = previewDurationMs
            ),
            lines = listOf(
                LyricLine(
                    startTime = 0L,
                    endTime = 2 * previewWordDurationMs + previewLongWordDurationMs,
                    text = "欢迎使用 DroidMate",
                    words = listOf(
                        LyricWord("欢迎", 0L, previewWordDurationMs),
                        LyricWord("使用 ", previewWordDurationMs, 2 * previewWordDurationMs),
                        LyricWord("DroidMate", 2 * previewWordDurationMs, 2 * previewWordDurationMs + previewLongWordDurationMs)
                    )
                ),
                LyricLine(
                    startTime = 2 * previewWordDurationMs + previewLongWordDurationMs,
                    endTime = 4 * previewWordDurationMs + 2 * previewLongWordDurationMs,
                    text = "Welcome to DroidMate",
                    words = listOf(
                        LyricWord("Welcome ", 2 * previewWordDurationMs + previewLongWordDurationMs, 3 * previewWordDurationMs + previewLongWordDurationMs),
                        LyricWord("to ", 3 * previewWordDurationMs + previewLongWordDurationMs, 4 * previewWordDurationMs + previewLongWordDurationMs),
                        LyricWord("DroidMate", 4 * previewWordDurationMs + previewLongWordDurationMs,4 * previewWordDurationMs + 2 * previewLongWordDurationMs )
                    )
                )
            )
        )
    }
    val previewNowPlaying = remember(previewTimeMs, previewAlbumArtUri) {
        NowPlayingMusic(
            title = "Preview",
            artist = "AMLL",
            duration = previewDurationMs,
            currentPosition = previewTimeMs,
            isPlaying = true,
            albumArtUri = previewAlbumArtUri
        )
    }

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("歌词组件设置") },
                navigationIcon = {
                    FilledIconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                ),
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                LyricsVisualLayer(
                    nowPlaying = previewNowPlaying,
                    lyrics = previewLyrics,
                    currentTime = previewTimeMs,
                    webViewReloadKey = 0,
                    onLineSeek = {},
                    debugSource = "GraphicSettingsPreview",
                    useAndroidBlurOverride = !backgroundRendererEnabled,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Text(
                text = "歌词动画",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val newState = !blurEnabled
                        blurEnabled = newState
                        AMLLSettings.setAmllAnimationBlurEnabled(context, newState)
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.fillMaxWidth(0.75f)) {
                        Text("歌词模糊", color = MaterialTheme.colorScheme.onSurface)
                    }
                    SwitchWithIcon(
                        checked = blurEnabled,
                        onCheckedChange = { enabled ->
                            blurEnabled = enabled
                            AMLLSettings.setAmllAnimationBlurEnabled(context, enabled)
                        },
                        colors = switchColors
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val newState = !springEnabled
                        springEnabled = newState
                        AMLLSettings.setAmllAnimationSpringEnabled(context, newState)
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.fillMaxWidth(0.75f)) {
                        Text("歌词弹簧", color = MaterialTheme.colorScheme.onSurface)
                    }
                    SwitchWithIcon(
                        checked = springEnabled,
                        onCheckedChange = { enabled ->
                            springEnabled = enabled
                            AMLLSettings.setAmllAnimationSpringEnabled(context, enabled)
                        },
                        colors = switchColors
                    )
                }
            }

            Text(
                text = "背景",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val newState = !backgroundRendererEnabled
                        backgroundRendererEnabled = newState
                        AMLLSettings.setAmllBackgroundRendererEnabled(context, newState)
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.fillMaxWidth(0.75f)) {
                        Text("AMLL 背景渲染器", color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = "关闭后使用 Android 实现模糊",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    SwitchWithIcon(
                        checked = backgroundRendererEnabled,
                        onCheckedChange = { enabled ->
                            backgroundRendererEnabled = enabled
                            AMLLSettings.setAmllBackgroundRendererEnabled(context, enabled)
                        },
                        colors = switchColors
                    )
                }
            }

            // ==================== 字体设置 ====================
            Text(
                text = "字体设置",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            OutlinedTextField(
                value = amllFontFamily,
                onValueChange = { amllFontFamily = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("font-family") },
                singleLine = false,
                maxLines = 3
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        AMLLSettings.setAmllFontFamily(context, amllFontFamily)
                        fontStatusMessage = "font-family 设置已保存"
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存")
                }

                Button(
                    onClick = {
                        importFontLauncher.launch(arrayOf("font/*"))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导入字体")
                }

                OutlinedButton(
                    onClick = {
                        AMLLSettings.resetAmllFontSettings(context)
                        amllFontFamily = AMLLSettings.getDefaultAmllFontFamily()
                        enabledFontIds = emptySet()
                        importedFonts = emptyList()
                        fontWeight = AMLLSettings.DEFAULT_AMLL_FONT_WEIGHT
                        AMLLSettings.setAmllFontWeight(context, fontWeight)
                        useDefaultFontSize = true
                        lyricFontSize = AMLLSettings.DEFAULT_AMLL_LYRIC_FONT_SIZE
                        AMLLSettings.setAmllLyricFontSize(context, 0)
                        fontStatusMessage = "已还原为默认 font-family 设置"
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("还原")
                }
            }

            // ==================== 字重与字号调节 ====================
            Text(
                text = "字重与字号",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 字重滑块
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "字重：$fontWeight",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when (fontWeight) {
                        in 100..199 -> "细体"
                        in 200..299 -> "特轻"
                        in 300..399 -> "轻体"
                        400 -> "常规"
                        in 401..499 -> "中粗"
                        500 -> "中等"
                        in 501..599 -> "中粗+"
                        600 -> "半粗"
                        in 601..699 -> "加粗-"
                        700 -> "加粗"
                        in 701..799 -> "特粗-"
                        800 -> "特粗"
                        in 801..900 -> "黑体"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            Slider(
                value = fontWeight.toFloat(),
                onValueChange = {
                    fontWeight = it.toInt()
                    AMLLSettings.setAmllFontWeight(context, fontWeight)
                },
                valueRange = 100f..900f,
                steps = 7,
                modifier = Modifier.fillMaxWidth()
            )

            // 字号：自适应默认 / 手动指定
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (useDefaultFontSize) "字体大小：自适应默认" else "字体大小：$lyricFontSize px",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                SwitchWithIcon(
                    checked = useDefaultFontSize,
                    onCheckedChange = { enabled ->
                        useDefaultFontSize = enabled
                        if (enabled) {
                            AMLLSettings.setAmllLyricFontSize(context, 0)
                        } else {
                            AMLLSettings.setAmllLyricFontSize(context, lyricFontSize)
                        }
                    },
                    colors = switchColors
                )
            }
            Slider(
                value = lyricFontSize.toFloat(),
                onValueChange = {
                    lyricFontSize = it.toInt()
                    if (!useDefaultFontSize) AMLLSettings.setAmllLyricFontSize(context, lyricFontSize)
                },
                valueRange = 12f..48f,
                steps = 0,
                enabled = !useDefaultFontSize,
                modifier = Modifier.fillMaxWidth()
            )

            // 已导入字体列表
            val sortedFonts = importedFonts.sortedBy { it.fontFamilyName.lowercase() }
            if (sortedFonts.isEmpty()) {
                Text(
                    text = "未导入字体文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            } else {
                Text(
                    text = "已导入的字体",
                    style = MaterialTheme.typography.titleMedium
                )
                sortedFonts.forEach { font ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .heightIn(min = 48.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = font.displayName,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .clickable {
                                        File(font.absolutePath).takeIf { it.exists() }?.delete()
                                        importedFonts = AMLLSettings.removeAmllFontFile(context, font.id)
                                        val next = enabledFontIds.toMutableSet().apply { remove(font.id) }
                                        enabledFontIds = next
                                        AMLLSettings.setEnabledAmllFontFileIds(context, next.toList())
                                        fontStatusMessage = "已删除字体: ${font.displayName}"
                                    }
                                    .padding(8.dp)
                            )

                            SwitchWithIcon(
                                checked = enabledFontIds.contains(font.id),
                                onCheckedChange = { enabled ->
                                    val next = enabledFontIds.toMutableSet()
                                    if (enabled) {
                                        next.add(font.id)
                                    } else {
                                        next.remove(font.id)
                                    }
                                    enabledFontIds = next
                                    AMLLSettings.setEnabledAmllFontFileIds(context, next.toList())
                                    fontStatusMessage = if (enabled) {
                                        "已启用字体: ${font.displayName}"
                                    } else {
                                        "已停用字体: ${font.displayName}"
                                    }
                                },
                                colors = switchColors
                            )
                        }
                    }
                }
            }

            // 字体状态消息
            if (!fontStatusMessage.isNullOrBlank()) {
                Text(
                    text = fontStatusMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
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
    val rawName = queryDisplayName(context, sourceUri) ?: "custom_font_${System.currentTimeMillis()}.ttf"
    val safeName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_")

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

    // 尝试读取内部族名，失败则回退到原始文件名
    val internalName = AMLLSettings.readFontFamilyName(outFile)
    val displayName = internalName ?: rawName

    return ImportedFontResult(
        absolutePath = outFile.absolutePath,
        displayName = displayName
    )
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    val resolver = context.contentResolver
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex != -1 && cursor.moveToFirst()) {
            return cursor.getString(nameIndex)
        }
    }
    return null
}