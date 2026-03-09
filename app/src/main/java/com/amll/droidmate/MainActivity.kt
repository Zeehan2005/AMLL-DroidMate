package com.amll.droidmate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amll.droidmate.ui.theme.AlbumColorExtractor
import com.amll.droidmate.ui.theme.DroidMateTheme
import com.amll.droidmate.ui.theme.DynamicColorScheme
import com.amll.droidmate.ui.theme.DynamicThemeManager
import com.amll.droidmate.ui.screens.MainScreen
import com.amll.droidmate.ui.viewmodel.MainViewModel
import timber.log.Timber

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 启用沉浸式状态栏
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        setContent {
            val viewModel: MainViewModel = viewModel()
            val nowPlaying by viewModel.nowPlayingMusic.collectAsState()
            val isDarkTheme = isSystemInDarkTheme()
            
            // 当专辑图变化时，提取颜色并更新到全局ThemeManager
            LaunchedEffect(nowPlaying?.albumArtUri, isDarkTheme) {
                val albumArtUri = nowPlaying?.albumArtUri
                if (!albumArtUri.isNullOrBlank()) {
                    try {
                        Timber.d("Extracting colors from album art: $albumArtUri")
                        val colors = AlbumColorExtractor.extractColorsFromAlbumArt(
                            context = this@MainActivity,
                            albumArtUri = albumArtUri,
                            isDarkTheme = isDarkTheme
                        )
                        // 更新到全局ThemeManager，供所有Activity使用
                        DynamicThemeManager.updateColorScheme(colors)
                        if (colors != null) {
                            Timber.d("Dynamic colors extracted and applied globally")
                        } else {
                            Timber.d("Failed to extract colors, using default theme")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error extracting colors from album art")
                        DynamicThemeManager.clearColorScheme()
                    }
                } else {
                    // 没有专辑图时使用默认主题
                    DynamicThemeManager.clearColorScheme()
                }
            }
            
            // 使用全局ThemeManager的颜色方案
            val dynamicColorScheme by DynamicThemeManager.observeColorScheme()
            
            DroidMateTheme(
                darkTheme = isDarkTheme,
                dynamicColorScheme = dynamicColorScheme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}
