# Whisper 离线语音识别实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将讯飞在线语音识别替换为基于 Whisper 的离线语音识别，实现完全本地化的转录功能。

**Architecture:** 创建 WhisperTranscribeService 和 WhisperModelManager 服务类，修改 Recording 表添加转录字段，更新 UI 层集成新的转录服务。使用 TFLite 运行 Whisper 模型进行本地推理。

**Tech Stack:** Kotlin, Android Room, OkHttp, TFLite, Whisper 模型

---

## Task 1: 数据库迁移 - 添加转录字段

**Files:**
- Modify: `app/src/main/java/com/example/voicerec/data/Recording.kt`
- Create: `app/src/main/java/com/example/voicerec/data/AppDatabase.kt` (add migration)

**Step 1: 修改 Recording 实体类**

在 `Recording.kt` 添加两个新字段：

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
    val transcriptionText: String? = null,   // 新增：转录结果
    val transcriptionTime: Long? = null      // 新增：转录时间戳
)
```

**Step 2: 添加数据库迁移**

在 `AppDatabase.kt` 添加版本迁移（从 version 1 到 version 2）：

```kotlin
@Database(
    entities = [Recording::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE recordings ADD COLUMN transcriptionText TEXT DEFAULT NULL"
                )
                database.execSQL(
                    "ALTER TABLE recordings ADD COLUMN transcriptionTime INTEGER DEFAULT NULL"
                )
            }
        }
    }
}
```

**Step 3: 更新 DatabaseBuilder**

修改 `RecordingRepository.kt` 中的数据库构建，添加迁移：

```kotlin
database = Room.databaseBuilder(
    context,
    AppDatabase::class.java,
    "recordings_database"
)
.addMigrations(AppDatabase.MIGRATION_1_2)
.build()
```

**Step 4: 构建验证**

Run: `./gradlew build`

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voicerec/data/Recording.kt
git add app/src/main/java/com/example/voicerec/data/AppDatabase.kt
git add app/src/main/java/com/example/voicerec/data/RecordingRepository.kt
git commit -m "feat: add transcription fields to Recording entity"
```

---

## Task 2: 创建 WhisperModelManager - 模型管理

**Files:**
- Create: `app/src/main/java/com/example/voicerec/service/WhisperModelManager.kt`

**Step 1: 创建 WhisperModelManager 类**

```kotlin
package com.example.voicerec.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink

/**
 * Whisper 模型管理器
 * 负责模型的下载、检查和管理
 */
class WhisperModelManager(private val context: Context) {

    companion object {
        private const val MODEL_DIR = "models/whisper"
        private const val MODEL_FILE = "small.tflite"
        // 使用 HuggingFace 上的 Whisper.cpp 模型
        private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
        private const val MODEL_SIZE_BYTES = 467_734_112L // ~446MB (Whisper small 实际大小)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 获取模型文件目录
     */
    private fun getModelDir(): File {
        val dir = File(context.filesDir, MODEL_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 获取模型文件
     */
    fun getModelFile(): File {
        return File(getModelDir(), MODEL_FILE)
    }

    /**
     * 检查模型是否已下载
     */
    fun isModelDownloaded(): Boolean {
        val file = getModelFile()
        return file.exists() && file.length() > 0
    }

    /**
     * 下载模型文件
     */
    suspend fun downloadModel(
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val file = getModelFile()
            if (file.exists()) {
                file.delete()
            }

            val request = Request.Builder()
                .url(MODEL_URL)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("下载失败: HTTP ${response.code}"))
            }

            val body = response.body
            if (body == null) {
                return@withContext Result.failure(Exception("响应体为空"))
            }

            val contentLength = body.contentLength()
            var downloadedBytes = 0L

            file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int

                body.byteStream().use { input ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        if (contentLength > 0) {
                            val progress = (downloadedBytes * 100 / contentLength).toInt()
                            onProgress(progress)
                        }
                    }
                }
            }

            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 删除模型文件
     */
    fun deleteModel() {
        getModelFile().delete()
    }

    /**
     * 获取模型大小（字节）
     */
    fun getModelSize(): Long {
        return if (isModelDownloaded()) {
            getModelFile().length()
        } else {
            0L
        }
    }
}
```

