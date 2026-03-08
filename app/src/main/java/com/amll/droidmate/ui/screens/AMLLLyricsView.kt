package com.amll.droidmate.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
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
 * DOM_LITE: 使用轻量DOM渲染(阉割版)
 */
enum class AMLLRenderMode {
    DOM,
    DOM_LITE
}

private const val AMLL_LOG_TAG = "AMLL"
private val AMLL_VIEW_INSTANCE_COUNTER = AtomicInteger(0)

private fun amllDebug(message: String) {
    Timber.d(message)
    Log.d(AMLL_LOG_TAG, message)
}

private fun amllInfo(message: String) {
    Timber.i(message)
    Log.i(AMLL_LOG_TAG, message)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AMLLLyricsView(
    lyrics: TTMLLyrics?,
    currentTime: Long,
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
    var lastFontFamily by remember { mutableStateOf<String?>(null) }
    var lastFontFileUri by remember { mutableStateOf<String?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            amllInfo("[$debugSource#$instanceId] Creating AMLL WebView, onLineSeek=${onLineSeekState.value != null}")
            WebView.setWebContentsDebuggingEnabled(true)
            WebView(context).apply {
                // 设置 WebView 的 LayoutParams 为 MATCH_PARENT
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        isPageReady = false
                        lastModeValue = null
                        lastBackgroundProfileValue = null
                        lastLyrics = null
                        lastLyricsPayload = null
                        lastAlbumArtUri = null
                        lastFontFamily = null
                        lastFontFileUri = null
                        amllDebug("[$debugSource#$instanceId] WebView page started: $url")
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        isPageReady = true
                        // Force one re-sync after page finishes to avoid losing early bridge calls.
                        lastModeValue = null
                        lastBackgroundProfileValue = null
                        lastLyrics = null
                        lastLyricsPayload = null
                        lastAlbumArtUri = null
                        lastFontFamily = null
                        lastFontFileUri = null
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

                // 透明 WebView 配置，允许宿主 Compose 层的专辑图背景透出
                // 先设置背景透明
                setBackgroundColor(Color.TRANSPARENT)
                // 使用 NONE 让 View 自行决定渲染方式，通常会使用硬件加速
                // 同时避免软件渲染导致的帧率问题
                setLayerType(View.LAYER_TYPE_NONE, null)

                addJavascriptInterface(
                    AMLLInterface(debugSource, instanceId) { seekTime ->
                        amllInfo("[$debugSource#$instanceId] Bridge callback onLineSeek($seekTime), callbackPresent=${onLineSeekState.value != null}")
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

            // 只在lyrics对象引用改变时才重新构建JSON（避免每秒都构建）
            if (lyrics !== lastLyrics) {
                if (lyrics != null) {
                    val lyricsJson = buildLyricsJson(lyrics)
                    amllDebug("[$debugSource#$instanceId] Bridge call: updateLyrics(lines=${lyrics.lines.size})")
                    view.evaluateJavascript("window.updateLyrics && window.updateLyrics($lyricsJson);", null)
                    lastLyricsPayload = lyricsJson
                } else {
                    lastLyricsPayload = null
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
            val configuredFontPath = AppSettings.getAmllFontFilePath(view.context)
            val configuredFontUri = configuredFontPath
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it) }
                ?.takeIf { it.exists() }
                ?.toURI()
                ?.toString()

            if (lastFontFamily != configuredFontFamily || lastFontFileUri != configuredFontUri) {
                val escapedFamily = escapeJsString(configuredFontFamily)
                val escapedUri = escapeJsString(configuredFontUri ?: "")
                amllDebug("[$debugSource#$instanceId] Bridge call: setFontSettings(customFile=${if (configuredFontUri.isNullOrBlank()) "none" else "present"})")
                view.evaluateJavascript(
                    "window.setFontSettings && window.setFontSettings(\"$escapedFamily\", \"$escapedUri\");",
                    null
                )
                lastFontFamily = configuredFontFamily
                lastFontFileUri = configuredFontUri
            }

            Log.v(AMLL_LOG_TAG, "[$debugSource#$instanceId] Bridge call: updateTime($currentTime)")
            view.evaluateJavascript("window.updateTime && window.updateTime($currentTime);", null)
        }
    )
}

private fun escapeJsString(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
}

private fun buildLyricsJson(lyrics: TTMLLyrics): String {
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
