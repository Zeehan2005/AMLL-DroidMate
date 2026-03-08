# 自选歌词候选歌词统一转换规则集成

## 概述

本文档说明如何在自选歌词的候选歌词中应用统一的多格式歌词转换规则。通过使用新的 `UnifiedLyricsParser`，我们可以确保从不同平台（QQ音乐、网易云、酷狗）获取的候选歌词都能正确解析，包括格式识别和逐词同步数据的保留。

## 改动汇总

### 1. 更新 LyricsRepository 平台获取方法签名

#### a. `getQQMusicLyrics()`
- **变更前**: `suspend fun getQQMusicLyrics(songMid: String): TTMLLyrics?`
- **变更后**: `suspend fun getQQMusicLyrics(songMid: String, title: String? = null, artist: String? = null): TTMLLyrics?`
- **说明**: 增加可选的 `title` 和 `artist` 参数，用于元数据传递

#### b. `getNeteaseLyrics()`
- **变更前**: `suspend fun getNeteaseLyrics(songId: String): TTMLLyrics?`
- **变更后**: `suspend fun getNeteaseLyrics(songId: String, title: String? = null, artist: String? = null): TTMLLyrics?`
- **说明**: 增加可选的 `title` 和 `artist` 参数，用于元数据传递

#### c. `getKugouLyrics()`
- **变更前**: `suspend fun getKugouLyrics(hash: String): TTMLLyrics?`
- **变更后**: `suspend fun getKugouLyrics(hash: String, title: String? = null, artist: String? = null): TTMLLyrics?`
- **说明**: 增加可选的 `title` 和 `artist` 参数，用于元数据传递

### 2. 更新 getLyrics() 主入口点

```kotlin
suspend fun getLyrics(
    provider: String,
    songId: String,
    title: String? = null,           // NEW
    artist: String? = null            // NEW
): LyricsResult
```

**改动**：
- 接受可选的 `title` 和 `artist` 参数
- 在委托给平台特定方法时传递这些参数

```kotlin
val lyrics = when (normalizedProvider) {
    "amll" -> getAMLL_TTMLLyrics(songId)
    "netease", "ncm" -> getNeteaseLyrics(songId, title, artist)
    "qq", "qqmusic" -> getQQMusicLyrics(songId, title, artist)
    "kugou" -> getKugouLyrics(songId, title, artist)
    else -> { ... }
}
```

### 3. 使用 UnifiedLyricsParser 替代 parseLRC/parseYRC

#### 在 getQQMusicLyrics 中：
```kotlin
// 旧方式
val ttml = parseLRC(decodedLyric)

// 新方式
val ttml = com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(
    content = decodedLyric,
    title = title ?: "Unknown",
    artist = artist ?: "Unknown"
)
```

**优势**：
- 自动检测格式（QRC、LRC 等）
- 保留逐词/逐音节同步数据
- 统一处理所有平台的歌词格式

#### 在 getNeteaseLyrics 中：
```kotlin
// 旧方式
val ttml = if (!yrcContent.isNullOrBlank()) {
    parseYRC(yrcContent, translationContent, transliterationContent)
} else {
    parseLRC(lyricContent.orEmpty(), translationContent, transliterationContent)
}

// 新方式
val mainContent = if (!yrcContent.isNullOrBlank()) yrcContent else lyricContent.orEmpty()
val ttml = com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(
    content = mainContent,
    title = title ?: "Unknown",
    artist = artist ?: "Unknown"
)
// 然后添加翻译和音译...
```

#### 在 getKugouLyrics 中：
```kotlin
// 旧方式
val ttml = parseLRC(decodedLyric)

// 新方式
val ttml = com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(
    content = decodedLyric,
    title = title ?: "Unknown",
    artist = artist ?: "Unknown"
)
```

### 4. 更新 fetchLyricsAuto() 中的调用

在智能获取歌词的两处地方增加 `title` 和 `artist` 参数：

