# LLM Title Generation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Integrate Qwen2.5-1.5B-Instruct GGUF model to automatically generate short Chinese titles (5-10 characters) from audio transcriptions, running entirely offline on Android.

**Architecture:** Independent JNI module using llama.cpp for GGUF inference, separate from existing Whisper integration. Transcription completion triggers LLM title generation, saved to database `ai_title` field and displayed in UI next to recording timestamp.

**Tech Stack:**
- llama.cpp (GGUF inference engine)
- JNI (Kotlin ↔ C++)
- Room database (migration 2→3)
- Qwen2.5-1.5B-Instruct-Q4_K_M.gguf

---

## Prerequisites

**Model file placement:**
- Place `qwen2.5-1.5b-instruct-q4_k_m.gguf` in `app/src/main/assets/`
- Model size: ~1.2 GB
- Download from: https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF

---

## Task 1: Database Schema Migration

**Files:**
- Modify: `app/src/main/java/com/example/voicerec/data/Recording.kt`
- Modify: `app/src/main/java/com/example/voicerec/data/RecordingsDao.kt`
- Modify: `app/src/main/java/com/example/voicerec/database/AppDatabase.kt`

**Step 1: Add aiTitle fields to Recording entity**

Open `app/src/main/java/com/example/voicerec/data/Recording.kt`

Add two new properties at the end of the data class (before closing brace):

```kotlin
val aiTitle: String? = null,
val aiTitleTime: Long? = null
```

The Recording entity should now be:

```kotlin
@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val filePath: String,
    val dayFolder: String,
    val hourFolder: String,
    val timestamp: Long,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val transcriptionText: String? = null,
    val transcriptionTime: Long? = null,
    val aiTitle: String? = null,
    val aiTitleTime: Long? = null
)
```

**Step 2: Add updateAiTitle method to RecordingsDao**

Open `app/src/main/java/com/example/voicerec/data/RecordingsDao.kt`

Add the new method:

```kotlin
@Query("UPDATE recordings SET aiTitle = :title, aiTitleTime = :timestamp WHERE id = :id")
suspend fun updateAiTitle(id: Long, title: String, timestamp: Long)
```

**Step 3: Update database version and add migration**

Open `app/src/main/java/com/example/voicerec/database/AppDatabase.kt`

Update version from 2 to 3:

```kotlin
@Database(
    entities = [Recording::class],
    version = 3,  // Changed from 2 to 3
    exportSchema = false
)
```

Add migration constant before the class:

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE recordings ADD COLUMN aiTitle TEXT")
        database.execSQL("ALTER TABLE recordings ADD COLUMN aiTitleTime INTEGER")
    }
}
```

Add migration to the migration array in `getDatabase()`:

```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3)
```

**Step 4: Build to verify**

Run: `./gradlew assembleDebug`

Expected: Build succeeds with database migration

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voicerec/data/Recording.kt
git add app/src/main/java/com/example/voicerec/data/RecordingsDao.kt
git add app/src/main/java/com/example/voicerec/database/AppDatabase.kt
git commit -m "feat: add aiTitle fields to Recording entity

Add aiTitle and aiTitleTime columns for LLM-generated titles.
Migration 2→3."
```

---

## Task 2: Native Layer - llama.cpp Integration

**Files:**
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/llama_jni.cpp`

**Step 1: Update CMakeLists.txt**

Open `app/src/main/cpp/CMakeLists.txt`

After the whisper section (after line 64), add:

```cmake
# ============================================
# llama.cpp - Qwen2.5 GGUF model support
# ============================================

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

include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        b3879
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

# Llama JNI library
add_library(voicerec_llama SHARED
    llama_jni.cpp
)

target_include_directories(voicerec_llama PRIVATE
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/src
)

target_link_libraries(voicerec_llama
    llama
    ${log-lib}
    android
)
```

**Step 2: Create llama_jni.cpp**

Create `app/src/main/cpp/llama_jni.cpp`:

```cpp
#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include "llama.h"

