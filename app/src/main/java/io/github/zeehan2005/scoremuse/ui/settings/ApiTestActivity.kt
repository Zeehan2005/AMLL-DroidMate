package io.github.zeehan2005.scoremuse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.zeehan2005.scoremuse.components.ServiceLocator
import io.github.zeehan2005.scoremuse.data.api.ApiTestClient
import io.github.zeehan2005.scoremuse.data.api.ApiTestResult
import io.github.zeehan2005.scoremuse.ui.BaseComposeActivity
import kotlinx.coroutines.launch

class ApiTestActivity : BaseComposeActivity() {
    @Composable
    override fun RenderContent() {
        ApiTestPage(onBack = { finish() })
    }
}

private data class ApiOperation(
    val label: String,
    val method: String,
    val baseUrl: String,
    val hint: String,
    val extraLabel: String = "",
    val call: suspend ApiTestClient.(main: String, extra: String) -> ApiTestResult
)

private val operations = listOf(
    ApiOperation("QQ音乐 - 搜索歌曲", "POST", "https://u.y.qq.com/cgi-bin/musicu.fcg", "关键词") { m, _ -> qqSearch(m) },
    ApiOperation("QQ音乐 - 获取歌词 (musicu.fcg)", "GET", "https://u.y.qq.com/cgi-bin/musicu.fcg", "歌曲 mid") { m, _ -> qqGetLyrics(m) },
    ApiOperation("QQ音乐 - 获取歌词含QRC (musicu.fcg)", "GET", "https://u.y.qq.com/cgi-bin/musicu.fcg?crypt=1&qrc=1", "歌曲 mid") { m, _ -> qqGetLyricsWithQrc(m) },
    ApiOperation("QQ音乐 - QRC Hex 解密测试", "DECRYPT", "3DES+Zlib → 明文", "QRC Hex 字符串") { m, _ -> qqDecryptQrcHex(m) },
    ApiOperation("QQ音乐 - 获取歌词 (lyric_download.fcg)", "POST", "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg", "歌曲数字 musicId") { m, _ -> qqLyricDownload(m) },
    ApiOperation("网易云音乐 - 搜索歌曲", "POST", "https://interface.music.163.com/eapi/cloudsearch/pc", "关键词") { m, _ -> neteaseSearch(m) },
    ApiOperation("网易云音乐 - 获取歌词", "POST", "https://interface3.music.163.com/eapi/song/lyric/v1", "歌曲数字 ID") { m, _ -> neteaseGetLyrics(m) },
    ApiOperation("酷狗音乐 - 搜索歌曲", "GET", "http://mobilecdn.kugou.com/api/v3/search/song", "关键词") { m, _ -> kugouSearch(m) },
    ApiOperation("酷狗音乐 - 歌词候选搜索", "GET", "https://lyrics.kugou.com/search", "歌曲 hash") { m, _ -> kugouLyricSearch(m) },
    ApiOperation("酷狗音乐 - 下载歌词", "GET", "https://lyrics.kugou.com/download", "歌词候选 ID", "accesskey") { m, e -> kugouLyricDownload(m, e) },
    ApiOperation("AMLL TTML DB - 歌名搜索 (bikonoo.com)", "POST", "https://amlldb.bikonoo.com/api/search-lyrics", "歌曲名") { m, _ -> amllDbSearch(m) },
    // AMLL TTML — 每个镜像站单独一个操作
    ApiOperation("AMLL TTML DB - 歌词获取 (stevexmh.net)", "GET",
        "https://amll-ttml-db.stevexmh.net/{platform}/{id}", "ID（如 ncm:12345）") { m, _ -> amllGetLyrics(m, 0) },
    ApiOperation("AMLL TTML DB - 歌词获取 (bikonoo.com)", "GET",
        "https://amlldb.bikonoo.com/{folder}/{id}.ttml", "ID（如 ncm:12345）") { m, _ -> amllGetLyrics(m, 1) },
    ApiOperation("AMLL TTML DB - 歌词获取 (dimeta.top)", "GET",
        "https://amll.mirror.dimeta.top/api/db/{folder}/{id}.ttml", "ID（如 ncm:12345）") { m, _ -> amllGetLyrics(m, 2) },
    ApiOperation("AMLL TTML DB - 歌词获取 (GitHub)", "GET",
        "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/{folder}/{id}.ttml", "ID（如 ncm:12345）") { m, _ -> amllGetLyrics(m, 3) }
)

@Composable
private fun ApiTestPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sel by remember { mutableIntStateOf(0) }
    val op = operations[sel]
    var input by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ApiTestResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    val barState = rememberTopAppBarState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(barState)

    fun exec() {
        if (input.isBlank()) return
        loading = true; result = null
        scope.launch {
            result = op.call(ApiTestClient(ServiceLocator.provideHttpClient(context)), input.trim(), extra.trim())
            loading = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("API 检测") },
                navigationIcon = {
                    FilledIconButton(onClick = onBack, colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface
                    )) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.background),
                scrollBehavior = scroll
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("操作", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            @OptIn(ExperimentalMaterial3Api::class)
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    OutlinedTextField(value = op.label, onValueChange = {}, readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(), singleLine = true)
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        operations.forEachIndexed { i, o ->
                            DropdownMenuItem(text = { Text(o.label) }, onClick = { sel = i; extra = ""; result = null; expanded = false },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding)
                        }
                    }
                }
            }

            // 请求预览（执行前显示 URL）
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    op.baseUrl.lines().forEachIndexed { i, line ->
                        Text(if (i == 0) "${op.method} $line" else line,
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            Text("参数", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(op.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text("输入参数") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { if (!loading) exec() }),
                            trailingIcon = { if (input.isNotEmpty()) IconButton(onClick = { input = ""; result = null }) { Icon(Icons.Default.Clear, "清空") } })
                        Button(onClick = { exec() }, enabled = !loading && input.isNotBlank()) {
                            if (loading) Text("请求中...")
                            else { Icon(Icons.Default.PlayArrow, null, Modifier.padding(end = 4.dp)); Text("执行") }
                        }
                    }
                    if (op.extraLabel.isNotBlank()) {
                        OutlinedTextField(value = extra, onValueChange = { extra = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text(op.extraLabel) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { if (!loading) exec() }))
                    }
                }
            }

            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            result?.let { r ->
                // 请求行
                if (r.requestUrl.isNotBlank()) {
                    Text("${r.requestMethod} ${r.requestUrl}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                }

                // 响应
                val statusInfo = when {
                    r.statusCode != null -> "HTTP ${r.statusCode}  ${r.durationMs}ms"
                    r.errorMessage != null -> r.errorMessage
                    else -> ""
                }
                if (statusInfo.isNotBlank()) {
                    Text(statusInfo, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(4.dp))
                }

                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        SelectionContainer {
                            Text(
                                text = r.responseBody.ifBlank { if (r.errorMessage != null) "(error: ${r.errorMessage})" else "（空）" },
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