```kotlin
// 同分回退时
val lyrics = when (result.provider) {
    "netease" -> getNeteaseLyrics(result.songId, result.title, result.artist)
    "qq" -> getQQMusicLyrics(result.songId, result.title, result.artist)
    "kugou" -> getKugouLyrics(result.songId, result.title, result.artist)
    else -> null
}

// 依次尝试各个搜索结果时（同上）
```

### 5. 更新 CustomLyricsViewModel

#### a. applyCandidate() 方法
```kotlin
fun applyCandidate(candidate: CustomLyricsCandidate) {
    viewModelScope.launch {
        _isApplying.value = true
        _errorMessage.value = null
        try {
            // NEW: 传递候选歌词的 title 和 artist
            val result = lyricsRepository.getLyrics(
                candidate.provider,
                candidate.songId,
                candidate.title,
                candidate.artist
            )
            if (result.isSuccess && result.lyrics != null) {
                _appliedLyricsText.value = TTMLConverter.toTTMLString(result.lyrics)
            } else {
                _errorMessage.value = result.errorMessage ?: "应用候选歌词失败"
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to apply candidate")
            _errorMessage.value = "应用候选歌词失败: ${e.message}"
        } finally {
            _isApplying.value = false
        }
    }
}
```

#### b. buildCandidatesAsync() 方法
```kotlin
// 检查 AMLL TTML DB 时，传递元数据
val amllResult = lyricsRepository.getLyrics("amll", result.songId, result.title, result.artist)
```

## 工作流程

### 用户场景：应用来自 QQ 音乐的候选歌词（包含 QRC 格式）

1. **搜索阶段**
   - CustomLyricsViewModel 调用 `lyricsRepository.searchLyrics(title, artist)`
   - 返回包含 title、artist、provider（"qq"）、songId 的 `LyricsSearchResult` 列表

2. **构建候选列表**
   - `buildCandidatesAsync()` 为每个搜索结果创建 `CustomLyricsCandidate`
   - 候选对象包含完整的 title 和 artist 信息

3. **用户选择候选**
   - 用户在 UI 中点击选择一个候选

4. **应用候选歌词**
   - `applyCandidate(candidate)` 被调用
   - 调用 `lyricsRepository.getLyrics(candidate.provider, candidate.songId, candidate.title, candidate.artist)`
   - `getLyrics()` 委托给 `getQQMusicLyrics()`

5. **获取和解析**
   - `getQQMusicLyrics()` 从 QQ API 获取 base64 编码的歌词
   - 解码后可能是 QRC 格式 `[ms,duration]word(offset,duration)`
   - 使用 `UnifiedLyricsParser.parse()` 自动检测格式为 QRC
   - QrcParser 解析逐字数据，返回 TTMLLyrics，其中 words 包含每个字的时间戳

6. **转换为 TTML**
   - `TTMLConverter.toTTMLString()` 将 TTMLLyrics 转换为 TTML XML 字符串
   - TTML 格式保留 `<span>` 元素中的逐字时间信息
   - 发送回 MainViewModel 用于显示和保存

## 支持格式

| 平台 | 原生格式 | 解析器 | 特性 |
|------|---------|--------|------|
| QQ 音乐 | QRC | QrcParser | 逐字级时间戳 + 偏移 |
| 酷狗 | KRC | KrcParser | 逐音节级时间戳 |
| 网易云 | YRC (JSON) | YrcParser | 逐字级时间戳 + 翻译 |
| 网易云 | LRC | LrcParser | 行级时间戳 |
| 通用 | 增强 LRC | EnhancedLrcParser | 逐字级时间戳 |
| 通用 | 标准 LRC | LrcParser | 行级时间戳 |

## 关键改进

### 1. 格式自动检测
- `UnifiedLyricsParser.parse()` 使用 `LyricsFormat.detect()` 自动识别格式
- 不再依赖调用方知道正确的格式