// Global engine state
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static int g_n_threads = 4;

extern "C" JNIEXPORT jint JNICALL
Java_com_example_voicerec_service_LlamaService_initModel(
    JNIEnv* env,
    jobject /* this */,
    jstring model_path,
    jint n_threads) {

    const char* model_path_cstr = env->GetStringUTFChars(model_path, nullptr);
    g_n_threads = n_threads;

    LOGI("Initializing llama model from: %s", model_path_cstr);

    // Initialize model parameters
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;  // CPU only for now

    // Load model
    g_model = llama_load_model_from_file(model_path_cstr, model_params);
    env->ReleaseStringUTFChars(model_path, model_path_cstr);

    if (g_model == nullptr) {
        LOGE("Failed to load llama model");
        return -1;
    }

    LOGI("Model loaded successfully");

    // Initialize context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;  // Context size
    ctx_params.n_threads = g_n_threads;
    ctx_params.seed = 1234;

    g_ctx = llama_new_context_with_model(g_model, ctx_params);
    if (g_ctx == nullptr) {
        LOGE("Failed to create llama context");
        llama_free_model(g_model);
        g_model = nullptr;
        return -2;
    }

    LOGI("Llama context initialized");
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_voicerec_service_LlamaService_generateTitle(
    JNIEnv* env,
    jobject /* this */,
    jstring prompt_jstring) {

    if (g_model == nullptr || g_ctx == nullptr) {
        LOGE("Model or context not initialized");
        return env->NewStringUTF("");
    }

    std::string prompt = env->GetStringUTFChars(prompt_jstring, nullptr);

    // Tokenize prompt
    std::vector<llama_token> tokens;
    tokens = llama_tokenize(g_ctx, prompt, true, true);

    LOGI("Prompt tokens: %zu", tokens.size());

    // Clear the KV cache
    llama_kv_cache_clear(g_ctx);

    // Evaluate prompt
    int n_evaluated = 0;
    for (size_t i = 0; i < tokens.size(); i++) {
        llama_batch batch = llama_batch_get_one(&tokens[i], 1, n_evaluated, 0);
        n_evaluated += llama_decode(g_ctx, batch);
    }

    // Generate response
    std::string result;
    int max_tokens = 50;
    llama_token new_token;

    for (int i = 0; i < max_tokens; i++) {
        // Sample next token
        new_token = llama_sampler_sample_top_p_top_k(
            g_ctx,
            llama_sampler_init_top_k(40),
            llama_sampler_init_top_p(0.95f),
            0  // temperature
        );

        // Check for EOS
        if (new_token == llama_token_eos(g_model)) {
            break;
        }

        // Convert token to string
        char* token_str = llama_token_to_piece(g_ctx, new_token);
        if (token_str) {
            result += token_str;
            free(token_str);
        }

        // Evaluate the new token
        llama_batch batch = llama_batch_get_one(&new_token, 1, n_evaluated + i, 0);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Failed to decode token");
            break;
        }
    }

    LOGI("Generated title: %s", result.c_str());

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_voicerec_service_LlamaService_cleanup(
    JNIEnv* /* env */,
    jobject /* this */) {

    LOGI("Cleaning up llama resources");

    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }

    if (g_model != nullptr) {
        llama_free_model(g_model);
        g_model = nullptr;
    }

    LOGI("Llama cleanup complete");
}
```

**Step 3: Build native libraries**

Run: `./gradlew assembleDebug`

Expected: Build succeeds with native lib compilation

**Step 4: Commit**

```bash
git add app/src/main/cpp/CMakeLists.txt
git add app/src/main/cpp/llama_jni.cpp
git commit -m "feat: add llama.cpp JNI integration

