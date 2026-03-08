# 点击跳转功能调试指南

## 问题诊断 (2026-03-08)

如果点击歌词行没有生效，请按以下步骤调试：

### 第一步：检查 Android 接口

在 Android 代码中，确保已注册 JavaScript 接口：

```kotlin
// 在配置 WebView 时
webView.addJavascriptInterface(object : Any() {
    @JavascriptInterface
    fun onLineClick(lineIndex: Int, startTimeMs: Int) {
        // 这里处理跳转逻辑
        Log.d("AMLL", "Line clicked: index=$lineIndex, startTime=$startTimeMs")
        // 调用音乐播放器的 seek() 方法
        // musicPlayer.seekTo(startTimeMs)
    }
    
    @JavascriptInterface
    fun log(message: String) {
        Log.d("AMLL", message)
    }
}, "Android")
```

### 第二步：检查日志输出

点击歌词行后，在 Android 日志中查找以下信息。如果缺少某些日志，表示哪一步失败：

#### ✅ 成功点击的日志序列
```
[AMLL-INIT] Android.onLineClick interface is ready
[AMLL-TAP] Tap detected at coordinates (X, Y), duration=Zms
[AMLL-TAP] Clicked element: DIV, class=...
[AMLL-TAP] Found lyric line element
[AMLL-TAP] Dispatched click event
[AMLL-CLICK] Line X clicked, seeking to Yms
```

#### 问题 1：日志中出现 `[AMLL-CLICK-DEBUG] Event object`
这说明点击事件正确触发，但 `startTime` 的获取可能有问题。
- 检查日志中 `Method 1/2/3` 哪个方式成功获取了时间
- 如果都失败，需要检查 LyricPlayer 的事件结构

#### 问题 2：日志中出现 `[AMLL-TAP] Clicked element`
说明点击检测工作正常，但可能点击的不是歌词行。
- 检查 `class=` 后的类名，确认是否为歌词行元素
- 类名应该包含 `lyric` 或类似 `_lyricLine_1vq69_6` 的模式

#### 问题 3：日志中出现 `WARNING: Android.onLineClick interface NOT found`
说明 Android 接口没有正确注册。
- 确保在 WebView 上调用了 `addJavascriptInterface()`
- 检查接口的对象名是否为 `"Android"`

---

## 改进的点击机制

为了提高点击的可靠性，新版本使用了两层触发机制：

### 1️⃣ 第一层：LyricPlayer 原生事件（最优）
```javascript
player.addEventListener('line-click', (evt) => {
  // 直接从 LyricPlayer 获取点击事件
  Android.onLineClick(lineIndex, startTime)
})
```

### 2️⃣ 第二层：触摸点击检测（备用）
```javascript
// 在 touchend 事件中
if (!state.touch.isMoved && touchDuration < 300) {
  // 检测用户是否快速点击（<300ms，没有显著移动）
  const element = document.elementFromPoint(x, y)
  // 查找最近的歌词行元素
  const lyricLine = element.closest('._lyricLine_1vq69_6, ._lyricLine_1ygrf_6')
  // 通过 dispatchEvent 触发 click 事件
  lyricLine.dispatchEvent(new MouseEvent('click', {...}))
}
```

---

## 常见问题解决

### Q1: 点击后什么都没有发生
**可能原因**：
- [ ] Android 接口未注册 → 检查 `addJavascriptInterface()` 调用
- [ ] JavaScript 代码出错 → 查看日志是否有 `[AMLL-ERROR]`
- [ ] WebView 启用了 JavaScript → 确认 `settings.javaScriptEnabled = true`

**解决方案**：
```kotlin
webView.settings.javaScriptEnabled = true
webView.addJavascriptInterface(MyJavaScriptInterface(), "Android")
```

### Q2: 日志中无 `[AMLL-TAP]` 信息
**可能原因**：
- 点击持续时间超过 300ms，被当作拖拽处理
- 点击发生的移动超过 10px（threshold）

**解决方案**：
快速轻轻点击（<300ms，几乎没有移动）

### Q3: 日志显示 `[AMLL-TAP-ERROR]`
**可能原因**：
- JavaScript 异常导致点击处理失败
- DOM 元素结构与预期不符

