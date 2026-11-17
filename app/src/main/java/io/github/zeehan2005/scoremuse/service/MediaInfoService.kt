package io.github.zeehan2005.scoremuse.service

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import io.github.zeehan2005.scoremuse.global.NowPlayingMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.scale
import kotlin.time.Duration.Companion.milliseconds

/**
 * 轮询间隔（毫秒）。歌词逐帧推进已交给 WebView 内的 rAF 本地时钟，
 * 这里只需 10Hz 采样即可保证进度条/通知及时更新，避免 120Hz 轮询
 * 引发 StateFlow 每帧发射与 Compose 重组风暴。
 */
private const val MEDIA_POLL_INTERVAL_MS = 100L

/**
 * 媒体信息监听服务 - 获取当前播放的歌曲信息
 *
 * 性能优化：
 * - 使用 Coroutine + Dispatchers.IO 在后台线程执行轮询
 * - 专辑图片异步处理（不再缓存，专辑图变化时直接覆盖旧文件）
 * - Flow 发射优化，仅在关键数据变化时更新
 *
 * 并发与文件安全：
 * - 专辑图保存使用 Mutex 串行化，避免轮询并发导致文件写入被截断
 * - 写入采用「临时文件 + 原子 rename」策略，保证前端永远读到完整的文件
 */
class MediaInfoService(private val context: Context) {

    private val _nowPlayingMusic = MutableStateFlow<NowPlayingMusic?>(null)
    val nowPlayingMusic: StateFlow<NowPlayingMusic?> = _nowPlayingMusic

    /** 后台协程作用域，使用 IO 调度器 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mediaSessionManager: MediaSessionManager? = try {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    } catch (e: Exception) {
        Timber.e("[MediaInfoService] Failed to get MediaSessionManager $e")
        null
    }

    private val listenerComponentName = ComponentName(context, MediaListenerService::class.java)

    private var currentController: MediaController? = null

    /** 专辑图保存互斥锁：保证文件写入/删除串行执行，避免轮询并发导致的「只获取一半」bug */
    private val albumArtWriteMutex = Mutex()

    /** 上一次专辑图强制刷新请求的来源标识，用于去重和避免重复重拉 */
    private var lastAlbumArtRefreshKey: String? = null

    /** 缓存上次处理专辑图的歌曲签名 — 歌曲切换时才重新 fetch，避免每轮轮询调用 getBitmap()（昂贵的 binder IPC） */
    private var lastAlbumArtSongKey: String? = null



    /**
     * 启动监听
     */
    fun startListening() {
        Timber.i("[MediaInfoService] Starting media info listener")
        scheduleNextUpdate()
    }

    /**
     * 停止监听
     */
    fun stopListening() {
        Timber.i("[MediaInfoService] Stopping media info listener")
        serviceScope.cancel()
    }

    /**
     * 强制刷新专辑图。
     *
     * 由用户主动点击「刷新」按钮触发，会清空当前缓存的专辑图，并强制下一轮轮询
     * 重新走「获取 Bitmap → 写文件」的完整流程，确保前端能立刻看到最新专辑图。
     *
     * @param refreshKey 调用方提供的唯一标识（例如歌曲的 title+artist+packageName），
     *                   相同 key 在专辑图未变化时不会重复清空缓存。
     */
    fun refreshAlbumArt(refreshKey: String? = null) {
        if (refreshKey != null && refreshKey == lastAlbumArtRefreshKey) {
            Timber.d("[MediaInfoService] refreshAlbumArt skipped (same key: $refreshKey)")
            return
        }
        lastAlbumArtRefreshKey = refreshKey
        Timber.i("[MediaInfoService] refreshAlbumArt requested (key=$refreshKey)")

        serviceScope.launch {
            albumArtWriteMutex.withLock {
                try {
                    val cacheDir = File(context.cacheDir, "album_art")
                    if (cacheDir.exists()) {
                        val files = cacheDir.listFiles().orEmpty()
                        for (f in files) {
                            if (f.isFile && !f.delete()) {
                                Timber.w("[AlbumArtExtractor] refreshAlbumArt: failed to delete ${f.absolutePath}")
                            }
                        }
                        Timber.i("[MediaInfoService] refreshAlbumArt: cleared ${files.size} stale files")
                    }
                } catch (e: Exception) {
                    Timber.e("[AlbumArtExtractor] refreshAlbumArt: failed to clear cache $e")
                }
            }
        }
    }