**Step 2: 添加网络权限**

确保 `AndroidManifest.xml` 包含：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

**Step 3: 构建验证**

Run: `./gradlew build`

**Step 4: Commit**

```bash
git add app/src/main/java/com/example/voicerec/service/WhisperModelManager.kt
git commit -m "feat: add WhisperModelManager for model download and management"
```

---

## Task 3: 创建 WhisperTranscribeService - 转录服务

**Files:**
- Create: `app/src/main/java/com/example/voicerec/service/WhisperTranscribeService.kt`

**Step 1: 添加 Whisper 依赖**

在 `app/build.gradle.kts` 添加：

```kotlin
dependencies {
    // ... 现有依赖

    // Whisper TFLite (使用 whisper.cpp 的 Android 绑定)
    implementation("com.github.ggerganov:whisper.android:1.0.0")

    // 或者使用纯 TFLite 方案
    // implementation("org.tensorflow:tensorflow-lite:2.14.0")
    // implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
}
```

添加 JitPack 仓库到 `settings.gradle.kts`：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")  // 添加这行
    }
}
```

**Step 2: 创建 WhisperTranscribeService**

```kotlin
package com.example.voicerec.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Log

/**
 * Whisper 离线语音转文字服务
 * 使用 Whisper.cpp 的 Android 绑定
 */
class WhisperTranscribeService(
    private val context: Context,
    private val modelManager: WhisperModelManager
) {

    companion object {
        private const val TAG = "WhisperTranscribe"
    }

    private var whisperContext: Long = 0  // Native context pointer

    /**
     * 转写音频文件
     * @param audioPath 音频文件路径
     * @param onProgress 进度回调
     * @return Result<String> 转写结果
     */
    suspend fun transcribe(
        audioPath: String,
        onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 1. 检查模型
            if (!modelManager.isModelDownloaded()) {
                return@withContext Result.failure(Exception("模型未下载，请先下载模型"))
            }

            onProgress("正在加载模型...")

            // 2. 加载模型 (使用 JNI 调用 whisper.cpp)
            val modelPath = modelManager.getModelFile().absolutePath
            val contextPtr = loadModel(modelPath)

            if (contextPtr == 0L) {
                return@withContext Result.failure(Exception("模型加载失败"))
            }

            whisperContext = contextPtr

            onProgress("正在处理音频...")

            // 3. 转写音频
            val audioFile = File(audioPath)
            if (!audioFile.exists()) {
                releaseModel()
                return@withContext Result.failure(Exception("音频文件不存在"))
            }

            // 4. 调用原生转写方法
            val text = transcribeAudioNative(audioPath)

            // 5. 清理资源
            releaseModel()

            if (text.isNullOrEmpty()) {
                Result.failure(Exception("转写结果为空"))
            } else {
                Result.success(text)
            }

        } catch (e: Exception) {
            Log.e(TAG, "转写失败", e)
            releaseModel()
            Result.failure(e)
        }
    }

    /**
     * 加载 Whisper 模型 (JNI)
     */
    private external fun loadModel(modelPath: String): Long

    /**
     * 转写音频文件 (JNI)
     */
    private external fun transcribeAudioNative(audioPath: String): String?

    /**
     * 释放模型资源 (JNI)
     */
    private external fun releaseModel()

    companion object {
        init {
            try {
                System.loadLibrary("whisper-android")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load whisper native library", e)
            }
        }
    }
}
```

**注意**: 上面的代码使用了 JNI 调用原生库。实际实现需要使用现成的 Whisper Android 库。推荐使用以下方案之一：

**方案 A: 使用 whisper.android 库**

```kotlin
package com.example.voicerec.service

