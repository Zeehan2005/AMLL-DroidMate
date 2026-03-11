# DroidMate Unilyric 多源搜索集成 - 更新日志

## 更新时间
2026年3月8日

## 概述
成功将 [apoint123/Unilyric](https://github.com/apoint123/Unilyric) 的多源歌词搜索策略集成到 DroidMate 项目中。

## 主要变更

### 1. LyricsRepository.kt - 核心功能增强

#### 新增平台搜索方法

**QQ音乐**
```kotlin
suspend fun searchQQMusic(title: String, artist: String): List<LyricsSearchResult>  // now returns up to three candidates
suspend fun getQQMusicLyrics(songMid: String): TTMLLyrics?
```
- 搜索API: `https://u.y.qq.com/cgi-bin/musicu.fcg`
- 歌词API: 同上,不同参数
- 格式: Base64编码的LRC → 自动解码 → TTML
- 特点: 使用JSON请求体,支持批量搜索

**网易云音乐**
```kotlin
suspend fun searchNetease(title: String, artist: String): LyricsSearchResult?
suspend fun getNeteaseLyrics(songId: String): TTMLLyrics?
```
- 搜索API: `https://music.163.com/api/search/get/web`
- 歌词API: `https://music.163.com/api/song/lyric`
- 格式: LRC → TTML
- 特点: 与AMLL TTML DB的NCM ID对应,优先级最高（现已支持通过平台前缀查询，如qq:/spotify:）

**酷狗音乐**
```kotlin
suspend fun searchKugou(title: String, artist: String): LyricsSearchResult?
suspend fun getKugouLyrics(hash: String): TTMLLyrics?
```
- 搜索API: `http://mobilecdn.kugou.com/api/v3/search/song`
- 歌词API: `http://www.kugou.com/yy/index.php?r=play/getdata`
- 格式: Base64(可能) → LRC → TTML
- 特点: 使用hash作为歌曲ID

#### 智能搜索策略

**searchLyrics()** - 多源并行搜索
```kotlin
suspend fun searchLyrics(title: String, artist: String): List<LyricsSearchResult>
```
- 搜索所有平台 (网易云 → QQ → 酷狗)
- 计算置信度 (标题60% + 歌手40%)
- 按置信度降序排序
- 返回所有可用结果供选择

**fetchLyricsAuto()** - 全自动智能获取 (推荐)
```kotlin
suspend fun fetchLyricsAuto(
    title: String,
    artist: String,
    currentSourceName: String? = null  // 可选的播放来源名称，用于同分时优先排序
): LyricsResult
```
工作流程:
1. 调用 searchLyrics() 获取所有搜索结果
2. 若搜索结果中包含任何 AMLL（本地缓存）条目，则按置信度降序尝试这些缓存条目，成功则立即返回。
   这保证了本地已有歌词优先匹配，避免不必要的网络请求。
3. 缓存全部失败或不存在时，继续使用常规流程：按置信度依次尝试各平台直接API
4. 返回第一个成功的结果

**getLyrics()** - 增强版(支持所有provider)
```kotlin
suspend fun getLyrics(provider: String, songId: String): LyricsResult
```
支持的provider:
- `"amll"` → AMLL TTML DB
- `"netease"` / `"ncm"` → 网易云音乐
- `"qq"` / `"qqmusic"` → QQ音乐
- `"kugou"` → 酷狗音乐

#### 辅助功能

**calculateMatchConfidence()** - 匹配置信度计算
```kotlin
private fun calculateMatchConfidence(
    searchTitle: String, 
    searchArtist: String,
    resultTitle: String, 
    resultArtist: String
): Float
```
- 标题匹配: 0.6分
- 歌手匹配: 0.4分

## 2026-03-10
- 增强匹配系统：
  - 添加音调/重音去除，标点、"feat" 等标签过滤

## 2026-03-11
- 新增 `fetchLyricsAuto` 的 `currentSourceName` 可选参数，用以
  在搜索结果置信度相同的情况下进行偏好排序。
  支持在 `adjustResultsForFeatures` 中传入播放来源名称（例如
  应用名），并在候选完全平分时根据关键字“网易”、“QQ”、“酷狗”
  优先选取对应平台。
- 相应地更新文档与单元测试以覆盖新规则。
  - 支持 dash/paren 等价性、标题前置冠词移除
  - 艺术家匹配兼容 &/and 以及去除缩写差异
  - 调整权重和阈值以减少误判
  - 增加单元测试覆盖评估逻辑
- 返回范围: 0.0 - 1.0

**parseLRC()** - LRC格式解析器
```kotlin
private fun parseLRC(lrcContent: String): TTMLLyrics?
```
- 解析 `[ti:]`, `[ar:]` 元数据
- 正则匹配 `[mm:ss.xx]text` 格式
- 自动调整每行结束时间为下一行开始时间
- 返回标准化的 TTMLLyrics 对象

### 2. MainViewModel.kt - 业务逻辑更新

**fetchLyrics()** - 重构为智能搜索
```kotlin
fun fetchLyrics()
```
变更:
- **之前**: 直接调用 `getLyrics(provider="amll", songId="title-artist")`
- **现在**: 调用 `fetchLyricsAuto(title, artist)`
- **改进**: 
  - 自动搜索多个平台
  - 优先AMLL高质量歌词
  - 多重回退机制
  - 更准确的songId (不再使用"title-artist"拼接)

**歌词通知**
- 播放暂停时不再直接取消通知，而是将其更新为**可清理**的普通通知，方便一键清除；播放恢复时重新标记为 ongoing。
- 在暂停状态下应用只会发送一次 **ongoing=false** 的通知，然后不再更新歌词行，直到播放恢复。无论用户是否滑掉通知，这个暂停‑后‑停止的逻辑都适用。
- `MainViewModel.updateLyricNotification()` 现在传递 `ongoing` 标志给 `LyricNotificationManager`。
- 添加了 `ongoing` 参数到 `LyricNotificationManager.showOrUpdate()`。
### 3. 文档更新

**新增文档**
- `UNILYRIC_INTEGRATION.md` - 详细的Unilyric集成文档
  - API参考
  - 使用示例
  - 错误处理
  - 性能优化建议
  - 与Unilyric的功能对比

**更新文档**
- `README.md` - 更新歌词来源部分,突出多源搜索特性

## 技术细节

### API交互

**QQ音乐请求示例**
```kotlin
// 搜索
GET https://u.y.qq.com/cgi-bin/musicu.fcg
Parameters:
  data: {
    "music.search.SearchCgiService": {
      "module": "music.search.SearchCgiService",
      "method": "DoSearchForQQMusicDesktop",
      "param": {
        "query": "七里香 周杰伦",
        "page_num": 1,
        "num_per_page": 5
      }
    }
  }
  format: "json"

// 获取歌词
GET https://u.y.qq.com/cgi-bin/musicu.fcg
Parameters:
  data: {
    "req_1": {
      "module": "music.musichallSong.PlayLyricInfo",
      "method": "GetPlayLyricInfo",
      "param": {
        "songMID": "001Qu4I30eVFYb"
      }
    }
  }
  format: "json"
```

**网易云音乐请求示例**
```kotlin
// 搜索
GET https://music.163.com/api/search/get/web
Parameters:
  s: "七里香 周杰伦"
  type: "1"
  limit: "5"
  offset: "0"

// 获取歌词
GET https://music.163.com/api/song/lyric
Parameters:
  id: "186016"
  lv: "1"
  tv: "-1"
```

### 格式转换流程

```
QQ音乐: API Response → Base64 Decode → LRC → parseLRC() → TTML
网易云: API Response → LRC → parseLRC() → TTML
酷狗: API Response → Base64 Decode(可能) → LRC → parseLRC() → TTML
AMLL: API Response → TTML (直接使用 parseTTML())
```

### 错误处理

所有网络请求都包含完整的异常捕获:
- `UnknownHostException` - DNS解析失败
- `HttpStatusException` - HTTP错误状态码
- `SerializationException` - JSON解析失败
- `Exception` - 通用异常兜底


## 编译验证

### 编译状态
✅ **BUILD SUCCESSFUL**

### 警告信息 (非阻塞)
1. `Divider` 已弃用 → 建议改用 `HorizontalDivider`
2. `LinearProgressIndicator` 过时重载 → 建议使用lambda版本
3. 未使用参数: `convertLRCToTTML()` 和 `exportLyricsAsTTML()` 的参数

### 依赖版本
- Gradle: 8.7
- AGP: 8.4.0
- Kotlin: 1.9.23
- Ktor: 2.3.6
- Compose BOM: 2024.02.02

## 使用示例

### 基本使用 (自动搜索)
```kotlin
// 在 MainViewModel 中
fun fetchLyrics() {
    val music = _nowPlayingMusic.value ?: return
    
    viewModelScope.launch {
        val result = lyricsRepository.fetchLyricsAuto(
            title = music.title,
            artist = music.artist
        )
        
        if (result.isSuccess) {
            _lyrics.value = result.lyrics
            println("成功从 ${result.source} 获取歌词")
        }
    }
}
```

### 高级使用 (手动选择)
```kotlin
// 1. 搜索所有来源
val results = lyricsRepository.searchLyrics("七里香", "周杰伦")

// 2. 显示所有结果
results.forEach { result ->
    println("${result.provider}: ${result.title} - ${result.artist}")
    println("置信度: ${result.confidence}")
}

// 3. 选择最佳结果
val best = results.maxByOrNull { it.confidence }
if (best != null) {
    val lyrics = lyricsRepository.getLyrics(
        provider = best.provider,
        songId = best.songId
    )
}
```

## 性能考虑

### 当前实现
- **顺序搜索**: 网易云 → QQ → 酷狗
- **平均耗时**: 1-3秒 (取决于网络)
- **超时设置**: HttpClient 默认超时

## 测试建议

### 测试场景

1. **流行歌曲** (预期: 所有平台都有)
   - 测试歌曲: "七里香 - 周杰伦"
   - 预期结果: AMLL TTML DB 成功

2. **冷门歌曲** (预期: 仅部分平台有)
   - 测试歌曲: 小众独立音乐
   - 预期结果: 回退到普通LRC歌词

3. **纯音乐** (预期: 无歌词)
   - 测试歌曲: 钢琴曲/纯音乐
   - 预期结果: "找到歌曲但无法获取歌词"

4. **网络异常** (预期: 错误处理)
   - 场景: 断开网络连接
   - 预期结果: 清晰的错误信息

### 日志检查

```bash
# 查看搜索流程
adb logcat -s DroidMate:* | grep "search"

# 查看获取结果
adb logcat -s DroidMate:* | grep "fetched"

# 查看错误
adb logcat -s DroidMate:* | grep -E "(ERROR|WARN)"
```

## 与 Unilyric 的对比

| 功能 | Unilyric (Rust) | DroidMate (Kotlin/Android) | 状态 |
|------|----------------|---------------------------|------|
| QQ音乐搜索 | ✅ | ✅ | 完成 |
| 网易云搜索 | ✅ | ✅ | 完成 |
| 酷狗搜索 | ✅ | ✅ | 完成 |
| AMLL TTML DB | ✅ | ✅ | 完成 |
| 置信度计算 | ✅ | ✅ | 完成 |
| LRC解析 | ✅ | ✅ | 完成 |
| 并行搜索 | ✅ | ❌ | 待优化 |
| 多格式转换 | ✅ (10+) | ⚠️ (LRC/TTML) | 部分 |
| 歌词编辑 | ✅ | ❌ | ❌ |
| WebSocket联动 | ✅ | ❌ | ❌ |

## 致谢

特别感谢以下项目和贡献者:

- [apoint123/Unilyric](https://github.com/apoint123/Unilyric) - 多源搜索策略灵感来源
- [amll-dev/amll-ttml-db](https://github.com/amll-dev/amll-ttml-db) - 高质量歌词数据库
- [Steve-xmh](https://github.com/Steve-xmh) - AMLL 生态系统维护


---

**更新者**: GitHub Copilot  
**审核者**: 待审核  
**版本**: v1.0.0
