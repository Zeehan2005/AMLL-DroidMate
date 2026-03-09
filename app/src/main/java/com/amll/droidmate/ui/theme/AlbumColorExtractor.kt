package com.amll.droidmate.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream

/**
 * 从专辑封面图提取动态颜色的工具类
 */
object AlbumColorExtractor {

    /**
     * 从专辑封面URI提取动态颜色主题
     * @param context Context
     * @param albumArtUri 专辑封面URI（支持 file:// 和 content:// 协议）
     * @param isDarkTheme 是否为深色模式
     * @return DynamicColorScheme 包含extracted colors
     */
    suspend fun extractColorsFromAlbumArt(
        context: Context,
        albumArtUri: String?,
        isDarkTheme: Boolean
    ): DynamicColorScheme? = withContext(Dispatchers.IO) {
        if (albumArtUri.isNullOrBlank()) {
            Timber.d("Album art URI is null or blank")
            return@withContext null
        }

        try {
            val bitmap = loadBitmapFromUri(context, albumArtUri) ?: return@withContext null
            val palette = Palette.from(bitmap).generate()
            
            bitmap.recycle()

            return@withContext createDynamicColorScheme(palette, isDarkTheme)
        } catch (e: Exception) {
            Timber.e(e, "Failed to extract colors from album art: $albumArtUri")
            return@withContext null
        }
    }

    /**
     * 从URI加载Bitmap
     */
    private fun loadBitmapFromUri(context: Context, uriString: String): Bitmap? {
        try {
            val inputStream: InputStream? = when {
                uriString.startsWith("file://") -> {
                    val path = uriString.removePrefix("file://")
                    File(path).inputStream()
                }
                uriString.startsWith("content://") -> {
                    val uri = Uri.parse(uriString)
                    context.contentResolver.openInputStream(uri)
                }
                else -> {
                    Timber.w("Unsupported URI scheme: $uriString")
                    return null
                }
            }

            return inputStream?.use { stream ->
                // 缩小图片以提高性能
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 4 // 缩小到1/4大小
                }
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load bitmap from URI: $uriString")
            return null
        }
    }

    /**
     * 根据Palette生成动态颜色方案
     */
    private fun createDynamicColorScheme(
        palette: Palette,
        isDarkTheme: Boolean
    ): DynamicColorScheme {
        if (isDarkTheme) {
            return createDarkColorScheme(palette)
        } else {
            return createLightColorScheme(palette)
        }
    }