Add llama.cpp support for Qwen2.5 GGUF model inference.
Separate library (voicerec_llama) from existing whisper."
```

---

## Task 3: LlamaService Kotlin Layer

**Files:**
- Create: `app/src/main/java/com/example/voicerec/service/LlamaService.kt`
- Create: `app/src/main/java/com/example/voicerec/service/LlamaModelManager.kt`

**Step 1: Create LlamaModelManager**

Create `app/src/main/java/com/example/voicerec/service/LlamaModelManager.kt`:

```kotlin
package com.example.voicerec.service

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Qwen2.5 LLM 模型管理器
 */
class LlamaModelManager(private val context: Context) {

    companion object {
        private const val TAG = "LlamaModelManager"
        const val MODEL_FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        private const val MIN_MODEL_SIZE = 100_000_000L  // 100MB minimum
    }

    /**
     * 获取模型文件路径
     */
    fun getModelFile(): File {
        val modelsDir = File(context.filesDir, "llm_models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        return File(modelsDir, MODEL_FILE)
    }

    /**
     * 检查模型是否已准备好
     */
    fun isModelReady(): Boolean {
        val file = getModelFile()
        val exists = file.exists()
        val sizeOk = file.length() > MIN_MODEL_SIZE
        Log.d(TAG, "Model ready check: exists=$exists, sizeOk=$sizeOk")
        return exists && sizeOk
    }

    /**
     * 从 assets 复制模型文件
     */
    fun copyModelFromAssets(onProgress: ((String) -> Unit)? = null): Result<Unit> {
        return try {
            onProgress?.invoke("正在准备模型...")

            val assetFile = context.assets.open(MODEL_FILE)
            val targetFile = getModelFile()

            // Check if already exists and valid
            if (targetFile.exists() && targetFile.length() > MIN_MODEL_SIZE) {
                Log.i(TAG, "Model already exists, skipping copy")
                return Result.success(Unit)
            }

            val totalSize = assetFile.available().toLong()
            var copiedBytes = 0L
            val buffer = ByteArray(1024 * 1024)  // 1MB buffer

            targetFile.outputStream().use { output ->
                var bytesRead: Int
                while (assetFile.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    copiedBytes += bytesRead

                    val progress = (copiedBytes * 100 / totalSize).toInt()
                    onProgress?.invoke("正在复制模型... $progress%")
                }
            }

            assetFile.close()
            Log.i(TAG, "Model copied successfully: ${targetFile.length()} bytes")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model", e)
            Result.failure(e)
        }
    }

    /**
     * 获取模型大小（字节）
     */
    fun getModelSizeBytes(): Long {
        return if (getModelFile().exists()) {
            getModelFile().length()
        } else {
            0L
        }
    }

    /**
     * 获取模型大小（格式化字符串）
     */
    fun getModelSizeFormatted(): String {
        val bytes = getModelSizeBytes()
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.1fGB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024))
            else -> "%.1fKB".format(bytes / 1024.0)
        }
    }
}
```

**Step 2: Create LlamaService**

Create `app/src/main/java/com/example/voicerec/service/LlamaService.kt`:

```kotlin
package com.example.voicerec.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

/**
 * Qwen2.5 LLM 标题生成服务
 * 使用 llama.cpp 进行本地推理
 */
class LlamaService(private val context: Context) {

