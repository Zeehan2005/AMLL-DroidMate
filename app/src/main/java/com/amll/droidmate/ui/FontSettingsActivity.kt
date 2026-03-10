package com.amll.droidmate.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete

// suppress icon deprecation where used
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.amll.droidmate.ui.theme.DroidMateTheme
import com.amll.droidmate.ui.theme.DynamicThemeManager
import java.io.File
import java.io.IOException

class FontSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val isDarkTheme = isSystemInDarkTheme()
            val dynamicColorScheme by DynamicThemeManager.observeColorScheme()
            
            DroidMateTheme(
                darkTheme = isDarkTheme,
                dynamicColorScheme = dynamicColorScheme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FontSettingsPage(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FontSettingsPage(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    
    val loadExistingFonts = {
        val all = AppSettings.getAmllFontFiles(context)
        // filter out missing files and refresh any display names from the actual file
        val corrected = all.filter { File(it.absolutePath).exists() }.map { file ->
            val actualName = readFontFamilyName(File(file.absolutePath))
            if (!actualName.isNullOrBlank() && actualName != file.displayName) {
                // persist updated displayName
                AppSettings.upsertAmllFontFile(context, file.absolutePath, actualName)
                file.copy(displayName = actualName)
            } else {
                file
            }
        }
        if (corrected.size != all.size) {
            AppSettings.setAmllFontFiles(context, corrected)
        }
        corrected
    }

    var amllFontFamily by remember { mutableStateOf(AppSettings.getAmllFontFamily(context)) }
    var importedFonts by remember { mutableStateOf(loadExistingFonts()) }
    var enabledFontIds by remember { mutableStateOf(AppSettings.getEnabledAmllFontFileIds(context).toSet()) }
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

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(
            title = { Text("字体设置") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    @Suppress("DEPRECATION")
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
            val sortedFonts = importedFonts.sortedBy { it.fontFamilyName.lowercase() }

            OutlinedTextField(
                value = amllFontFamily,
                onValueChange = { amllFontFamily = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("font-family") },
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        AppSettings.setAmllFontFamily(context, amllFontFamily)
                        fontStatusMessage = "font-family设置已保存"
                    }
                ) {
                    Text("保存font-family设置")
                }

                Button(
                    onClick = {
                        importFontLauncher.launch(arrayOf("font/*"))
                    }
                ) {
                    Text("导入字体文件")
                }

                OutlinedButton(
                    onClick = {
                        AppSettings.resetAmllFontSettings(context)
                        amllFontFamily = AppSettings.getDefaultAmllFontFamily()
                        enabledFontIds = emptySet()
                        fontStatusMessage = "已还原为默认font-family设置"
                    }
                ) {
                    Text("还原font-family设置")
                }
            }

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
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.65f)
                                .heightIn(min = 48.dp), // 保持至少两行高度
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = font.displayName,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }

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

    // try to read internal family name, fallback to original name
    val internalName = readFontFamilyName(outFile)
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

/**
 * Attempts to parse the "name" table of a TrueType/OpenType font file to
 * extract the font family name (nameID = 1). This is the human-visible font
 * name such as "微软雅黑". Returns null if parsing fails.
 */
private fun readFontFamilyName(file: File): String? {
    try {
        java.io.RandomAccessFile(file, "r").use { raf ->
            // skip sfnt version
            raf.readInt()
            val numTables = raf.readUnsignedShort()
            raf.skipBytes(6) // searchRange, entrySelector, rangeShift
            for (i in 0 until numTables) {
                val tag = raf.readInt()
                val _checkSum = raf.readInt()
                val offset = raf.readInt()
                val _length = raf.readInt()
                // 'name' table tag
                if (tag == 0x6E616D65) {
                    raf.seek(offset.toLong())
                    val _format = raf.readUnsignedShort()
                    val count = raf.readUnsignedShort()
                    val stringOffset = raf.readUnsignedShort()
                    for (j in 0 until count) {
                        val platformID = raf.readUnsignedShort()
                        val _encodingID = raf.readUnsignedShort()
                        val _languageID = raf.readUnsignedShort()
                        val nameID = raf.readUnsignedShort()
                        val lengthEntry = raf.readUnsignedShort()
                        val offsetEntry = raf.readUnsignedShort()
                        if (nameID == 1) { // Font Family name
                            val pos = offset.toLong() + stringOffset + offsetEntry
                            raf.seek(pos)
                            val bytes = ByteArray(lengthEntry)
                            raf.readFully(bytes)
                            val encoding = when (platformID) {
                                0, 3 -> Charsets.UTF_16BE
                                else -> Charsets.ISO_8859_1
                            }
                            return try {
                                String(bytes, encoding)
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                    break
                }
            }
        }
    } catch (_: Exception) {
    }
    return null
}
