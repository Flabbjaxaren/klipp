import java.util.Properties
import javax.inject.Inject

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

/**
 * The commit this build is made from – shown in Settings → About with a link to that exact source
 * (GPL-3.0 §6). From git, or from SOURCE_COMMIT in a source package (no .git there). "-dirty" when the working tree has
 * changes the commit doesn't have (such a build's source is not exactly the linked one).
 */
val gitCommit: String = run {
    fun git(vararg a: String) = runCatching {
        val p = ProcessBuilder("git", *a).directory(rootDir).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().takeIf { p.waitFor() == 0 }
    }.getOrNull()
    val head = git("rev-parse", "HEAD")
    when {
        head != null -> head + if (git("status", "--porcelain", "--untracked-files=no").isNullOrEmpty()) "" else "-dirty"
        rootDir.resolve("SOURCE_COMMIT").isFile -> rootDir.resolve("SOURCE_COMMIT").readText().trim()
        else -> "unknown"
    }
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
        versionCode = 100
        versionName = "0.20.0-beta.1"
        ndk { abiFilters += "arm64-v8a" }
        // speech recognition is always built optimized (the debug build's -O0 would be tens of times slower)
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared") } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "Klipp"
        // the export's test points: off everywhere but the designTest build
        buildConfigField("boolean", "TEST_HOOKS", "false")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
    }
    // the GPU tests on a phone (src/androidTest): their own app id beside the installed Klipp, so running them never
    // replaces it or touches its projects (`./gradlew connectedGpuTestAndroidTest`)
    testBuildType = "gpuTest"
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
        create("gpuTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".gputest"
            matchingFallbacks += "debug"
        }
        // a trial of the new menu design: the release build under its own id and name, installed beside Klipp
        // (`./gradlew assembleDesignTest`)
        create("designTest") {
            initWith(getByName("release"))
            applicationIdSuffix = ".designtest"
            versionNameSuffix = "-designtest.203"
            // the export's test points (the copy's latch, a refused foreground start, the time limit): this build only
            buildConfigField("boolean", "TEST_HOOKS", "true")
            manifestPlaceholders["appLabel"] = "Klipp test"
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
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
    // Media3 ExoPlayer as source code with Klipp's changes (third_party/media3-exoplayer); replaces the Maven module everywhere
    implementation(project(":media3-exoplayer"))
    // Media3 transformer as source code with Klipp's changes (third_party/media3-transformer); replaces the Maven module everywhere
    implementation(project(":media3-transformer"))
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

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// Transformer, ExoPlayer etc. depend on media3-effect: all of them get the local module
configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.media3:media3-effect")).using(project(":media3-effect"))
        substitute(module("androidx.media3:media3-transformer")).using(project(":media3-transformer"))
        substitute(module("androidx.media3:media3-exoplayer")).using(project(":media3-exoplayer"))
    }
}

// LICENSES/ (license texts, notices, inventory.json – see docs/licensing.md) goes into the APK as assets/licenses/, so
// the license screen works offline and every copy carries the notices.
abstract class LicenseAssets : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val licenses: DirectoryProperty
    @get:OutputDirectory abstract val output: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations
    @TaskAction fun copy() {
        fs.sync { from(licenses); into(output.dir("licenses")) }
    }
}
val licenseAssets = tasks.register<LicenseAssets>("licenseAssets") { licenses.set(rootProject.layout.projectDirectory.dir("LICENSES")) }
androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(licenseAssets, LicenseAssets::output) }
}
