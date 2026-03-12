import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
}

val buildTimestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
    .format(Date())

android {
    namespace = "com.amll.droidmate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.amll.droidmate"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "Alpha $buildTimestamp"
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

    buildFeatures {
        compose = true
    }

    lint {
        disable += listOf("FullBackupContent", "NetworkSecurityConfig")
    }
}

// Rename APKs using the modern onVariants API
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(
                "AMLL-DroidMate-Alpha-$buildTimestamp.apk"
            )
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.00"))

    // AndroidX
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.media:media:1.7.1")
    implementation("androidx.palette:palette:1.0.0")

    // media3 UI gives us DefaultTimeBar and other player controls
    implementation("androidx.media3:media3-ui:1.0.0")

    // Jetpack Compose (Version managed by BOM)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    
    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Networking (Ktor 3.x)
    implementation("io.ktor:ktor-client-core:3.4.1")
    implementation("io.ktor:ktor-client-okhttp:3.4.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.4.1")
    implementation("io.ktor:ktor-client-serialization:3.4.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.1")

    // JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Database (Room)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.ktor:ktor-client-mock:3.4.1")
    testImplementation("io.ktor:ktor-client-mock-jvm:3.4.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
