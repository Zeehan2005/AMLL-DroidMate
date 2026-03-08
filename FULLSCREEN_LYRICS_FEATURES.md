# 全屏歌词互动功能说明

## 📋 功能概述

本次更新为 AMLL（Apple Music-Like Lyrics）全屏歌词播放器添加了三项核心交互功能：

### 1. **点击跳转** (Click-to-Seek)
- **功能**：用户点击任意歌词行，播放器自动跳转到该行开始时间
- **实现机制**：
  - LyricPlayer 组件已原生支持 `line-click` 事件
  - 点击事件通过 WebView bridge 调用 `Android.onLineClick(lineIndex, startTime)`
  - Android 原生层接收事件并执行音乐跳转

### 2. **触摸取消模糊** (Touch-to-Unblur)
- **功能**：用户触摸屏幕时，非当前行歌词的模糊效果立即消除，便于浏览和阅读
- **实现方式**：
  - 检测 `touchstart` 事件，调用 `setEnableBlur(false)` 禁用模糊
  - 歌词行变得清晰可读，用户可自由浏览所有文本
  
### 3. **自动恢复模糊** (Auto-Reblur after 5s)
- **功能**：用户停止触摸后，5秒内无操作则自动恢复模糊效果
- **实现方式**：
  - 触摸开始/移动时重置 5 秒计时器
  - 计时器到期后自动调用 `setEnableBlur(true)` 恢复模糊
  - 继续触摸会重新开始计时

---

## 🔧 实现细节

### 修改的文件

#### 1. `frontend/src/main.js` - 主业务逻辑

**状态管理**：
```javascript
const state = {
  lyricLines: [],
  currentTime: 0,
  isSeeking: false,
  blur: {
    enabled: true,
    timeoutId: null,
    TIMEOUT_MS: 5000, // 5秒超时
  },
}
```

**触摸处理函数**：
```javascript
function handleTouchStart() {
  // 触摸时禁用模糊
  if (player && state.blur.enabled === true) {
    callPlayer('setEnableBlur', false)
    state.blur.enabled = false
  }
  resetBlurTimeout() // 重置计时器
}

function handleTouchEnd() {
  resetBlurTimeout() // 继续计时，等待恢复
}

function handleTouchMove() {
  resetBlurTimeout() // 滑动时延迟恢复
}

function resetBlurTimeout() {
  // 清除旧计时器，设置新的 5 秒计时
  if (state.blur.timeoutId !== null) {
    clearTimeout(state.blur.timeoutId)
  }
  
  state.blur.timeoutId = setTimeout(() => {
    if (player && state.blur.enabled === false) {
      callPlayer('setEnableBlur', true)
      state.blur.enabled = true
      logToAndroid('[AMLL-BLUR] Blur restored after 5s inactivity')
    }
    state.blur.timeoutId = null
  }, state.blur.TIMEOUT_MS)
}
```

**事件监听注册**（在 `mountPlayer()` 中）：
```javascript
app.addEventListener('touchstart', handleTouchStart, false)
app.addEventListener('touchmove', handleTouchMove, false)
app.addEventListener('touchend', handleTouchEnd, false)
```

**公开 API 接口**：
```javascript
// 手动控制模糊状态
window.setBlurEnabled = function (enabled) { ... }

// 自定义超时时间（毫秒）
window.setBlurTimeout = function (timeMs) { ... }
```

#### 2. `app/src/main/assets/amll/amll.bundle.js` - 编译后的产物
- 包含所有上述功能的最小化版本
- 由 Vite 构建工具自动生成

#### 3. `app/src/main/assets/amll/index.html` - HTML 容器
- `<div id="app">` 作为事件委托目标
- 所有触摸事件在此容器上捕捉

---

## 🚀 使用方式

### 基本使用（自动）
用户无需任何额外操作，功能自动启用：
1. 点击任何歌词行 → 播放器跳转到该行
2. 触摸屏幕 → 模糊消除，清晰显示全部歌词
3. 停止触摸 5 秒 → 模糊自动恢复

### 高级 API（OpenAPI）

