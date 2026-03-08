# This is a configuration file for ProGuard.
# http://proguard.sourceforge.net/index.html#manual/usage.html

-dontobfuscate
-optimizationpasses 5

# Keep our application classes
-keep class com.amll.droidmate.** { *; }

# Keep Jetpack Compose
-keep class androidx.** { *; }

# Keep Kotlin
-keepclassmembers class kotlin.Metadata {
    *** valueOf(...);
    *** values();
}

# Keep serialization
-keep class kotlinx.serialization.** { *; }
-keep class **$$serializer { *; }
-keepclassmembers class **$Companion {
    *** INSTANCE;
}

# Keep Ktor
-keep class io.ktor.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Timber
-keep class timber.** { *; }

# Platform calls Class.forName on types which do not exist on Android to determine platform.
-dontnote retrofit2.Platform
# Platform used when running on Java 8+
-dontwarn retrofit2.Platform$Java8
# Retain generic type information for use by reflection by converters and adapters.
-keepattributes Signature
# Retain declared checked exceptions for use by a custom Retrofit call adapter.
-keepattributes Exceptions
