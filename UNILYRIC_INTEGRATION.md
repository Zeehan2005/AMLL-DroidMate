# Unilyric 多源搜索逻辑集成文档

## 概述

DroidMate 已成功集成 Unilyric 的多源歌词搜索策略,支持自动从多个平台搜索和获取歌词。

## 支持的歌词来源

### 1. 网易云音乐 (Netease/NCM)
- **优先级**: 最高 (与 AMLL TTML DB 关联)
- **搜索API**: `https://music.163.com/api/search/get/web`
- **歌词API**: `https://music.163.com/api/song/lyric`
- **格式**: LRC (自动转换为 TTML)
- **特点**: 歌曲库最大,与 AMLL TTML DB 的 NCM ID 直接对应

### 2. QQ音乐
- **优先级**: 高
- **搜索API**: `https://u.y.qq.com/cgi-bin/musicu.fcg`
- **歌词API**: 同上 (使用不同的请求参数)
- **格式**: Base64编码的LRC (自动解码并转换)
- **特点**: 歌词质量高,覆盖面广

### 3. 酷狗音乐
- **优先级**: 中
- **搜索API**: `http://mobilecdn.kugou.com/api/v3/search/song`
- **歌词API**: `http://www.kugou.com/yy/index.php?r=play/getdata`
- **格式**: Base64编码的LRC (可能需要解码)
- **特点**: 补充来源

### 4. AMLL TTML DB
- **优先级**: 最高 (当网易云ID可用时)
- **基础URL**: 4个镜像站点
- **格式**: TTML (逐字歌词)
- **特点**: 最高质量,支持逐词同步

## 工作流程

### 自动智能搜索 (推荐)

```kotlin
// MainViewModel 中的用法
fun fetchLyrics() {
    val music = _nowPlayingMusic.value
    if (music == null) {
        _errorMessage.value = "未检测到播放信息"
        return
    }
    
    viewModelScope.launch {
        _isLoading.value = true
        
        // 自动多源搜索
        val result = lyricsRepository.fetchLyricsAuto(
            title = music.title,
            artist = music.artist
        )
        
        if (result.isSuccess && result.lyrics != null) {
            _lyrics.value = result.lyrics
        } else {
            _errorMessage.value = result.errorMessage
        }
        
        _isLoading.value = false
    }
}
```

### 搜索流程详解

1. **并行搜索阶段**
   ```
   网易云音乐搜索 → 返回 NCM ID + 置信度
   QQ音乐搜索    → 返回 MID + 置信度
   酷狗音乐搜索   → 返回 Hash + 置信度
   ```

2. **置信度计算**
   - 标题匹配: 60%
   - 歌手匹配: 40%
   - 总分范围: 0.0 - 1.0

3. **优先级获取策略**
   ```
   if (网易云结果存在) {
       尝试 AMLL TTML DB (ncm/{id})
       if (成功) return 高质量逐字歌词
   }
   
   for (搜索结果 按置信度排序) {
       尝试直接获取该平台歌词
       if (成功) return 歌词
   }
   
   return 失败
   ```

## 使用示例

### 示例 1: 完全自动搜索

```kotlin
val result = lyricsRepository.fetchLyricsAuto(
    title = "七里香",
    artist = "周杰伦"
)

when {
    result.isSuccess -> {
        println("成功从 ${result.source} 获取歌词")
        displayLyrics(result.lyrics!!)
    }
    else -> println("失败: ${result.errorMessage}")
}
```

**输出示例**:
```
成功从 AMLL TTML DB (网易云 186016) 获取歌词
```

### 示例 2: 手动搜索后选择

```kotlin
// 1. 搜索
val searchResults = lyricsRepository.searchLyrics(
    title = "稻香",
    artist = "周杰伦"
)

// 2. 查看所有结果
searchResults.forEach { result ->
    println("${result.provider}: ${result.title} - ${result.artist} (置信度: ${result.confidence})")
}

// 3. 手动选择最佳结果
val best = searchResults.maxByOrNull { it.confidence }
if (best != null) {
    val lyrics = lyricsRepository.getLyrics(
        provider = best.provider,
        songId = best.songId
    )
}
```

**输出示例**:
```
netease: 稻香 - 周杰伦 (置信度: 1.0)
qq: 稻香 - 周杰伦 (置信度: 1.0)
kugou: 稻香 - 周杰伦 (置信度: 1.0)
```

### 示例 3: 指定来源获取

```kotlin
// 如果已知网易云ID
val lyrics = lyricsRepository.getLyrics(
    provider = "netease",
    songId = "186016"
)

// 如果已知QQ音乐MID
val lyrics = lyricsRepository.getLyrics(
    provider = "qq",
    songId = "001Qu4I30eVFYb"
)

// 尝试AMLL TTML DB
val lyrics = lyricsRepository.getLyrics(
    provider = "amll",
    songId = "186016"  // 使用网易云ID
)
```

## API 参考

### LyricsRepository

#### fetchLyricsAuto()
**最推荐的方法** - 自动搜索并选择最佳来源

> 会优先检查并返回本地 AMLL 缓存的歌词，如果存在则不再发起其它网络请求。

