import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DroidMate Android 应用构建配置
 *
 * 这个文件定义了 Android 应用的编译、打包和依赖配置。
 *
 * **主要功能**：
 * # 自定义 Gradle 任务（构建前端资源）
 * # Android 编译配置（SDK 版本、Java 版本等）
 * # 依赖管理（库版本控制）
 * # APK 命名和版本号生成
 *
 * **关键插件**：
 * - com.android.application: Android 应用插件
 * - kotlin("plugin.serialization"): Kotlin 序列化支持
 * - kotlin("plugin.compose"): Jetpack Compose 支持
 */

plugins {
    /** Android 应用插件：将 Kotlin 项目编译为 Android APK */
    id("com.android.application")
    
    /** Kotlin 序列化插件：支持 @Serializable 注解 */
    kotlin("plugin.serialization")
    
    /** Jetpack Compose 插件：支持 Compose UI */
    kotlin("plugin.compose")
}

abstract class BuildFrontendTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {

    // ==================== 任务输入输出配置 ====================

    /**
     * 前端源码目录（增量构建的输入，包含 src/ 下所有文件）
     *
     * Gradle 会监控这个目录的变化，只有文件变化时才执行任务
     * PathSensitivity.RELATIVE: 只关心相对路径，不关心绝对路径
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val frontendSrcDir: DirectoryProperty

    /**
     * styles.css 文件（位于 frontend/ 根目录，不在 frontendSrcDir 范围内）
     *
     * 单独列为输入，确保修改 styles.css 时也能触发重建
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stylesCss: RegularFileProperty

    /**
     * 输出目录（用于 up-to-date 检查）
     *
     * Gradle 会比较输出目录的时间戳，判断是否需要重新执行任务
     */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /**
     * 根项目目录（内部使用，不参与 up-to-date 检查）
     *
     * @Internal: 标记为内部属性，不影响任务状态
     */
    @get:Internal
    abstract val rootProjectDir: DirectoryProperty

    init {
        // 任务分组：在 Gradle 任务列表中归类到 "frontend" 组
        group = "frontend"
        // 任务描述：在 gradle tasks 命令中显示的说明
        description = "Build frontend assets using npm"
    }

    /**
     * 执行前端构建的核心逻辑
     *
     * 这个函数会在每次执行 buildFrontend 任务时被调用。
     * 它会根据操作系统选择合适的构建命令。
     */
    @TaskAction
    fun buildFrontend() {
        val rootDir = rootProjectDir.get().asFile
        val frontendDir = File(rootDir, "frontend")
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")

        if (!frontendDir.exists()) {
            logger.warn("Frontend directory not found, skipping build")
            return
        }

        // Step 1: 执行 npm install (确保依赖存在)
        val installCommand = if (isWindows) listOf("cmd", "/c", "npm install") else listOf("npm", "install")
        execOperations.exec {
            workingDir(frontendDir)
            commandLine(installCommand)
            // 不要忽略安装错误，确保依赖正确安装
            isIgnoreExitValue = false
        }

        // Step 2: 执行构建命令
        logger.info("Building frontend in ${frontendDir.absolutePath}")
        val buildCommand = if (isWindows) listOf("cmd", "/c", "npm run build") else listOf("npm", "run", "build")

        execOperations.exec {
            workingDir(frontendDir)
            commandLine(buildCommand)
        }

        // Step 3: 将构建产物从 frontend/dist 同步到 app/src/main/assets/amll
        val distDir = File(frontendDir, "dist")
        val assetsDir = outputDir.get().asFile

        if (distDir.exists()) {
            assetsDir.deleteRecursively()
            assetsDir.mkdirs()
            distDir.copyRecursively(assetsDir, overwrite = true)
            
            // 同时复制 index.html (Vite lib 模式不会自动复制它)
            val sourceIndex = File(frontendDir, "index.html")
            if (sourceIndex.exists()) {
                sourceIndex.copyTo(File(assetsDir, "index.html"), overwrite = true)
            }
            
            // 同时复制 styles.css (用于 WebView 的初始样式加载)
            val sourceStyles = File(frontendDir, "styles.css")
            if (sourceStyles.exists()) {
                sourceStyles.copyTo(File(assetsDir, "styles.css"), overwrite = true)
            }

            logger.info("Successfully synced frontend build to assets")
        } else {
            throw GradleException("Frontend build failed: dist directory not found at ${distDir.absolutePath}")
        }
    }
}

