#!/bin/bash
# ProRes import (engine/ProRes.kt): a minimal FFmpeg and Klipp's JNI wrapper around it as ONE library,
# app/src/main/jniLibs/arm64-v8a/libklipp_prores.so. The repository commits no .so files – this script (and CI, see
# .github/workflows/build.yml) builds it from the pinned FFmpeg release tarball and the sources in this folder.
#
#   tools/ffmpeg/build.sh            # the Android library (arm64-v8a, API 29) into app/src/main/jniLibs
#   tools/ffmpeg/build.sh --host     # the same FFmpeg configuration for this computer + tools/ffmpeg/build/bench
#                                    # (decode speed check, see bench.c)
#
# What is in it: FFmpeg's ProRes decoder, the PCM decoders, the MOV/MP4 demuxer and the MOV muxer – nothing else
# (--disable-everything, no programs, no network, no external libraries). LGPL-2.1-or-later build: no --enable-gpl,
# no --enable-nonfree, no --enable-version3. FFmpeg is linked statically into libklipp_prores.so; anyone can relink it
# with a modified FFmpeg by editing FFMPEG_* below or pointing FFMPEG_TARBALL at their own tree's tarball and running
# this script again (LGPL-2.1 §6 – the whole of Klipp's source and this script are published, docs/licensing.md).
#
# Environment: ANDROID_NDK_HOME (default: $ANDROID_HOME/ndk/29.0.14206865, else ~/Library/Android/sdk/ndk/…),
# FFMPEG_TARBALL (an already downloaded ffmpeg-<version>.tar.xz – the source package carries one), KLIPP_FFMPEG_WORK
# (build folder, default tools/ffmpeg/build – git-ignored).
set -euo pipefail

FFMPEG_VERSION=9.0.2
FFMPEG_SHA256=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
FFMPEG_URL="https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
NDK_VERSION=29.0.14206865
API=29

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
WORK=${KLIPP_FFMPEG_WORK:-$HERE/build}
HOST=0
[ "${1:-}" = "--host" ] && HOST=1
mkdir -p "$WORK"
JOBS=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

# ---------------------------------------------------------------- source (pinned, hash checked)
TARBALL=${FFMPEG_TARBALL:-$WORK/ffmpeg-$FFMPEG_VERSION.tar.xz}
if [ ! -f "$TARBALL" ]; then
  echo "== downloading $FFMPEG_URL"
  curl -sSfL -o "$TARBALL.part" "$FFMPEG_URL"
  mv "$TARBALL.part" "$TARBALL"
fi
GOT=$(sha256 "$TARBALL")
if [ "$GOT" != "$FFMPEG_SHA256" ]; then
  echo "SHA-256 mismatch for $TARBALL: $GOT (expected $FFMPEG_SHA256)" >&2
  exit 1
fi
SRC="$WORK/ffmpeg-$FFMPEG_VERSION"
# unpacked only to configure and build FFmpeg (and removed again: only the installed libraries are needed to link)
unpack() { if [ ! -f "$SRC/configure" ]; then rm -rf "$SRC"; tar -xJf "$TARBALL" -C "$WORK"; fi; }

# ---------------------------------------------------------------- the configuration (same for both targets)
COMMON=(
  --disable-everything --disable-autodetect --disable-programs --disable-doc --disable-network
  --disable-avdevice --disable-avfilter --disable-swscale --disable-swresample
  --disable-debug --enable-static --disable-shared --enable-pic
  # ProRes 422 (apco apcs apcn apch) and 4444 (ap4h ap4x)
  --enable-decoder=prores
  # camera audio beside ProRes: PCM in every width and byte order a MOV carries
  --enable-decoder=pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f32be,pcm_s8,pcm_u8
  --enable-demuxer=mov
  # the intermediate file (HEVC from the phone's encoder + the mixed PCM) is written as MOV
  --enable-muxer=mov
)

