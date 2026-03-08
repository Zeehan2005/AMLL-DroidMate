> [!WARNING]
> **v1.0 服务提示**
>
> 受限于技术原因，自选歌词可能有异常。建议优先使用 AMLL TTML DB，或使用 [Unilyric](https://github.com/apoint123/Unilyric) 预处理后导入。

# AMLL DroidMate

Android 端外置歌词显示器，集成 [AMLL](https://github.com/amll-dev/applemusic-like-lyrics) 风格渲染与多源歌词检索能力。

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
