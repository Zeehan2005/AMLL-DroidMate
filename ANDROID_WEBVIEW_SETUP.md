# Android WebView 配置示例

## 正确的 JavaScript 接口实现

### 方式 1: 独立的 JavaScript 接口类

```kotlin
import android.webkit.JavascriptInterface
import android.util.Log

class AMLLJavaScriptInterface(private val musicPlayer: MusicPlayer) {
    
    /**
     * 处理歌词行点击事件
     * 从 WebView 中的 JavaScript 调用
     * 
     * @param lineIndex 点击的歌词行索引
     * @param startTimeMs 该行的开始时间（毫秒）
     */
    @JavascriptInterface
    fun onLineClick(lineIndex: Int, startTimeMs: Int) {
        Log.d("AMLL", "onLineClick called: lineIndex=$lineIndex, startTimeMs=$startTimeMs")
        
        // 将点击事件发送到主线程（WebView 调用来自 JS 线程）
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            try {
                // 调用音乐播放器的 seek 方法
                musicPlayer.seekTo(startTimeMs.toLong())
                Log.d("AMLL", "Seeked to $startTimeMs ms")
            } catch (e: Exception) {
                Log.e("AMLL", "Error seeking to time: ${e.message}")
            }
        }
    }
    
    /**
     * 处理来自 JavaScript 的日志消息
     * 
     * @param message 日志内容
     */
    @JavascriptInterface
    fun log(message: String) {
        Log.d("AMLL.JS", message)
    }
}
```

### 方式 2: 在 Activity/Fragment 中配置 WebView

```kotlin
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Build
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

class AMLLPlayerActivity : AppCompatActivity() {
    
    private lateinit var webView: WebView
    private lateinit var musicPlayer: MusicPlayer
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        webView = WebView(this)
        setContentView(webView)
        
        // 配置 WebView 设置
        configureWebView()
        
        // 加载 AMLL HTML
        webView.loadUrl("file:///android_asset/amll/index.html")
    }
    
    private fun configureWebView() {
        webView.settings.apply {
            // ✅ 启用 JavaScript（必须！）
            javaScriptEnabled = true
            
            // ✅ 启用调试（开发时使用）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            
            // （可选）其他常用设置
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            
            // 混合内容（如果需要）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }
        
        // ✅ 注册 JavaScript 接口（最关键！）
        val jsInterface = AMLLJavaScriptInterface(musicPlayer)
        webView.addJavascriptInterface(jsInterface, "Android")
        
        // 设置 WebViewClient（用于加载事件等）
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("AMLL", "WebView page finished loading: $url")
                
                // （可选）在页面加载完成后，初始化播放器数据
                initializeAMLLPlayer()
            }
        }
    }
    
    private fun initializeAMLLPlayer() {
        // 获取当前歌词
        val lyrics = musicPlayer.getCurrentLyrics()
        
        // 通过 JavaScript 更新歌词
        val updateScript = """
            window.updateLyrics({
                lines: ${lyrics.toJsonArray()}
            });
        """.trimIndent()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(updateScript) { result ->
                Log.d("AMLL", "updateLyrics returned: $result")
            }
        } else {
            webView.loadUrl("javascript:$updateScript")
        }
    }
    
    // 当播放器时间更新时，调用此方法
    fun updatePlaybackTime(timeMs: Long) {
        val updateScript = "window.updateTime($timeMs);"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(updateScript) { _ -> }
        } else {
            webView.loadUrl("javascript:$updateScript")
        }
    }
}
```

---

## MusicPlayer 接口示例

如果你还没有 `MusicPlayer` 类，这里是一个示意实现：