if [ "$HOST" = 1 ]; then
  OUT="$WORK/host"
  if [ ! -f "$OUT/lib/libavformat.a" ]; then
    unpack
    rm -rf "$WORK/obj-host" && mkdir -p "$WORK/obj-host"
    (cd "$WORK/obj-host" && "$SRC/configure" --prefix="$OUT" "${COMMON[@]}" --extra-cflags="-O3" && make -j"$JOBS" && make install) > "$WORK/host.log" 2>&1 \
      || { tail -40 "$WORK/host.log"; exit 1; }
    rm -rf "$WORK/obj-host" "$SRC"
  fi
  cc -O3 -I"$OUT/include" -I"$HERE" -o "$WORK/bench" "$HERE/bench.c" \
    "$OUT/lib/libavformat.a" "$OUT/lib/libavcodec.a" "$OUT/lib/libavutil.a" -lm -lpthread
  echo "== $WORK/bench"
  exit 0
fi

# ---------------------------------------------------------------- Android (arm64-v8a)
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk/$NDK_VERSION" ]; then ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
  else ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/$NDK_VERSION"; fi
fi
case "$(uname -s)" in Darwin) HOSTTAG=darwin-x86_64 ;; *) HOSTTAG=linux-x86_64 ;; esac
TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOSTTAG"
[ -x "$TC/bin/clang" ] || { echo "NDK not found at $ANDROID_NDK_HOME" >&2; exit 1; }
CC="$TC/bin/aarch64-linux-android$API-clang"
OUT="$WORK/android-arm64"
# build paths out of the library (assert messages carry __FILE__), so the result doesn't depend on the build machine
MAP="-ffile-prefix-map=$SRC/=ffmpeg/ -ffile-prefix-map=$WORK/=build/ -ffile-prefix-map=$HERE/=tools/ffmpeg/"
STAMP="$OUT/.config-$(echo "${COMMON[*]} $FFMPEG_VERSION $NDK_VERSION" | cksum | cut -d' ' -f1)"
if [ ! -f "$STAMP" ]; then
  unpack
  rm -rf "$WORK/obj-android" "$OUT" && mkdir -p "$WORK/obj-android"
  echo "== configuring FFmpeg $FFMPEG_VERSION for android arm64 (log: $WORK/android.log)"
  (cd "$WORK/obj-android" && "$SRC/configure" --prefix="$OUT" "${COMMON[@]}" \
      --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile \
      --cc="$CC" --cxx="$CC++" --ld="$CC" --ar="$TC/bin/llvm-ar" --nm="$TC/bin/llvm-nm" --ranlib="$TC/bin/llvm-ranlib" \
      --strip="$TC/bin/llvm-strip" --sysroot="$TC/sysroot" \
      --extra-cflags="-O3 -fPIC -ffunction-sections -fdata-sections $MAP" \
    && make -j"$JOBS" && make install) > "$WORK/android.log" 2>&1 || { tail -40 "$WORK/android.log"; exit 1; }
  touch "$STAMP"
  rm -rf "$WORK/obj-android" "$SRC"
fi

DEST="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"
echo "== linking libklipp_prores.so"
# one library: Klipp's wrapper + FFmpeg (static, hidden – only the JNI functions are exported); 16 KB pages
"$CC" -shared -O3 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections $MAP -I"$OUT/include" -I"$HERE" \
  -o "$WORK/libklipp_prores.so" "$HERE/klipp_prores.c" \
  "$OUT/lib/libavformat.a" "$OUT/lib/libavcodec.a" "$OUT/lib/libavutil.a" \
  -lm -llog -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--version-script="$HERE/exports.map" \
  -Wl,-z,max-page-size=16384 -Wl,-z,noexecstack -Wl,--build-id=sha1
"$TC/bin/llvm-strip" --strip-unneeded "$WORK/libklipp_prores.so"
cp "$WORK/libklipp_prores.so" "$DEST/libklipp_prores.so"
ls -l "$DEST/libklipp_prores.so"
