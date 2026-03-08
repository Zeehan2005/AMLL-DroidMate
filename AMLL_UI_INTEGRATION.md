# AMLL UI 集成说明

## 概述

DroidMate 现在使用 AMLL (Apple Music-like Lyrics) 的漂亮 UI 组件来显示歌词,提供与 iPad 版 Apple Music 相似的视觉体验。

## 实现方案

### 技术架构

由于 AMLL 是基于 Web 技术(TypeScript/JavaScript)构建的,我们采用 **WebView** 方案来集成 AMLL UI:

```
Android App (Jetpack Compose)
    ↓
WebView (AMLLLyricsView)
    ↓
AMLL Core (JavaScript via CDN)
    ↓
Apple Music风格歌词显示
```

### 核心组件

#### 1. AMLLLyricsView.kt
封装了 AMLL WebView 组件的 Kotlin Composable:

```kotlin
@Composable
fun AMLLLyricsView(
    lyrics: TTMLLyrics?,      // 歌词数据
    currentTime: Long,         // 当前播放时间(毫秒)
    modifier: Modifier = Modifier
)
```

**特性**:
- ✅ 使用 WebView 加载 AMLL Core
- ✅ 通过 CDN 加载最新的 AMLL 组件
- ✅ 实时同步播放进度
- ✅ JavaScript ↔ Android 双向通信
- ✅ 降级方案(CDN失败时的简化显示)

#### 2. 主屏幕集成
在 [MainScreen.kt](app/src/main/java/com/amll/droidmate/ui/screens/MainScreen.kt) 中使用:

```kotlin
if (lyrics != null) {
    Card(...) {
        Column {
            Text("歌词 (AMLL风格)")
            
            AMLLLyricsView(
                lyrics = lyrics!!,
                currentTime = currentTime,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}
```

### JavaScript 接口

#### Android → JavaScript

**更新歌词**:
```javascript
updateLyrics(lyricsJson)
```

**更新播放时间**:
```javascript
updateTime(milliseconds)
```

#### JavaScript → Android

**日志输出**:
```javascript
Android.log(message)
```

**点击事件**:
```javascript
Android.onLineClick(lineIndex)
```

## AMLL 特性

### 视觉效果

1. **Apple Music 风格动画**
   - 平滑的行级滚动
   - 当前行高亮放大
   - 渐变过渡效果

2. **动态流体背景** (可选)
   - 根据专辑封面颜色生成
   - 实时音频频谱可视化

3. **逐字同步**
   - 支持 AMLL TTML DB 的逐字歌词
   - 精确到单词/字的时间轴
   - 卡拉OK风格高亮

### 支持的歌词格式

AMLL Core 支持多种格式(通过 AMLL-Lyric 模块):
- ✅ TTML (完整支持逐字同步)
- ✅ LRC (标准歌词格式)
- ✅ YRC (网易云逐字格式)
- ✅ QRC (QQ音乐逐字格式)
- ✅ Lyricify Syllable

DroidMate 当前主要使用 TTML 格式。

## 性能优化

### 内存管理

WebView 会占用额外内存,当前配置:
- **WebView 高度**: 400dp (可调整)
- **JavaScript 启用**: 仅在歌词显示时
- **缓存策略**: 不缓存,每次加载最新版本

### 降级方案

当 AMLL Core CDN 加载失败时,自动启用简化显示:
- 纯文本歌词列表
- 基于时间的行高亮
- 简单的 CSS 动画

```javascript
function displaySimpleLyrics(lyrics) {
    // 降级为简单的 HTML + CSS 显示
    // 不依赖 AMLL Core
}
```

## 网络依赖

### CDN 资源

```html
<script src="https://cdn.jsdelivr.net/npm/@applemusic-like-lyrics/core@latest/dist/index.js">
```

**注意事项**:
- 需要网络连接才能首次加载 AMLL Core
- 建议在 WiFi 环境下使用
- CDN 失败会自动降级到简化模式

