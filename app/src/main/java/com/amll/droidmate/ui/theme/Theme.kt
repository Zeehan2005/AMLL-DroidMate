package com.amll.droidmate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6366F1),
    secondary = Color(0xFF8B5CF6),
    tertiary = Color(0xFFF97316),
    background = Color(0xFF1F2937),
    surface = Color(0xFF111827),
    error = Color(0xFFEF4444),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6366F1),
    secondary = Color(0xFF8B5CF6),
    tertiary = Color(0xFFF97316),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFEF4444),
)

/**
 * DroidMate主题
 * @param darkTheme 是否使用深色主题
 * @param dynamicColorScheme 可选的动态颜色方案（从专辑封面提取）
 * @param content Composable内容
 */
@Composable
fun DroidMateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColorScheme: DynamicColorScheme? = null,
    content: @Composable () -> Unit
) {
    val colorScheme = if (dynamicColorScheme != null) {
        // 使用动态颜色方案
        if (darkTheme) {
            darkColorScheme(
                primary = dynamicColorScheme.primary,
                onPrimary = dynamicColorScheme.onPrimary,
                primaryContainer = dynamicColorScheme.primary.copy(alpha = 0.3f),
                onPrimaryContainer = dynamicColorScheme.onPrimary,
                secondary = dynamicColorScheme.secondary,
                onSecondary = dynamicColorScheme.onSecondary,
                secondaryContainer = dynamicColorScheme.secondary.copy(alpha = 0.3f),
                onSecondaryContainer = dynamicColorScheme.onSecondary,
                tertiary = dynamicColorScheme.tertiary,
                onTertiary = dynamicColorScheme.onTertiary,
                tertiaryContainer = dynamicColorScheme.tertiary.copy(alpha = 0.3f),
                onTertiaryContainer = dynamicColorScheme.onTertiary,
                background = dynamicColorScheme.background,
                onBackground = dynamicColorScheme.onBackground,
                surface = dynamicColorScheme.surface,
                onSurface = dynamicColorScheme.onSurface,
                surfaceVariant = dynamicColorScheme.surfaceVariant,
                onSurfaceVariant = dynamicColorScheme.onSurfaceVariant,
                error = dynamicColorScheme.error,
                onError = dynamicColorScheme.onError
            )
        } else {
            lightColorScheme(
                primary = dynamicColorScheme.primary,
                onPrimary = dynamicColorScheme.onPrimary,
                primaryContainer = dynamicColorScheme.primary.copy(alpha = 0.1f),
                onPrimaryContainer = dynamicColorScheme.primary,
                secondary = dynamicColorScheme.secondary,
                onSecondary = dynamicColorScheme.onSecondary,
                secondaryContainer = dynamicColorScheme.secondary.copy(alpha = 0.1f),
                onSecondaryContainer = dynamicColorScheme.secondary,
                tertiary = dynamicColorScheme.tertiary,
                onTertiary = dynamicColorScheme.onTertiary,
                tertiaryContainer = dynamicColorScheme.tertiary.copy(alpha = 0.1f),
                onTertiaryContainer = dynamicColorScheme.tertiary,
                background = dynamicColorScheme.background,
                onBackground = dynamicColorScheme.onBackground,
                surface = dynamicColorScheme.surface,
                onSurface = dynamicColorScheme.onSurface,
                surfaceVariant = dynamicColorScheme.surfaceVariant,
                onSurfaceVariant = dynamicColorScheme.onSurfaceVariant,
                error = dynamicColorScheme.error,
                onError = dynamicColorScheme.onError
            )
        }
    } else {
        // 使用默认颜色方案
        if (darkTheme) DarkColors else LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
