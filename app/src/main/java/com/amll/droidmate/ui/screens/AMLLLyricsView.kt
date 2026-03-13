package com.amll.droidmate.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amll.droidmate.ui.AppSettings
import androidx.compose.ui.viewinterop.AndroidView
import com.amll.droidmate.domain.model.TTMLLyrics
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 对齐原AMLL项目的两种DOM渲染策略:
 * DOM: 使用AMLL Core的LyricPlayer
 * DOM_LITE: 使用轻量DOM渲染(阉割�?
 */
enum class AMLLRenderMode {
    DOM,
    DOM_LITE
}

private const val AMLL_LOG_TAG = "AMLL"
private val AMLL_VIEW_INSTANCE_COUNTER = AtomicInteger(0)

private fun amllDebug(message: String) {
    Timber.d(message)
}

private fun amllInfo(message: String) {
    Timber.i(message)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AMLLLyricsView(
    lyrics: TTMLLyrics?,
    currentTime: Long,
    isPlaying: Boolean = false, // playback state from host; used for pause flag
    albumArtUri: String? = null,
    renderMode: AMLLRenderMode = AMLLRenderMode.DOM,
    debugSource: String = "unknown",
    onLyricsClick: (() -> Unit)? = null,
    onLineSeek: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val instanceId = remember { AMLL_VIEW_INSTANCE_COUNTER.incrementAndGet() }
    val onLyricsClickState = rememberUpdatedState(onLyricsClick)
    val onLineSeekState = rememberUpdatedState(onLineSeek)
    var isPageReady by remember { mutableStateOf(false) }
    var lastModeValue by remember { mutableStateOf<String?>(null) }
    var lastBackgroundProfileValue by remember { mutableStateOf<String?>(null) }
    var lastLyrics by remember { mutableStateOf<TTMLLyrics?>(null) }
    var lastLyricsPayload by remember { mutableStateOf<String?>(null) }
    var lastAlbumArtUri by remember { mutableStateOf<String?>(null) }
    var lastFontConfigSignature by remember { mutableStateOf<String?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            amllInfo("[$debugSource#$instanceId] Creating AMLL WebView, onLineSeek=${onLineSeekState.value != null}")
            WebView.setWebContentsDebuggingEnabled(true)
            WebView(context).apply {
                // 设置 WebView �?LayoutParams �?MATCH_PARENT
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        isPageReady = false
                        lastModeValue = null
                        lastBackgroundProfileValue = null
                        // retain cached lyrics so a refresh while paused can still reapply them\n                        // lastLyrics = null
                        // lastLyricsPayload = null
                        lastAlbumArtUri = null
                        lastFontConfigSignature = null
                        amllDebug("[$debugSource#$instanceId] WebView page started: $url")
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        isPageReady = true
                        // Force one re-sync after page finishes to avoid losing early bridge calls.
                        lastModeValue = null
                        lastBackgroundProfileValue = null
                        // 页面刷新结束时不主动清空 lastLyrics，让我们知道是否还有有效歌词
                        // // retain cached lyrics so a refresh while paused can still reapply them\n                        // lastLyrics = null
                        // 页面刷新完成后如果我们之前有歌词 JSON 且当前仍然有 lyrics（不是因歌曲切换而清空），先立刻重新下发
                        if (lastLyricsPayload != null) {
                            amllDebug("[$debugSource#$instanceId] reapplying lyrics payload after page finish")
                            view.evaluateJavascript("window.updateLyrics && window.updateLyrics($lastLyricsPayload);", null)
                        }
                        // 不清�?payload，让 update() 继续根据 lyrics 对象决定重新生成
                        // // lastLyricsPayload = null
                        lastAlbumArtUri = null
                        lastFontConfigSignature = null
                        // 确保页面加载后背景仍然透明
                        view.setBackgroundColor(Color.TRANSPARENT)
                        amllDebug("[$debugSource#$instanceId] WebView page finished: $url")
                        view.evaluateJavascript(
                            "window.logFromKotlin && window.logFromKotlin('[KOTLIN] page finished for $debugSource#$instanceId');",
                            null
                        )
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        amllDebug(
                            "[$debugSource#$instanceId] JS Console(${consoleMessage.messageLevel()} @${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}): ${consoleMessage.message()}"
                        )
                        return super.onConsoleMessage(consoleMessage)
                    }
                }
                @Suppress("DEPRECATION")
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    // Allow asset page (file origin) to read local cached album art file URIs.
                    // Required for `file:///data/user/0/...` album art paths passed from Kotlin.
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                    // 性能优化配置
                    cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
                }

                // 透明 WebView 配置，允许宿�?Compose 层的专辑图背景透出
                // 先设置背景透明
                setBackgroundColor(Color.TRANSPARENT)
                // 使用 NONE �?View 自行决定渲染方式，通常会使用硬件加�?
                // 同时避免软件渲染导致的帧率问�?
                setLayerType(View.LAYER_TYPE_NONE, null)

                // keep a reference to the WebView so we can send immediate commands back to
                // the javascript bridge when the user initiates a seek via clicking a lyric.
                val webViewRef = this

                addJavascriptInterface(
                    AMLLInterface(debugSource, instanceId) { seekTime ->
                        amllInfo("[$debugSource#$instanceId] Bridge callback onLineSeek($seekTime), callbackPresent=${onLineSeekState.value != null}")

                        // schedule a UI-thread action so that the webview can immediately
                        // acknowledge the seek and prevent the "lyrics running around" effect.
                        webViewRef.post {
                            // tell the JS player we are seeking so it can suspend auto-scroll
                            webViewRef.evaluateJavascript(
                                "window.callPlayer && window.callPlayer('setIsSeeking', true);",
                                null
                            )

                            // update the webview time to the target position right away. this
                            // reduces the window where the old time would cause the view to
                            // scroll back to the previous line before the new position arrives
                            webViewRef.evaluateJavascript(
                                "window.updateTime && window.updateTime($seekTime);",
                                null
                            )
                        }

                        // finally notify host view model so the audio actually seeks
                        onLineSeekState.value?.invoke(seekTime)
                    },
                    "Android"
                )
                amllDebug("[$debugSource#$instanceId] JavascriptInterface added as Android")

                setOnClickListener {
                    amllDebug("[$debugSource#$instanceId] WebView onClick listener fired")
                    onLyricsClickState.value?.invoke()
                }

                loadUrl("file:///android_asset/amll/index.html")

                post {
                    amllDebug("[$debugSource#$instanceId] WebView size after layout: width=$width, height=$height, measuredWidth=$measuredWidth, measuredHeight=$measuredHeight")
                }

                amllDebug("[$debugSource#$instanceId] WebView initialized with URL: file:///android_asset/amll/index.html")
            }
        },
        update = { view ->
            if (!isPageReady) {
                amllDebug("[$debugSource#$instanceId] Bridge skipped: page not ready")
                return@AndroidView
            }

            amllDebug("[$debugSource#$instanceId] Update callback - WebView actual size: width=${view.width}, height=${view.height}, measuredWidth=${view.measuredWidth}, measuredHeight=${view.measuredHeight}")

            val modeValue = if (renderMode == AMLLRenderMode.DOM) "dom" else "dom-lite"
            if (lastModeValue != modeValue) {
                amllDebug("[$debugSource#$instanceId] Bridge call: setRenderMode($modeValue)")
                view.evaluateJavascript("window.setRenderMode && window.setRenderMode('$modeValue');", null)
                lastModeValue = modeValue
            }

            val backgroundProfile = if (renderMode == AMLLRenderMode.DOM) {
                """{"renderer":"pixi","fps":60,"flowSpeed":2.35,"renderScale":0.9,"staticMode":false,"lowFreqVolume":1.0}"""
            } else {
                """{"renderer":"pixi","fps":30,"flowSpeed":1.4,"renderScale":0.65,"staticMode":false,"lowFreqVolume":1.0}"""
            }
            if (lastBackgroundProfileValue != backgroundProfile) {
                amllDebug("[$debugSource#$instanceId] Bridge call: configureBackgroundEffect(profile=$backgroundProfile)")
                view.evaluateJavascript(
                    "window.configureBackgroundEffect && window.configureBackgroundEffect($backgroundProfile);",
                    null
                )
                lastBackgroundProfileValue = backgroundProfile
            }

            // 先更新时间，确保 JS 层的 state.currentTime 是正确的，然后再更新歌词
            // 这样可以避免在间�?前奏/尾奏时切换全屏导致歌词位置重置的问题
            // include paused flag so frontend no longer has to infer from time
            Timber.d("[$debugSource#$instanceId] Bridge call: updateTime($currentTime, paused=${!isPlaying})")
            view.evaluateJavascript("window.updateTime && window.updateTime($currentTime, ${!isPlaying});", null)

            // 只在lyrics对象引用改变时才重新构建JSON（避免每秒都构建�?
            if (lyrics !== lastLyrics) {
                if (lyrics != null) {
                    val lyricsJson = buildLyricsJson(lyrics)
                    amllDebug("[$debugSource#$instanceId] Bridge call: updateLyrics(lines=${lyrics.lines.size})")
                    view.evaluateJavascript("window.updateLyrics && window.updateLyrics($lyricsJson);", null)
                    lastLyricsPayload = lyricsJson
                } else {
                    // lastLyricsPayload = null
                }
                lastLyrics = lyrics
            }

            if (lastAlbumArtUri != albumArtUri) {
                val escapedAlbumUri = escapeJsString(albumArtUri ?: "")
                amllDebug("[$debugSource#$instanceId] Bridge call: updateAlbumArt(uri=${if (albumArtUri.isNullOrBlank()) "empty" else "present"})")
                view.evaluateJavascript("window.updateAlbumArt && window.updateAlbumArt(\"$escapedAlbumUri\");", null)
                lastAlbumArtUri = albumArtUri
            }

            val configuredFontFamily = AppSettings.getAmllFontFamily(view.context)
            val fontFiles = AppSettings.getAmllFontFiles(view.context)
                .filter { it.absolutePath.isNotBlank() }
                .mapNotNull { item ->
                    val file = File(item.absolutePath)
                    if (!file.exists()) return@mapNotNull null
                    FontWebEntry(
                        id = item.id,
                        sortKey = item.fontFamilyName,
                        familyName = buildRuntimeFontFamilyName(item.fontFamilyName, item.id),
                        uri = file.toURI().toString()
                    )
                }

            val enabledIds = AppSettings.getEnabledAmllFontFileIds(view.context)
            val preferredOrder = parsePreferredFontOrder(configuredFontFamily)
            val enabledFamilies = fontFiles
                .filter { enabledIds.contains(it.id) }
                .sortedWith(
                    compareBy<FontWebEntry> { fontSortPriority(it.sortKey, preferredOrder) }
                        .thenBy { it.sortKey.lowercase() }
                        .thenBy { it.id }
                )
                .map { it.familyName }
                .distinct()

            val effectiveFamily = if (enabledFamilies.isNotEmpty()) {
                val enabledStack = enabledFamilies.joinToString(", ") { "\"$it\"" }
                "$enabledStack, $configuredFontFamily"
            } else {
                configuredFontFamily
            }

            val fontSignature = buildString {
                append(effectiveFamily)
                append("|")
                append(fontFiles.joinToString(";") { "${it.id}:${it.familyName}:${it.uri}" })
                append("|")
                append(enabledFamilies.joinToString(","))
            }

            if (lastFontConfigSignature != fontSignature) {
                val script = buildApplyFontScript(effectiveFamily, fontFiles)
                amllDebug(
                    "[$debugSource#$instanceId] Bridge call: applyFontSettings(enabled=${enabledFamilies.size}, files=${fontFiles.size})"
                )
                view.evaluateJavascript(script, null)
                lastFontConfigSignature = fontSignature
            }
        }
    )
}

