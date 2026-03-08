# DroidMate 项目文件清单

## 📁 项目结构总览

```
DroidMate/
├── 📄 README.md                          # 项目说明文档
├── 📄 DEVELOPMENT.md                     # 开发指南
├── 📄 INTEGRATION_GUIDE.md               # 集成指南
├── 📄 .gitignore                         # Git 忽略文件
├── 📄 gradle.properties                  # Gradle 配置
├── 📄 build.gradle.kts                   # 根级 Gradle 构建脚本
├── 📄 settings.gradle.kts                # Gradle 设置
│
└── 📦 app/                               # Android 应用模块
    ├── 📄 build.gradle.kts               # 应用级 Gradle 构建脚本
    ├── 📄 lint.xml                       # Android Lint 配置
    ├── 📄 proguard-rules.pro             # ProGuard 混淆规则
    │
    ├── src/
    │   ├── main/
    │   │   ├── 📄 AndroidManifest.xml    # Android 清单文件
    │   │   │
    │   │   ├── java/com/amll/droidmate/
    │   │   │   ├── 📄 MainActivity.kt     # 应用主入口
    │   │   │   │
    │   │   │   ├── domain/              # 领域层（业务实体）
    │   │   │   │   └── model/
    │   │   │   │       └── 📄 Lyrics.kt  # 歌词数据模型
    │   │   │   │
    │   │   │   ├── data/                # 数据层
    │   │   │   │   ├── repository/
    │   │   │   │   │   └── 📄 LyricsRepository.kt  # 歌词数据源管理
    │   │   │   │   └── converter/
    │   │   │   │       └── 📄 TTMLConverter.kt     # TTML 格式转换
    │   │   │   │
    │   │   │   ├── service/             # 服务层
    │   │   │   │   └── 📄 MediaInfoService.kt      # 媒体信息监听
    │   │   │   │
    │   │   │   └── ui/                  # UI 层
    │   │   │       ├── screens/
    │   │   │       │   └── 📄 MainScreen.kt        # 主屏幕 UI
    │   │   │       ├── theme/
    │   │   │       │   └── 📄 Theme.kt  # 主题配置
    │   │   │       └── viewmodel/
    │   │   │           └── 📄 MainViewModel.kt     # 状态管理
    │   │   │
    │   │   └── res/                     # 资源文件
    │   │       ├── values/
    │   │       │   ├── 📄 strings.xml    # 字符串资源
    │   │       │   └── 📄 styles.xml     # 样式定义
    │   │       └── xml/
    │   │           ├── 📄 data_extraction_rules.xml
    │   │           └── 📄 backup_rules.xml
    │   │
    │   ├── test/                         # 单元测试
    │   │   └── java/com/amll/droidmate/
    │   │       └── data/converter/
    │   │           └── 📄 TTMLConverterTest.kt
    │   │
    │   └── androidTest/                  # Android 集成测试
    │       └── java/com/amll/droidmate/
    │           └── 📄 MediaInfoServiceTest.kt
    │
    └── 📁 .gradle/                       # Gradle 缓存（自动生成）
```

## 📋 文件详细清单

### 核心配置文件

| 文件 | 说明 | 关键内容 |
|------|------|--------|
| `build.gradle.kts` | 项目构建脚本 | 定义构建依赖和插件 |
| `settings.gradle.kts` | 项目设置 | 模块配置和仓库定义 |
| `gradle.properties` | Gradle 属性 | JVM 参数和特性开关 |
| `.gitignore` | Git 忽略 | 排除生成和临时文件 |

### Android 配置

| 文件 | 说明 | 位置 |
|------|------|------|
| `AndroidManifest.xml` | 应用清单 | `app/src/main/` |
| `app/build.gradle.kts` | 应用构建脚本 | `app/` |
| `app/proguard-rules.pro` | 混淆规则 | `app/` |
| `app/lint.xml` | Lint 配置 | `app/` |

### Kotlin 源代码文件

#### 应用入口
- **MainActivity.kt** - 应用主活动，Compose UI 初始化

#### 领域层 (Domain)
- **Lyrics.kt** - 数据模型定义
  - `NowPlayingMusic` - 当前播放信息
  - `LyricLine` - 单行歌词
  - `TTMLLyrics` - 完整歌词对象
  - `TTMLMetadata` - 歌词元数据
  - `LyricsSearchResult` - 搜索结果
  - `LyricsResult` - 获取结果

#### 数据层 (Data)
- **LyricsRepository.kt** - 歌词数据管理
  - QQ 音乐搜索
  - 网易云音乐搜索
  - AMLL TTML DB 获取
  - TTML 解析
  - 多源综合搜索

- **TTMLConverter.kt** - 格式转换器
  - TTML 字符串生成
  - LRC 到 TTML 转换
  - 时间格式化
  - XML 转义处理

#### 服务层 (Service)
- **MediaInfoService.kt** - 媒体监听服务
  - 获取当前播放歌曲信息
  - 实时监听播放进度
  - MediaSessionManager 集成

#### UI 层 (UI)
- **MainScreen.kt** - 主屏幕 Compose
  - 播放信息卡片
  - 歌词显示区域
  - 控制按钮
  - 时间格式化工具

- **Theme.kt** - 主题配置
  - 深色/浅色配色方案
  - Material 3 设计

- **MainViewModel.kt** - 视图模型
  - 状态管理
  - 业务逻辑
  - 歌词获取和转换
  - 导出功能

