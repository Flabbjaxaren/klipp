# MotionCam decoder in Klipp

MotionCam's official MCRAW decoder (https://github.com/mirsadm/motioncam-decoder, commit b931ec0, 2026-10-15,
Apache-2.0, © MotionCam – see `LICENSE`) reads MotionCam raw video (`.mcraw`): the container, the losslessly packed
Bayer frames, the per-frame metadata and the audio. `app/src/main/cpp/CMakeLists.txt` builds it (the three files in
`lib/`) with Klipp's JNI layer `app/src/main/cpp/mcraw_jni.cpp` into `libklipp_mcraw.so`; the app reads it through
`engine/Mcraw.kt`, and the develop (levels, lens shading, white balance, demosaic, colour and log curve, on the GPU) is
`engine/McrawConvert.kt`.

Trimmed to what that library needs: `lib/` unchanged, `thirdparty/nlohmann/json.hpp` (MIT) and the SIMDe headers
(MIT) that `lib/RawData.cpp` includes (the SSE2/SSE4.1 emulation, NEON on arm64). Removed: the example, the tests,
CMake, AudioFile, tinydng and the rest of SIMDe. No changes to the source.

When updating: copy `lib/` and `thirdparty/nlohmann/json.hpp`, then recompute the SIMDe headers the library needs
(e.g. `clang++ -std=c++17 -Ilib/include -Ithirdparty -M lib/RawData.cpp`, for the Android target) and build the app
(`./gradlew :app:assembleDebug` builds `libklipp_mcraw.so` for arm64-v8a).