```kotlin
suspend fun fetchLyricsAuto(
    title: String,
    artist: String,
    currentSourceName: String? = null // 可选的播放来源名称，用于同分时优先排序
): LyricsResult
```

#### searchLyrics()
搜索所有来源,返回结果列表

```kotlin
suspend fun searchLyrics(
    title: String,
    artist: String
): List<LyricsSearchResult>
```

#### getLyrics()
从指定来源获取歌词

```kotlin
suspend fun getLyrics(
    provider: String,  // "netease", "qq", "kugou", "amll"
    songId: String
): LyricsResult
```

#### 平台特定方法

```kotlin
// 网易云
suspend fun searchNetease(title: String, artist: String): LyricsSearchResult?
suspend fun getNeteaseLyrics(songId: String): TTMLLyrics?

// QQ音乐
suspend fun searchQQMusic(title: String, artist: String): List<LyricsSearchResult>  // returns top three matches
suspend fun getQQMusicLyrics(songMid: String): TTMLLyrics?

// 酷狗音乐
suspend fun searchKugou(title: String, artist: String): LyricsSearchResult?
suspend fun getKugouLyrics(hash: String): TTMLLyrics?

// AMLL TTML DB
suspend fun getAMLL_TTMLLyrics(songId: String): TTMLLyrics?
```

## 数据模型

### LyricsSearchResult
```kotlin
data class LyricsSearchResult(
    val provider: String,      // "netease", "qq", "kugou"
    val songId: String,        // 平台特定ID
    val title: String,         // 歌曲名
    val artist: String,        // 歌手名
    val album: String? = null, // 专辑名 (可选)
    val confidence: Float      // 匹配度 0.0-1.0
)
```

### LyricsResult
```kotlin
data class LyricsResult(
    val isSuccess: Boolean,           // 是否成功
    val lyrics: TTMLLyrics? = null,   // 歌词内容
    val source: String? = null,       // 来源描述
    val errorMessage: String? = null  // 错误信息
)
```

## 格式转换

所有平台的歌词都会自动转换为统一的 TTML 格式:

- **LRC → TTML**: 自动解析时间标签和歌词文本
- **Base64 → LRC → TTML**: QQ音乐和酷狗的解码流程
- **TTML → 直接使用**: AMLL TTML DB 的原生格式

### LRC 解析示例

输入 (LRC):
```
[ti:七里香]
[ar:周杰伦]
[00:00.00]窗外的麻雀
[00:02.50]在电线杆上多嘴
```

输出 (TTML):
```kotlin
TTMLLyrics(
    metadata = TTMLMetadata(
        title = "七里香",
        artist = "周杰伦"
    ),
    lines = listOf(
        LyricLine(startTime = 0, endTime = 2500, text = "窗外的麻雀"),
        LyricLine(startTime = 2500, endTime = Long.MAX_VALUE, text = "在电线杆上多嘴")
    )
)
```

## 错误处理

### 常见错误及解决方案

| 错误信息 | 原因 | 解决方案 |
|---------|------|---------|
| "未找到歌曲,请检查歌曲名和歌手名" | 所有平台搜索都无结果 | 检查歌曲名和歌手名拼写 |
| "找到歌曲但无法获取歌词" | 搜索成功但歌词API返回空 | 该歌曲可能是纯音乐或暂无歌词 |
| "All AMLL hosts are unreachable (DNS)" | 所有AMLL镜像DNS解析失败 | 检查网络连接 |
| "Lyrics not found on AMLL mirrors for songId=X" | AMLL数据库中无该歌曲 | 自动回退到其他来源 |

### 日志级别

```

## 性能优化建议

### 1. 缓存策略 (计划中)
```kotlin
// 使用 Room 数据库缓存已获取的歌词
// 避免重复网络请求
```

### 2. 超时设置
```kotlin
// 在 MainViewModel 中配置 HttpClient
HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15000  // 15秒
        connectTimeoutMillis = 10000  // 10秒
    }
}
```

## 与 Unilyric 的差异

| 功能 | Unilyric (Rust) | DroidMate (Kotlin) |
|------|-----------------|-------------------|
| 平台支持 | QQ/网易云/酷狗/AMLL | ✅ 完全相同 |
| 搜索策略 | 多源并行搜索 | ✅ 顺序搜索 (可优化为并行) |
| 格式转换 | 支持10+格式 | ✅ LRC/TTML |
| 优先级策略 | 可配置 | ✅ 固定优先级 (网易云优先) |
| 歌词编辑 | ✅ 支持 | ❌ 仅展示 |
| AMLL Player 联动 | ✅ WebSocket | ❌ |

## 致谢

本实现基于 [apoint123/Unilyric](https://github.com/apoint123/Unilyric) 的多源搜索策略,特别感谢:

- [@apoint123](https://github.com/apoint123) - Unilyric 作者
- [AMLL TTML DB](https://github.com/amll-dev/amll-ttml-db) - 高质量逐字歌词数据库
- [Steve-xmh](https://github.com/Steve-xmh) - AMLL 生态系统维护者