    /**
     * 创建深色模式的颜色方案
     */
    private fun createDarkColorScheme(palette: Palette): DynamicColorScheme {
        // 主色：使用鲜艳的颜色
        val primarySwatch = palette.vibrantSwatch 
            ?: palette.lightVibrantSwatch
            ?: palette.dominantSwatch
        val primary = primarySwatch?.let { Color(it.rgb).adjustForDarkMode() }
            ?: Color(0xFF6366F1)

        // 次要色：使用柔和的颜色
        val secondarySwatch = palette.mutedSwatch 
            ?: palette.lightMutedSwatch
            ?: palette.dominantSwatch
        val secondary = secondarySwatch?.let { Color(it.rgb).adjustForDarkMode() }
            ?: Color(0xFF8B5CF6)

        // 背景色：深色
        val background = palette.darkMutedSwatch?.let {
            Color(it.rgb).darken(0.6f).adjustSaturation(0.4f)
        } ?: Color(0xFF1F2937)

        // Surface色：略浅于背景
        val surface = background.lighten(0.08f)

        // 确保对比度
        val onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White
        val onSecondary = if (secondary.luminance() > 0.5f) Color.Black else Color.White
        val onBackground = Color(0xFFE5E5E5)
        val onSurface = Color(0xFFE5E5E5)

        return DynamicColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            tertiary = primary.rotatehue(30f),
            onTertiary = onPrimary,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surface.lighten(0.05f),
            onSurfaceVariant = onSurface.copy(alpha = 0.8f),
            error = Color(0xFFEF4444),
            onError = Color.White
        )
    }

    /**
     * 创建浅色模式的颜色方案
     */
    private fun createLightColorScheme(palette: Palette): DynamicColorScheme {
        // 主色：使用鲜艳的颜色
        val primarySwatch = palette.vibrantSwatch 
            ?: palette.darkVibrantSwatch
            ?: palette.dominantSwatch
        val primary = primarySwatch?.let { Color(it.rgb).adjustForLightMode() }
            ?: Color(0xFF6366F1)

        // 次要色：使用柔和的颜色
        val secondarySwatch = palette.mutedSwatch 
            ?: palette.darkMutedSwatch
            ?: palette.dominantSwatch
        val secondary = secondarySwatch?.let { Color(it.rgb).adjustForLightMode() }
            ?: Color(0xFF8B5CF6)

        // 背景色：浅色
        val background = palette.lightMutedSwatch?.let {
            Color(it.rgb).lighten(0.8f).adjustSaturation(0.2f)
        } ?: Color(0xFFFAFAFA)

        // Surface色：纯白或接近白
        val surface = background.lighten(0.1f).coerceAtMost(Color.White)

        // 确保对比度
        val onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White
        val onSecondary = if (secondary.luminance() > 0.5f) Color.Black else Color.White
        val onBackground = Color(0xFF1F1F1F)
        val onSurface = Color(0xFF1F1F1F)

        return DynamicColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            tertiary = primary.rotatehue(30f),
            onTertiary = onPrimary,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surface.darken(0.05f),
            onSurfaceVariant = onSurface.copy(alpha = 0.8f),
            error = Color(0xFFEF4444),
            onError = Color.White
        )
    }

    // 颜色调整辅助函数

    /**
     * 调整颜色使其适合深色模式（提高亮度）
     */
    private fun Color.adjustForDarkMode(): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(this.toArgb(), hsv)
        
        // 如果太暗，提高亮度
        if (hsv[2] < 0.5f) {
            hsv[2] = hsv[2].coerceAtLeast(0.6f)
        }
        // 如果太鲜艳，降低饱和度
        if (hsv[1] > 0.8f) {
            hsv[1] = 0.7f
        }
        
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /**
     * 调整颜色使其适合浅色模式（降低亮度）
     */
    private fun Color.adjustForLightMode(): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(this.toArgb(), hsv)
        
        // 如果太亮，降低亮度
        if (hsv[2] > 0.7f) {
            hsv[2] = hsv[2].coerceAtMost(0.6f)
        }
        // 增加饱和度使颜色更鲜明
        if (hsv[1] < 0.5f) {
            hsv[1] = 0.6f
        }
        
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /**
     * 使颜色变暗
     */
    private fun Color.darken(factor: Float): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(this.toArgb(), hsv)
        hsv[2] = (hsv[2] * (1f - factor)).coerceAtLeast(0f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /**
     * 使颜色变亮
     */
    private fun Color.lighten(factor: Float): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(this.toArgb(), hsv)
        hsv[2] = (hsv[2] + (1f - hsv[2]) * factor).coerceAtMost(1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /**
     * 调整饱和度
     */
    private fun Color.adjustSaturation(factor: Float): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(this.toArgb(), hsv)
        hsv[1] = (hsv[1] * factor).coerceIn(0f, 1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /**
     * 旋转色相
     */
    private fun Color.rotatehue(degrees: Float): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(this.toArgb(), hsv)
        hsv[0] = (hsv[0] + degrees) % 360f
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /**
     * 限制颜色的最大值
     */
    private fun Color.coerceAtMost(other: Color): Color {
        return if (this.luminance() > other.luminance()) other else this
    }
}

/**
 * 动态颜色方案数据类
 */
data class DynamicColorScheme(
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val error: Color,
    val onError: Color
)