private data class FontWebEntry(
    val id: String,
    val sortKey: String,
    val familyName: String,
    val uri: String
)

private fun buildRuntimeFontFamilyName(baseFamilyName: String, fontId: String): String {
    val base = baseFamilyName
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .ifBlank { "AMLL_FONT" }
    return "${base}_$fontId"
}

private fun parsePreferredFontOrder(configuredFontFamily: String): List<String> {
    return configuredFontFamily
        .split(',')
        .map { normalizeFontToken(it) }
        .filter { it.isNotBlank() }
}

private fun fontSortPriority(sortKey: String, preferredOrder: List<String>): Int {
    if (preferredOrder.isEmpty()) return Int.MAX_VALUE
    val normalizedSortKey = normalizeFontToken(sortKey)
    for (index in preferredOrder.indices) {
        val preferred = preferredOrder[index]
        if (preferred.isBlank()) continue
        if (normalizedSortKey.contains(preferred) || preferred.contains(normalizedSortKey)) {
            return index
        }
    }
    return Int.MAX_VALUE
}

private fun normalizeFontToken(value: String): String {
    return value
        .lowercase()
        .replace(Regex("[^a-z0-9]"), "")
}

private fun buildApplyFontScript(effectiveFamily: String, files: List<FontWebEntry>): String {
    val escapedFamily = escapeJsString(effectiveFamily)
    val filesJs = files.joinToString(",") {
        "{id:\"${escapeJsString(it.id)}\",familyName:\"${escapeJsString(it.familyName)}\",uri:\"${escapeJsString(it.uri)}\"}"
    }

    return """
        (function() {
            var effectiveFamily = "$escapedFamily";
            var files = [$filesJs];
            var styleId = 'amll-dynamic-font-face-style';
            var styleNode = document.getElementById(styleId);
            if (!styleNode) {
                styleNode = document.createElement('style');
                styleNode.id = styleId;
                document.head.appendChild(styleNode);
            }

            var css = '';
            for (var i = 0; i < files.length; i += 1) {
                var item = files[i];
                if (!item || !item.familyName || !item.uri) continue;
                css += '@font-face{font-family:"' + item.familyName + '";src:url("' + item.uri + '");font-display:swap;}';
            }
            styleNode.textContent = css;

            document.documentElement.style.setProperty('--amll-user-font-family', effectiveFamily);
            document.documentElement.style.setProperty('--amll-lp-font-family', 'var(--amll-user-font-family)');

            var players = document.querySelectorAll('.amll-lyric-player');
            for (var j = 0; j < players.length; j += 1) {
                players[j].style.fontFamily = 'var(--amll-lp-font-family)';
            }
        })();
    """.trimIndent().replace("\n", " ")
}

