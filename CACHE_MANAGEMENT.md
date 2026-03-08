# DroidMate 缓存管理说明

## 概述

DroidMate 应用的所有可清理缓存数据都已配置存储在 Android 系统的 cache 目录中，可以通过系统设置中的"清除缓存"功能进行清理。

## 缓存目录结构

```
/data/data/com.amll.droidmate/cache/
├── album_art/          # 专辑封面图片缓存 (MediaInfoService)
│   ├── album_art_xxxxx.jpg
│   └── ...
└── http_cache/         # HTTP 网络请求缓存 (Ktor/OkHttp)
    ├── journal
    └── xxxxx.0
```

## 缓存分类

### 1. 专辑封面缓存 (`album_art/`)
- **位置**: `/cache/album_art/`
- **大小**: 动态（每个封面约 100-500 KB）
- **用途**: 存储当前播放歌曲的专辑封面图片
- **管理**: 由 `MediaInfoService.kt` 管理
- **文件命名**: `album_art_<hash>.jpg`（基于歌曲信息的哈希值）

### 2. HTTP 网络缓存 (`http_cache/`)
- **位置**: `/cache/http_cache/`
- **大小**: 最大 50 MB
- **用途**: 缓存所有网络请求响应（歌词搜索、歌词获取等）
- **管理**: 由 `HttpClientFactory.kt` 配置的 OkHttp 缓存
- **自动清理**: 当缓存超过 50 MB 时自动清理旧数据

## 用户数据（不可清除）

以下数据存储在 `files/` 或 `shared_prefs/` 目录，**不受**"清除缓存"影响：

### SharedPreferences (`/shared_prefs/`)
- **设置项**:
  - 点击卡片行为偏好
  - 实时歌词通知开关
  - 其他用户设置

## 清除缓存的影响

当用户在系统设置中清除应用缓存时：

✅ **会被清除**:
- 专辑封面图片（下次播放时重新获取）
- 网络请求缓存（下次请求时重新获取）

❌ **不会被清除**:
- 用户设置偏好
- 应用数据

## 技术实现

### HttpClientFactory.kt
```kotlin
object HttpClientFactory {
    private const val CACHE_SIZE = 50L * 1024 * 1024 // 50 MB
    private const val CACHE_DIR_NAME = "http_cache"
    
    fun create(context: Context): HttpClient {
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
        return HttpClient(OkHttp) {
            engine {
                config {
                    cache(Cache(cacheDir, CACHE_SIZE))
                }
            }
        }
    }
}
```

### MediaInfoService.kt
```kotlin
private fun saveAlbumArtToCache(...): String? {
    val cacheDir = File(context.cacheDir, "album_art")
    if (!cacheDir.exists()) {
        cacheDir.mkdirs()
    }
    // 保存封面到 cache 目录
}
```

## 优势

1. **符合 Android 规范**: 遵循 Android 存储最佳实践
2. **用户可控**: 用户可通过系统设置清除缓存
3. **空间管理**: 系统可在空间不足时自动清理 cache 目录
4. **性能优化**: HTTP 缓存减少网络请求，提升加载速度
5. **数据安全**: 用户设置不会被误删

## 监控与调试

### 查看当前缓存大小

```bash
# 通过 adb 查看
adb shell du -sh /data/data/com.amll.droidmate/cache/*

# 输出示例:
# 512K    /data/data/com.amll.droidmate/cache/album_art
# 15M     /data/data/com.amll.droidmate/cache/http_cache
```

### 手动清除缓存（测试）

```bash
adb shell pm clear com.amll.droidmate
# 或
adb shell rm -rf /data/data/com.amll.droidmate/cache
```

## 相关文件

- `HttpClientFactory.kt` - HTTP 缓存配置
- `MediaInfoService.kt` - 专辑封面缓存
- `MainViewModel.kt` - 使用 HttpClient
- `CustomLyricsViewModel.kt` - 使用 HttpClient

---

**最后更新**: 2026年3月8日