## 使用示例

### 基本使用

```kotlin
@Composable
fun MyLyricsScreen() {
    val lyrics by viewModel.lyrics.collectAsState()
    val currentTime by viewModel.currentTime.collectAsState()
    
    AMLLLyricsView(
        lyrics = lyrics,
        currentTime = currentTime,
        modifier = Modifier
            .fillMaxWidth()
            .height(500.dp)
    )
}
```

### 自定义配置

可以修改 `getAMLLHtml()` 中的 CSS 变量:

```css
:root {
    --amll-lyric-player-font-size: 48px;           /* 字体大小 */
    --amll-lyric-player-font-weight: 700;          /* 字体粗细 */
    --amll-lyric-player-line-color: rgba(255, 255, 255, 0.4);  /* 未激活行颜色 */
    --amll-lyric-player-line-color-active: rgba(255, 255, 255, 1);  /* 激活行颜色 */
    --amll-lyric-player-bg-color: #000;            /* 背景颜色 */
}
```

## 调试

### 查看 WebView 日志

```bash
adb logcat -s DroidMate:* | grep "AMLL"
```

输出示例:
```
D DroidMate: AMLL WebView initialized
D DroidMate: AMLL-JS: Page loaded, initializing AMLL...
D DroidMate: AMLL-JS: AMLL Player initialized successfully
D DroidMate: Lyrics updated in AMLL WebView
```

### Chrome DevTools 调试

启用 WebView 调试:

```kotlin
if (BuildConfig.DEBUG) {
    WebView.setWebContentsDebuggingEnabled(true)
}
```

然后访问: `chrome://inspect/#devices`

## 已知问题与限制

### 当前限制

1. **网络依赖**
   - 首次加载需要网络
   - CDN 在中国大陆可能较慢
   - 解决方案: 使用备用 CDN 或离线打包

2. **性能开销**
   - WebView 会增加内存占用 (~30MB)
   - JavaScript 执行有延迟
   - 解决方案: 限制 WebView 高度,按需加载

3. **动画流畅度**
   - 取决于设备性能
   - 低端设备可能卡顿
   - 解决方案: 提供简化模式选项

### 兼容性

- ✅ Android 7.0+ (API 26+)
- ✅ WebView 91+
- ⚠️ 低于 Android 7.0 可能无法正常显示

## 与原生UI的对比

| 特性 | 原生 Compose UI | AMLL WebView |
|------|----------------|--------------|
| 视觉效果 | 简单 | Apple Music 风格 ⭐ |
| 性能 | 优秀 | 良好 |
| 内存占用 | 低 | 中等 |
| 逐字同步 | 不支持 | 完整支持 ⭐ |
| 网络依赖 | 无 | 首次加载需要 |
| 定制性 | 高 | 中等 |

## 参考资料

- [AMLL 官方文档](https://github.com/amll-dev/applemusic-like-lyrics)
- [AMLL Core README](https://github.com/amll-dev/applemusic-like-lyrics/blob/full-refractor/packages/core/README.md)
- [AMLL Player 预览](https://amll-dev.github.io/applemusic-like-lyrics/)
- [WebView 最佳实践](https://developer.android.com/guide/webapps/webview)

## 许可证

AMLL 使用 AGPL-3.0 许可证。DroidMate 作为客户端应用使用 AMLL 的 JavaScript 库,不受 AGPL 传染性影响,仍可保持 MIT 许可证。

但如果修改 AMLL Core 源码并分发,则需要遵守 AGPL-3.0。

## 致谢

感谢 [AMLL 项目](https://github.com/amll-dev/applemusic-like-lyrics) 提供如此漂亮的歌词组件!

特别感谢:
- [@Steve-xmh](https://github.com/Steve-xmh) - AMLL原作者
- [@apoint123](https://github.com/apoint123) - AMLL核心维护者
- AMLL社区所有贡献者

---

**更新时间**: 2026年3月8日  
**版本**: v1.0.0
