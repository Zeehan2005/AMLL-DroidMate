package com.amll.droidmate.service

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import com.amll.droidmate.domain.model.NowPlayingMusic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * 媒体信息监听服务 - 获取当前播放的歌曲信息
 */
class MediaInfoService(private val context: Context) {
    
    private val _nowPlayingMusic = MutableStateFlow<NowPlayingMusic?>(null)
    val nowPlayingMusic: StateFlow<NowPlayingMusic?> = _nowPlayingMusic
    
    private val mediaSessionManager: MediaSessionManager? = try {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    } catch (e: Exception) {
        Timber.f(e, "Failed to get MediaSessionManager")
        null
    }

    private val listenerComponentName = ComponentName(context, MediaListenerService::class.java)
    
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var currentController: MediaController? = null
    
    /**
     * 启动监听
     */
    fun startListening() {
        Timber.i("Starting media info listener")
        updateMediaInfo()
        scheduleNextUpdate()
    }
    
    /**
     * 停止监听
     */
    fun stopListening() {
        Timber.i("Stopping media info listener")
        updateRunnable?.let { handler.removeCallbacks(it) }
    }
    
    /**
     * 更新媒体信息
     */
    private fun updateMediaInfo() {
        try {
            val activeSessions = mediaSessionManager?.getActiveSessions(listenerComponentName)
            
            if (activeSessions != null && activeSessions.isNotEmpty()) {
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
                    
                    // 获取专辑封面 - 优先获取 Bitmap，然后保存并返回 URI
                    val albumArtUri = try {
                        val albumArtBitmap = metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
                            ?: metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
                        
                        if (albumArtBitmap != null) {
                            // 如果有 Bitmap，保存到缓存并返回 content:// URI
                            saveAlbumArtToCache(
                                bitmap = albumArtBitmap,
                                title = title,
                                artist = artist,
                                packageName = packageName
                            )
                        } else {
                            // 否则尝试获取 URI
                            metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                                ?: metadata.getString(android.media.MediaMetadata.METADATA_KEY_ART_URI)
                        }
                    } catch (e: Exception) {
                        Timber.f(e, "Failed to get album art")
                        null
                    }
                    
                    _nowPlayingMusic.value = NowPlayingMusic(
                        title = title,
                        artist = artist,
                        album = album,
                        duration = duration,
                        currentPosition = position,
                        isPlaying = isPlaying,
                        packageName = packageName,
                        albumArtUri = albumArtUri,
                        timestamp = System.currentTimeMillis()
                    )
                    
                    Timber.d("Updated media info: $title - $artist (from $packageName)")
                }
            } else {
                Timber.w("No active media sessions found")
                currentController = null
            }
        } catch (e: SecurityException) {
            Timber.f("Permission denied to access media sessions")
            // 尝试通过其他方式获取
            updateMediaInfoViaContentResolver()
        } catch (e: Exception) {
            Timber.f(e, "Error updating media info")
        }
    }
    
    /**
     * 通过 ContentResolver 获取媒体信息（备选方案）
     */
    private fun updateMediaInfoViaContentResolver() {
        try {
            // 注: 这是一个简化的实现
            // 实际应用可能需要使用 MediaStore 或其他方式
            Timber.i("Attempting to get media info via ContentResolver")
        } catch (e: Exception) {
            Timber.e(e, "Error getting media info via ContentResolver")
        }
    }
    
    /**
     * 保存专辑封面到缓存并返回 file:// URI
     */
    private fun saveAlbumArtToCache(
        bitmap: Bitmap,
        title: String,
        artist: String,
        packageName: String?
    ): String? {
        return try {
            val cacheDir = File(context.cacheDir, "album_art")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            // 基于歌曲信息生成文件名，避免切歌后因同名文件导致 UI 缓存不刷新
            val safeKey = ("${packageName ?: "unknown"}_${title}_${artist}").hashCode().toUInt().toString(16)
            val file = File(cacheDir, "album_art_${safeKey}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            
            val uri = "file://${file.absolutePath}"
            Timber.d("Saved album art to cache: $uri")
            uri
        } catch (e: Exception) {
            Timber.f(e, "Failed to save album art to cache")
            null
        }
    }
    
    /**
     * 定时更新媒体信息
     */
    private fun scheduleNextUpdate() {
        updateRunnable = Runnable {
            updateMediaInfo()
            scheduleNextUpdate()
        }.also { runnable ->
            handler.postDelayed(runnable, UPDATE_INTERVAL_MS)
        }
    }
    
    /**
     * 播放控制
     */
    fun play() {
        currentController?.transportControls?.play()
        Timber.i("Play command sent")
    }
    
    fun pause() {
        currentController?.transportControls?.pause()
        Timber.i("Pause command sent")
    }
    
    fun skipToNext() {
        currentController?.transportControls?.skipToNext()
        Timber.i("Skip to next command sent")
    }
    
    fun skipToPrevious() {
        currentController?.transportControls?.skipToPrevious()
        Timber.i("Skip to previous command sent")
    }
    
    fun seekTo(position: Long) {
        val controller = currentController
        if (controller == null) {
            Timber.e("Seek ignored: no active MediaController, target=$position ms")
            return
        }

        val packageName = controller.packageName
        val playbackState = controller.playbackState?.state
        controller.transportControls.seekTo(position)
        Timber.i("Seek command sent: target=$position ms, package=$packageName, playbackState=$playbackState")
    }
    
    fun fastForward() {
        currentController?.transportControls?.fastForward()
        Timber.i("Fast forward command sent")
    }
    
    fun rewind() {
        currentController?.transportControls?.rewind()
        Timber.i("Rewind command sent")
    }
    
    companion object {
        private const val UPDATE_INTERVAL_MS = 500L  // 每 500ms 更新一次
    }
}