/** 注册并配置 buildFrontend 任务 */
val buildFrontendProvider = tasks.register("buildFrontend", BuildFrontendTask::class.java) {
    description = "Build frontend assets using npm and sync to Android assets directory"
    // 设置前端源码目录为输入 - src/ 下文件变化会触发重建
    frontendSrcDir.set(File(rootProject.projectDir, "frontend/src"))
    // styles.css 也在 frontend/ 根目录，单独列为输入
    stylesCss.set(File(rootProject.projectDir, "frontend/styles.css"))

    // 设置输出目录用于 up-to-date 检查
    outputDir.set(File(rootProject.projectDir, "app/src/main/assets/amll"))

    // 设置根项目目录供任务执行时使用
    rootProjectDir.set(rootProject.layout.projectDirectory)
}

/** 构建依赖关系：在 preBuild 之前先执行 buildFrontend
 *
 * preBuild 是 Android 构建的标准前置任务，在它之前先构建前端资源 */
tasks.named("preBuild") {
    dependsOn(buildFrontendProvider)
}



/** 版本号生成器（使用时间戳）
 *
 * 确保每次构建都重新计算时间戳
 *
 * 直接定义为一个函数，每次调用都会重新计算 */
fun getBuildTimestamp(): String {
    // 格式：yyyyMMddHHmmss (例如：20260401123456)
    return SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
}
/** 正式版版本号 */
val customVersion = ""

// ============================================================================
// Android 应用配置
// ============================================================================
android {
    signingConfigs {
        getByName("debug") {
            storeFile = file("\\sign.jks")
            storePassword = "AHC8jzFB0nsARVGPsHff"
            keyAlias = "key0"
            keyPassword = "AHC8jzFB0nsARVGPsHff"
        }
    }
    // ==================== 基本配置 ====================
    // 包名：应用的唯一标识符（用于 Google Play、安装等）
    namespace = "dev.amll.droidmate"
    // 编译 SDK 版本：使用最新 SDK 以获得新特性支持
    compileSdk = 37

    defaultConfig {
        // 应用 ID：设备的唯一标识（可以与 namespace 不同）
        applicationId = "dev.amll.droidmate"
        
        // 最低支持的 Android 版本（API 26 = Android 8.0）
        minSdk = 26
        
        // 目标 SDK：针对最新版本优化
        targetSdk = 37
        
        // 版本号：整数，每次发布递增（Google Play 要求）
        versionCode = 1
        
        // 版本名称：显示给用户的版本信息（使用时间戳格式）
        versionName = "Alpha ${getBuildTimestamp()}" // 开发版
//        versionName = customVersion // 正式版

    }

    buildTypes {
        // Release 构建类型（生产环境）
        release {
            // 是否启用代码压缩（ProGuard/R8）
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // Java 兼容性配置
    compileOptions {
        // 源代码 Java 版本
        sourceCompatibility = JavaVersion.VERSION_25
        // 目标字节码 Java 版本
        targetCompatibility = JavaVersion.VERSION_25
    }

    // Jetpack Compose 功能开关
    buildFeatures {
        // 启用 Compose UI 支持
        compose = true
    }

    buildToolsVersion = "37.0.0"
}

// ============================================================================
// APK 文件命名配置
// ============================================================================
// 使用现代 Gradle API (androidComponents) 为每个构建变体自定义 APK 文件名
// 格式：ScoreMuse-Alpha-时间戳.apk
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            // 重命名 APK 文件（仅适用于 VariantOutputImpl 类型）
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(
                // 版本号
                "AMLL-DroidMate-Alpha-${getBuildTimestamp()}.apk" // 开发版
//                "AMLL-DroidMate-$customVersion.apk" // 正式版
            )
        }
    }
}