import android.content.Context
import com.whispercpp.demo.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class WhisperTranscribeService(
    private val context: Context,
    private val modelManager: WhisperModelManager
) {

    suspend fun transcribe(
        audioPath: String,
        onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!modelManager.isModelDownloaded()) {
                return@withContext Result.failure(Exception("模型未下载"))
            }

            onProgress("正在加载模型...")

            val modelFile = modelManager.getModelFile()

            // 使用 WhisperContext
            val whisperCtx = WhisperContext.createContext(modelFile.absolutePath)
                ?: return@withContext Result.failure(Exception("模型加载失败"))

            onProgress("正在转写音频...")

            // 转写参数
            val params = WhisperContext.FullParams().apply {
                // 语言设置
                language = "zh"
                translate = false

                // 采样参数
                nThreads = 4
                offsetMs = 0
                durationMs = 0

                // 解码策略
                speedUp = false

                // 音频处理
                audioContext = 0

                // Token 相关
                maxTokens = 224
                tokenTimestamps = true
                tholdPt = 0.01f
                tholdPtsum = 0.01f

                // Mel 相关
                melNum = 80

                // 检测语音
                maxInitialTs = 0.0f
                maxSegments = 0
            }

            // 处理音频并转写
            val audioData = readAudioFile(audioPath)

            val result = whisperCtx.fullTranscribe(params, audioData)

            val text = StringBuilder()
            for (i in 0 until whisperCtx.getTextSegmentCount()) {
                val segment = whisperCtx.getTextSegment(i)
                text.append(segment.text)
            }

            whisperCtx.release()

            Result.success(text.toString().trim())

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun readAudioFile(path: String): FloatArray {
        // 读取音频文件并转换为 FloatArray (PCM)
        // 可以使用 Android MediaPlayer 或提取音频数据
        // 这里需要实现音频解码
        return floatArrayOf()
    }
}
```

**Step 3: 添加 JitPack 依赖**

在 `settings.gradle.kts` 添加：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
```

在 `app/build.gradle.kts` 添加：

```kotlin
dependencies {
    implementation("com.github.ggerganov:whisper.android:1.0.0")
}
```

**Step 4: 构建验证**

Run: `./gradlew build`

**Step 5: Commit**

```bash
git add app/build.gradle.kts
git add settings.gradle.kts
git add app/src/main/java/com/example/voicerec/service/WhisperTranscribeService.kt
git commit -m "feat: add WhisperTranscribeService for offline transcription"
```

---

## Task 4: 创建音频处理工具类

**Files:**
- Create: `app/src/main/java/com/example/voicerec/service/AudioProcessor.kt`

**Step 1: 创建 AudioProcessor 类**

