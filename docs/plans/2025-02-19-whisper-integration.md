# Whisper语音识别集成实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 用whisper.cpp完全替换Vosk实现，使用Q8量化模型实现离线中文语音识别

**Architecture:** Kotlin服务层 + JNI接口 + C++原生代码(whisper.cpp)，录音后批量识别模式

**Tech Stack:** whisper.cpp, JNI, Android NDK, CMake, Kotlin Coroutines

---

## Task 1: 准备C++目录结构

**Files:**
- Create: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/native-lib.cpp`
- Create: `app/src/main/cpp/audio_utils.cpp`
- Create: `app/src/main/cpp/audio_utils.h`

**Step 1: 创建cpp目录**

```bash
mkdir -p "C:\Users\A.FYYX-2020PYSOXM\AndroidStudioProjects\voicerec\app\src\main\cpp"
```

**Step 2: 验证目录创建**

```bash
ls "C:\Users\A.FYYX-2020PYSOXM\AndroidStudioProjects\voicerec\app\src\main\cpp"
```

Expected: 空目录或无错误

**Step 3: 创建CMakeLists.txt**

完整文件内容 (参考 `F:\whisper-android-demo\app\src\main\cpp\CMakeLists.txt`):

```cmake
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
```

**Step 4: 创建audio_utils.h**

完整文件内容:

```cpp
#ifndef AUDIO_UTILS_H
#define AUDIO_UTILS_H

#include <vector>

// Convert 16-bit PCM audio to float32 format
std::vector<float> convertToFloat32(const std::vector<int16_t>& audio16);

// Resample audio to target sample rate
std::vector<float> resampleAudio(const std::vector<float>& audio, int source_rate, int target_rate);

// Apply high-pass filter to remove low frequency noise
void highPassFilter(std::vector<float>& audio, float cutoff_freq, int sample_rate);

// Apply noise reduction using spectral subtraction
void spectralSubtractionDenoise(std::vector<float>& audio, int sample_rate);

// Normalize audio volume to optimal range
void normalizeAudio(std::vector<float>& audio);

// Apply voice enhancement filter
void voiceEnhancementFilter(std::vector<float>& audio, int sample_rate);

// Detect voice activity using advanced algorithm
bool detectVoiceActivity(const std::vector<float>& audio, int sample_rate);

// Apply adaptive noise gate
void adaptiveNoiseGate(std::vector<float>& audio, float threshold_ratio = 0.1f);

#endif // AUDIO_UTILS_H
```

**Step 5: 创建audio_utils.cpp**

完整文件内容 (参考 `F:\whisper-android-demo\app\src\main\cpp\audio_utils.cpp`):

```cpp
#include "audio_utils.h"
#include <cmath>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "AudioUtils"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

std::vector<float> convertToFloat32(const std::vector<int16_t>& audio16) {
    std::vector<float> audio_f32;
    audio_f32.reserve(audio16.size());

    for (int16_t sample : audio16) {
        audio_f32.push_back(static_cast<float>(sample) / 32768.0f);
    }

    return audio_f32;
}

std::vector<float> resampleAudio(const std::vector<float>& audio, int source_rate, int target_rate) {
    if (source_rate == target_rate) {
        return audio;
    }

    const float ratio = static_cast<float>(source_rate) / target_rate;
    const size_t new_size = static_cast<size_t>(audio.size() / ratio);
    std::vector<float> resampled;
    resampled.reserve(new_size);

    for (size_t i = 0; i < new_size; ++i) {
        const float index = i * ratio;
        const size_t index1 = static_cast<size_t>(index);
        const size_t index2 = std::min(index1 + 1, audio.size() - 1);
        const float frac = index - index1;

        const float sample = audio[index1] * (1.0f - frac) + audio[index2] * frac;
        resampled.push_back(sample);
    }

    LOGI("Resampled audio from %d Hz to %d Hz, size: %zu -> %zu",
         source_rate, target_rate, audio.size(), resampled.size());

    return resampled;
}