### 资源文件

| 文件 | 类型 | 内容 |
|------|------|------|
| `strings.xml` | 字符串 | UI 标签和消息 |
| `styles.xml` | 样式 | 应用主题 |
| `data_extraction_rules.xml` | XML | 网络配置 |
| `backup_rules.xml` | XML | 备份规则 |

### 测试文件

| 文件 | 类型 | 位置 | 用途 |
|------|------|------|------|
| `TTMLConverterTest.kt` | 单元测试 | `src/test/` | 测试 TTML 转换功能 |
| `MediaInfoServiceTest.kt` | 集成测试 | `src/androidTest/` | 测试媒体服务 |

### 文档文件

| 文件 | 说明 |
|------|------|
| `README.md` | 项目总览和快速开始 |
| `INTEGRATION_GUIDE.md` | Unilyric 和 AMLL TTML DB 集成指南 |
| `DEVELOPMENT.md` | 开发指南和最佳实践 |

## 🔧 关键类和接口

### 数据模型

```kotlin
// 当前播放信息
data class NowPlayingMusic(
    val title: String,
    val artist: String,
    val album: String?,
    val duration: Long,
    val currentPosition: Long,
    val isPlaying: Boolean
)

// 单行歌词
data class LyricLine(
    val startTime: Long,
    val endTime: Long,
    val text: String,
    val translation: String?,
    val transliteration: String?
)

// 完整歌词
data class TTMLLyrics(
    val metadata: TTMLMetadata,
    val lines: List<LyricLine>
)
```

### 主要服务

```kotlin
// 媒体信息服务
class MediaInfoService {
    fun startListening()
    fun stopListening()
    val nowPlayingMusic: StateFlow<NowPlayingMusic?>
}

// 歌词仓库
class LyricsRepository {
    suspend fun searchLyrics(title: String, artist: String): List<LyricsSearchResult>
    suspend fun getLyrics(provider: String, songId: String): LyricsResult
    suspend fun getAMLL_TTMLLyrics(songId: String): TTMLLyrics?
}

// TTML 转换器
object TTMLConverter {
    fun toTTMLString(lyrics: TTMLLyrics): String
    fun fromLRC(lrcContent: String): TTMLLyrics?
    fun formatTime(millis: Long): String
}

// 主视图模型
class MainViewModel {
    fun fetchLyrics()
    fun convertLRCToTTML(content: String, title: String, artist: String)
    fun exportLyricsAsTTML(fileName: String): String?
}
```

## 📦 依赖管理

### 主要依赖

```kotlin
// Android X
androidx.core:core-ktx:1.12.0
androidx.lifecycle:lifecycle-runtime-ktx:2.6.2
androidx.media:media:1.7.0

// Compose
androidx.compose.ui:ui:1.6.0
androidx.compose.material3:material3:1.1.2

// 网络
io.ktor:ktor-client-okhttp:2.3.6

// 序列化
org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.1

// 协程
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3
```

## 🎯 功能映射

| 功能 | 实现位置 | 相关文件 |
|------|--------|--------|
| 获取当前播放信息 | `MediaInfoService` | `service/MediaInfoService.kt` |
| 搜索歌词 | `LyricsRepository.searchLyrics()` | `data/repository/LyricsRepository.kt` |
| 获取歌词内容 | `LyricsRepository.getLyrics()` | `data/repository/LyricsRepository.kt` |
| LRC 转 TTML | `TTMLConverter.fromLRC()` | `data/converter/TTMLConverter.kt` |
| TTML 生成 | `TTMLConverter.toTTMLString()` | `data/converter/TTMLConverter.kt` |
| UI 显示 | `MainScreen` Composable | `ui/screens/MainScreen.kt` |
| 状态管理 | `MainViewModel` | `ui/viewmodel/MainViewModel.kt` |
| 主题设置 | `DroidMateTheme` | `ui/theme/Theme.kt` |

## 📊 代码统计

- **Kotlin 源文件**: 9 个
- **资源文件**: 4 个
- **配置文件**: 7 个
- **文档文件**: 4 个
- **测试文件**: 2 个
- **总计**: ~2500+ 行代码和文档

## 🚀 快速启动步骤

1. **安装依赖**
   ```bash
   cd DroidMate
   ./gradlew build
   ```

2. **运行应用**
   ```bash
   ./gradlew installDebug
   ```

3. **运行测试**
   ```bash
   ./gradlew test connectedAndroidTest
   ```

4. **查看文档**
   - 快速开始: 见 `README.md`
   - 集成指南: 见 `INTEGRATION_GUIDE.md`
   - 开发指南: 见 `DEVELOPMENT.md`

## ✅ 完成清单

- [x] 项目结构创建
- [x] 核心功能实现
- [x] UI 界面完成
- [x] 数据模型定义
- [x] 服务层实现
- [x] 类型安全代码
- [x] 错误处理
- [x] 单元测试
- [x] 集成测试
- [x] 完整文档
- [x] 开发指南
- [x] 集成指南

##  项目原则

✅ **保留原有结构** - 项目原本就很完善，最小化修改  
✅ **模块化设计** - 各层职责清晰  
✅ **完整文档** - 易于集成和扩展  
✅ **可测试性** - 包含单元和集成测试  
✅ **Kotlin 最佳实践** - 使用协程和类型安全  
✅ **Compose 现代 UI** - 响应式界面设计
