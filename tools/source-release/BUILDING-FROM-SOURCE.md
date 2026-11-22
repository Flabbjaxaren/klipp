# Building Klipp from this source package

This archive is the Corresponding Source of one Klipp release: the repository at the commit in `SOURCE_COMMIT`,
the source of every Rust crate linked into `libklipp_bridge.so` (`rust/vendor/`, with `rust/.cargo/config.toml`
pointing Cargo at it), the source jars of the Maven libraries in the APK (`third_party-sources/maven/`), and FFmpeg's
release tarball for the ProRes import (`third_party-sources/ffmpeg/`, LGPL-2.1-or-later).
Klipp is free software under the GNU GPL v3 (`LICENSE`); third-party parts keep their own licenses
(`THIRD_PARTY.md`, `LICENSES/`).

## Tools (not part of the source)

- JDK 21
- Android SDK with platform 37, Android NDK **29.0.14206865** and CMake **3.31.6**
  (`sdkmanager "ndk;29.0.14206865" "cmake;3.31.6"`)
- Rust (stable) with `rustup target add aarch64-linux-android` and `cargo install cargo-ndk`

## Steps

1. Build the Rust bridge from the vendored crates (no network needed):

   ```
   cd rust
   export ANDROID_NDK_HOME=<sdk>/ndk/29.0.14206865
   RUSTFLAGS="--remap-path-prefix=$HOME=~" cargo ndk -t arm64-v8a --platform 29 -o ../app/src/main/jniLibs build --release --offline
   cd ..
   ```

2. Build the ProRes import's library (`libklipp_prores.so`: FFmpeg + Klipp's wrapper) from the included FFmpeg
   tarball (no network needed; a few minutes). To use a modified FFmpeg, point `FFMPEG_TARBALL` at your own tarball and
   change `FFMPEG_SHA256` in the script:

   ```
   export ANDROID_NDK_HOME=<sdk>/ndk/29.0.14206865
   FFMPEG_TARBALL=third_party-sources/ffmpeg/ffmpeg-9.0.2.tar.xz tools/ffmpeg/build.sh
   ```

3. Build the APK. Gradle downloads the Maven libraries (their sources are in `third_party-sources/maven/`) and builds
   whisper.cpp from `third_party/whisper.cpp` with CMake:

   ```
   echo "sdk.dir=<sdk>" > local.properties
   ./gradlew assembleRelease -Pklipp.debugSigned=true
   ```

   The APK is `app/build/outputs/apk/release/app-release.apk`.

## Signing

Android installs an update only if it is signed with the same key as the installed app. This package contains no keys.
`-Pklipp.debugSigned=true` signs with your debug key. To use your own release key, put its path, alias and passwords in
`~/.klipp/keystore.properties` (see `README.md` → Signing) and leave the flag out.

An APK you build yourself has a different signer than the published Klipp, so it cannot update it. Either install it
after uninstalling the published app (that removes Klipp's projects – back them up first), or change `applicationId`
in `app/build.gradle.kts` to install your build next to the published one.
