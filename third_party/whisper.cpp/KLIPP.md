# whisper.cpp in Klipp

whisper.cpp 1.9.4 (commit 927cfce, MIT, © The ggml authors – see `LICENSE` and `AUTHORS`) for on-device speech recognition (auto captions,
text-based editing), built by `app/src/main/cpp/CMakeLists.txt`.

Trimmed to what the app builds: `src/`, `include/` and `ggml/` with only the CPU and OpenCL backends. Removed: the
examples, bindings, test models, tests, CI scripts and every other ggml backend (CUDA, Vulkan, Metal, SYCL …).

## Changes

- `ggml/src/ggml-opencl/ggml-opencl.cpp` ("Klipp patch"): the buffer type's alignment, max size and alloc size and
  `supports_op` initialize the OpenCL device on demand (`ggml_cl_init`). whisper.cpp asks them while it loads the
  model, before the device is initialized; the device context was still null and the app crashed (SIGSEGV) on the
  Adreno GPU of a Snapdragon 8 Elite Gen 5.

Redo the patch when updating whisper.cpp.
