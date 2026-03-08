# DroidMate 开发指南

## 项目创建

本项目已创建，包含以下主要功能：

### 核心功能

1. **媒体监听** (`MediaInfoService`)
   - 通过 MediaSessionManager 获取当前播放歌曲信息
   - 实时监听播放进度
   - 支持多个播放器

2. **歌词获取** (`LyricsRepository`)
   - 集成 QQ 音乐、网易云音乐、AMLL TTML DB
   - 支持多提供商并发搜索
   - 完整的错误处理

3. **格式转换** (`TTMLConverter`)
   - LRC → TTML 转换
   - TTML 生成和序列化
   - 时间格式化处理

4. **用户界面** (Jetpack Compose)
   - 响应式 UI 设计
   - 实时歌词显示
   - 播放信息卡片
   - 深色/浅色主题支持

### 项目结构完整性检查

所有必需的文件已创建：

```
✅ 项目配置文件
  ├── build.gradle.kts (根)
  ├── build.gradle.kts (app)
  ├── settings.gradle.kts
  ├── gradle.properties
  └── .gitignore

✅ Android 配置
  ├── AndroidManifest.xml
  ├── app/lint.xml
  └── res/xml/
      ├── data_extraction_rules.xml
      └── backup_rules.xml

✅ 资源文件
  ├── res/values/strings.xml
  └── res/values/styles.xml

✅ Kotlin 源代码
  ├── MainActivity.kt
  ├── domain/model/Lyrics.kt
  ├── data/repository/LyricsRepository.kt
  ├── data/converter/TTMLConverter.kt
  ├── service/MediaInfoService.kt
  ├── ui/theme/Theme.kt
  ├── ui/screens/MainScreen.kt
  └── ui/viewmodel/MainViewModel.kt

✅ 测试
  ├── test/TTMLConverterTest.kt (单元测试)
  └── androidTest/MediaInfoServiceTest.kt (集成测试)

✅ 文档
  ├── README.md
  ├── INTEGRATION_GUIDE.md
  └── DEVELOPMENT.md (本文件)
```

## 开发工作流

### 1. 设置开发环境

```bash
# 克隆项目
git clone https://github.com/yourusername/DroidMate.git
cd DroidMate

# 同步 Gradle
./gradlew sync

# 构建项目
./gradlew build
```

### 2. 常用命令

```bash
# 构建调试版本
./gradlew assembleDebug

# 构建发布版本
./gradlew assembleRelease

# 运行单元测试
./gradlew test

# 运行集成测试
./gradlew connectedAndroidTest

# 生成代码覆盖率报告
./gradlew testDebugUnitTest --debug

# 清理构建
./gradlew clean
```

### 3. 代码风格

本项目遵循 Kotlin 官方风格指南：

- 使用 4 空格缩进
- 使用 camelCase 命名变量和函数
- 使用 PascalCase 命名类和接口
- 单行代码不超过 100 个字符
- 使用有意义的变量名

示例：
```kotlin
// ✅ 好
fun fetchLyricsFromProvider(providerId: String): Deferred<TTMLLyrics?> {
    return viewModelScope.async {
        lyricsRepository.getLyrics(providerId)
    }
}

// ❌ 不好
fun fetchLyrics(id: String): Deferred<TTMLLyrics?> {
    return viewModelScope.async { repo.get(id) }
}
```

## 模块说明

### Domain Layer (domain/)

**职责**：业务实体和接口定义

- `LyricLine`: 单行歌词数据类
- `TTMLLyrics`: 完整歌词对象
- `NowPlayingMusic`: 当前播放信息
- 模型与 UI、数据层解耦

### Data Layer (data/)

**职责**：数据访问和转换

- `LyricsRepository`: 歌词数据来源管理
- `TTMLConverter`: 格式转换实现
- 支持多个数据源
- 实现缓存机制

### Service Layer (service/)

**职责**：业务逻辑和系统集成