    /**
     * 更新媒体信息（在 IO 线程执行）
     */
    private suspend fun updateMediaInfo() {
        if (!serviceScope.isActive) return

        try {
            val activeSessions = withContext(Dispatchers.IO) {
                mediaSessionManager?.getActiveSessions(listenerComponentName)
            }

            if (!activeSessions.isNullOrEmpty()) {
                val controller = activeSessions[0]
                currentController = controller
                val metadata = controller.metadata
                val playbackState = controller.playbackState
                val packageName = controller.packageName

                if (metadata != null && playbackState != null) {
                    val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
                    val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown"
                    val album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)
                    val duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
                    val position = playbackState.position
                    val isPlaying = playbackState.state == android.media.session.PlaybackState.STATE_PLAYING

                    /** 旧音乐对象（用于对比） */
                    val oldMusic = _nowPlayingMusic.value

                    /** 当前已展示的专辑图 URI */
                    val currentAlbumArtUri = oldMusic?.albumArtUri

                    /** 歌曲签名 — 用于判断专辑图是否需要重新 fetch */
                    val currentSongKey = "${packageName ?: ""}_${title}_$artist"
                    val songChanged = lastAlbumArtSongKey != currentSongKey

                    /** 仅在歌曲切换或尚无专辑图时才进行昂贵的跨进程 bitmap fetch */
                    val needsAlbumArtRefresh = songChanged || currentAlbumArtUri.isNullOrEmpty()

                    val newAlbumArtBitmap = if (needsAlbumArtRefresh) {
                        metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
                            ?: metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
                    } else {
                        null // 复用已有缓存，避免重复 binder IPC
                    }

                    /** 歌曲未变化且拿到 bitmap → 更新缓存 key；若 getBitmap 返回 null 则不更新，下一轮重试 */
                    if (newAlbumArtBitmap != null) {
                        lastAlbumArtSongKey = currentSongKey
                    }

                    /** 获取专辑图 URI（轻量 binder 调用，作为备选来源）*/
                    val currentArtUri = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                        ?: metadata.getString(android.media.MediaMetadata.METADATA_KEY_ART_URI)

                    /** 智能判断是否需要处理专辑图 */
                    val finalAlbumArtUri = when {
                        // 情况 1: 获取到了新的 Bitmap → 写入文件
                        newAlbumArtBitmap != null -> {
                            val newUri = processAlbumArtBitmap(bitmap = newAlbumArtBitmap, title, artist, packageName)
                            // 新文件写入成功后，延迟清理不再使用的旧专辑图文件
                            // 放在这里而不是 saveAlbumArtBitmapToCache 内部，避免在写入期间删除正在被 UI 引用的旧文件
                            if (newUri != null && currentAlbumArtUri != newUri) {
                                serviceScope.launch {
                                    albumArtWriteMutex.withLock {
                                        val cacheDir = File(context.cacheDir, "album_art")
                                        val newFile = File(newUri.removePrefix("file://"))
                                        deleteAllAlbumArtCache(cacheDir, excluding = newFile, onlyTmp = false)
                                    }
                                }
                            }
                            newUri
                        }
                        // 情况 2: 无 bitmap 也无 URI → 尝试异步从 URI 获取（降级）
                        currentAlbumArtUri.isNullOrBlank() -> {
                            if (!currentArtUri.isNullOrBlank()) {
                                Timber.d("[MediaInfoService] No bitmap, falling back to URI fetch")
                                processAlbumArtAsync(metadata, title, artist, packageName)
                            } else {
                                Timber.d("[MediaInfoService] No album art source available")
                                null
                            }
                        }
                        // 情况 3: 复用已有专辑图（歌曲未变化，bitmap 为 null 说明跳过 fetch）
                        else -> {
                            // 验证文件是否仍然存在（可能被 processAlbumArtBitmap 的 cache cleanup 删除）
                            val cachedFile = File(currentAlbumArtUri.removePrefix("file://"))
                            if (cachedFile.exists()) {
                                currentAlbumArtUri
                            } else {
                                Timber.d("[MediaInfoService] Cached album art file deleted, trying URI fallback")
                                if (!currentArtUri.isNullOrBlank()) {
                                    processAlbumArtAsync(metadata, title, artist, packageName)
                                } else {
                                    null
                                }
                            }
                        }
                    }

                    /** 构建最终的音乐对象（包含最新播放时间和专辑图） */
                    val updatedMusic = NowPlayingMusic(
                        title = title,
                        artist = artist,
                        album = album,
                        duration = duration,
                        currentPosition = position,
                        isPlaying = isPlaying,
                        packageName = packageName,
                        albumArtUri = finalAlbumArtUri,
                        timestamp = System.currentTimeMillis()
                    )


                        _nowPlayingMusic.value = updatedMusic
                }
            } else {
                currentController = null
            }
        } catch (e: SecurityException) {
            Timber.e("[MediaInfoService] Permission denied to access media sessions $e")
            updateMediaInfoViaContentResolver()
        } catch (e: Exception) {
            Timber.e("[MediaInfoService] Error updating media info $e")
        }
    }

    /**
     * 通过 ContentResolver 获取媒体信息（备选方案）
     */
    private fun updateMediaInfoViaContentResolver() {
        try {
            // 注：这是一个简化的实现
            // 实际应用可能需要使用 MediaStore 或其他方式
            Timber.i("[MediaInfoService] Attempting to get media info via ContentResolver")
        } catch (e: Exception) {
            Timber.e("[MediaInfoService] Error getting media info via ContentResolver $e")
        }
    }

    /**
     * 直接从 Bitmap 处理专辑图（不再使用内存缓存，每次写入都会覆盖旧文件）
     */
    private suspend fun processAlbumArtBitmap(
        bitmap: Bitmap,
        title: String,
        artist: String,
        packageName: String?
    ): String? = withContext(Dispatchers.IO) {
        try {
            /** 生成缓存 key */
            val cacheKey = "${packageName ?: "unknown"}_${title}_$artist"

            // 保存（并销毁旧文件）
            saveAlbumArtBitmapToCache(
                bitmap = bitmap,
                cacheKey = cacheKey
            )
        } catch (e: Exception) {
            Timber.e("[AlbumArtExtractor] Failed to process album art bitmap $e")
            null
        }
    }

    /**
     * 异步处理专辑封面（在 IO 线程执行）
     * 从 URI 获取专辑图（不再使用内存缓存）
     */
    private suspend fun processAlbumArtAsync(
        metadata: android.media.MediaMetadata,
        title: String,
        artist: String,
        packageName: String?
    ): String? = withContext(Dispatchers.IO) {
        try {
            /** 生成缓存 key */
            val cacheKey = "${packageName ?: "unknown"}_${title}_$artist"

            /** 获取专辑图 Bitmap */
            val albumArtBitmap = metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)

            if (albumArtBitmap == null) {
                Timber.i("[AlbumArtExtractor] Failed to get bitmap, trying URI")
                return@withContext metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: metadata.getString(android.media.MediaMetadata.METADATA_KEY_ART_URI)
            }

            // 保存（并销毁旧文件）
            saveAlbumArtBitmapToCache(
                bitmap = albumArtBitmap,
                cacheKey = cacheKey
            )
        } catch (e: Exception) {
            Timber.e("[AlbumArtExtractor] Failed to process album art $e")
            null
        }
    }

    /**
     * 保存专辑图 Bitmap 到磁盘
     *
     * 行为变更：
     * - 移除了内存缓存（albumArtCache）以及「文件已存在则直接返回」的逻辑
     * - 每次写入新的专辑图前，都会先销毁当前缓存目录中的所有旧专辑图文件，
     *   以确保专辑图变化时能即时反映到前端，不会出现「同一首歌内残留旧图」的情况
     *
     * 关键修复（避免「专辑图只获取一半」bug）：
     * - 整个删除 + 写入流程由 albumArtWriteMutex 串行化，避免轮询并发时
     *   两次保存互相删除对方正在写入的文件，导致最终落到磁盘上的文件不完整
     * - 写入采用「临时文件 + 原子 rename」策略，保证前端任何时刻读取到的文件
     *   都是完整可用的 JPEG 数据
     */
    private suspend fun saveAlbumArtBitmapToCache(
        bitmap: Bitmap,
        cacheKey: String
    ): String? {
        return albumArtWriteMutex.withLock {
            try {
                val cacheDir = File(context.cacheDir, "album_art")
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }

                /** 使用哈希值作为文件名，避免特殊字符问题 */
                val safeKey = cacheKey.hashCode().toUInt().toString(16)
                val file = File(cacheDir, "album_art_${safeKey}.jpg")
                /** 临时文件：先写到这里，写完后再原子重命名为正式文件 */
                val tmpFile = File(cacheDir, "album_art_${safeKey}.jpg.tmp")

                // 仅删除与新文件名不同的旧 .tmp 临时文件
                // 不删除其他正式专辑图文件，避免在歌曲切换瞬间导致旧的 URI 指向不存在的文件
                // 旧的正式文件会在新文件写入成功后、下一次轮询中因不再被引用而被清理
                deleteAllAlbumArtCache(cacheDir, excluding = file, onlyTmp = true)

                /** 缩放图片至最大 512x512，减少内存占用 */
                val scaledBitmap = resizeBitmap(bitmap)

                try {
                    FileOutputStream(tmpFile).use { out ->
                        // 压缩质量 75，显著减少文件大小
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
                        out.flush()
                    }
                } finally {
                    // 关键修复：仅在创建了新 Bitmap 时才调用 recycle()
                    // 如果图片本身小于 512px，resizeBitmap 会返回原始 bitmap，
                    // 此时不应 recycle，因为它可能仍被 MediaSession/Metadata 使用。
                    if (scaledBitmap !== bitmap) {
                        scaledBitmap.recycle()
                    }
                }

                // 原子重命名：保证前端任何时刻看到的 album_art_xxx.jpg 都是完整文件
                // renameTo 在大多数文件系统上是原子操作，且若目标已存在会自动覆盖
                if (!tmpFile.renameTo(file)) {
                    // 极少数情况下 renameTo 失败（例如跨挂载点），回退到 delete + rename
                    file.delete()
                    if (!tmpFile.renameTo(file)) {
                        Timber.e("[AlbumArtExtractor] Failed to rename tmp file to ${file.absolutePath}")
                        return@withLock null
                    }
                }

                val uri = "file://${file.absolutePath}"
                uri
            } catch (e: Exception) {
                Timber.e("[AlbumArtExtractor] Failed to save album art to cache $e")
                null
            }
        }
    }

    /**
     * 删除缓存目录中除指定保留文件以外的专辑图文件
     * @param onlyTmp 如果为 true，仅删除 .tmp 临时文件；如果为 false，删除所有非保留文件
     */
    private fun deleteAllAlbumArtCache(cacheDir: File, excluding: File, onlyTmp: Boolean = false) {
        try {
            val files = cacheDir.listFiles() ?: return
            for (f in files) {
                if (f.isFile && f.absolutePath != excluding.absolutePath) {
                    if (onlyTmp && !f.name.endsWith(".tmp")) continue
                    if (!f.delete()) {
                        Timber.w("[AlbumArtExtractor] Failed to delete stale album art: ${f.absolutePath}")
                    } else {
                        Timber.d("[AlbumArtExtractor] Deleted stale album art: ${f.absolutePath}")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e("[AlbumArtExtractor] Failed to clean album art cache $e")
        }
    }

    /**
     * 缩放 Bitmap 到目标尺寸
     */
    private fun resizeBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= 512 && bitmap.height <= 512) {
            return bitmap
        }

        val ratio = minOf(512.toFloat() / bitmap.width, 512.toFloat() / bitmap.height)
        val newWidth = (bitmap.width * ratio).toInt()
        val newHeight = (bitmap.height * ratio).toInt()

        return bitmap.scale(newWidth, newHeight)
    }

    /**
     * 定时更新媒体信息（使用协程而非 Handler）
     */
    private fun scheduleNextUpdate() {
        serviceScope.launch {
            while (isActive) {
                updateMediaInfo()
                delay(MEDIA_POLL_INTERVAL_MS.milliseconds)
            }
        }
    }

    /**
     * 播放控制
     */
    fun play() {
        currentController?.transportControls?.play()
        Timber.i("[PlaybackControl] Play command sent")
    }

    fun pause() {
        currentController?.transportControls?.pause()
        Timber.i("[PlaybackControl] Pause command sent")
    }

    fun skipToNext() {
        currentController?.transportControls?.skipToNext()
        Timber.i("[PlaybackControl] Skip to next command sent")
    }

    fun skipToPrevious() {
        currentController?.transportControls?.skipToPrevious()
        Timber.i("[PlaybackControl] Skip to previous command sent")
    }

    fun seekTo(position: Long) {
        val controller = currentController
        if (controller == null) {
            Timber.e("[PlaybackControl] Seek ignored: no active MediaController, target=$position ms")
            return
        }

        val packageName = controller.packageName
        val playbackState = controller.playbackState?.state
        controller.transportControls.seekTo(position)
        Timber.i("[PlaybackControl] Seek command sent: target=$position ms, package=$packageName, playbackState=$playbackState")
    }

    fun fastForward() {
        currentController?.transportControls?.fastForward()
        Timber.i("[PlaybackControl] Fast forward command sent")
    }

    fun rewind() {
        currentController?.transportControls?.rewind()
        Timber.i("[PlaybackControl] Rewind command sent")
    }
}
