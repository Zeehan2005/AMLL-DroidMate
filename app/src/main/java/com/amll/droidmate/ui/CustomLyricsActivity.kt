package com.amll.droidmate.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Storage

// icons are deprecated but AutoMirrored is unavailable; suppress warnings where used
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amll.droidmate.ui.theme.DroidMateTheme
import com.amll.droidmate.ui.theme.DynamicThemeManager
import com.amll.droidmate.ui.viewmodel.CustomLyricsCandidate
import com.amll.droidmate.ui.viewmodel.CustomLyricsViewModel

class CustomLyricsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty()
        val playbackSource = intent.getStringExtra(EXTRA_PLAYBACK_SOURCE)

        // 我们不再在 Activity 里阻止搜索，即使有缓存也继续显示候选
        // （缓存优先逻辑由 ViewModel 处理）

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
                    val viewModel: CustomLyricsViewModel = viewModel()
                    // tell VM about playback app so ranking rules can use it
                    LaunchedEffect(playbackSource) {
                        viewModel.updateCurrentSource(playbackSource)
                    }
                    val appliedSource by viewModel.appliedLyricsSource.collectAsState()

                    CustomLyricsPage(
                        title = title,
                        artist = artist,
                        onBack = { finish() },
                        onApply = { lyricsText ->
                            val result = Intent().apply {
                                putExtra(EXTRA_TITLE, title)
                                putExtra(EXTRA_ARTIST, artist)
                                putExtra(EXTRA_LYRICS_TEXT, lyricsText)
                                putExtra(EXTRA_SOURCE, appliedSource ?: "manual")
                            }
                            setResult(RESULT_OK, result)
                            finish()
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_LYRICS_TEXT = "extra_lyrics_text"
        const val EXTRA_SOURCE = "extra_lyrics_source"
        const val EXTRA_PLAYBACK_SOURCE = "extra_playback_source"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomLyricsPage(
    title: String,
    artist: String,
    onBack: () -> Unit,
    onApply: (String) -> Unit
) {
    val vm: CustomLyricsViewModel = viewModel()
    val candidates by vm.candidates.collectAsState()
    // Hide local-cache entries from the displayed list
    val visibleCandidates = candidates.filter { it.provider.lowercase() != "cache" }
    val isSearching by vm.isSearching.collectAsState()
    val isApplying by vm.isApplying.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()
    val appliedLyricsText by vm.appliedLyricsText.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var manualText by remember { mutableStateOf("") }

    // File picker launcher for importing lyrics from file
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val fileContent = inputStream.bufferedReader().use { it.readText() }
                    manualText = fileContent
                    // 自动应用导入的歌词
                    vm.applyManualInput(fileContent, title, artist)
                } else {
                    // Error reading file
                }
            } catch (e: Exception) {
                // Error handling
            }
        }
    }

    LaunchedEffect(title, artist) {
        vm.searchCandidates(title, artist)
    }

    LaunchedEffect(appliedLyricsText) {
        if (!appliedLyricsText.isNullOrBlank()) {
            onApply(appliedLyricsText!!)
            vm.consumeAppliedLyricsText()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        TopAppBar(
            title = { Text("自选歌词") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    @Suppress("DEPRECATION")
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = {
                    context.startActivity(Intent(context, LyricsCacheActivity::class.java))
                }) {
                    @Suppress("DEPRECATION")
                    Icon(Icons.Default.Storage, contentDescription = "管理缓存歌词")
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = if (title.isNotBlank()) title else "未识别歌曲", style = MaterialTheme.typography.titleMedium)
                    Text(text = if (artist.isNotBlank()) artist else "未知歌手", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text(
                text = "选择歌词来源",
                style = MaterialTheme.typography.titleMedium
            )

            // new explanatory subtitle
            Text(
                text = "按照推荐排序，因此新出现的选项也可能出现在上方。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isSearching && candidates.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "正在查询...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isSearching && candidates.isEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }

                items(visibleCandidates) { candidate ->
                    CandidateItem(
                        candidate = candidate,
                        isApplying = isApplying,
                        onUse = { vm.applyCandidate(candidate) }
                    )
                }

                // 按钮项可以放在列表底部，这样在滚动列表时也可见
                item {
                    if (visibleCandidates.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Button(onClick = {
                                // 对当前已显示的每个来源请求更多
                                visibleCandidates.map { it.provider.lowercase() }
                                    .distinct()
                                    .forEach { prov -> vm.loadMore(prov) }
                            }) {
                                Text("查询更多选项")
                            }
                        }
                    }
                }
            }


            OutlinedTextField(
                value = manualText,
                onValueChange = { manualText = it },
                label = { Text("长按以粘贴") },
                placeholder = { Text("推荐TTML，也支持其他格式。") },
                modifier = Modifier
                    .fillMaxWidth(),
                minLines = 1 // 减少行数以进一步降低高度
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { vm.applyManualInput(manualText, title, artist) },
                    enabled = manualText.isNotBlank() && !isApplying,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isApplying) "处理中..." else "应用")
                }
                Button(
                    onClick = { filePickerLauncher.launch("*/*") },
                    enabled = !isApplying,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("从文件导入")
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun CandidateItem(
    candidate: CustomLyricsCandidate,
    isApplying: Boolean,
    onUse: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = candidate.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "${candidate.title} - ${candidate.artist}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                // 显示百分比、ID 以及支持的功能列表
                text = buildString {
                    append("匹配度: ${(candidate.confidence * 100).toInt()}%")
                    if (candidate.matchType.isNotBlank()) {
                        append(" (${candidate.matchType})")
                    }
                    if (candidate.features.isNotEmpty()) {
                        append(" | 支持: ")
                        append(candidate.features.joinToString(separator = ", ") {
                            it.displayName
                        })
                    }
                    append(" | ID: ${candidate.songId}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Button(
                onClick = onUse,
                enabled = !isApplying,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("应用")
            }
        }
    }
}
