pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2")
    }
}
rootProject.name = "Klipp"
include(":app")
// Media3 effect as source code with Klipp's changes (floating point through the whole video chain), see third_party/media3-effect/KLIPP.md
include(":media3-effect")
project(":media3-effect").projectDir = file("third_party/media3-effect")
