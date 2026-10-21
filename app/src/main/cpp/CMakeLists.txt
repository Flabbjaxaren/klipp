# Speech recognition (automatic subtitles): whisper.cpp + ggml, built for the phone's ARM cores and, on Snapdragon,
# the Adreno GPU.
cmake_minimum_required(VERSION 3.22)
project(klipp_whisper C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

set(WHISPER_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
set(OPENCL_HEADERS ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/OpenCL-Headers)

# ggml backends as separate libraries, loaded at run time (whisper_jni.cpp): the CPU backend always, the OpenCL backend
# only where the phone has an OpenCL driver (Qualcomm's on Snapdragon). Without one, dlopen fails and the CPU is used.
# CPU: ARMv8.2 with dotprod (int8 matrices, quantised models) and fp16 – every Snapdragon 8 since the 855. No OpenMP
# (ggml's own thread pool is enough and needs no extra library).
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
# Adreno: Qualcomm's matmul kernels, compiled into the library (no kernel files at run time)
set(GGML_OPENCL ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)

# libOpenCL.so to link against only (opencl_stub.c); excluded from the APK (build.gradle.kts) so that the phone's own
# driver is loaded instead
add_library(OpenCL SHARED opencl_stub.c)
set(OpenCL_INCLUDE_DIR ${OPENCL_HEADERS} CACHE PATH "" FORCE)
set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)
add_subdirectory(${WHISPER_ROOT}/ggml ggml)
target_compile_definitions(ggml-opencl PRIVATE CL_TARGET_OPENCL_VERSION=300)

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