private fun escapeJsString(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
}

private fun buildLyricsJson(lyrics: TTMLLyrics): String {
    val bgLines = lyrics.lines.filter { it.isBG }
    val bgWithTranslation = bgLines.count { !it.translation.isNullOrBlank() }
    val bgWithRoman = bgLines.count { !it.transliteration.isNullOrBlank() }
    val sampleBg = bgLines.firstOrNull()
    amllDebug("[BG-LYRICS-DEBUG] buildLyricsJson summary: total=${lyrics.lines.size}, bg=${bgLines.size}, bgWithTrans=$bgWithTranslation, bgWithRoman=$bgWithRoman, sampleBg='${sampleBg?.text ?: ""}', sampleTrans='${sampleBg?.translation ?: ""}'")

    val linesJson = lyrics.lines.joinToString(",") { line ->
        val text = line.text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val translation = line.translation?.replace("\\", "\\\\")?.replace("\"", "\\\"") ?: ""
        val transliteration = line.transliteration?.replace("\\", "\\\\")?.replace("\"", "\\\"") ?: ""
        
        // 构建 words 数组
        val wordsJson = if (line.words.isNotEmpty()) {
            line.words.joinToString(",") { word ->
                val wordText = word.word.replace("\\", "\\\\").replace("\"", "\\\"")
                """{"word":"$wordText","startTime":${word.startTime},"endTime":${word.endTime}}"""
            }
        } else {
            // 如果没有逐词信息，则使用整行文本作为单词
            val wordText = text.replace("\"", "\\\"")
            """{"word":"$wordText","startTime":${line.startTime},"endTime":${line.endTime}}"""
        }
        
        // 调试日志
        if (line.words.isNotEmpty()) {
            amllDebug("Building JSON for line: '${line.text}' with ${line.words.size} words")
        }
        
        // 调试背景歌词的数据传�?
        if (line.isBG) {
            amllDebug("[BG-LYRICS-DEBUG] JSON for BG line: text='$text' translation='$translation' roman='$transliteration' isBG=${line.isBG}")
        }
        
        """{
            "startTime":${line.startTime},
            "endTime":${line.endTime},
            "text":"$text",
            "translatedLyric":"$translation",
            "romanLyric":"$transliteration",
            "words":[$wordsJson],
            "isBG":${line.isBG},
            "isDuet":${line.isDuet}
        }"""
    }

    val title = lyrics.metadata.title.replace("\\", "\\\\").replace("\"", "\\\"")
    val artist = lyrics.metadata.artist.replace("\\", "\\\\").replace("\"", "\\\"")

    return """{"metadata":{"title":"$title","artist":"$artist"},"lines":[$linesJson]}"""
}

class AMLLInterface(
    private val debugSource: String,
    private val instanceId: Int,
    private val onLineSeek: ((Long) -> Unit)? = null
) {
    @JavascriptInterface
    fun log(message: String) {
        amllDebug("[$debugSource#$instanceId] JS: $message")
    }

    @JavascriptInterface
    fun onLineClick(lineIndex: Int, startTime: Long) {
        amllInfo("[$debugSource#$instanceId] User clicked lyric line: index=$lineIndex, startTime=$startTime, callbackPresent=${onLineSeek != null}")
        onLineSeek?.invoke(startTime)
    }
}
