// Media3 ExoPlayer 1.11.1 (Apache 2.0, https://github.com/androidx/media) as source code, with Klipp's changes
// (see KLIPP.md). Replaces androidx.media3:media3-exoplayer in the whole app (dependencySubstitution in app/build.gradle.kts).
plugins {
    id("com.android.library")
}

android {
    namespace = "androidx.media3.exoplayer"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false }
}

dependencies {
    val media3 = "1.11.1"
    api("androidx.media3:media3-common:$media3")
    api("androidx.media3:media3-container:$media3")
    api("androidx.media3:media3-datasource:$media3")
    api("androidx.media3:media3-decoder:$media3")
    api("androidx.media3:media3-extractor:$media3")
    api("androidx.media3:media3-database:$media3")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.exifinterface:exifinterface:1.3.6")
    compileOnly("org.checkerframework:checker-qual:3.49.0")
    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
    compileOnly("org.jetbrains.kotlin:kotlin-annotations-jvm:1.9.24")
}
