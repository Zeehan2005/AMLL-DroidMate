# AMLL DroidMate - Android Lyrics Application

一个功能强大的安卓应用，专为音乐爱好者设计，可以获取当前播放歌曲的信息和歌词，并以 TTML 格式显示，支持 Apple Music 风格的歌词展示。
## ✨ 核心特性

### 🎵 智能多源歌词搜索 (基于 Unilyric 策略)
- **自动搜索**: 从网易云音乐、QQ音乐、酷狗音乐自动搜索歌词
- **优先级策略**: 优先使用 AMLL TTML DB 的高质量逐字歌词
- **智能匹配**: 自动计算置信度,选择最佳匹配结果
- **多重回退**: 当主要来源失败时,自动尝试备用来源

### 🎨 Apple Music 风格 UI (AMLL 集成)
- **AMLL WebView**: 集成 AMLL Core 组件实现 iPad Apple Music 风格歌词显示
- **逐字同步**: 支持 TTML 格式的精确逐字歌词高亮
- **流畅动画**: 平滑的滚动、渐变、缩放效果
- **动态背景**: 可选的流体背景和频谱可视化

### 📱 媒体信息获取
- 实时监听当前播放的歌曲信息 (通过 MediaSessionManager)
- 支持所有主流音乐播放器 (需要授权通知访问权限)
- 显示歌曲名、歌手、专辑、播放进度等详细信息

### 🔄 格式转换
- LRC ↔ TTML 双向转换
- 支持导出为标准 TTML 文件
- 自动解析多种时间格式
## 功能特性

- 🎵 **自动识别当前播放歌曲** - 通过 MediaSession API 实时获取当前播放的歌曲信息
- 📝 **智能歌词获取** - 集成多个歌词源（QQ音乐、网易云音乐、AMLL TTML DB）
- 🔄 **TTML 转换** - 支持将 LRC、VRC 等格式转换为 TTML 格式
- 🎨 **Apple Music 风格显示** - 使用 TTML 标准显示歌词，支持翻译和音译
- ⚡ **实时切换** - 自动检测歌曲切换，动态更新歌词
- 💾 **离线支持** - 支持导出和保存歌词文件

> [!WARNING]  
> 由于调教者对该语言掌握能力有限，最初版本所有代码均为 Github Copilot 编写。AI可能会出错。

## 技术栈

### 框架和库

- **Android API**: API 26+
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Networking**: Ktor Client
- **Serialization**: Kotlinx Serialization
- **Async**: Kotlin Coroutines
- **Logging**: Timber

### 项目结构

```
DroidMate/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/amll/droidmate/
│   │       │   ├── domain/
│   │       │   │   └── model/          # 数据模型
│   │       │   ├── data/
│   │       │   │   ├── repository/     # 数据访问层
│   │       │   │   └── converter/      # TTML 转换器
│   │       │   ├── service/            # 业务逻辑服务
│   │       │   ├── ui/
│   │       │   │   ├── screens/        # UI 屏幕
│   │       │   │   ├── theme/          # 主题设置
│   │       │   │   └── viewmodel/      # ViewModel
│   │       │   └── MainActivity.kt     # 主入口
│   │       └── res/                    # 资源文件
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## 核心模块说明

### 1. MediaInfoService
获取当前播放的歌曲信息，通过 MediaSessionManager 监听系统媒体会话。

### 2. LyricsRepository
从多个来源（QQ音乐、网易云音乐、AMLL TTML DB）搜索和获取歌词。

### 3. TTMLConverter
负责格式转换：
- LRC → TTML
- VRC → TTML
- TTML 生成和序列化

### 4. MainViewModel
使用 MVVM 架构管理应用状态和业务逻辑。

### 5. UI Screens
Jetpack Compose 实现的响应式 UI，包括：
- 主屏幕（歌词显示）
- 播放信息卡片
- 歌词列表
- 控制按钮

## TTML 格式支持

本应用完全支持 AMLL TTML 规范。

## 权限要求

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.MEDIA_CONTENT_CONTROL" />
```

## 构建和运行

### 前置条件
- Android SDK 26+
- Gradle 8.0+
- Kotlin 1.9.20+

### 构建
```bash
cd DroidMate
./gradlew build
```

### 运行
```bash
./gradlew installDebug
```

或者在 Android Studio 中直接运行。

## 歌词来源

DroidMate 集成了 [Unilyric](https://github.com/apoint123/Unilyric) 的多源搜索策略,支持以下来源:

### 主要来源

1. **AMLL TTML DB** (最高优先级)
   - 高质量逐字歌词,支持翻译和音译
   - 4个镜像站点确保可用性
   - 详见: [amll-dev/amll-ttml-db](https://github.com/amll-dev/amll-ttml-db)

2. **网易云音乐** (优先级: 高)
   - 歌曲库最全,与 AMLL TTML DB 直接关联
   - LRC格式自动转换为TTML

3. **QQ音乐** (优先级: 高)
   - 歌词质量高,覆盖面广
   - Base64编码自动解码

4. **酷狗音乐** (优先级: 中)
   - 补充来源,部分独家歌词

### 智能搜索流程

```
1. 并行搜索所有平台 → 计算匹配置信度
2. 如果找到网易云结果:
   ├─ 优先尝试 AMLL TTML DB (逐字高质量歌词)
   └─ 成功则返回,失败则继续
3. 按置信度依次尝试各平台直接API
4. 返回第一个成功的结果
```

详细API文档: [UNILYRIC_INTEGRATION.md](UNILYRIC_INTEGRATION.md)

## 与 Unilyric 集成

本项目完整实现了 [apoint123/Unilyric](https://github.com/apoint123/Unilyric) 的多源搜索策略:

- ✅ 支持QQ音乐、网易云、酷狗音乐搜索
- ✅ 智能匹配算法 (置信度计算)
- ✅ 优先使用 AMLL TTML DB 高质量歌词
- ✅ 多重回退机制
- ✅ LRC自动转换为TTML格式
- ✅ 完整的错误处理

## 与 AMLL TTML DB 集成

支持 [amll-dev/amll-ttml-db](https://github.com/amll-dev/amll-ttml-db) 的标准：

- TTML 格式完全兼容
- 支持 Apple Music 样式翻译
- 支持逐词和逐行歌词
- 支持背景人声和对唱

## 许可证

MIT License - 详见 LICENSE 文件

## 贡献

欢迎提交 Issues 和 Pull Requests！

## 相关项目

- [apoint123/Unilyric](https://github.com/apoint123/Unilyric) - 通用歌词辅助工具
- [amll-dev/amll-ttml-db](https://github.com/amll-dev/amll-ttml-db) - AMLL TTML 歌词数据库
- [amll-dev/Apple Music-like Lyrics](https://github.com/amll-dev/applemusic-like-lyrics) - Apple Music 风格歌词显示

## 注意事项

- 本应用仅供学习和个人使用
- 歌词数据来自各个音乐平台的开放 API
- 请遵守各平台的服务条款
- 不建议用于商业用途
