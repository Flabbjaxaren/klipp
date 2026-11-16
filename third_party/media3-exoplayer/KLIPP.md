# Media3 ExoPlayer 1.11.1 – Klipp's version

The source code of `androidx.media3:media3-exoplayer:1.11.1` (Apache License 2.0, © The Android Open Source Project,
https://github.com/androidx/media), with a few changes. The module replaces the Maven artifact throughout the app
(`dependencySubstitution` in `app/build.gradle.kts` and `third_party/media3-transformer/build.gradle.kts`). On a Media3
upgrade: take the new version's sources (the `-sources.jar` of the same version), its AAR's `res/`, manifest and
`proguard.txt` (here `consumer-rules.pro`), and redo the changes below.

## Changes

(none yet)

The resources (`res/`) and the manifest come from the AAR file of the same version.