    companion object {
        private const val TAG = "LlamaService"

        init {
            try {
                System.loadLibrary("voicerec_llama")
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }
    }

    private var isModelLoaded = false

    // Native methods
    private external fun initModel(modelPath: String, nThreads: Int): Int
    private external fun generateTitle(prompt: String): String
    private external fun cleanup()

    /**
     * 加载模型
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelManager = LlamaModelManager(context)

            if (!modelManager.isModelReady()) {
                Log.w(TAG, "Model not ready, copying from assets...")
                val copyResult = modelManager.copyModelFromAssets { message ->
                    Log.i(TAG, "Copy progress: $message")
                }
                if (copyResult.isFailure) {
                    Log.e(TAG, "Failed to copy model: ${copyResult.exceptionOrNull()?.message}")
                    return@withContext false
                }
            }

            val modelFile = modelManager.getModelFile()
            Log.i(TAG, "Loading model from: ${modelFile.absolutePath}, size: ${modelFile.length()} bytes")

            val result = initModel(modelFile.absolutePath, 4)
            isModelLoaded = (result == 0)

            if (isModelLoaded) {
                Log.i(TAG, "Model loaded successfully")
            } else {
                Log.e(TAG, "Failed to load model, result code: $result")
            }

            return@withContext isModelLoaded

        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            return@withContext false
        }
    }

    /**
     * 生成 AI 标题
     */
    suspend fun generateAiTitle(transcription: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Validate input
            if (transcription.isBlank()) {
                return@withContext Result.failure(Exception("转写文本为空"))
            }

            // Limit input length
            val truncated = transcription.take(500)

            // Load model if needed
            if (!isModelLoaded) {
                Log.w(TAG, "Model not loaded, loading now...")
                val loaded = loadModel()
                if (!loaded) {
                    return@withContext Result.failure(Exception("LLM模型加载失败"))
                }
            }

            // Build prompt for Qwen2.5 Instruct
            val prompt = buildPrompt(truncated)

            Log.i(TAG, "Generating title, prompt length: ${prompt.length}")

            val elapsed = measureTimeMillis {
                val rawOutput = generateTitle(prompt)
                val cleanedTitle = cleanOutput(rawOutput)

                if (cleanedTitle.isNotBlank()) {
                    Log.i(TAG, "Title generated: '$cleanedTitle' in ${elapsed}ms")
                    Result.success(cleanedTitle)
                } else {
                    Result.failure(Exception("生成的标题为空"))
                }
            }

            return@withContext Result.success("")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate AI title", e)
            return@withContext Result.failure(e)
        }
    }

    /**
     * 构建 Qwen2.5 Instruct 格式的 prompt
     */
    private fun buildPrompt(transcription: String): String {
        return """<|im_start|>system
你是一个录音标题生成助手。请根据录音转写文本，生成一个5-10个中文字符的简短标题，概括主旨。只输出标题，不要加引号或其他符号。
<|im_end|>
<|im_start|>user
$transcription
<|im_end|>
<|im_start|>assistant
"""
    }

    /**
     * 清理模型输出
     */
    private fun cleanOutput(output: String): String {
        return output
            .replace("<|im_start|>assistant\n", "")
            .replace("<|im_end|>", "")
            .replace("\"", "")
            .trim()
            .take(20)  // Safety limit
    }

    /**
     * 释放模型资源
     */
    fun release() {
        if (isModelLoaded) {
            try {
                cleanup()
                isModelLoaded = false
                Log.i(TAG, "Model resources released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing model", e)
            }
        }
    }
}
```

**Step 3: Build to verify**

Run: `./gradlew assembleDebug`

Expected: Build succeeds

**Step 4: Commit**

```bash
git add app/src/main/java/com/example/voicerec/service/LlamaService.kt
git add app/src/main/java/com/example/voicerec/service/LlamaModelManager.kt
git commit -m "feat: add LlamaService for AI title generation

