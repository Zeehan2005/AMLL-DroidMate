# This is a configuration file for ProGuard.
# http://proguard.sourceforge.net/index.html#manual/usage.html

-dontobfuscate
-optimizationpasses 5

# Keep our application entry points and data models.  Avoid overly broad rules affecting 100+ classes.
-keep class com.amll.droidmate.MainActivity { *; }
-keep class com.amll.droidmate.service.** { *; }
-keep class com.amll.droidmate.ui.screens.** { *; }
-keep class com.amll.droidmate.domain.model.** { *; }
# (remove blanket package rule to let shrinker trim unused classes)

# Keep Jetpack Compose (previous rule matched no members in lint analysis; remove or narrow if needed)
#-keep class androidx.** { *; }

# Keep Kotlin
-keepclassmembers class kotlin.Metadata {
    *** valueOf(...);
    *** values();
}

# Keep serialization (allow shrinking so rule doesn't count as overly broad)
-keep,allowshrinking class kotlinx.serialization.** { *; }
-keep,allowshrinking class **$$serializer { *; }
-keepclassmembers class **$Companion {
    *** INSTANCE;
}

# Keep Ktor (allow shrinking)
-keep,allowshrinking class io.ktor.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
# (no keep rule; allow shrinker to remove unused okhttp classes)

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
