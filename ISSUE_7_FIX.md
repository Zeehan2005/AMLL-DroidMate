# Issue #7 修复：背景歌词括号和翻译问题

## 问题描述
背景歌词在前端应删去每一句的括号（括号在背景歌词第一词的首和最后一词的尾），并且显示背景歌词的翻译。

## 修改内容

### 1. 后端修改 - TTMLParser.kt
**文件**: `app/src/main/java/com/amll/droidmate/data/parser/TTMLParser.kt`

#### 修改1：添加背景翻译和音译字段
在 `ParagraphParseBuffer` 数据类中添加了两个新字段：
```kotlin
private data class ParagraphParseBuffer(
    val mainWords: MutableList<LyricWord> = mutableListOf(),
    val bgWords: MutableList<LyricWord> = mutableListOf(),
    var translation: String? = null,
    var transliteration: String? = null,
    var bgTranslation: String? = null,        // 新增
    var bgTransliteration: String? = null     // 新增
)
```

#### 修改2：收集背景歌词的翻译和音译
修改 `parseNodeChildren` 方法，使其在解析背景歌词（`inBackground=true`）时也收集翻译和音译内容：

```kotlin
"x-translation" -> {
    val text = normalizeAuxiliaryText(element.textContent ?: "")
    if (text.isNotEmpty()) {
        if (inBackground) {
            buffer.bgTranslation = text  // 背景歌词的翻译
        } else {
            buffer.translation = text    // 主歌词的翻译
        }
    }
}

"x-roman", "x-romanization" -> {
    val text = normalizeAuxiliaryText(element.textContent ?: "")
    if (text.isNotEmpty()) {
        if (inBackground) {
            buffer.bgTransliteration = text  // 背景歌词的音译
        } else {
            buffer.transliteration = text    // 主歌词的音译
        }
    }
}
```

#### 修改3：在创建背景歌词行时使用翻译和音译
修改背景歌词行的创建，添加 `translation` 和 `transliteration` 字段：

```kotlin
val bgLine = if (bgText.isNotEmpty()) {
    val bgStart = buffer.bgWords.firstOrNull()?.startTime ?: startTime
    val bgEndRaw = buffer.bgWords.lastOrNull()?.endTime ?: endTime
    val bgEnd = maxOf(bgStart, bgEndRaw)
    LyricLine(
        startTime = bgStart,
        endTime = bgEnd,
        text = bgText,
        translation = buffer.bgTranslation,        // 新增
        transliteration = buffer.bgTransliteration, // 新增
        words = buffer.bgWords.toList(),
        isBG = true
    )
}
```

### 2. 前端修改 - main.js
**文件**: `frontend/src/main.js`

#### 修改：去除背景歌词的括号
在 `toWordEntries` 函数中添加了逻辑，当歌词行是背景歌词（`isBG=true`）时：
- 去除第一个词开头的 `(` 括号
- 去除最后一个词结尾的 `)` 括号

```javascript
// 背景歌词：去除第一个词开头的'('和最后一个词结尾的')'
if (line?.isBG && normalized.length > 0) {
  // 去除第一个词的开头括号
  const firstWord = normalized[0]
  if (firstWord.word.startsWith('(')) {
    firstWord.word = firstWord.word.substring(1)
  }

  // 去除最后一个词的结尾括号
  const lastWord = normalized[normalized.length - 1]
  if (lastWord.word.endsWith(')')) {
    lastWord.word = lastWord.word.substring(0, lastWord.word.length - 1)
  }
}
```

## 技术细节

### TTML规范参考
根据 [AMLL TTML规范 5.3节](https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/instructions/ttml-specification.md)：

1. **背景人声标记**：使用 `<span ttm:role="x-bg" begin="..." end="...">...</span>`
2. **括号约定**：建议使用半角括号将背景人声文本包裹起来
3. **背景歌词的翻译和音译**：可以在背景人声的 `<span>` 内嵌套翻译和音译

示例TTML结构：
```xml
<span ttm:role="x-bg" begin="00:30.500" end="00:32.500">
    <!-- 背景人声的主歌词 -->
    <span begin="00:30.500" end="00:31.500">(秘密</span>
    <span begin="00:31.600" end="00:32.500">だよ)</span>
    
    <!-- 背景人声的翻译 -->
    <span ttm:role="x-translation" xml:lang="zh-CN">是秘密哦</span>
    
    <!-- 背景人声的音译 -->
    <span ttm:role="x-roman" xml:lang="ja-Latn">himitsu da yo</span>
</span>
```

### 数据流
1. **TTML解析** → TTMLParser 解析TTML文件，提取背景歌词及其翻译/音译
2. **数据传输** → WebView通过JSON传递歌词数据（包含 `translatedLyric` 和 `romanLyric`）
3. **前端处理** → `toWordEntries` 函数去除背景歌词的括号
4. **显示** → AMLL核心库的 `setSubLinesText` 方法显示翻译和音译

## 测试建议

1. **准备测试用例**：创建包含背景歌词且带有翻译和音译的TTML文件
2. **验证括号去除**：检查前端显示的背景歌词是否正确去除了首尾括号
3. **验证翻译显示**：确认背景歌词的翻译和音译是否正确显示
4. **边界情况测试**：
   - 只有一个词的背景歌词（同时有开括号和闭括号）
   - 没有括号的背景歌词
   - 只有开括号或只有闭括号的情况

## 相关文件
- [app/src/main/java/com/amll/droidmate/data/parser/TTMLParser.kt](app/src/main/java/com/amll/droidmate/data/parser/TTMLParser.kt)
- [frontend/src/main.js](frontend/src/main.js)
- [AMLL TTML规范](https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/instructions/ttml-specification.md)

## 参考链接
- [GitHub Issue #7](https://github.com/Zeehan2005/AMLL-DroidMate/issues/7)
- [AMLL TTML Database](https://github.com/amll-dev/amll-ttml-db)
- [Apple Music-like Lyrics](https://github.com/amll-dev/applemusic-like-lyrics)
