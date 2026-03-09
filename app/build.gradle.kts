import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val buildTimestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
    .format(Date())

android {
    namespace = "com.amll.droidmate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.amll.droidmate"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "Alpha $buildTimestamp" // 版本号
        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    
    lint {
        disable += listOf("FullBackupContent", "NetworkSecurityConfig")
    }

    applicationVariants.all {
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName =
                "AMLL-DroidMate-Alpha-$buildTimestamp.apk" // 版本号 APK
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.02"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.02"))

    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation("androidx.media:media:1.7.0")

    // Jetpack Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    
    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.5.0")
    
    // Palette for dynamic color extraction
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Networking
    implementation("io.ktor:ktor-client-core:2.3.6")
    implementation("io.ktor:ktor-client-okhttp:2.3.6")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.6")
    implementation("io.ktor:ktor-client-serialization:2.3.6")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.6")

    // JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Lyrics Helper (Unilyric)
    // 注: 实际项目应该使用他们发布的库或直接集成源码
    // implementation("com.github.apoint123:unilyric-android:main")

    // TTML parsing and display
    // 注: 应该集成amll-ttml-db的Android版本
    // implementation("com.github.amll-dev:amll-ttml-android:main")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Database (Room) - for caching lyrics
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
