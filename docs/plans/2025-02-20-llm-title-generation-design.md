# LLM 标题生成集成设计文档

**日期:** 2025-02-20
**模型:** Qwen2.5-1.5B-Instruct-Q4_K_M.gguf

---

## 1. 概述

在现有 Android 录音应用中集成 Qwen2.5 LLM，在音频转写完成后自动生成简短的中文标题（5-10个字符），并保存到数据库。

**核心需求:**
- 离线运行，无网络依赖
- 转写完成后自动生成标题
- 标题保存到数据库新字段
- 显示在录音文件时间后面

---

## 2. 架构

```
┌─────────────────────────────────────────────────────────────┐
│                      Android Application                     │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐         ┌─────────────────┐            │
│  │ WhisperService  │────────>│  LlamaService   │            │
│  │  (已有)          │         │   (新增)         │            │
│  └─────────────────┘         └────────┬────────┘            │
│  ┌─────────────────┐                  │                      │
│  │ RecordingService│                  │                      │
│  │                 │<─────────────────┘                      │
│  └────────┬────────┘                                         │
│           │                                                   │
│  ┌────────▼────────┐         ┌─────────────────┐            │
│  │ RecordingsVM    │────────>│    Database     │            │
│  └─────────────────┘         │  (Room + aiTitle)│           │
│                              └─────────────────┘            │
└─────────────────────────────────────────────────────────────┘
                              │
                              v
┌─────────────────────────────────────────────────────────────┐
│                      Native Layer (JNI)                      │
├─────────────────────────────────────────────────────────────┤
│  ┌──────────────────┐         ┌──────────────────┐         │
│  │  whisper_jni.cpp │         │  llama_jni.cpp   │         │
│  │      (已有)       │         │     (新增)        │         │
│  └──────────────────┘         └────────┬─────────┘         │
│                                        │                    │
│                                        v                    │
│  ┌──────────────────────────────────────────────────┐     │
│  │              llama.cpp (FetchContent)             │     │
│  └──────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────────┘
```

**流程:**
1. WhisperService 完成音频转写
2. RecordingService 调用 LlamaService 生成标题
3. 标题保存到数据库 `ai_title` 字段
4. UI 刷新显示标题（在时间后面）

---

## 3. 数据模型

### Recording 实体扩展

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
    val aiTitle: String? = null,           // 新增
    val aiTitleTime: Long? = null          // 新增
)
```

### 数据库迁移

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE recordings ADD COLUMN aiTitle TEXT")
        database.execSQL("ALTER TABLE recordings ADD COLUMN aiTitleTime INTEGER")
    }
}
```

### RecordingsDao 新增方法

```kotlin
@Query("UPDATE recordings SET aiTitle = :title, aiTitleTime = :timestamp WHERE id = :id")
suspend fun updateAiTitle(id: Long, title: String, timestamp: Long)
```

---

## 4. Native 层 (llama.cpp)

### llama_jni.cpp

```cpp
#include <jni.h>
#include <string>
#include <llama.h>

class LlamaEngine {
private:
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;

public:
    bool loadModel(const std::string& path, int n_threads = 4);
    std::string generate(const std::string& prompt, int max_tokens = 50);
    void release();
};

extern "C" JNIEXPORT jint JNICALL
Java_com_example_voicerec_service_LlamaService_initModel(
    JNIEnv* env, jobject, jstring model_path, jint n_threads);

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_voicerec_service_LlamaService_generateTitle(
    JNIEnv* env, jobject, jstring transcription_text);

extern "C" JNIEXPORT void JNICALL
Java_com_example_voicerec_service_LlamaService_cleanup(JNIEnv*, jobject);
```

### Prompt 模板 (Qwen2.5 Instruct)

```cpp
std::string buildPrompt(const std::string& transcription) {
    return "<|im_start|>system\n"
           "你是一个录音标题生成助手。请根据录音转写文本，生成一个5-10个中文字符的简短标题，概括主旨。"
           "<|im_end|>\n"
           "<|im_start|>user\n"
           + transcription +
           "<|im_end|>\n"
           "<|im_start|>assistant\n";
}
```

### CMakeLists.txt 扩展

```cmake
# 添加 llama.cpp
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        master
    GIT_SHALLOW    TRUE
)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_NO_AVX ON CACHE BOOL "" FORCE)
set(LLAMA_NO_AVX2 ON CACHE BOOL "" FORCE)
set(LLAMA_NO_FMA ON CACHE BOOL "" FORCE)
set(LLAMA_NO_F16C ON CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama)

add_library(voicerec_llama SHARED llama_jni.cpp)
target_include_directories(voicerec_llama PRIVATE ${llama_SOURCE_DIR}/include)
target_link_libraries(voicerec_llama llama ${log-lib} android)
```

---

## 5. Kotlin 服务层

### LlamaService.kt

