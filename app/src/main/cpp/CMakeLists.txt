# Speech recognition (automatic captions, text-based editing): whisper.cpp + ggml on the phone's ARM cores.
# (The Adreno GPU via ggml's OpenCL backend was tried: on a Snapdragon 8 Gen 3 it was ~90× slower than the CPU – whisper
# decodes token by token, too little work per GPU dispatch – and on an 8 Elite Gen 5 it crashed. CPU only.)
cmake_minimum_required(VERSION 3.22)
project(klipp_whisper C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

set(WHISPER_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
# source paths relative to the repository in the library (asserts print __FILE__), not the build machine's folders
get_filename_component(KLIPP_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../.. ABSOLUTE)
add_compile_options(-ffile-prefix-map=${KLIPP_ROOT}/=)

# ggml linked statically into one library. ARMv8.2 with dotprod (int8 matrices, quantised models) and fp16 – every
# Snapdragon 8 since the 855. No OpenMP (ggml's own thread pool is enough and needs no extra library).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
add_subdirectory(${WHISPER_ROOT}/ggml ggml)

add_library(klipp_whisper SHARED
    ${WHISPER_ROOT}/src/whisper.cpp
    whisper_jni.cpp
)
target_include_directories(klipp_whisper PRIVATE ${WHISPER_ROOT}/include ${WHISPER_ROOT}/src ${WHISPER_ROOT}/ggml/include)
target_compile_definitions(klipp_whisper PRIVATE WHISPER_VERSION="1.9.4")
target_compile_options(klipp_whisper PRIVATE -O3 -march=armv8.2-a+dotprod+fp16 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(klipp_whisper PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
find_library(LOG_LIB log)
target_link_libraries(klipp_whisper ggml ${LOG_LIB} android)

set(CRAW_ROOT ${KLIPP_ROOT}/third_party/craw)
add_library(klipp_craw SHARED
    craw_jni.cpp
    ${CRAW_ROOT}/src/Decoder.cpp
    ${CRAW_ROOT}/src/Reader.cpp
    ${CRAW_ROOT}/tools/common/Clip.cpp
)
target_compile_features(klipp_craw PRIVATE cxx_std_20)
target_include_directories(klipp_craw PRIVATE ${CRAW_ROOT}/include ${CRAW_ROOT}/tools)
# +crc: the decoder's CRC32C (every frame payload and decoded frame) uses the instructions instead of a byte table;
# unused clip-tool code (DNG/WAV writers) is dropped at link time
target_compile_options(klipp_craw PRIVATE -O3 -march=armv8-a+crc -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(klipp_craw PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections)

# MotionCam .mcraw reading (mcraw_jni.cpp) with MotionCam's own decoder library, motioncam-decoder (Apache-2.0,
# https://github.com/mirsadm/motioncam-decoder, commit b931ec0), vendored in third_party/motioncam-decoder: only its
# library sources are built here (its own CMake adds an example, tests and -Werror), with the nlohmann/json and SIMDe
# headers it includes (SIMDe turns its SSE2/SSE4.1 code into NEON on arm64)
set(MCRAW_ROOT ${KLIPP_ROOT}/third_party/motioncam-decoder)
add_library(klipp_mcraw SHARED
    ${MCRAW_ROOT}/lib/Decoder.cpp
    ${MCRAW_ROOT}/lib/RawData.cpp
    ${MCRAW_ROOT}/lib/RawData_Legacy.cpp
    mcraw_jni.cpp
)
target_include_directories(klipp_mcraw PRIVATE ${MCRAW_ROOT}/lib/include ${MCRAW_ROOT}/thirdparty)
target_compile_options(klipp_mcraw PRIVATE -O3 -fexceptions -frtti -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(klipp_mcraw PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
target_link_libraries(klipp_mcraw ${LOG_LIB})
