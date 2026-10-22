# whisper.cpp in Klipp

whisper.cpp 1.9.4 (commit 927cfce, MIT, © The ggml authors – see `LICENSE` and `AUTHORS`) for on-device speech recognition (auto captions,
text-based editing), built by `app/src/main/cpp/CMakeLists.txt`.

Trimmed to what the app builds: `src/`, `include/` and `ggml/` with only the CPU backend. Removed: the examples,
bindings, test models, tests, CI scripts and every GPU backend (CUDA, Vulkan, Metal, SYCL, OpenCL …).

One addition to the source, marked `[Klipp]`: `whisper_full_get_token_t_dtw()` (`include/whisper.h`,
`src/whisper.cpp`) returns a token's DTW timestamp mapped back to the original audio when VAD is on, the same way
`whisper_full_get_token_t0()` does (upstream only exposes the raw `t_dtw` in VAD-processed time). Redo it when
updating whisper.cpp.

The Adreno GPU (the OpenCL backend) was tried and dropped: on a Snapdragon 8 Gen 3 it was ~90× slower than the CPU
(whisper decodes token by token – too little work per GPU dispatch), and on a Snapdragon 8 Elite Gen 5 it crashed
(the buffer type read the device context before the device was initialized).
