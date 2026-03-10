# 点击跳转功能 - 快速参考

## 📋 本次更新（2026-03-08）

你现在拥有**改进的双层点击机制**：

### ✨ 新增功能

| 功能 | 描述 |
|------|------|
| **LyricPlayer 事件监听** | 直接监听 `line-click` 事件（第一层） |
| **触摸点击检测** | 自动检测快速轻点（<300ms）并模拟点击（第二层备用） |
| **详细日志调试** | 输出 20+ 条诊断日志帮助排查问题 |
| **Android 接口验证** | 启动时检查 JavaScript 接口是否就绪 |

---

## 🚀 快速开始

### 1. 更新你的 Android 代码

复制并使用本项目的 `AMLLJavaScriptInterface` 和 WebView 配置代码：

```kotlin
// 核心三行代码
webView.settings.javaScriptEnabled = true
val jsInterface = AMLLJavaScriptInterface(musicPlayer)
webView.addJavascriptInterface(jsInterface, "Android")
```

参考文档：[ANDROID_WEBVIEW_SETUP.md](ANDROID_WEBVIEW_SETUP.md)

### 2. 确保使用最新的 amll.bundle.js

最新编译版本包含改进的点击机制：

```bash
# 已自动部署到：
app/src/main/assets/amll/amll.bundle.js
```

### 3. 构建、部署、测试

```bash
# Android Studio / gradle
./gradlew build

# 启动应用并轻轻点击歌词
# 检查 Logcat 中的 AMLL 日志
```

---

## 🔍 日志追踪

### 成功流程

点击歌词行后，应该看到这些日志（按顺序）：

```
✅ [AMLL-INIT] Android.onLineClick interface is ready
✅ [AMLL-TAP] Tap detected at coordinates (X, Y), duration=120ms
✅ [AMLL-TAP] Clicked element: DIV, class=...lyricLine...
✅ [AMLL-TAP] Found lyric line element
✅ [AMLL-TAP] Dispatched click event
✅ [AMLL-CLICK] Line 2 clicked, seeking to 5000ms
```

### 失败调查

根据日志缺失位置判断问题：

| 缺少的日志 | 问题所在 | 检查项 |
|----------|--------|--------|
| 没有任何 AMLL 日志 | WebView 未加载 HTML | 检查 `loadUrl()` 调用 |
| 缺少 `AMLL-TAP` | 触摸未到达 JavaScript | 检查 HTML/CSS 指针事件设置 |
| 有 `AMLL-TAP` 但缺少 `Found lyric line` | 点击位置不对或 DOM 结构不符 | 重新构建，或调整类名选择器 |
| 有完整日志但无 `AMLL-CLICK` | click 事件未被 LyricPlayer 捕获 | 检查 LyricPlayer 的事件绑定 |
| 完整日志但播放器不跳转 | Android 接口未正确实现 | 参考 `AMLLJavaScriptInterface` |

---

## 🛠️ 故障排查步骤

### Step 1: 验证基础設置

```kotlin
// 在 WebView 配置中添加这段代码
webView.webViewClient = object : WebViewClient() {
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        // 测试接口是否可用
        view?.evaluateJavascript("typeof Android") { result ->
            Log.d("AMLL", "Android interface available: $result")
        }
    }
}
```

### Step 2: 查看 Logcat

```bash
# 终端运行
adb logcat | grep AMLL

# 或在 Android Studio 的 Logcat 窗口中搜索 "AMLL"
```

### Step 3: 手动触发测试

```kotlin
// 在 Activity 中调用此方法
fun testClickManually() {
    webView.evaluateJavascript("window.Android.onLineClick(0, 3000)") { result ->
        Log.d("AMLL", "Manual test result: $result")
    }
}
```

如果手动测试成功跳转了，说明问题在 JavaScript 或 HTML 层；如果手动测试也不工作，问题在 Android 接口。

---

## 📚 详细文档

| 文档 | 用途 |
|------|------|
| [CLICK_SEEK_DEBUG_GUIDE.md](CLICK_SEEK_DEBUG_GUIDE.md) | 完整的调试指南和故障排除流程图 |
| [ANDROID_WEBVIEW_SETUP.md](ANDROID_WEBVIEW_SETUP.md) | Android Kotlin 完整配置代码示例 |
| [FULLSCREEN_LYRICS_FEATURES.md](FULLSCREEN_LYRICS_FEATURES.md) | 全屏歌词功能概述（点击、模糊、自动恢复） |

---

## 💡 关键要点

1. **JavaScript 接口是关键**
   - 必须通过 `addJavascriptInterface()` 注册
   - 对象名必须是 `"Android"` 与 JavaScript 中的 `Android` 匹配
   - 方法必须用 `@JavascriptInterface` 标记

2. **点击检测的三层保障**
   - 第一层：LyricPlayer 监听 `line-click` 事件
   - 第二层：自动检测触摸点击（快速轻点）
   - 第三层：手动通过 `window.Android.onLineClick()` 调用

3. **日志是你的朋友**
   - 所有关键步骤都有日志输出
   - 比对日志序列快速定位问题
   - 使用 `adb logcat` 或 Android Studio Logcat

4. **TestDebug 任何地方**
   ```kotlin
   // 用这行代码快速测试 Android 接口
   webView.evaluateJavascript("window.Android.log('test')") { _ -> }
   ```

---

## ✅ 最后检查清单

在部署到真实设备前：

- [ ] Android 代码中 `javaScriptEnabled = true`
- [ ] `addJavascriptInterface(jsInterface, "Android")` 已调用
- [ ] `onLineClick(lineIndex, startTimeMs)` 方法已在 Android 中实现
- [ ] 升级到最新的 `amll.bundle.js` (2026-03-08 版本)
- [ ] `app/src/main/assets/amll/` 目录存在且包含 `index.html`
- [ ] 测试设备已启用开发者选项
- [ ] 有 USB 调试权限或已有 Logcat 输出

---

## 🆘 还是不行？

如果点击仍然不工作：

1. **收集诊断信息**
   - 完整的 Logcat 输出
   - 当前运行的 `amll.bundle.js` 文件大小（应 > 390KB）
   - Android 版本和 WebView 版本

2. **检查这些常见错误**
   - [ ] `WebView.setWebContentsDebuggingEnabled()` 在 release build 中被禁用
   - [ ] 其他 JavaScript 代码有语法错误，导致整个脚本失败
   - [ ] WebView 在单独的进程中运行，导致接口访问失败

3. **参考示例代码**
   - 本项目提供的 `AMLLJavaScriptInterface` 是完整可用的
   - 直接复制 `onLineClick()` 方法实现

---

**版本**：2.0 - 双层点击机制  
**发布日期**：2026-03-08  
**兼容性**：Android 4.4+  
**最后编译**：✓ amll.bundle.js 已更新
