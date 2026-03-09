# 动态配色方案集成文档

## 概述

DroidMate 现已支持基于专辑封面的动态配色方案。当播放音乐时，应用会自动从专辑封面图片中提取主色调，并应用到整个UI界面，同时适配深色和浅色模式。

## 功能特性

### 1. 自动颜色提取
- 使用 Android Palette API 从专辑封面提取颜色
- 提取多种颜色：主色（Vibrant）、次要色（Muted）、背景色等
- 智能调整颜色以确保良好的对比度和可读性

### 2. 深色/浅色模式适配
- **深色模式**: 自动提高颜色亮度，降低饱和度，适合暗光环境
- **浅色模式**: 自动降低颜色亮度，增加饱和度，确保文字清晰可读

### 3. 平滑过渡
- 切换歌曲时，颜色主题会平滑过渡
- 无专辑封面时自动回退到默认配色方案

### 4. 全局应用
- **统一体验**: 所有Activity（主界面、设置、歌词缓存、自定义歌词等）都使用相同的动态配色
- **自动同步**: 使用单例ThemeManager，一处更新，全局生效
- **无缝切换**: 从主界面进入任何子页面，配色保持一致

## 技术实现

### 核心组件

#### 1. AlbumColorExtractor (`AlbumColorExtractor.kt`)
颜色提取工具类，负责：
- 从 `file://` 或 `content://` URI 加载专辑封面
- 使用 Palette API 分析图片颜色
- 根据深色/浅色模式生成相应的颜色方案
- 调整颜色的亮度、饱和度和色相以优化显示效果

**关键方法**:
```kotlin
suspend fun extractColorsFromAlbumArt(
    context: Context,
    albumArtUri: String?,
    isDarkTheme: Boolean
): DynamicColorScheme?
```

#### 2. DynamicColorScheme (`AlbumColorExtractor.kt`)
颜色方案数据类，包含：
- `primary`, `onPrimary` - 主色及其文字颜色
- `secondary`, `onSecondary` - 次要色及其文字颜色
- `tertiary`, `onTertiary` - 第三色及其文字颜色
- `background`, `onBackground` - 背景色及其文字颜色
- `surface`, `onSurface` - 表面色及其文字颜色
- `surfaceVariant`, `onSurfaceVariant` - 变体表面色
- `error`, `onError` - 错误色

#### 3. DroidMateTheme (`Theme.kt`)
增强的主题组件，支持：
- 接收可选的 `dynamicColorScheme` 参数
- 自动在动态颜色和默认颜色之间切换
- 保持与 Material 3 设计规范的兼容性

**用法**:
```kotlin
DroidMateTheme(
    darkTheme = isSystemInDarkTheme(),
    dynamicColorScheme = extractedColors // 可选
) {
    // 你的UI内容
}
```

#### 4. DynamicThemeManager (`DynamicThemeManager.kt`)
全局主题管理器，使用单例模式：
- 存储当前的动态颜色方案
- 在MainActivity中更新
- 在所有其他Activity中观察和应用
- 支持Compose State观察，自动响应变化

**用法**:
```kotlin
// 在MainActivity中更新颜色
DynamicThemeManager.updateColorScheme(extractedColors)

// 在其他Activity中观察颜色
val dynamicColorScheme by DynamicThemeManager.observeColorScheme()
DroidMateTheme(dynamicColorScheme = dynamicColorScheme) { ... }
```

#### 5. Activity集成
所有Activity都已集成动态颜色支持：
- **MainActivity**: 监听专辑图URI变化，提取颜色并更新到ThemeManager
- **SettingsActivity**: 观察ThemeManager，自动应用动态颜色
- **CustomLyricsActivity**: 观察ThemeManager，自动应用动态颜色
- **LyricsCacheActivity**: 观察ThemeManager，自动应用动态颜色

当用户在主界面播放音乐时，从主界面进入设置页面会保持相同的配色方案，提供一致的视觉体验。

## 颜色调整算法