void highPassFilter(std::vector<float>& audio, float cutoff_freq, int sample_rate) {
    const float rc = 1.0f / (cutoff_freq * 2.0f * M_PI);
    const float dt = 1.0f / sample_rate;
    const float alpha = rc / (rc + dt);

    if (audio.empty()) return;

    float prev_input = audio[0];
    float prev_output = audio[0];

    for (size_t i = 1; i < audio.size(); ++i) {
        const float output = alpha * (prev_output + audio[i] - prev_input);
        prev_input = audio[i];
        prev_output = output;
        audio[i] = output;
    }
}

void spectralSubtractionDenoise(std::vector<float>& audio, int sample_rate) {
    if (audio.empty()) return;

    float rms = 0.0f;
    for (float sample : audio) {
        rms += sample * sample;
    }
    rms = std::sqrt(rms / audio.size());

    float noise_threshold = rms * 0.1f;

    for (float& sample : audio) {
        if (std::abs(sample) < noise_threshold) {
            sample *= 0.1f;
        }
    }

    LOGI("Applied spectral subtraction denoising, RMS: %f, threshold: %f", rms, noise_threshold);
}

void normalizeAudio(std::vector<float>& audio) {
    if (audio.empty()) return;

    float max_val = 0.0f;
    for (float sample : audio) {
        max_val = std::max(max_val, std::abs(sample));
    }

    if (max_val > 0.0f) {
        float scale = 0.8f / max_val;
        for (float& sample : audio) {
            sample *= scale;
        }
        LOGI("Normalized audio, max value was: %f, scale: %f", max_val, scale);
    }
}

void voiceEnhancementFilter(std::vector<float>& audio, int sample_rate) {
    if (audio.empty()) return;

    highPassFilter(audio, 300.0f, sample_rate);

    for (float& sample : audio) {
        sample *= 1.2f;
        sample = std::max(-0.95f, std::min(0.95f, sample));
    }

    LOGI("Applied voice enhancement filter");
}

bool detectVoiceActivity(const std::vector<float>& audio, int sample_rate) {
    if (audio.empty()) return false;

    float energy = 0.0f;
    for (float sample : audio) {
        energy += sample * sample;
    }
    energy /= audio.size();

    int zero_crossings = 0;
    for (size_t i = 1; i < audio.size(); ++i) {
        if ((audio[i-1] >= 0) != (audio[i] >= 0)) {
            zero_crossings++;
        }
    }
    float zcr = static_cast<float>(zero_crossings) / audio.size();

    const float energy_threshold = 0.001f;
    const float zcr_min = 0.01f;
    const float zcr_max = 0.3f;

    bool is_voice = (energy > energy_threshold) && (zcr > zcr_min) && (zcr < zcr_max);

    LOGI("VAD: energy=%f, zcr=%f, is_voice=%s", energy, zcr, is_voice ? "true" : "false");

    return is_voice;
}

void adaptiveNoiseGate(std::vector<float>& audio, float threshold_ratio) {
    if (audio.empty()) return;

    float max_val = 0.0f;
    for (float sample : audio) {
        max_val = std::max(max_val, std::abs(sample));
    }

    float gate_threshold = max_val * threshold_ratio;

    for (float& sample : audio) {
        if (std::abs(sample) < gate_threshold) {
            sample *= 0.01f;
        }
    }

    LOGI("Applied adaptive noise gate, threshold: %f", gate_threshold);
}
```

**Step 6: 创建native-lib.cpp**

完整文件内容:

```cpp
#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

// Include whisper headers
#ifdef __cplusplus
extern "C" {
#endif
#include "whisper.h"
#ifdef __cplusplus
}
#endif

#include "audio_utils.h"