```kotlin
interface MusicPlayer {
    fun seekTo(timeMs: Long)
    fun getCurrentTime(): Long
    fun getCurrentLyrics(): List<LyricLine>
    fun play()
    fun pause()
}

data class LyricLine(
    val text: String,
    val translatedLyric: String = "",
    val romanLyric: String = "",
    val startTime: Long,
    val endTime: Long,
    val words: List<LyricWord> = emptyList(),
    val isBG: Boolean = false,
    val isDuet: Boolean = false
)

data class LyricWord(
    val word: String,
    val startTime: Long,
    val endTime: Long
)

fun List<LyricLine>.toJsonArray(): String {
    val jsonLines = this.map { line ->
        """
        {
            "text": "${line.text.replace("\"", "\\\"")}",
            "translatedLyric": "${line.translatedLyric.replace("\"", "\\\"")}",
            "romanLyric": "${line.romanLyric.replace("\"", "\\\"")}",
            "startTime": ${line.startTime},
            "endTime": ${line.endTime},
            "isBG": ${line.isBG},
            "isDuet": ${line.isDuet},
            "words": ${line.words.toJsonArray()}
        }
        """.trimIndent()
    }
    return "[${ jsonLines.joinToString(",") }]"
}

fun List<LyricWord>.toJsonArray(): String {
    val jsonWords = this.map { word ->
        """
        {
            "word": "${word.word.replace("\"", "\\\"")}",
            "startTime": ${word.startTime},
            "endTime": ${word.endTime}
        }
        """.trimIndent()
    }
    return "[${ jsonWords.joinToString(",") }]"
}
```

---

## 常见问题解决

### ❌ 问题：`JavaScript interface method called for unknown object` 错误

**原因**：接口注册时的对象名与 JavaScript 中引用的名字不匹配

**解决**：
```kotlin
// ❌ 错误：对象名不一致
webView.addJavascriptInterface(jsInterface, "MusicInterface")
// 但 JavaScript 中使用 Android.onLineClick()

// ✅ 正确：
webView.addJavascriptInterface(jsInterface, "Android")
// JavaScript 使用 Android.onLineClick()
```

---

### ❌ 问题：`java.lang.NullPointerException` 在 onLineClick 中

**原因**：`musicPlayer` 或其他对象未初始化

**解决**：
```kotlin
class AMLLJavaScriptInterface(private val musicPlayer: MusicPlayer?) {
    @JavascriptInterface
    fun onLineClick(lineIndex: Int, startTimeMs: Int) {
        if (musicPlayer == null) {
            Log.e("AMLL", "musicPlayer is null!")
            return
        }
        
        // 安全地调用
        musicPlayer?.seekTo(startTimeMs.toLong())
    }
}
```

---

### ❌ 问题：JavaScript 無法调用 Android 接口

**可能原因**：
- [ ] `javaScriptEnabled = false` → 改为 `true`
- [ ] 接口注册时机太早（在 HTML 未加载前）→ 在 `onPageFinished` 后注册
- [ ] WebView 运行在错误的线程 → 确保在主线程创建和修改 WebView

**检查清单**：
```kotlin
// ✅ 正确的线程处理
runOnUiThread {
    webView.settings.javaScriptEnabled = true
    webView.addJavascriptInterface(jsInterface, "Android")
}
```

---

## 完整的 AndroidManifest.xml 配置

确保已添加必要的权限：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    
    <!-- ✅ WebView 需要的权限 -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    
    <!-- 如果需要文件访问 -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    
    <application>
        <!-- ... 其他配置 ... -->
    </application>
    
</manifest>
```

---

## 测试点击功能

### 方法 1：通过日志验证

启动应用后，在 Logcat 中搜索 `AMLL`，应该看到：

```
[AMLL] WebView page finished loading: file:///android_asset/amll/index.html
[AMLL] onLineClick called: lineIndex=0, startTimeMs=1000
[AMLL] Seeked to 1000 ms
```

### 方法 2：manual 测试

```kotlin
// 在 Activity 中手动触发
fun testLineClick() {
    webView.evaluateJavascript(
        "window.Android.onLineClick(0, 5000);"
    ) { result ->
        Log.d("AMLL", "Test result: $result")
    }
}
```

---

**版本**：2.0  
**更新日期**：2026-03-08  
**兼容性**：Android 4.4+ (WebView)