```kotlin
package com.example.voicerec.service

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音频处理工具类
 * 用于将 M4A 转换为 PCM 格式供 Whisper 使用
 */
class AudioProcessor {

    companion object {
        private const val TAG = "AudioProcessor"
        private const val TARGET_SAMPLE_RATE = 16000  // Whisper 需要 16kHz
        private const val TARGET_CHANNELS = 1         // 单声道
    }

    /**
     * 将音频文件转换为 PCM FloatArray
     * @param audioPath 输入音频文件路径 (M4A/MP3/WAV 等)
     * @return FloatArray PCM 数据
     */
    fun convertToPcm(audioPath: String): FloatArray {
        val file = File(audioPath)
        if (!file.exists()) {
            throw IllegalArgumentException("文件不存在: $audioPath")
        }

        // 使用 MediaExtractor 解码音频
        val extractor = MediaExtractor()
        extractor.setDataSource(audioPath)

        // 找到音频轨道
        val audioTrackIndex = findAudioTrack(extractor)
        if (audioTrackIndex < 0) {
            extractor.release()
            throw IllegalArgumentException("未找到音频轨道")
        }

        extractor.selectTrack(audioTrackIndex)
        val format = extractor.getTrackFormat(audioTrackIndex)

        Log.d(TAG, "音频格式: $format")

        // 提取音频数据
        val pcmData = extractPcmData(extractor, format)

        extractor.release()

        return pcmData
    }

    /**
     * 查找音频轨道
     */
    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1
    }

    /**
     * 提取 PCM 数据
     */
    private fun extractPcmData(
        extractor: MediaExtractor,
        format: MediaFormat
    ): FloatArray {
        val maxBufferSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        val buffer = ByteBuffer.allocate(maxBufferSize)
        val pcmList = mutableListOf<Float>()

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize <= 0) break

            // 将字节转换为 PCM
            buffer.position(0)
            buffer.limit(sampleSize)

            // 假设输入是 16-bit PCM
            val shorts = ShortArray(sampleSize / 2)
            buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

            // 转换为 Float 并归一化到 [-1, 1]
            for (sample in shorts) {
                pcmList.add(sample / 32768.0f)
            }

            extractor.advance()
        }

        // 如果需要重采样到 16kHz (简化处理)
        // 实际项目中应使用专业的重采样库如 libswresample

        return pcmList.toFloatArray()
    }

    /**
     * 重采样音频数据
     * @param input 输入 PCM 数据
     * @param inputSampleRate 输入采样率
     * @param outputSampleRate 输出采样率
     * @return 重采样后的数据
     */
    private fun resample(
        input: FloatArray,
        inputSampleRate: Int,
        outputSampleRate: Int
    ): FloatArray {
        if (inputSampleRate == outputSampleRate) {
            return input
        }

        val ratio = inputSampleRate.toFloat() / outputSampleRate
        val outputLength = (input.size / ratio).toInt()
        val output = FloatArray(outputLength)

        for (i in 0 until outputLength) {
            val inputIndex = (i * ratio).toInt()
            output[i] = input[inputIndex]
        }

        return output
    }
}
```

**Step 2: 更新 WhisperTranscribeService 使用 AudioProcessor**

```kotlin
class WhisperTranscribeService(
    private val context: Context,
    private val modelManager: WhisperModelManager
) {
    private val audioProcessor = AudioProcessor()

    suspend fun transcribe(
        audioPath: String,
        onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!modelManager.isModelDownloaded()) {
                return@withContext Result.failure(Exception("模型未下载"))
            }

            onProgress("正在处理音频...")

            // 转换音频为 PCM
            val pcmData = audioProcessor.convertToPcm(audioPath)

            onProgress("正在加载模型...")

            val modelFile = modelManager.getModelFile()
            val whisperCtx = WhisperContext.createContext(modelFile.absolutePath)
                ?: return@withContext Result.failure(Exception("模型加载失败"))

            onProgress("正在转写...")

            // 使用 PCM 数据转写
            val params = WhisperContext.FullParams().apply {
                language = "zh"
                translate = false
                nThreads = 4
            }

            val result = whisperCtx.fullTranscribe(params, pcmData)

            val text = StringBuilder()
            for (i in 0 until whisperCtx.getTextSegmentCount()) {
                val segment = whisperCtx.getTextSegment(i)
                text.append(segment.text)
            }

            whisperCtx.release()

            Result.success(text.toString().trim())

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

**Step 3: 构建验证**

Run: `./gradlew build`

**Step 4: Commit**

```bash
git add app/src/main/java/com/example/voicerec/service/AudioProcessor.kt
git add app/src/main/java/com/example/voicerec/service/WhisperTranscribeService.kt
git commit -m "feat: add AudioProcessor for audio format conversion"
```

---

## Task 5: 更新 RecordingsViewModel - 添加转录方法

**Files:**
- Modify: `app/src/main/java/com/example/voicerec/ui/recordings/RecordingsViewModel.kt`

**Step 1: 添加转录相关方法**

在 `RecordingsViewModel.kt` 添加：

```kotlin
class RecordingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RecordingRepository(application)

    // ... 现有代码 ...

    /**
     * 转写录音
     */
    suspend fun transcribeRecording(recording: Recording): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val modelManager = WhisperModelManager(getApplication())

                // 检查模型是否已下载
                if (!modelManager.isModelDownloaded()) {
                    return@withContext Result.failure(ModelNotDownloadedException())
                }

                val transcribeService = WhisperTranscribeService(
                    getApplication(),
                    modelManager
                )

                val result = transcribeService.transcribe(recording.filePath) { progress ->
                    // 可以在这里更新进度状态
                }

                // 如果成功，保存到数据库
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
    }

    /**
     * 检查模型是否已下载
     */
    fun isModelDownloaded(): Boolean {
        val modelManager = WhisperModelManager(getApplication())
        return modelManager.isModelDownloaded()
    }

    /**
     * 获取模型信息
     */
    fun getModelInfo(): ModelInfo {
        val modelManager = WhisperModelManager(getApplication())
        return ModelInfo(
            isDownloaded = modelManager.isModelDownloaded(),
            sizeBytes = modelManager.getModelSize()
        )
    }
}

