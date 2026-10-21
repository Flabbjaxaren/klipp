// Media3 effect 1.11.1 (Apache 2.0, https://github.com/androidx/media) som källkod, med Klipps ändringar
// (se KLIPP.md). Ersätter androidx.media3:media3-effect i hela appen (dependencySubstitution i app/build.gradle.kts).
plugins {
    id("com.android.library")
}

android {
    namespace = "androidx.media3.effect"
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
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")
    compileOnly("org.checkerframework:checker-qual:3.49.0")
    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
    compileOnly("org.jetbrains.kotlin:kotlin-annotations-jvm:1.9.24")
}
