// Media3 transformer 1.11.1 (Apache 2.0, https://github.com/androidx/media) as source code, with Klipp's changes
// (see KLIPP.md). Replaces androidx.media3:media3-transformer in the whole app (dependencySubstitution in app/build.gradle.kts).
plugins {
    id("com.android.library")
}

android {
    namespace = "androidx.media3.transformer"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false }
}

dependencies {
    val media3 = "1.11.1"
    api(project(":media3-exoplayer"))
    api(project(":media3-effect"))
    api("androidx.media3:media3-muxer:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-container:$media3")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    compileOnly("org.checkerframework:checker-qual:3.49.0")
    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
    compileOnly("org.jetbrains.kotlin:kotlin-annotations-jvm:1.9.24")
}

// ExoPlayer etc. depend on media3-effect: they get the local module
configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.media3:media3-effect")).using(project(":media3-effect"))
        substitute(module("androidx.media3:media3-exoplayer")).using(project(":media3-exoplayer"))
    }
}
