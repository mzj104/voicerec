cmake_minimum_required(VERSION 3.22.1)

project("voicerec")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add compiler flags
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -fPIC")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG -fPIC")

# Disable unnecessary whisper features
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# Clone and build whisper.cpp
include(FetchContent)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        master
    GIT_SHALLOW    TRUE
    GIT_PROGRESS   TRUE
)

# Set whisper build options
set(WHISPER_NO_AVX ON CACHE BOOL "" FORCE)
set(WHISPER_NO_AVX2 ON CACHE BOOL "" FORCE)
set(WHISPER_NO_FMA ON CACHE BOOL "" FORCE)
set(WHISPER_NO_F16C ON CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(whisper)

# Add our native library
add_library(voicerec SHARED
    native-lib.cpp
    audio_utils.cpp
)

# Find required libraries
find_library(log-lib log)

# Include whisper headers
target_include_directories(voicerec PRIVATE
    ${whisper_SOURCE_DIR}/include
    ${whisper_SOURCE_DIR}
)

# Include ggml headers if they exist
if(EXISTS "${whisper_SOURCE_DIR}/ggml/include")
    target_include_directories(voicerec PRIVATE ${whisper_SOURCE_DIR}/ggml/include)
endif()

if(EXISTS "${whisper_SOURCE_DIR}/ggml")
    target_include_directories(voicerec PRIVATE ${whisper_SOURCE_DIR}/ggml)
endif()

# Link libraries
target_link_libraries(voicerec
    whisper
    ${log-lib}
    android
)

# ============================================
# llama.cpp - Qwen2.5 GGUF model support
# ============================================
# Build llama.cpp in a completely isolated way to avoid ggml conflicts

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_NO_AVX ON CACHE BOOL "" FORCE)
set(LLAMA_NO_AVX2 ON CACHE BOOL "" FORCE)
set(LLAMA_NO_FMA ON CACHE BOOL "" FORCE)
set(LLAMA_NO_F16C ON CACHE BOOL "" FORCE)
set(LLAMA_NO_AVX512 ON CACHE BOOL "" FORCE)
set(LLAMA_NO_AVX512_VBMI2 ON CACHE BOOL "" FORCE)
set(LLAMA_NO_AVX512_VNNI ON CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS_OFF ON CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        b3879
    GIT_SHALLOW    TRUE
)

# Only populate the sources, don't build yet
FetchContent_Populate(llama)

# Build llama.cpp with explicit include paths that exclude whisper's ggml
set(LLAMA_SRC_DIR ${llama_SOURCE_DIR})

# Collect ggml source files - only CPU-only sources
file(GLOB GGML_SOURCES
    ${LLAMA_SRC_DIR}/ggml/src/*.cpp
    ${LLAMA_SRC_DIR}/ggml/src/*.c
    ${LLAMA_SRC_DIR}/ggml/src/ggml-cpu/*.cpp
    ${LLAMA_SRC_DIR}/ggml/src/ggml-cpu/*.c
    ${LLAMA_SRC_DIR}/ggml/src/ggml-cpu/amx/*.cpp
)

# Filter out GPU-related and optional dependency files
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*ggml-common.c$")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*ggml-opencl.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*ggml-cuda.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*ggml-metal.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*ggml-vulkan.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*ggml-sycl.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*ggml-kompute.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*ggml-blas.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*ggml-cann.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*llamafile.*")

# Create ggml library
add_library(ggml_llama STATIC ${GGML_SOURCES})

target_include_directories(ggml_llama PRIVATE
    ${LLAMA_SRC_DIR}/ggml/include
    ${LLAMA_SRC_DIR}/ggml/src
)

target_compile_definitions(ggml_llama PRIVATE
    GGML_USE_CPU
)

# Create llama library manually with isolated include paths
add_library(llama STATIC
    ${LLAMA_SRC_DIR}/src/llama.cpp
    ${LLAMA_SRC_DIR}/src/llama-vocab.cpp
    ${LLAMA_SRC_DIR}/src/llama-grammar.cpp
    ${LLAMA_SRC_DIR}/src/llama-sampling.cpp
    ${LLAMA_SRC_DIR}/src/unicode.cpp
    ${LLAMA_SRC_DIR}/src/unicode-data.cpp
)

# CRITICAL: Use ONLY llama's own ggml headers
target_include_directories(llama PRIVATE
    ${LLAMA_SRC_DIR}/ggml/include
    ${LLAMA_SRC_DIR}/ggml/src
    ${LLAMA_SRC_DIR}/src
    ${LLAMA_SRC_DIR}/include
)

target_compile_definitions(llama PRIVATE
    GGML_USE_CPU
)

target_link_libraries(llama
    ggml_llama
    ${log-lib}
)

# Llama JNI library - ISOLATED from whisper
add_library(voicerec_llama SHARED
    llama_jni.cpp
)

# CRITICAL: Only include llama's own ggml headers, NOT whisper's
target_include_directories(voicerec_llama PRIVATE
    ${LLAMA_SRC_DIR}/include
    ${LLAMA_SRC_DIR}/src
    ${LLAMA_SRC_DIR}/ggml/include
    ${LLAMA_SRC_DIR}/ggml/src
)

# Link llama library (which includes ggml_llama)
target_link_libraries(voicerec_llama
    llama
    ${log-lib}
    android
)
