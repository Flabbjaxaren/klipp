import java.util.Properties

/**
 * The release key lives outside the repository: ~/.klipp/keystore.properties (storeFile, storePassword, keyAlias,
 * keyPassword) – or another file named by the KLIPP_KEYSTORE environment variable. Without it (CI, other machines) the
 * release build is signed with the debug key; -Pklipp.debugSigned=true forces that (to update an install that still
 * has the old debug signature).
 */
val releaseKey: Properties? = run {
    val f = System.getenv("KLIPP_KEYSTORE")?.let { file(it) } ?: file("${System.getProperty("user.home")}/.klipp/keystore.properties")
    if (!f.isFile || project.findProperty("klipp.debugSigned") == "true") null
    else Properties().apply { f.inputStream().use { load(it) } }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "se.gendut.klipp"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "se.gendut.klipp"
        minSdk = 29
        targetSdk = 36
        versionCode = 35
        versionName = "0.17.6"
        ndk { abiFilters += "arm64-v8a" }
        // speech recognition is always built optimized (the debug build's -O0 would be tens of times slower)
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared") } }
    }
    ndkVersion = "29.0.14206865"
    // per-app language in the system settings (Android 13+): English (default) and Swedish
    androidResources { generateLocaleConfig = true }
    lint {
        // Klipp deliberately builds on Media3's unstable APIs (and vendors media3-effect), see third_party/media3-effect
        disable += "UnsafeOptInUsageError"
    }
    // whisper.cpp (automatic captions), see src/main/cpp
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Release: R8 (shrinks and optimizes the code), ahead-of-time compilation and the baseline profiles from Compose and
        // Media3 – several times faster startup and smoother UI than the debug build. Signed with the release key
        // (see releaseKey above); an app signed with another key can't be updated by it – Android requires the same key.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
            it.maxHeapSize = "3g"
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    // Media3 effect as source code with Klipp's changes (third_party/media3-effect); replaces the Maven module everywhere
    implementation(project(":media3-effect"))
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-ui-compose:$media3")
    // MediaExtractorCompat: the tracker reads the file with the same extractors (and timestamps) as playback
    implementation("androidx.media3:media3-inspector:$media3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation(bom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.76.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Transformer, ExoPlayer etc. depend on media3-effect: all of them get the local module
configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.media3:media3-effect")).using(project(":media3-effect"))
    }
}