// ============================================================================
// 依赖管理配置
// ============================================================================
dependencies {
    // ==================== Compose BOM (Bill of Materials) ====================
    // 使用 BOM 统一管理所有 Compose 相关库的版本，避免版本冲突
    implementation(platform("androidx.compose:compose-bom:latest.release"))
    implementation("androidx.compose.material3:material3:latest.release")
    androidTestImplementation(platform("androidx.compose:compose-bom:latest.release"))

    // ==================== AndroidX 核心库 ====================
    // Kotlin 扩展函数，提供更简洁的 API
    implementation("androidx.core:core-ktx:latest.release")
    
    // 启动屏支持（Android 12+ 原生启动屏 API）
    // 1.2.0 是最新稳定版（1.1.0 从未发布正式版）
    implementation("androidx.core:core-splashscreen:latest.release")
    
    // Lifecycle 运行时和 ViewModel（支持 Compose）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:latest.release")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:latest.release")
    
    // Activity Compose 集成（使 Activity 支持 Compose）
    implementation("androidx.activity:activity-compose:latest.release")
    
    // 媒体播放支持（用于获取音乐播放信息）
    implementation("androidx.media:media:latest.release")
    
    // 调色板提取（从专辑封面提取颜色）

    // ==================== Media3 UI 组件 ====================
    // 提供 DefaultTimeBar 和其他播放器控制组件
    implementation("androidx.media3:media3-ui:latest.release")
    
    // WebView 支持（用于 AMLL 歌词渲染）
    implementation("androidx.webkit:webkit:latest.release")
    // Jetpack WindowManager：Activity Embedding / 大屏分屏支持
    implementation("androidx.window:window:latest.release")
    // ==================== Jetpack Compose UI ====================
    // Compose UI 核心功能
    implementation("androidx.compose.ui:ui:latest.release")
    // UI 图形绘制（Canvas、路径等）
    implementation("androidx.compose.ui:ui-graphics:latest.release")
    // UI 工具预览（@Preview 注解支持）
    implementation("androidx.compose.ui:ui-tooling-preview:latest.release")
    // Material3 自适应布局（WindowSizeClass 等自适应基元）
    implementation("androidx.compose.material3.adaptive:adaptive:latest.release")
    // Google Material 设计组件（非 Compose 版本）
    implementation("com.google.android.material:material:latest.release")
    // Material 图标扩展库（更多图标选择）
    implementation("androidx.compose.material:material-icons-extended:latest.release")
    implementation("androidx.palette:palette:latest.release")
    
    // ==================== 图片加载 ====================
    // Coil: Kotlin 编写的图片加载库，支持 Compose
    implementation("io.coil-kt:coil-compose:latest.release")

    // ==================== Ktor 网络客户端（3.x 版本） ====================
    // Ktor 核心库（HTTP 客户端）
    //noinspection NewerVersionAvailable
    implementation("io.ktor:ktor-client-core:3.3.3")
    // OkHttp 引擎（Android 平台实现）
    //noinspection NewerVersionAvailable
    implementation("io.ktor:ktor-client-okhttp:3.3.3")
    // 内容协商（JSON/XML 序列化）
    //noinspection NewerVersionAvailable
    implementation("io.ktor:ktor-client-content-negotiation:3.3.3")
    // 序列化支持
    //noinspection NewerVersionAvailable
    implementation("io.ktor:ktor-client-serialization:3.3.3")
    // Kotlinx JSON 序列化器
    //noinspection NewerVersionAvailable
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3")

    // ==================== JSON 序列化 ====================
    // Kotlinx Serialization: Kotlin 原生的 JSON 序列化库
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:latest.release")


    // ==================== 协程（异步编程） ====================
    // Android 平台协程（包含主线程调度器）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:latest.release")
    // 协程核心库
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:latest.release")

    // ==================== 日志框架 ====================
    // Timber: JakeWharton 开发的轻量级日志库
    implementation("com.jakewharton.timber:timber:latest.release")
    // SLF4J Android: 将 SLF4J 日志转发到 Logcat，解决库依赖问题
    implementation("org.slf4j:slf4j-android:1.7.36")

    // ==================== 数据库（Room ORM） ====================
    // Room 运行时库（SQLite 对象映射）
    implementation("androidx.room:room-runtime:latest.release")
    // Room Kotlin 扩展（Flow、协程支持）
    implementation("androidx.room:room-ktx:latest.release")

    // ==================== 测试库 ====================
    // JUnit4: Java/Kotlin 单元测试框架
    testImplementation("junit:junit:latest.release")
    
    // 协程测试支持（TestDispatcher 等）
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:latest.release")

    // Ktor Mock 引擎（单元测试用）
    testImplementation("io.ktor:ktor-client-mock:latest.release")
    testImplementation("io.ktor:ktor-client-mock-jvm:latest.release")
    
    // Android 仪器化测试（在模拟器/真机上运行）
    androidTestImplementation("androidx.test.ext:junit:latest.release")
    androidTestImplementation("androidx.test.espresso:espresso-core:latest.release")
    
    // Compose UI 测试框架
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:latest.release")
    
    // 调试工具（Compose 预览和测试辅助）
    debugImplementation("androidx.compose.ui:ui-tooling:latest.release")
    debugImplementation("androidx.compose.ui:ui-test-manifest:latest.release")
}
repositories {
    google()
}