- `MediaInfoService`: 媒体信息监听
- 定时任务和后台处理
- 权限和资源管理

### UI Layer (ui/)

**职责**：用户界面的显示和交互

- `MainScreen`: 主屏幕 Compose
- `MainViewModel`: 状态管理
- `ThemeProvider`: 主题配置

## 扩展开发

### 添加新的歌词提供商

1. 在 `LyricsRepository` 中添加新方法：

```kotlin
suspend fun searchKugou(title: String, artist: String): LyricsSearchResult? {
    // 实现酷狗音乐搜索
}
```

2. 更新 `getLyrics()` 方法以支持新提供商

3. 添加单元测试

### 自定义 UI 主题

编辑 `ui/theme/Theme.kt`：

```kotlin
private val CustomColors = lightColorScheme(
    primary = Color(0xFF6366F1),
    secondary = Color(0xFF8B5CF6),
    // ... 其他颜色
)
```

### 实现本地缓存

创建 `data/database/LyricsDatabase.kt`：

```kotlin
@Database(entities = [CachedLyrics::class], version = 1)
abstract class LyricsDatabase : RoomDatabase() {
    abstract fun lyricsDao(): LyricsCacheDao
}
```

## 常见问题

### Q: 如何调试媒体会话获取问题？

A: 使用以下命令查看活动媒体会话：
```bash
adb shell dumpsys media_session
```

### Q: 如何优化性能？

A: 
- 使用 `remember` 缓存可组合项
- 实现分页加载歌词
- 使用 Dispatchers.IO 处理网络请求

### Q: 如何处理网络超时？

A: 在 `build.gradle.kts` 中配置 Ktor 超时

### Q: 支持离线模式吗？

A: 可以通过 Room 数据库缓存歌词实现离线支持

## 代码审查清单

提交 PR 前请检查：

- [ ] 代码遵循风格指南
- [ ] 添加了必要的注释
- [ ] 单元测试覆盖新代码
- [ ] 没有 hardcoded 字符串（使用 strings.xml）
- [ ] 错误处理完整
- [ ] 没有内存泄漏
- [ ] 性能测试通过
- [ ] 文档已更新

## 性能基准

目标性能指标：

| 操作 | 目标 | 备注 |
|-----|------|------|
| 搜索歌词 | < 3s | 网络依赖 |
| 加载歌词 | < 1s | 本地或缓存 |
| 切换歌曲 | < 500ms | UI 响应 |
| 更新进度 | 60fps | 滚动列表 |

## 依赖管理

### 核心依赖版本

- Kotlin: 1.9.20+
- Android Gradle Plugin: 8.2.0+
- Jetpack Compose: 1.6.0+
- Ktor Client: 2.3.6+

### 定期更新

```bash
./gradlew dependencyUpdates
```

## 监测和日志

使用 Timber 进行日志记录：

```kotlin
Timber.d("Debug message")
Timber.i("Info message")
Timber.w("Warning message")
Timber.e("Error message")
```

## 部署和发布

### 构建发布版本

```bash
# 1. 更新版本号
# 在 build.gradle.kts 中修改 versionCode 和 versionName

# 2. 进行测试
./gradlew connectedAndroidTest

# 3. 构建 APK
./gradlew bundleRelease

# 4. 签名应用
# 使用 Android Studio 或 jarsigner 工具

# 5. 上传到 Play Store 或其他平台
```

## 贡献指南

1. Fork 项目
2. 创建功能分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 许可证

MIT License - 详见项目根目录的 LICENSE 文件

## 联系方式

- Issues: GitHub Issues
- Discussions: GitHub Discussions
- Email: contact@example.com

## 相关资源

- [Android Developers](https://developer.android.com/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
- [Ktor Client](https://ktor.io/docs/client.html)

## 更新日志

### v1.0 (当前)
- 初始版本
- 核心功能实现
- 基础 UI 完成
- Unilyric 和 AMLL TTML DB 集成指南
