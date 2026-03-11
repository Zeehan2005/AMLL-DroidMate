# 多格式歌词解析器集成 (Unilyric 规则)

## 概述

已成功集成基于 Unilyric 项目的多格式歌词解析规则到 DroidMate 应用中。现在支持解析多种歌词格式，包括 LRC、QRC、KRC、YRC 等。

## 支持的格式

| 格式 | 说明 | 来源 | 逐字时间戳 |
|------|------|------|-----------|
| **LRC** | 标准歌词格式 | 通用 | ❌ |
| **Enhanced LRC** | 增强型LRC，逐字时间戳 | 通用 | ✅ |
| **QRC** | QQ音乐逐字歌词格式 | QQ音乐 | ✅ |
| **KRC** | 酷狗音乐逐字歌词格式 | 酷狗音乐 | ✅ |
| **YRC** | 网易云音乐逐字歌词格式 (JSON) | 网易云音乐 | ✅ |
| **TTML** | Apple Music 式歌词格式 | Apple Music | ✅ |
| **纯文本** | 无时间戳的纯文本 | 通用 | ❌ |

## 实现文件

### 核心解析器类

1. **LyricsFormat.kt** - 歌词格式枚举和自动检测
   - 自动检测歌词内容的格式
   - 支持从文件扩展名获取格式

2. **LrcParser.kt** - 标准 LRC 格式解析器
   - 支持 `[mm:ss.ms]` 时间戳
   - 支持多个时间戳指向同一行
   - 自动提取元数据 (ti, ar, al)

3. **EnhancedLrcParser.kt** - 增强型 LRC 格式解析器
   - 支持逐字时间戳: `<mm:ss.ms>词`
   - 自动计算词的持续时间

4. **QrcParser.kt** - QQ音乐 QRC 格式解析器
   - 时间戳格式: `[起始ms,持续ms]`
   - 逐字格式: `文本(起始,持续)`
   - 示例: `[0,3500]在(0,500)世(500,300)界(800,400)`

5. **KrcParser.kt** - 酷狗音乐 KRC 格式解析器
   - 时间戳格式: `[起始ms,持续ms]`
   - 逐字格式: `<偏移,持续,0>字`
   - 示例: `[0,3500]<0,500,0>在<500,300,0>世<800,400,0>界`

6. **YrcParser.kt** - 网易云音乐 YRC 格式解析器
   - JSON 格式
   - 格式: `{"t":起始时间,"c":[{"tx":"文","tr":(offset,duration,0)}]}`
   - 自动提取元数据并跳过

7. **UnifiedLyricsParser.kt** - 统一歌词解析器

    修复：KRC 元数据内的括号导致错误识别为 QRC。现在检测逻辑
    将 KRC 放在 QRC 之前，并要求 QRC 的时间戳与括号出现在同一行。

   - 自动检测格式
   - 统一的解析接口
   - 支持语言检测

### 更新的现有文件

1. **TTMLConverter.kt**
   - 新增 `fromLyrics()` 方法，支持多种格式
   - 旧的 `fromLRC()` 方法标记为 `@Deprecated`

2. **LyricsRepository.kt**
   - 更新 `parseLRC()` 和 `parseYRC()` 方法使用新的统一解析器
   - 保持向后兼容性

3. **CustomLyricsViewModel.kt**
   - 更新 `parseInput()` 方法使用统一解析器
   - 自动检测和解析多种格式

## 使用示例

### 自动检测格式并解析

```kotlin
// 自动检测格式
val lyrics = UnifiedLyricsParser.parse(
    content = lyricsContent,
    title = "歌曲名",
    artist = "歌手名"
)
```

### 指定格式解析

```kotlin
// 检测格式
val format = LyricsFormat.detect(content)

// 使用指定格式解析
val lines = UnifiedLyricsParser.parseWithFormat(content, format)
```

### 在 TTMLConverter 中使用

```kotlin
// 旧方法（仍然可用但已弃用）
val lyrics1 = TTMLConverter.fromLRC(lrcContent)

// 新方法（推荐）
val lyrics2 = TTMLConverter.fromLyrics(
    content = content,  // 支持多种格式
    title = "歌曲名",
    artist = "歌手名"
)
```

### 在 LyricsRepository 中使用

```kotlin
// parseLRC 现在使用新的解析器
val lyrics = lyricsRepository.parseLRC(
    lrcContent = content,
    translationLrc = translationContent,  // 可选
    transliterationLrc = romanization     // 可选
)
```

## 格式检测规则

解析器会按以下顺序检测格式：

1. **TTML**: 以 `<?xml` 或 `<tt` 开头
2. **YRC**: 包含以 `{"t":` 开头的 JSON 行
3. **QRC**: 包含 `[数字,数字]` 格式的时间戳
4. **KRC**: 包含 `[language:]`, `[id:]` 或 `[hash:]` 等标签
5. **Enhanced LRC**: 包含 `<mm:ss.ms>` 格式的逐字时间戳
6. **LRC**: 包含 `[mm:ss.ms]` 格式的时间戳
7. **纯文本**: 无时间戳的普通文本

## 格式示例

### LRC 格式
```
[ti:歌曲名]
[ar:歌手名]
[00:12.50]在世界的尽头
[00:16.80]会有另一个我
```

### Enhanced LRC 格式
```
[00:12.50]<00:12.50>在<00:13.00>世<00:13.30>界<00:13.70>的<00:14.00>尽<00:14.40>头
```

### QRC 格式 (QQ音乐)
```
[0,3500]在(0,500)世(500,300)界(800,400)的(1200,300)尽(1500,400)头(1900,600)
```

### KRC 格式 (酷狗)
```
[language:zh-CN]
[0,3500]<0,500,0>在<500,300,0>世<800,400,0>界<1200,300,0>的<1500,400,0>尽<1900,600,0>头
```

### YRC 格式 (网易云)
```json
{"t":0,"c":[{"tx":"在","tr":(0,500,0)},{"tx":"世","tr":(500,300,0)},{"tx":"界","tr":(800,400,0)}]}
```

## 参考资料

- [Unilyric 项目](https://github.com/apoint123/Unilyric)
- [lyrics_helper_rs](https://github.com/apoint123/Unilyric/tree/main/lyrics_helper_rs)
- 具体解析器实现参考了 Unilyric 的 Rust 代码，使用 Kotlin 重新实现

## 测试

所有解析器都包含详细的日志输出，可以通过 Timber 查看：
\

## 向后兼容性

- 所有现有的 API 保持不变
- `TTMLConverter.fromLRC()` 仍然可用（虽然已标记为废弃）
- `LyricsRepository.parseLRC()` 接口未变，内部使用新解析器

## 注意事项

1. **自动格式检测**: 大多数情况下无需指定格式，解析器会自动检测
2. **翻译和音译**: QRC、KRC、YRC 格式可以包含翻译和音译信息
3. **时间戳精度**: 所有时间戳统一使用毫秒 (ms) 作为单位
4. **元数据提取**: 自动提取歌词中的元数据 (标题、艺术家、专辑等)
