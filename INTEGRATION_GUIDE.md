# DroidMate 集成指南

本文档说明如何将 DroidMate 与 apoint123/Unilyric 和 amll-dev/amll-ttml-db 集成。

## 1. Unilyric 集成

### 方式 A: 通过 JitPack 依赖

如果 Unilyric 的 Android 版本已发布到 JitPack，可以在 `build.gradle.kts` 中添加：

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // implementation("com.github.apoint123:unilyric-android:main")
}
```

### 方式 B: 手动集成源码

1. 克隆 Unilyric 仓库：
```bash
git clone https://github.com/apoint123/Unilyric.git
cd Unilyric
```

2. 构建 Android 库（如果有）或 Kotlin/Java 库

3. 在 DroidMate 中引用：
```kotlin
// 在 LyricsRepository.kt 中使用 Unilyric
import com.unilyric.lyrics.api.LyricsHelper
import com.unilyric.lyrics.model.Track

suspend fun searchWithUnilyric(title: String, artist: String) {
    val helper = LyricsHelper.newInstance()
    helper.loadProviders()
    
    val track = Track(
        title = title,
        artists = arrayOf(artist)
    )
    
    val results = helper.searchTrack(track).await()
    // 处理搜索结果
}
```

### Unilyric 支持的提供商

- QQ 音乐 (search_songs, get_full_lyrics)
- 网易云音乐 (search_songs, get_full_lyrics)
- 酷狗音乐 (search_songs, get_full_lyrics)
- AMLL TTML DB (search_songs, get_full_lyrics)

## 2. AMLL TTML DB 集成

### 直接 API 访问

```kotlin
// 在 LyricsRepository.kt 中
suspend fun getAMLL_TTMLLyrics(songId: String): TTMLLyrics? {
    val url = "https://amll-ttml-db.imuzikk.com/ttml/$songId"
    // 或使用其他镜像源
    // val url = "https://amll-ttml-db.stevexmh.net/ttml/$songId"
    
    val response = httpClient.get(url)
    val ttmlContent = response.body<String>()
    
    return LyricsRepository.parseTTML(ttmlContent)
}
```

### 可用的镜像源

| 源 | URL | 备注 |
|---|-----|------|
| 官方 | `https://amll-ttml-db.imuzikk.com` | 主源 |
| stevexmh | `https://amll-ttml-db.stevexmh.net` | 备用源 |
| 网易云 | `https://amll-ttml-db.music.163.com` | 需要 NCM ID |

### AMLL Player 集成

如果需要和 AMLL Player 协同工作，可以实现一个服务来发送歌词：

```kotlin
// 创建 AmllPlayerService.kt
class AmllPlayerService : Service() {
    
    // 通过 IPC 或 Intent 与 AMLL Player 通信
    // 发送 TTML 歌词数据
    
    override fun onBind(intent: Intent?): IBinder? {
        // 实现 Binder 接口
        return null
    }
}
```

## 3. 歌词搜索流程

完整的歌词搜索和获取流程：

```kotlin
suspend fun completeLyricsWorkflow(title: String, artist: String) {
    // 1. 搜索歌词
    val searchResults = lyricsRepository.searchLyrics(title, artist)
    
    // 2. 选择最佳结果（通常选择匹配度最高的）
    val selectedResult = searchResults.maxByOrNull { it.confidence }
    
    if (selectedResult != null) {
        // 3. 获取歌词内容
        val lyricsResult = lyricsRepository.getLyrics(
            provider = selectedResult.provider,
            songId = selectedResult.songId
        )
        
        if (lyricsResult.isSuccess) {
            // 4. 使用 TTML 数据
            val ttml = lyricsResult.lyrics
            
            // 5. 导出为文件（可选）
            val ttmlString = TTMLConverter.toTTMLString(ttml, formatted = true)
            // 保存到文件或显示
        }
    }
}
```

## 4. 格式转换

支持多种歌词格式的转换：