Add Kotlin service layer for Qwen2.5 model inference.
Includes model manager for asset copying and validation."
```

---

## Task 4: Integrate Title Generation into RecordingService

**Files:**
- Modify: `app/src/main/java/com/example/voicerec/service/RecordingService.kt`

**Step 1: Find the transcription completion handler**

Open `app/src/main/java/com/example/voicerec/service/RecordingService.kt`

Search for the function that handles transcription completion (look for `ACTION_TRANSCRIPTION_COMPLETE` or where transcription is saved to database).

**Step 2: Add AI title generation after transcription**

After the transcription is saved to database, add title generation:

```kotlin
// After saving transcription to database:
lifecycleScope.launch(Dispatchers.IO) {
    try {
        val llamaService = LlamaService(applicationContext)
        val aiTitle = llamaService.generateAiTitle(transcriptionText).getOrNull()
        aiTitle?.let { title ->
            database.recordingsDao().updateAiTitle(
                recordingId,
                title,
                System.currentTimeMillis()
            )
            Log.i(TAG, "AI title saved: $title")
        }
        llamaService.release()
    } catch (e: Exception) {
        Log.w(TAG, "AI title generation failed, not blocking", e)
        // Don't throw - title generation failure shouldn't block transcription
    }
}
```

**Step 3: Build to verify**

Run: `./gradlew assembleDebug`

**Step 4: Commit**

```bash
git add app/src/main/java/com/example/voicerec/service/RecordingService.kt
git commit -m "feat: integrate AI title generation after transcription

Automatically generate short titles using Qwen2.5 when transcription completes.
Failure to generate title does not block transcription."
```

---

## Task 5: UI Update - Display AI Title

**Files:**
- Modify: `app/src/main/java/com/example/voicerec/ui/recordings/RecordingsAdapter.kt`

**Step 1: Find timestamp display in ViewHolder**

Open `app/src/main/java/com/example/voicerec/ui/recordings/RecordingsAdapter.kt`

Search for where timestamp/time is displayed (look for `tvTime` or similar).

**Step 2: Update time display to include AI title**

Modify the time TextView text to include AI title when available:

```kotlin
// Original (example):
holder.binding.tvTime.text = formatTime(recording.timestamp)

// Updated:
val timeText = formatTime(recording.timestamp)
val titleText = recording.aiTitle?.let { " · $it" } ?: ""
holder.binding.tvTime.text = timeText + titleText
```

If using a custom time format function, update accordingly.

**Step 3: Build and verify**

Run: `./gradlew assembleDebug`

**Step 4: Commit**

```bash
git add app/src/main/java/com/example/voicerec/ui/recordings/RecordingsAdapter.kt
git commit -m "feat: display AI title next to timestamp

Show LLM-generated title after recording time when available."
```

---

## Task 6: Add Model File

**Files:**
- Create: `app/src/main/assets/qwen2.5-1.5b-instruct-q4_k_m.gguf`

**Step 1: Download the model**

Download from: https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf

**Step 2: Place in assets directory**

Copy the downloaded file to `app/src/main/assets/`

**Step 3: Update .gitignore**

Add to `.gitignore` (model should not be committed to git):

```
# Ignore large model files
app/src/main/assets/*.gguf
```

**Step 4: Commit gitignore update**

```bash
git add .gitignore
git commit -m "chore: ignore GGUF model files in assets"
```

---

## Task 7: Testing and Verification

**Step 1: Install and test**

Run: `./gradlew installDebug`

**Step 2: Test flow**

1. Open app
2. Start a recording
3. Stop recording
4. Trigger transcription
5. Verify:
   - Transcription completes
   - AI title is generated (check logs)
   - Title is saved to database
   - Title appears in UI next to time

**Step 3: Check logs for issues**

```bash
adb logcat | grep -E "(LlamaService|LlamaJNI|RecordingService)"
```

**Step 4: Fix any issues found**

Address any bugs or crashes discovered during testing.

**Step 5: Final commit**

```bash
git add -A
git commit -m "fix: address issues found during testing"
```

---

## Summary

After completing all tasks:
- Database migration 2→3 with `aiTitle` and `aiTitleTime` fields
- llama.cpp JNI integration for GGUF model inference
- LlamaService for title generation with Qwen2.5
- Automatic title generation after transcription
- UI displays title next to recording timestamp
- ~1.2GB model file in assets (gitignored)

**Testing Checklist:**
- [ ] Database migration works on existing install
- [ ] Model loads successfully
- [ ] Title generates without crash
- [ ] Title displays in UI
- [ ] Transcription still works if title generation fails
- [ ] App doesn't crash on low memory devices
