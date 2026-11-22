# Corresponding Source for a Klipp release

Klipp is distributed as an APK (GitHub Releases now, maybe Google Play later). GPL-3.0 §6 requires the Corresponding
Source to be available to everyone who gets the APK; MPL-2.0 §3.2 (Symphonia) requires telling them where the
source of the MPL files is. Klipp uses **§6(d)**: the source is offered from a designated place (GitHub) at no further
charge, with clear directions next to the object code (the release page and the app), and kept available as long as
the APK is distributed. This process makes that concrete for each release.

## Per release

1. **Tag** the exact commit the release APK is built from: annotated tag `v<versionName>` ("Klipp x.y.z"), pushed.
   The app shows its commit (`BuildConfig.GIT_COMMIT`) and links to `https://github.com/Flabbjaxaren/klipp/tree/<commit>`
   (Settings → About → Licenses and source code), so the link never follows the moving `main`.
2. **Build** the release in a clean worktree of that tag with a freshly built bridge (as `README.md` → Building;
   `RUSTFLAGS=--remap-path-prefix=$HOME=~`). Check `python3 tools/licenses/inventory.py check --gradle-deps <deps>`.
3. **Source package**: `tools/source-release/make_source_package.sh v<versionName> <dir>` → `klipp-<version>-source.tar.gz`
   (+ `.sha256`): the repository at the tag, every Rust crate vendored (`cargo vendor`, so it builds offline), the
   Maven libraries' source jars, and `BUILDING-FROM-SOURCE.md`.
4. **Check the package builds**: unpack it in an empty folder and follow `BUILDING-FROM-SOURCE.md` with your own
   (debug) key. Compare `lib/arm64-v8a/libklipp_bridge.so` in the result with the release APK's (same toolchain →
   expected byte-identical) and the version name/code. Record the result in the release's build folder.
5. **Publish** the source package as an asset of the same GitHub release, next to the APK, and say in the release
   notes: "Source code: Klipp-<version>-source.tar.gz (Corresponding Source, GPL-3.0) – or the tag v<version>".
6. **Keep** every release's source asset as long as that APK is distributed (on Play: as long as that version code can
   be installed from there, and at least three years after the last copy was distributed, to stay on the safe side of
   §6(b)'s period).

Never put signing keys, `keystore.properties`, `local.properties`, API keys or other local files into the package: it
is made from `git archive` of the tag plus downloaded third-party source, never from a working folder.

## What the Corresponding Source contains, and the edge cases

| Part | In the package | Why |
|---|---|---|
| Klipp's code, build scripts (Gradle, Cargo, CMake), vendored Media3 and whisper.cpp | the repository at the tag | the work itself and "the scripts used to control its compilation" (§1) |
| Rust crates linked into `libklipp_bridge.so` (FilmCraft, craft-libs, Symphonia …) | `rust/vendor/` | statically linked into the binary; also satisfies MPL-2.0 for Symphonia |
| Git dependencies' whole repositories (FilmCraft, craft-libs) at the pinned commits | `third_party-sources/git/` | their license files, NOTICE and the files their crates include from outside the crate folder |
| Maven libraries in the dex (AndroidX, Compose, Kotlin, Guava …) | source jars, `third_party-sources/maven/` | bundled in the APK; their source is also public on Google Maven / Maven Central |
| FFmpeg in `libklipp_prores.so` (LGPL-2.1-or-later, statically linked) | the release tarball, `third_party-sources/ffmpeg/`, plus `tools/ffmpeg/build.sh` in the repository | LGPL-2.1 §6: the library's source and what is needed to relink it with a modified FFmpeg (docs/licensing.md) |
| `libc++_shared.so` (NDK) | not included – the NDK version is named | arguably a System Library of the compiler (GPL-3.0 §1). Edge case: see `docs/licensing-open-questions.md`. Its source is LLVM's, public |
| Assets (fonts, spektrafilm tables, Silero VAD) | in the repository | not covered by the GPL (own licenses), shipped as they are |
| Build tools (JDK, SDK, NDK, CMake, Rust, cargo-ndk, Gradle's plugins) | named with versions | generally available tools, not part of the Corresponding Source |

A plain "see GitHub" link is not assumed to be enough on its own: the package and its build check exist so the offer
is complete even if an upstream (a git dependency, a Maven repository) disappears.

## Last check

**v0.20.0-beta.1** (a9be050), 2026-11-18: `make_source_package.sh` → `klipp-0.20.0-beta.1-source.tar.gz` (96 MB, kept with
its `.sha256` in `Klipp-builds/0.20.0-beta.1-ui-internal/source-package/`; regenerated after the MISSING.txt explanation
was added – a tar's timestamps make each run's hash differ, the content is the same). Unpacked in an empty folder and built
as `BUILDING-FROM-SOURCE.md` says, the Rust bridge offline with an empty `CARGO_HOME` (only the vendored crates): the APK
builds – `se.gendut.klipp` 0.20.0-beta.1, versionCode 100. Against the release APK: `libc++_shared.so` and
`libandroidx.graphics.path.so` identical; `libklipp_bridge.so` differs in its embedded source paths (panic locations:
`rust/vendor/<crate>` instead of Cargo's registry folders) and the layout that follows from their lengths – not
byte-identical, which would need the same build paths; `libklipp_whisper.so` differs only in ggml's embedded build
commit ("unknown" without git) and the build id. The first attempt found that `cargo vendor` leaves out files a
crate includes from elsewhere in its repository (filmcraft-text's fonts) – the script now copies them and keeps each git
dependency's whole repository (`third_party-sources/git/`).