```kotlin
// LRC 转 TTML
val lrcContent = "[00:00.00]歌词\n[00:02.00]内容"
val ttml = TTMLConverter.fromLRC(lrcContent)

// TTML 导出
val ttmlString = TTMLConverter.toTTMLString(ttml)

// 自定义歌词行
val customLines = listOf(
    LyricLine(0, 1000, "歌", translation = "Song"),
    LyricLine(1000, 2000, "词", translation = "Word")
)
val customTTML = TTMLConverter.fromLyricLines(
    customLines,
    title = "Test",
    artist = "Artist"
)
```

## 5. 高级功能

### 缓存歌词

使用 Room 数据库缓存歌词以减少网络请求：

```kotlin
@Entity(tableName = "lyrics_cache")
data class CachedLyrics(
    @PrimaryKey val songId: String,
    val title: String,
    val artist: String,
    val ttmLContent: String,
    val provider: String,
    val timestamp: Long
)

@Dao
interface LyricsCacheDao {
    @Query("SELECT * FROM lyrics_cache WHERE songId = :songId")
    suspend fun getLyrics(songId: String): CachedLyrics?
    
    @Insert
    suspend fun saveLyrics(lyrics: CachedLyrics)
}
```

### 多语言支持

```kotlin
// 获取特定语言的翻译
fun getTranslation(line: LyricLine, language: String): String? {
    return when (language) {
        "zh-CN" -> line.translation
        "ja" -> line.transliteration
        else -> null
    }
}
```

### 实时歌词同步

```kotlin
// 定时更新当前行位置
fun updateCurrentLyricLine(currentTime: Long): LyricLine? {
    return lyrics.value?.lines?.find {
        currentTime in it.startTime..it.endTime
    }
}
```

## 6. 故障排除

### 问题：无法获取媒体信息

**症状**：MediaInfoService 无法获取当前播放歌曲

**解决方案**：
1. 检查权限声明（`MEDIA_CONTENT_CONTROL`）
2. 确保播放器支持 MediaSession
3. 使用 adb 检查媒体会话：
```bash
adb shell dumpsys media_session
```

### 问题：TTML 解析失败

**症状**：TTMLConverter.parseTTML() 返回 null

**解决方案**：
1. 验证 TTML 文件格式是否规范
2. 检查 XML 编码（应为 UTF-8，无 BOM）
3. 使用 XML 验证工具检查结构

### 问题：网络请求超时

**症状**：歌词获取请求超时

**解决方案**：
1. 增加 Ktor 超时时间：
```kotlin
client {
    install(HttpTimeout) {
        requestTimeoutMillis = 30000
        connectTimeoutMillis = 15000
    }
}
```
2. 使用代理或 VPN
3. 尝试备用 API 端点

## 7. 性能优化

### 建议

1. **异步操作**：所有网络请求都应该在 Coroutine 中进行
2. **缓存**：频繁访问的歌词应该缓存到本地数据库
3. **增量更新**：只在歌曲变化时更新歌词
4. **内存管理**：及时释放大的字符串和对象

## 8. API 参考

### LyricsRepository 主要方法

```kotlin
// 搜索歌词
suspend fun searchLyrics(title: String, artist: String): List<LyricsSearchResult>

// 获取歌词
suspend fun getLyrics(provider: String, songId: String): LyricsResult

// 从 AMLL TTML DB 获取
suspend fun getAMLL_TTMLLyrics(songId: String): TTMLLyrics?

// 解析 TTML
fun parseTTML(ttmlContent: String): TTMLLyrics?
```

### TTMLConverter 主要方法

```kotlin
// TTML 字符串转换
fun toTTMLString(lyrics: TTMLLyrics, formatted: Boolean = false): String

// LRC 到 TTML
fun fromLRC(lrcContent: String): TTMLLyrics?

// 时间格式化
fun formatTime(millis: Long): String
fun timeToMillis(timeStr: String): Long
```

## 9. 更新和维护

定期检查以下项目的更新：

- Unilyric: `{https://github.com/apoint123/Unilyric/releases}`
- AMLL TTML DB: `{https://github.com/amll-dev/amll-ttml-db}`
- 依赖库：Kotlin, Android X, Ktor 等

## 10. 许可和归属

- Unilyric: 原作者 apoint123
- AMLL TTML DB: 原作者 amll-dev
- DroidMate: MIT License

使用时请保留原始项目的归属信息。
