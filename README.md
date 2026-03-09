
# AMLL DroidMate

## 更新点

- 手动/自定义歌词编辑及候选资源搜索，支持本地缓存和远程来源。
- 优化了歌词搜索流程，提升响应速度。
- 优化常驻通知，暂停时可手动清除
- 实现读取本地歌词缓存
- 歌词搜索逻辑修改，提高稳定性
- 状态栏沉浸支持
- 全屏歌词沉浸并加入按钮交互
- 字体设置独立页面
- 应用主题颜色随专辑封面变化
- 支持自定义字体设置
- 字号和字重可配置
- 进出全屏时保持位置不重置
- 间奏动画的行间隔优化（#5）
- 背景歌词模糊触摸保持模糊（issue #6）
- 可选辅助功能：首次按上一首回到0:00


傻瓜式 Android 端外置歌词显示器，集成 [AMLL](https://github.com/amll-dev/applemusic-like-lyrics) 风格渲染与多源歌词检索能力。

![Image](https://github.com/user-attachments/assets/266ed199-3763-4567-bfc3-3aa6fcd602f7)
![Image](https://github.com/user-attachments/assets/7d7a3ab4-7a95-4cf9-bceb-f54aef6a4314)
![Image](https://github.com/user-attachments/assets/59faa67c-92b5-477e-8685-272319945189)

演示视频: https://github.com/user-attachments/assets/0eadfa92-dc6f-4e66-b709-1d919478a41c

## 核心特性

- 在你爱用的流媒体上使用AMLL，而无需提前下载解密音频
- 自动识别当前播放歌曲（MediaSessionManager）
- 多源歌词检索（网易云、QQ、酷狗、AMLL TTML DB 优先策略）
- TTML/LRC 等格式处理与转换
- Apple Music 风格歌词显示（AMLL WebView）
- 逐字高亮、翻译/音译、点击跳转（Click-to-seek）
- 实时歌词通知：可选常驻通知实时显示当前句歌词（支持锁屏显示）
- **手动/自定义歌词功能**：搜索候选、自行输入并应用歌词，便于修正或补充。

## 更新点

- 优化常驻通知，暂停时可手动清除
- 实现读取本地歌词缓存
- 歌词搜索逻辑修改，提高稳定性
- 状态栏沉浸支持
- 全屏歌词沉浸并加入按钮交互
- 字体设置独立页面
- 应用主题颜色随专辑封面变化
- 支持自定义字体设置
- 字号和字重可配置
- 进出全屏时保持位置不重置
- 间奏动画的行间隔优化（#5）
- 背景歌词模糊触摸保持模糊（issue #6）
- 可选辅助功能：首次按上一首回到0:00



## 技术栈

- Android API 26+
- Kotlin 1.9.23
- Android Gradle Plugin 8.4.0
- Gradle 8.7
- Jetpack Compose + WebView
- Ktor + Kotlinx Serialization + Coroutines

## 快速开始（Windows）

### 1) 环境要求

- Android Studio（推荐最新稳定版）
- Android SDK 34（`compileSdk/targetSdk`）
- JDK: 推荐使用 Android Studio 内置 JBR

建议在 PowerShell 中设置:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

### 2) 构建项目

```powershell
cd "c:\Users\Zeehan\Documents\VSCode\DroidMate"
.\gradlew.bat clean build
```

### 3) 安装 Debug 包

```powershell
.\gradlew.bat installDebug
```

也可以直接在 Android Studio 里运行 `app` 模块。

## 前端（AMLL Web 资源）构建

项目包含 `frontend/`（Vite + React）用于生成 `WebView` 资源。

```powershell
cd "c:\Users\Zeehan\Documents\VSCode\DroidMate\frontend"
npm.cmd install
npm.cmd run build:android
```

`build:android` 会在打包后自动复制产物到 `app/src/main/assets/amll/`。

## 主要模块

- `MediaInfoService`: 监听系统媒体会话，获取歌曲信息与进度
- `LyricsRepository`: 多源歌词搜索与回退策略
- `TTMLConverter`: LRC/VRC 到 TTML 的转换和解析
- `MainViewModel`: 统一状态管理与业务编排
- `ServiceLocator`: 中央工厂/依赖定位器，提供 HTTP 客户端和仓库实例
- `PreferenceHelper`: 轻量 `SharedPreferences` 封装，降低重复访问的样板代码
- `AMLL WebView UI`: Apple Music 风格歌词渲染与交互

## 权限说明

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

另外需要在系统中授予“通知访问权限”（Notification Listener），用于获取正在播放媒体信息。

## 实时歌词通知使用

1. 打开应用设置页，启用`实时歌词通知`。
2. Android 13+ 允许通知权限（`POST_NOTIFICATIONS`）。
3. 在系统设置中授予本应用通知访问权限。
4. 播放歌曲后，通知栏会实时更新当前歌词行。
   暂停播放时通知仍保留但会变成可清理的普通通知，可以手动滑动或点击“清除全部”。

## 相关文档

- `QUICK_START.md`
- `DEVELOPMENT.md`
- `INTEGRATION_GUIDE.md`
- `UNILYRIC_INTEGRATION.md`
- `ANDROID_WEBVIEW_SETUP.md`

## 相关项目

- [apoint123/Unilyric](https://github.com/apoint123/Unilyric)
- [amll-dev/amll-ttml-db](https://github.com/amll-dev/amll-ttml-db)
- [amll-dev/Apple Music-like Lyrics](https://github.com/amll-dev/applemusic-like-lyrics)


## 许可证

MIT
