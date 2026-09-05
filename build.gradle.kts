// ============================================================================
// Gradle 顶层构建文件
// ============================================================================
// 
// **文件作用**：
// build.gradle.kts 是项目的根构建配置文件，定义了整个项目的全局设置。
// 它主要用于管理跨模块的插件版本和清理任务。
// 
// **与 settings.gradle.kts 的区别**：
// - settings.gradle.kts：项目结构配置（在构建之前运行）
// - build.gradle.kts：构建逻辑配置（定义任务和依赖）
// 
// **主要功能**：
// 1. 声明所有子模块使用的插件及其版本
// 2. 提供全局清理任务
// ============================================================================

// ==================== 插件声明 ====================
// 这些插件在子模块中使用，但版本在这里统一控制（避免版本冲突）
plugins {
    // Android Application 插件：用于编译 Android 应用
    // version: Android Gradle Plugin 版本，需与 Android Studio 版本匹配
    // apply false: 仅声明版本，不立即应用（在子模块中按需应用）
    id("com.android.application") version "9.4.0" apply false
    
    // Kotlin 序列化插件：支持 Kotlin 数据类的 JSON/XML 序列化
    // version: Kotlin 编译器版本
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
    
    // Jetpack Compose 插件：支持使用 Kotlin 编写声明式 UI
    // version: Compose 编译器版本
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
repositories {
    google()
}