#### `window.setBlurEnabled(enabled: boolean)`
手动控制模糊状态：
```javascript
// 禁用模糊
window.setBlurEnabled(false)

// 启用模糊
window.setBlurEnabled(true)
```

#### `window.setBlurTimeout(timeMs: number)`
自定义无操作多久后恢复模糊：
```javascript
// 修改为 3 秒
window.setBlurTimeout(3000)

// 修改为 10 秒
window.setBlurTimeout(10000)
```

#### Android 原生调用（Java/Kotlin）
```kotlin
// 禁用模糊
webView.evaluateJavascript("window.setBlurEnabled(false)") { _ -> }

// 启用模糊
webView.evaluateJavascript("window.setBlurEnabled(true)") { _ -> }

// 设置 8 秒超时
webView.evaluateJavascript("window.setBlurTimeout(8000)") { _ -> }
```

---

## 📝 技术架构流程图

```
用户触摸
    ↓
touchstart 事件触发
    ↓
handleTouchStart() 执行
    ├─ setEnableBlur(false) → 禁用模糊
    └─ resetBlurTimeout() → 设置 5 秒计时器
         
用户继续触摸（touchmove）
    ↓
handleTouchMove() 执行
    └─ resetBlurTimeout() → 重新设置计时器（延迟恢复）

用户停止触摸（touchend）
    ↓
handleTouchEnd() 执行
    └─ resetBlurTimeout() → 最后一次重置计时器

  [等待 5 秒内无新事件]
           ↓
计时器到期 (timeout 回调)
    ├─ setEnableBlur(true) → 恢复模糊
    └─ 清除计时器 ID

[若继续触摸，重复上述流程]
```

---

## 🛠️ 构建与部署

### 重新构建前端资源
在项目的 `frontend/` 目录执行：
```bash
npm.cmd run build:android
```

此命令会：
1. 编译 TypeScript/JavaScript 代码
2. 生成最小化版本的 `amll.bundle.js` 和 `frontend.css`
3. 自动复制到 `app/src/main/assets/amll/` 安卓资源目录

### 验证部署
检查以下文件是否已更新：
- `app/src/main/assets/amll/amll.bundle.js`（新增 `handleTouchStart`, `setBlurEnabled` 等）
- `app/src/main/assets/amll/frontend.css`（样式表）

---

## 🔍 日志调试

启用 Android 日志来追踪功能运行：

```kotlin
// 在 Android 应用的 WebView 配置中
webView.setWebChromeClient(object: WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        Log.d("AMLL", consoleMessage?.message() ?: "")
        return true
    }
})
```

查看日志信息：
- `[AMLL-BLUR] Blur disabled on touch` - 用户触摸，模糊已禁用
- `[AMLL-BLUR] Blur restored after 5s inactivity` - 5 秒无操作，模糊已恢复
- `[AMLL-CLICK] Line X clicked, seeking to Yms` - 用户点击了第 X 行，跳转到 Y 毫秒

---

## 🐛 常见问题

### Q: 模糊没有被禁用
**A**: 确保：
1. AMLL 播放器已初始化（检查 `[AMLL-INIT]` 日志）
2. 触摸事件正确传递到 `#app` 容器
3. 使用 `setBlurEnabled()` API 手动测试

### Q: 5 秒计时不准确
**A**: 
- 继续触摸会重置计时器。确保停止所有操作 5 秒
- 可用 `setBlurTimeout()` 自定义超时时间

### Q: 点击歌词行无反应
**A**:
- 确保 `Android.onLineClick()` 方法已在 WebView 接口中注册
- 检查 `[AMLL-CLICK]` 日志是否出现
- 检查 Android 原生代码是否正确处理点击事件回调

---

## 📚 相关代码引用

- **源代码**：[frontend/src/main.js](../frontend/src/main.js)
- **编译配置**：[frontend/vite.config.js](../frontend/vite.config.js)
- **HTML 容器**：[app/src/main/assets/amll/index.html](../app/src/main/assets/amll/index.html)

---

**更新时间**：2026-03-08  
**作者**：GitHub Copilot  
**版本**：1.0.0
