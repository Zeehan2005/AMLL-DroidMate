# Phase 2 完成报告 - 自选歌词候选集成

## 阶段目标
"把自选歌词里面各个平台的候选歌词也应用这样的转换"
- 将 Phase 1 完成的统一多格式解析器应用到自选歌词功能中的候选歌词流程

## 完成内容

### 1. 更新方法签名 ✅

#### LyricsRepository 平台获取方法
- `getQQMusicLyrics()` - 添加可选的 title/artist 参数
- `getNeteaseLyrics()` - 添加可选的 title/artist 参数
- `getKugouLyrics()` - 添加可选的 title/artist 参数

#### 主入口点
- `getLyrics()` - 添加可选的 title/artist 参数，并在委托时传递

### 2. 使用 UnifiedLyricsParser ✅

将所有平台的获取方法从 `parseLRC()`/`parseYRC()` 替换为 `UnifiedLyricsParser.parse()`：

#### getQQMusicLyrics()
```kotlin
val ttml = com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(
    content = decodedLyric,
    title = title ?: "Unknown",
    artist = artist ?: "Unknown"
)
```

#### getNeteaseLyrics()
```kotlin
val mainContent = if (!yrcContent.isNullOrBlank()) yrcContent else lyricContent.orEmpty()
val ttml = com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(
    content = mainContent,
    title = title ?: "Unknown",
    artist = artist ?: "Unknown"
)
// 并保留翻译/音译的追加逻辑
```

#### getKugouLyrics()
```kotlin
val ttml = com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(
    content = decodedLyric,
    title = title ?: "Unknown",
    artist = artist ?: "Unknown"
)
```

### 3. 更新 fetchLyricsAuto() ✅

在智能获取歌词中的两处调用添加 title/artist：
- 同分回退时：传递 `result.title` 和 `result.artist`
- 依次尝试各个搜索结果时：传递 `result.title` 和 `result.artist`

### 4. 更新 CustomLyricsViewModel ✅

#### applyCandidate() 方法
```kotlin
val result = lyricsRepository.getLyrics(
    candidate.provider,
    candidate.songId,
    candidate.title,
    candidate.artist
)
```

#### buildCandidatesAsync() 方法
```kotlin
val amllResult = lyricsRepository.getLyrics("amll", result.songId, result.title, result.artist)
```

## 代码变更汇总

### 修改的文件：4 个

1. **app/src/main/java/com/amll/droidmate/data/repository/LyricsRepository.kt**
   - 修改 `getQQMusicLyrics()` 签名和实现
   - 修改 `getNeteaseLyrics()` 签名和实现
   - 修改 `getKugouLyrics()` 签名和实现
   - 修改 `getLyrics()` 签名和实现
   - 更新 `fetchLyricsAuto()` 中的两处调用

2. **app/src/main/java/com/amll/droidmate/ui/viewmodel/CustomLyricsViewModel.kt**
   - 修改 `applyCandidate()` 方法
   - 修改 `buildCandidatesAsync()` 方法

### 创建的文件：1 个

1. **CANDIDATE_LYRICS_INTEGRATION.md**
   - 完整的集成文档
   - 工作流程说明
   - 使用示例
   - 测试清单

## 编译状态

✅ **零错误** - 所有代码正确编译

## 工作流程验证

### 用户操作流程
```
用户搜索歌曲
    ↓
获取候选歌词列表（包含多个平台）
    ↓
用户选择一个候选歌词（例如 QQ 音乐的 QRC 格式）
    ↓
applyCandidate(candidate) 被调用
    ↓
getLyrics(provider, songId, title="选中的标题", artist="选中的歌手")
    ↓
getQQMusicLyrics(songId, title, artist) - 获取 QRC 格式歌词
    ↓
UnifiedLyricsParser.parse(QRC内容, title, artist) - 自动识别 QRC 格式
    ↓
QrcParser 解析逐字数据，返回 TTMLLyrics (包含 words 列表)
    ↓
TTMLConverter.toTTMLString() - 转换为 TTML XML (保留逐字 span)
    ↓
UI 显示和保存歌词
```

## 关键改进

### 1. 格式自动识别
- QQ 音乐：QRC 格式自动识别 `[ms,duration]word(offset,duration)`
- 酷狗：KRC 格式自动识别 `[ms,duration]<offset,duration,0>word`
- 网易：YRC JSON 格式自动识别
- 备用：标准 LRC 格式 `[mm:ss.ms]`

### 2. 逐词数据保留
- QQ：逐字级时间戳 (word-level)
- 酷狗：逐音节级时间戳 (syllable-level)
- 网易：逐字级时间戳 + 翻译
- 输出 TTML 包含所有 `<span>` 元素的完整时间信息

### 3. 元数据正确性
- title 和 artist 从 searchResult 传递到解析器
- 所有输出的 TTMLLyrics 都有正确的元数据
- TTML 字符串包含正确的 metadata

### 4. 向后兼容性
- 所有新参数都是可选的（默认值）
- 没有破坏性改动
- 存在的代码不需要修改

## 潜在的改进领域

## 验证清单

- [x] 方法签名更新（5 个方法）
- [x] UnifiedLyricsParser 集成（3 个平台）
- [x] metadata 传递直通（title/artist）
- [x] 向后兼容性保持
- [x] 编译无错误
- [x] 所有调用点更新
- [x] 文档编写完成

## 与 Phase 1 的关系

| 阶段 | 目标 | 完成情况 | 输出 |
|------|------|---------|------|
| Phase 1 | 创建统一的多格式解析器 | ✅ 完成 | 7 个 Parser 类 + 1 个 Unified Entry |
| Phase 2 | 应用到候选歌词流程 | ✅ 完成 | 5 个方法更新 + 1 个集成文档 |

## 功能完整性

### 支持的平台和格式

| 平台 | 来源格式 | 输出格式 | 逐词级别 | 特殊功能 |
|------|---------|---------|---------|---------|
| QQ 音乐 | QRC | TTML+words | ✅ 字 | 偏移量 |
| 酷狗 | KRC | TTML+words | ✅ 音节 | - |
| 网易云 | YRC | TTML+words | ✅ 字 | 翻译+ 音译 |
| 网易云 | LRC | TTML | ❌ 行 | - |
| 通用 | 增强 LRC | TTML+words | ✅ 字 | - |
| 通用 | 标准 LRC | TTML | ❌ 行 | - |
| AMLL | TTML | TTML | ✅ 字 | 原生 TTML |

## 代码质量指标

- **编译错误**: 0 ❌ → 0 ✅
- **向后兼容**: 100% ✅
- **文档覆盖**: 完整 ✅
- **实现一致性**: 高 ✅

## 最后验证

所有改动的关键点：
1. ✅ 新参数是可选的，不会破坏现有代码
2. ✅ 所有平台获取方法都使用 UnifiedLyricsParser
3. ✅ CustomLyricsViewModel 正确传递 title/artist
4. ✅ fetchLyricsAuto() 也使用新参数
5. ✅ 没有遗留的调用点未更新
6. ✅ 文档清晰说明了工作流程

## 总结

Successfully integrated unified multi-format lyrics parsing into the custom lyrics candidate selection workflow. All candidate lyrics from QQ Music, Netease, and Kugou will now:

- ✅ Be automatically identified by format (QRC, KRC, YRC, LRC)
- ✅ Have word/syllable-level timing data preserved
- ✅ Include correct metadata (title, artist)
- ✅ Be converted to TTML with all sync details intact
- ✅ Display properly in AMLL UI for synchronization

**Status: Phase 2 Complete** 🎉
