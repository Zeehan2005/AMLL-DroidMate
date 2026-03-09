package com.amll.droidmate.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import timber.log.Timber

/**
 * 全局动态主题管理器
 * 管理从专辑封面提取的动态颜色方案，供所有Activity使用
 */
object DynamicThemeManager {
    private val _dynamicColorScheme = mutableStateOf<DynamicColorScheme?>(null)
    
    /**
     * 当前的动态颜色方案
     */
    val dynamicColorScheme: DynamicColorScheme?
        get() = _dynamicColorScheme.value
    
    /**
     * 更新动态颜色方案
     */
    fun updateColorScheme(scheme: DynamicColorScheme?) {
        if (_dynamicColorScheme.value != scheme) {
            _dynamicColorScheme.value = scheme
            if (scheme != null) {
                Timber.d("Dynamic color scheme updated globally")
            } else {
                Timber.d("Dynamic color scheme cleared, using default theme")
            }
        }
    }
    
    /**
     * 清除动态颜色方案，恢复默认主题
     */
    fun clearColorScheme() {
        updateColorScheme(null)
    }
    
    /**
     * 获取动态颜色方案的 Composable State
     * 用于在 Composable 函数中观察变化
     */
    @Composable
    fun observeColorScheme(): State<DynamicColorScheme?> {
        return _dynamicColorScheme
    }
}
