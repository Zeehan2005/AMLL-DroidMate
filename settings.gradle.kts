// ============================================================================
// Gradle 项目级设置文件
// ============================================================================
// 
// **文件作用**：
// settings.gradle.kts 是 Gradle 项目的入口文件，负责配置整个项目的结构。
// 它在任何构建任务执行之前运行，用于初始化项目设置。
//
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    // 允许 Gradle 通过 Foojay 解析/下载 JDK 工具链，供 updateDaemonJvm 等任务使用
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// ==================== 项目基本信息 ====================
// 根项目名称（用于 IDE 显示和构建输出）
rootProject.name = "AMLL DroidMate"

// 包含的子模块
// ":app" 表示包含 app 目录下的模块（主要的 Android 应用模块）
include(":app")