/**
 * 模型未下载异常
 */
class ModelNotDownloadedException : Exception("模型未下载，请先下载模型")

/**
 * 模型信息
 */
data class ModelInfo(
    val isDownloaded: Boolean,
    val sizeBytes: Long
)
```

**Step 2: 添加 Repository 更新方法**

在 `RecordingRepository.kt` 添加：

```kotlin
/**
 * 更新录音
 */
suspend fun updateRecording(recording: Recording) {
    recordingDao.update(recording)
}
```

在 `RecordingDao.kt` 添加：

```kotlin
@Update
suspend fun update(recording: Recording)
```

**Step 3: 添加导入**

在 `RecordingsViewModel.kt` 顶部添加：

```kotlin
import com.example.voicerec.service.WhisperModelManager
import com.example.voicerec.service.WhisperTranscribeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

**Step 4: 构建验证**

Run: `./gradlew build`

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/voicerec/ui/recordings/RecordingsViewModel.kt
git add app/src/main/java/com/example/voicerec/data/RecordingRepository.kt
git add app/src/main/java/com/example/voicerec/data/RecordingDao.kt
git commit -m "feat: add transcription support to ViewModel"
```

---

## Task 6: 更新 RecordingsFragment - 集成 Whisper 服务

**Files:**
- Modify: `app/src/main/java/com/example/voicerec/ui/recordings/RecordingsFragment.kt`

**Step 1: 删除讯飞导入**

删除或替换：

```kotlin
// 删除这行
import com.example.voicerec.service.IFlytekTranscribeService
```

**Step 2: 更新 transcribeRecording 方法**

```kotlin
private fun transcribeRecording(recording: Recording) {
    // 检查模型是否已下载
    if (!viewModel.isModelDownloaded()) {
        showModelDownloadDialog(recording)
        return
    }

    // 如果已有转录结果，直接显示
    if (!recording.transcriptionText.isNullOrEmpty()) {
        showTranscriptionResult(
            recording.transcriptionText!!,
            recording.transcriptionTime
        )
        return
    }

    // 显示进度对话框
    val progressDialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle("语音转文字")
        .setMessage("正在处理...")
        .setCancelable(false)
        .create()

    progressDialog.show()

    lifecycleScope.launch {
        val result = viewModel.transcribeRecording(recording)

        progressDialog.dismiss()

        result.fold(
            onSuccess = { text ->
                showTranscriptionResult(text, System.currentTimeMillis())
            },
            onFailure = { error ->
                val message = when (error) {
                    is ModelNotDownloadedException -> "模型未下载"
                    else -> error.message ?: "转写失败"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        )
    }
}
```

**Step 3: 添加模型下载对话框**

```kotlin
private fun showModelDownloadDialog(recording: Recording? = null) {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle("下载模型")
        .setMessage("首次使用需要下载 Whisper 模型 (~250MB)，建议在 WiFi 环境下下载。\n\n是否立即下载？")
        .setPositiveButton("下载") { _, _ ->
            downloadModel(recording)
        }
        .setNegativeButton("取消", null)
        .show()
}

private fun downloadModel(recording: Recording? = null) {
    // 创建进度对话框
    val progressDialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle("下载模型")
        .setMessage("正在下载... 0%")
        .setCancelable(false)
        .create()

    progressDialog.show()

    val modelManager = WhisperModelManager(requireContext())

    lifecycleScope.launch {
        val result = modelManager.downloadModel { progress ->
            activity?.runOnUiThread {
                progressDialog.setMessage("正在下载... ${progress}%")
            }
        }

        progressDialog.dismiss()

        result.fold(
            onSuccess = {
                Toast.makeText(context, "模型下载完成", Toast.LENGTH_SHORT).show()
                // 如果有待转录的录音，开始转录
                recording?.let { transcribeRecording(it) }
            },
            onFailure = { error ->
                Toast.makeText(context, "下载失败: ${error.message}", Toast.LENGTH_LONG).show()
            }
        )
    }
}
```

**Step 4: 更新 showTranscriptionResult 方法**

```kotlin
private fun showTranscriptionResult(text: String, timestamp: Long?) {
    val timeText = if (timestamp != null) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        "\n\n转录时间: ${sdf.format(Date(timestamp))}"
    } else {
        ""
    }

    MaterialAlertDialogBuilder(requireContext())
        .setTitle("转写结果")
        .setMessage(text + timeText)
        .setPositiveButton("复制") { _, _ ->
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("转写结果", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("关闭", null)
        .show()
}
```

**Step 5: 添加导入**

```kotlin
import android.content.ClipData
import android.content.ClipboardManager
import com.example.voicerec.service.WhisperModelManager
```

**Step 6: 构建验证**

Run: `./gradlew build`

**Step 7: Commit**

```bash
git add app/src/main/java/com/example/voicerec/ui/recordings/RecordingsFragment.kt
git commit -m "feat: integrate Whisper transcription service"
```

---

## Task 7: 更新 RecordingsAdapter - 显示转录状态

**Files:**
- Modify: `app/src/main/java/com/example/voicerec/ui/recordings/RecordingsAdapter.kt`

**Step 1: 更新 RecordingViewHolder 的 bind 方法**

```kotlin
inner class RecordingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val titleText: TextView = view.findViewById(R.id.tv_recording_title)
    private val subtitleText: TextView = view.findViewById(R.id.tv_recording_subtitle)
    private val moreIcon: ImageView = view.findViewById(R.id.iv_more)
    private val transcribeButton: TextView = view.findViewById(R.id.btn_transcribe)

    fun bind(item: TreeItem.RecordingItem) {
        val recording = item.recording
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date(recording.timestamp))

        titleText.text = time
        subtitleText.text = "${viewModel.formatDuration(recording.durationMs)} · " +
                viewModel.formatFileSize(recording.fileSizeBytes)"

        // 根据是否已转录更新按钮文本
        transcribeButton.text = if (recording.transcriptionText != null) {
            "查看"
        } else {
            "转文字"
        }

        itemView.setOnClickListener {
            onItemClick(recording)
        }

        itemView.setOnLongClickListener {
            onItemLongClick(recording)
            true
        }

        moreIcon.setOnClickListener {
            onItemLongClick(recording)
        }

        transcribeButton.setOnClickListener {
            onTranscribe(recording)
        }
    }
}
```

**Step 2: 构建验证**

Run: `./gradlew build`

**Step 3: Commit**

```bash
git add app/src/main/java/com/example/voicerec/ui/recordings/RecordingsAdapter.kt
git commit -m "feat: update adapter to show transcription status"
```

---

## Task 8: 删除讯飞相关代码

**Files:**
- Delete: `app/src/main/java/com/example/voicerec/service/IFlytekTranscribeService.kt`
- Modify: `app/build.gradle.kts`

**Step 1: 删除 IFlytekTranscribeService 文件**

```bash
rm app/src/main/java/com/example/voicerec/service/IFlytekTranscribeService.kt
```

**Step 2: 清理 build.gradle.kts 依赖**

删除不再需要的依赖：

```kotlin
dependencies {
    // ... 保留其他依赖 ...

    // 删除这些（仅用于讯飞）
    // implementation("com.squareup.retrofit2:retrofit:2.9.0")
    // implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    // implementation("com.google.code.gson:gson:2.10.1")

    // 保留 OkHttp（用于模型下载）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 添加 Whisper
    implementation("com.github.ggerganov:whisper.android:1.0.0")
}
```

**Step 3: 清理 RecordingsFragment 导入**

确保删除了讯飞的导入：

```kotlin
// 删除这行
import com.example.voicerec.service.IFlytekTranscribeService
```

**Step 4: 构建验证**

Run: `./gradlew clean build`

**Step 5: Commit**

```bash
git rm app/src/main/java/com/example/voicerec/service/IFlytekTranscribeService.kt
git add app/build.gradle.kts
git add app/src/main/java/com/example/voicerec/ui/recordings/RecordingsFragment.kt
git commit -m "refactor: remove iFlytek transcription service"
```

---

## Task 9: 测试与验证

**Files:**
- Test: 手动测试

**Step 1: 安装测试**

Run: `./gradlew installDebug`

**Step 2: 验证数据库迁移**

- 启动应用，检查现有录音是否正常显示
- 检查数据库版本是否正确升级

**Step 3: 测试模型下载**

- 点击"转文字"，检查是否提示下载模型
- 确认下载进度显示正常
- 下载完成后检查模型文件是否存在

**Step 4: 测试转录功能**

- 选择一条录音，点击"转文字"
- 检查进度对话框显示
- 验证转录结果是否显示
- 检查复制功能是否正常

**Step 5: 测试已转录录音**

- 已转录的录音应显示"查看"按钮
- 点击"查看"应直接显示之前的结果
- 结果应包含转录时间

**Step 6: 测试错误处理**

- 无网络状态下（离线）转录应正常工作
- 无效的音频文件应显示错误
- 存储空间不足时应有提示

**Step 7: 记录测试结果**

创建测试报告文档记录发现的问题。

---

## 实现注意事项

### 音频处理

1. **M4A 格式处理**: Android 原生不直接支持 M4A 解码，需要使用 `MediaExtractor` + `MediaCodec` 或第三方库

2. **重采样**: Whisper 需要 16kHz 单声道 PCM，可能需要重采样

3. **推荐库**:
   - 使用 `whisper.android` 库（已包含音频处理）
   - 或使用 `ffmpeg-kit` 进行音频转换

### 模型文件

1. **模型来源**: 从 HuggingFace 下载 `ggml-small.bin` 格式

2. **存储位置**: 使用 `context.filesDir` 确保隐私和自动清理

3. **断点续传**: 建议实现 HTTP Range 请求支持断点续传

### 性能优化

1. **多线程**: Whisper 支持多线程处理，可设置 `nThreads`

2. **模型复用**: 避免每次转录都重新加载模型

3. **内存管理**: 及时释放 native 资源

### 替代方案

如果 `whisper.android` 集成困难，可以考虑：

1. **TensorFlow Lite**: 使用官方 Whisper TFLite 模型
2. **Vosk**: 替代的离线语音识别库
3. **云端 API**: 作为后备方案保留在线服务

---

## 参考资源

- [whisper.cpp](https://github.com/ggerganov/whisper.cpp)
- [whisper.android](https://github.com/ggerganov/whisper.android)
- [OpenAI Whisper](https://github.com/openai/whisper)
- [Android MediaExtractor](https://developer.android.com/guide/topics/media/media-formats)