#define LOG_TAG "WhisperNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static struct whisper_context* g_whisper_context = nullptr;

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_voicerec_service_WhisperService_getVersion(JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF("Whisper.cpp for VoiceRec 1.0");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_voicerec_service_WhisperService_loadModel(JNIEnv *env, jobject thiz, jstring model_path) {
    const char* path = env->GetStringUTFChars(model_path, nullptr);

    LOGI("Loading model from: %s", path);

    g_whisper_context = whisper_init_from_file(path);

    env->ReleaseStringUTFChars(model_path, path);

    if (g_whisper_context == nullptr) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_voicerec_service_WhisperService_transcribe(JNIEnv *env, jobject thiz, jfloatArray audio_data, jint sample_rate) {
    if (g_whisper_context == nullptr) {
        LOGE("Model not loaded");
        return env->NewStringUTF("");
    }

    // Get audio data from Java array
    jsize length = env->GetArrayLength(audio_data);
    jfloat* data = env->GetFloatArrayElements(audio_data, nullptr);

    std::vector<float> pcmf32(data, data + length);

    // Apply audio preprocessing
    LOGI("Applying audio preprocessing...");

    normalizeAudio(pcmf32);
    highPassFilter(pcmf32, 80.0f, sample_rate);
    spectralSubtractionDenoise(pcmf32, sample_rate);
    voiceEnhancementFilter(pcmf32, sample_rate);
    adaptiveNoiseGate(pcmf32, 0.05f);

    LOGI("Audio preprocessing completed, processed %zu samples", pcmf32.size());

    // Create whisper parameters
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_realtime   = false;
    wparams.print_progress   = false;
    wparams.print_timestamps = false;
    wparams.print_special    = false;
    wparams.translate        = false;
    wparams.language         = "zh";
    wparams.n_threads        = 4;
    wparams.offset_ms        = 0;
    wparams.no_context       = true;
    wparams.single_segment   = false;

    // Process audio
    if (whisper_full(g_whisper_context, wparams, pcmf32.data(), pcmf32.size()) != 0) {
        LOGE("Whisper transcription failed");
        env->ReleaseFloatArrayElements(audio_data, data, JNI_ABORT);
        return env->NewStringUTF("");
    }

    // Get transcription result
    std::string result;
    const int n_segments = whisper_full_n_segments(g_whisper_context);
    for (int i = 0; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(g_whisper_context, i);
        result += text;
    }

    env->ReleaseFloatArrayElements(audio_data, data, JNI_ABORT);

    LOGI("Transcription result: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_voicerec_service_WhisperService_releaseModel(JNIEnv *env, jobject thiz) {
    if (g_whisper_context != nullptr) {
        whisper_free(g_whisper_context);
        g_whisper_context = nullptr;
        LOGI("Model released");
    }
}
```

**Step 7: Commit**

```bash
cd "C:\Users\A.FYYX-2020PYSOXM\AndroidStudioProjects\voicerec"
git add app/src/main/cpp/
git commit -m "feat: add C++ native code for Whisper integration

- Add CMakeLists.txt with whisper.cpp integration
- Add audio_utils.cpp/h for audio preprocessing
- Add native-lib.cpp for JNI bridge

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## Task 2: 更新build.gradle.kts配置

**Files:**
- Modify: `app/build.gradle.kts`

**Step 1: 读取当前build.gradle.kts**

```bash
cat "C:\Users\A.FYYX-2020PYSOXM\AndroidStudioProjects\voicerec\app\build.gradle.kts"
```

**Step 2: 更新android配置块**

在 `android {}` 块中确保有 `externalNativeBuild` 配置:

```kotlin
android {
    namespace = "com.example.voicerec"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.voicerec"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    // ... 其他配置保持不变 ...

    externalNativeBuild {
        cmake {
            path = project.file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "26.1.10909125"
}
```

**Step 3: 移除不需要的依赖**

在 `dependencies {}` 块中，删除以下行:

```kotlin
// 删除这些行
implementation("org.tensorflow:tensorflow-lite:2.14.0")
implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.14.0")
implementation("com.alphacephei:vosk-android:0.3.32")
implementation("net.java.dev.jna:jna:5.13.0@aar")
```

保留的依赖应该类似:

```kotlin
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // OkHttp for model download
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
```

**Step 4: 同步Gradle**

```bash
cd "C:\Users\A.FYYX-2020PYSOXM\AndroidStudioProjects\voicerec"
./gradlew :app:assembleDebug --dry-run
```

Expected: 配置验证通过，无错误

**Step 5: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: remove Vosk/TFLite deps, add CMake config for Whisper

- Remove vosk-android and JNA dependencies
- Remove TensorFlow Lite dependencies
- Add externalNativeBuild configuration for CMake

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## Task 3: 重写WhisperModelManager

**Files:**
- Modify: `app/src/main/java/com/example/voicerec/service/WhisperModelManager.kt`

**Step 1: 删除旧文件并创建新版本**

完整新文件内容:

```kotlin
package com.example.voicerec.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Whisper模型管理器
 * 管理ggml-base-q8_0.bin模型文件
 */
class WhisperModelManager(private val context: Context) {

    companion object {
        private const val MODEL_FILE_NAME = "ggml-base-q8_0.bin"
        private const val MIN_MODEL_SIZE = 50 * 1024 * 1024 // 50MB
    }

    /**
     * 获取模型文件路径
     */
    fun getModelFile(): File {
        return File(context.filesDir, MODEL_FILE_NAME)
    }

    /**
     * 检查模型是否已准备好
     */
    fun isModelReady(): Boolean {
        val modelFile = getModelFile()
        if (!modelFile.exists()) {
            return false
        }
        // 检查文件大小是否合理
        return modelFile.length() > MIN_MODEL_SIZE
    }

    /**
     * 从assets复制模型到内部存储
     */
    suspend fun copyModelFromAssets(
        onProgress: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val modelFile = getModelFile()

            // 如果文件已存在且大小合理，直接返回
            if (modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE) {
                onProgress("模型文件已存在")
                return@withContext Result.success(modelFile)
            }

            onProgress("正在复制模型文件...")

            // 删除旧文件
            if (modelFile.exists()) {
                modelFile.delete()
            }

            // 确保父目录存在
            modelFile.parentFile?.mkdirs()

            // 从assets复制
            val assetManager = context.assets
            assetManager.open(MODEL_FILE_NAME).use { input ->
                FileOutputStream(modelFile).use { output ->
                    val buffer = ByteArray(8192)
                    var totalCopied = 0L
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalCopied += bytesRead

                        // 每10MB更新一次进度
                        if (totalCopied % (10 * 1024 * 1024) < 8192) {
                            val mb = totalCopied / (1024 * 1024)
                            onProgress("正在复制: ${mb}MB")
                        }
                    }
                    output.flush()
                }
            }

            // 验证文件
            if (!modelFile.exists()) {
                return@withContext Result.failure(Exception("模型文件复制失败"))
            }

            if (modelFile.length() < MIN_MODEL_SIZE) {
                modelFile.delete()
                return@withContext Result.failure(Exception("模型文件大小异常: ${modelFile.length()} bytes"))
            }

            onProgress("模型复制完成")
            Result.success(modelFile)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取模型大小
     */
    fun getModelSize(): Long {
        val modelFile = getModelFile()
        return if (modelFile.exists()) modelFile.length() else 0
    }

    /**
     * 获取格式化的模型大小
     */
    fun getFormattedSize(): String {
        val bytes = getModelSize()
        return when {
            bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024))
            bytes >= 1024 -> "%.1fKB".format(bytes / 1024.0)
            else -> "${bytes}B"
        }
    }

    /**
     * 删除模型
     */
    fun deleteModel() {
        getModelFile().delete()
    }
}
```

**Step 2: 验证代码语法**

```bash
cd "C:\Users\A.FYYX-2020PYSOXM\AndroidStudioProjects\voicerec"
./gradlew :app:compileDebugKotlin
```

Expected: 编译成功

**Step 3: Commit**

```bash
git add app/src/main/java/com/example/voicerec/service/WhisperModelManager.kt
git commit -m "refactor: rewrite WhisperModelManager for Whisper.cpp

- Change to manage ggml-base-q8_0.bin model
- Copy model from assets instead of downloading
- Remove Vosk-related code

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## Task 4: 创建新的WhisperService

**Files:**
- Delete: `app/src/main/java/com/example/voicerec/service/WhisperTranscribeService.kt`
- Create: `app/src/main/java/com/example/voicerec/service/WhisperService.kt`

**Step 1: 删除旧的WhisperTranscribeService**

```bash
rm "C:\Users\A.FYYX-2020PYSOXM\AndroidStudioProjects\voicerec\app\src\main\java\com\example\voicerec\service\WhisperTranscribeService.kt"
```

**Step 2: 创建新的WhisperService**

完整文件内容 (参考 `F:\whisper-android-demo\app\src\main\java\com\example\whisperdemo\WhisperService.kt`):

```kotlin
package com.example.voicerec.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Whisper语音转文字服务
 * 使用whisper.cpp原生实现
 */
class WhisperService(private val context: Context) {

    companion object {
        private const val TAG = "WhisperService"

        init {
            try {
                System.loadLibrary("voicerec")
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }
    }

    private var isModelLoaded = false

    // Native methods
    private external fun getVersion(): String
    private external fun loadModel(modelPath: String): Boolean
    private external fun transcribe(audioData: FloatArray, sampleRate: Int): String
    private external fun releaseModel()

    /**
     * 加载Whisper模型
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelManager = WhisperModelManager(context)

            if (!modelManager.isModelReady()) {
                Log.w(TAG, "Model not ready, copying from assets...")
                val copyResult = modelManager.copyModelFromAssets { }
                if (copyResult.isFailure) {
                    Log.e(TAG, "Failed to copy model: ${copyResult.exceptionOrNull()?.message}")
                    return@withContext false
                }
            }

            val modelFile = modelManager.getModelFile()
            Log.i(TAG, "Loading model from: ${modelFile.absolutePath}")

            isModelLoaded = loadModel(modelFile.absolutePath)

            if (isModelLoaded) {
                Log.i(TAG, "Model loaded successfully")
            } else {
                Log.e(TAG, "Failed to load model")
            }

            return@withContext isModelLoaded
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            return@withContext false
        }
    }

    /**
     * 转录音频文件
     */
    suspend fun transcribeAudio(
        audioPath: String,
        onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isModelLoaded) {
                Log.w(TAG, "Model not loaded, loading now...")
                val loaded = loadModel()
                if (!loaded) {
                    return@withContext Result.failure(Exception("模型加载失败"))
                }
            }

            onProgress("正在解码音频...")
            val audioSamples = decodeAudioToFloat(audioPath)
            Log.i(TAG, "Audio decoded, samples: ${audioSamples.size}")

            onProgress("正在进行语音识别...")
            val result = transcribe(audioSamples, 16000)
            Log.i(TAG, "Transcription result: $result")

            if (result.isNotBlank()) {
                Result.success(result.trim())
            } else {
                Result.failure(Exception("识别结果为空"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            Result.failure(e)
        }
    }

    /**
     * 释放模型资源
     */
    fun release() {
        if (isModelLoaded) {
            releaseModel()
            isModelLoaded = false
            Log.i(TAG, "Model released")
        }
    }

    /**
     * 获取版本信息
     */
    fun getVersionInfo(): String {
        return try {
            getVersion()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting version", e)
            "Unknown"
        }
    }

    /**
     * 解码音频文件到FloatArray
     */
    private fun decodeAudioToFloat(audioPath: String): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audioPath)
            val trackIndex = findAudioTrack(extractor)
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 16000)

            Log.d(TAG, "Audio format: $mime, sampleRate: $sampleRate Hz")

            val pcm = decodeWithMediaCodec(extractor, mime, sampleRate)

            return if (sampleRate != 16000) {
                resample(pcm, sampleRate.toFloat(), 16000f)
            } else {
                pcm
            }

        } finally {
            extractor.release()
        }
    }

    private fun decodeWithMediaCodec(
        extractor: MediaExtractor,
        mime: String,
        sampleRate: Int
    ): FloatArray {
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(extractor.getTrackFormat(findAudioTrack(extractor)), null, null, 0)
        codec.start()

        val output = mutableListOf<Float>()
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            var inputEOS = false
            var outputEOS = false

            while (!outputEOS) {
                if (!inputEOS) {
                    val idx = codec.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)
                        val size = extractor.readSampleData(buf!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEOS = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val idx = codec.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        val floats = bufferToFloatArray(buf!!, bufferInfo.size)
                        output.addAll(floats.toList())
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEOS = true
                        }
                        codec.releaseOutputBuffer(idx, false)
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }

        return output.toFloatArray()
    }

    private fun bufferToFloatArray(buffer: ByteBuffer, size: Int): FloatArray {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(size / 2)
        for (i in floats.indices) {
            val short = buffer.short.toShort().toInt()
            floats[i] = short / 32768.0f
        }
        return floats
    }

    private fun resample(input: FloatArray, fromRate: Float, toRate: Float): FloatArray {
        if (fromRate == toRate) return input
        val ratio = fromRate / toRate
        val outLen = (input.size / ratio).toInt()
        val output = FloatArray(outLen)
        for (i in output.indices) {
            output[i] = input[(i * ratio).toInt()]
        }
        return output
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1
    }
}
```

**Step 3: 验证代码语法**

```bash
cd "C:\Users\A.FYYX-2020PYSOXM\AndroidStudioProjects\voicerec"
./gradlew :app:compileDebugKotlin
```

Expected: 编译成功，可能报错找不到native方法（这是正常的，C++代码还没编译）

**Step 4: Commit**

```bash
git add app/src/main/java/com/example/voicerec/service/
git commit -m "refactor: replace WhisperTranscribeService with WhisperService

- New WhisperService uses whisper.cpp via JNI
- Remove Vosk and TFLite fallback logic
- Implement audio decoding with MediaCodec

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## Task 5: 更新RecordingsViewModel

**Files:**
- Modify: `app/src/main/java/com/example/voicerec/ui/recordings/RecordingsViewModel.kt`

**Step 1: 修改import和transcribeRecording方法**

将 `import com.example.voicerec.service.WhisperTranscribeService` 改为 `import com.example.voicerec.service.WhisperService`

更新 `transcribeRecording` 方法:

```kotlin
suspend fun transcribeRecording(recording: Recording): Result<String> = withContext(Dispatchers.IO) {
    try {
        val modelManager = WhisperModelManager(getApplication())

        // 确保模型已准备好
        if (!modelManager.isModelReady()) {
            val copyResult = modelManager.copyModelFromAssets { }
            if (copyResult.isFailure) {
                return@withContext Result.failure(ModelNotDownloadedException())
            }
        }

        val whisperService = WhisperService(getApplication())

        val result = whisperService.transcribeRecording(recording.filePath) { progress ->
            // 进度回调
        }

        whisperService.release()

        result.fold(
            onSuccess = { text ->
                val updatedRecording = recording.copy(
                    transcriptionText = text,
                    transcriptionTime = System.currentTimeMillis()
                )
                repository.updateRecording(updatedRecording)
                Result.success(text)
            },
            onFailure = { error ->
                Result.failure(error)
            }
        )

    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

**Step 2: 更新getModelInfo方法**

保持不变，因为WhisperModelManager的接口兼容。

**Step 3: 验证代码语法**

```bash
cd "C:\Users\A.FYYX-2020PYSOXM\AndroidStudioProjects\voicerec"
./gradlew :app:compileDebugKotlin
```

Expected: 编译成功

**Step 4: Commit**

```bash
git add app/src/main/java/com/example/voicerec/ui/recordings/RecordingsViewModel.kt
git commit -m "refactor: update RecordingsViewModel to use new WhisperService

- Change from WhisperTranscribeService to WhisperService
- Add model preparation check
- Properly release WhisperService after use

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## Task 6: 复制模型文件到assets

**Files:**
- Create: `app/src/main/assets/ggml-base-q8_0.bin`

**Step 1: 创建assets目录**

```bash
mkdir -p "C:\Users\A.FYYX-2020PYSOXM\AndroidStudioProjects\voicerec\app\src\main\assets"
```

**Step 2: 复制模型文件**

```bash
cp "F:\whisper-android-demo\app\src\main\assets\ggml-base-q8_0.bin" "C:\Users\A.FYYX-2020PYSOXM\AndroidStudioProjects\voicerec\app\src\main\assets\ggml-base-q8_0.bin"
```

**Step 3: 验证文件大小**

```bash
ls -lh "C:\Users\A.FYYX-2020PYSOXM\AndroidStudioProjects\voicerec\app\src\main\assets\ggml-base-q8_0.bin"
```

Expected: 文件大小约78MB

**Step 4: 添加到.gitignore（如果文件太大）**

如果模型文件导致仓库过大，可以添加到.gitignore:

```bash
echo "app/src/main/assets/ggml-base-q8_0.bin" >> "C:\Users\A.FYYX-2020PYSOXM\AndroidStudioProjects\voicerec\.gitignore"
```

**Step 5: 提交（如果没有添加到gitignore）**

```bash
git add app/src/main/assets/ggml-base-q8_0.bin
git commit -m "feat: add Whisper Q8 model to assets

- Add ggml-base-q8_0.bin (~78MB) for Chinese speech recognition

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

或者如果添加到.gitignore:

```bash
git add .gitignore
git commit -m "chore: add Whisper model to gitignore

- Model is too large for git, users need to copy it manually

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## Task 7: 构建和测试

**Step 1: 清理构建**

```bash
cd "C:\Users\A.FYYX-2020PYSOXM\AndroidStudioProjects\voicerec"
./gradlew clean
```

**Step 2: 构建项目**

```bash
./gradlew :app:assembleDebug
```

Expected: 构建成功，C++代码被编译

**Step 3: 检查生成的so库**

```bash
ls -la "C:\Users\A.FYYX-2020PYSOXM\AndroidStudioProjects\voicerec\app\build\intermediates\cmake\debug\obj\"
```

Expected: 看到 arm64-v8a 和/或 armeabi-v7a 目录，里面有 libvoicerec.so

**Step 4: 在设备上运行测试**

1. 连接Android设备或启动模拟器
2. 安装应用: `./gradlew :app:installDebug`
3. 打开应用，录音一段
4. 点击"转文字"按钮
5. 检查logcat输出: `adb logcat | grep Whisper`

Expected: 看到"Model loaded successfully"和转录结果

**Step 5: 最终Commit**

```bash
git add -A
git commit -m "test: verify Whisper integration works end-to-end

- Successfully build native libraries
- Test transcription on device
- Confirm model loading and inference work

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## 验证清单

- [ ] C++代码编译通过，生成libvoicerec.so
- [ ] Kotlin代码编译通过
- [ ] 模型文件正确复制到assets
- [ ] 应用可以在设备上安装
- [ ] 录音功能正常
- [ ] 转文字功能正常
- [ ] 没有Vosk/TFLite相关的报错

## 参考文件

- Demo项目: `F:\whisper-android-demo`
- whisper.cpp: https://github.com/ggerganov/whisper.cpp