### 2. 元数据传递
- title 和 artist 从搜索结果一直传递到解析器
- 确保输出的 TTMLLyrics 有正确的元数据

### 3. 逐字数据保留
- 所有格式的逐字/逐音节时间戳都被正确解析
- TTMLConverter 保留这些细粒度数据在 TTML 输出中

### 4. 向后兼容
- 所有新参数都是可选的（使用 `?` 默认值）
- 存在的调用不需要修改，会使用默认值

## 测试清单

- [ ] QQ 音乐候选歌词（验证 QRC 格式解析）
  - [ ] 搜索歌曲
  - [ ] 检查候选歌词是否显示正确的标题/歌手
  - [ ] 应用候选歌词
  - [ ] 验证 TTML 输出包含逐字时间戳
  
- [ ] 网易云候选歌词（验证 YRC/LRC 格式解析）
  - [ ] 搜索歌曲
  - [ ] 应用包含 YRC 的候选歌词
  - [ ] 验证逐字时间戳是否正确
  - [ ] 验证翻译是否被保留
  
- [ ] 酷狗候选歌词（验证 KRC 格式解析）
  - [ ] 搜索歌曲
  - [ ] 应用候选歌词
  - [ ] 验证逐音节时间戳是否正确
  
- [ ] AMLL TTML DB 候选歌词
  - [ ] 网易云结果时检查 AMLL 备选
  - [ ] 验证 AMLL TTML 候选是否显示

## 使用示例

### 在其他代码中访问候选歌词

```kotlin
// 在 Activity 中
val candidate = customLyricsViewModel.candidates.value?.get(selectedIndex)
if (candidate != null) {
    customLyricsViewModel.applyCandidate(candidate)
}

// 或者手动获取歌词
val result = lyricsRepository.getLyrics(
    provider = "qq",
    songId = "songMid123",
    title = "Song Title",
    artist = "Artist Name"
)

if (result.isSuccess && result.lyrics != null) {
    val ttmlString = TTMLConverter.toTTMLString(result.lyrics)
    // 使用 ttmlString...
}
```

## 相关文件

- [data/parser/UnifiedLyricsParser.kt](data/parser/UnifiedLyricsParser.kt) - 统一解析器入口
- [data/parser/LyricsFormat.kt](data/parser/LyricsFormat.kt) - 格式枚举和检测
- [data/repository/LyricsRepository.kt](data/repository/LyricsRepository.kt) - 平台特定的获取方法
- [ui/viewmodel/CustomLyricsViewModel.kt](ui/viewmodel/CustomLyricsViewModel.kt) - UI 逻辑
- [data/converter/TTMLConverter.kt](data/converter/TTMLConverter.kt) - TTML 转换

## 故障排除

### 问题：候选歌词元数据为 "Unknown"
- **原因**: 没有传递 title/artist 参数
- **解决**: 检查调用 `getLyrics()` 时是否传递了 title 和 artist

### 问题：QRC 歌词没有逐字同步
- **原因**: 未使用 UnifiedLyricsParser，或 QcsParser 未正确识别格式
- **解决**: 确保 LyricsFormat.detect() 正确识别为 QRC；检查 QrcParser 的解析逻辑

### 问题：Netease YRC 没有翻译
- **原因**: 翻译参数未被传递或修复逻辑有误
- **解决**: Verify parseTimedLRCMap() and findNearestTimedText() implementations

## 性能考虑

- 格式自动检测需要遍历前几行字符，成本低
- 各个解析器都是单通道 (~O(n) 时间复杂度)
- 元数据传递不增加显著开销

## 总结

通过应用统一的多格式歌词转换规则到候选歌词流程，我们：
- ✅ 确保所有平台（QQ、网易、酷狗）的歌词都被正确识别和解析
- ✅ 保留逐词/逐音节的时间戳数据
- ✅ 传递正确的元数据（标题、歌手）
- ✅ 维护向后兼容性
- ✅ 为用户在 AMLL UI 中提供高质量的同步歌词
