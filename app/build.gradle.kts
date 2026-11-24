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
        versionCode = 104
        versionName = "0.22.0-alpha.1"
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
            versionNameSuffix = "-designtest.281"
            // the export's test points (the copy's latch, a refused foreground start, the time limit): this build only
            buildConfigField("boolean", "TEST_HOOKS", "true")
            manifestPlaceholders["appLabel"] = "Klipp test"
            matchingFallbacks += "release"
        }
    }
    packaging {
        // cargo ndk also copies DeepFilterNet's own cdylib (libdf-<hash>.so) into jniLibs; its code is linked into
        // libklipp_bridge.so already and nothing loads it
        jniLibs.excludes += "**/libdf-*.so"
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
    // on-device models (exact word edges): ONNX Runtime, MIT
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
    // face following: MediaPipe Face Landmarker (Apache-2.0). Without Google's usage logging: its transport libraries
    // are left out, and the task runner's stats logger is replaced by MediaPipe's own no-op one (NoTaskStats below)
    implementation("com.google.mediapipe:tasks-vision:1.2.3") { exclude(group = "com.google.android.datatransport") }
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

/**
 * MediaPipe Tasks sends usage statistics (the app's id and version, the task, its running times) to Google through the
 * Firebase transport libraries, from TasksStatsLoggerFactory.create. Klipp sends nothing: that call is rewritten in the
 * bytecode to MediaPipe's own TasksStatsDummyLogger.create (same arguments; every method a no-op), and the transport
 * libraries are not in the app at all (excluded in the dependencies above).
 */
abstract class NoTaskStats : com.android.build.api.instrumentation.AsmClassVisitorFactory<com.android.build.api.instrumentation.InstrumentationParameters.None> {
    override fun isInstrumentable(classData: com.android.build.api.instrumentation.ClassData) =
        classData.className == "com.google.mediapipe.tasks.core.logging.TasksStatsLoggerFactory"
    override fun createClassVisitor(classContext: com.android.build.api.instrumentation.ClassContext, nextClassVisitor: org.objectweb.asm.ClassVisitor) =
        object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): org.objectweb.asm.MethodVisitor =
                object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    override fun visitMethodInsn(opcode: Int, owner: String?, name: String?, descriptor: String?, isInterface: Boolean) {
                        val pkg = "com/google/mediapipe/tasks/core/logging/"
                        if (opcode == org.objectweb.asm.Opcodes.INVOKESTATIC && owner == pkg + "TasksStatsProtoLogger" && name == "create")
                            super.visitMethodInsn(opcode, pkg + "TasksStatsDummyLogger", name, descriptor!!.replace("TasksStatsProtoLogger;", "TasksStatsDummyLogger;"), isInterface)
                        else super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
        }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(licenseAssets, LicenseAssets::output)
        variant.instrumentation.transformClassesWith(NoTaskStats::class.java, com.android.build.api.instrumentation.InstrumentationScope.ALL) {}
    }
}