**解决方案**：
检查完整的错误信息。如 `elementFromPoint()` 或 `closest()` 失败，考虑调整类名匹配规则。

---

## 高级调试

### 启用 WebView 调试（开发者选项）

#### Kotlin 代码：
```kotlin
import android.webkit.WebView

// 在 API 19+ 启用调试
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
    WebView.setWebContentsDebuggingEnabled(true)
}
```

然后在 Chrome 浏览器中访问 `chrome://inspect/#devices` 调试 WebView。

### 手动触发测试

在 Android 代码中手动触发点击以验证 Android 接口：

```kotlin
// 手动测试 JavaScript 接口是否工作
webView.evaluateJavascript("window.Android.log('Test message from Java')") { _ -> }
```

---

## HTML/CSS 层面的排查

### 检查是否有元素阻挡点击

在 HTML 中添加检查脚本（仅用于调试）：

```html
<!-- 在 index.html 的 </head> 之前添加 -->
<script>
  document.addEventListener('click', (e) => {
    console.log('Click detected at:', e.target)
    console.log('Pointer events:', getComputedStyle(e.target).pointerEvents)
  }, true)
</script>
```

### 检查 CSS 是否禁用了指针事件

确保没有 CSS 规则设置 `pointer-events: none`：

```css
/* ❌ 这会阻挡所有点击
#app { pointer-events: none; }

/* ✅ 这样是对的
#app { pointer-events: auto; }
```

---

## 快速检查清单

- [ ] Android 接口已通过 `addJavascriptInterface()` 注册
- [ ] WebView 的 `javaScriptEnabled = true`
- [ ] HTML 中 `#app` 容器存在且可见
- [ ] 无 CSS 规则设置 `pointer-events: none`
- [ ] 点击速度快速轻点（<300ms）
- [ ] 日志中出现 `[AMLL-INIT]` 信息表示初始化成功
- [ ] 测试环境运行的是最新编译的 `amll.bundle.js`

---

## 日志关键字速查

| 日志 | 含义 | 优先级 |
|------|------|--------|
| `[AMLL-INIT]` Core LyricPlayer created | 播放器初始化成功 | ⚠️ 必须有 |
| `[AMLL-INIT]` Android.onLineClick interface is ready | Android 接口已就绪 | ⚠️ 必须有 |
| `[AMLL-TAP]` Tap detected | 检测到点击 | ℹ️ 信息 |
| `[AMLL-TAP]` Clicked element | 找到点击的元素 | ℹ️ 信息 |
| `[AMLL-TAP]` Found lyric line element | 确认点击到歌词行 | ℹ️ 信息 |
| `[AMLL-CLICK]` Line X clicked | 即将调用 Android.onLineClick | ✅ 成功 |
| `[AMLL-ERROR]` line-click bridge error | 点击事件处理出错 | ❌ 错误 |
| `[AMLL-INIT] WARNING: Android.onLineClick interface NOT found | Android 接口未注册 | ❌ 错误 |

---

## 故障排除流程图

```
用户点击歌词
    ↓
[检查] 是否出现 "[AMLL-TAP] Tap detected"?
    ├─ NO  → 触摸事件未到达JS层
    │       → 检查 HTML/CSS 的指针事件设置
    │
    └─ YES (出现该日志)
        ↓
[检查] 是否出现 "[AMLL-TAP] Clicked element"?
    ├─ NO  → elementFromPoint() 返回 null
    │       → 检查 Z-index 和元素可见性
    │
    └─ YES
        ↓
[检查] 是否出现 "[AMLL-TAP] Found lyric line element"?
    ├─ NO  → closest() 未找到歌词行元素
    │       → 更新类名选择器以匹配实际 DOM
    │
    └─ YES
        ↓
[检查] 是否出现 "[AMLL-CLICK] Line X clicked"?
    ├─ NO  → click 事件未触发 LyricPlayer 监听器
    │       → 可能需要调整事件分发方式
    │
    └─ YES
        ↓
[检查] 播放器是否跳转到相应时间?
    ├─ NO  → Android.onLineClick 未实现或出错
    │       → 检查 Kotlin 代码中的接口实现
    │
    └─ YES ✅ 功能正常工作!
```

---

**更新日期**：2026-03-08  
**版本**：2.0（改进的双层点击机制）