### 深色模式调整
1. **提高亮度**: 确保颜色在深色背景上可见（最低亮度 60%）
2. **降低饱和度**: 避免颜色过于刺眼（最高饱和度 70%）
3. **背景变暗**: 将提取的背景色降低亮度 60%，饱和度 40%
4. **Surface 略浅**: Surface 比背景亮 8%

### 浅色模式调整
1. **降低亮度**: 确保颜色在浅色背景上清晰（最高亮度 60%）
2. **增加饱和度**: 使颜色更加鲜明（最低饱和度 60%）
3. **背景变亮**: 将提取的背景色提高亮度 80%，降低饱和度 20%
4. **Surface 更亮**: Surface 比背景亮 10%，接近白色

### 对比度保证
- 自动判断 `onPrimary` 和 `onSecondary` 颜色
- 根据背景亮度选择黑色或白色文字
- 确保符合 WCAG 可访问性标准

## 性能优化

### 图片处理优化
- 加载时将专辑封面缩小到 1/4 大小 (`inSampleSize = 4`)
- 提取完成后立即回收 Bitmap (`bitmap.recycle()`)
- 使用协程在后台线程处理，不阻塞 UI

### 缓存策略
- 颜色提取结果存储在 Compose State 中
- 仅在专辑图 URI 变化时重新提取
- 深色/浅色模式切换时也会重新计算颜色

## 回退机制

当出现以下情况时，自动使用默认配色方案：
1. 专辑封面 URI 为空
2. 无法加载专辑封面图片
3. Palette 提取失败
4. 图片格式不支持

默认配色方案：
- **深色模式**: 深蓝灰色背景，靛蓝主色
- **浅色模式**: 浅灰色背景，靛蓝主色

## 已知限制

1. **首次加载延迟**: 提取颜色需要 50-200ms，切歌时可能有轻微延迟
2. **内存使用**: 处理大图片时可能占用额外内存（已通过缩放优化）
3. **不支持 GIF**: Palette API 仅支持静态图片格式

## 未来改进方向

### 短期改进
- [ ] 添加颜色提取结果的磁盘缓存，避免重复计算
- [ ] 支持用户自定义颜色调整强度（饱和度、亮度偏移）
- [ ] 添加"固定颜色模式"选项，禁用动态颜色

### 长期规划
- [ ] 支持从网络专辑图提取颜色
- [ ] 机器学习模型优化颜色选择
- [ ] 为不同音乐流派设置不同的颜色风格
- [ ] 与 Material You 动态颜色主题集成（Android 12+）

## 相关文件

- `app/src/main/java/com/amll/droidmate/ui/theme/AlbumColorExtractor.kt` - 颜色提取工具
- `app/src/main/java/com/amll/droidmate/ui/theme/DynamicThemeManager.kt` - 全局主题管理器
- `app/src/main/java/com/amll/droidmate/ui/theme/Theme.kt` - 主题定义
- `app/src/main/java/com/amll/droidmate/MainActivity.kt` - 主Activity，颜色提取和更新
- `app/src/main/java/com/amll/droidmate/ui/SettingsActivity.kt` - 设置页面集成
- `app/src/main/java/com/amll/droidmate/ui/CustomLyricsActivity.kt` - 自定义歌词页面集成
- `app/src/main/java/com/amll/droidmate/ui/LyricsCacheActivity.kt` - 歌词缓存页面集成
- `app/build.gradle.kts` - Palette 依赖配置

## 依赖库

```kotlin
// build.gradle.kts
implementation("androidx.palette:palette-ktx:1.0.0")
```

## 测试建议

1. 测试不同风格的专辑封面（亮色、暗色、彩色、黑白）
2. 测试深色/浅色模式切换
3. 测试无专辑封面的情况
4. 测试快速切歌时的性能
5. 测试不同 Android 版本的兼容性（API 26+）

## 日志记录

启用 Timber 日志查看颜色提取过程：
```
adb logcat | grep "AlbumColorExtractor"
```

关键日志：
- `Extracting colors from album art: [URI]` - 开始提取
- `Dynamic colors extracted successfully` - 提取成功
- `Failed to extract colors, using default theme` - 提取失败，使用默认主题