```kotlin
class LlamaService(private val context: Context) {

    companion object {
        private const val TAG = "LlamaService"
        const val MODEL_FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"

        init {
            System.loadLibrary("voicerec_llama")
        }
    }

    private external fun initModel(modelPath: String, nThreads: Int): Int
    private external fun generateTitle(transcriptionText: String): String
    private external fun cleanup()

    private var isModelLoaded = false

    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelManager = LlamaModelManager(context)
            if (!modelManager.isModelReady()) {
                modelManager.copyModelFromAssets().getOrThrow()
            }
            isModelLoaded = initModel(modelManager.getModelFile().absolutePath, 4) == 0
            return@withContext isModelLoaded
        } catch (e: Exception) {
            Log.e(TAG, "模型加载失败", e)
            return@withContext false
        }
    }

    suspend fun generateAiTitle(transcription: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (transcription.isBlank()) {
                return@withContext Result.failure(Exception("转写文本为空"))
            }

            if (!isModelLoaded) {
                if (!loadModel()) {
                    return@withContext Result.failure(Exception("LLM模型加载失败"))
                }
            }

            val truncated = transcription.take(500)  // 限制输入长度
            val rawOutput = generateTitle(truncated)
            val cleanedTitle = cleanOutput(rawOutput)

            Result.success(cleanedTitle)
        } catch (e: Exception) {
            Log.e(TAG, "AI标题生成失败", e)
            Result.failure(e)
        }
    }

    private fun cleanOutput(output: String): String {
        return output
            .removePrefix("<|im_start|>assistant\n")
            .removeSuffix("<|im_end|>")
            .trim()
            .take(20)
    }

    fun release() {
        if (isModelLoaded) {
            cleanup()
            isModelLoaded = false
        }
    }
}
```

### LlamaModelManager.kt

```kotlin
class LlamaModelManager(private val context: Context) {

    companion object {
        const val MODEL_FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    }

    fun getModelFile(): File {
        val modelsDir = File(context.filesDir, "llm_models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        return File(modelsDir, MODEL_FILE)
    }

    fun isModelReady(): Boolean {
        return getModelFile().exists() && getModelFile().length() > 100_000_000
    }

    fun copyModelFromAssets(): Result<Unit> {
        return try {
            val assetFile = context.assets.open(MODEL_FILE)
            val targetFile = getModelFile()
            assetFile.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getModelSizeBytes(): Long {
        return if (getModelFile().exists()) getModelFile().length() else 0
    }
}
```

---

## 6. 工作流集成

### RecordingService.kt 修改

在转写完成后自动生成标题：

```kotlin
private fun onTranscriptionComplete(recordingId: Long, transcriptionText: String) {
    lifecycleScope.launch(Dispatchers.IO) {
        // 生成 AI 标题
        val llamaService = LlamaService(applicationContext)
        try {
            val aiTitle = llamaService.generateAiTitle(transcriptionText).getOrNull()
            aiTitle?.let {
                database.recordingsDao().updateAiTitle(
                    recordingId,
                    it,
                    System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI标题生成失败，不影响转写结果", e)
        } finally {
            llamaService.release()
        }

        // 发送广播通知UI
        sendBroadcast(Intent(ACTION_TRANSCRIPTION_COMPLETE).apply {
            putExtra("recordingId", recordingId)
        })
    }
}
```

---

## 7. UI 变更

### RecordingsAdapter.kt

在时间后面显示 AI 标题：

```kotlin
// 在 ViewHolder 中
holder.binding.tvTime.text = formatTime(recording.timestamp)
recording.aiTitle?.let { title ->
    holder.binding.tvTime.text = "${formatTime(recording.timestamp)} · $title"
}
```

### fragment_recordings.xml

无需新增视图，复用现有的时间 TextView。

---

## 8. 错误处理与边界情况

| 场景 | 处理策略 |
|------|----------|
| 转写文本为空 | 跳过标题生成 |
| 转写文本过长 | 截取前500字符 |
| 模型文件不存在 | 记录日志，不影响转写 |
| 生成超时(>30s) | 取消并释放资源 |
| 输出包含特殊标记 | 正则清理 |
| OOM | 捕获异常，静默失败 |

**关键原则:** AI 标题生成失败不应阻塞或影响核心转写功能。

---

## 9. 文件清单

### 新增文件

```
app/src/main/cpp/llama_jni.cpp
app/src/main/java/.../service/LlamaService.kt
app/src/main/java/.../service/LlamaModelManager.kt
app/src/main/assets/qwen2.5-1.5b-instruct-q4_k_m.gguf
```

### 修改文件

```
app/src/main/cpp/CMakeLists.txt
app/src/main/java/.../data/Recording.kt
app/src/main/java/.../data/RecordingsDao.kt
app/src/main/java/.../service/RecordingService.kt
app/src/main/java/.../ui/recordings/RecordingsAdapter.kt
app/src/main/java/.../database/AppDatabase.kt (版本 2→3)
```

---

## 10. 技术规格

| 项目 | 规格 |
|------|------|
| 模型 | Qwen2.5-1.5B-Instruct-Q4_K_M.gguf |
| 模型大小 | ~1.2 GB |
| 推理引擎 | llama.cpp |
| JNI 库名 | voicerec_llama |
| 标题长度 | 5-10 中文字符 |
| 输入截断 | 500 字符 |
| 超时时间 | 30 秒 |
| 线程数 | 4 |
| 数据库版本 | 3 |
